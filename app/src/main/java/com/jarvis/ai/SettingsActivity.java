package com.jarvis.ai;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private static final int CYAN = 0xFF00E5FF;
    private static final int DIM = 0xFF78909C;

    private static final String[][] STT = {
            {"English (India)", "en-IN"}, {"Hindi", "hi-IN"}, {"English (US)", "en-US"}, {"English (UK)", "en-GB"}};
    private static final String[][] VOICES = {
            {"Jarvis (deep British male)", "jarvis"}, {"Friday (female)", "friday"},
            {"Desi male (Indian accent)", "desi_male"}, {"Desi female (Indian accent)", "desi_female"},
            {"American male (friendly)", "us_male"}, {"American female (warm)", "us_female"}};
    private static final String[][] PROVIDERS = {
            {"Google Gemini", "gemini"}, {"Groq (fast, free tier)", "groq"},
            {"OpenRouter (free models)", "openrouter"}, {"Mistral", "mistral"},
            {"NVIDIA NIM (free, many models)", "nvidia"}, {"Custom (any OpenAI-style API)", "custom"}};
    private static final String[][] TTS = {
            {"British (classic Jarvis)", "en-GB"}, {"Indian English", "en-IN"}, {"US English", "en-US"}};

    private Prefs prefs;
    private LinearLayout col;
    private EditText key, model, name, city, cc, elevenKey, elevenVoice, elevenModel;
    private Spinner sttLang, ttsLang, providerSp, voiceSp;
    private final java.util.Map<String, EditText> pKey = new java.util.HashMap<>();
    private final java.util.Map<String, EditText> pModel = new java.util.HashMap<>();
    private EditText customBase, pinField, patField, sarvamKey, sarvamVoice;
    private TextView vpStatus;
    private Switch spkSw;
    private SeekBar thrBar;
    private volatile boolean vpBusy;
    private TextView hdStatus;
    private Switch hdSw;
    private Switch silentSw;
    private TextView silentStatus;
    private volatile boolean silentBusy;
    private volatile boolean hdBusy;
    private Switch wakeSw, convSw, lockSw;
    private EditText owner, passphrase, codeApp;
    private SeekBar rate, pitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF02060A);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(col);
        setContentView(sv);

        TextView h = label("SETTINGS", 20, CYAN);
        h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(h);

        header("BRAIN");
        key = field("Gemini API key", prefs.apiKey(), true);
        add(button("Get a free Gemini API key", v -> openUrl("https://aistudio.google.com/apikey")));
        model = field("Model (change if replies fail with 'model not found')", prefs.model(), false);

        providerSp = spinner("Which AI brain to use", PROVIDERS, prefs.provider());
        col.addView(label("Fill several keys. If the chosen brain is busy or fails, Jarvis automatically tries the next one that has a key.", 12, DIM));
        String[][] ps = {{"groq", "Groq"}, {"nvidia", "NVIDIA NIM"}, {"openrouter", "OpenRouter"}, {"mistral", "Mistral"}, {"custom", "Custom"}};
        for (String[] pr : ps) {
            if (pr[0].equals("custom")) {
                customBase = field("Custom: base URL (e.g. https://api.cerebras.ai/v1)", prefs.raw("customBase"), false);
            }
            pKey.put(pr[0], field(pr[1] + " API key", prefs.keyFor(pr[0]), true));
            pModel.put(pr[0], field(pr[1] + " model (empty = default)", prefs.raw("model_" + pr[0]), false));
        }
        add(button("Get free Groq key", v -> openUrl("https://console.groq.com/keys")));
        add(button("Get free NVIDIA key", v -> openUrl("https://build.nvidia.com/settings/api-keys")));
        add(button("Get free OpenRouter key", v -> openUrl("https://openrouter.ai/settings/keys")));
        add(button("Get free Mistral key", v -> openUrl("https://console.mistral.ai/api-keys")));

        header("YOU");
        name = field("What should I call you?", prefs.name(), false);
        city = field("Home city (for weather)", prefs.city(), false);
        cc = field("Country code for WhatsApp numbers (e.g. 91)", prefs.countryCode(), false);

        codeApp = field("Code app for 'write code' (app name, e.g. Pyroid or Notepad)", prefs.codeApp(), false);

        header("WAKE WORD");
        col.addView(label("Silent wake engine: offline, no ding sounds, works with screen off (one-time download).", 12, DIM));
        silentStatus = label(WakeEngine.installed(this) ? "Silent wake engine: ready" : "Silent wake engine: not downloaded yet", 13, DIM);
        col.addView(silentStatus);
        silentSw = toggle("Use silent wake engine (recommended)", prefs.silentWake());
        add(button("Download silent wake engine (128 MB, one time)", v -> downloadSilent()));

        header("VOICE");
        col.addView(label("BEST HINDI VOICE (Sarvam, natural Indian voices, free credits on signup). Leave empty to skip.", 12, DIM));
        sarvamKey = field("Sarvam API key", prefs.sarvamKey(), true);
        sarvamVoice = field("Sarvam voice name (optional; empty = automatic from the voice choice below)", prefs.raw("sarvamVoice"), false);
        add(button("Get free Sarvam key", v -> openUrl("https://dashboard.sarvam.ai")));
        hdStatus = label(Neural.installed(this) ? "HD voice: ready (offline)" : "HD voice: not downloaded yet", 13, DIM);
        col.addView(hdStatus);
        add(button("Download HD natural voices (130 MB, one time)", v -> downloadHd()));
        add(button("Test HD voice", v -> testHd()));
        hdSw = toggle("Use HD voice when available", prefs.neuralOn());
        add(button("Phone voice settings", v -> openTtsSettings()));
        col.addView(label("If you hear repeated 'ding' sounds in the background with Always-on, allow Do Not Disturb access so Jarvis can silence them:", 12, DIM));
        add(button("Allow Do Not Disturb access (stops dings)", v -> {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            } catch (Exception ignored) {
            }
        }));
        voiceSp = spinner("Voice (free, on-phone). Change any time", VOICES, prefs.voicePreset());
        sttLang = spinner("I listen in", STT, prefs.sttLang());
        ttsLang = spinner("I speak English in", TTS, prefs.ttsLang());
        rate = seek("Speaking speed", prefs.rate());
        pitch = seek("Voice pitch (lower = deeper)", prefs.pitch());
        convSw = toggle("Keep the conversation going after each answer", prefs.conv());

        header("ELEVENLABS VOICE (optional)");
        elevenKey = field("ElevenLabs API key", prefs.elevenKey(), true);
        elevenVoice = field("Voice ID (from your ElevenLabs voice library)", prefs.elevenVoice(), false);
        elevenModel = field("Model (eleven_flash_v2_5 is fastest, eleven_multilingual_v2 is richer)",
                prefs.elevenModel(), false);
        col.addView(label("When both key and voice ID are filled, I speak with ElevenLabs. "
                + "Leave them empty to use the phone's own voice. Each reply uses your ElevenLabs credits.", 12, DIM));

        header("OWNER LOCK");
        lockSw = toggle("Protect sensitive actions (unlock, call, SMS, WhatsApp, email, screen control)", prefs.lock());
        owner = field("Owner name (shown to strangers)", prefs.ownerName(), false);
        vpStatus = label(vpText(), 13, DIM);
        col.addView(vpStatus);
        spkSw = toggle("Obey only my voice (voice-ID)", prefs.speakerOn());
        add(button("Record my voice (5 short samples)", v -> enrollVoice()));
        col.addView(label("Voice match strictness (higher = stricter). If it rejects you, lower it. Each command shows its match score in the chat.", 12, DIM));
        thrBar = new SeekBar(this);
        thrBar.setMax(100);
        thrBar.setProgress(Math.round(prefs.voiceThreshold() * 100));
        add(thrBar);
        pinField = field("Phone unlock PIN (digits only, saved encrypted on this phone; leave empty to keep the current one)", "", true);
        pinField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        col.addView(label("OR pattern lock: type the dot numbers in the order you draw them. Dots: 1 2 3 / 4 5 6 / 7 8 9 (top row first). Example: 14789 = top-left, down to bottom-left, then right along the bottom.", 12, DIM));
        patField = field("Phone unlock pattern (digits 1-9, no repeats)", "", true);
        patField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        add(button("Remove saved PIN / pattern", v -> { PinVault.clear(this); vpStatus.setText(vpText()); }));
        passphrase = field(prefs.passSet()
                ? "New passphrase (a phrase only you know; leave empty to keep the current one)"
                : "Set your passphrase (a phrase only you know, 2 to 4 words). Required to use protected actions.",
                "", true);
        col.addView(label("When a protected action is asked, Jarvis asks for the passphrase and checks it on the phone "
                + "(it is never sent to the internet). After you say it once, it stays unlocked for 10 minutes. "
                + "Anyone who does not know it is told to get permission from the owner.", 12, DIM));

        header("ALWAYS ON");
        wakeSw = toggle("Wake word: say \"Jarvis\" any time", prefs.wake());
        wakeSw.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    MainActivity.requestAllPermissions(this);
                    v.setChecked(false);
                    return;
                }
                prefs.setBool("wake", true);
                try {
                    WakeService.start(this);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    v.setChecked(false);
                }
            } else {
                prefs.setBool("wake", false);
                WakeService.stop(this);
                Jarvis.get(this).setWake(false);
            }
        });
        TextView note = label("Wake word keeps the microphone open in the background and uses battery. "
                + "Allow the two permissions below so Android does not kill it or block apps from opening.", 12, DIM);
        col.addView(note);

        header("PERMISSIONS");
        add(button("Grant microphone, contacts, calls, SMS", v -> MainActivity.requestAllPermissions(this)));
        add(button("Allow display over other apps", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }));
        add(button("Turn off battery optimisation", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));

        header("FULL PHONE CONTROL");
        add(button("Turn on Jarvis accessibility (tap, type, home, back, read screen)",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        add(button("Allow restricted settings (open app info, menu with 3 dots)", v -> {
            Intent in = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(in);
        }));
        add(button("Allow Jarvis to read notifications",
                v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        col.addView(label("If Android says the setting is restricted: open app info, tap the 3 dots, "
                + "choose Allow restricted settings, then turn on Jarvis in Accessibility.", 12, DIM));
        col.addView(label("Offline use: in the Google app open Settings, Voice, Offline speech recognition and "
                + "download English and Hindi. Simple commands then work with no internet.", 12, DIM));

        header("ACTIONS");
        add(button("Save and test voice", v -> {
            save();
            Jarvis.get(this).submit("Hello Jarvis, introduce yourself in one short sentence.");
            finish();
        }));
        add(button("Clear memory and chat history", v -> {
            prefs.clearMemory();
            Jarvis.get(this).clearConversation();
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show();
        }));
        add(button("Save", v -> {
            save();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        }));
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    private void save() {
        prefs.setString("apiKey", key.getText().toString().trim());
        String m = model.getText().toString().trim();
        prefs.setString("model", m.isEmpty() ? "gemini-3.5-flash" : m);
        String n = name.getText().toString().trim();
        prefs.setString("name", n.isEmpty() ? "sir" : n);
        prefs.setString("city", city.getText().toString().trim());
        String code = cc.getText().toString().replaceAll("[^0-9]", "");
        prefs.setString("cc", code.isEmpty() ? "91" : code);
        prefs.setString("sttLang", STT[sttLang.getSelectedItemPosition()][1]);
        prefs.setString("ttsLang", TTS[ttsLang.getSelectedItemPosition()][1]);
        prefs.setString("elevenKey", elevenKey.getText().toString().trim());
        prefs.setString("elevenVoice", elevenVoice.getText().toString().trim());
        String em = elevenModel.getText().toString().trim();
        prefs.setString("elevenModel", em.isEmpty() ? "eleven_flash_v2_5" : em);
        prefs.setBool("conv", convSw.isChecked());
        prefs.setBool("lock", lockSw.isChecked());
        prefs.setBool("neuralOn", hdSw.isChecked());
        prefs.setBool("silentWake", silentSw.isChecked());
        prefs.setString("sarvamKey", sarvamKey.getText().toString().trim());
        prefs.setString("sarvamVoice", sarvamVoice.getText().toString().trim());
        prefs.setBool("speakerOn", spkSw.isChecked());
        prefs.setFloat("voiceThr", Math.max(0.1f, thrBar.getProgress() / 100f));
        String pat = patField.getText().toString().trim();
        if (!pat.isEmpty()) {
            boolean ok = pat.length() >= 4 && pat.matches("[1-9]+");
            for (int i = 0; ok && i < pat.length(); i++) if (pat.indexOf(pat.charAt(i)) != i) ok = false;
            if (!ok) {
                android.widget.Toast.makeText(this, "Pattern must be 4 to 9 different digits from 1 to 9.", android.widget.Toast.LENGTH_LONG).show();
            } else {
                try {
                    PinVault.save(this, "P:" + pat);
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Could not save the pattern securely.", android.widget.Toast.LENGTH_LONG).show();
                }
            }
        }
        String pin = pinField.getText().toString().trim();
        if (!pin.isEmpty()) {
            try {
                PinVault.save(this, pin);
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Could not save the PIN securely.", android.widget.Toast.LENGTH_LONG).show();
            }
        }
        prefs.setString("voicePreset", VOICES[voiceSp.getSelectedItemPosition()][1]);
        prefs.setString("provider", PROVIDERS[providerSp.getSelectedItemPosition()][1]);
        for (String pk : pKey.keySet()) {
            prefs.setString("key_" + pk, pKey.get(pk).getText().toString().trim());
            prefs.setString("model_" + pk, pModel.get(pk).getText().toString().trim());
        }
        prefs.setString("customBase", customBase.getText().toString().trim());
        prefs.setString("codeApp", codeApp.getText().toString().trim());
        String on = owner.getText().toString().trim();
        prefs.setString("owner", on.isEmpty() ? "Shudhanshu" : on);
        String ph = passphrase.getText().toString().trim();
        if (!ph.isEmpty()) {
            prefs.setPass(ph);
            passphrase.setText("");
        }
        prefs.setFloat("rate", 0.5f + rate.getProgress() / 100f);
        prefs.setFloat("pitch", 0.5f + pitch.getProgress() / 100f);
    }

    // ------------------------------------------------------------------ UI helpers

    private String vpText() {
        return "Voice-ID: " + (VoicePrint.enrolled(prefs) ? "your voice is saved" : "not recorded yet")
                + ".  Phone lock: " + (PinVault.has(this) ? "saved (encrypted)" : "not saved") + ".";
    }

    private void enrollVoice() {
        if (vpBusy) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.requestAllPermissions(this);
            return;
        }
        vpBusy = true;
        final android.content.Context app = getApplicationContext();
        final Jarvis j = Jarvis.get(app);
        final boolean wasWake = j.wakeActive();
        j.setWake(false);
        j.stopAll();
        final String[] phrases = {
                "Jarvis, mera phone open karo",
                "Aaj ka mausam kaisa hai",
                "Jarvis, gaana chala do",
                "Mujhe aaj ki taaza khabar batao",
                "Jarvis, subah chhe baje ka alarm laga do"};
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    if (!VoicePrint.modelReady(app)) {
                        ui("Downloading voice-ID model...");
                        VoicePrint.downloadModel(app);
                    }
                    java.util.List<float[]> embs = new java.util.ArrayList<>();
                    int tries = 0;
                    while (embs.size() < phrases.length && tries < 9) {
                        String ph = phrases[embs.size()];
                        ui("Get ready... then say clearly (" + (embs.size() + 1) + "/5):\n\"" + ph + "\"");
                        Thread.sleep(1800);
                        ui("Speak now (" + (embs.size() + 1) + "/5):\n\"" + ph + "\"");
                        short[] pcm = VoicePrint.record(4500);
                        float[] e = VoicePrint.embed(app, pcm);
                        tries++;
                        if (e == null) {
                            ui("I could not hear you clearly. Speak louder and try again.");
                            Thread.sleep(1500);
                            continue;
                        }
                        embs.add(e);
                    }
                    if (embs.size() < phrases.length) throw new java.io.IOException("Not enough clear samples.");
                    VoicePrint.saveEnrollment(prefs, embs);
                    result = "Done. " + vpText();
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    result = "Voice recording failed: " + m;
                }
                vpBusy = false;
                if (wasWake) j.setWake(true);
                ui(result);
            }
        }).start();
    }

    private void ui(final String m) {
        runOnUiThread(new Runnable() {
            @Override public void run() { vpStatus.setText(m); }
        });
    }

    private void testHd() {
        if (hdBusy) return;
        hdBusy = true;
        hdStatus.setText("Testing HD voice... (first load can take 10-20 seconds)");
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = Neural.selfTest(app);
                hdBusy = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() { hdStatus.setText(r); }
                });
                if (r.startsWith("OK")) Jarvis.get(app).say("Hello sir. I am Jarvis. The HD voice is working.");
            }
        }).start();
    }

    private void downloadSilent() {
        if (silentBusy) return;
        if (WakeEngine.installed(this)) {
            silentStatus.setText("Silent wake engine: ready");
            return;
        }
        silentBusy = true;
        silentStatus.setText("Downloading silent wake engine... 0%");
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    WakeEngine.download(app, new WakeEngine.Progress() {
                        @Override
                        public void on(final String m) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { silentStatus.setText(m); }
                            });
                        }
                    });
                    result = "Silent wake engine: ready. Save, then turn Always-on off and on.";
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    result = "Download failed (" + t.getClass().getSimpleName() + "): " + m;
                }
                silentBusy = false;
                final String r = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() { silentStatus.setText(r); }
                });
            }
        }).start();
    }

    private void downloadHd() {
        if (hdBusy) return;
        if (Neural.installed(this)) {
            hdStatus.setText("HD voice: ready (offline)");
            return;
        }
        hdBusy = true;
        hdStatus.setText("Downloading HD voice... 0%");
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    Neural.download(app, new Neural.Progress() {
                        @Override
                        public void on(final String m) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { hdStatus.setText(m); }
                            });
                        }
                    });
                    result = "HD voice: ready (offline). Save and go back.";
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    result = "Download failed (" + t.getClass().getSimpleName() + "): " + m + ". Check internet and try again.";
                }
                hdBusy = false;
                final String r = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() { hdStatus.setText(r); }
                });
            }
        }).start();
    }

    private void openTtsSettings() {
        try {
            startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        col.addView(v, lp);
    }

    private void header(String s) {
        TextView t = label(s, 12, CYAN);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.2f);
        t.setPadding(0, dp(22), 0, dp(2));
        col.addView(t);
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText field(String hint, String value, boolean secret) {
        col.addView(label(hint, 12, DIM));
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        e.setBackground(border());
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(e);
        return e;
    }

    private Spinner spinner(String title, String[][] opts, String current) {
        col.addView(label(title, 12, DIM));
        String[] names = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i][0];
            if (opts[i][1].equals(current)) sel = i;
        }
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(sel);
        add(sp);
        return sp;
    }

    private SeekBar seek(String title, float value) {
        col.addView(label(title, 12, DIM));
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(Math.max(0, Math.min(100, Math.round((value - 0.5f) * 100f))));
        add(s);
        return s;
    }

    private Switch toggle(String text, boolean on) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(Color.WHITE);
        s.setChecked(on);
        add(s);
        return s;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(CYAN);
        b.setBackground(border());
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable border() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF07141C);
        g.setStroke(dp(1), 0x6600E5FF);
        g.setCornerRadius(dp(10));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
