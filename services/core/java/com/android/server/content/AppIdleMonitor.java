/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.content;

import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.UserHandle;

import com.android.server.LocalServices;

/**
 * Helper to listen for app idle and charging status changes and restart backed off
 * sync operations.
 */
class AppIdleMonitor implements AppIdleStateChangeListener {

    private final SyncManager mSyncManager;
    private final UsageStatsManagerInternal mUsageStats;
    final BatteryManager mBatteryManager;
    /** Is the device currently plugged into power. */
    private boolean mPluggedIn;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onPluggedIn(mBatteryManager.isCharging());
        }
    };

    AppIdleMonitor(SyncManager syncManager, Context context) {
        mSyncManager = syncManager;
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mUsageStats.addAppIdleStateChangeListener(this);
        mBatteryManager = context.getSystemService(BatteryManager.class);
        mPluggedIn = isPowered();
        registerReceivers(context);
    }

    private void registerReceivers(Context context) {
        // Monitor battery charging state
        IntentFilter filter = new IntentFilter(BatteryManager.ACTION_CHARGING);
        filter.addAction(BatteryManager.ACTION_DISCHARGING);
        context.registerReceiver(mReceiver, filter);
    }

    private boolean isPowered() {
        return mBatteryManager.isCharging();
    }

    void onPluggedIn(boolean pluggedIn) {
        if (mPluggedIn == pluggedIn) {
            return;
        }
        mPluggedIn = pluggedIn;
        if (mPluggedIn) {
            mSyncManager.onAppNotIdle(null, UserHandle.USER_ALL);
        }
    }

    boolean isAppIdle(String packageName, int userId) {
        return !mPluggedIn && mUsageStats.isAppIdle(packageName, userId);
    }

    @Override
    public void onAppIdleStateChanged(String packageName, int userId, boolean idle) {
        // Don't care if the app is becoming idle
        if (idle) return;
        mSyncManager.onAppNotIdle(packageName, userId);
    }
}
