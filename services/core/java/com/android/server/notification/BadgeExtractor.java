/**
* Copyright (C) 2017 The Android Open Source Project
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

import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;

import android.app.Notification;
import android.content.Context;
import android.util.Slog;

/**
 * Determines whether a badge should be shown for this notification
 */
public class BadgeExtractor implements NotificationSignalExtractor {
    private static final String TAG = "BadgeExtractor";
    private static final boolean DBG = false;

    private RankingConfig mConfig;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
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
        boolean userWantsBadges = mConfig.badgingEnabled(record.getSbn().getUser());
        boolean appCanShowBadge =
                mConfig.canShowBadge(record.getSbn().getPackageName(), record.getSbn().getUid());
        if (!userWantsBadges || !appCanShowBadge) {
            record.setShowBadge(false);
        } else {
            if (record.getChannel() != null) {
                record.setShowBadge(record.getChannel().canShowBadge() && appCanShowBadge);
            } else {
                record.setShowBadge(appCanShowBadge);
            }
        }

        if (record.isIntercepted()
                && (record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_BADGE) != 0) {
            record.setShowBadge(false);
        }

        Notification.BubbleMetadata metadata = record.getNotification().getBubbleMetadata();
        if (metadata != null && metadata.isNotificationSuppressed()) {
            record.setShowBadge(false);
        }

        if (mConfig.isMediaNotificationFilteringEnabled()) {
            final Notification notif = record.getNotification();
            if (notif.hasMediaSession()) {
                if (notif.isStyle(Notification.DecoratedMediaCustomViewStyle.class)
                        || notif.isStyle(Notification.MediaStyle.class)) {
                    record.setShowBadge(false);
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
}
