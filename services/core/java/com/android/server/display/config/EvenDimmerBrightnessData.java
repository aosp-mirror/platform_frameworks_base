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
import android.util.Slog;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.List;

/**
 * Brightness config for even dimmer
 */
public class EvenDimmerBrightnessData {
    private static final String TAG = "EvenDimmerBrightnessData";

    /**
     * Brightness value at which even dimmer methods are used.
     */
    public final float mTransitionPoint;

    /**
     * Nits array, maps to mBacklight
     */
    public final float[] mNits;

    /**
     * Backlight array, maps to mBrightness and mNits
     */
    public final float[] mBacklight;

    /**
     * Brightness array, maps to mBacklight
     */
    public final float[] mBrightness;
    /**
     * Spline, mapping between backlight and nits
     */
    public final Spline mBacklightToNits;
    /**
     * Spline, mapping between nits and backlight
     */
    public final Spline mNitsToBacklight;
    /**
     * Spline, mapping between brightness and backlight
     */
    public final Spline mBrightnessToBacklight;
    /**
     * Spline, mapping between backlight and brightness
     */
    public final Spline mBacklightToBrightness;
    public final Spline mMinLuxToNits;

    @VisibleForTesting
    public EvenDimmerBrightnessData(float transitionPoint, float[] nits,
            float[] backlight, float[] brightness, Spline backlightToNits,
            Spline nitsToBacklight, Spline brightnessToBacklight, Spline backlightToBrightness,
            Spline minLuxToNits) {
        mTransitionPoint = transitionPoint;
        mNits = nits;
        mBacklight = backlight;
        mBrightness = brightness;
        mBacklightToNits = backlightToNits;
        mNitsToBacklight = nitsToBacklight;
        mBrightnessToBacklight = brightnessToBacklight;
        mBacklightToBrightness = backlightToBrightness;
        mMinLuxToNits = minLuxToNits;
    }

    @Override
    public String toString() {
        return "EvenDimmerBrightnessData {"
                + "mTransitionPoint: " + mTransitionPoint
                + ", mNits: " + Arrays.toString(mNits)
                + ", mBacklight: " + Arrays.toString(mBacklight)
                + ", mBrightness: " + Arrays.toString(mBrightness)
                + ", mBacklightToNits: " + mBacklightToNits
                + ", mNitsToBacklight: " + mNitsToBacklight
                + ", mBrightnessToBacklight: " + mBrightnessToBacklight
                + ", mBacklightToBrightness: " + mBacklightToBrightness
                + ", mMinLuxToNits: " + mMinLuxToNits
                + "} ";
    }

    /**
     * Loads EvenDimmerBrightnessData from DisplayConfiguration
     */
    @Nullable
    public static EvenDimmerBrightnessData loadConfig(DisplayConfiguration config) {
        final EvenDimmerMode lbm = config.getEvenDimmer();
        if (lbm == null) {
            return null;
        }

        boolean lbmIsEnabled = lbm.getEnabled();
        if (!lbmIsEnabled) {
            return null;
        }

        List<Float> nitsList = lbm.getNits();
        List<Float> backlightList = lbm.getBacklight();
        List<Float> brightnessList = lbm.getBrightness();
        float transitionPoints = lbm.getTransitionPoint().floatValue();

        if (nitsList.isEmpty()
                || backlightList.size() != brightnessList.size()
                || backlightList.size() != nitsList.size()) {
            Slog.e(TAG, "Invalid even dimmer array lengths");
            return null;
        }

        float[] nits = new float[nitsList.size()];
        float[] backlight = new float[nitsList.size()];
        float[] brightness = new float[nitsList.size()];

        for (int i = 0; i < nitsList.size(); i++) {
            nits[i] = nitsList.get(i);
            backlight[i] = backlightList.get(i);
            brightness[i] = brightnessList.get(i);
        }

        final NitsMap map = lbm.getLuxToMinimumNitsMap();
        if (map == null) {
            Slog.e(TAG, "Invalid min lux to nits mapping");
            return null;
        }
        final List<Point> points = map.getPoint();
        final int size = points.size();

        float[] minLux = new float[size];
        float[] minNits = new float[size];

        int i = 0;
        for (Point point : points) {
            minLux[i] = point.getValue().floatValue();
            minNits[i] = point.getNits().floatValue();
            if (i > 0) {
                if (minLux[i] < minLux[i - 1]) {
                    Slog.e(TAG, "minLuxToNitsSpline must be non-decreasing, ignoring rest "
                            + " of configuration. Value: " + minLux[i] + " < " + minLux[i - 1]);
                }
                if (minNits[i] < minNits[i - 1]) {
                    Slog.e(TAG, "minLuxToNitsSpline must be non-decreasing, ignoring rest "
                            + " of configuration. Nits: " + minNits[i] + " < " + minNits[i - 1]);
                }
            }
            ++i;
        }

        return new EvenDimmerBrightnessData(transitionPoints, nits, backlight, brightness,
                Spline.createSpline(backlight, nits),
                Spline.createSpline(nits, backlight),
                Spline.createSpline(brightness, backlight),
                Spline.createSpline(backlight, brightness),
                Spline.createSpline(minLux, minNits)
        );
    }
}
