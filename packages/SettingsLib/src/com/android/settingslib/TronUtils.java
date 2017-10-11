/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib;

import android.content.Context;
import android.net.NetworkBadging;

import com.android.internal.logging.MetricsLogger;

/** Utilites for Tron Logging. */
public final class TronUtils {

    private TronUtils() {};

    public static void logWifiSettingsBadge(Context context, int badgeEnum) {
        logNetworkBadgeMetric(context, "settings_wifibadging", badgeEnum);
    }

    /**
     * Logs an occurrence of the given network badge to a Histogram.
     *
     * @param context Context
     * @param histogram the Tron histogram name to write to
     * @param badgeEnum the {@link NetworkBadging.Badging} badge value
     * @throws IllegalArgumentException if the given badge enum is not supported
     */
    private static void logNetworkBadgeMetric(
            Context context, String histogram, int badgeEnum)
            throws IllegalArgumentException {
        int bucket;
        switch (badgeEnum) {
            case NetworkBadging.BADGING_NONE:
                bucket = 0;
                break;
            case NetworkBadging.BADGING_SD:
                bucket = 1;
                break;
            case NetworkBadging.BADGING_HD:
                bucket = 2;
                break;
            case NetworkBadging.BADGING_4K:
                bucket = 3;
                break;
            default:
                throw new IllegalArgumentException("Unsupported badge enum: " + badgeEnum);
        }

        MetricsLogger.histogram(context, histogram, bucket);
    }
}
