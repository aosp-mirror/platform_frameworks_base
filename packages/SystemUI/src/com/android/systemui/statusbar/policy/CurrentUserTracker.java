package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class CurrentUserTracker extends BroadcastReceiver {

    private int mCurrentUserId;

    public CurrentUserTracker(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(this, filter);
        mCurrentUserId = ActivityManager.getCurrentUser();
    }

    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
            mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
        }
    }
}
