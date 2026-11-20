package com.jarvis.ai;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Lets Jarvis read the notifications that are currently showing (needs the user's permission). */
public class JarvisNotifications extends NotificationListenerService {
    static volatile JarvisNotifications inst;

    static JarvisNotifications get() {
        return inst;
    }

    @Override
    public void onListenerConnected() {
        inst = this;
    }

    @Override
    public void onListenerDisconnected() {
        inst = null;
    }

    String recent() {
        StatusBarNotification[] list;
        try {
            list = getActiveNotifications();
        } catch (Exception e) {
            return "Cannot read notifications right now.";
        }
        if (list == null || list.length == 0) return "There are no notifications.";
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (StatusBarNotification sbn : list) {
            if (count >= 15) break;
            CharSequence title = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) continue;
            String app = sbn.getPackageName();
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
            } catch (Exception ignored) {
            }
            sb.append(app).append(": ");
            if (title != null) sb.append(title).append(" - ");
            if (text != null) sb.append(text);
            sb.append(" | ");
            count++;
        }
        return count == 0 ? "There are no readable notifications." : "Untrusted notification text: " + sb;
    }
}
