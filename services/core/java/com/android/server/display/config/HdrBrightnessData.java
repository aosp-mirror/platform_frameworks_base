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

package com.android.server.display.config;

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Brightness config for HDR content
 */
public class HdrBrightnessData {

    /**
     * Lux to brightness map
     */
    public final Map<Float, Float> mMaxBrightnessLimits;

    /**
     * Debounce time for brightness increase
     */
    public final long mBrightnessIncreaseDebounceMillis;

    /**
     * Brightness increase animation speed
     */
    public final float mScreenBrightnessRampIncrease;

    /**
     * Debounce time for brightness decrease
     */
    public final long mBrightnessDecreaseDebounceMillis;

    /**
     * Brightness decrease animation speed
     */
    public final float mScreenBrightnessRampDecrease;

    @VisibleForTesting
    public HdrBrightnessData(Map<Float, Float> maxBrightnessLimits,
            long brightnessIncreaseDebounceMillis, float screenBrightnessRampIncrease,
            long brightnessDecreaseDebounceMillis, float screenBrightnessRampDecrease) {
        mMaxBrightnessLimits = maxBrightnessLimits;
        mBrightnessIncreaseDebounceMillis = brightnessIncreaseDebounceMillis;
        mScreenBrightnessRampIncrease = screenBrightnessRampIncrease;
        mBrightnessDecreaseDebounceMillis = brightnessDecreaseDebounceMillis;
        mScreenBrightnessRampDecrease = screenBrightnessRampDecrease;
    }

    @Override
    public String toString() {
        return "HdrBrightnessData {"
                + "mMaxBrightnessLimits: " + mMaxBrightnessLimits
                + ", mBrightnessIncreaseDebounceMillis: " + mBrightnessIncreaseDebounceMillis
                + ", mScreenBrightnessRampIncrease: " + mScreenBrightnessRampIncrease
                + ", mBrightnessDecreaseDebounceMillis: " + mBrightnessDecreaseDebounceMillis
                + ", mScreenBrightnessRampDecrease: " + mScreenBrightnessRampDecrease
                + "} ";
    }

    /**
     * Loads HdrBrightnessData from DisplayConfiguration
     */
    @Nullable
    public static HdrBrightnessData loadConfig(DisplayConfiguration config) {
        HdrBrightnessConfig hdrConfig = config.getHdrBrightnessConfig();
        if (hdrConfig == null) {
            return null;
        }

        List<NonNegativeFloatToFloatPoint> points = hdrConfig.getBrightnessMap().getPoint();
        Map<Float, Float> brightnessLimits = new HashMap<>();
        for (NonNegativeFloatToFloatPoint point: points) {
            brightnessLimits.put(point.getFirst().floatValue(), point.getSecond().floatValue());
        }

        return new HdrBrightnessData(brightnessLimits,
                hdrConfig.getBrightnessIncreaseDebounceMillis().longValue(),
                hdrConfig.getScreenBrightnessRampIncrease().floatValue(),
                hdrConfig.getBrightnessDecreaseDebounceMillis().longValue(),
                hdrConfig.getScreenBrightnessRampDecrease().floatValue());
    }
}
