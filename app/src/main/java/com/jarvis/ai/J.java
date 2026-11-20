package com.jarvis.ai;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tiny JSON helpers so the rest of the code does not need try/catch everywhere. */
final class J {
    private J() {}

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put((String) kv[i], kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object it : items) a.put(it);
        return a;
    }
}
