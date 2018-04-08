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
import com.android.server.display.utils.Plog;

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

    private static final Plog PLOG = Plog.createSystemPlog(TAG);

    @Nullable
    public static BrightnessMappingStrategy create(Resources resources) {
        float[] luxLevels = getLuxLevels(resources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLevels));
        int[] brightnessLevelsBacklight = resources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);
        float[] brightnessLevelsNits = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits));
        float autoBrightnessAdjustmentMaxGamma = resources.getFraction(
                com.android.internal.R.fraction.config_autoBrightnessAdjustmentMaxGamma,
                1, 1);

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
                Slog.w(TAG, "Screen brightness mapping does not cover whole range of available " +
                        "backlight values, autobrightness functionality may be impaired.");
            }
            BrightnessConfiguration.Builder builder = new BrightnessConfiguration.Builder();
            builder.setCurve(luxLevels, brightnessLevelsNits);
            return new PhysicalMappingStrategy(builder.build(), nitsRange, backlightRange,
                    autoBrightnessAdjustmentMaxGamma);
        } else if (isValidMapping(luxLevels, brightnessLevelsBacklight)) {
            return new SimpleMappingStrategy(luxLevels, brightnessLevelsBacklight,
                    autoBrightnessAdjustmentMaxGamma);
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
     * Returns the current auto-brightness adjustment.
     *
     * The returned adjustment is a value in the range [-1.0, 1.0] such that
     * {@code config_autoBrightnessAdjustmentMaxGamma<sup>-adjustment</sup>} is used to gamma
     * correct the brightness curve.
     */
    public abstract float getAutoBrightnessAdjustment();

    /**
     * Sets the auto-brightness adjustment.
     *
     * @param adjustment The desired auto-brightness adjustment.
     * @return Whether the auto-brightness adjustment has changed.
     *
     * @Deprecated The auto-brightness adjustment should not be set directly, but rather inferred
     * from user data points.
     */
    public abstract boolean setAutoBrightnessAdjustment(float adjustment);

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

    /** @return True if there are any short term adjustments applied to the curve. */
    public abstract boolean hasUserDataPoints();

    /** @return True if the current brightness configuration is the default one. */
    public abstract boolean isDefaultConfig();

    /** @return The default brightness configuration. */
    public abstract BrightnessConfiguration getDefaultConfig();

    public abstract void dump(PrintWriter pw);

    private static float normalizeAbsoluteBrightness(int brightness) {
        brightness = MathUtils.constrain(brightness,
                PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
        return (float) brightness / PowerManager.BRIGHTNESS_ON;
    }

    private static Pair<float[], float[]> insertControlPoint(
            float[] luxLevels, float[] brightnessLevels, float lux, float brightness) {
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
            PLOG.logCurve("unsmoothed curve", lux, brightness);
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
            PLOG.logCurve("smoothed curve", lux, brightness);
        }
    }

    private static float permissibleRatio(float currLux, float prevLux) {
        return MathUtils.exp(MAX_GRAD
                * (MathUtils.log(currLux + LUX_GRAD_SMOOTHING)
                    - MathUtils.log(prevLux + LUX_GRAD_SMOOTHING)));
    }

    private static float inferAutoBrightnessAdjustment(float maxGamma,
            float desiredBrightness, float currentBrightness) {
        float adjustment = 0;
        float gamma = Float.NaN;
        // Extreme edge cases: use a simpler heuristic, as proper gamma correction around the edges
        // affects the curve rather drastically.
        if (currentBrightness <= 0.1f || currentBrightness >= 0.9f) {
            adjustment = (desiredBrightness - currentBrightness) * 2;
        // Edge case: darkest adjustment possible.
        } else if (desiredBrightness == 0) {
            adjustment = -1;
        // Edge case: brightest adjustment possible.
        } else if (desiredBrightness == 1) {
            adjustment = +1;
        } else {
            // current^gamma = desired => gamma = log[current](desired)
            gamma = MathUtils.log(desiredBrightness) / MathUtils.log(currentBrightness);
            // max^-adjustment = gamma => adjustment = -log[max](gamma)
            adjustment = -MathUtils.constrain(
                    MathUtils.log(gamma) / MathUtils.log(maxGamma), -1, 1);
        }
        if (DEBUG) {
            Slog.d(TAG, "inferAutoBrightnessAdjustment: " + maxGamma + "^" + -adjustment + "=" +
                    MathUtils.pow(maxGamma, -adjustment) + " == " + gamma);
            Slog.d(TAG, "inferAutoBrightnessAdjustment: " + currentBrightness + "^" + gamma + "=" +
                    MathUtils.pow(currentBrightness, gamma) + " == " + desiredBrightness);
        }
        return adjustment;
    }

    private static Pair<float[], float[]> getAdjustedCurve(float[] lux, float[] brightness,
            float userLux, float userBrightness, float adjustment, float maxGamma) {
        float[] newLux = lux;
        float[] newBrightness = Arrays.copyOf(brightness, brightness.length);
        if (DEBUG) {
            PLOG.logCurve("unadjusted curve", newLux, newBrightness);
        }
        adjustment = MathUtils.constrain(adjustment, -1, 1);
        float gamma = MathUtils.pow(maxGamma, -adjustment);
        if (DEBUG) {
            Slog.d(TAG, "getAdjustedCurve: " + maxGamma + "^" + -adjustment + "=" +
                    MathUtils.pow(maxGamma, -adjustment) + " == " + gamma);
        }
        if (gamma != 1) {
            for (int i = 0; i < newBrightness.length; i++) {
                newBrightness[i] = MathUtils.pow(newBrightness[i], gamma);
            }
        }
        if (DEBUG) {
            PLOG.logCurve("gamma adjusted curve", newLux, newBrightness);
        }
        if (userLux != -1) {
            Pair<float[], float[]> curve = insertControlPoint(newLux, newBrightness, userLux,
                    userBrightness);
            newLux = curve.first;
            newBrightness = curve.second;
            if (DEBUG) {
                PLOG.logCurve("gamma and user adjusted curve", newLux, newBrightness);
                // This is done for comparison.
                curve = insertControlPoint(lux, brightness, userLux, userBrightness);
                PLOG.logCurve("user adjusted curve", curve.first ,curve.second);
            }
        }
        return Pair.create(newLux, newBrightness);
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
        private float mMaxGamma;
        private float mAutoBrightnessAdjustment;
        private float mUserLux;
        private float mUserBrightness;

        public SimpleMappingStrategy(float[] lux, int[] brightness, float maxGamma) {
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

            mMaxGamma = maxGamma;
            mAutoBrightnessAdjustment = 0;
            mUserLux = -1;
            mUserBrightness = -1;
            if (DEBUG) {
                PLOG.start("simple mapping strategy");
            }
            computeSpline();
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
        public float getAutoBrightnessAdjustment() {
            return mAutoBrightnessAdjustment;
        }

        @Override
        public boolean setAutoBrightnessAdjustment(float adjustment) {
            adjustment = MathUtils.constrain(adjustment, -1, 1);
            if (adjustment == mAutoBrightnessAdjustment) {
                return false;
            }
            if (DEBUG) {
                Slog.d(TAG, "setAutoBrightnessAdjustment: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
                PLOG.start("auto-brightness adjustment");
            }
            mAutoBrightnessAdjustment = adjustment;
            computeSpline();
            return true;
        }

        @Override
        public float convertToNits(int backlight) {
            return -1.0f;
        }

        @Override
        public void addUserDataPoint(float lux, float brightness) {
            float unadjustedBrightness = getUnadjustedBrightness(lux);
            if (DEBUG){
                Slog.d(TAG, "addUserDataPoint: (" + lux + "," + brightness + ")");
                PLOG.start("add user data point")
                        .logPoint("user data point", lux, brightness)
                        .logPoint("current brightness", lux, unadjustedBrightness);
            }
            float adjustment = inferAutoBrightnessAdjustment(mMaxGamma,
                    brightness /* desiredBrightness */,
                    unadjustedBrightness /* currentBrightness */);
            if (DEBUG) {
                Slog.d(TAG, "addUserDataPoint: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
            }
            mAutoBrightnessAdjustment = adjustment;
            mUserLux = lux;
            mUserBrightness = brightness;
            computeSpline();
        }

        @Override
        public void clearUserDataPoints() {
            if (mUserLux != -1) {
                if (DEBUG) {
                    Slog.d(TAG, "clearUserDataPoints: " + mAutoBrightnessAdjustment + " => 0");
                    PLOG.start("clear user data points")
                            .logPoint("user data point", mUserLux, mUserBrightness);
                }
                mAutoBrightnessAdjustment = 0;
                mUserLux = -1;
                mUserBrightness = -1;
                computeSpline();
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
        public BrightnessConfiguration getDefaultConfig() {
            return null;
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("SimpleMappingStrategy");
            pw.println("  mSpline=" + mSpline);
            pw.println("  mMaxGamma=" + mMaxGamma);
            pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
        }

        private void computeSpline() {
            Pair<float[], float[]> curve = getAdjustedCurve(mLux, mBrightness, mUserLux,
                    mUserBrightness, mAutoBrightnessAdjustment, mMaxGamma);
            mSpline = Spline.createSpline(curve.first, curve.second);
        }

        private float getUnadjustedBrightness(float lux) {
            Spline spline = Spline.createSpline(mLux, mBrightness);
            return spline.interpolate(lux);
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

        private float mMaxGamma;
        private float mAutoBrightnessAdjustment;
        private float mUserLux;
        private float mUserBrightness;

        public PhysicalMappingStrategy(BrightnessConfiguration config, float[] nits,
                                       int[] backlight, float maxGamma) {
            Preconditions.checkArgument(nits.length != 0 && backlight.length != 0,
                    "Nits and backlight arrays must not be empty!");
            Preconditions.checkArgument(nits.length == backlight.length,
                    "Nits and backlight arrays must be the same length!");
            Preconditions.checkNotNull(config);
            Preconditions.checkArrayElementsInRange(nits, 0, Float.MAX_VALUE, "nits");
            Preconditions.checkArrayElementsInRange(backlight,
                    PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON, "backlight");

            mMaxGamma = maxGamma;
            mAutoBrightnessAdjustment = 0;
            mUserLux = -1;
            mUserBrightness = -1;

            // Setup the backlight spline
            final int N = nits.length;
            float[] normalizedBacklight = new float[N];
            for (int i = 0; i < N; i++) {
                normalizedBacklight[i] = normalizeAbsoluteBrightness(backlight[i]);
            }

            mNitsToBacklightSpline = Spline.createSpline(nits, normalizedBacklight);
            mBacklightToNitsSpline = Spline.createSpline(normalizedBacklight, nits);

            mDefaultConfig = config;
            if (DEBUG) {
                PLOG.start("physical mapping strategy");
            }
            mConfig = config;
            computeSpline();
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            if (config == null) {
                config = mDefaultConfig;
            }
            if (config.equals(mConfig)) {
                return false;
            }
            if (DEBUG) {
                PLOG.start("brightness configuration");
            }
            mConfig = config;
            computeSpline();
            return true;
        }

        @Override
        public float getBrightness(float lux) {
            float nits = mBrightnessSpline.interpolate(lux);
            float backlight = mNitsToBacklightSpline.interpolate(nits);
            return backlight;
        }

        @Override
        public float getAutoBrightnessAdjustment() {
            return mAutoBrightnessAdjustment;
        }

        @Override
        public boolean setAutoBrightnessAdjustment(float adjustment) {
            adjustment = MathUtils.constrain(adjustment, -1, 1);
            if (adjustment == mAutoBrightnessAdjustment) {
                return false;
            }
            if (DEBUG) {
                Slog.d(TAG, "setAutoBrightnessAdjustment: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
                PLOG.start("auto-brightness adjustment");
            }
            mAutoBrightnessAdjustment = adjustment;
            computeSpline();
            return true;
        }

        @Override
        public float convertToNits(int backlight) {
            return mBacklightToNitsSpline.interpolate(normalizeAbsoluteBrightness(backlight));
        }

        @Override
        public void addUserDataPoint(float lux, float brightness) {
            float unadjustedBrightness = getUnadjustedBrightness(lux);
            if (DEBUG){
                Slog.d(TAG, "addUserDataPoint: (" + lux + "," + brightness + ")");
                PLOG.start("add user data point")
                        .logPoint("user data point", lux, brightness)
                        .logPoint("current brightness", lux, unadjustedBrightness);
            }
            float adjustment = inferAutoBrightnessAdjustment(mMaxGamma,
                    brightness /* desiredBrightness */,
                    unadjustedBrightness /* currentBrightness */);
            if (DEBUG) {
                Slog.d(TAG, "addUserDataPoint: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
            }
            mAutoBrightnessAdjustment = adjustment;
            mUserLux = lux;
            mUserBrightness = brightness;
            computeSpline();
        }

        @Override
        public void clearUserDataPoints() {
            if (mUserLux != -1) {
                if (DEBUG) {
                    Slog.d(TAG, "clearUserDataPoints: " + mAutoBrightnessAdjustment + " => 0");
                    PLOG.start("clear user data points")
                            .logPoint("user data point", mUserLux, mUserBrightness);
                }
                mAutoBrightnessAdjustment = 0;
                mUserLux = -1;
                mUserBrightness = -1;
                computeSpline();
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
        public BrightnessConfiguration getDefaultConfig() {
            return mDefaultConfig;
        }

        @Override
        public void dump(PrintWriter pw) {
            pw.println("PhysicalMappingStrategy");
            pw.println("  mConfig=" + mConfig);
            pw.println("  mBrightnessSpline=" + mBrightnessSpline);
            pw.println("  mNitsToBacklightSpline=" + mNitsToBacklightSpline);
            pw.println("  mMaxGamma=" + mMaxGamma);
            pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
        }

        private void computeSpline() {
            Pair<float[], float[]> defaultCurve = mConfig.getCurve();
            float[] defaultLux = defaultCurve.first;
            float[] defaultNits = defaultCurve.second;
            float[] defaultBacklight = new float[defaultNits.length];
            for (int i = 0; i < defaultBacklight.length; i++) {
                defaultBacklight[i] = mNitsToBacklightSpline.interpolate(defaultNits[i]);
            }
            Pair<float[], float[]> curve = getAdjustedCurve(defaultLux, defaultBacklight, mUserLux,
                    mUserBrightness, mAutoBrightnessAdjustment, mMaxGamma);
            float[] lux = curve.first;
            float[] backlight = curve.second;
            float[] nits = new float[backlight.length];
            for (int i = 0; i < nits.length; i++) {
                nits[i] = mBacklightToNitsSpline.interpolate(backlight[i]);
            }
            mBrightnessSpline = Spline.createSpline(lux, nits);
        }

        private float getUnadjustedBrightness(float lux) {
            Pair<float[], float[]> curve = mConfig.getCurve();
            Spline spline = Spline.createSpline(curve.first, curve.second);
            return mNitsToBacklightSpline.interpolate(spline.interpolate(lux));
        }
    }
}
