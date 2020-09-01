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

package com.android.server.display.utils;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.TypedValue;

public class AmbientFilterFactory {
    /**
     * Creates a temporal filter which functions as a weighted moving average buffer for recent
     * sensor values.
     * @param tag
     *      The tag used for dumping and logging.
     * @param horizon
     *      How long ambient value changes are kept and taken into consideration.
     * @param intercept
     *      Recent changes are prioritised by integrating their duration over y = x + intercept
     *      (the higher it is, the less prioritised recent changes are).
     *
     * @return
     *      An AmbientFiler.
     *
     * @throws IllegalArgumentException
     *      - Horizon is not positive.
     *      - Intercept not configured.
     */
    public static AmbientFilter createAmbientFilter(String tag, int horizon, float intercept) {
        if (!Float.isNaN(intercept)) {
            return new AmbientFilter.WeightedMovingAverageAmbientFilter(tag, horizon, intercept);
        }
        throw new IllegalArgumentException("missing configurations: "
                + "expected config_displayWhiteBalanceBrightnessFilterIntercept");
    }

    /**
     * Helper to create a default BrightnessFilter which has configuration in the resource file.
     * @param tag
     *      The tag used for dumping and logging.
     * @param resources
     *      The resources used to configure the various components.
     *
     * @return
     *      An AmbientFilter.
     */
    public static AmbientFilter createBrightnessFilter(String tag, Resources resources) {
        final int horizon = resources.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceBrightnessFilterHorizon);
        final float intercept = getFloat(resources,
                com.android.internal.R.dimen.config_displayWhiteBalanceBrightnessFilterIntercept);

        return createAmbientFilter(tag, horizon, intercept);
    }

    /**
     * Helper to creates a default ColorTemperatureFilter which has configuration in the resource
     * file.
     * @param tag
     *      The tag used for dumping and logging.
     * @param resources
     *      The resources used to configure the various components.
     *
     * @return
     *      An AmbientFilter.
     */
    public static AmbientFilter createColorTemperatureFilter(String tag, Resources resources) {
        final int horizon = resources.getInteger(
                com.android.internal.R.integer
                .config_displayWhiteBalanceColorTemperatureFilterHorizon);
        final float intercept = getFloat(resources,
                com.android.internal.R.dimen
                .config_displayWhiteBalanceColorTemperatureFilterIntercept);

        return createAmbientFilter(tag, horizon, intercept);
    }

    // Instantiation is disabled.
    private AmbientFilterFactory() { }

    private static float getFloat(Resources resources, int id) {
        TypedValue value = new TypedValue();

        resources.getValue(id, value, true /* resolveRefs */);
        if (value.type != TypedValue.TYPE_FLOAT) {
            return Float.NaN;
        }

        return value.getFloat();
    }
}

