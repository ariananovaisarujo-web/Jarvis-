package com.jarvis.ai;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Everything Jarvis can actually DO on the phone. Each tool is exposed to Gemini as a function. */
final class Tools {
    private final Context c;
    private final Prefs prefs;
    private volatile boolean endRequested;
    private boolean launched;

    Tools(Context c, Prefs prefs) {
        this.c = c;
        this.prefs = prefs;
    }

    boolean consumeEnd() {
        boolean e = endRequested;
        endRequested = false;
        return e;
    }

    // ------------------------------------------------------------------ declarations

    private static JSONObject s(String desc) { return J.obj("type", "string", "description", desc); }
    private static JSONObject i(String desc) { return J.obj("type", "integer", "description", desc); }
    private static JSONObject b(String desc) { return J.obj("type", "boolean", "description", desc); }

    private static JSONObject en(String desc, String... values) {
        JSONArray vals = new JSONArray();
        for (String v : values) vals.put(v);
        return J.obj("type", "string", "description", desc, "enum", vals);
    }

    private static JSONObject fn(String name, String desc, JSONObject props, String... required) {
        JSONObject f = J.obj("name", name, "description", desc);
        if (props != null) {
            JSONObject params = J.obj("type", "object", "properties", props);
            try {
                if (required.length > 0) {
                    JSONArray req = new JSONArray();
                    for (String r : required) req.put(r);
                    params.put("required", req);
                }
                f.put("parameters", params);
            } catch (Exception ignored) {
            }
        }
        return f;
    }

    JSONArray declarations() {
        JSONArray a = new JSONArray();
        a.put(fn("open_app", "Open an installed app by name.",
                J.obj("name", s("App name as shown on the phone, e.g. YouTube, WhatsApp, Camera, Settings")), "name"));
        a.put(fn("call_contact", "Place a phone call to a saved contact or a phone number.",
                J.obj("who", s("Contact name or phone number")), "who"));
        a.put(fn("send_sms", "Send an SMS text message right away.",
                J.obj("to", s("Contact name or phone number"), "message", s("The message text")), "to", "message"));
        a.put(fn("whatsapp_message", "Open a WhatsApp chat with a prefilled message. The user still taps send.",
                J.obj("to", s("Contact name or phone number"), "message", s("The message text")), "to"));
        a.put(fn("set_alarm", "Set an alarm on the phone.",
                J.obj("hour", i("Hour, 0 to 23"), "minute", i("Minute, 0 to 59"), "label", s("Optional label")),
                "hour", "minute"));
        a.put(fn("set_timer", "Start a countdown timer.",
                J.obj("seconds", i("Duration in seconds"), "label", s("Optional label")), "seconds"));
        a.put(fn("flashlight", "Turn the flashlight on or off.",
                J.obj("on", b("true to turn on, false to turn off")), "on"));
        a.put(fn("web_search", "Search Google in the browser.",
                J.obj("query", s("What to search for")), "query"));
        a.put(fn("open_url", "Open a web address in the browser.",
                J.obj("url", s("The web address")), "url"));
        a.put(fn("play_music", "Play a song, artist or playlist in a music app.",
                J.obj("query", s("Song, artist or playlist")), "query"));
        a.put(fn("media_control", "Control whatever media is currently playing.",
                J.obj("action", en("What to do", "play_pause", "next", "previous", "stop")), "action"));
        a.put(fn("navigate", "Start turn-by-turn navigation in Google Maps.",
                J.obj("destination", s("Place or address")), "destination"));
        a.put(fn("volume", "Set or change the media volume. Give either percent or direction.",
                J.obj("percent", i("Absolute level from 0 to 100"),
                        "direction", en("Relative change", "up", "down"))));
        a.put(fn("battery_status", "Get the battery level and charging state.", null));
        a.put(fn("current_time", "Get the current date and time.", null));
        a.put(fn("weather", "Get the current weather and today's forecast for a city.",
                J.obj("city", s("City name. Leave empty for the user's home city."))));
        a.put(fn("open_settings", "Open a phone settings screen.",
                J.obj("page", en("Which screen", "wifi", "bluetooth", "display", "sound", "battery",
                        "location", "apps", "airplane", "main")), "page"));
        a.put(fn("open_camera", "Open the camera app.", null));
        a.put(fn("compose_email", "Open an email draft ready to send.",
                J.obj("to", s("Recipient email address"), "subject", s("Subject"), "body", s("Body text"))));
        a.put(fn("remember", "Save a lasting fact or preference about the user for future conversations.",
                J.obj("fact", s("The fact, written as a short sentence")), "fact"));
        a.put(fn("forget", "Delete a remembered fact.",
                J.obj("fact", s("Keywords identifying the fact to forget")), "fact"));
        a.put(fn("end_conversation", "Call when the user is finished and no further listening is needed.", null));
        return a;
    }

