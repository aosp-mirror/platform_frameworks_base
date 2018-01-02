/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.display;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.display.BrightnessConfiguration;
import android.os.PowerManager;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.Spline;

import com.android.internal.util.Preconditions;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * A utility to map from an ambient brightness to a display's "backlight" brightness based on the
 * available display information and brightness configuration.
 *
 * Note that without a mapping from the nits to a display backlight level, any
 * {@link BrightnessConfiguration}s that are set are just ignored.
 */
public abstract class BrightnessMappingStrategy {
    private static final String TAG = "BrightnessMappingStrategy";
    private static final boolean DEBUG = false;

    @Nullable
    public static BrightnessMappingStrategy create(Resources resources) {
        float[] luxLevels = getLuxLevels(resources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLevels));
        int[] brightnessLevelsBacklight = resources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);
        float[] brightnessLevelsNits = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits));

        float[] nitsRange = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits));
        int[] backlightRange = resources.getIntArray(
                com.android.internal.R.array.config_screenBrightnessBacklight);

        if (isValidMapping(nitsRange, backlightRange)
                && isValidMapping(luxLevels, brightnessLevelsNits)) {
            int minimumBacklight = resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessSettingMinimum);
            int maximumBacklight = resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessSettingMaximum);
            if (backlightRange[0] > minimumBacklight
                    || backlightRange[backlightRange.length - 1] < maximumBacklight) {
                Slog.w(TAG, "Screen brightness mapping does not cover whole range of available"
                        + " backlight values, autobrightness functionality may be impaired.");
            }
            BrightnessConfiguration.Builder builder = new BrightnessConfiguration.Builder();
            builder.setCurve(luxLevels, brightnessLevelsNits);
            return new PhysicalMappingStrategy(builder.build(), nitsRange, backlightRange);
        } else if (isValidMapping(luxLevels, brightnessLevelsBacklight)) {
            return new SimpleMappingStrategy(luxLevels, brightnessLevelsBacklight);
        } else {
            return null;
        }
    }

    private static float[] getLuxLevels(int[] lux) {
        // The first control point is implicit and always at 0 lux.
        float[] levels = new float[lux.length + 1];
        for (int i = 0; i < lux.length; i++) {
            levels[i + 1] = (float) lux[i];
        }
        return levels;
    }

    private static float[] getFloatArray(TypedArray array) {
        final int N = array.length();
        float[] vals = new float[N];
        for (int i = 0; i < N; i++) {
            vals[i] = array.getFloat(i, -1.0f);
        }
        array.recycle();
        return vals;
    }

    private static boolean isValidMapping(float[] x, float[] y) {
        if (x == null || y == null || x.length == 0 || y.length == 0) {
            return false;
        }
        if (x.length != y.length) {
            return false;
        }
        final int N = x.length;
        float prevX = x[0];
        float prevY = y[0];
        if (prevX < 0 || prevY < 0 || Float.isNaN(prevX) || Float.isNaN(prevY)) {
            return false;
        }
        for (int i = 1; i < N; i++) {
            if (prevX >= x[i] || prevY > y[i]) {
                return false;
            }
            if (Float.isNaN(x[i]) || Float.isNaN(y[i])) {
                return false;
            }
            prevX = x[i];
            prevY = y[i];
        }
        return true;
    }

    private static boolean isValidMapping(float[] x, int[] y) {
        if (x == null || y == null || x.length == 0 || y.length == 0) {
            return false;
        }
        if (x.length != y.length) {
            return false;
        }
        final int N = x.length;
        float prevX = x[0];
        int prevY = y[0];
        if (prevX < 0 || prevY < 0 || Float.isNaN(prevX)) {
            return false;
        }
        for (int i = 1; i < N; i++) {
            if (prevX >= x[i] || prevY > y[i]) {
                return false;
            }
            if (Float.isNaN(x[i])) {
                return false;
            }
            prevX = x[i];
            prevY = y[i];
        }
        return true;
    }

    /**
     * Sets the {@link BrightnessConfiguration}.
     *
     * @param config The new configuration. If {@code null} is passed, the default configuration is
     *               used.
     * @return Whether the brightness configuration has changed.
     */
    public abstract boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config);

    /**
     * Returns the desired brightness of the display based on the current ambient lux.
     *
     * The returned brightness will be in the range [0, 1.0], where 1.0 is the display at max
     * brightness and 0 is the display at minimum brightness.
     *
     * @param lux The current ambient brightness in lux.
     * @return The desired brightness of the display normalized to the range [0, 1.0].
     */
    public abstract float getBrightness(float lux);

    /**
     * Gets the display's brightness in nits for the given backlight value.
     *
     * Returns -1.0f if there's no available mapping for the backlight to nits.
     */
    public abstract float getNits(int backlight);

    public abstract void dump(PrintWriter pw);

    private static float normalizeAbsoluteBrightness(int brightness) {
        brightness = MathUtils.constrain(brightness,
                PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
        return (float) brightness / PowerManager.BRIGHTNESS_ON;
    }


    /**
     * A {@link BrightnessMappingStrategy} that maps from ambient room brightness directly to the
     * backlight of the display.
     *
     * Since we don't have information about the display's physical brightness, any brightness
     * configurations that are set are just ignored.
     */
    private static class SimpleMappingStrategy extends BrightnessMappingStrategy {
        private final Spline mSpline;

        public SimpleMappingStrategy(float[] lux, int[] brightness) {
            Preconditions.checkArgument(lux.length != 0 && brightness.length != 0,
                    "Lux and brightness arrays must not be empty!");
            Preconditions.checkArgument(lux.length == brightness.length,
                    "Lux and brightness arrays must be the same length!");
            Preconditions.checkArrayElementsInRange(lux, 0, Float.MAX_VALUE, "lux");
            Preconditions.checkArrayElementsInRange(brightness,
                    0, Integer.MAX_VALUE, "brightness");

            final int N = brightness.length;
            float[] x = new float[N];
            float[] y = new float[N];
            for (int i = 0; i < N; i++) {
                x[i] = lux[i];
                y[i] = normalizeAbsoluteBrightness(brightness[i]);
            }

            mSpline = Spline.createSpline(x, y);
            if (DEBUG) {
                Slog.d(TAG, "Auto-brightness spline: " + mSpline);
                for (float v = 1f; v < lux[lux.length - 1] * 1.25f; v *= 1.25f) {
                    Slog.d(TAG, String.format("  %7.1f: %7.1f", v, mSpline.interpolate(v)));
                }
            }
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            return false;
        }

        @Override
        public float getBrightness(float lux) {
            return mSpline.interpolate(lux);
        }

        @Override
        public float getNits(int backlight) {
            return -1.0f;
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("SimpleMappingStrategy");
            pw.println("  mSpline=" + mSpline);
        }
    }

    /** A {@link BrightnessMappingStrategy} that maps from ambient room brightness to the physical
     * range of the display, rather than to the range of the backlight control (typically 0-255).
     *
     * By mapping through the physical brightness, the curve becomes portable across devices and
     * gives us more resolution in the resulting mapping.
     */
    @VisibleForTesting
    static class PhysicalMappingStrategy extends BrightnessMappingStrategy {
        // The current brightness configuration.
        private BrightnessConfiguration mConfig;

        // A spline mapping from the current ambient light in lux to the desired display brightness
        // in nits.
        private Spline mBrightnessSpline;

        // A spline mapping from nits to the corresponding backlight value, normalized to the range
        // [0, 1.0].
        private final Spline mNitsToBacklightSpline;

        // A spline mapping from the device's backlight value, normalized to the range [0, 1.0], to
        // a brightness in nits.
        private final Spline mBacklightToNitsSpline;

        // The default brightness configuration.
        private final BrightnessConfiguration mDefaultConfig;

        public PhysicalMappingStrategy(BrightnessConfiguration config,
                float[] nits, int[] backlight) {
            Preconditions.checkArgument(nits.length != 0 && backlight.length != 0,
                    "Nits and backlight arrays must not be empty!");
            Preconditions.checkArgument(nits.length == backlight.length,
                    "Nits and backlight arrays must be the same length!");
            Preconditions.checkNotNull(config);
            Preconditions.checkArrayElementsInRange(nits, 0, Float.MAX_VALUE, "nits");
            Preconditions.checkArrayElementsInRange(backlight,
                    PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON, "backlight");

            // Setup the backlight spline
            final int N = nits.length;
            float[] normalizedBacklight = new float[N];
            for (int i = 0; i < N; i++) {
                normalizedBacklight[i] = normalizeAbsoluteBrightness(backlight[i]);
            }

            mNitsToBacklightSpline = Spline.createSpline(nits, normalizedBacklight);
            mBacklightToNitsSpline = Spline.createSpline(normalizedBacklight, nits);
            if (DEBUG) {
                Slog.d(TAG, "Backlight spline: " + mNitsToBacklightSpline);
                for (float v = 1f; v < nits[nits.length - 1] * 1.25f; v *= 1.25f) {
                    Slog.d(TAG, String.format(
                                "  %7.1f: %7.1f", v, mNitsToBacklightSpline.interpolate(v)));
                }
            }

            mDefaultConfig = config;
            setBrightnessConfiguration(config);
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            if (config == null) {
                config = mDefaultConfig;
            }
            if (config.equals(mConfig)) {
                if (DEBUG) {
                    Slog.d(TAG, "Tried to set an identical brightness config, ignoring");
                }
                return false;
            }

            Pair<float[], float[]> curve = config.getCurve();
            mBrightnessSpline = Spline.createSpline(curve.first /*lux*/, curve.second /*nits*/);
            if (DEBUG) {
                Slog.d(TAG, "Brightness spline: " + mBrightnessSpline);
                final float[] lux = curve.first;
                for (float v = 1f; v < lux[lux.length - 1] * 1.25f; v *= 1.25f) {
                    Slog.d(TAG, String.format(
                                "  %7.1f: %7.1f", v, mBrightnessSpline.interpolate(v)));
                }
            }
            mConfig = config;
            return true;
        }

        @Override
        public float getBrightness(float lux) {
            return mNitsToBacklightSpline.interpolate(mBrightnessSpline.interpolate(lux));
        }

        @Override
        public float getNits(int backlight) {
            return mBacklightToNitsSpline.interpolate(normalizeAbsoluteBrightness(backlight));
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("PhysicalMappingStrategy");
            pw.println("  mConfig=" + mConfig);
            pw.println("  mBrightnessSpline=" + mBrightnessSpline);
            pw.println("  mNitsToBacklightSpline=" + mNitsToBacklightSpline);
        }
    }
}
