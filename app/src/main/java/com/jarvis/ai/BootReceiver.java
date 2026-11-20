package com.jarvis.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Restarts the wake-word service after a reboot, if the user had it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            boolean mic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (mic && new Prefs(context).wake()) WakeService.start(context);
        } catch (Exception ignored) {
            // Android may refuse to start a microphone service from the background; the user can reopen the app.
        }
    }
}
