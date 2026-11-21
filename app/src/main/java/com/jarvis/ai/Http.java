package com.jarvis.ai;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Http {
    private Http() {}

    static final class Err extends Exception {
        final int code;

        Err(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static String get(String url) throws IOException, Err {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        try {
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    static String postJson(String url, String body, String apiKey) throws IOException, Err {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", apiKey);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    static String postJsonHeader(String url, String body, String headerName, String headerValue)
            throws IOException, Err {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty(headerName, headerValue);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    /** POST a JSON body and return the raw response bytes (used for ElevenLabs audio). */
    static byte[] postForBytes(String url, String body, String headerName, String headerValue)
            throws IOException, Err {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "audio/mpeg");
        c.setRequestProperty(headerName, headerValue);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream err = c.getErrorStream();
                throw new Err(code, extractMessage(err == null ? "" : readAll(err)));
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String read(HttpURLConnection c) throws IOException, Err {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "" : readAll(in);
        if (code >= 400) throw new Err(code, extractMessage(s));
        return s;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static String extractMessage(String s) {
        try {
            JSONObject e = new JSONObject(s).optJSONObject("error");
            if (e != null) {
                String m = e.optString("message", "");
                if (!m.isEmpty()) return m;
            }
        } catch (Exception ignored) {
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
