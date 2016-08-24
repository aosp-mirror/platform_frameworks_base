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
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.util.Slog;

/**
 * This {@link com.android.server.notification.NotificationSignalExtractor} notices noisy
 * notifications and marks them to get a temporary ranking bump.
 */
public class NotificationIntrusivenessExtractor implements NotificationSignalExtractor {
    private static final String TAG = "IntrusivenessExtractor";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /** Length of time (in milliseconds) that an intrusive or noisy notification will stay at
    the top of the ranking order, before it falls back to its natural position. */
    private static final long HANG_TIME_MS = 10000;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (record.getImportance() >= NotificationListenerService.Ranking.IMPORTANCE_DEFAULT) {
            final Notification notification = record.getNotification();
            if ((notification.defaults & Notification.DEFAULT_VIBRATE) != 0 ||
                    notification.vibrate != null ||
                    (notification.defaults & Notification.DEFAULT_SOUND) != 0 ||
                    notification.sound != null ||
                    notification.fullScreenIntent != null) {
                record.setRecentlyIntrusive(true);
            }
        }

        return new RankingReconsideration(record.getKey(), HANG_TIME_MS) {
            @Override
            public void work() {
                // pass
            }

            @Override
            public void applyChangesLocked(NotificationRecord record) {
                record.setRecentlyIntrusive(false);
            }
        };
    }

    @Override
    public void setConfig(RankingConfig config) {
        // ignore: config has no relevant information yet.
    }
}
