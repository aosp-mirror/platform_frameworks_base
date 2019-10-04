/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.NotificationData;

/**
 * Extends the lifetime of foreground notification services such that they show for at least
 * five seconds
 */
public class ForegroundServiceLifetimeExtender implements NotificationLifetimeExtender {

    private static final String TAG = "FGSLifetimeExtender";
    @VisibleForTesting
    static final int MIN_FGS_TIME_MS = 5000;

    private NotificationSafeToRemoveCallback mNotificationSafeToRemoveCallback;
    private ArraySet<NotificationData.Entry> mManagedEntries = new ArraySet<>();
    private Handler mHandler = new Handler(Looper.getMainLooper());

    ForegroundServiceLifetimeExtender() {
    }

    @Override
    public void setCallback(@NonNull NotificationSafeToRemoveCallback callback) {
        mNotificationSafeToRemoveCallback = callback;
    }

    @Override
    public boolean shouldExtendLifetime(@NonNull NotificationData.Entry entry) {
        if ((entry.notification.getNotification().flags
                & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        return currentTime - entry.notification.getPostTime() < MIN_FGS_TIME_MS;
    }

    @Override
    public boolean shouldExtendLifetimeForPendingNotification(
            @NonNull NotificationData.Entry entry) {
        return shouldExtendLifetime(entry);
    }

    @Override
    public void setShouldManageLifetime(
            @NonNull NotificationData.Entry entry, boolean shouldManage) {
        if (!shouldManage) {
            mManagedEntries.remove(entry);
            return;
        }

        mManagedEntries.add(entry);

        Runnable r = () -> {
            if (mManagedEntries.contains(entry)) {
                mManagedEntries.remove(entry);
                if (mNotificationSafeToRemoveCallback != null) {
                    mNotificationSafeToRemoveCallback.onSafeToRemove(entry.key);
                }
            }
        };
        long delayAmt = MIN_FGS_TIME_MS
                - (System.currentTimeMillis() - entry.notification.getPostTime());
        mHandler.postDelayed(r, delayAmt);
    }
}
