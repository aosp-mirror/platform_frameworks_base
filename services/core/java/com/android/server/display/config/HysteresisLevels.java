/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.ArrayRes;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.utils.DebugUtils;

import java.util.Arrays;
import java.util.List;

/**
 * A helper class for handling access to illuminance hysteresis level values.
 */
public class HysteresisLevels {
    private static final String TAG = "HysteresisLevels";

    private static final float[] DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS = new float[]{100f};
    private static final float[] DEFAULT_AMBIENT_DARKENING_THRESHOLDS = new float[]{200f};
    private static final float[] DEFAULT_AMBIENT_THRESHOLD_LEVELS = new float[]{0f};
    private static final float[] DEFAULT_SCREEN_THRESHOLD_LEVELS = new float[]{0f};
    private static final float[] DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS = new float[]{100f};
    private static final float[] DEFAULT_SCREEN_DARKENING_THRESHOLDS = new float[]{200f};

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.HysteresisLevels DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    /**
     * The array that describes the brightness threshold percentage change
     * at each brightness level described in mBrighteningThresholdLevels.
     */
    private final float[] mBrighteningThresholdsPercentages;

    /**
     * The array that describes the brightness threshold percentage change
     * at each brightness level described in mDarkeningThresholdLevels.
     */
    private final float[] mDarkeningThresholdsPercentages;

    /**
     * The array that describes the range of brightness that each threshold percentage applies to
     *
     * The (zero-based) index is calculated as follows
     * value = current brightness value
     * level = mBrighteningThresholdLevels
     *
     * condition                       return
     * value < mBrighteningThresholdLevels[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mBrighteningThresholdsPercentages[n]
     * level[MAX] <= value             = mBrighteningThresholdsPercentages[MAX]
     */
    private final float[] mBrighteningThresholdLevels;

    /**
     * The array that describes the range of brightness that each threshold percentage applies to
     *
     * The (zero-based) index is calculated as follows
     * value = current brightness value
     * level = mDarkeningThresholdLevels
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mDarkeningThresholdsPercentages[n]
     * level[MAX] <= value             = mDarkeningThresholdsPercentages[MAX]
     */
    private final float[] mDarkeningThresholdLevels;

    /**
     * The minimum value decrease for darkening event
     */
    private final float mMinDarkening;

    /**
     * The minimum value increase for brightening event.
     */
    private final float mMinBrightening;

    /**
     * Creates a {@code HysteresisLevels} object with the given equal-length
     * float arrays.
     *
     * @param brighteningThresholdsPercentages 0-100 of thresholds
     * @param darkeningThresholdsPercentages   0-100 of thresholds
     * @param brighteningThresholdLevels       float array of brightness values in the relevant
     *                                         units
     * @param minBrighteningThreshold          the minimum value for which the brightening value
     *                                         needs to
     *                                         return.
     * @param minDarkeningThreshold            the minimum value for which the darkening value needs
     *                                         to return.
     */
    @VisibleForTesting
    public HysteresisLevels(float[] brighteningThresholdsPercentages,
            float[] darkeningThresholdsPercentages,
            float[] brighteningThresholdLevels, float[] darkeningThresholdLevels,
            float minDarkeningThreshold, float minBrighteningThreshold) {
        if (brighteningThresholdsPercentages.length != brighteningThresholdLevels.length
                || darkeningThresholdsPercentages.length != darkeningThresholdLevels.length) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrighteningThresholdsPercentages =
                setArrayFormat(brighteningThresholdsPercentages, 100.0f);
        mDarkeningThresholdsPercentages =
                setArrayFormat(darkeningThresholdsPercentages, 100.0f);
        mBrighteningThresholdLevels = setArrayFormat(brighteningThresholdLevels, 1.0f);
        mDarkeningThresholdLevels = setArrayFormat(darkeningThresholdLevels, 1.0f);
        mMinDarkening = minDarkeningThreshold;
        mMinBrightening = minBrighteningThreshold;
    }

