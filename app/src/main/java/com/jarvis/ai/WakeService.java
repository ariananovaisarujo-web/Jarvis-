package com.jarvis.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps Jarvis alive in the background so "Jarvis ..." works with the screen off. */
public class WakeService extends Service {
    private static final String CHANNEL = "jarvis_wake";
    private static final String ACTION_STOP = "com.jarvis.ai.STOP";
    private PowerManager.WakeLock wl;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, WakeService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, WakeService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            new Prefs(this).setBool("wake", false);
            Jarvis.get(this).setWake(false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Jarvis wake word", NotificationManager.IMPORTANCE_LOW));

        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), imm);
        PendingIntent off = PendingIntent.getService(this, 1,
                new Intent(this, WakeService.class).setAction(ACTION_STOP), imm);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("J.A.R.V.I.S. online")
                .setContentText("Say \"Jarvis\" to talk to me")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Turn off", off).build())
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wl == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake");
            wl.acquire();
        }
        Jarvis.get(this).setWake(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        Jarvis.get(this).setWake(false);
        super.onDestroy();
    }
}
