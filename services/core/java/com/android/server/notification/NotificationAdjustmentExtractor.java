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

import static android.service.notification.Adjustment.KEY_TYPE;
import static android.service.notification.Flags.notificationForceGrouping;

import android.content.Context;
import android.util.Slog;

/**
 * Applies adjustments from the group helper and notification assistant
 */
public class NotificationAdjustmentExtractor implements NotificationSignalExtractor {
    private static final String TAG = "AdjustmentExtractor";
    private static final boolean DBG = false;
    private GroupHelper mGroupHelper;


    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        final boolean hasAdjustedClassification = record.hasAdjustment(KEY_TYPE);
        record.applyAdjustments();

        if (notificationForceGrouping()
                && android.service.notification.Flags.notificationClassification()) {
            // Classification adjustments trigger regrouping
            if (mGroupHelper != null && hasAdjustedClassification) {
                return new RankingReconsideration(record.getKey(), 0) {
                    @Override
                    public void work() {
                    }

                    @Override
                    public void applyChangesLocked(NotificationRecord record) {
                        mGroupHelper.onChannelUpdated(record);
                    }
                };
            }
        }

        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
        // config is not used
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {

    }

    @Override
    public void setGroupHelper(GroupHelper groupHelper) {
        mGroupHelper = groupHelper;
    }
}
