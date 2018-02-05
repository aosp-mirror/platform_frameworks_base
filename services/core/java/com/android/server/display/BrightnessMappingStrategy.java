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
import java.util.Arrays;

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

    private static final float LUX_GRAD_SMOOTHING = 0.25f;
    private static final float MAX_GRAD = 1.0f;

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
     * Converts the provided backlight value to nits if possible.
     *
     * Returns -1.0f if there's no available mapping for the backlight to nits.
     */
    public abstract float convertToNits(int backlight);

    /**
     * Adds a user interaction data point to the brightness mapping.
     *
     * This data point <b>must</b> exist on the brightness curve as a result of this call. This is
     * so that the next time we come to query what the screen brightness should be, we get what the
     * user requested rather than immediately changing to some other value.
     *
     * Currently, we only keep track of one of these at a time to constrain what can happen to the
     * curve.
     */
    public abstract void addUserDataPoint(float lux, float brightness);

    /**
     * Removes any short term adjustments made to the curve from user interactions.
     *
     * Note that this does *not* reset the mapping to its initial state, any brightness
     * configurations that have been applied will continue to be in effect. This solely removes the
     * effects of user interactions on the model.
     */
    public abstract void clearUserDataPoints();

    /** @return true if there are any short term adjustments applied to the curve */
    public abstract boolean hasUserDataPoints();

    /** @return true if the current brightness config is the default one */
    public abstract boolean isDefaultConfig();

    public abstract void dump(PrintWriter pw);

    private static float normalizeAbsoluteBrightness(int brightness) {
        brightness = MathUtils.constrain(brightness,
                PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
        return (float) brightness / PowerManager.BRIGHTNESS_ON;
    }

    private static Spline createSpline(float[] x, float[] y) {
        Spline spline = Spline.createSpline(x, y);
        if (DEBUG) {
            Slog.d(TAG, "Spline: " + spline);
            for (float v = 1f; v < x[x.length - 1] * 1.25f; v *= 1.25f) {
                Slog.d(TAG, String.format("  %7.1f: %7.1f", v, spline.interpolate(v)));
            }
        }
        return spline;
    }

    private static Pair<float[], float[]> insertControlPoint(
            float[] luxLevels, float[] brightnessLevels, float lux, float brightness) {
        if (DEBUG) {
            Slog.d(TAG, "Inserting new control point at (" + lux + ", " + brightness + ")");
        }
        final int idx = findInsertionPoint(luxLevels, lux);
        final float[] newLuxLevels;
        final float[] newBrightnessLevels;
        if (idx == luxLevels.length) {
            newLuxLevels = Arrays.copyOf(luxLevels, luxLevels.length + 1);
            newBrightnessLevels  = Arrays.copyOf(brightnessLevels, brightnessLevels.length + 1);
            newLuxLevels[idx] = lux;
            newBrightnessLevels[idx] = brightness;
        } else if (luxLevels[idx] == lux) {
            newLuxLevels = Arrays.copyOf(luxLevels, luxLevels.length);
            newBrightnessLevels = Arrays.copyOf(brightnessLevels, brightnessLevels.length);
            newBrightnessLevels[idx] = brightness;
        } else {
            newLuxLevels = Arrays.copyOf(luxLevels, luxLevels.length + 1);
            System.arraycopy(newLuxLevels, idx, newLuxLevels, idx+1, luxLevels.length - idx);
            newLuxLevels[idx] = lux;
            newBrightnessLevels  = Arrays.copyOf(brightnessLevels, brightnessLevels.length + 1);
            System.arraycopy(newBrightnessLevels, idx, newBrightnessLevels, idx+1,
                    brightnessLevels.length - idx);
            newBrightnessLevels[idx] = brightness;
        }
        smoothCurve(newLuxLevels, newBrightnessLevels, idx);
        return Pair.create(newLuxLevels, newBrightnessLevels);
    }

    /**
     * Returns the index of the first value that's less than or equal to {@code val}.
     *
     * This assumes that {@code arr} is sorted. If all values in {@code arr} are greater
     * than val, then it will return the length of arr as the insertion point.
     */
    private static int findInsertionPoint(float[] arr, float val) {
        for (int i = 0; i < arr.length; i++) {
            if (val <= arr[i]) {
                return i;
            }
        }
        return arr.length;
    }

    private static void smoothCurve(float[] lux, float[] brightness, int idx) {
        if (DEBUG) {
            Slog.d(TAG, "smoothCurve(lux=" + Arrays.toString(lux)
                    + ", brightness=" + Arrays.toString(brightness)
                    + ", idx=" + idx + ")");
        }
        float prevLux = lux[idx];
        float prevBrightness = brightness[idx];
        // Smooth curve for data points above the newly introduced point
        for (int i = idx+1; i < lux.length; i++) {
            float currLux = lux[i];
            float currBrightness = brightness[i];
            float maxBrightness = prevBrightness * permissibleRatio(currLux, prevLux);
            float newBrightness = MathUtils.constrain(
                    currBrightness, prevBrightness, maxBrightness);
            if (newBrightness == currBrightness) {
                break;
            }
            prevLux = currLux;
            prevBrightness = newBrightness;
            brightness[i] = newBrightness;
        }

        // Smooth curve for data points below the newly introduced point
        prevLux = lux[idx];
        prevBrightness = brightness[idx];
        for (int i = idx-1; i >= 0; i--) {
            float currLux = lux[i];
            float currBrightness = brightness[i];
            float minBrightness = prevBrightness * permissibleRatio(currLux, prevLux);
            float newBrightness = MathUtils.constrain(
                    currBrightness, minBrightness, prevBrightness);
            if (newBrightness == currBrightness) {
                break;
            }
            prevLux = currLux;
            prevBrightness = newBrightness;
            brightness[i] = newBrightness;
        }
        if (DEBUG) {
            Slog.d(TAG, "Smoothed Curve: lux=" + Arrays.toString(lux)
                    + ", brightness=" + Arrays.toString(brightness));
        }
    }

    private static float permissibleRatio(float currLux, float prevLux) {
        return MathUtils.exp(MAX_GRAD
                * (MathUtils.log(currLux + LUX_GRAD_SMOOTHING)
                    - MathUtils.log(prevLux + LUX_GRAD_SMOOTHING)));
    }

    /**
     * A {@link BrightnessMappingStrategy} that maps from ambient room brightness directly to the
     * backlight of the display.
     *
     * Since we don't have information about the display's physical brightness, any brightness
     * configurations that are set are just ignored.
     */
    private static class SimpleMappingStrategy extends BrightnessMappingStrategy {
        // Lux control points
        private final float[] mLux;
        // Brightness control points normalized to [0, 1]
        private final float[] mBrightness;

        private Spline mSpline;
        private float mUserLux;
        private float mUserBrightness;

        public SimpleMappingStrategy(float[] lux, int[] brightness) {
            Preconditions.checkArgument(lux.length != 0 && brightness.length != 0,
                    "Lux and brightness arrays must not be empty!");
            Preconditions.checkArgument(lux.length == brightness.length,
                    "Lux and brightness arrays must be the same length!");
            Preconditions.checkArrayElementsInRange(lux, 0, Float.MAX_VALUE, "lux");
            Preconditions.checkArrayElementsInRange(brightness,
                    0, Integer.MAX_VALUE, "brightness");

            final int N = brightness.length;
            mLux = new float[N];
            mBrightness = new float[N];
            for (int i = 0; i < N; i++) {
                mLux[i] = lux[i];
                mBrightness[i] = normalizeAbsoluteBrightness(brightness[i]);
            }

            mSpline = createSpline(mLux, mBrightness);
            mUserLux = -1;
            mUserBrightness = -1;
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
        public float convertToNits(int backlight) {
            return -1.0f;
        }

        @Override
        public void addUserDataPoint(float lux, float brightness) {
            if (DEBUG){
                Slog.d(TAG, "addUserDataPoint(lux=" + lux + ", brightness=" + brightness + ")");
            }
            Pair<float[], float[]> curve = insertControlPoint(mLux, mBrightness, lux, brightness);
            mSpline = createSpline(curve.first, curve.second);
            mUserLux = lux;
            mUserBrightness = brightness;
        }

        @Override
        public void clearUserDataPoints() {
            if (mUserLux != -1) {
                mSpline = createSpline(mLux, mBrightness);
                mUserLux = -1;
                mUserBrightness = -1;
            }
        }

        @Override
        public boolean hasUserDataPoints() {
            return mUserLux != -1;
        }

        @Override
        public boolean isDefaultConfig() {
            return true;
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("SimpleMappingStrategy");
            pw.println("  mSpline=" + mSpline);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
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

        // The default brightness configuration.
        private final BrightnessConfiguration mDefaultConfig;

        // A spline mapping from the device's backlight value, normalized to the range [0, 1.0], to
        // a brightness in nits.
        private Spline mBacklightToNitsSpline;

        private float mUserLux;
        private float mUserBrightness;

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

            mUserLux = -1;
            mUserBrightness = -1;

            // Setup the backlight spline
            final int N = nits.length;
            float[] normalizedBacklight = new float[N];
            for (int i = 0; i < N; i++) {
                normalizedBacklight[i] = normalizeAbsoluteBrightness(backlight[i]);
            }

            mNitsToBacklightSpline = createSpline(nits, normalizedBacklight);
            mBacklightToNitsSpline = createSpline(normalizedBacklight, nits);

            mDefaultConfig = config;
            setBrightnessConfiguration(config);
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            if (config == null) {
                config = mDefaultConfig;
            }
            if (config.equals(mConfig)) {
                return false;
            }

            Pair<float[], float[]> curve = config.getCurve();
            mBrightnessSpline = createSpline(curve.first /*lux*/, curve.second /*nits*/);
            mConfig = config;
            return true;
        }

        @Override
        public float getBrightness(float lux) {
            float nits = mBrightnessSpline.interpolate(lux);
            float backlight = mNitsToBacklightSpline.interpolate(nits);
            return backlight;
        }

        @Override
        public float convertToNits(int backlight) {
            return mBacklightToNitsSpline.interpolate(normalizeAbsoluteBrightness(backlight));
        }

        @Override
        public void addUserDataPoint(float lux, float backlight) {
            if (DEBUG){
                Slog.d(TAG, "addUserDataPoint(lux=" + lux + ", backlight=" + backlight + ")");
            }
            float brightness = mBacklightToNitsSpline.interpolate(backlight);
            Pair<float[], float[]> defaultCurve = mConfig.getCurve();
            Pair<float[], float[]> newCurve =
                    insertControlPoint(defaultCurve.first, defaultCurve.second, lux, brightness);
            mBrightnessSpline = createSpline(newCurve.first, newCurve.second);
            mUserLux = lux;
            mUserBrightness = brightness;
        }

        @Override
        public void clearUserDataPoints() {
            if (mUserLux != -1) {
                Pair<float[], float[]> defaultCurve = mConfig.getCurve();
                mBrightnessSpline = createSpline(defaultCurve.first, defaultCurve.second);
                mUserLux = -1;
                mUserBrightness = -1;
            }
        }

        @Override
        public boolean hasUserDataPoints() {
            return mUserLux != -1;
        }

        @Override
        public boolean isDefaultConfig() {
            return mDefaultConfig.equals(mConfig);
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("PhysicalMappingStrategy");
            pw.println("  mConfig=" + mConfig);
            pw.println("  mBrightnessSpline=" + mBrightnessSpline);
            pw.println("  mNitsToBacklightSpline=" + mNitsToBacklightSpline);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
        }
    }
}
