package com.jarvis.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Lets Jarvis operate the phone like a person: go home/back, tap things by their label,
 * type, scroll and read what is on screen. The user must switch it on in Android's
 * Accessibility settings.
 */
public class JarvisAccessibility extends AccessibilityService {
    static volatile JarvisAccessibility inst;

    static JarvisAccessibility get() {
        return inst;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    boolean global(int action) {
        return performGlobalAction(action);
    }

    // ------------------------------------------------------------------ tap

    String tap(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Cannot read the screen right now.";
        String q = text.trim();
        if (q.isEmpty()) return "Nothing to tap: no label given.";

        AccessibilityNodeInfo target = null;
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(q);
        if (hits != null && !hits.isEmpty()) {
            for (AccessibilityNodeInfo n : hits) {
                CharSequence t = n.getText();
                if (t != null && t.toString().equalsIgnoreCase(q)) {
                    target = n;
                    break;
                }
            }
            if (target == null) target = hits.get(0);
        }
        if (target == null) target = findByDescription(root, q.toLowerCase(Locale.ROOT), 0);
        if (target == null) return "Nothing on screen matches '" + q + "'.";

        AccessibilityNodeInfo n = target;
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped '" + q + "'.";

        Rect r = new Rect();
        target.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            Path p = new Path();
            p.moveTo(r.centerX(), r.centerY());
            GestureDescription.StrokeDescription sd = new GestureDescription.StrokeDescription(p, 0, 60);
            boolean sent = dispatchGesture(new GestureDescription.Builder().addStroke(sd).build(), null, null);
            if (sent) return "Tapped '" + q + "'.";
        }
        return "Cannot tap '" + q + "'.";
    }

    private AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node, String q, int depth) {
        if (node == null || depth > 40) return null;
        CharSequence d = node.getContentDescription();
        if (d != null && d.toString().toLowerCase(Locale.ROOT).contains(q)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByDescription(node.getChild(i), q, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ type

    String type(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Cannot read the screen right now.";
        AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) field = findEditable(root, 0);
        if (field == null) return "Nothing on screen accepts text. Tap a text field first.";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                ? "Typed the text." : "Cannot type into this field.";
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findEditable(node.getChild(i), depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    String paste() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Cannot read the screen right now.";
        AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) field = findEditable(root, 0);
        if (field == null) return "Nothing on screen accepts text. Tap a text field first.";
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE) ? "Pasted." : "Cannot paste here.";
    }

    // ------------------------------------------------------------------ scroll

    String scroll(boolean down) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Cannot read the screen right now.";
        AccessibilityNodeInfo s = findScrollable(root, 0);
        if (s == null) return "Nothing on screen can be scrolled.";
        int action = down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        return s.performAction(action) ? "Scrolled." : "Cannot scroll further.";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findScrollable(node.getChild(i), depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ read

    String read() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Cannot read the screen right now.";
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(root.getPackageName()).append(". Screen shows: ");
        collect(root, sb, 0);
        if (sb.length() > 1800) sb.setLength(1800);
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 40 || sb.length() > 1800) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        else if (d != null && d.length() > 0) sb.append(d).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), sb, depth + 1);
    }
}
