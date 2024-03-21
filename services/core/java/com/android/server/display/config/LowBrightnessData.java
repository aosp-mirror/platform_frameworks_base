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
 * Brightness config for low brightness mode
 */
public class LowBrightnessData {
    private static final String TAG = "LowBrightnessData";

    /**
     * Brightness value at which lower brightness methods are used.
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

    @VisibleForTesting
    public LowBrightnessData(float transitionPoint, float[] nits,
            float[] backlight, float[] brightness, Spline backlightToNits,
            Spline nitsToBacklight, Spline brightnessToBacklight, Spline backlightToBrightness) {
        mTransitionPoint = transitionPoint;
        mNits = nits;
        mBacklight = backlight;
        mBrightness = brightness;
        mBacklightToNits = backlightToNits;
        mNitsToBacklight = nitsToBacklight;
        mBrightnessToBacklight = brightnessToBacklight;
        mBacklightToBrightness = backlightToBrightness;
    }

    @Override
    public String toString() {
        return "LowBrightnessData {"
                + "mTransitionPoint: " + mTransitionPoint
                + ", mNits: " + Arrays.toString(mNits)
                + ", mBacklight: " + Arrays.toString(mBacklight)
                + ", mBrightness: " + Arrays.toString(mBrightness)
                + ", mBacklightToNits: " + mBacklightToNits
                + ", mNitsToBacklight: " + mNitsToBacklight
                + ", mBrightnessToBacklight: " + mBrightnessToBacklight
                + ", mBacklightToBrightness: " + mBacklightToBrightness
                + "} ";
    }

    /**
     * Loads LowBrightnessData from DisplayConfiguration
     */
    @Nullable
    public static LowBrightnessData loadConfig(DisplayConfiguration config) {
        final LowBrightnessMode lbm = config.getLowBrightness();
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
            Slog.e(TAG, "Invalid low brightness array lengths");
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

        return new LowBrightnessData(transitionPoints, nits, backlight, brightness,
                Spline.createSpline(backlight, nits),
                Spline.createSpline(nits, backlight),
                Spline.createSpline(brightness, backlight),
                Spline.createSpline(backlight, brightness)
                );
    }
}
