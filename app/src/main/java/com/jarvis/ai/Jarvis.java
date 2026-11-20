package com.jarvis.ai;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant core. One instance lives for the whole process and is shared by the
 * activity (UI) and the wake-word service, so Jarvis keeps working with the screen off.
 * All state changes happen on the main thread.
 */
final class Jarvis {

    enum State { IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        void onState(State s, boolean wakeActive);
        void onLog(String who, String text);
        void onLevel(float db);
        void onPartial(String text);
    }

    /** True while MainActivity is on screen. */
    static volatile boolean visible = false;

    private static Jarvis inst;

    static synchronized Jarvis get(Context c) {
        if (inst == null) inst = new Jarvis(c.getApplicationContext());
        return inst;
    }

    private static final Pattern WAKE = Pattern.compile(
            "(jarvis|jarvish|jervis|garvis|जार्विस|जारविस|जर्विस|ज़ार्विस|जार्विश)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    final Context ctx;
    final Prefs prefs;
    final Tools tools;
    final Gemini gemini;
    private final Stt stt;
    private final Tts tts;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String[]> history = new ArrayList<>();

    private State state = State.IDLE;
    private boolean wakeEnabled;
    private boolean wakeListening;
    private int gen;

    private final Runnable wakeRun = new Runnable() {
        @Override
        public void run() {
            if (wakeEnabled && state == State.IDLE && !wakeListening) listen(true, false);
        }
    };

    private Jarvis(Context c) {
        ctx = c;
        prefs = new Prefs(c);
        tools = new Tools(c, prefs);
        gemini = new Gemini(prefs, tools);
        stt = new Stt(c);
        tts = new Tts(c, prefs);
        tts.notice = msg -> log("system", msg);
    }

    // ------------------------------------------------------------------ public API (any thread)

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    State state() { return state; }
    boolean wakeActive() { return wakeEnabled; }
    List<String[]> history() { return new ArrayList<>(history); }

    /** Mic button / reactor tap: start listening, or stop whatever is going on. */
    void talk() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state != State.IDLE) {
                    stopNow();
                    return;
                }
                gen++;
                listen(false, false);
            }
        });
    }

    /** Typed command. */
    void submit(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                String t = text.trim();
                if (!t.isEmpty()) handle(t, false);
            }
        });
    }

    void stopAll() {
        main.post(new Runnable() {
            @Override public void run() { stopNow(); }
        });
    }

    void setWake(final boolean on) {
        main.post(new Runnable() {
            @Override
            public void run() {
                wakeEnabled = on;
                setState(state);
                if (on) {
                    if (state == State.IDLE) scheduleWake(0);
                } else {
                    main.removeCallbacks(wakeRun);
                    if (wakeListening) {
                        stt.cancel();
                        wakeListening = false;
                    }
                }
            }
        });
    }

    void clearConversation() {
        bg.execute(new Runnable() {
            @Override public void run() { gemini.reset(); }
        });
        main.post(new Runnable() {
            @Override public void run() { history.clear(); }
        });
    }

    // ------------------------------------------------------------------ internals (main thread)

    private void setState(State s) {
        state = s;
        for (Listener l : listeners) l.onState(s, wakeEnabled);
    }

    private void log(String who, String text) {
        history.add(new String[]{who, text});
        while (history.size() > 200) history.remove(0);
        for (Listener l : listeners) l.onLog(who, text);
    }

    private boolean hasMic() {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void stopNow() {
        gen++;
        stt.cancel();
        tts.stop();
        wakeListening = false;
        main.removeCallbacks(wakeRun);
        toIdle();
    }

    private void toIdle() {
        setState(State.IDLE);
        if (wakeEnabled) scheduleWake(300);
    }

    private void scheduleWake(long delayMs) {
        main.removeCallbacks(wakeRun);
        if (wakeEnabled) main.postDelayed(wakeRun, delayMs);
    }

    /**
     * @param wake true for the background "say Jarvis" loop, false for a real command
     * @param cont true when this listen follows a reply, so silence ends the chat quietly
     */
    private void listen(final boolean wake, final boolean cont) {
        if (!hasMic()) {
            log("system", "I need microphone permission, sir. Open Settings and grant it.");
            wakeEnabled = false;
            toIdle();
            return;
        }
        if (!wake) {
            tts.stop();
            setState(State.LISTENING);
        }
        wakeListening = wake;
        stt.start(prefs.sttLang(), wake, !wake, new Stt.Cb() {
            @Override
            public void onRms(float db) {
                if (!wake) for (Listener l : listeners) l.onLevel(db);
            }

            @Override
            public void onPartial(String text) {
                if (!wake) for (Listener l : listeners) l.onPartial(text);
            }

            @Override
            public void onResult(List<String> alts) {
                wakeListening = false;
                if (wake) onWakeResult(alts);
                else onHeard(alts.isEmpty() ? "" : alts.get(0));
            }

            @Override
            public void onError(int code) {
                wakeListening = false;
                onSttError(code, wake, cont);
            }
        });
    }

    private void onHeard(String text) {
        text = text.trim();
        if (text.isEmpty()) {
            toIdle();
            return;
        }
        handle(text, true);
    }

    private void onWakeResult(List<String> alts) {
        for (String a : alts) {
            Matcher m = WAKE.matcher(a);
            if (m.find()) {
                String cmd = a.substring(m.end()).replaceFirst("^[\\s,.:!?\\-]+", "").trim();
                wakeHit(cmd);
                return;
            }
        }
        scheduleWake(100);
    }

    private void wakeHit(String cmd) {
        if (cmd.length() >= 3) {
            handle(cmd, true);
            return;
        }
        gen++;
        final int my = gen;
        setState(State.SPEAKING);
        String n = prefs.name();
        tts.speak("Yes, " + n + "?", new Runnable() {
            @Override
            public void run() {
                if (my == gen) listen(false, true);
            }
        });
    }

    private void onSttError(int code, boolean wake, boolean cont) {
        if (wake) {
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wakeEnabled = false;
                log("system", "Wake word stopped: microphone permission is missing.");
                setState(State.IDLE);
                return;
            }
            long d = code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500
                    : (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ? 150
                    : 1200;
            scheduleWake(d);
            return;
        }
        boolean quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (!(quiet && cont)) {
            if (quiet) log("system", "I didn't catch that.");
            else if (code == -1) log("system", "Speech recognition isn't available. Install or enable the Google app.");
            else if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || code == SpeechRecognizer.ERROR_SERVER) log("system", "Speech recognition needs an internet connection.");
            else log("system", "Speech recognition error " + code + ".");
        }
        toIdle();
    }

    private void handle(final String text, final boolean voice) {
        final int my = ++gen;
        stt.cancel();
        tts.stop();
        wakeListening = false;
        log("you", text);
        setState(State.THINKING);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Gemini.Result r = gemini.ask(text);
                main.post(new Runnable() {
                    @Override public void run() { onReply(r, voice, my); }
                });
            }
        });
    }

    private void onReply(Gemini.Result r, final boolean voice, final int my) {
        if (my != gen) return;
        final boolean end = tools.consumeEnd();
        if (r.error != null) {
            log("system", r.error);
            setState(State.SPEAKING);
            tts.speak(r.error, new Runnable() {
                @Override public void run() { if (my == gen) toIdle(); }
            });
            return;
        }
        log("jarvis", r.text);
        setState(State.SPEAKING);
        tts.speak(r.text, new Runnable() {
            @Override
            public void run() {
                if (my != gen) return;
                if (!end && voice && prefs.conv()) listen(false, true);
                else toIdle();
            }
        });
    }
}
