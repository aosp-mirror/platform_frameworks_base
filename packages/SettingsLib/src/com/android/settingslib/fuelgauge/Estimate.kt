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
 * limitations under the License
 */

package com.android.settingslib.fuelgauge

import android.content.Context
import android.provider.Settings
import java.time.Duration
import java.time.Instant

const val AVERAGE_TIME_TO_DISCHARGE_UNKNOWN = -1
const val ESTIMATE_MILLIS_UNKNOWN = -1

class Estimate(
    val estimateMillis: Long,
    val isBasedOnUsage: Boolean,
    val averageDischargeTime: Long
) {
    companion object {
        /**
         * Returns the cached estimate if it is available and fresh. Will return null if estimate is
         * unavailable or older than 2 minutes.
         *
         * @param context A valid context
         * @return An [Estimate] object with the latest battery estimates.
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun getCachedEstimateIfAvailable(context: Context): Estimate? {
            // if time > 2 min return null or the estimate otherwise
            val resolver = context.contentResolver
            val lastUpdateTime = getLastCacheUpdateTime(context)
            return if (Duration.between(lastUpdateTime,
                            Instant.now()) > Duration.ofMinutes(1)) {
                null
            } else Estimate(
                    Settings.Global.getLong(resolver,
                            Settings.Global.TIME_REMAINING_ESTIMATE_MILLIS,
                            ESTIMATE_MILLIS_UNKNOWN.toLong()),
                    Settings.Global.getInt(resolver,
                            Settings.Global.TIME_REMAINING_ESTIMATE_BASED_ON_USAGE, 0) == 1,
                    Settings.Global.getLong(resolver, Settings.Global.AVERAGE_TIME_TO_DISCHARGE,
                            AVERAGE_TIME_TO_DISCHARGE_UNKNOWN.toLong()))
        }

        /**
         * Stores an estimate to the cache along with a timestamp. Can be obtained via
         * [.getCachedEstimateIfAvailable].
         *
         * @param context A valid context
         * @param estimate the [Estimate] object to store
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun storeCachedEstimate(context: Context, estimate: Estimate) {
            // store the estimate and update the timestamp
            val resolver = context.contentResolver
            Settings.Global.putLong(resolver, Settings.Global.TIME_REMAINING_ESTIMATE_MILLIS,
                    estimate.estimateMillis)
            Settings.Global.putInt(resolver, Settings.Global.TIME_REMAINING_ESTIMATE_BASED_ON_USAGE,
                    if (estimate.isBasedOnUsage) 1 else 0)
            Settings.Global.putLong(resolver, Settings.Global.AVERAGE_TIME_TO_DISCHARGE,
                    estimate.averageDischargeTime)
            Settings.Global.putLong(resolver, Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME,
                    System.currentTimeMillis())
        }

        /**
         * Returns when the estimate was last updated as an Instant
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun getLastCacheUpdateTime(context: Context): Instant {
            return Instant.ofEpochMilli(
                    Settings.Global.getLong(
                            context.contentResolver,
                            Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME,
                            -1))
        }
    }
}
