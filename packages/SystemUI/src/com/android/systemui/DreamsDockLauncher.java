package com.android.systemui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Slog;

public class DreamsDockLauncher extends Activity {
    private static final String TAG = "DreamsDockLauncher";
    @Override
    protected void onCreate (Bundle icicle) {
        super.onCreate(icicle);
        try {
            String component = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.DREAM_COMPONENT);
            if (component == null) {
                component = getResources().getString(com.android.internal.R.string.config_defaultDreamComponent);
            }
            if (component != null) {
                ComponentName cn = ComponentName.unflattenFromString(component);
                Intent zzz = new Intent(Intent.ACTION_MAIN)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        );
                startActivity(zzz);
            } else {
                Slog.e(TAG, "Couldn't start screen saver: none selected");
            }
        } catch (android.content.ActivityNotFoundException exc) {
            // no screensaver? give up
            Slog.e(TAG, "Couldn't start screen saver: none installed");
        }
        finish();
    }
}
