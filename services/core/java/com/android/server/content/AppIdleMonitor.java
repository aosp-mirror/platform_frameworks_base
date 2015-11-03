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
import android.os.UserHandle;

import com.android.server.LocalServices;

/**
 * Helper to listen for app idle and charging status changes and restart backed off
 * sync operations.
 */
class AppIdleMonitor extends AppIdleStateChangeListener {

    private final SyncManager mSyncManager;
    private final UsageStatsManagerInternal mUsageStats;
    private boolean mAppIdleParoleOn;

    AppIdleMonitor(SyncManager syncManager) {
        mSyncManager = syncManager;
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mAppIdleParoleOn = mUsageStats.isAppIdleParoleOn();

        mUsageStats.addAppIdleStateChangeListener(this);
    }

    void setAppIdleParoleOn(boolean appIdleParoleOn) {
        if (mAppIdleParoleOn == appIdleParoleOn) {
            return;
        }
        mAppIdleParoleOn = appIdleParoleOn;
        if (mAppIdleParoleOn) {
            mSyncManager.onAppNotIdle(null, UserHandle.USER_ALL);
        }
    }

    boolean isAppIdle(String packageName, int uidForAppId, int userId) {
        return !mAppIdleParoleOn && mUsageStats.isAppIdle(packageName, uidForAppId, userId);
    }

    @Override
    public void onAppIdleStateChanged(String packageName, int userId, boolean idle) {
        // Don't care if the app is becoming idle
        if (idle) return;
        mSyncManager.onAppNotIdle(packageName, userId);
    }

    @Override
    public void onParoleStateChanged(boolean isParoleOn) {
        setAppIdleParoleOn(isParoleOn);
    }
}