    /**
     * Return the brightening hysteresis threshold for the given value level.
     */
    public float getBrighteningThreshold(float value) {
        final float brightConstant = getReferenceLevel(value,
                mBrighteningThresholdLevels, mBrighteningThresholdsPercentages);

        float brightThreshold = value * (1.0f + brightConstant);
        if (DEBUG) {
            Slog.d(TAG, "bright hysteresis constant=" + brightConstant + ", threshold="
                    + brightThreshold + ", value=" + value);
        }

        brightThreshold = Math.max(brightThreshold, value + mMinBrightening);
        return brightThreshold;
    }

    /**
     * Return the darkening hysteresis threshold for the given value level.
     */
    public float getDarkeningThreshold(float value) {
        final float darkConstant = getReferenceLevel(value,
                mDarkeningThresholdLevels, mDarkeningThresholdsPercentages);
        float darkThreshold = value * (1.0f - darkConstant);
        if (DEBUG) {
            Slog.d(TAG, "dark hysteresis constant=: " + darkConstant + ", threshold="
                    + darkThreshold + ", value=" + value);
        }
        darkThreshold = Math.min(darkThreshold, value - mMinDarkening);
        return Math.max(darkThreshold, 0.0f);
    }

    @VisibleForTesting
    public float[] getBrighteningThresholdsPercentages() {
        return mBrighteningThresholdsPercentages;
    }

    @VisibleForTesting
    public float[] getDarkeningThresholdsPercentages() {
        return mDarkeningThresholdsPercentages;
    }

    @VisibleForTesting
    public float[] getBrighteningThresholdLevels() {
        return mBrighteningThresholdLevels;
    }

    @VisibleForTesting
    public float[] getDarkeningThresholdLevels() {
        return mDarkeningThresholdLevels;
    }

    @VisibleForTesting
    public float getMinDarkening() {
        return mMinDarkening;
    }

    @VisibleForTesting
    public float getMinBrightening() {
        return mMinBrightening;
    }

    /**
     * Return the hysteresis constant for the closest threshold value from the given array.
     */
    private float getReferenceLevel(float value, float[] thresholdLevels,
            float[] thresholdPercentages) {
        if (thresholdLevels == null || thresholdLevels.length == 0 || value < thresholdLevels[0]) {
            return 0.0f;
        }
        int index = 0;
        while (index < thresholdLevels.length - 1 && value >= thresholdLevels[index + 1]) {
            index++;
        }
        return thresholdPercentages[index];
    }

    /**
     * Return a float array where each i-th element equals {@code configArray[i]/divideFactor}.
     */
    private float[] setArrayFormat(float[] configArray, float divideFactor) {
        float[] levelArray = new float[configArray.length];
        for (int index = 0; levelArray.length > index; ++index) {
            levelArray[index] = configArray[index] / divideFactor;
        }
        return levelArray;
    }

    @Override
    public String toString() {
        return "HysteresisLevels {"
                + "\n"
                + "    mBrighteningThresholdLevels=" + Arrays.toString(mBrighteningThresholdLevels)
                + ",\n"
                + "    mBrighteningThresholdsPercentages="
                + Arrays.toString(mBrighteningThresholdsPercentages)
                + ",\n"
                + "    mMinBrightening=" + mMinBrightening
                + ",\n"
                + "    mDarkeningThresholdLevels=" + Arrays.toString(mDarkeningThresholdLevels)
                + ",\n"
                + "    mDarkeningThresholdsPercentages="
                + Arrays.toString(mDarkeningThresholdsPercentages)
                + ",\n"
                + "    mMinDarkening=" + mMinDarkening
                + "\n"
                + "}";
    }

    /**
     * Creates hysteresis levels for Active Ambient Lux
     */
    public static HysteresisLevels loadAmbientBrightnessConfig(
            @Nullable DisplayConfiguration config, @Nullable Resources resources) {
        return createHysteresisLevels(
                config == null ? null : config.getAmbientBrightnessChangeThresholds(),
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientBrighteningThresholds,
                com.android.internal.R.array.config_ambientDarkeningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS,
                DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS,
                DEFAULT_AMBIENT_DARKENING_THRESHOLDS,
                resources, /* potentialOldBrightnessScale= */ false);
    }

