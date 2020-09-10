/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.hvac;

import android.graphics.Color;

/**
 * Contains the logic for mapping colors to temperatures
 */
class TemperatureColorStore {

    private static class TemperatureColorValue {
        final float mTemperature;
        final int mColor;

        private TemperatureColorValue(float temperature, int color) {
            this.mTemperature = temperature;
            this.mColor = color;
        }

        float getTemperature() {
            return mTemperature;
        }

        int getColor() {
            return mColor;
        }
    }

    private static TemperatureColorValue tempToColor(float temperature, int color) {
        return new TemperatureColorValue(temperature, color);
    }

    private static final int COLOR_COLDEST = 0xFF406DFF;
    private static final int COLOR_COLD = 0xFF4094FF;
    private static final int COLOR_NEUTRAL = 0xFFF4F4F4;
    private static final int COLOR_WARM = 0xFFFF550F;
    private static final int COLOR_WARMEST = 0xFFFF0000;
    // must be sorted by temperature
    private static final TemperatureColorValue[] sTemperatureColorValues =
            {
                    // Celsius
                    tempToColor(19, COLOR_COLDEST),
                    tempToColor(21, COLOR_COLD),
                    tempToColor(23, COLOR_NEUTRAL),
                    tempToColor(25, COLOR_WARM),
                    tempToColor(27, COLOR_WARMEST),

                    // Switch over
                    tempToColor(45, COLOR_WARMEST),
                    tempToColor(45.00001f, COLOR_COLDEST),

                    // Farenheight
                    tempToColor(66, COLOR_COLDEST),
                    tempToColor(70, COLOR_COLD),
                    tempToColor(74, COLOR_NEUTRAL),
                    tempToColor(76, COLOR_WARM),
                    tempToColor(80, COLOR_WARMEST)
            };

    private static final int COLOR_UNSET = Color.BLACK;

    private final float[] mTempHsv1 = new float[3];
    private final float[] mTempHsv2 = new float[3];
    private final float[] mTempHsv3 = new float[3];

    int getMinColor() {
        return COLOR_COLDEST;
    }

    int getMaxColor() {
        return COLOR_WARMEST;
    }

    int getColorForTemperature(float temperature) {
        if (Float.isNaN(temperature)) {
            return COLOR_UNSET;
        }
        TemperatureColorValue bottomValue = sTemperatureColorValues[0];
        if (temperature <= bottomValue.getTemperature()) {
            return bottomValue.getColor();
        }
        TemperatureColorValue topValue =
                sTemperatureColorValues[sTemperatureColorValues.length - 1];
        if (temperature >= topValue.getTemperature()) {
            return topValue.getColor();
        }

        int index = binarySearch(temperature);
        if (index >= 0) {
            return sTemperatureColorValues[index].getColor();
        }

        index = -index - 1; // move to the insertion point

        TemperatureColorValue startValue = sTemperatureColorValues[index - 1];
        TemperatureColorValue endValue = sTemperatureColorValues[index];
        float fraction = (temperature - startValue.getTemperature()) / (endValue.getTemperature()
                - startValue.getTemperature());
        return lerpColor(fraction, startValue.getColor(), endValue.getColor());
    }

    int lerpColor(float fraction, int startColor, int endColor) {
        float[] startHsv = mTempHsv1;
        Color.colorToHSV(startColor, startHsv);
        float[] endHsv = mTempHsv2;
        Color.colorToHSV(endColor, endHsv);

        // If a target color is white/gray, it should use the same hue as the other target
        if (startHsv[1] == 0) {
            startHsv[0] = endHsv[0];
        }
        if (endHsv[1] == 0) {
            endHsv[0] = startHsv[0];
        }

        float[] outColor = mTempHsv3;
        outColor[0] = hueLerp(fraction, startHsv[0], endHsv[0]);
        outColor[1] = lerp(fraction, startHsv[1], endHsv[1]);
        outColor[2] = lerp(fraction, startHsv[2], endHsv[2]);

        return Color.HSVToColor(outColor);
    }

    private float hueLerp(float fraction, float start, float end) {
        // If in flat part of curve, no interpolation necessary
        if (start == end) {
            return start;
        }

        // If the hues are more than 180 degrees apart, go the other way around the color wheel
        // by moving the smaller value above 360
        if (Math.abs(start - end) > 180f) {
            if (start < end) {
                start += 360f;
            } else {
                end += 360f;
            }
        }
        // Lerp and ensure the final output is within [0, 360)
        return lerp(fraction, start, end) % 360f;

    }

    private float lerp(float fraction, float start, float end) {
        // If in flat part of curve, no interpolation necessary
        if (start == end) {
            return start;
        }

        // If outside bounds, use boundary value
        if (fraction >= 1) {
            return end;
        }
        if (fraction <= 0) {
            return start;
        }

        return (end - start) * fraction + start;
    }

    private int binarySearch(float temperature) {
        int low = 0;
        int high = sTemperatureColorValues.length;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            float midVal = sTemperatureColorValues[mid].getTemperature();

            if (midVal < temperature) {
                low = mid + 1;  // Neither val is NaN, thisVal is smaller
            } else if (midVal > temperature) {
                high = mid - 1; // Neither val is NaN, thisVal is larger
            } else {
                int midBits = Float.floatToIntBits(midVal);
                int keyBits = Float.floatToIntBits(temperature);
                if (midBits == keyBits) {    // Values are equal
                    return mid;             // Key found
                } else if (midBits < keyBits) { // (-0.0, 0.0) or (!NaN, NaN)
                    low = mid + 1;
                } else {                        /* (0.0, -0.0) or (NaN, !NaN)*/
                    high = mid - 1;
                }
            }
        }
        return -(low + 1);  // key not found.
    }
}
