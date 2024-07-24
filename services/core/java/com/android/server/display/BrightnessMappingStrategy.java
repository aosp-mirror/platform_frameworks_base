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

import static android.text.TextUtils.formatSimple;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.TypedArray;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessCorrection;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LongArray;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessUtils;
import com.android.internal.util.Preconditions;
import com.android.server.display.utils.Plog;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A utility to map from an ambient brightness to a display's "backlight" brightness based on the
 * available display information and brightness configuration.
 *
 * Note that without a mapping from the nits to a display backlight level, any
 * {@link BrightnessConfiguration}s that are set are just ignored.
 */
public abstract class BrightnessMappingStrategy {
    private static final String TAG = "BrightnessMappingStrategy";

    public static final float INVALID_LUX = -1;
    public static final float INVALID_NITS = -1;

    private static final float LUX_GRAD_SMOOTHING = 0.25f;
    private static final float MAX_GRAD = 1.0f;
    private static final float SHORT_TERM_MODEL_THRESHOLD_RATIO = 0.6f;

    // Constant that ensures that each step of the curve can increase by up to at least
    // MIN_PERMISSABLE_INCREASE. Otherwise when the brightness is set to 0, the curve will never
    // increase and will always be 0.
    private static final float MIN_PERMISSABLE_INCREASE =  0.004f;

    protected boolean mLoggingEnabled;

    private static final Plog PLOG = Plog.createSystemPlog(TAG);

    /**
     * Creates a BrightnessMapping strategy. We do not create a simple mapping strategy for idle
     * mode.
     *
     * @param context
     * @param displayDeviceConfig
     * @param mode The auto-brightness mode. Different modes use different brightness curves
     * @param displayWhiteBalanceController
     * @return the BrightnessMappingStrategy
     */
    @Nullable
    static BrightnessMappingStrategy create(Context context,
            DisplayDeviceConfig displayDeviceConfig,
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode,
            @Nullable DisplayWhiteBalanceController displayWhiteBalanceController) {

        // Display independent, mode dependent values
        float[] brightnessLevelsNits = null;
        float[] brightnessLevels = null;
        float[] luxLevels = null;
        int preset = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL, UserHandle.USER_CURRENT);
        switch (mode) {
            case AUTO_BRIGHTNESS_MODE_DEFAULT -> {
                brightnessLevelsNits = displayDeviceConfig.getAutoBrightnessBrighteningLevelsNits();
                luxLevels = displayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(mode, preset);
                brightnessLevels =
                        displayDeviceConfig.getAutoBrightnessBrighteningLevels(mode, preset);
            }
            case AUTO_BRIGHTNESS_MODE_IDLE -> {
                brightnessLevelsNits = getFloatArray(context.getResources().obtainTypedArray(
                        com.android.internal.R.array.config_autoBrightnessDisplayValuesNitsIdle));
                luxLevels = getLuxLevels(context.getResources().getIntArray(
                        com.android.internal.R.array.config_autoBrightnessLevelsIdle));
            }
            case AUTO_BRIGHTNESS_MODE_DOZE -> {
                luxLevels = displayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(mode, preset);
                brightnessLevels =
                        displayDeviceConfig.getAutoBrightnessBrighteningLevels(mode, preset);
            }
        }

        // Display independent, mode independent values
        float autoBrightnessAdjustmentMaxGamma = context.getResources().getFraction(
                com.android.internal.R.fraction.config_autoBrightnessAdjustmentMaxGamma,
                1, 1);
        long shortTermModelTimeout = context.getResources().getInteger(
                com.android.internal.R.integer.config_autoBrightnessShortTermModelTimeout);

        // Display dependent values - used for physical mapping strategy nits -> brightness
        final float[] nitsRange = displayDeviceConfig.getNits();
        final float[] brightnessRange = displayDeviceConfig.getBrightness();

