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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.DREAM_AQI_COLOR_DEFAULT;
import static com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.DREAM_AQI_COLOR_THRESHOLDS;
import static com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.DREAM_AQI_COLOR_VALUES;

import android.util.Log;

import androidx.annotation.ColorInt;

import javax.inject.Inject;
import javax.inject.Named;

final class AirQualityColorPicker {
    private static final String TAG = "AirQualityColorPicker";
    private final int[] mThresholds;
    private final int[] mColorValues;
    private final int mDefaultColor;

    @Inject
    AirQualityColorPicker(@Named(DREAM_AQI_COLOR_THRESHOLDS) int[] thresholds,
            @Named(DREAM_AQI_COLOR_VALUES) int[] colorValues,
            @Named(DREAM_AQI_COLOR_DEFAULT) @ColorInt int defaultColor) {
        mThresholds = thresholds;
        mColorValues = colorValues;
        mDefaultColor = defaultColor;
    }

    @ColorInt
    int getColorForValue(String aqiString) {
        int size = mThresholds.length;
        if (mThresholds.length != mColorValues.length) {
            size = Math.min(mThresholds.length, mColorValues.length);
            Log.e(TAG,
                    "Threshold size ("
                            + mThresholds.length + ") does not match color value size ("
                            + mColorValues.length
                            + "). Taking the minimum, some values may be ignored.");

        }
        try {
            final int value = Integer.parseInt(aqiString.replaceAll("[^0-9]", ""));
            for (int i = size - 1; i >= 0; i--) {
                if (value > mThresholds[i]) {
                    return mColorValues[i];
                }
            }
            Log.e(TAG, "No matching AQI color for value: " + value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Could not read AQI value from:" + aqiString);
        }
        return mDefaultColor;
    }
}
