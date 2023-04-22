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

package com.android.internal.display;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

/**
 * Constants and utility methods for refresh rate settings.
 */
public class RefreshRateSettingsUtils {

    private static final String TAG = "RefreshRateSettingsUtils";

    public static final float DEFAULT_REFRESH_RATE = 60f;

    /**
     * Find the highest refresh rate among all the modes of the default display.
     * @param context The context
     * @return The highest refresh rate
     */
    public static float findHighestRefreshRateForDefaultDisplay(Context context) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        if (display == null) {
            Log.w(TAG, "No valid default display device");
            return DEFAULT_REFRESH_RATE;
        }

        float maxRefreshRate = DEFAULT_REFRESH_RATE;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (Math.round(mode.getRefreshRate()) > maxRefreshRate) {
                maxRefreshRate = mode.getRefreshRate();
            }
        }
        return maxRefreshRate;
    }

    /**
     * Get the min refresh rate which is determined by
     * {@link Settings.System.FORCE_PEAK_REFRESH_RATE}.
     * @param context The context
     * @return The min refresh rate
     */
    public static float getMinRefreshRate(Context context) {
        final ContentResolver cr = context.getContentResolver();
        int forcePeakRefreshRateSetting = Settings.System.getIntForUser(cr,
                Settings.System.FORCE_PEAK_REFRESH_RATE, -1, cr.getUserId());
        return forcePeakRefreshRateSetting == 1
                ? findHighestRefreshRateForDefaultDisplay(context)
                : 0;
    }

    /**
     * Get the peak refresh rate which is determined by {@link Settings.System.SMOOTH_DISPLAY}.
     * @param context The context
     * @param defaultPeakRefreshRate The refresh rate to return if the setting doesn't have a value
     * @return The peak refresh rate
     */
    public static float getPeakRefreshRate(Context context, float defaultPeakRefreshRate) {
        final ContentResolver cr = context.getContentResolver();
        int smoothDisplaySetting = Settings.System.getIntForUser(cr,
                Settings.System.SMOOTH_DISPLAY, -1, cr.getUserId());
        switch (smoothDisplaySetting) {
            case 0:
                return DEFAULT_REFRESH_RATE;
            case 1:
                return findHighestRefreshRateForDefaultDisplay(context);
            default:
                return defaultPeakRefreshRate;
        }
    }
}
