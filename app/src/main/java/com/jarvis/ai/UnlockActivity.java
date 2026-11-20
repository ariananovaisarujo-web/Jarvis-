package com.jarvis.ai;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Turns the screen on and asks Android to dismiss the lock screen.
 * With no lock or a plain swipe lock the phone opens by itself. With a PIN, pattern or
 * fingerprint Android shows its own unlock prompt and the owner completes it.
 */
public class UnlockActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { finish(); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) finish(); }
            }, 60000);
        } else {
            finish();
        }
    }
}
