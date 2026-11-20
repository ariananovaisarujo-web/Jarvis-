package com.jarvis.ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Animated arc-reactor / HUD orb. Colour and motion follow Jarvis's state. */
final class ReactorView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long t0 = System.nanoTime();

    private Jarvis.State state = Jarvis.State.IDLE;
    private float level;      // 0..1 mic level
    private float smooth;     // smoothed pulse
    private final float dp;

    ReactorView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    void setJarvisState(Jarvis.State s) {
        state = s;
    }

    /** dB value straight from SpeechRecognizer (roughly -2 .. 10). */
    void setLevelDb(float db) {
        level = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
    }

    private int color() {
        switch (state) {
            case LISTENING: return 0xFF00E5FF;
            case THINKING: return 0xFFFFAB00;
            case SPEAKING: return 0xFF40C4FF;
            default: return 0xFF00A7C4;
        }
    }

    private void stroke(int col, int alpha, float widthDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setAlpha(alpha);
        p.setStrokeWidth(widthDp * dp);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float t = (System.nanoTime() - t0) / 1e9f;
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float R = Math.min(w, h) / 2f * 0.92f;
        int col = color();

        float target;
        float speed = 1f;
        switch (state) {
            case LISTENING: target = 0.25f + level * 0.75f; break;
            case SPEAKING:
                target = 0.45f + 0.35f * (float) Math.abs(Math.sin(t * 7.0) * Math.sin(t * 2.3 + 1.0));
                speed = 1.6f;
                break;
            case THINKING:
                target = 0.45f + 0.25f * (float) Math.sin(t * 5.0);
                speed = 3f;
                break;
            default:
                target = 0.15f + 0.08f * (float) Math.sin(t * 1.6);
                break;
        }
        smooth += (target - smooth) * 0.15f;

        // soft glow
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        for (int k = 1; k <= 6; k++) {
            p.setAlpha((int) (10 + 22 * smooth));
            c.drawCircle(cx, cy, R * (0.26f + k * 0.055f * (1f + smooth)), p);
        }

        // outer thin ring
        stroke(col, 110, 1.5f);
        c.drawCircle(cx, cy, R, p);

        // rotating outer arcs
        stroke(col, 220, 5f);
        float rr = R * 0.90f;
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
        float rot1 = t * 30f * speed;
        for (int i = 0; i < 3; i++) {
            c.drawArc(rect, rot1 + i * 120f, 70f, false, p);
        }

        // tick ring
        stroke(col, 160, 1.2f);
        float tr = R * 0.78f;
        float rot2 = -t * 18f * speed;
        for (int i = 0; i < 72; i++) {
            float len = (i % 6 == 0) ? 12f * dp : 6f * dp;
            c.save();
            c.rotate(rot2 + i * 5f, cx, cy);
            c.drawLine(cx, cy - tr, cx, cy - tr + len, p);
            c.restore();
        }

        // segmented inner ring
        stroke(col, 230, 9f);
        float sr = R * 0.60f;
        rect.set(cx - sr, cy - sr, cx + sr, cy + sr);
        float rot3 = t * 45f * speed;
        for (int i = 0; i < 10; i++) {
            c.drawArc(rect, rot3 + i * 36f, 26f, false, p);
        }

        // voice bars
        if (state == Jarvis.State.LISTENING || state == Jarvis.State.SPEAKING || state == Jarvis.State.THINKING) {
            stroke(col, 200, 2.5f);
            float br = R * 0.44f;
            for (int i = 0; i < 48; i++) {
                float n = 0.5f + 0.5f * (float) Math.sin(i * 1.9f + t * 9f);
                float len = R * 0.10f * (0.15f + smooth * 1.4f * n);
                c.save();
                c.rotate(i * 7.5f, cx, cy);
                c.drawLine(cx, cy - br, cx, cy - br - len, p);
                c.restore();
            }
        }

        // core
        stroke(col, 255, 3f);
        c.drawCircle(cx, cy, R * (0.34f + smooth * 0.05f), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        p.setAlpha((int) (70 + 110 * smooth));
        c.drawCircle(cx, cy, R * (0.28f + smooth * 0.04f), p);
        p.setColor(Color.WHITE);
        p.setAlpha((int) (170 + 85 * smooth));
        c.drawCircle(cx, cy, R * (0.12f + smooth * 0.03f), p);

        postInvalidateOnAnimation();
    }
}
