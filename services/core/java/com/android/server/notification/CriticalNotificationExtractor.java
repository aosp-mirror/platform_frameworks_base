/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.notification;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Slog;

/**
 * Sets the criticality of a notification record. This is used to allow a bypass to all other
 * ranking signals. It is required in the automotive use case to facilitate placing emergency and
 * warning notifications above all others. It does not process notifications unless the system
 * has the automotive feature flag set.
 * <p>
 * Note: it is up to the notification ranking system to determine the effect of criticality values
 * on a notification record
 *
 */
public class CriticalNotificationExtractor implements NotificationSignalExtractor {

    private static final String TAG = "CriticalNotificationExt";
    private static final boolean DBG = false;
    private boolean mSupportsCriticalNotifications = false;
    /** 
     * Intended to bypass all other ranking, notification should be placed above all others.
     * In the automotive case, the notification would be used to tell a driver to pull over
     * immediately 
     */
    static final int CRITICAL = 0;
    /**
     * Indicates a notification should be place above all notifications except those marked as
     * critical. In the automotive case this is a check engine light. 
     */
    static final int CRITICAL_LOW = 1;
    /** Normal notification. */
    static final int NORMAL = 2;

    @Override
    public void initialize(Context context, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mSupportsCriticalNotifications = supportsCriticalNotifications(context);
    }

    private boolean supportsCriticalNotifications(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0);
    }

    @Override
    public RankingReconsideration process(NotificationRecord record) {
        if (!mSupportsCriticalNotifications) {
            if (DBG) Slog.d(TAG, "skipping since system does not support critical notification");
            return null;
        }
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }
        // Note: The use of both CATEGORY_CAR_EMERGENCY and CATEGORY_CAR_WARNING is restricted to
        // System apps
        if (record.isCategory(Notification.CATEGORY_CAR_EMERGENCY)) {
            record.setCriticality(CRITICAL);
        } else if (record.isCategory(Notification.CATEGORY_CAR_WARNING)) {
            record.setCriticality(CRITICAL_LOW);
        } else {
            record.setCriticality(NORMAL);
        }
        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {
    }

}
