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
 * limitations under the License
 */

package com.android.internal.policy;

import android.content.res.Resources;

import com.android.internal.R;

/**
 * Utility functions for screen decorations used by both window manager and System UI.
 */
public class ScreenDecorationsUtils {

    /**
     * Corner radius that should be used on windows in order to cover the display.
     * These values are expressed in pixels because they should not respect display or font
     * scaling, this means that we don't have to reload them on config changes.
     */
    public static float getWindowCornerRadius(Resources resources) {
        if (!supportsRoundedCornersOnWindows(resources)) {
            return 0f;
        }

        // Radius that should be used in case top or bottom aren't defined.
        float defaultRadius = resources.getDimension(R.dimen.rounded_corner_radius)
                - resources.getDimension(R.dimen.rounded_corner_radius_adjustment);

        float topRadius = resources.getDimension(R.dimen.rounded_corner_radius_top)
                - resources.getDimension(R.dimen.rounded_corner_radius_top_adjustment);
        if (topRadius == 0f) {
            topRadius = defaultRadius;
        }
        float bottomRadius = resources.getDimension(R.dimen.rounded_corner_radius_bottom)
                - resources.getDimension(R.dimen.rounded_corner_radius_bottom_adjustment);
        if (bottomRadius == 0f) {
            bottomRadius = defaultRadius;
        }

        // Always use the smallest radius to make sure the rounded corners will
        // completely cover the display.
        return Math.min(topRadius, bottomRadius);
    }

    /**
     * If live rounded corners are supported on windows.
     */
    public static boolean supportsRoundedCornersOnWindows(Resources resources) {
        return resources.getBoolean(R.bool.config_supportsRoundedCornersOnWindows);
    }
}
