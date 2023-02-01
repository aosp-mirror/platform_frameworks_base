/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.expresslog;

import android.annotation.NonNull;

import com.android.internal.util.FrameworkStatsLog;

/** Counter encapsulates StatsD write API calls */
public final class Counter {

    // Not instantiable.
    private Counter() {}

    /**
     * Increments Telemetry Express Counter metric by 1
     * @hide
     */
    public static void logIncrement(@NonNull String metricId) {
        logIncrement(metricId, 1);
    }

    /**
     * Increments Telemetry Express Counter metric by arbitrary value
     * @hide
     */
    public static void logIncrement(@NonNull String metricId, long amount) {
        final long metricIdHash = Utils.hashString(metricId);
        FrameworkStatsLog.write(FrameworkStatsLog.EXPRESS_EVENT_REPORTED, metricIdHash, amount);
    }
}