    /**
     * Creates hysteresis levels for Active Screen Brightness
     */
    public static HysteresisLevels loadDisplayBrightnessConfig(
            @Nullable DisplayConfiguration config, @Nullable Resources resources) {
        return createHysteresisLevels(
                config == null ? null : config.getDisplayBrightnessChangeThresholds(),
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenBrighteningThresholds,
                com.android.internal.R.array.config_screenDarkeningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS,
                DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS,
                DEFAULT_SCREEN_DARKENING_THRESHOLDS,
                resources, /* potentialOldBrightnessScale= */ true);
    }

    /**
     * Creates hysteresis levels for Idle Ambient Lux
     */
    public static HysteresisLevels loadAmbientBrightnessIdleConfig(
            @Nullable DisplayConfiguration config, @Nullable Resources resources) {
        return createHysteresisLevels(
                config == null ? null : config.getAmbientBrightnessChangeThresholdsIdle(),
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientBrighteningThresholds,
                com.android.internal.R.array.config_ambientDarkeningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS,
                DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS,
                DEFAULT_AMBIENT_DARKENING_THRESHOLDS,
                resources, /* potentialOldBrightnessScale= */ false);
    }

    /**
     * Creates hysteresis levels for Idle Screen Brightness
     */
    public static HysteresisLevels loadDisplayBrightnessIdleConfig(
            @Nullable DisplayConfiguration config, @Nullable Resources resources) {
        return createHysteresisLevels(
                config == null ? null : config.getDisplayBrightnessChangeThresholdsIdle(),
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenBrighteningThresholds,
                com.android.internal.R.array.config_screenDarkeningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS,
                DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS,
                DEFAULT_SCREEN_DARKENING_THRESHOLDS,
                resources, /* potentialOldBrightnessScale= */ true);
    }


    private static HysteresisLevels createHysteresisLevels(
            @Nullable Thresholds thresholds,
            @ArrayRes int configLevels,
            @ArrayRes int configBrighteningThresholds,
            @ArrayRes int configDarkeningThresholds,
            float[] defaultLevels,
            float[] defaultBrighteningThresholds,
            float[] defaultDarkeningThresholds,
            @Nullable Resources resources,
            boolean potentialOldBrightnessScale
    ) {
        BrightnessThresholds brighteningThresholds =
                thresholds == null ? null : thresholds.getBrighteningThresholds();
        BrightnessThresholds darkeningThresholds =
                thresholds == null ? null : thresholds.getDarkeningThresholds();

        Pair<float[], float[]> brighteningPair = getBrightnessLevelAndPercentage(
                brighteningThresholds,
                configLevels, configBrighteningThresholds,
                defaultLevels, defaultBrighteningThresholds,
                potentialOldBrightnessScale, resources);

        Pair<float[], float[]> darkeningPair = getBrightnessLevelAndPercentage(
                darkeningThresholds,
                configLevels, configDarkeningThresholds,
                defaultLevels, defaultDarkeningThresholds,
                potentialOldBrightnessScale, resources);

        float brighteningMinThreshold =
                brighteningThresholds != null && brighteningThresholds.getMinimum() != null
                        ? brighteningThresholds.getMinimum().floatValue() : 0f;
        float darkeningMinThreshold =
                darkeningThresholds != null && darkeningThresholds.getMinimum() != null
                        ? darkeningThresholds.getMinimum().floatValue() : 0f;

        return new HysteresisLevels(
                brighteningPair.second,
                darkeningPair.second,
                brighteningPair.first,
                darkeningPair.first,
                darkeningMinThreshold,
                brighteningMinThreshold
        );
    }

