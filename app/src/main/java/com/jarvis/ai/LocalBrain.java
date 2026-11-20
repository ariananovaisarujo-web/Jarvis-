package com.jarvis.ai;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the common phone commands WITHOUT internet (Hindi / Hinglish / English).
 * It is tried first, so simple commands are instant and work offline. Anything it does not
 * recognise goes to Gemini when online.
 */
final class LocalBrain {

    static final class Match {
        final String tool;
        final JSONObject args;
        final String say;
        final boolean speakResult;

        Match(String tool, JSONObject args, String say, boolean speakResult) {
            this.tool = tool;
            this.args = args;
            this.say = say;
            this.speakResult = speakResult;
        }
    }

    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "jarvis", "जार्विस", "जारविस", "please", "plz", "pls", "ok", "okay", "hey", "zara", "jara",
            "yaar", "bhai", "sir", "ji", "ज़रा", "जरा", "प्लीज"));

    private static final Pattern CALL1 = Pattern.compile("^(?:call|dial)\\s+(.+)$");
    private static final Pattern CALL2 = Pattern.compile(
            "^(.+?)\\s+(?:ko|को)\\s+(?:call|phone|फोन|कॉल)(?:\\s+(?:karo|kar do|kar de|lagao|laga do|milao|mila do|करो|कर दो|लगाओ|मिलाओ))?$");
    private static final Pattern OPEN1 = Pattern.compile("^(?:open|launch|start|run)\\s+(.+)$");
    private static final Pattern OPEN2 = Pattern.compile(
            "^(.+?)\\s+(?:kholo|khol do|khol de|khol dijiye|open karo|open kar do|open kar de|chalu karo|chalu kar do|chalao|chala do|start karo|खोलो|खोल दो|खोल दे|ओपन करो|ओपन कर दो|चालू करो|चलाओ)$");
    private static final Pattern NUM = Pattern.compile("(\\d{1,3})");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?");
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(hours?|hrs?|ghante|ghanta|घंटे|घंटा|minutes?|mins?|min|मिनट|seconds?|secs?|sec|सेकंड)");

    static boolean looksFailed(String out) {
        return out.startsWith("Failed") || out.startsWith("Permission denied") || out.startsWith("No ")
                || out.startsWith("This phone has") || out.startsWith("Accessibility")
                || out.startsWith("Nothing") || out.startsWith("Cannot") || out.startsWith("BLOCKED");
    }

    // ------------------------------------------------------------------ helpers

    private static String norm(String raw) {
        String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[.,!?।\"'“”‘’:;]", " ")
                .replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String tok : s.split(" ")) {
            if (tok.isEmpty() || FILLER.contains(tok)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    /** True if any word/phrase appears as whole tokens in t. */
    private static boolean w(String t, String... words) {
        String hay = " " + t + " ";
        for (String x : words) if (hay.contains(" " + x + " ")) return true;
        return false;
    }

    private static int tokens(String t) {
        return t.split(" ").length;
    }

    private static JSONObject o(Object... kv) {
        return J.obj(kv);
    }

    private static Match m(String tool, JSONObject args, String say) {
        return new Match(tool, args, say, false);
    }

    private static Match info(String tool) {
        return new Match(tool, new JSONObject(), "", true);
    }

    private static String cleanName(String n) {
        n = n.trim();
        n = n.replaceAll("^(?:my|the)\\s+", "");
        n = n.replaceAll("\\s+(?:app|application|ऐप|एप)$", "");
        return n.trim();
    }

    // ------------------------------------------------------------------ matching

    Match match(String raw, String name) {
        String t = norm(raw);
        if (t.isEmpty()) return null;
        String ok = "Done, " + name + ".";

        // end of conversation
        if (t.matches("bas|bas karo|bas itna hi|bas itna|stop|thank you|thanks|thanks a lot|that is all|that s all|"
                + "shukriya|dhanyawad|theek hai bas|ruko|बस|बस करो|धन्यवाद|शुक्रिया|रुको")) {
            return m("end_conversation", o(), "Okay.");
        }

        // flashlight
        if (w(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट")) {
            boolean off = w(t, "off", "band", "bujha", "bujhao", "बंद", "बुझा", "बुझाओ");
            return m("flashlight", o("on", !off), off ? "Torch off." : "Torch on.");
        }

        // volume
        if (w(t, "volume", "वॉल्यूम", "awaaz", "आवाज़", "आवाज")) {
            Matcher n = NUM.matcher(t);
            if (n.find()) {
                int p = Math.min(100, Integer.parseInt(n.group(1)));
                return m("volume", o("percent", p), "Volume " + p + " percent.");
            }
            if (w(t, "max", "full", "फुल")) return m("volume", o("percent", 100), "Volume at maximum.");
            if (w(t, "mute", "म्यूट")) return m("volume", o("percent", 0), "Muted.");
            if (w(t, "up", "badhao", "badha", "increase", "zyada", "ज्यादा", "बढ़ाओ", "बढ़ा")) {
                return m("volume", o("direction", "up"), "Volume up.");
            }
            if (w(t, "down", "kam", "ghatao", "ghata", "decrease", "कम", "घटाओ", "घटा")) {
                return m("volume", o("direction", "down"), "Volume down.");
            }
        }

        // information
        if (w(t, "battery", "बैटरी")) return info("battery_status");
        if (t.matches(".*(what time|time kya|kitne baje|abhi time|time batao|current time|samay kya|टाइम क्या|कितने बजे).*")) {
            return info("current_time");
        }

        // media
        if (t.matches("next|next song|next track|next gaana|next gana|agla gaana|agla gana|agla song|अगला गाना")) {
            return m("media_control", o("action", "next"), "Next.");
        }
        if (t.matches("previous|previous song|previous track|pichla gaana|pichla gana|pichhla gaana|पिछला गाना")) {
            return m("media_control", o("action", "previous"), "Previous.");
        }
        if (t.matches("pause|pause music|pause song|play|resume|play music|music play|music pause|gaana rok do|"
                + "gana rok do|gaana chalao|gana chalao|gaana pause karo|गाना रोक दो|गाना चलाओ")) {
            return m("media_control", o("action", "play_pause"), ok);
        }

        // alarm and timer
        if (w(t, "alarm", "अलार्म")) {
            Matcher c = CLOCK.matcher(t);
            if (c.find()) {
                int h = Integer.parseInt(c.group(1));
                int min = c.group(2) == null ? 0 : Integer.parseInt(c.group(2));
                if (w(t, "pm", "shaam", "sham", "evening", "raat", "night", "dopahar", "afternoon", "शाम", "रात", "दोपहर")
                        && h < 12) h += 12;
                if (w(t, "am", "subah", "morning", "सुबह") && h == 12) h = 0;
                if (h <= 23 && min <= 59) {
                    return m("set_alarm", o("hour", h, "minute", min, "label", "Jarvis"),
                            String.format(Locale.ENGLISH, "Alarm set for %02d:%02d.", h, min));
                }
            }
            return null;
        }
        if (w(t, "timer", "टाइमर")) {
            Matcher d = DURATION.matcher(t);
            if (d.find()) {
                int v = Integer.parseInt(d.group(1));
                String u = d.group(2);
                int secs;
                if (u.startsWith("h") || u.startsWith("ghant") || u.startsWith("घंट")) secs = v * 3600;
                else if (u.startsWith("s") || u.startsWith("सेक")) secs = v;
                else secs = v * 60;
                return m("set_timer", o("seconds", secs, "label", "Jarvis"), "Timer started.");
            }
            return null;
        }

        // navigation keys (need the accessibility service)
        if (t.matches("home|go home|home screen|home jao|ghar jao|home par jao|होम|होम जाओ")) {
            return m("press_key", o("action", "home"), ok);
        }
        if (t.matches("back|go back|back karo|back jao|peeche jao|piche jao|wapas jao|पीछे जाओ|वापस जाओ")) {
            return m("press_key", o("action", "back"), ok);
        }
        if (t.matches("recent|recents|recent apps|show recent apps|recent apps dikhao")) {
            return m("press_key", o("action", "recents"), ok);
        }
        if (t.matches("notifications|notification panel|.*notifications? (dikhao|kholo|open|show|dekho).*")) {
            return m("press_key", o("action", "notifications"), ok);
        }
        if (t.contains("screenshot")) return m("press_key", o("action", "screenshot"), "Screenshot taken.");

        // calls
        Matcher c1 = CALL1.matcher(t);
        if (c1.matches()) {
            String who = cleanName(c1.group(1));
            if (!who.isEmpty()) return m("call_contact", o("who", who), "Calling " + who + ".");
        }
        Matcher c2 = CALL2.matcher(t);
        if (c2.matches()) {
            String who = cleanName(c2.group(1));
            if (!who.isEmpty()) return m("call_contact", o("who", who), "Calling " + who + ".");
        }

        // lock / unlock the phone
        boolean phoneWord = w(t, "phone", "mobile", "screen", "फोन", "मोबाइल", "स्क्रीन");
        boolean isApp = w(t, "app", "ऐप", "एप");
        if (phoneWord && !isApp && tokens(t) <= 6) {
            if (w(t, "open", "unlock", "kholo", "khol", "on", "खोलो", "खोल", "अनलॉक", "ऑन")) {
                return m("unlock_phone", o(), "Unlocking.");
            }
            if (w(t, "lock", "band", "off", "बंद", "लॉक")) {
                return m("lock_screen", o(), "Locking.");
            }
        }
        if (t.startsWith("unlock")) return m("unlock_phone", o(), "Unlocking.");

        // open an app
        Matcher o1 = OPEN1.matcher(t);
        Matcher o2 = OPEN2.matcher(t);
        String app = null;
        if (o1.matches()) app = cleanName(o1.group(1));
        else if (o2.matches()) app = cleanName(o2.group(1));
        if (app != null && !app.isEmpty()) {
            if (app.equals("settings") || app.equals("setting") || app.equals("सेटिंग्स")) {
                return m("open_settings", o("page", "main"), "Opening settings.");
            }
            return m("open_app", o("name", app), "Opening " + app + ".");
        }
        return null;
    }
}
