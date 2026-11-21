package com.jarvis.ai;

import android.content.Context;

import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Natural on-phone voices (Kokoro neural TTS through sherpa-onnx). Free and offline once the
 * model (about 130 MB) has been downloaded one time.
 */
final class Neural {
    private static final String URL_MODEL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2";
    private static final String TOP = "kokoro-int8-multi-lang-v1_0/";

    private static OfflineTts engine;

    interface Progress {
        void on(String message);
    }

    private Neural() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "kokoro");
    }

    static boolean installed(Context c) {
        return new File(dir(c), "ok.flag").exists();
    }

    static synchronized void remove(Context c) {
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
            engine = null;
        }
        deleteAll(dir(c));
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

    /** Downloads and unpacks the model. Blocking; call from a background thread. */
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
                String name = e.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (!name.startsWith(TOP)) continue;
                String rel = name.substring(TOP.length());
                if (rel.isEmpty() || e.isDirectory()) continue;
                boolean keep = rel.equals("model.int8.onnx") || rel.equals("voices.bin") || rel.equals("tokens.txt")
                        || rel.equals("lexicon-us-en.txt") || rel.equals("lexicon-gb-en.txt")
                        || rel.startsWith("espeak-ng-data/");
                if (!keep) continue;
                File out = new File(root, rel);
                if (!out.getCanonicalPath().startsWith(base)) continue;
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int r;
                    while ((r = tar.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        if (p != null && counting.n - lastReport > (4L << 20)) {
                            lastReport = counting.n;
                            int pct = total > 0 ? (int) (counting.n * 100 / total) : 0;
                            p.on("Downloading HD voice... " + pct + "%");
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!new File(root, "model.int8.onnx").exists() || !new File(root, "voices.bin").exists()) {
            deleteAll(root);
            throw new IOException("The voice pack was incomplete.");
        }
        new File(root, "ok.flag").createNewFile();
        if (p != null) p.on("HD voice ready.");
    }

    static synchronized OfflineTts engine(Context c) {
        if (engine != null) return engine;
        File d = dir(c);
        OfflineTtsKokoroModelConfig k = OfflineTtsKokoroModelConfig.builder()
                .setModel(new File(d, "model.int8.onnx").getAbsolutePath())
                .setVoices(new File(d, "voices.bin").getAbsolutePath())
                .setTokens(new File(d, "tokens.txt").getAbsolutePath())
                .setLexicon(new File(d, "lexicon-us-en.txt").getAbsolutePath())
                .setDataDir(new File(d, "espeak-ng-data").getAbsolutePath())
                .build();
        OfflineTtsModelConfig m = OfflineTtsModelConfig.builder()
                .setKokoro(k)
                .setNumThreads(4)
                .setDebug(false)
                .build();
        engine = new OfflineTts(OfflineTtsConfig.builder().setModel(m).build());
        return engine;
    }

    /** Speaker IDs in the Kokoro v1.0 voice table. */
    static int sid(String preset, boolean hindi) {
        switch (preset) {
            case "friday": return hindi ? 31 : 21;      // hf_alpha / bf_emma
            case "desi_male": return 33;                 // hm_omega
            case "desi_female": return 31;               // hf_alpha
            case "us_male": return hindi ? 34 : 16;      // hm_psi / am_michael
            case "us_female": return hindi ? 32 : 3;     // hf_beta / af_heart
            default: return hindi ? 33 : 26;             // jarvis: hm_omega / bm_george
        }
    }

    static String lang(String preset, boolean hindi) {
        if (hindi) return "hi";
        if (preset.equals("jarvis") || preset.equals("friday")) return "en-gb";
        return "en-us";
    }

    static GenerationConfig config(String preset, boolean hindi, float speed) {
        GenerationConfig g = new GenerationConfig();
        g.setSid(sid(preset, hindi));
        g.setSpeed(speed);
        g.setSilenceScale(0.2f);
        Map<String, String> extra = new HashMap<>();
        extra.put("lang", lang(preset, hindi));
        g.setExtra(extra);
        return g;
    }

    /** Short pieces so the first one is spoken quickly. */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : text.split("(?<=[.!?।;:,])\\s+")) {
            cur.append(s).append(' ');
            if (cur.length() >= 40) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }
}
