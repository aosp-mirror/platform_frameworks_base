/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.stats.pull;

import static android.provider.Settings.System.PEAK_REFRESH_RATE;

import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import com.android.internal.display.RefreshRateSettingsUtils;

/**
 * Utility methods for processing raw settings values to the format that
 * is acceptable for telemetry system.
 * For instance, raw setting values could be hard to visualize on dashboards, etc.
 */
public final class RawSettingsTelemetryUtils {

    private static final String TAG = "SettingsTelemetryUtils";

    /**
     * Get string that should be written as a value of settingKey and should be sent to telemetry
     * system.
     *
     * @param context The context
     * @param key The setting key
     * @param value The setting raw value that was parsed from Settings.
     * @return The setting string value that should be sent to telemetry system.
     */
    @Nullable
    public static String getTelemetrySettingFromRawVal(Context context, String key, String value) {
        if (key == null) {
            return null;
        }

        if (key.equals(PEAK_REFRESH_RATE)) {
            return getPeakRefreshRateSetting(context, value);
        }

        return value;
    }

    /**
     * Get string that should be written as a value of "peak_refresh_setting" setting
     * and should be sent to telemetry.
     * system.
     *
     * @param context The context
     * @param settingRawValue The setting raw value that was parsed from Settings.
     * @return The "peak_refresh_setting" string value that should be sent to telemetry system.
     */
    @Nullable
    private static String getPeakRefreshRateSetting(Context context, String settingRawValue) {
        if (settingRawValue == null) {
            Log.e(TAG, "PEAK_REFRESH_RATE value is null");
            return null;
        }

        String floatInfinityStr = Float.toString(Float.POSITIVE_INFINITY);
        if (settingRawValue.equals(floatInfinityStr)) {
            float max_refresh_rate =
                    RefreshRateSettingsUtils.findHighestRefreshRateAmongAllBuiltInDisplays(context);
            return Float.toString(max_refresh_rate);
        }

        return settingRawValue;
    }
}
