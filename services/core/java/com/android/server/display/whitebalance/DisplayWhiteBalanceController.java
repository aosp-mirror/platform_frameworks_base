/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.whitebalance;

import android.annotation.NonNull;
import android.util.Slog;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.History;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * The DisplayWhiteBalanceController drives display white-balance (automatically correcting the
 * display color temperature depending on the ambient color temperature).
 *
 * The DisplayWhiteBalanceController:
 * - Uses the AmbientColorTemperatureSensor to detect changes in the ambient color temperature;
 * - Uses the AmbientColorTemperatureFilter to average these changes over time, filter out the
 *   noise, and arrive at an estimate of the actual ambient color temperature;
 * - Uses the DisplayWhiteBalanceThrottler to decide whether the display color temperature should
 *   be updated, suppressing changes that are too frequent or too minor.
 *
 *   Calls to this class must happen on the DisplayPowerController(2) handler, to ensure
 *   values do not get out of sync.
 */
public class DisplayWhiteBalanceController implements
        AmbientSensor.AmbientBrightnessSensor.Callbacks,
        AmbientSensor.AmbientColorTemperatureSensor.Callbacks {

    private static final String TAG = "DisplayWhiteBalanceController";
    private boolean mLoggingEnabled;

    private final ColorDisplayServiceInternal mColorDisplayServiceInternal;

    private final AmbientSensor.AmbientBrightnessSensor mBrightnessSensor;
    @VisibleForTesting
    AmbientFilter mBrightnessFilter;
    private final AmbientSensor.AmbientColorTemperatureSensor mColorTemperatureSensor;
    @VisibleForTesting
    AmbientFilter mColorTemperatureFilter;
    private final DisplayWhiteBalanceThrottler mThrottler;
    // In low brightness conditions the ALS readings are more noisy and produce
    // high errors. This default is introduced to provide a fixed display color
    // temperature when sensor readings become unreliable.
    private final float mLowLightAmbientColorTemperature;
    // As above, but used when in strong mode (idle screen brightness mode).
    private final float mLowLightAmbientColorTemperatureStrong;

    // In high brightness conditions certain color temperatures can cause peak display
    // brightness to drop. This fixed color temperature can be used to compensate for
    // this effect.
    private final float mHighLightAmbientColorTemperature;
    // As above, but used when in strong mode (idle screen brightness mode).
    private final float mHighLightAmbientColorTemperatureStrong;

    private final boolean mLightModeAllowed;

    private float mAmbientColorTemperature;
    @VisibleForTesting
    float mPendingAmbientColorTemperature;
    private float mLastAmbientColorTemperature;

    // The most recent ambient color temperature values are kept for debugging purposes.
    private final History mAmbientColorTemperatureHistory;

    // Override the ambient color temperature for debugging purposes.
    private float mAmbientColorTemperatureOverride;

    // A piecewise linear relationship between ambient and display color temperatures.
    private Spline.LinearSpline mAmbientToDisplayColorTemperatureSpline;

    // A piecewise linear relationship between ambient and display color temperatures, with a
    // stronger change between the two sets of values.
    private Spline.LinearSpline mStrongAmbientToDisplayColorTemperatureSpline;

    // In very low or very high brightness conditions Display White Balance should
    // be to set to a default instead of using mAmbientToDisplayColorTemperatureSpline.
    // However, setting Display White Balance based on thresholds can cause the
    // display to rapidly change color temperature. To solve this,
    // mLowLightAmbientBrightnessToBiasSpline and
    // mHighLightAmbientBrightnessToBiasSpline are used to smoothly interpolate from
    // ambient color temperature to the defaults. A piecewise linear relationship
    // between low light brightness and low light bias.
    private Spline.LinearSpline mLowLightAmbientBrightnessToBiasSpline;
    private Spline.LinearSpline mLowLightAmbientBrightnessToBiasSplineStrong;

    // A piecewise linear relationship between high light brightness and high light bias.
    private Spline.LinearSpline mHighLightAmbientBrightnessToBiasSpline;
    private Spline.LinearSpline mHighLightAmbientBrightnessToBiasSplineStrong;

    private float mLatestAmbientColorTemperature;
    private float mLatestAmbientBrightness;
    private float mLatestLowLightBias;
    private float mLatestHighLightBias;

    private boolean mEnabled;

    // Whether a higher-strength adjustment should be applied; this must be enabled in addition to
    // mEnabled in order to be applied.
    private boolean mStrongModeEnabled;

    // To decouple the DisplayPowerController from the DisplayWhiteBalanceController, the DPC
    // implements Callbacks and passes itself to the DWBC so it can call back into it without
    // knowing about it.
    private Callbacks mDisplayPowerControllerCallbacks;

    /**
     * @param brightnessSensor
     *      The sensor used to detect changes in the ambient brightness.
     * @param brightnessFilter
     *      The filter used to avergae ambient brightness changes over time, filter out the noise
     *      and arrive at an estimate of the actual ambient brightness.
     * @param colorTemperatureSensor
     *      The sensor used to detect changes in the ambient color temperature.
     * @param colorTemperatureFilter
     *      The filter used to average ambient color temperature changes over time, filter out the
     *      noise and arrive at an estimate of the actual ambient color temperature.
     * @param throttler
     *      The throttler used to determine whether the new display color temperature should be
     *      updated or not.
     * @param lowLightAmbientBrightnesses
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to lowLightAmbientColorTemperature.
     * @param lowLightAmbientBrightnessesStrong
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to lowLightAmbientColorTemperature.
     * @param lowLightAmbientBiases
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      lowLightAmbientColorTemperature.
     * @param lowLightAmbientBiasesStrong
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      lowLightAmbientColorTemperature.
     * @param lowLightAmbientColorTemperature
     *      The ambient color temperature to which we interpolate to based on the low light curve.
     * @param highLightAmbientBrightnesses
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to highLightAmbientColorTemperature.
     * @param highLightAmbientBrightnessesStrong
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to highLightAmbientColorTemperature.
     * @param highLightAmbientBiases
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      highLightAmbientColorTemperature.
     * @param highLightAmbientBiasesStrong
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      highLightAmbientColorTemperature.
     * @param highLightAmbientColorTemperature
     *      The ambient color temperature to which we interpolate to based on the high light curve.
     * @param ambientColorTemperatures
     *      The ambient color tempeartures used to map the ambient color temperature to the display
     *      color temperature (or null if no mapping is necessary).
     * @param displayColorTemperatures
     *      The display color temperatures used to map the ambient color temperature to the display
     *      color temperature (or null if no mapping is necessary).
     * @param lightModeAllowed
     *      Whether a lighter version should be applied when Strong Mode is not enabled.
     *
     * @throws NullPointerException
     *      - brightnessSensor is null;
     *      - brightnessFilter is null;
     *      - colorTemperatureSensor is null;
     *      - colorTemperatureFilter is null;
     *      - throttler is null.
     */
    public DisplayWhiteBalanceController(
            @NonNull AmbientSensor.AmbientBrightnessSensor brightnessSensor,
            @NonNull AmbientFilter brightnessFilter,
            @NonNull AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor,
            @NonNull AmbientFilter colorTemperatureFilter,
            @NonNull DisplayWhiteBalanceThrottler throttler,
            float[] lowLightAmbientBrightnesses,
            float[] lowLightAmbientBrightnessesStrong,
            float[] lowLightAmbientBiases,
            float[] lowLightAmbientBiasesStrong,
            float lowLightAmbientColorTemperature,
            float lowLightAmbientColorTemperatureStrong,
            float[] highLightAmbientBrightnesses,
            float[] highLightAmbientBrightnessesStrong,
            float[] highLightAmbientBiases,
            float[] highLightAmbientBiasesStrong,
            float highLightAmbientColorTemperature,
            float highLightAmbientColorTemperatureStrong,
            float[] ambientColorTemperatures,
            float[] displayColorTemperatures,
            float[] strongAmbientColorTemperatures,
            float[] strongDisplayColorTemperatures,
            boolean lightModeAllowed) {
        validateArguments(brightnessSensor, brightnessFilter, colorTemperatureSensor,
                colorTemperatureFilter, throttler);
        mBrightnessSensor = brightnessSensor;
        mBrightnessFilter = brightnessFilter;
        mColorTemperatureSensor = colorTemperatureSensor;
        mColorTemperatureFilter = colorTemperatureFilter;
        mThrottler = throttler;
        mLowLightAmbientColorTemperature = lowLightAmbientColorTemperature;
        mLowLightAmbientColorTemperatureStrong = lowLightAmbientColorTemperatureStrong;
        mHighLightAmbientColorTemperature = highLightAmbientColorTemperature;
        mHighLightAmbientColorTemperatureStrong = highLightAmbientColorTemperatureStrong;
        mAmbientColorTemperature = -1.0f;
        mPendingAmbientColorTemperature = -1.0f;
        mLastAmbientColorTemperature = -1.0f;
        mAmbientColorTemperatureHistory = new History(/* size= */ 50);
        mAmbientColorTemperatureOverride = -1.0f;
        mLightModeAllowed = lightModeAllowed;

        try {
            mLowLightAmbientBrightnessToBiasSpline = new Spline.LinearSpline(
                    lowLightAmbientBrightnesses, lowLightAmbientBiases);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create low light ambient brightness to bias spline.", e);
            mLowLightAmbientBrightnessToBiasSpline = null;
        }
        if (mLowLightAmbientBrightnessToBiasSpline != null) {
            if (mLowLightAmbientBrightnessToBiasSpline.interpolate(0.0f) != 0.0f ||
                    mLowLightAmbientBrightnessToBiasSpline.interpolate(Float.POSITIVE_INFINITY)
                    != 1.0f) {
                Slog.d(TAG, "invalid low light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mLowLightAmbientBrightnessToBiasSpline = null;
            }
        }

        try {
            mLowLightAmbientBrightnessToBiasSplineStrong = new Spline.LinearSpline(
                    lowLightAmbientBrightnessesStrong, lowLightAmbientBiasesStrong);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create strong low light ambient brightness to bias spline.", e);
            mLowLightAmbientBrightnessToBiasSplineStrong = null;
        }
        if (mLowLightAmbientBrightnessToBiasSplineStrong != null) {
            if (mLowLightAmbientBrightnessToBiasSplineStrong.interpolate(0.0f) != 0.0f
                    || mLowLightAmbientBrightnessToBiasSplineStrong.interpolate(
                    Float.POSITIVE_INFINITY) != 1.0f) {
                Slog.d(TAG, "invalid strong low light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mLowLightAmbientBrightnessToBiasSplineStrong = null;
            }
        }

        try {
            mHighLightAmbientBrightnessToBiasSpline = new Spline.LinearSpline(
                    highLightAmbientBrightnesses, highLightAmbientBiases);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create high light ambient brightness to bias spline.", e);
            mHighLightAmbientBrightnessToBiasSpline = null;
        }
        if (mHighLightAmbientBrightnessToBiasSpline != null) {
            if (mHighLightAmbientBrightnessToBiasSpline.interpolate(0.0f) != 0.0f ||
                    mHighLightAmbientBrightnessToBiasSpline.interpolate(Float.POSITIVE_INFINITY)
                    != 1.0f) {
                Slog.d(TAG, "invalid high light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mHighLightAmbientBrightnessToBiasSpline = null;
            }
        }

        try {
            mHighLightAmbientBrightnessToBiasSplineStrong = new Spline.LinearSpline(
                    highLightAmbientBrightnessesStrong, highLightAmbientBiasesStrong);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create strong high light ambient brightness to bias spline.", e);
            mHighLightAmbientBrightnessToBiasSplineStrong = null;
        }
        if (mHighLightAmbientBrightnessToBiasSplineStrong != null) {
            if (mHighLightAmbientBrightnessToBiasSplineStrong.interpolate(0.0f) != 0.0f
                    || mHighLightAmbientBrightnessToBiasSplineStrong.interpolate(
                    Float.POSITIVE_INFINITY) != 1.0f) {
                Slog.d(TAG, "invalid strong high light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mHighLightAmbientBrightnessToBiasSplineStrong = null;
            }
        }

        if (mLowLightAmbientBrightnessToBiasSpline != null &&
                mHighLightAmbientBrightnessToBiasSpline != null) {
            if (lowLightAmbientBrightnesses[lowLightAmbientBrightnesses.length - 1] >
                    highLightAmbientBrightnesses[0]) {
                Slog.d(TAG, "invalid low light and high light ambient brightness to bias spline "
                        + "combination, defined domains must not intersect.");
                mLowLightAmbientBrightnessToBiasSpline = null;
                mHighLightAmbientBrightnessToBiasSpline = null;
            }
        }

        if (mLowLightAmbientBrightnessToBiasSplineStrong != null
                && mHighLightAmbientBrightnessToBiasSplineStrong != null) {
            if (lowLightAmbientBrightnessesStrong[lowLightAmbientBrightnessesStrong.length - 1]
                    > highLightAmbientBrightnessesStrong[0]) {
                Slog.d(TAG,
                        "invalid strong low light and high light ambient brightness to bias "
                                + "spline combination, defined domains must not intersect.");
                mLowLightAmbientBrightnessToBiasSplineStrong = null;
                mHighLightAmbientBrightnessToBiasSplineStrong = null;
            }
        }

        try {
            mAmbientToDisplayColorTemperatureSpline = new Spline.LinearSpline(
                    ambientColorTemperatures, displayColorTemperatures);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create ambient to display color temperature spline.", e);
            mAmbientToDisplayColorTemperatureSpline = null;
        }

        try {
            mStrongAmbientToDisplayColorTemperatureSpline = new Spline.LinearSpline(
                    strongAmbientColorTemperatures, strongDisplayColorTemperatures);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create strong ambient to display color temperature spline", e);
        }

        mColorDisplayServiceInternal = LocalServices.getService(ColorDisplayServiceInternal.class);
    }

    /**
     * Enable/disable the controller.
     *
     * @param enabled
     *      Whether the controller should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setEnabled(boolean enabled) {
        if (enabled) {
            return enable();
        } else {
            return disable();
        }
    }

    /**
     * Enable/disable the stronger adjustment option.
     *
     * @param enabled whether the stronger adjustment option should be turned on
     */
    public void setStrongModeEnabled(boolean enabled) {
        mStrongModeEnabled = enabled;
        mColorDisplayServiceInternal.setDisplayWhiteBalanceAllowed(mLightModeAllowed
                || mStrongModeEnabled);
        if (mEnabled) {
            updateAmbientColorTemperature();
            updateDisplayColorTemperature();
        }
    }

    /**
     * Set an object to call back to when the display color temperature should be updated.
     *
     * @param callbacks
     *      The object to call back to.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setCallbacks(Callbacks callbacks) {
        if (mDisplayPowerControllerCallbacks == callbacks) {
            return false;
        }
        mDisplayPowerControllerCallbacks = callbacks;
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
        mBrightnessSensor.setLoggingEnabled(loggingEnabled);
        mBrightnessFilter.setLoggingEnabled(loggingEnabled);
        mColorTemperatureSensor.setLoggingEnabled(loggingEnabled);
        mColorTemperatureFilter.setLoggingEnabled(loggingEnabled);
        mThrottler.setLoggingEnabled(loggingEnabled);
        return true;
    }

    /**
     * Set the ambient color temperature override.
     *
     * This is only applied when the ambient color temperature changes or is updated (in which case
     * it overrides the ambient color temperature estimate); in other words, it doesn't necessarily
     * change the display color temperature immediately.
     *
     * @param ambientColorTemperatureOverride
     *      The ambient color temperature override.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setAmbientColorTemperatureOverride(float ambientColorTemperatureOverride) {
        if (mAmbientColorTemperatureOverride == ambientColorTemperatureOverride) {
            return false;
        }
        mAmbientColorTemperatureOverride = ambientColorTemperatureOverride;
        return true;
    }

    /**
     * Dump the state.
     *
     * @param writer
     *      The writer used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println("DisplayWhiteBalanceController:");
        writer.println("------------------------------");
        writer.println("  mLoggingEnabled=" + mLoggingEnabled);
        writer.println("  mEnabled=" + mEnabled);
        writer.println("  mStrongModeEnabled=" + mStrongModeEnabled);
        writer.println("  mDisplayPowerControllerCallbacks=" + mDisplayPowerControllerCallbacks);
        mBrightnessSensor.dump(writer);
        mBrightnessFilter.dump(writer);
        mColorTemperatureSensor.dump(writer);
        mColorTemperatureFilter.dump(writer);
        mThrottler.dump(writer);
        writer.println("  mLowLightAmbientColorTemperature=" + mLowLightAmbientColorTemperature);
        writer.println("  mLowLightAmbientColorTemperatureStrong="
                + mLowLightAmbientColorTemperatureStrong);
        writer.println("  mHighLightAmbientColorTemperature=" + mHighLightAmbientColorTemperature);
        writer.println("  mHighLightAmbientColorTemperatureStrong="
                + mHighLightAmbientColorTemperatureStrong);
        writer.println("  mAmbientColorTemperature=" + mAmbientColorTemperature);
        writer.println("  mPendingAmbientColorTemperature=" + mPendingAmbientColorTemperature);
        writer.println("  mLastAmbientColorTemperature=" + mLastAmbientColorTemperature);
        writer.println("  mAmbientColorTemperatureHistory=" + mAmbientColorTemperatureHistory);
        writer.println("  mAmbientColorTemperatureOverride=" + mAmbientColorTemperatureOverride);
        writer.println("  mAmbientToDisplayColorTemperatureSpline="
                + mAmbientToDisplayColorTemperatureSpline);
        writer.println("  mStrongAmbientToDisplayColorTemperatureSpline="
                + mStrongAmbientToDisplayColorTemperatureSpline);
        writer.println("  mLowLightAmbientBrightnessToBiasSpline="
                + mLowLightAmbientBrightnessToBiasSpline);
        writer.println("  mLowLightAmbientBrightnessToBiasSplineStrong="
                + mLowLightAmbientBrightnessToBiasSplineStrong);
        writer.println("  mHighLightAmbientBrightnessToBiasSpline="
                + mHighLightAmbientBrightnessToBiasSpline);
        writer.println("  mHighLightAmbientBrightnessToBiasSplineStrong="
                + mHighLightAmbientBrightnessToBiasSplineStrong);
    }

    @Override // AmbientSensor.AmbientBrightnessSensor.Callbacks
    public void onAmbientBrightnessChanged(float value) {
        final long time = System.currentTimeMillis();
        mBrightnessFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    @Override // AmbientSensor.AmbientColorTemperatureSensor.Callbacks
    public void onAmbientColorTemperatureChanged(float value) {
        final long time = System.currentTimeMillis();
        mColorTemperatureFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    /**
     * Updates the ambient color temperature.
     */
    public void updateAmbientColorTemperature() {
        final long time = System.currentTimeMillis();
        final float lowLightAmbientColorTemperature = mStrongModeEnabled
                ? mLowLightAmbientColorTemperatureStrong : mLowLightAmbientColorTemperature;
        final float highLightAmbientColorTemperature = mStrongModeEnabled
                ? mHighLightAmbientColorTemperatureStrong : mHighLightAmbientColorTemperature;
        final Spline.LinearSpline lowLightAmbientBrightnessToBiasSpline = mStrongModeEnabled
                ? mLowLightAmbientBrightnessToBiasSplineStrong
                : mLowLightAmbientBrightnessToBiasSpline;
        final Spline.LinearSpline highLightAmbientBrightnessToBiasSpline = mStrongModeEnabled
                ? mHighLightAmbientBrightnessToBiasSplineStrong
                : mHighLightAmbientBrightnessToBiasSpline;

        float ambientColorTemperature = mColorTemperatureFilter.getEstimate(time);
        mLatestAmbientColorTemperature = ambientColorTemperature;

        if (mStrongModeEnabled) {
            if (mStrongAmbientToDisplayColorTemperatureSpline != null
                    && ambientColorTemperature != -1.0f) {
                ambientColorTemperature =
                        mStrongAmbientToDisplayColorTemperatureSpline.interpolate(
                                ambientColorTemperature);
            }
        } else {
            if (mAmbientToDisplayColorTemperatureSpline != null
                    && ambientColorTemperature != -1.0f) {
                ambientColorTemperature =
                        mAmbientToDisplayColorTemperatureSpline.interpolate(
                                ambientColorTemperature);
            }
        }

        float ambientBrightness = mBrightnessFilter.getEstimate(time);
        mLatestAmbientBrightness = ambientBrightness;

        if (ambientColorTemperature != -1.0f && ambientBrightness != -1.0f
                && lowLightAmbientBrightnessToBiasSpline != null) {
            float bias = lowLightAmbientBrightnessToBiasSpline.interpolate(ambientBrightness);
            ambientColorTemperature =
                    bias * ambientColorTemperature + (1.0f - bias)
                    * lowLightAmbientColorTemperature;
            mLatestLowLightBias = bias;
        }
        if (ambientColorTemperature != -1.0f && ambientBrightness != -1.0f
                && highLightAmbientBrightnessToBiasSpline != null) {
            float bias = highLightAmbientBrightnessToBiasSpline.interpolate(ambientBrightness);
            ambientColorTemperature =
                    (1.0f - bias) * ambientColorTemperature + bias
                    * highLightAmbientColorTemperature;
            mLatestHighLightBias = bias;
        }

        if (mAmbientColorTemperatureOverride != -1.0f) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "override ambient color temperature: " + ambientColorTemperature
                        + " => " + mAmbientColorTemperatureOverride);
            }
            ambientColorTemperature = mAmbientColorTemperatureOverride;
        }

        // When the display color temperature needs to be updated, we call DisplayPowerController to
        // call our updateColorTemperature. The reason we don't call it directly is that we want
        // all changes to the system to happen in a predictable order in DPC's main loop
        // (updatePowerState).
        if (ambientColorTemperature == -1.0f || mThrottler.throttle(ambientColorTemperature)) {
            return;
        }

        if (mLoggingEnabled) {
            Slog.d(TAG, "pending ambient color temperature: " + ambientColorTemperature);
        }
        mPendingAmbientColorTemperature = ambientColorTemperature;
        if (mDisplayPowerControllerCallbacks != null) {
            mDisplayPowerControllerCallbacks.updateWhiteBalance();
        }
    }

    /**
     * Updates the display color temperature.
     */
    public void updateDisplayColorTemperature() {
        float ambientColorTemperature = -1.0f;

        // If both the pending and the current ambient color temperatures are -1, it means the DWBC
        // was just enabled, and we use the last ambient color temperature until new sensor events
        // give us a better estimate.
        if (mAmbientColorTemperature == -1.0f && mPendingAmbientColorTemperature == -1.0f) {
            ambientColorTemperature = mLastAmbientColorTemperature;
        }

        // Otherwise, we use the pending ambient color temperature, but only if it's non-trivial
        // and different than the current one.
        if (mPendingAmbientColorTemperature != -1.0f
                && mPendingAmbientColorTemperature != mAmbientColorTemperature) {
            ambientColorTemperature = mPendingAmbientColorTemperature;
        }

        if (ambientColorTemperature == -1.0f) {
            return;
        }

        mAmbientColorTemperature = ambientColorTemperature;
        if (mLoggingEnabled) {
            Slog.d(TAG, "ambient color temperature: " + mAmbientColorTemperature);
        }
        mPendingAmbientColorTemperature = -1.0f;
        mAmbientColorTemperatureHistory.add(mAmbientColorTemperature);
        Slog.d(TAG, "Display cct: " + mAmbientColorTemperature
                + " Latest ambient cct: " + mLatestAmbientColorTemperature
                + " Latest ambient lux: " + mLatestAmbientBrightness
                + " Latest low light bias: " + mLatestLowLightBias
                + " Latest high light bias: " + mLatestHighLightBias);
        mColorDisplayServiceInternal.setDisplayWhiteBalanceColorTemperature(
                (int) mAmbientColorTemperature);
        mLastAmbientColorTemperature = mAmbientColorTemperature;
    }

    /**
     * Calculate the adjusted brightness, in nits, due to the DWB color adaptation
     *
     * @param requestedBrightnessNits brightness the framework requires to be output
     * @return the adjusted brightness the framework needs to output to counter the drop in
     *         brightness due to DWB, or the requestedBrightnessNits if an adjustment cannot be made
     */
    public float calculateAdjustedBrightnessNits(float requestedBrightnessNits) {
        float luminance = mColorDisplayServiceInternal.getDisplayWhiteBalanceLuminance();
        if (luminance == -1) {
            return requestedBrightnessNits;
        }
        float effectiveBrightness = requestedBrightnessNits * luminance;
        return (requestedBrightnessNits - effectiveBrightness) + requestedBrightnessNits;
    }

    /**
     * The DisplayWhiteBalanceController decouples itself from its parent (DisplayPowerController)
     * by providing this interface to implement (and a method to set its callbacks object), and
     * calling these methods.
     */
    public interface Callbacks {

        /**
         * Called whenever the display white-balance state has changed.
         *
         * Usually, this means the estimated ambient color temperature has changed enough, and the
         * display color temperature should be updated; but it is also called if settings change.
         */
        void updateWhiteBalance();
    }

    private void validateArguments(AmbientSensor.AmbientBrightnessSensor brightnessSensor,
            AmbientFilter brightnessFilter,
            AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor,
            AmbientFilter colorTemperatureFilter,
            DisplayWhiteBalanceThrottler throttler) {
        Objects.requireNonNull(brightnessSensor, "brightnessSensor must not be null");
        Objects.requireNonNull(brightnessFilter, "brightnessFilter must not be null");
        Objects.requireNonNull(colorTemperatureSensor,
                "colorTemperatureSensor must not be null");
        Objects.requireNonNull(colorTemperatureFilter,
                "colorTemperatureFilter must not be null");
        Objects.requireNonNull(throttler, "throttler cannot be null");
    }

    private boolean enable() {
        if (mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "enabling");
        }
        mEnabled = true;
        mBrightnessSensor.setEnabled(true);
        mColorTemperatureSensor.setEnabled(true);
        return true;
    }

    private boolean disable() {
        if (!mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "disabling");
        }
        mEnabled = false;
        mBrightnessSensor.setEnabled(false);
        mBrightnessFilter.clear();
        mColorTemperatureSensor.setEnabled(false);
        mColorTemperatureFilter.clear();
        mThrottler.clear();
        mAmbientColorTemperature = -1.0f;
        mPendingAmbientColorTemperature = -1.0f;
        mColorDisplayServiceInternal.resetDisplayWhiteBalanceColorTemperature();
        return true;
    }

}
