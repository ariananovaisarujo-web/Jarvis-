package com.jarvis.ai;

import android.content.Context;
import android.content.Intent;
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
        } catch (Exception e) {
            Cb c = cur;
            cur = null;
            destroy();
            if (c != null) c.onError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    void cancel() {
        cur = null;
        if (sr != null) {
            try {
                sr.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    void destroy() {
        cur = null;
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
            Cb c = cur;
            cur = null;
            if (c == null) return;
            ArrayList<String> l = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            c.onResult(l == null ? new ArrayList<String>() : l);
        }

        @Override
        public void onError(int error) {
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