    // Returns two float arrays, one of the brightness levels and one of the corresponding threshold
    // percentages for brightness levels at or above the lux value.
    // Historically, config.xml would have an array for brightness levels that was 1 shorter than
    // the levels array. Now we prepend a 0 to this array so they can be treated the same in the
    // rest of the framework. Values were also defined in different units (permille vs percent).
    private static Pair<float[], float[]> getBrightnessLevelAndPercentage(
            @Nullable BrightnessThresholds thresholds,
            int configFallbackThreshold, int configFallbackPermille,
            float[] defaultLevels, float[] defaultPercentage, boolean potentialOldBrightnessScale,
            @Nullable Resources resources) {
        if (thresholds != null
                && thresholds.getBrightnessThresholdPoints() != null
                && !thresholds.getBrightnessThresholdPoints().getBrightnessThresholdPoint()
                .isEmpty()) {

            // The level and percentages arrays are equal length in the ddc (new system)
            List<ThresholdPoint> points =
                    thresholds.getBrightnessThresholdPoints().getBrightnessThresholdPoint();
            final int size = points.size();

            float[] thresholdLevels = new float[size];
            float[] thresholdPercentages = new float[size];

            int i = 0;
            for (ThresholdPoint point : points) {
                thresholdLevels[i] = point.getThreshold().floatValue();
                thresholdPercentages[i] = point.getPercentage().floatValue();
                i++;
            }
            return new Pair<>(thresholdLevels, thresholdPercentages);
        } else if (resources != null) {
            // The level and percentages arrays are unequal length in config.xml (old system)
            // We prefix the array with a 0 value to ensure they can be handled consistently
            // with the new system.

            // Load levels array
            int[] configThresholdArray = resources.getIntArray(configFallbackThreshold);
            int configThresholdsSize;
            // null check is not needed here, however it test we are mocking resources that might
            // return null
            if (configThresholdArray == null || configThresholdArray.length == 0) {
                configThresholdsSize = 1;
            } else {
                configThresholdsSize = configThresholdArray.length + 1;
            }

            // Load percentage array
            int[] configPermille = resources.getIntArray(configFallbackPermille);

            // Ensure lengths match up
            // null check is not needed here, however it test we are mocking resources that might
            // return null
            boolean emptyArray = configPermille == null || configPermille.length == 0;
            if (emptyArray && configThresholdsSize == 1) {
                return new Pair<>(defaultLevels, defaultPercentage);
            }
            if (emptyArray || configPermille.length != configThresholdsSize) {
                throw new IllegalArgumentException(
                        "Brightness threshold arrays do not align in length");
            }

            // Calculate levels array
            float[] configThresholdWithZeroPrefixed = new float[configThresholdsSize];
            // Start at 1, so that 0 index value is 0.0f (default)
            for (int i = 1; i < configThresholdsSize; i++) {
                configThresholdWithZeroPrefixed[i] = (float) configThresholdArray[i - 1];
            }
            if (potentialOldBrightnessScale) {
                configThresholdWithZeroPrefixed =
                        constraintInRangeIfNeeded(configThresholdWithZeroPrefixed);
            }

            // Calculate percentages array
            float[] configPercentage = new float[configThresholdsSize];
            for (int i = 0; i < configPermille.length; i++) {
                configPercentage[i] = configPermille[i] / 10.0f;
            }
            return new Pair<>(configThresholdWithZeroPrefixed, configPercentage);
        } else {
            return new Pair<>(defaultLevels, defaultPercentage);
        }
    }

    /**
     * This check is due to historical reasons, where screen thresholdLevels used to be
     * integer values in the range of [0-255], but then was changed to be float values from [0,1].
     * To accommodate both the possibilities, we first check if all the thresholdLevels are in
     * [0,1], and if not, we divide all the levels with 255 to bring them down to the same scale.
     */
    private static float[] constraintInRangeIfNeeded(float[] thresholdLevels) {
        if (isAllInRange(thresholdLevels, /* minValueInclusive= */ 0.0f,
                /* maxValueInclusive= */ 1.0f)) {
            return thresholdLevels;
        }

        Slog.w(TAG, "Detected screen thresholdLevels on a deprecated brightness scale");
        float[] thresholdLevelsScaled = new float[thresholdLevels.length];
        for (int index = 0; thresholdLevels.length > index; ++index) {
            thresholdLevelsScaled[index] = thresholdLevels[index] / 255.0f;
        }
        return thresholdLevelsScaled;
    }

    private static boolean isAllInRange(float[] configArray, float minValueInclusive,
            float maxValueInclusive) {
        for (float v : configArray) {
            if (v < minValueInclusive || v > maxValueInclusive) {
                return false;
            }
        }
        return true;
    }

}
