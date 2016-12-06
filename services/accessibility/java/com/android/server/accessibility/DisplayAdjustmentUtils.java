/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings.Secure;
import android.view.accessibility.AccessibilityManager;

import com.android.server.LocalServices;
import com.android.server.display.DisplayTransformManager;

/**
 * Utility methods for performing accessibility display adjustments.
 */
class DisplayAdjustmentUtils {

    /** Default inversion mode for display color correction. */
    private static final int DEFAULT_DISPLAY_DALTONIZER =
            AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY;

    /** Matrix and offset used for converting color to gray-scale. */
    private static final float[] MATRIX_GRAYSCALE = new float[] {
        .2126f, .2126f, .2126f, 0,
        .7152f, .7152f, .7152f, 0,
        .0722f, .0722f, .0722f, 0,
             0,      0,      0, 1
    };

    /**
     * Matrix and offset used for luminance inversion. Represents a transform
     * from RGB to YIQ color space, rotation around the Y axis by 180 degrees,
     * transform back to RGB color space, and subtraction from 1. The last row
     * represents a non-multiplied addition, see surfaceflinger's ProgramCache
     * for full implementation details.
     */
    private static final float[] MATRIX_INVERT_COLOR = new float[] {
        0.402f, -0.598f, -0.599f, 0,
       -1.174f, -0.174f, -1.175f, 0,
       -0.228f, -0.228f,  0.772f, 0,
             1,       1,       1, 1
    };

    public static void applyDaltonizerSetting(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();
        final DisplayTransformManager dtm = LocalServices.getService(DisplayTransformManager.class);

        int daltonizerMode = AccessibilityManager.DALTONIZER_DISABLED;
        if (Secure.getIntForUser(cr,
                Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0) {
            daltonizerMode = Secure.getIntForUser(cr,
                    Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DEFAULT_DISPLAY_DALTONIZER, userId);
        }

        float[] grayscaleMatrix = null;
        if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
            // Monochromacy isn't supported by the native Daltonizer.
            grayscaleMatrix = MATRIX_GRAYSCALE;
            daltonizerMode = AccessibilityManager.DALTONIZER_DISABLED;
        }
        dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE, grayscaleMatrix);
        dtm.setDaltonizerMode(daltonizerMode);
    }

    /**
     * Applies the specified user's display color adjustments.
     */
    public static void applyInversionSetting(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();
        final DisplayTransformManager dtm = LocalServices.getService(DisplayTransformManager.class);

        final boolean invertColors = Secure.getIntForUser(cr,
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) != 0;
        dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR,
                invertColors ? MATRIX_INVERT_COLOR : null);
    }
}
