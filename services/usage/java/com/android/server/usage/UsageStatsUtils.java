/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.app.usage.UsageStatsManager;

import java.util.Calendar;

/**
 * A collection of utility methods used by the UsageStatsService and accompanying classes.
 */
final class UsageStatsUtils {
    private UsageStatsUtils() {}

    /**
     * Truncates the date to the given UsageStats bucket. For example, if the bucket is
     * {@link UsageStatsManager#INTERVAL_YEARLY}, the date is truncated to the 1st day of the year,
     * with the time set to 00:00:00.
     *
     * @param bucket The UsageStats bucket to truncate to.
     * @param cal The date to truncate.
     */
    public static void truncateDateTo(int bucket, Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        switch (bucket) {
            case UsageStatsManager.INTERVAL_YEARLY:
                cal.set(Calendar.DAY_OF_YEAR, 0);
                break;

            case UsageStatsManager.INTERVAL_MONTHLY:
                cal.set(Calendar.DAY_OF_MONTH, 0);
                break;

            case UsageStatsManager.INTERVAL_WEEKLY:
                cal.set(Calendar.DAY_OF_WEEK, 0);
                break;

            case UsageStatsManager.INTERVAL_DAILY:
                break;

            default:
                throw new UnsupportedOperationException("Can't truncate date to bucket " + bucket);
        }
    }
}
