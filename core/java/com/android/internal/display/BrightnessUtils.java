/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.util.MathUtils;

/**
 * Utility class providing functions to convert between linear and perceptual gamma space.
 *
 * Internally, this implements the Hybrid Log Gamma electro-optical transfer function, which is a
 * slight improvement to the typical gamma transfer function for displays whose max brightness
 * exceeds the 120 nit reference point, but doesn't set a specific reference brightness like the PQ
 * function does.
 *
 * Note that this transfer function is only valid if the display's backlight value is a linear
 * control. If it's calibrated to be something non-linear, then a different transfer function
 * should be used.
 *
 * Note: This code is based on the same class in the com.android.settingslib.display package.
 */
public class BrightnessUtils {

    // Hybrid Log Gamma constant values
    private static final float R = 0.5f;
    private static final float A = 0.17883277f;
    private static final float B = 0.28466892f;
    private static final float C = 0.55991073f;

    /**
     * A function for converting from the gamma space into the linear space.
     *
     * @param val The value in the gamma space [0 .. 1.0]
     * @return The corresponding value in the linear space [0 .. 1.0].
     */
    public static final float convertGammaToLinear(float val) {
        final float ret;
        if (val <= R) {
            ret = MathUtils.sq(val / R);
        } else {
            ret = MathUtils.exp((val - C) / A) + B;
        }

        // HLG is normalized to the range [0, 12], ensure that value is within that range,
        // it shouldn't be out of bounds.
        final float normalizedRet = MathUtils.constrain(ret, 0, 12);

        // Re-normalize to the range [0, 1]
        // in order to derive the correct setting value.
        return normalizedRet / 12;
    }

    /**
     * A function for converting from the linear space into the gamma space.
     *
     * @param val The value in linear space [0 .. 1.0]
     * @return The corresponding value in gamma space [0 .. 1.0]
     */
    public static final float convertLinearToGamma(float val) {
        // For some reason, HLG normalizes to the range [0, 12] rather than [0, 1]
        final float normalizedVal = val * 12;
        final float ret;
        if (normalizedVal <= 1f) {
            ret = MathUtils.sqrt(normalizedVal) * R;
        } else {
            ret = A * MathUtils.log(normalizedVal - B) + C;
        }
        return ret;
    }
}