    // ------------------------------------------------------------------ execution

    String execute(String name, JSONObject a) {
        launched = false;
        String out;
        try {
            out = run(name, a);
        } catch (SecurityException e) {
            out = "Permission denied: " + e.getMessage() + ". Ask the user to grant it in Jarvis settings.";
        } catch (Exception e) {
            out = "Failed: " + e.getMessage();
        }
        if (launched && !Jarvis.visible && !Settings.canDrawOverlays(c)) {
            out += " NOTE: the app is in the background and 'Display over other apps' is not granted, "
                    + "so Android may have blocked this. Tell the user to enable it in Jarvis settings.";
        }
        return out;
    }

    private String run(String name, JSONObject a) throws Exception {
        switch (name) {
            case "open_app":
                return openApp(a.optString("name"));
            case "call_contact": {
                String[] r = resolve(a.optString("who"));
                if (r == null) return "No contact found matching '" + a.optString("who") + "'.";
                String action = c.checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                        == PackageManager.PERMISSION_GRANTED ? Intent.ACTION_CALL : Intent.ACTION_DIAL;
                launch(new Intent(action, Uri.fromParts("tel", r[1], null)));
                return "Calling " + r[0] + ".";
            }
            case "send_sms": {
                String[] r = resolve(a.optString("to"));
                if (r == null) return "No contact found matching '" + a.optString("to") + "'.";
                String msg = a.optString("message");
                if (msg.isEmpty()) return "The message was empty.";
                if (c.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("SMS permission not granted");
                }
                SmsManager sm = SmsManager.getDefault();
                sm.sendMultipartTextMessage(r[1], null, sm.divideMessage(msg), null, null);
                return "SMS sent to " + r[0] + ".";
            }
            case "whatsapp_message": {
                String[] r = resolve(a.optString("to"));
                if (r == null) return "No contact found matching '" + a.optString("to") + "'.";
                String digits = r[1].replaceAll("[^0-9]", "");
                if (!r[1].trim().startsWith("+")) {
                    if (digits.startsWith("0")) digits = prefs.countryCode() + digits.substring(1);
                    else if (digits.length() == 10) digits = prefs.countryCode() + digits;
                }
                String msg = a.optString("message");
                String url = "https://wa.me/" + digits + (msg.isEmpty() ? "" : "?text=" + Uri.encode(msg));
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return "WhatsApp chat with " + r[0] + " opened. The user must tap send.";
            }
            case "set_alarm": {
                Intent in = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                launch(in);
                return String.format(Locale.ENGLISH, "Alarm set for %02d:%02d.", a.optInt("hour"), a.optInt("minute"));
            }
            case "set_timer": {
                int secs = Math.max(1, a.optInt("seconds", 60));
                Intent in = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                launch(in);
                return "Timer started for " + secs + " seconds.";
            }
            case "flashlight":
                return flashlight(a.optBoolean("on", true));
            case "web_search": {
                String q = a.optString("query");
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
                return "Searching for " + q + ".";
            }
            case "open_url": {
                String u = a.optString("url").trim();
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                return "Opened " + u;
            }
            case "play_music":
                return playMusic(a.optString("query"));
            case "media_control":
                return mediaControl(a.optString("action"));
            case "navigate": {
                String d = a.optString("destination");
                Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d)));
                in.setPackage("com.google.android.apps.maps");
                try {
                    launch(in);
                } catch (ActivityNotFoundException e) {
                    launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(d))));
                }
                return "Navigating to " + d + ".";
            }
            case "volume":
                return volume(a);
            case "battery_status":
                return battery();
            case "current_time":
                return new SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a z", Locale.ENGLISH).format(new Date());
            case "weather":
                return weather(a.optString("city").trim());
            case "open_settings":
                return openSettings(a.optString("page"));
            case "open_camera":
                launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                return "Camera opened.";
            case "compose_email": {
                Intent in = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.optString("to")));
                in.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"));
                in.putExtra(Intent.EXTRA_TEXT, a.optString("body"));
                launch(in);
                return "Email draft opened.";
            }
            case "remember":
                prefs.addMemory(a.optString("fact"));
                return "Saved.";
            case "forget":
                return prefs.removeMemory(a.optString("fact")) ? "Forgotten." : "Nothing matching was stored.";
            case "end_conversation":
                endRequested = true;
                return "Conversation ended.";
            default:
                return "Unknown tool: " + name;
        }
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        launched = true;
    }

    // ------------------------------------------------------------------ individual tools

    private String openApp(String name) {
        String q = name.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return "No app name given.";
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : list) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(q)) score = 4;
            else if (label.startsWith(q)) score = 3;
            else if (label.contains(q)) score = 2;
            else if (label.length() >= 3 && q.contains(label)) score = 1;
            if (score > bestScore) {
                bestScore = score;
                best = ri;
            }
        }
        if (best == null) return "No installed app matches '" + name + "'.";
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(best.activityInfo.packageName, best.activityInfo.name);
        launch(i);
        return "Opened " + best.loadLabel(pm) + ".";
    }

    private String[] resolve(String who) {
        who = who.trim();
        if (who.isEmpty()) return null;
        if (who.matches("[0-9+()\\-\\s]{5,}")) {
            return new String[]{who, who.replaceAll("[^0-9+]", "")};
        }
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Contacts permission not granted");
        }
        String nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME;
        Cursor cur = c.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{nameCol, ContactsContract.CommonDataKinds.Phone.NUMBER},
                nameCol + " LIKE ?", new String[]{"%" + who + "%"}, nameCol + " ASC");
        String[] best = null;
        if (cur != null) {
            try {
                while (cur.moveToNext()) {
                    String n = cur.getString(0);
                    String num = cur.getString(1);
                    if (n == null || num == null) continue;
                    if (n.equalsIgnoreCase(who)) {
                        best = new String[]{n, num};
                        break;
                    }
                    if (best == null) best = new String[]{n, num};
                }
            } finally {
                cur.close();
            }
        }
        return best;
    }

    private String flashlight(boolean on) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (flash != null && flash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cm.setTorchMode(id, on);
                return on ? "Flashlight on." : "Flashlight off.";
            }
        }
        return "This phone has no flashlight.";
    }

    private String playMusic(String q) {
        String[] pkgs = {"com.spotify.music", "com.google.android.apps.youtube.music"};
        for (String p : pkgs) {
            try {
                Intent in = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                in.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                in.putExtra(SearchManager.QUERY, q);
                in.setPackage(p);
                launch(in);
                return "Playing " + q + ".";
            } catch (ActivityNotFoundException ignored) {
            }
        }
        launch(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
        return "Opened YouTube results for " + q + ". The user must tap a result.";
    }

    private String mediaControl(String action) {
        int code;
        switch (action) {
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return "Done (" + action + ").";
    }

    private String volume(JSONObject a) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (a.has("percent")) {
            int p = Math.max(0, Math.min(100, a.optInt("percent")));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * p / 100f), AudioManager.FLAG_SHOW_UI);
            return "Volume set to " + p + " percent.";
        }
        String d = a.optString("direction");
        if ("up".equals(d)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return "Volume raised.";
        }
        if ("down".equals(d)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return "Volume lowered.";
        }
        return "Specify percent or direction.";
    }

    private String battery() {
        Intent st = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (st == null) return "Battery information unavailable.";
        int level = st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = st.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = st.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int pct = scale > 0 ? Math.round(level * 100f / scale) : level;
        return "Battery is at " + pct + " percent" + (charging ? " and charging." : ", not charging.");
    }

    private String openSettings(String page) {
        String action;
        switch (page) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": action = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
            case "location": action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "airplane": action = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        launch(new Intent(action));
        return "Opened " + page + " settings. Android does not let apps toggle these directly, the user must flip the switch.";
    }

    private String weather(String city) throws Exception {
        if (city.isEmpty()) city = prefs.city();
        if (city.isEmpty()) return "No city given and no home city set. Ask the user which city.";
        JSONObject geo = new JSONObject(Http.get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(city)));
        JSONArray rs = geo.optJSONArray("results");
        if (rs == null || rs.length() == 0) return "City not found: " + city;
        JSONObject g = rs.getJSONObject(0);
        double lat = g.getDouble("latitude");
        double lon = g.getDouble("longitude");
        String place = g.optString("name", city);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=1";
        JSONObject w = new JSONObject(Http.get(url));
        JSONObject cur = w.getJSONObject("current");
        JSONObject day = w.getJSONObject("daily");
        double hi = day.getJSONArray("temperature_2m_max").optDouble(0);
        double lo = day.getJSONArray("temperature_2m_min").optDouble(0);
        double rain = day.getJSONArray("precipitation_probability_max").optDouble(0, -1);
        return String.format(Locale.ENGLISH,
                "%s: %s, %.0f C now (feels like %.0f), humidity %.0f%%, wind %.0f km/h. Today high %.0f, low %.0f%s.",
                place, wmo(cur.optInt("weather_code")),
                cur.optDouble("temperature_2m"), cur.optDouble("apparent_temperature"),
                cur.optDouble("relative_humidity_2m"), cur.optDouble("wind_speed_10m"),
                hi, lo, rain >= 0 ? String.format(Locale.ENGLISH, ", rain chance %.0f%%", rain) : "");
    }

    private static String wmo(int code) {
        if (code == 0) return "clear sky";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "rain showers";
        if (code == 85 || code == 86) return "snow showers";
        if (code >= 95) return "thunderstorm";
        return "unsettled";
    }
}
