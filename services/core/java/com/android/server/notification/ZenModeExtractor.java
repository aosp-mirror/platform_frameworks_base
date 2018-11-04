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

import android.content.Context;
import android.util.Log;
import android.util.Slog;

/**
 * This {@link ZenModeExtractor} updates intercepted and visual interruption states.
 */
public class ZenModeExtractor implements NotificationSignalExtractor {
    private static final String TAG = "ZenModeExtractor";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private ZenModeHelper mZenModeHelper;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (mZenModeHelper == null) {
            if (DBG) Slog.d(TAG, "skipping - no zen info available");
            return null;
        }

        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(
                    mZenModeHelper.getConsolidatedNotificationPolicy().suppressedVisualEffects);
        } else {
            record.setSuppressedVisualEffects(0);
        }

        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
        // ignore: config has no relevant information yet.
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {
        mZenModeHelper = helper;
    }
}
