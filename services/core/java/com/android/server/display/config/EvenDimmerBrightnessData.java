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
    public final float transitionPoint;

    /**
     * Nits array, maps to mBacklight
     */
    public final float[] nits;

    /**
     * Backlight array, maps to mBrightness and mNits
     */
    public final float[] backlight;

    /**
     * Brightness array, maps to mBacklight
     */
    public final float[] brightness;

    /**
     * Spline, mapping between backlight and nits
     */
    public final Spline backlightToNits;

    /**
     * Spline, mapping between nits and backlight
     */
    public final Spline nitsToBacklight;

    /**
     * Spline, mapping between brightness and backlight
     */
    public final Spline brightnessToBacklight;

    /**
     * Spline, mapping between backlight and brightness
     */
    public final Spline backlightToBrightness;

    /**
     * Spline, mapping the minimum nits for each lux condition.
     */
    public final Spline minLuxToNits;

    @VisibleForTesting
    public EvenDimmerBrightnessData(float transitionPoint, float[] nits,
            float[] backlight, float[] brightness, Spline backlightToNits,
            Spline nitsToBacklight, Spline brightnessToBacklight, Spline backlightToBrightness,
            Spline minLuxToNits) {
        this.transitionPoint = transitionPoint;
        this.nits = nits;
        this.backlight = backlight;
        this.brightness = brightness;
        this.backlightToNits = backlightToNits;
        this.nitsToBacklight = nitsToBacklight;
        this.brightnessToBacklight = brightnessToBacklight;
        this.backlightToBrightness = backlightToBrightness;
        this.minLuxToNits = minLuxToNits;
    }

    @Override
    public String toString() {
        return "EvenDimmerBrightnessData {"
                + "transitionPoint: " + transitionPoint
                + ", nits: " + Arrays.toString(nits)
                + ", backlight: " + Arrays.toString(backlight)
                + ", brightness: " + Arrays.toString(brightness)
                + ", backlightToNits: " + backlightToNits
                + ", nitsToBacklight: " + nitsToBacklight
                + ", brightnessToBacklight: " + brightnessToBacklight
                + ", backlightToBrightness: " + backlightToBrightness
                + ", minLuxToNits: " + minLuxToNits
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

        ComprehensiveBrightnessMap map = lbm.getBrightnessMapping();
        if (map == null) {
            return null;
        }

        List<BrightnessPoint> brightnessPoints = map.getBrightnessPoint();
        if (brightnessPoints.isEmpty()) {
            return null;
        }

        float[] nits = new float[brightnessPoints.size()];
        float[] backlight = new float[brightnessPoints.size()];
        float[] brightness = new float[brightnessPoints.size()];

        for (int i = 0; i < brightnessPoints.size(); i++) {
            BrightnessPoint val = brightnessPoints.get(i);
            nits[i] = val.getNits().floatValue();
            backlight[i] = val.getBacklight().floatValue();
            brightness[i] = val.getBrightness().floatValue();
        }

        float transitionPoint = lbm.getTransitionPoint().floatValue();
        final NitsMap minimumNitsMap = lbm.getLuxToMinimumNitsMap();
        if (minimumNitsMap == null) {
            Slog.e(TAG, "Invalid min lux to nits mapping");
            return null;
        }
        final List<Point> points = minimumNitsMap.getPoint();
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

        return new EvenDimmerBrightnessData(transitionPoint, nits, backlight, brightness,
                new Spline.LinearSpline(backlight, nits),
                new Spline.LinearSpline(nits, backlight),
                new Spline.LinearSpline(brightness, backlight),
                new Spline.LinearSpline(backlight, brightness),
                Spline.createSpline(minLux, minNits)
        );
    }
}
