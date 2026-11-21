package com.jarvis.ai;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Brain for OpenAI-compatible providers (Groq, OpenRouter, Mistral). Same job as Gemini.java:
 * chat plus the tool-calling loop that lets Jarvis operate the phone.
 */
final class OpenAiChat {
    private final Prefs prefs;
    private final Tools tools;
    private final JSONArray msgs = new JSONArray();

    OpenAiChat(Prefs prefs, Tools tools) {
        this.prefs = prefs;
        this.tools = tools;
    }

    synchronized void reset() {
        while (msgs.length() > 0) msgs.remove(0);
    }

    private JSONArray toolDefs() {
        JSONArray out = new JSONArray();
        JSONArray decls = tools.declarations();
        for (int i = 0; i < decls.length(); i++) {
            JSONObject d = decls.optJSONObject(i);
            if (d == null) continue;
            JSONObject params = d.optJSONObject("parameters");
            if (params == null) params = J.obj("type", "object", "properties", new JSONObject());
            out.put(J.obj("type", "function", "function", J.obj(
                    "name", d.optString("name"),
                    "description", d.optString("description"),
                    "parameters", params)));
        }
        return out;
    }

    synchronized Gemini.Result ask(String userText, String system) {
        Gemini.Result res = new Gemini.Result();
        java.util.List<String> chain = prefs.altChain();
        if (chain.isEmpty()) {
            res.error = "Sir, no API key is set for the chosen brain. Add one in Settings.";
            return res;
        }
        int ci = 0;
        String prov = chain.get(0);
        final int mark = msgs.length();
        msgs.put(J.obj("role", "user", "content", userText));
        trim();
        JSONArray defs = toolDefs();
        String last = "";

        for (int step = 0; step < 12; step++) {
            JSONArray all = new JSONArray();
            all.put(J.obj("role", "system", "content", system));
            for (int i = 0; i < msgs.length(); i++) all.put(msgs.opt(i));
            JSONObject req = J.obj("model", prefs.modelFor(prov), "messages", all,
                    "tools", defs, "tool_choice", "auto", "temperature", 0.8, "max_tokens", 400);

            String body;
            try {
                body = Http.postJsonHeader(prefs.baseFor(prov) + "/chat/completions", req.toString(),
                        "Authorization", "Bearer " + prefs.keyFor(prov));
            } catch (Http.Err e) {
                if (ci + 1 < chain.size()) {
                    prov = chain.get(++ci);
                    step--;
                    continue;
                }
                truncate(mark);
                res.error = friendly(e);
                return res;
            } catch (Exception e) {
                truncate(mark);
                res.error = "Sir, internet nahi chal raha. Net on kar dijiye, phir main kar dunga.";
                return res;
            }

            JSONObject msg;
            try {
                JSONArray ch = new JSONObject(body).optJSONArray("choices");
                msg = (ch == null || ch.length() == 0) ? null : ch.optJSONObject(0).optJSONObject("message");
            } catch (Exception e) {
                msg = null;
            }
            if (msg == null) {
                truncate(mark);
                res.error = "The model sent back nothing usable, sir.";
                return res;
            }

            String content = msg.isNull("content") ? "" : msg.optString("content", "");
            JSONArray calls = msg.optJSONArray("tool_calls");
            boolean hasCalls = calls != null && calls.length() > 0;
            JSONObject asst = J.obj("role", "assistant");
            try {
                asst.put("content", (content.isEmpty() && hasCalls) ? JSONObject.NULL : content);
                if (hasCalls) asst.put("tool_calls", calls);
            } catch (Exception ignored) {
            }
            msgs.put(asst);
            if (!content.isEmpty()) last = content;
            if (!hasCalls) break;

            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                String id = call.optString("id");
                JSONObject fn = call.optJSONObject("function");
                String name = fn == null ? "" : fn.optString("name");
                String argStr = fn == null ? "{}" : fn.optString("arguments", "{}");
                JSONObject args;
                try {
                    args = new JSONObject(argStr.trim().isEmpty() ? "{}" : argStr);
                } catch (Exception e) {
                    args = new JSONObject();
                }
                String out;
                try {
                    out = tools.execute(name, args);
                } catch (Throwable t) {
                    out = "Failed: " + t.getMessage();
                }
                msgs.put(J.obj("role", "tool", "tool_call_id", id, "content", out));
            }
        }

        String t = last.replace("**", "").replace("`", "").trim();
        res.text = t.isEmpty() ? "Done." : t;
        return res;
    }

    private static String friendly(Http.Err e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e.code == 429) return "I'm being rate limited, sir. Give me a minute, or switch provider or model in Settings.";
        if (e.code == 401 || e.code == 403) return "That provider rejected my API key, sir. Please check it in Settings.";
        if (e.code == 404) return "That model name was not found, sir. Change the model in Settings.";
        if (e.code >= 500) return "The provider's servers are having trouble, sir. Try again shortly.";
        if (m.length() > 160) m = m.substring(0, 160);
        return "Server error " + e.code + ": " + m;
    }

    private void trim() {
        while (msgs.length() > 1 && (msgs.length() > 40 || !"user".equals(msgs.optJSONObject(0).optString("role")))) {
            msgs.remove(0);
        }
    }

    private void truncate(int mark) {
        while (msgs.length() > mark) msgs.remove(msgs.length() - 1);
    }
}
