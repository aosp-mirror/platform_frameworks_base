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

package com.android.server.display.whitebalance;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.TypedValue;

/**
 * The DisplayWhiteBalanceFactory creates and configures an DisplayWhiteBalanceController.
 */
public class DisplayWhiteBalanceFactory {

    private static final String BRIGHTNESS_FILTER_TAG = "AmbientBrightnessFilter";
    private static final String COLOR_TEMPERATURE_FILTER_TAG = "AmbientColorTemperatureFilter";

    /**
     * Create and configure an DisplayWhiteBalanceController.
     *
     * @param handler
     *      The handler used to determine which thread to run on.
     * @param sensorManager
     *      The sensor manager used to acquire necessary sensors.
     * @param resources
     *      The resources used to configure the various components.
     *
     * @return An DisplayWhiteBalanceController.
     *
     * @throws NullPointerException
     *      - handler is null;
     *      - sensorManager is null.
     * @throws Resources.NotFoundException
     *      - Configurations are missing.
     * @throws IllegalArgumentException
     *      - Configurations are invalid.
     * @throws IllegalStateException
     *      - Cannot find the necessary sensors.
     */
    public static DisplayWhiteBalanceController create(Handler handler,
            SensorManager sensorManager, Resources resources) {
        final AmbientSensor.AmbientBrightnessSensor brightnessSensor =
                createBrightnessSensor(handler, sensorManager, resources);
        final AmbientFilter brightnessFilter = createBrightnessFilter(resources);
        final AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor =
                createColorTemperatureSensor(handler, sensorManager, resources);
        final AmbientFilter colorTemperatureFilter = createColorTemperatureFilter(resources);
        final DisplayWhiteBalanceThrottler throttler = createThrottler(resources);
        final float lowLightAmbientBrightnessThreshold = getFloat(resources,
                com.android.internal.R.dimen
                .config_displayWhiteBalanceLowLightAmbientBrightnessThreshold);
        final float lowLightAmbientColorTemperature = getFloat(resources,
                com.android.internal.R.dimen
                .config_displayWhiteBalanceLowLightAmbientColorTemperature);
        final DisplayWhiteBalanceController controller = new DisplayWhiteBalanceController(
                brightnessSensor, brightnessFilter, colorTemperatureSensor, colorTemperatureFilter,
                throttler, lowLightAmbientBrightnessThreshold, lowLightAmbientColorTemperature);
        brightnessSensor.setCallbacks(controller);
        colorTemperatureSensor.setCallbacks(controller);
        return controller;
    }

    // Instantiation is disabled.
    private DisplayWhiteBalanceFactory() { }

    private static AmbientSensor.AmbientBrightnessSensor createBrightnessSensor(Handler handler,
            SensorManager sensorManager, Resources resources) {
        final int rate = resources.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceBrightnessSensorRate);
        return new AmbientSensor.AmbientBrightnessSensor(handler, sensorManager, rate);
    }

    private static AmbientFilter createBrightnessFilter(Resources resources) {
        final int horizon = resources.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceBrightnessFilterHorizon);
        final float intercept = getFloat(resources,
                com.android.internal.R.dimen.config_displayWhiteBalanceBrightnessFilterIntercept);
        if (!Float.isNaN(intercept)) {
            return new AmbientFilter.WeightedMovingAverageAmbientFilter(
                    BRIGHTNESS_FILTER_TAG, horizon, intercept);
        }
        throw new IllegalArgumentException("missing configurations: "
                + "expected config_displayWhiteBalanceBrightnessFilterIntercept");
    }


    private static AmbientSensor.AmbientColorTemperatureSensor createColorTemperatureSensor(
            Handler handler, SensorManager sensorManager, Resources resources) {
        final String name = resources.getString(
                com.android.internal.R.string
                .config_displayWhiteBalanceColorTemperatureSensorName);
        final int rate = resources.getInteger(
                com.android.internal.R.integer
                .config_displayWhiteBalanceColorTemperatureSensorRate);
        return new AmbientSensor.AmbientColorTemperatureSensor(handler, sensorManager, name, rate);
    }

    private static AmbientFilter createColorTemperatureFilter(Resources resources) {
        final int horizon = resources.getInteger(
                com.android.internal.R.integer
                .config_displayWhiteBalanceColorTemperatureFilterHorizon);
        final float intercept = getFloat(resources,
                com.android.internal.R.dimen
                .config_displayWhiteBalanceColorTemperatureFilterIntercept);
        if (!Float.isNaN(intercept)) {
            return new AmbientFilter.WeightedMovingAverageAmbientFilter(
                    COLOR_TEMPERATURE_FILTER_TAG, horizon, intercept);
        }
        throw new IllegalArgumentException("missing configurations: "
                + "expected config_displayWhiteBalanceColorTemperatureFilterIntercept");
    }

    private static DisplayWhiteBalanceThrottler createThrottler(Resources resources) {
        final int increaseDebounce = resources.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceDecreaseDebounce);
        final int decreaseDebounce = resources.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceIncreaseDebounce);
        final float[] baseThresholds = getFloatArray(resources,
                com.android.internal.R.array.config_displayWhiteBalanceBaseThresholds);
        final float[] increaseThresholds = getFloatArray(resources,
                com.android.internal.R.array.config_displayWhiteBalanceIncreaseThresholds);
        final float[] decreaseThresholds = getFloatArray(resources,
                com.android.internal.R.array.config_displayWhiteBalanceDecreaseThresholds);
        return new DisplayWhiteBalanceThrottler(increaseDebounce, decreaseDebounce, baseThresholds,
                increaseThresholds, decreaseThresholds);
    }

    private static float getFloat(Resources resources, int id) {
        TypedValue value = new TypedValue();
        resources.getValue(id, value, true /* resolveRefs */);
        if (value.type != TypedValue.TYPE_FLOAT) {
            return Float.NaN;
        }
        return value.getFloat();
    }

    private static float[] getFloatArray(Resources resources, int id) {
        TypedArray array = resources.obtainTypedArray(id);
        try {
            if (array.length() == 0) {
                return null;
            }
            float[] values = new float[array.length()];
            for (int i = 0; i < values.length; i++) {
                values[i] = array.getFloat(i, Float.NaN);
                if (Float.isNaN(values[i])) {
                    return null;
                }
            }
            return values;
        } finally {
            array.recycle();
        }
    }

}
