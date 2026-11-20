package com.jarvis.ai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Speech output. Uses ElevenLabs when a key and voice ID are set (falling back to the phone's
 * own voice if ElevenLabs fails), otherwise the Android TTS engine with a deeper British voice.
 */
final class Tts {
    private static final Object END = new Object();

    private static final class Failure {
        final String message;

        Failure(String m) {
            message = m;
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newCachedThreadPool();
    private TextToSpeech tts;
    private volatile boolean ready;
    private String lastId = "";
    private Runnable done;
    private int counter;

    // ElevenLabs playback state (main thread, except egen which the fetch thread reads)
    private volatile int egen;
    private MediaPlayer mp;

    /** Optional hook so the UI can show "ElevenLabs failed" messages. Called on the main thread. */
    Consumer<String> notice;

    Tts(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = status == TextToSpeech.SUCCESS;
                if (!ready || tts == null) return;
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onDone(String utteranceId) { finished(utteranceId); }
                    @Override public void onError(String utteranceId) { finished(utteranceId); }
                });
            }
        });
    }

    private void finished(String id) {
        if (!id.equals(lastId)) return;
        final Runnable r = done;
        done = null;
        if (r != null) main.post(r);
    }

    void stop() {
        done = null;
        lastId = "";
        egen++;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    void speak(String raw, Runnable onDone) {
        String text = clean(raw);
        if (text.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        if (prefs.elevenOn() && Net.online(ctx)) speakEleven(text, onDone);
        else speakSystem(text, onDone, 0);
    }

    // ------------------------------------------------------------------ ElevenLabs

    private void speakEleven(final String text, final Runnable onDone) {
        stop();
        final int my = egen;
        final List<String> parts = chunks(text);
        final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
        final String key = prefs.elevenKey();
        final String voice = prefs.elevenVoice();
        final String model = prefs.elevenModel();

        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (String part : parts) {
                        if (my != egen) return;
                        q.put(fetch(part, key, voice, model));
                    }
                    q.put(END);
                } catch (Http.Err e) {
                    q.offer(new Failure("ElevenLabs error " + e.code + ": " + e.getMessage()));
                } catch (Exception e) {
                    q.offer(new Failure("ElevenLabs could not be reached."));
                }
            }
        });
        pump(q, my, parts, 0, onDone);
    }

    private File fetch(String part, String key, String voice, String model) throws Exception {
        String url = "https://api.elevenlabs.io/v1/text-to-speech/" + voice + "?output_format=mp3_44100_128";
        String body = J.obj("text", part, "model_id", model).toString();
        byte[] audio = Http.postForBytes(url, body, "xi-api-key", key);
        File f = File.createTempFile("tts", ".mp3", ctx.getCacheDir());
        FileOutputStream os = new FileOutputStream(f);
        os.write(audio);
        os.close();
        return f;
    }

    /** Plays queued clips one after another; on failure the rest is spoken by the phone's own voice. */
    private void pump(final LinkedBlockingQueue<Object> q, final int my, final List<String> parts,
                      final int idx, final Runnable onDone) {
        if (my != egen) return;
        Object o = q.poll();
        if (o == null) {
            main.postDelayed(new Runnable() {
                @Override public void run() { pump(q, my, parts, idx, onDone); }
            }, 50);
            return;
        }
        if (o == END) {
            if (onDone != null) onDone.run();
            return;
        }
        if (o instanceof Failure) {
            if (notice != null) notice.accept(((Failure) o).message + " Using the phone's voice instead.");
            StringBuilder rest = new StringBuilder();
            for (int i = idx; i < parts.size(); i++) rest.append(parts.get(i)).append(' ');
            speakSystem(rest.toString().trim(), onDone, 0);
            return;
        }
        final File f = (File) o;
        play(f, new Runnable() {
            @Override
            public void run() {
                f.delete();
                pump(q, my, parts, idx + 1, onDone);
            }
        });
    }

    private void play(File f, final Runnable onComplete) {
        try {
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            m.setDataSource(f.getAbsolutePath());
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer x) { x.start(); }
            });
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer x) {
                    x.release();
                    if (mp == x) mp = null;
                    onComplete.run();
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer x, int what, int extra) {
                    x.release();
                    if (mp == x) mp = null;
                    onComplete.run();
                    return true;
                }
            });
            mp = m;
            m.prepareAsync();
        } catch (Exception e) {
            onComplete.run();
        }
    }

    /** Short pieces so the first sentence starts playing quickly. */
    private static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : text.split("(?<=[.!?।])\\s+")) {
            if (cur.length() > 0 && cur.length() + s.length() > 280) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(s).append(' ');
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    // ------------------------------------------------------------------ system voice

    private void speakSystem(final String text, final Runnable onDone, final int tries) {
        if (!ready) {
            if (tries < 8) {
                main.postDelayed(new Runnable() {
                    @Override public void run() { speakSystem(text, onDone, tries + 1); }
                }, 250);
            } else if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (text.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        applyVoice(hasDevanagari(text));
        done = onDone;
        List<String> pieces = split(text);
        for (int i = 0; i < pieces.size(); i++) {
            String id = "u" + (++counter);
            if (i == pieces.size() - 1) lastId = id;
            tts.speak(pieces.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
        }
    }

    private void applyVoice(boolean hindi) {
        Locale loc = hindi ? new Locale("hi", "IN") : Locale.forLanguageTag(prefs.ttsLang());
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        } else if (!hindi && "GB".equals(loc.getCountry())) {
            pickMaleVoice(loc);
        }
        tts.setSpeechRate(prefs.rate());
        tts.setPitch(prefs.pitch());
    }

    private void pickMaleVoice(Locale loc) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            for (Voice v : voices) {
                String n = v.getName().toLowerCase(Locale.ROOT);
                if (loc.equals(v.getLocale()) && !v.isNetworkConnectionRequired()
                        && !n.contains("female")
                        && (n.contains("gbb") || n.contains("gbd") || n.contains("rjs") || n.contains("male"))) {
                    tts.setVoice(v);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean hasDevanagari(String s) {
        int dev = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x0900 && ch <= 0x097F) dev++;
        }
        return dev > 2;
    }

    private static String clean(String s) {
        return s.replaceAll("https?://\\S+", "link")
                .replaceAll("[*#`_~|]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int max = 3000;
        while (text.length() > max) {
            int cut = text.lastIndexOf(' ', max);
            if (cut < 1) cut = max;
            out.add(text.substring(0, cut));
            text = text.substring(cut).trim();
        }
        if (!text.isEmpty()) out.add(text);
        return out;
    }
}
