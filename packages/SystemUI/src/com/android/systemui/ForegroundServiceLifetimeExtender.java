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

package com.android.systemui;

import android.annotation.NonNull;
import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.NotificationInteractionTracker;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.time.SystemClock;

import javax.inject.Inject;

/**
 * Extends the lifetime of foreground notification services such that they show for at least
 * five seconds
 */
public class ForegroundServiceLifetimeExtender implements NotificationLifetimeExtender {

    private static final String TAG = "FGSLifetimeExtender";
    @VisibleForTesting
    static final int MIN_FGS_TIME_MS = 5000;

    private NotificationSafeToRemoveCallback mNotificationSafeToRemoveCallback;
    private ArraySet<NotificationEntry> mManagedEntries = new ArraySet<>();
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private final SystemClock mSystemClock;
    private final NotificationInteractionTracker mInteractionTracker;

    @Inject
    public ForegroundServiceLifetimeExtender(
            NotificationInteractionTracker interactionTracker,
            SystemClock systemClock) {
        mSystemClock = systemClock;
        mInteractionTracker = interactionTracker;
    }

    @Override
    public void setCallback(@NonNull NotificationSafeToRemoveCallback callback) {
        mNotificationSafeToRemoveCallback = callback;
    }

    @Override
    public boolean shouldExtendLifetime(@NonNull NotificationEntry entry) {
        if ((entry.getSbn().getNotification().flags
                & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
            return false;
        }

        boolean hasInteracted = mInteractionTracker.hasUserInteractedWith(entry.getKey());
        long aliveTime = mSystemClock.uptimeMillis() - entry.getCreationTime();
        return aliveTime < MIN_FGS_TIME_MS && !hasInteracted;
    }

    @Override
    public boolean shouldExtendLifetimeForPendingNotification(
            @NonNull NotificationEntry entry) {
        return shouldExtendLifetime(entry);
    }

    @Override
    public void setShouldManageLifetime(
            @NonNull NotificationEntry entry, boolean shouldManage) {
        if (!shouldManage) {
            mManagedEntries.remove(entry);
            return;
        }

        mManagedEntries.add(entry);

        Runnable r = () -> {
            if (mManagedEntries.contains(entry)) {
                mManagedEntries.remove(entry);
                if (mNotificationSafeToRemoveCallback != null) {
                    mNotificationSafeToRemoveCallback.onSafeToRemove(entry.getKey());
                }
            }
        };
        long delayAmt = MIN_FGS_TIME_MS
                - (mSystemClock.uptimeMillis() - entry.getCreationTime());
        mHandler.postDelayed(r, delayAmt);
    }
}