        if (isValidMapping(nitsRange, brightnessRange)
                && isValidMapping(luxLevels, brightnessLevelsNits)) {
            BrightnessConfiguration.Builder builder = new BrightnessConfiguration.Builder(
                    luxLevels, brightnessLevelsNits);
            builder.setShortTermModelTimeoutMillis(shortTermModelTimeout);
            builder.setShortTermModelLowerLuxMultiplier(SHORT_TERM_MODEL_THRESHOLD_RATIO);
            builder.setShortTermModelUpperLuxMultiplier(SHORT_TERM_MODEL_THRESHOLD_RATIO);
            return new PhysicalMappingStrategy(builder.build(), nitsRange, brightnessRange,
                    autoBrightnessAdjustmentMaxGamma, mode, displayWhiteBalanceController);
        } else if (isValidMapping(luxLevels, brightnessLevels)) {
            return new SimpleMappingStrategy(luxLevels, brightnessLevels,
                    autoBrightnessAdjustmentMaxGamma, shortTermModelTimeout, mode);
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

    /**
     * Extracts a float array from the specified {@link TypedArray}.
     *
     * @param array The array to convert.
     * @return the given array as a float array.
     */
    public static float[] getFloatArray(TypedArray array) {
        final int N = array.length();
        float[] vals = new float[N];
        for (int i = 0; i < N; i++) {
            vals[i] = array.getFloat(i, PowerManager.BRIGHTNESS_OFF_FLOAT);
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
     * Enable/disable logging.
     *
     * @param loggingEnabled
     *      Whether logging should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return false;
        }
        mLoggingEnabled = loggingEnabled;
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
     * Gets the current {@link BrightnessConfiguration}.
     */
    @Nullable
    public abstract BrightnessConfiguration getBrightnessConfiguration();

    /**
     * Returns the desired brightness of the display based on the current ambient lux, including
     * any context-related corrections.
     *
     * The returned brightness will be in the range [0, 1.0], where 1.0 is the display at max
     * brightness and 0 is the display at minimum brightness.
     *
     * @param lux The current ambient brightness in lux.
     * @param packageName the foreground app package name.
     * @param category the foreground app package category.
     * @return The desired brightness of the display normalized to the range [0, 1.0].
     */
    public abstract float getBrightness(float lux, String packageName,
            @ApplicationInfo.Category int category);

    /**
     * Returns the desired brightness of the display based on the current ambient lux.
     *
     * The returned brightness wil be in the range [0, 1.0], where 1.0 is the display at max
     * brightness and 0 is the display at minimum brightness.
     *
     * @param lux The current ambient brightness in lux.
     *
     * @return The desired brightness of the display normalized to the range [0, 1.0].
     */
    public float getBrightness(float lux) {
        return getBrightness(lux, null /* packageName */, ApplicationInfo.CATEGORY_UNDEFINED);
    }

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
     * Converts the provided brightness value to nits if possible.
     *
     * Returns {@link INVALID_NITS} if there's no available mapping for the brightness to nits.
     */
    public abstract float convertToNits(float brightness);

    /**
     * Converts the provided brightness value to nits if possible. Adjustments, such as RBC are
     * applied.
     *
     * Returns {@link INVALID_NITS} if there's no available mapping for the brightness to nits.
     */
    public abstract float convertToAdjustedNits(float brightness);

    /**
     * Converts the provided nit value to a float scale value if possible.
     *
     * Returns {@link PowerManager.BRIGHTNESS_INVALID_FLOAT} if there's no available mapping for
     * the nits to float scale.
     */
    public abstract float getBrightnessFromNits(float nits);

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

    /** Recalculates the backlight-to-nits and nits-to-backlight splines. */
    public abstract void recalculateSplines(boolean applyAdjustment, float[] adjustment);

    /**
     * Returns the timeout, in milliseconds for the short term model
     *
     * Timeout after which we remove the effects any user interactions might've had on the
     * brightness mapping. This timeout doesn't start until we transition to a non-interactive
     * display policy so that we don't reset while users are using their devices, but also so that
     * we don't erroneously keep the short-term model if the device is dozing but the
     * display is fully on.
     *
     * This timeout is also used when the device switches from interactive screen brightness mode
     * to idle screen brightness mode, to preserve the user's preference when they resume usage of
     * the device, within the specified timeframe.
     */
    public abstract long getShortTermModelTimeout();

    /**
     * Prints dump output for display dumpsys.
     */
    public abstract void dump(PrintWriter pw, float hbmTransition);

    abstract float getUserLux();

    abstract float getUserBrightness();

    /**
     * @return The auto-brightness mode of this mapping strategy. Different modes use different
     * brightness curves.
     */
    @AutomaticBrightnessController.AutomaticBrightnessMode
    abstract int getMode();

    /**
     * Check if the short term model should be reset given the anchor lux the last
     * brightness change was made at and the current ambient lux.
     */
    public boolean shouldResetShortTermModel(float ambientLux, float shortTermModelAnchor) {
        BrightnessConfiguration config = getBrightnessConfiguration();
        float minThresholdRatio = SHORT_TERM_MODEL_THRESHOLD_RATIO;
        float maxThresholdRatio = SHORT_TERM_MODEL_THRESHOLD_RATIO;
        if (config != null) {
            if (!Float.isNaN(config.getShortTermModelLowerLuxMultiplier())) {
                minThresholdRatio = config.getShortTermModelLowerLuxMultiplier();
            }
            if (!Float.isNaN(config.getShortTermModelUpperLuxMultiplier())) {
                maxThresholdRatio = config.getShortTermModelUpperLuxMultiplier();
            }
        }
        final float minAmbientLux =
                shortTermModelAnchor - shortTermModelAnchor * minThresholdRatio;
        final float maxAmbientLux =
                shortTermModelAnchor + shortTermModelAnchor * maxThresholdRatio;
        if (minAmbientLux < ambientLux && ambientLux <= maxAmbientLux) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "ShortTermModel: re-validate user data, ambient lux is "
                        + minAmbientLux + " < " + ambientLux + " < " + maxAmbientLux);
            }
            return false;
        } else {
            Slog.d(TAG, "ShortTermModel: reset data, ambient lux is " + ambientLux
                    + "(" + minAmbientLux + ", " + maxAmbientLux + ")");
            return true;
        }
    }

    private Pair<float[], float[]> insertControlPoint(
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
    private int findInsertionPoint(float[] arr, float val) {
        for (int i = 0; i < arr.length; i++) {
            if (val <= arr[i]) {
                return i;
            }
        }
        return arr.length;
    }

    private void smoothCurve(float[] lux, float[] brightness, int idx) {
        if (mLoggingEnabled) {
            PLOG.logCurve("unsmoothed curve", lux, brightness);
        }
        float prevLux = lux[idx];
        float prevBrightness = brightness[idx];
        // Smooth curve for data points above the newly introduced point
        for (int i = idx+1; i < lux.length; i++) {
            float currLux = lux[i];
            float currBrightness = brightness[i];
            float maxBrightness = MathUtils.max(
                    prevBrightness * permissibleRatio(currLux, prevLux),
                    prevBrightness + MIN_PERMISSABLE_INCREASE);
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
        if (mLoggingEnabled) {
            PLOG.logCurve("smoothed curve", lux, brightness);
        }
    }

    private float permissibleRatio(float currLux, float prevLux) {
        return MathUtils.pow((currLux + LUX_GRAD_SMOOTHING)
                / (prevLux + LUX_GRAD_SMOOTHING), MAX_GRAD);
    }

    protected float inferAutoBrightnessAdjustment(float maxGamma, float desiredBrightness,
            float currentBrightness) {
        float adjustment = 0;
        float gamma = Float.NaN;
        // Extreme edge cases: use a simpler heuristic, as proper gamma correction around the edges
        // affects the curve rather drastically.
        if (currentBrightness <= 0.1f || currentBrightness >= 0.9f) {
            adjustment = (desiredBrightness - currentBrightness);
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
            adjustment = -MathUtils.log(gamma) / MathUtils.log(maxGamma);
        }
        adjustment = MathUtils.constrain(adjustment, -1, +1);
        if (mLoggingEnabled) {
            Slog.d(TAG, "inferAutoBrightnessAdjustment: " + maxGamma + "^" + -adjustment + "=" +
                    MathUtils.pow(maxGamma, -adjustment) + " == " + gamma);
            Slog.d(TAG, "inferAutoBrightnessAdjustment: " + currentBrightness + "^" + gamma + "=" +
                    MathUtils.pow(currentBrightness, gamma) + " == " + desiredBrightness);
        }
        return adjustment;
    }

    protected Pair<float[], float[]> getAdjustedCurve(float[] lux, float[] brightness,
            float userLux, float userBrightness, float adjustment, float maxGamma) {
        float[] newLux = lux;
        float[] newBrightness = Arrays.copyOf(brightness, brightness.length);
        if (mLoggingEnabled) {
            PLOG.logCurve("unadjusted curve", newLux, newBrightness);
        }
        adjustment = MathUtils.constrain(adjustment, -1, 1);
        float gamma = MathUtils.pow(maxGamma, -adjustment);
        if (mLoggingEnabled) {
            Slog.d(TAG, "getAdjustedCurve: " + maxGamma + "^" + -adjustment + "=" +
                    MathUtils.pow(maxGamma, -adjustment) + " == " + gamma);
        }
        if (gamma != 1) {
            for (int i = 0; i < newBrightness.length; i++) {
                newBrightness[i] = MathUtils.pow(newBrightness[i], gamma);
            }
        }
        if (mLoggingEnabled) {
            PLOG.logCurve("gamma adjusted curve", newLux, newBrightness);
        }
        if (userLux != INVALID_LUX) {
            Pair<float[], float[]> curve = insertControlPoint(newLux, newBrightness, userLux,
                    userBrightness);
            newLux = curve.first;
            newBrightness = curve.second;
            if (mLoggingEnabled) {
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

        @AutomaticBrightnessController.AutomaticBrightnessMode
        private final int mMode;

        private Spline mSpline;
        private float mMaxGamma;
        private float mAutoBrightnessAdjustment;
        private float mUserLux;
        private float mUserBrightness;
        private long mShortTermModelTimeout;

        private SimpleMappingStrategy(float[] lux, float[] brightness, float maxGamma,
                long timeout, @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
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
                mBrightness[i] = brightness[i];
            }

            mMaxGamma = maxGamma;
            mAutoBrightnessAdjustment = 0;
            mUserLux = INVALID_LUX;
            mUserBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            if (mLoggingEnabled) {
                PLOG.start("simple mapping strategy");
            }
            computeSpline();
            mShortTermModelTimeout = timeout;
            mMode = mode;
        }

        @Override
        public long getShortTermModelTimeout() {
            return mShortTermModelTimeout;
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            return false;
        }

        @Override
        public BrightnessConfiguration getBrightnessConfiguration() {
            return null;
        }

        @Override
        public float getBrightness(float lux, String packageName,
                @ApplicationInfo.Category int category) {
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
            if (mLoggingEnabled) {
                Slog.d(TAG, "setAutoBrightnessAdjustment: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
                PLOG.start("auto-brightness adjustment");
            }
            mAutoBrightnessAdjustment = adjustment;
            computeSpline();
            return true;
        }

        @Override
        public float convertToNits(float brightness) {
            return INVALID_NITS;
        }

        @Override
        public float convertToAdjustedNits(float brightness) {
            return INVALID_NITS;
        }

        @Override
        public float getBrightnessFromNits(float nits) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }

        @Override
        public void addUserDataPoint(float lux, float brightness) {
            float unadjustedBrightness = getUnadjustedBrightness(lux);
            if (mLoggingEnabled) {
                Slog.d(TAG, "addUserDataPoint: (" + lux + "," + brightness + ")");
                PLOG.start("add user data point")
                        .logPoint("user data point", lux, brightness)
                        .logPoint("current brightness", lux, unadjustedBrightness);
            }
            float adjustment = inferAutoBrightnessAdjustment(mMaxGamma,
                    brightness /* desiredBrightness */,
                    unadjustedBrightness /* currentBrightness */);
            if (mLoggingEnabled) {
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
            if (mUserLux != INVALID_LUX) {
                if (mLoggingEnabled) {
                    Slog.d(TAG, "clearUserDataPoints: " + mAutoBrightnessAdjustment + " => 0");
                    PLOG.start("clear user data points")
                            .logPoint("user data point", mUserLux, mUserBrightness);
                }
                mAutoBrightnessAdjustment = 0;
                mUserLux = INVALID_LUX;
                mUserBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
                computeSpline();
            }
        }

        @Override
        public boolean hasUserDataPoints() {
            return mUserLux != INVALID_LUX;
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
        public void recalculateSplines(boolean applyAdjustment, float[] adjustment) {
            // Do nothing.
        }

        @Override
        public void dump(PrintWriter pw, float hbmTransition) {
            pw.println("SimpleMappingStrategy");
            pw.println("  mSpline=" + mSpline);
            pw.println("  mMaxGamma=" + mMaxGamma);
            pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
            pw.println("  mShortTermModelTimeout=" + mShortTermModelTimeout);
        }

        @Override
        int getMode() {
            return mMode;
        }

        @Override
        float getUserLux() {
            return mUserLux;
        }

        @Override
        float getUserBrightness() {
            return mUserBrightness;
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

        // A spline mapping from nits to the corresponding brightness value, normalized to the range
        // [0, 1.0].
        private Spline mNitsToBrightnessSpline;

        // A spline mapping from the system brightness value, normalized to the range [0, 1.0], to
        // a brightness in nits.
        private Spline mBrightnessToNitsSpline;

        // A spline mapping from nits with adjustments applied to the corresponding brightness
        // value, normalized to the range [0, 1.0].
        private Spline mAdjustedNitsToBrightnessSpline;

        // A spline mapping from the system brightness value, normalized to the range [0, 1.0], to
        // a brightness in nits with adjustments applied.
        private Spline mBrightnessToAdjustedNitsSpline;

        // The default brightness configuration.
        private final BrightnessConfiguration mDefaultConfig;

        private final float[] mNits;
        private final float[] mBrightness;

        private boolean mBrightnessRangeAdjustmentApplied;

        private final float mMaxGamma;
        private float mAutoBrightnessAdjustment;
        private float mUserLux;
        private float mUserBrightness;

        @Nullable
        private final DisplayWhiteBalanceController mDisplayWhiteBalanceController;

        @AutomaticBrightnessController.AutomaticBrightnessMode
        private final int mMode;

        // Previous short-term models and the times that they were computed stored for debugging
        // purposes
        private List<Spline> mPreviousBrightnessSplines = new ArrayList<>();
        private LongArray mBrightnessSplineChangeTimes = new LongArray();
        private static final int NO_OF_PREVIOUS_CONFIGS_TO_LOG = 5;
        private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

        public PhysicalMappingStrategy(BrightnessConfiguration config, float[] nits,
                float[] brightness, float maxGamma,
                @AutomaticBrightnessController.AutomaticBrightnessMode int mode,
                @Nullable DisplayWhiteBalanceController displayWhiteBalanceController) {

            Preconditions.checkArgument(nits.length != 0 && brightness.length != 0,
                    "Nits and brightness arrays must not be empty!");

            Preconditions.checkArgument(nits.length == brightness.length,
                    "Nits and brightness arrays must be the same length!");
            Objects.requireNonNull(config);
            Preconditions.checkArrayElementsInRange(nits, 0, Float.MAX_VALUE, "nits");
            Preconditions.checkArrayElementsInRange(brightness,
                    PowerManager.BRIGHTNESS_MIN, PowerManager.BRIGHTNESS_MAX, "brightness");

            mMode = mode;
            mMaxGamma = maxGamma;
            mAutoBrightnessAdjustment = 0;
            mUserLux = INVALID_LUX;
            mUserBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mDisplayWhiteBalanceController = displayWhiteBalanceController;

            mNits = nits;
            mBrightness = brightness;
            computeNitsBrightnessSplines(mNits);
            mAdjustedNitsToBrightnessSpline = mNitsToBrightnessSpline;
            mBrightnessToAdjustedNitsSpline = mBrightnessToNitsSpline;

            mDefaultConfig = config;
            if (mLoggingEnabled) {
                PLOG.start("physical mapping strategy");
            }
            mConfig = config;
            computeSpline();
        }

        @Override
        public long getShortTermModelTimeout() {
            if (mConfig.getShortTermModelTimeoutMillis() >= 0) {
                return mConfig.getShortTermModelTimeoutMillis();
            } else {
                return mDefaultConfig.getShortTermModelTimeoutMillis();
            }
        }

        @Override
        public boolean setBrightnessConfiguration(@Nullable BrightnessConfiguration config) {
            if (config == null) {
                config = mDefaultConfig;
            }
            if (config.equals(mConfig)) {
                return false;
            }
            if (mLoggingEnabled) {
                PLOG.start("brightness configuration");
            }
            mConfig = config;
            computeSpline();
            return true;
        }

        @Override
        public BrightnessConfiguration getBrightnessConfiguration() {
            return mConfig;
        }

        @Override
        public float getBrightness(float lux, String packageName,
                @ApplicationInfo.Category int category) {
            float nits = mBrightnessSpline.interpolate(lux);

            // Adjust nits to compensate for display white balance colour strength.
            if (mDisplayWhiteBalanceController != null) {
                nits = mDisplayWhiteBalanceController.calculateAdjustedBrightnessNits(nits);
            }

            float brightness = mAdjustedNitsToBrightnessSpline.interpolate(nits);
            // Correct the brightness according to the current application and its category, but
            // only if no user data point is set (as this will override the user setting).
            if (mUserLux == -1) {
                brightness = correctBrightness(brightness, packageName, category);
            } else if (mLoggingEnabled) {
                Slog.d(TAG, "user point set, correction not applied");
            }
            return brightness;
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
            if (mLoggingEnabled) {
                Slog.d(TAG, "setAutoBrightnessAdjustment: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
                PLOG.start("auto-brightness adjustment");
            }
            mAutoBrightnessAdjustment = adjustment;
            computeSpline();
            return true;
        }

        @Override
        public float convertToNits(float brightness) {
            return mBrightnessToNitsSpline.interpolate(brightness);
        }

        @Override
        public float convertToAdjustedNits(float brightness) {
            return mBrightnessToAdjustedNitsSpline.interpolate(brightness);
        }

        @Override
        public float getBrightnessFromNits(float nits) {
            return mNitsToBrightnessSpline.interpolate(nits);
        }

        @Override
        public void addUserDataPoint(float lux, float brightness) {
            float unadjustedBrightness = getUnadjustedBrightness(lux);
            if (mLoggingEnabled) {
                Slog.d(TAG, "addUserDataPoint: (" + lux + "," + brightness + ")");
                PLOG.start("add user data point")
                        .logPoint("user data point", lux, brightness)
                        .logPoint("current brightness", lux, unadjustedBrightness);
            }
            float adjustment = inferAutoBrightnessAdjustment(mMaxGamma,
                    brightness /* desiredBrightness */,
                    unadjustedBrightness /* currentBrightness */);
            if (mLoggingEnabled) {
                Slog.d(TAG, "addUserDataPoint: " + mAutoBrightnessAdjustment + " => " +
                        adjustment);
            }
            mAutoBrightnessAdjustment = adjustment;
            mUserLux = lux;
            mUserBrightness = brightness;
            computeSpline();

            if (mPreviousBrightnessSplines.size() == NO_OF_PREVIOUS_CONFIGS_TO_LOG) {
                mPreviousBrightnessSplines.remove(0);
                mBrightnessSplineChangeTimes.remove(0);
            }
            mPreviousBrightnessSplines.add(mBrightnessSpline);
            mBrightnessSplineChangeTimes.add(System.currentTimeMillis());
        }

        @Override
        public void clearUserDataPoints() {
            if (mUserLux != -1) {
                if (mLoggingEnabled) {
                    Slog.d(TAG, "clearUserDataPoints: " + mAutoBrightnessAdjustment + " => 0");
                    PLOG.start("clear user data points")
                            .logPoint("user data point", mUserLux, mUserBrightness);
                }
                mAutoBrightnessAdjustment = 0;
                mUserLux = INVALID_LUX;
                mUserBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
                computeSpline();
            }
        }

        @Override
        public boolean hasUserDataPoints() {
            return mUserLux != INVALID_LUX;
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
        public void recalculateSplines(boolean applyAdjustment, float[] adjustedNits) {
            mBrightnessRangeAdjustmentApplied = applyAdjustment;
            if (applyAdjustment) {
                mAdjustedNitsToBrightnessSpline = Spline.createSpline(adjustedNits, mBrightness);
                mBrightnessToAdjustedNitsSpline = Spline.createSpline(mBrightness, adjustedNits);
            } else {
                mAdjustedNitsToBrightnessSpline = mNitsToBrightnessSpline;
                mBrightnessToAdjustedNitsSpline = mBrightnessToNitsSpline;
            }
        }

        @Override
        public void dump(PrintWriter pw, float hbmTransition) {
            pw.println("PhysicalMappingStrategy");
            pw.println("  mConfig=" + mConfig);
            pw.println("  mBrightnessSpline=" + mBrightnessSpline);
            pw.println("  mNitsToBrightnessSpline=" + mNitsToBrightnessSpline);
            pw.println("  mBrightnessToNitsSpline=" + mBrightnessToNitsSpline);
            pw.println("  mAdjustedNitsToBrightnessSpline=" + mAdjustedNitsToBrightnessSpline);
            pw.println("  mAdjustedBrightnessToNitsSpline=" + mBrightnessToAdjustedNitsSpline);
            pw.println("  mMaxGamma=" + mMaxGamma);
            pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
            pw.println("  mUserLux=" + mUserLux);
            pw.println("  mUserBrightness=" + mUserBrightness);
            pw.println("  mDefaultConfig=" + mDefaultConfig);
            pw.println("  mBrightnessRangeAdjustmentApplied=" + mBrightnessRangeAdjustmentApplied);
            pw.println("  shortTermModelTimeout=" + getShortTermModelTimeout());

            pw.println("  Previous short-term models (oldest to newest): ");
            for (int i = 0; i < mPreviousBrightnessSplines.size(); i++) {
                pw.println("  Computed at "
                        + FORMAT.format(new Date(mBrightnessSplineChangeTimes.get(i))) + ": ");
                dumpConfigDiff(pw, hbmTransition, mPreviousBrightnessSplines.get(i),
                        /* shortTermModelOnly= */ true);
            }

            pw.println("  Difference between current config and default: ");
            dumpConfigDiff(pw, hbmTransition, mBrightnessSpline, /* shortTermModelOnly= */ false);
        }

        @Override
        int getMode() {
            return mMode;
        }

        @Override
        float getUserLux() {
            return mUserLux;
        }

        @Override
        float getUserBrightness() {
            return mUserBrightness;
        }

        /**
         * Prints out the default curve and how it differs from the long-term curve
         * and the current curve (in case the current curve includes short-term adjustments).
         *
         * @param pw The print-writer to write to.
         */
        private void dumpConfigDiff(PrintWriter pw, float hbmTransition, Spline brightnessSpline,
                boolean shortTermModelOnly) {
            Pair<float[], float[]> currentCurve = mConfig.getCurve();
            Spline currSpline = Spline.createSpline(currentCurve.first, currentCurve.second);

            Pair<float[], float[]> defaultCurve = mDefaultConfig.getCurve();
            Spline defaultSpline = Spline.createSpline(defaultCurve.first, defaultCurve.second);

            // Add the short-term curve lux point if present
            float[] luxes = currentCurve.first;
            if (mUserLux >= 0) {
                luxes = Arrays.copyOf(currentCurve.first, currentCurve.first.length + 1);
                luxes[luxes.length - 1] = mUserLux;
                Arrays.sort(luxes);
            }

            StringBuilder sbLux = null;
            StringBuilder sbNits = null;
            StringBuilder sbLong = null;
            StringBuilder sbShort = null;
            StringBuilder sbBrightness = null;
            StringBuilder sbPercent = null;
            StringBuilder sbPercentHbm = null;
            boolean needsHeaders = true;
            String separator = "";
            for (int i = 0; i < luxes.length; i++) {
                float lux = luxes[i];
                if (needsHeaders) {
                    sbLux = new StringBuilder("            lux: ");
                    sbNits = new StringBuilder("        default: ");
                    sbLong = new StringBuilder("      long-term: ");
                    sbShort = new StringBuilder("        current: ");
                    sbBrightness = new StringBuilder("    current(bl): ");
                    sbPercent = new StringBuilder("     current(%): ");
                    sbPercentHbm = new StringBuilder("  current(hbm%): ");
                    needsHeaders = false;
                }

                float defaultNits = defaultSpline.interpolate(lux);
                float longTermNits = currSpline.interpolate(lux);
                float shortTermNits = brightnessSpline.interpolate(lux);
                float brightness = mAdjustedNitsToBrightnessSpline.interpolate(shortTermNits);

                String luxPrefix = (lux == mUserLux ? "^" : "");
                String strLux = luxPrefix + toStrFloatForDump(lux);
                String strNits = toStrFloatForDump(defaultNits);
                String strLong = toStrFloatForDump(longTermNits);
                String strShort = toStrFloatForDump(shortTermNits);
                String strBrightness = toStrFloatForDump(brightness);
                String strPercent = String.valueOf(
                        Math.round(100.0f * BrightnessUtils.convertLinearToGamma(
                            (brightness / hbmTransition))));
                String strPercentHbm = String.valueOf(
                        Math.round(100.0f * BrightnessUtils.convertLinearToGamma(brightness)));

                int maxLen = Math.max(strLux.length(),
                        Math.max(strNits.length(),
                        Math.max(strBrightness.length(),
                        Math.max(strPercent.length(),
                        Math.max(strPercentHbm.length(),
                        Math.max(strLong.length(), strShort.length()))))));
                String format = separator + "%" + maxLen + "s";
                separator = ", ";

                sbLux.append(formatSimple(format, strLux));
                sbNits.append(formatSimple(format, strNits));
                sbLong.append(formatSimple(format, strLong));
                sbShort.append(formatSimple(format, strShort));
                sbBrightness.append(formatSimple(format, strBrightness));
                sbPercent.append(formatSimple(format, strPercent));
                sbPercentHbm.append(formatSimple(format, strPercentHbm));

                // At 80 chars, start another row
                if (sbLux.length() > 80 || (i == luxes.length - 1)) {
                    pw.println(sbLux);
                    if (!shortTermModelOnly) {
                        pw.println(sbNits);
                        pw.println(sbLong);
                    }
                    pw.println(sbShort);
                    pw.println(sbBrightness);
                    pw.println(sbPercent);
                    if (hbmTransition < PowerManager.BRIGHTNESS_MAX) {
                        pw.println(sbPercentHbm);
                    }
                    pw.println("");
                    needsHeaders = true;
                    separator = "";
                }
            }
        }

        private String toStrFloatForDump(float value) {
            if (value == 0.0f) {
                return "0";
            } else if (value < 0.1f) {
                return String.format(Locale.US, "%.3f", value);
            } else if (value < 1) {
                return String.format(Locale.US, "%.2f", value);
            } else if (value < 10) {
                return String.format(Locale.US, "%.1f", value);
            } else {
                return formatSimple("%d", Math.round(value));
            }
        }

        private void computeNitsBrightnessSplines(float[] nits) {
            mNitsToBrightnessSpline = Spline.createSpline(nits, mBrightness);
            mBrightnessToNitsSpline = Spline.createSpline(mBrightness, nits);
        }

        private void computeSpline() {
            Pair<float[], float[]> defaultCurve = mConfig.getCurve();
            float[] defaultLux = defaultCurve.first;
            float[] defaultNits = defaultCurve.second;
            float[] defaultBrightness = new float[defaultNits.length];
            for (int i = 0; i < defaultBrightness.length; i++) {
                defaultBrightness[i] = mAdjustedNitsToBrightnessSpline.interpolate(defaultNits[i]);
            }
            Pair<float[], float[]> curve = getAdjustedCurve(defaultLux, defaultBrightness, mUserLux,
                    mUserBrightness, mAutoBrightnessAdjustment, mMaxGamma);
            float[] lux = curve.first;
            float[] brightness = curve.second;
            float[] nits = new float[brightness.length];
            for (int i = 0; i < nits.length; i++) {
                nits[i] = mBrightnessToAdjustedNitsSpline.interpolate(brightness[i]);
            }
            mBrightnessSpline = Spline.createSpline(lux, nits);
        }

        private float getUnadjustedBrightness(float lux) {
            Pair<float[], float[]> curve = mConfig.getCurve();
            Spline spline = Spline.createSpline(curve.first, curve.second);
            return mAdjustedNitsToBrightnessSpline.interpolate(spline.interpolate(lux));
        }

        private float correctBrightness(float brightness, String packageName, int category) {
            if (packageName != null) {
                BrightnessCorrection correction = mConfig.getCorrectionByPackageName(packageName);
                if (correction != null) {
                    return correction.apply(brightness);
                }
            }
            if (category != ApplicationInfo.CATEGORY_UNDEFINED) {
                BrightnessCorrection correction = mConfig.getCorrectionByCategory(category);
                if (correction != null) {
                    return correction.apply(brightness);
                }
            }
            return brightness;
        }
    }
}
