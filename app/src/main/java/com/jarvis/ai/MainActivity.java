package com.jarvis.ai;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements Jarvis.Listener {
    private static final int BG = 0xFF02060A;
    private static final int CYAN = 0xFF00E5FF;
    private static final int DIM = 0xFF4F7480;
    private static final int AMBER = 0xFFFFAB00;

    private Jarvis jarvis;
    private ReactorView reactor;
    private TextView status;
    private TextView logView;
    private ScrollView scroll;
    private EditText input;

    /** Asks for every runtime permission Jarvis can use. Safe to call repeatedly. */
    static void requestAllPermissions(Activity a) {
        String[] all = {
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.SEND_SMS,
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN"
        };
        List<String> need = new ArrayList<>();
        for (String p : all) {
            if (p.endsWith("POST_NOTIFICATIONS") && Build.VERSION.SDK_INT < 33) continue;
            if ((p.endsWith("BLUETOOTH_CONNECT") || p.endsWith("BLUETOOTH_SCAN")) && Build.VERSION.SDK_INT < 31) continue;
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) a.requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        jarvis = Jarvis.get(this);
        buildUi();
        requestAllPermissions(this);

        boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (mic && jarvis.prefs.wake() && !jarvis.wakeActive()) {
            try {
                WakeService.start(this);
            } catch (Exception ignored) {
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("J.A.R.V.I.S", 18, CYAN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.25f);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = text("SETTINGS", 12, DIM);
        gear.setTypeface(Typeface.MONOSPACE);
        gear.setPadding(dp(12), dp(8), dp(4), dp(8));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        reactor = new ReactorView(this);
        reactor.setOnClickListener(v -> jarvis.talk());
        root.addView(reactor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270)));

        status = text("", 12, CYAN);
        status.setTypeface(Typeface.MONOSPACE);
        status.setGravity(Gravity.CENTER);
        status.setLetterSpacing(0.12f);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        logView = text("", 14, 0xFFB0BEC5);
        logView.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        input = new EditText(this);
        input.setHint("Type a command, or tap SEND to speak");
        input.setHintTextColor(DIM);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(border());
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setOnEditorActionListener((v, actionId, ev) -> {
            send();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = text("SEND", 13, CYAN);
        send.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        send.setGravity(Gravity.CENTER);
        send.setBackground(border());
        send.setPadding(dp(16), dp(12), dp(16), dp(12));
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(8);
        row.addView(send, sl);

        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void send() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) {
            jarvis.talk();
            return;
        }
        input.setText("");
        jarvis.submit(t);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Jarvis.visible = true;
        jarvis.addListener(this);

        logView.setText("");
        List<String[]> h = jarvis.history();
        if (h.isEmpty()) {
            addLine("jarvis", "Good day. J.A.R.V.I.S. online. Tap the reactor and speak, or type below.");
            if (jarvis.prefs.apiKey().isEmpty()) {
                addLine("system", "Add your free Gemini API key in SETTINGS to bring my brain online.");
            }
        } else {
            for (String[] e : h) addLine(e[0], e[1]);
        }
        onState(jarvis.state(), jarvis.wakeActive());
    }

    @Override
    protected void onStop() {
        Jarvis.visible = false;
        jarvis.removeListener(this);
        super.onStop();
    }

    // ------------------------------------------------------------------ Jarvis.Listener

    @Override
    public void onState(Jarvis.State s, boolean wakeActive) {
        reactor.setJarvisState(s);
        switch (s) {
            case LISTENING: status.setText("LISTENING"); break;
            case THINKING: status.setText("PROCESSING"); break;
            case SPEAKING: status.setText("SPEAKING"); break;
            default:
                status.setText(wakeActive ? "STANDBY  -  SAY \"JARVIS\"" : "STANDBY  -  TAP THE REACTOR");
                break;
        }
    }

    @Override
    public void onLog(String who, String text) {
        addLine(who, text);
    }

    @Override
    public void onLevel(float db) {
        reactor.setLevelDb(db);
    }

    @Override
    public void onPartial(String text) {
        status.setText(text);
    }

    // ------------------------------------------------------------------ helpers

    private void addLine(String who, String body) {
        String tag;
        int tagColor;
        int bodyColor;
        if ("you".equals(who)) {
            tag = "YOU  > ";
            tagColor = 0xFF78909C;
            bodyColor = 0xFFCFD8DC;
        } else if ("jarvis".equals(who)) {
            tag = "JARVIS > ";
            tagColor = CYAN;
            bodyColor = Color.WHITE;
        } else {
            tag = "SYSTEM > ";
            tagColor = AMBER;
            bodyColor = AMBER;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int s0 = sb.length();
        sb.append(tag);
        sb.setSpan(new ForegroundColorSpan(tagColor), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s1 = sb.length();
        sb.append(body).append("\n\n");
        sb.setSpan(new ForegroundColorSpan(bodyColor), s1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        logView.append(sb);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
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
