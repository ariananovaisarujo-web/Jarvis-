package com.jarvis.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    String apiKey() { return sp.getString("apiKey", "").trim(); }
    String model() {
        String m = sp.getString("model", "gemini-3.5-flash").trim();
        return (m.isEmpty() || m.equals("gemini-3.8-flash")) ? "gemini-3.5-flash" : m;
    }

    /** gemini, groq, openrouter or mistral. */
    String provider() { return sp.getString("provider", "gemini"); }
    String altKey() { return sp.getString("altKey", "").trim(); }

    String altBase() {
        switch (provider()) {
            case "groq": return "https://api.groq.com/openai/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "mistral": return "https://api.mistral.ai/v1";
            default: return "https://api.groq.com/openai/v1";
        }
    }

    String altModel() {
        String m = sp.getString("altModel", "").trim();
        if (!m.isEmpty()) return m;
        switch (provider()) {
            case "openrouter": return "openrouter/free";
            case "mistral": return "mistral-small-latest";
            default: return "openai/gpt-oss-120b";
        }
    }
    String name() { return sp.getString("name", "sir").trim(); }
    String city() { return sp.getString("city", "").trim(); }
    String sttLang() { return sp.getString("sttLang", "en-IN"); }
    String ttsLang() { return sp.getString("ttsLang", "en-GB"); }
    String countryCode() { return sp.getString("cc", "91").trim(); }
    boolean wake() { return sp.getBoolean("wake", false); }
    boolean conv() { return sp.getBoolean("conv", true); }
    float rate() { return sp.getFloat("rate", 1.0f); }
    float pitch() { return sp.getFloat("pitch", 0.85f); }

    String elevenKey() { return sp.getString("elevenKey", "").trim(); }
    String elevenVoice() { return sp.getString("elevenVoice", "").trim(); }
    String elevenModel() { return sp.getString("elevenModel", "eleven_flash_v2_5").trim(); }
    boolean elevenOn() { return !elevenKey().isEmpty() && !elevenVoice().isEmpty(); }

    // ---- owner lock ----

    String codeApp() { String a = sp.getString("codeApp", "Pyroid").trim(); return a.isEmpty() ? "Pyroid" : a; }
    String ownerName() { return sp.getString("owner", "Shudhanshu").trim(); }
    boolean lockOn() { return sp.getBoolean("lock", true); }
    boolean lock() { return lockOn(); }
    boolean passSet() { return sp.getInt("passLen", 0) > 0 && !sp.getString("passHash", "").isEmpty(); }

    private static String normPass(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    void setPass(String phrase) {
        String n = normPass(phrase);
        sp.edit().putInt("passLen", n.length()).putString("passHash", n.isEmpty() ? "" : sha(n)).apply();
    }

    /** True if the spoken text contains the owner's passphrase. */
    boolean checkPass(String spoken) {
        if (!passSet()) return false;
        String n = normPass(spoken);
        int len = sp.getInt("passLen", 0);
        String want = sp.getString("passHash", "");
        for (int i = 0; i + len <= n.length(); i++) {
            if (sha(n.substring(i, i + len)).equals(want)) return true;
        }
        return false;
    }

    void setString(String k, String v) { sp.edit().putString(k, v).apply(); }
    void setBool(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    void setFloat(String k, float v) { sp.edit().putFloat(k, v).apply(); }

    // ---- long-term memory (facts the user asked Jarvis to remember) ----

    List<String> memory() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("memory", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveMemory(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp.edit().putString("memory", a.toString()).apply();
    }

    void addMemory(String fact) {
        List<String> list = memory();
        list.add(fact.trim());
        while (list.size() > 60) list.remove(0);
        saveMemory(list);
    }

    boolean removeMemory(String fragment) {
        String f = fragment.toLowerCase(Locale.ROOT).trim();
        List<String> list = memory();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).toLowerCase(Locale.ROOT).contains(f)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) saveMemory(list);
        return changed;
    }

    void clearMemory() { sp.edit().remove("memory").apply(); }
}
