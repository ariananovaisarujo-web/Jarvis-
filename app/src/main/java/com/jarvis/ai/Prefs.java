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

    boolean neuralOn() { return sp.getBoolean("neuralOn", true); }

    String raw(String k) { return sp.getString(k, ""); }

    /** gemini, groq, openrouter, mistral, nvidia or custom. */
    String provider() { return sp.getString("provider", "gemini"); }
    String altKey() { return keyFor(provider()); }
    String keyFor(String p) { return sp.getString("key_" + p, "").trim(); }
    String altBase() { return baseFor(provider()); }
    String altModel() { return modelFor(provider()); }

    String baseFor(String p) {
        switch (p) {
            case "groq": return "https://api.groq.com/openai/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "mistral": return "https://api.mistral.ai/v1";
            case "nvidia": return "https://integrate.api.nvidia.com/v1";
            case "custom": {
                String u = sp.getString("customBase", "").trim();
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                return u;
            }
            default: return "https://api.groq.com/openai/v1";
        }
    }

    String modelFor(String p) {
        String m = sp.getString("model_" + p, "").trim();
        if (!m.isEmpty()) return m;
        switch (p) {
            case "openrouter": return "openrouter/free";
            case "mistral": return "mistral-small-latest";
            case "nvidia": return "meta/llama-3.3-70b-instruct";
            case "custom": return "";
            default: return "openai/gpt-oss-20b";
        }
    }

    /** Providers other than Gemini that have a key, selected one first. */
    java.util.List<String> altChain() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String sel = provider();
        if (!sel.equals("gemini") && !keyFor(sel).isEmpty()) out.add(sel);
        for (String p : new String[]{"groq", "nvidia", "openrouter", "mistral", "custom"}) {
            if (!out.contains(p) && !keyFor(p).isEmpty() && !baseFor(p).isEmpty()) out.add(p);
        }
        return out;
    }

    /** jarvis, friday, desi_male or desi_female (phone voices, free and offline). */
    String voicePreset() { return sp.getString("voicePreset", "jarvis"); }

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
