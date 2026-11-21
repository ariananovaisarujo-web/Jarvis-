package com.jarvis.ai;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/** Thin wrapper around Android's SpeechRecognizer. Everything runs on the main thread. */
final class Stt {

    interface Cb {
        void onRms(float db);
        void onPartial(String text);
        void onResult(List<String> alternatives);
        void onError(int code);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private Cb cur;

    /** When true, the microphone is also captured for voice-ID while the recognizer listens. */
    volatile boolean capture;
    private AudioRecord rec;
    private Thread recThread;
    private volatile boolean recOn;
    private final short[] ring = new short[16000 * 15];
    private int ringLen;
    private short[] lastAudio;

    /** Audio of the last listening session (16 kHz mono), or null. */
    synchronized short[] takeAudio() {
        short[] a = lastAudio;
        lastAudio = null;
        return a;
    }

    private void startCapture() {
        stopCapture();
        synchronized (this) {
            lastAudio = null;
        }
        if (!capture || ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            final AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return;
            }
            r.startRecording();
            rec = r;
            recOn = true;
            ringLen = 0;
            recThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    short[] buf = new short[2048];
                    while (recOn) {
                        int n = r.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        synchronized (ring) {
                            if (ringLen + n > ring.length) {
                                int drop = ringLen + n - ring.length;
                                System.arraycopy(ring, drop, ring, 0, ringLen - drop);
                                ringLen -= drop;
                            }
                            System.arraycopy(buf, 0, ring, ringLen, n);
                            ringLen += n;
                        }
                    }
                }
            });
            recThread.start();
        } catch (Exception e) {
            rec = null;
        }
    }

    private void stopCapture() {
        AudioRecord r = rec;
        if (r == null) return;
        recOn = false;
        rec = null;
        try {
            r.stop();
        } catch (Exception ignored) {
        }
        try {
            if (recThread != null) recThread.join(300);
        } catch (InterruptedException ignored) {
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
        synchronized (ring) {
            short[] snap = java.util.Arrays.copyOf(ring, ringLen);
            synchronized (this) {
                lastAudio = snap;
            }
        }
    }

    Stt(Context ctx) {
        this.ctx = ctx;
    }

    void start(String lang, boolean preferOffline, boolean partial, Cb cb) {
        cancel();
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            cb.onError(-1);
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(listener);
        }
        cur = cb;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        if (preferOffline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
        try {
            sr.startListening(i);
            startCapture();
        } catch (Exception e) {
            Cb c = cur;
            cur = null;
            destroy();
            if (c != null) c.onError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    void cancel() {
        cur = null;
        stopCapture();
        if (sr != null) {
            try {
                sr.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    void destroy() {
        cur = null;
        stopCapture();
        if (sr != null) {
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onRmsChanged(float rmsdB) {
            Cb c = cur;
            if (c != null) c.onRms(rmsdB);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            Cb c = cur;
            if (c == null || partialResults == null) return;
            ArrayList<String> l = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) c.onPartial(l.get(0));
        }

        @Override
        public void onResults(Bundle results) {
            stopCapture();
            Cb c = cur;
            cur = null;
            if (c == null) return;
            ArrayList<String> l = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            c.onResult(l == null ? new ArrayList<String>() : l);
        }

        @Override
        public void onError(int error) {
            stopCapture();
            Cb c = cur;
            cur = null;
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                main.post(new Runnable() {
                    @Override public void run() { if (cur == null) destroy(); }
                });
            }
            if (c != null) c.onError(error);
        }
    };
}
