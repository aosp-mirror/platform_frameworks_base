/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.settings;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;

public abstract class CurrentUserTracker extends BroadcastReceiver {

    private Context mContext;
    private int mCurrentUserId;

    public CurrentUserTracker(Context context) {
        mContext = context;
    }

    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
            int oldUserId = mCurrentUserId;
            mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
            if (oldUserId != mCurrentUserId) {
                onUserSwitched(mCurrentUserId);
            }
        }
    }

    public void startTracking() {
        mCurrentUserId = ActivityManager.getCurrentUser();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(this, filter);
    }

    public void stopTracking() {
        mContext.unregisterReceiver(this);
    }

    public abstract void onUserSwitched(int newUserId);

    public boolean isCurrentUserOwner() {
        return mCurrentUserId == UserHandle.USER_OWNER;
    }
}
