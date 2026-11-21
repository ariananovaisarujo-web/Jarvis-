package com.jarvis.ai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Voice ID: checks that a spoken command really comes from the owner (on-phone, offline). */
final class VoicePrint {
    static final int RATE = 16000;
    private static final String URL_MODEL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx";

    enum Status { MATCH, MISMATCH, UNAVAILABLE }

    static final class Result {
        Status status = Status.UNAVAILABLE;
        float score;
        String note = "";
    }

    private static SpeakerEmbeddingExtractor ex;

    private VoicePrint() {
    }

    static File model(Context c) {
        return new File(new File(c.getFilesDir(), "spk"), "model.onnx");
    }

    static boolean modelReady(Context c) {
        if (model(c).length() > 1000000L) return true;
        // The model ships inside the app: unpack it once.
        try {
            File f = model(c);
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".part");
            try (InputStream in = c.getAssets().open("spk/model.onnx"); OutputStream os = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
            }
            if (tmp.length() > 1000000L && tmp.renameTo(f)) return true;
            tmp.delete();
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean enrolled(Prefs p) {
        return !p.raw("voiceprint").isEmpty();
    }

    static void downloadModel(Context c) throws IOException {
        File f = model(c);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_MODEL).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        try {
            if (conn.getResponseCode() / 100 != 2) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = new BufferedInputStream(conn.getInputStream()); OutputStream os = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
            }
        } finally {
            conn.disconnect();
        }
        if (tmp.length() < 1000000L || !tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("The voice-ID model download was incomplete.");
        }
    }

    private static synchronized SpeakerEmbeddingExtractor extractor(Context c) {
        if (ex == null) {
            ex = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(model(c).getAbsolutePath()).setNumThreads(2).setDebug(false).build());
        }
        return ex;
    }

    /** Records the microphone for the given time. Blocking; needs RECORD_AUDIO and a free microphone. */
    static short[] record(int ms) throws Exception {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IOException("Microphone is busy or not allowed.");
        }
        short[] out = new short[RATE * ms / 1000];
        int n = 0;
        try {
            r.startRecording();
            while (n < out.length) {
                int got = r.read(out, n, Math.min(4096, out.length - n));
                if (got <= 0) break;
                n += got;
            }
        } finally {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Keeps the louder frames (speech) and returns null if there is too little of it. */
    private static float[] speechOnly(short[] pcm) {
        if (pcm == null || pcm.length < RATE / 2) return null;
        int frame = 480;
        int frames = pcm.length / frame;
        double[] rms = new double[frames];
        double peak = 0;
        for (int i = 0; i < frames; i++) {
            double s = 0;
            for (int j = 0; j < frame; j++) {
                double v = pcm[i * frame + j] / 32768.0;
                s += v * v;
            }
            rms[i] = Math.sqrt(s / frame);
            peak = Math.max(peak, rms[i]);
        }
        if (peak < 0.004) return null;
        double thr = Math.max(0.006, peak * 0.18);
        float[] tmp = new float[pcm.length];
        int n = 0;
        int kept = 0;
        for (int i = 0; i < frames; i++) {
            if (rms[i] < thr) continue;
            kept++;
            for (int j = 0; j < frame; j++) tmp[n++] = pcm[i * frame + j] / 32768f;
        }
        if (kept < 30) return null;
        return java.util.Arrays.copyOf(tmp, n);
    }

    /** Normalised voice embedding, or null if there is not enough clear speech. */
    static float[] embed(Context c, short[] pcm) {
        float[] f = speechOnly(pcm);
        if (f == null) return null;
        SpeakerEmbeddingExtractor e = extractor(c);
        OnlineStream s = e.createStream();
        try {
            s.acceptWaveform(f, RATE);
            s.inputFinished();
            if (!e.isReady(s)) return null;
            return normalize(e.compute(s));
        } finally {
            s.release();
        }
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n < 1e-9) return v;
        float[] o = new float[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (float) (v[i] / n);
        return o;
    }

    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return (float) d;
    }

    static String encode(float[] v) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeInt(v.length);
        for (float x : v) d.writeFloat(x);
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    static float[] decode(String s) {
        try {
            DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(Base64.decode(s, Base64.NO_WRAP)));
            float[] v = new float[d.readInt()];
            for (int i = 0; i < v.length; i++) v[i] = d.readFloat();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static void saveEnrollment(Prefs p, java.util.List<float[]> embs) throws IOException {
        float[] sum = null;
        for (float[] e : embs) {
            if (sum == null) sum = new float[e.length];
            for (int i = 0; i < e.length; i++) sum[i] += e[i];
        }
        if (sum == null) throw new IOException("No usable voice samples.");
        p.setString("voiceprint", encode(normalize(sum)));
    }

    static Result verify(Context c, Prefs p, short[] audio) {
        Result r = new Result();
        try {
            if (!modelReady(c)) {
                r.note = "voice-ID model missing";
                return r;
            }
            float[] ref = decode(p.raw("voiceprint"));
            if (ref == null) {
                r.note = "no saved voice";
                return r;
            }
            float[] e = embed(c, audio);
            if (e == null) {
                r.note = "not enough clear audio";
                return r;
            }
            r.score = cosine(ref, e);
            r.status = r.score >= p.voiceThreshold() ? Status.MATCH : Status.MISMATCH;
        } catch (Throwable t) {
            r.note = t.getClass().getSimpleName();
        }
        return r;
    }
}
