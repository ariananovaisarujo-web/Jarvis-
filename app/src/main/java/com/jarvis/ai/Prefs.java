package com.jarvis.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    String apiKey() { return sp.getString("apiKey", "").trim(); }
    String model() { return sp.getString("model", "gemini-3.8-flash").trim(); }
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
