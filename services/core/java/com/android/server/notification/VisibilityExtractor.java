/*
* Copyright (C) 2014 The Android Open Source Project
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
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.util.Slog;

/**
 * Determines if the given notification can display sensitive content on the lockscreen.
 */
public class VisibilityExtractor implements NotificationSignalExtractor {
    private static final String TAG = "VisibilityExtractor";
    private static final boolean DBG = false;

    private RankingConfig mConfig;
    private DevicePolicyManager mDpm;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mDpm = ctx.getSystemService(DevicePolicyManager.class);
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (mConfig == null) {
            if (DBG) Slog.d(TAG, "missing config");
            return null;
        }

        int userId = record.getUserId();

        if (userId == UserHandle.USER_ALL) {
            record.setPackageVisibilityOverride(record.getChannel().getLockscreenVisibility());
        } else {
            boolean userCanShowNotifications =
                    mConfig.canShowNotificationsOnLockscreen(userId);
            boolean dpmCanShowNotifications = adminAllowsKeyguardFeature(userId,
                    DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
            boolean channelCanShowNotifications = record.getChannel().getLockscreenVisibility()
                    != Notification.VISIBILITY_SECRET;

            if (!userCanShowNotifications || !dpmCanShowNotifications
                    || !channelCanShowNotifications) {
                record.setPackageVisibilityOverride(Notification.VISIBILITY_SECRET);
            } else {
                // notifications are allowed but should they be redacted?

                boolean userCanShowContents =
                        mConfig.canShowPrivateNotificationsOnLockScreen(userId);
                boolean dpmCanShowContents = adminAllowsKeyguardFeature(userId,
                        DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);
                boolean channelCanShowContents = record.getChannel().getLockscreenVisibility()
                        != Notification.VISIBILITY_PRIVATE;

                if (!userCanShowContents || !dpmCanShowContents || !channelCanShowContents) {
                    record.setPackageVisibilityOverride(Notification.VISIBILITY_PRIVATE);
                } else {
                    record.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
                }
            }
        }

        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
        mConfig = config;
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {

    }

    private boolean adminAllowsKeyguardFeature(int userHandle, int feature) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }
        final int dpmFlags = mDpm.getKeyguardDisabledFeatures(null /* admin */, userHandle);
        return (dpmFlags & feature) == 0;
    }

}
