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
 * limitations under the License.
 */

package com.android.systemui.assist.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

/**
 * Utility class for determining screen and corner dimensions.
 */
public class DisplayUtils {
    /**
     * Converts given distance from dp to pixels.
     */
    public static int convertDpToPx(float dp, Context context) {
        Display d = context.getDisplay();

        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);

        return (int) Math.ceil(dp * dm.density);
    }

    /**
     * The width of the display.
     *
     * - Not affected by rotation.
     * - Includes system decor.
     */
    public static int getWidth(Context context) {
        Display d = context.getDisplay();

        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);

        int rotation = d.getRotation();
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            return dm.widthPixels;
        } else {
            return dm.heightPixels;
        }
    }

    /**
     * The height of the display.
     *
     * - Not affected by rotation.
     * - Includes system decor.
     */
    public static int getHeight(Context context) {
        Display d = context.getDisplay();

        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);

        int rotation = d.getRotation();
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            return dm.heightPixels;
        } else {
            return dm.widthPixels;
        }
    }

    /**
     * Returns the radius of the bottom corners (the distance from the true corner to the point
     * where the curve ends), in pixels.
     */
    public static int getCornerRadiusBottom(Context context) {
        int radius = 0;

        int resourceId = context.getResources().getIdentifier("rounded_corner_radius_bottom",
                "dimen", "android");
        if (resourceId > 0) {
            radius = context.getResources().getDimensionPixelSize(resourceId);
        }

        if (radius == 0) {
            radius = getCornerRadiusDefault(context);
        }
        return radius;
    }

    /**
     * Returns the radius of the top corners (the distance from the true corner to the point where
     * the curve ends), in pixels.
     */
    public static int getCornerRadiusTop(Context context) {
        int radius = 0;

        int resourceId = context.getResources().getIdentifier("rounded_corner_radius_top",
                "dimen", "android");
        if (resourceId > 0) {
            radius = context.getResources().getDimensionPixelSize(resourceId);
        }

        if (radius == 0) {
            radius = getCornerRadiusDefault(context);
        }
        return radius;
    }

    private static int getCornerRadiusDefault(Context context) {
        int radius = 0;

        int resourceId = context.getResources().getIdentifier("rounded_corner_radius", "dimen",
                "android");
        if (resourceId > 0) {
            radius = context.getResources().getDimensionPixelSize(resourceId);
        }
        return radius;
    }
}
