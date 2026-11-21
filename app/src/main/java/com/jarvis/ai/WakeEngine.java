package com.jarvis.ai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Silent, offline "Jarvis" wake-word listener (small streaming speech model through sherpa-onnx).
 * Unlike Android's SpeechRecognizer it needs no internet, makes no ding sounds and keeps working
 * with the screen off inside the foreground service.
 */
final class WakeEngine {
    private static final String URL_MODEL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2";

    private static final Pattern HIT = Pattern.compile(
            "\\b(jarvis|jarvish|jarviz|jarvice|jervis|garvis|harvis|charvis|travis|"
                    + "jar\\s?vis|jer\\s?vis|gar\\s?vis|har\\s?vis|jar\\s?viz|jar\\s?vish)\\b");

    interface Hit {
        void onWake(String heard);
    }

    interface Progress {
        void on(String message);
    }

    private static OnlineRecognizer recognizer;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static Thread thread;
    static volatile String lastError = "";

    private WakeEngine() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "wakeasr");
    }

    static boolean installed(Context c) {
        return new File(dir(c), "ok.flag").exists();
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }

    private static final class Counting extends FilterInputStream {
        long n;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) n++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) n += r;
            return r;
        }
    }

    /** Downloads and unpacks the small model (about 40 MB kept). Blocking. */
    static void download(Context c, Progress p) throws Exception {
        File root = dir(c);
        deleteAll(root);
        root.mkdirs();
        String base = root.getCanonicalPath() + File.separator;

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_MODEL).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code / 100 != 2) throw new IOException("Download failed, HTTP " + code);
        long total = conn.getContentLengthLong();
        Counting counting = new Counting(new BufferedInputStream(conn.getInputStream(), 1 << 16));
        long lastReport = 0;

        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(counting))) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tar.getNextTarEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.startsWith("./")) name = name.substring(2);
                int slash = name.indexOf('/');
                String rel = slash >= 0 ? name.substring(slash + 1) : name;
                if (rel.isEmpty() || rel.contains("/")) continue;
                boolean keep = rel.equals("tokens.txt")
                        || (rel.endsWith(".onnx") && (rel.startsWith("decoder") || rel.contains("int8")));
                if (!keep) continue;
                File out = new File(root, rel);
                if (!out.getCanonicalPath().startsWith(base)) continue;
                try (OutputStream os = new FileOutputStream(out)) {
                    int r;
                    while ((r = tar.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        if (p != null && counting.n - lastReport > (4L << 20)) {
                            lastReport = counting.n;
                            int pct = total > 0 ? (int) (counting.n * 100 / total) : 0;
                            p.on("Downloading silent wake engine... " + pct + "%");
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (find(root, "encoder") == null || find(root, "decoder") == null
                || find(root, "joiner") == null || !new File(root, "tokens.txt").exists()) {
            deleteAll(root);
            throw new IOException("The wake engine pack was incomplete.");
        }
        new File(root, "ok.flag").createNewFile();
        if (p != null) p.on("Silent wake engine ready.");
    }

    /** Finds a model file by prefix, preferring the int8 build (decoder: the plain one). */
    private static File find(File root, String prefix) {
        File[] fs = root.listFiles();
        if (fs == null) return null;
        File plain = null, int8 = null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith(prefix) || !n.endsWith(".onnx")) continue;
            if (n.contains("int8")) int8 = f;
            else plain = f;
        }
        if (prefix.equals("decoder")) return plain != null ? plain : int8;
        return int8 != null ? int8 : plain;
    }

    private static synchronized OnlineRecognizer engine(Context c) {
        if (recognizer != null) return recognizer;
        File d = dir(c);
        OnlineTransducerModelConfig t = OnlineTransducerModelConfig.builder()
                .setEncoder(find(d, "encoder").getAbsolutePath())
                .setDecoder(find(d, "decoder").getAbsolutePath())
                .setJoiner(find(d, "joiner").getAbsolutePath())
                .build();
        OnlineModelConfig m = OnlineModelConfig.builder()
                .setTransducer(t)
                .setTokens(new File(d, "tokens.txt").getAbsolutePath())
                .setNumThreads(1)
                .setDebug(false)
                .build();
        OnlineRecognizerConfig cfg = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(m)
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                .setEndpointConfig(EndpointConfig.builder().build())
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build();
        recognizer = new OnlineRecognizer(cfg);
        return recognizer;
    }

    static boolean isRunning() {
        return running;
    }

    /** Starts listening on a background thread. Returns false (with lastError) if it cannot. */
    static synchronized boolean start(final Context c, final Hit cb) {
        stop();
        if (!installed(c)) {
            lastError = "not downloaded";
            return false;
        }
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lastError = "no microphone permission";
            return false;
        }
        final OnlineRecognizer rec;
        try {
            rec = engine(c);
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(rec, cb);
            }
        }, "jarvis-wake");
        thread.start();
        return true;
    }

    static synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void loop(OnlineRecognizer rec, final Hit cb) {
        AudioRecord ar = null;
        OnlineStream st = null;
        String hit = null;
        try {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000 * 2));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                lastError = "microphone busy";
                running = false;
                notifyFail(cb);
                return;
            }
            ar.startRecording();
            st = rec.createStream();
            short[] buf = new short[1600];
            ArrayDeque<float[]> pre = new ArrayDeque<>();
            long quietSince = System.currentTimeMillis();
            boolean active = false;
            long lastCheck = 0;
            while (running) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) {
                    lastError = "mic read " + n;
                    break;
                }
                float[] f = new float[n];
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    f[i] = buf[i] / 32768f;
                    sum += f[i] * f[i];
                }
                double rms = Math.sqrt(sum / n);
                long now = System.currentTimeMillis();
                if (rms > 0.006) quietSince = now;
                boolean voiced = now - quietSince < 1500;
                if (!voiced) {
                    if (active) {
                        active = false;
                        rec.reset(st);
                    }
                    pre.addLast(f);
                    while (pre.size() > 4) pre.removeFirst();
                    continue;
                }
                if (!active) {
                    active = true;
                    for (float[] p : pre) st.acceptWaveform(p, 16000);
                    pre.clear();
                }
                st.acceptWaveform(f, 16000);
                while (rec.isReady(st)) rec.decode(st);
                if (now - lastCheck > 120) {
                    lastCheck = now;
                    String text = rec.getResult(st).getText();
                    if (text != null) {
                        String low = text.toLowerCase(java.util.Locale.ROOT).trim();
                        if (!low.isEmpty() && HIT.matcher(low).find()) {
                            hit = low;
                            break;
                        }
                    }
                }
                if (rec.isEndpoint(st)) rec.reset(st);
            }
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (ar != null) {
                try {
                    ar.stop();
                } catch (Exception ignored) {
                }
                ar.release();
            }
            if (st != null) {
                try {
                    st.release();
                } catch (Throwable ignored) {
                }
            }
        }
        final boolean wasRunning = running;
        running = false;
        final String h = hit;
        if (h != null) {
            MAIN.post(new Runnable() {
                @Override public void run() { cb.onWake(h); }
            });
        } else if (wasRunning) {
            notifyFail(cb);
        }
    }

    /** The engine died by itself (mic taken by another app, etc). Reported as an empty wake text. */
    private static void notifyFail(final Hit cb) {
        MAIN.post(new Runnable() {
            @Override public void run() { cb.onWake(null); }
        });
    }
}
