
package com.google.android.example.locktasktests;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class MainActivity extends Activity {

    private final static String TAG = "LockTaskTests";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setBackgroundOnLockTaskMode();
    }

    @Override
    public void onResume() {
        super.onResume();
        setBackgroundOnLockTaskMode();
    }

    public void onButtonPressed(View v) {
        Class activity = null;
        switch (v.getId()) {
            case R.id.button_default:
                activity = LockDefaultActivity.class;
                break;
            case R.id.button_never:
                activity = LockTaskNeverActivity.class;
                break;
            case R.id.button_whitelist:
                activity = LockWhitelistedActivity.class;
                break;
            case R.id.button_always:
                activity = LockAtLaunchActivity.class;
                break;
        }
        Intent intent = new Intent(this, activity);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void setBackgroundOnLockTaskMode() {
        ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final int color =
                activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE ?
                        0xFFFFC0C0 : 0xFFFFFFFF;
        findViewById(R.id.root_launch).setBackgroundColor(color);
    }
}
