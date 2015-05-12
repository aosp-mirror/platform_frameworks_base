
package com.google.android.example.locktasktests;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

public class MainActivity extends Activity {

    private final static String TAG = "LockTaskTests";
    Runnable mBackgroundPolling;
    boolean mRunning;
    Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBackgroundPolling = new Runnable() {
            // Poll lock task state and set background pink if locked, otherwise white.
            @Override
            public void run() {
                if (!mRunning) {
                    return;
                }
                ActivityManager activityManager =
                        (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                final int color = activityManager.getLockTaskModeState() !=
                        ActivityManager.LOCK_TASK_MODE_NONE ? 0xFFFFC0C0 : 0xFFFFFFFF;
                findViewById(R.id.root_launch).setBackgroundColor(color);
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onResume() {
        super.onResume();
        mRunning = true;
        mBackgroundPolling.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        mRunning = false;
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

    public void onToast(View v) {
        showLockTaskEscapeMessage();
    }
}
