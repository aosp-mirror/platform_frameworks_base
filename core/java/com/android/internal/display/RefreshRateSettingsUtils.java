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

import android.content.Context;
import android.hardware.display.DisplayManager;
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
     *
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
            if (mode.getRefreshRate() > maxRefreshRate) {
                maxRefreshRate = mode.getRefreshRate();
            }
        }
        return maxRefreshRate;
    }
}
