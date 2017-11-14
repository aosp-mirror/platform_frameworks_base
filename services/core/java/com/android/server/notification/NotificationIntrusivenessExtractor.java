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

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This {@link com.android.server.notification.NotificationSignalExtractor} notices noisy
 * notifications and marks them to get a temporary ranking bump.
 */
public class NotificationIntrusivenessExtractor implements NotificationSignalExtractor {
    private static final String TAG = "IntrusivenessExtractor";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /** Length of time (in milliseconds) that an intrusive or noisy notification will stay at
    the top of the ranking order, before it falls back to its natural position. */
    @VisibleForTesting
    static final long HANG_TIME_MS = 10000;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (record.getFreshnessMs(System.currentTimeMillis()) < HANG_TIME_MS
                && record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT) {
            if (record.getSound() != null && record.getSound() != Uri.EMPTY) {
                record.setRecentlyIntrusive(true);
            }
            if (record.getVibration() != null) {
                record.setRecentlyIntrusive(true);
            }
            if (record.getNotification().fullScreenIntent != null) {
                record.setRecentlyIntrusive(true);
            }
        }

        if (!record.isRecentlyIntrusive()) {
            return null;
        }

        return new RankingReconsideration(record.getKey(), HANG_TIME_MS) {
            @Override
            public void work() {
                // pass
            }

            @Override
            public void applyChangesLocked(NotificationRecord record) {
                // there will be another reconsideration in the message queue HANG_TIME_MS
                // from each time this record alerts, which can finally clear this flag.
                if ((System.currentTimeMillis() - record.getLastIntrusive()) >= HANG_TIME_MS) {
                    record.setRecentlyIntrusive(false);
                }
            }
        };
    }

    @Override
    public void setConfig(RankingConfig config) {
        // ignore: config has no relevant information yet.
    }
}
