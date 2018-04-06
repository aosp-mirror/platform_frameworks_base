/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.server.EventLogTags;
import com.android.server.LocalServices;

import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.text.format.DateUtils;
import android.util.EventLog;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.PrintWriter;

class AutomaticBrightnessController {
    private static final String TAG = "AutomaticBrightnessController";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    // If true, enables the use of the screen auto-brightness adjustment setting.
    private static final boolean USE_SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT = true;

    // How long the current sensor reading is assumed to be valid beyond the current time.
    // This provides a bit of prediction, as well as ensures that the weight for the last sample is
    // non-zero, which in turn ensures that the total weight is non-zero.
    private static final long AMBIENT_LIGHT_PREDICTION_TIME_MILLIS = 100;

    // Debounce for sampling user-initiated changes in display brightness to ensure
    // the user is satisfied with the result before storing the sample.
    private static final int BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS = 10000;

    // Timeout after which we remove the effects any user interactions might've had on the
    // brightness mapping. This timeout doesn't start until we transition to a non-interactive
    // display policy so that we don't reset while users are using their devices, but also so that
    // we don't erroneously keep the short-term model if the device is dozing but the display is
    // fully on.
    private static final int SHORT_TERM_MODEL_TIMEOUT_MILLIS = 30000;

    private static final int MSG_UPDATE_AMBIENT_LUX = 1;
    private static final int MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE = 2;
    private static final int MSG_INVALIDATE_SHORT_TERM_MODEL = 3;

    // Length of the ambient light horizon used to calculate the long term estimate of ambient
    // light.
    private static final int AMBIENT_LIGHT_LONG_HORIZON_MILLIS = 10000;

    // Length of the ambient light horizon used to calculate short-term estimate of ambient light.
    private static final int AMBIENT_LIGHT_SHORT_HORIZON_MILLIS = 2000;

    // Callbacks for requesting updates to the display's power state
    private final Callbacks mCallbacks;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The light sensor, or null if not available or needed.
    private final Sensor mLightSensor;

    // The mapper to translate ambient lux to screen brightness in the range [0, 1.0].
    private final BrightnessMappingStrategy mBrightnessMapper;

    // The minimum and maximum screen brightnesses.
    private final int mScreenBrightnessRangeMinimum;
    private final int mScreenBrightnessRangeMaximum;
    private final float mDozeScaleFactor;

    // Initial light sensor event rate in milliseconds.
    private final int mInitialLightSensorRate;

    // Steady-state light sensor event rate in milliseconds.
    private final int mNormalLightSensorRate;

    // The current light sensor event rate in milliseconds.
    private int mCurrentLightSensorRate;

    // Stability requirements in milliseconds for accepting a new brightness level.  This is used
    // for debouncing the light sensor.  Different constants are used to debounce the light sensor
    // when adapting to brighter or darker environments.  This parameter controls how quickly
    // brightness changes occur in response to an observed change in light level that exceeds the
    // hysteresis threshold.
    private final long mBrighteningLightDebounceConfig;
    private final long mDarkeningLightDebounceConfig;

    // If true immediately after the screen is turned on the controller will try to adjust the
    // brightness based on the current sensor reads. If false, the controller will collect more data
    // and only then decide whether to change brightness.
    private final boolean mResetAmbientLuxAfterWarmUpConfig;

    // Period of time in which to consider light samples in milliseconds.
    private final int mAmbientLightHorizon;

    // The intercept used for the weighting calculation. This is used in order to keep all possible
    // weighting values positive.
    private final int mWeightingIntercept;

    // accessor object for determining thresholds to change brightness dynamically
    private final HysteresisLevels mDynamicHysteresis;

    // Amount of time to delay auto-brightness after screen on while waiting for
    // the light sensor to warm-up in milliseconds.
    // May be 0 if no warm-up is required.
    private int mLightSensorWarmUpTimeConfig;

    // Set to true if the light sensor is enabled.
    private boolean mLightSensorEnabled;

    // The time when the light sensor was enabled.
    private long mLightSensorEnableTime;

    // The currently accepted nominal ambient light level.
    private float mAmbientLux;

    // True if mAmbientLux holds a valid value.
    private boolean mAmbientLuxValid;

    // The ambient light level threshold at which to brighten or darken the screen.
    private float mBrighteningLuxThreshold;
    private float mDarkeningLuxThreshold;

    // The most recent light sample.
    private float mLastObservedLux;

    // The time of the most light recent sample.
    private long mLastObservedLuxTime;

    // The number of light samples collected since the light sensor was enabled.
    private int mRecentLightSamples;

    // A ring buffer containing all of the recent ambient light sensor readings.
    private AmbientLightRingBuffer mAmbientLightRingBuffer;

    // The handler
    private AutomaticBrightnessHandler mHandler;

    // The screen brightness level that has been chosen by the auto-brightness
    // algorithm.  The actual brightness should ramp towards this value.
    // We preserve this value even when we stop using the light sensor so
    // that we can quickly revert to the previous auto-brightness level
    // while the light sensor warms up.
    // Use -1 if there is no current auto-brightness value available.
    private int mScreenAutoBrightness = -1;

    // The current display policy. This is useful, for example,  for knowing when we're dozing,
    // where the light sensor may not be available.
    private int mDisplayPolicy = DisplayPowerRequest.POLICY_OFF;

    // True if we are collecting a brightness adjustment sample, along with some data
    // for the initial state of the sample.
    private boolean mBrightnessAdjustmentSamplePending;
    private float mBrightnessAdjustmentSampleOldLux;
    private int mBrightnessAdjustmentSampleOldBrightness;

    // When the short term model is invalidated, we don't necessarily reset it (i.e. clear the
    // user's adjustment) immediately, but wait for a drastic enough change in the ambient light.
    // The anchor determines what were the light levels when the user has set her preference, and
    // we use a relative threshold to determine when to revert to the OEM curve.
    private boolean mShortTermModelValid;
    private float mShortTermModelAnchor;
    private float SHORT_TERM_MODEL_THRESHOLD_RATIO = 0.6f;

    public AutomaticBrightnessController(Callbacks callbacks, Looper looper,
            SensorManager sensorManager, BrightnessMappingStrategy mapper,
            int lightSensorWarmUpTime, int brightnessMin, int brightnessMax, float dozeScaleFactor,
            int lightSensorRate, int initialLightSensorRate, long brighteningLightDebounceConfig,
            long darkeningLightDebounceConfig, boolean resetAmbientLuxAfterWarmUpConfig,
            int ambientLightHorizon, HysteresisLevels dynamicHysteresis) {
        mCallbacks = callbacks;
        mSensorManager = sensorManager;
        mBrightnessMapper = mapper;
        mScreenBrightnessRangeMinimum = brightnessMin;
        mScreenBrightnessRangeMaximum = brightnessMax;
        mLightSensorWarmUpTimeConfig = lightSensorWarmUpTime;
        mDozeScaleFactor = dozeScaleFactor;
        mNormalLightSensorRate = lightSensorRate;
        mInitialLightSensorRate = initialLightSensorRate;
        mCurrentLightSensorRate = -1;
        mBrighteningLightDebounceConfig = brighteningLightDebounceConfig;
        mDarkeningLightDebounceConfig = darkeningLightDebounceConfig;
        mResetAmbientLuxAfterWarmUpConfig = resetAmbientLuxAfterWarmUpConfig;
        mAmbientLightHorizon = ambientLightHorizon;
        mWeightingIntercept = ambientLightHorizon;
        mDynamicHysteresis = dynamicHysteresis;
        mShortTermModelValid = true;
        mShortTermModelAnchor = -1;

        mHandler = new AutomaticBrightnessHandler(looper);
        mAmbientLightRingBuffer =
            new AmbientLightRingBuffer(mNormalLightSensorRate, mAmbientLightHorizon);

        if (!DEBUG_PRETEND_LIGHT_SENSOR_ABSENT) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
    }

    public int getAutomaticScreenBrightness() {
        if (mDisplayPolicy == DisplayPowerRequest.POLICY_DOZE) {
            return (int) (mScreenAutoBrightness * mDozeScaleFactor);
        }
        return mScreenAutoBrightness;
    }

    public float getAutomaticScreenBrightnessAdjustment() {
        return mBrightnessMapper.getAutoBrightnessAdjustment();
    }

    public void configure(boolean enable, @Nullable BrightnessConfiguration configuration,
            float brightness, boolean userChangedBrightness, float adjustment,
            boolean userChangedAutoBrightnessAdjustment, int displayPolicy) {
        // While dozing, the application processor may be suspended which will prevent us from
        // receiving new information from the light sensor. On some devices, we may be able to
        // switch to a wake-up light sensor instead but for now we will simply disable the sensor
        // and hold onto the last computed screen auto brightness.  We save the dozing flag for
        // debugging purposes.
        boolean dozing = (displayPolicy == DisplayPowerRequest.POLICY_DOZE);
        boolean changed = setBrightnessConfiguration(configuration);
        changed |= setDisplayPolicy(displayPolicy);
        if (userChangedAutoBrightnessAdjustment) {
            changed |= setAutoBrightnessAdjustment(adjustment);
        }
        if (userChangedBrightness && enable) {
            // Update the brightness curve with the new user control point. It's critical this
            // happens after we update the autobrightness adjustment since it may reset it.
            changed |= setScreenBrightnessByUser(brightness);
        }
        final boolean userInitiatedChange =
                userChangedBrightness || userChangedAutoBrightnessAdjustment;
        if (userInitiatedChange && enable && !dozing) {
            prepareBrightnessAdjustmentSample();
        }
        changed |= setLightSensorEnabled(enable && !dozing);
        if (changed) {
            updateAutoBrightness(false /*sendUpdate*/);
        }
    }

    public boolean hasUserDataPoints() {
        return mBrightnessMapper.hasUserDataPoints();
    }

    public boolean isDefaultConfig() {
        return mBrightnessMapper.isDefaultConfig();
    }

    public BrightnessConfiguration getDefaultConfig() {
        return mBrightnessMapper.getDefaultConfig();
    }

    private boolean setDisplayPolicy(int policy) {
        if (mDisplayPolicy == policy) {
            return false;
        }
        final int oldPolicy = mDisplayPolicy;
        mDisplayPolicy = policy;
        if (DEBUG) {
            Slog.d(TAG, "Display policy transitioning from " + oldPolicy + " to " + policy);
        }
        if (!isInteractivePolicy(policy) && isInteractivePolicy(oldPolicy)) {
            mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_SHORT_TERM_MODEL,
                    SHORT_TERM_MODEL_TIMEOUT_MILLIS);
        } else if (isInteractivePolicy(policy) && !isInteractivePolicy(oldPolicy)) {
            mHandler.removeMessages(MSG_INVALIDATE_SHORT_TERM_MODEL);
        }
        return true;
    }

    private static boolean isInteractivePolicy(int policy) {
        return policy == DisplayPowerRequest.POLICY_BRIGHT
                || policy == DisplayPowerRequest.POLICY_DIM
                || policy == DisplayPowerRequest.POLICY_VR;
    }

    private boolean setScreenBrightnessByUser(float brightness) {
        if (!mAmbientLuxValid) {
            // If we don't have a valid ambient lux then we don't have a valid brightness anyways,
            // and we can't use this data to add a new control point to the short-term model.
            return false;
        }
        mBrightnessMapper.addUserDataPoint(mAmbientLux, brightness);
        mShortTermModelValid = true;
        mShortTermModelAnchor = mAmbientLux;
        if (DEBUG) {
            Slog.d(TAG, "ShortTermModel: anchor=" + mShortTermModelAnchor);
        }
        return true;
    }

    private void resetShortTermModel() {
        mBrightnessMapper.clearUserDataPoints();
        mShortTermModelValid = true;
        mShortTermModelAnchor = -1;
    }

    private void invalidateShortTermModel() {
        if (DEBUG) {
            Slog.d(TAG, "ShortTermModel: invalidate user data");
        }
        mShortTermModelValid = false;
    }

    public boolean setBrightnessConfiguration(BrightnessConfiguration configuration) {
        return mBrightnessMapper.setBrightnessConfiguration(configuration);
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Automatic Brightness Controller Configuration:");
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);
        pw.println("  mBrighteningLightDebounceConfig=" + mBrighteningLightDebounceConfig);
        pw.println("  mDarkeningLightDebounceConfig=" + mDarkeningLightDebounceConfig);
        pw.println("  mResetAmbientLuxAfterWarmUpConfig=" + mResetAmbientLuxAfterWarmUpConfig);

        pw.println();
        pw.println("Automatic Brightness Controller State:");
        pw.println("  mLightSensor=" + mLightSensor);
        pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("  mLightSensorEnableTime=" + TimeUtils.formatUptime(mLightSensorEnableTime));
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAmbientLightHorizon=" + mAmbientLightHorizon);
        pw.println("  mBrighteningLuxThreshold=" + mBrighteningLuxThreshold);
        pw.println("  mDarkeningLuxThreshold=" + mDarkeningLuxThreshold);
        pw.println("  mLastObservedLux=" + mLastObservedLux);
        pw.println("  mLastObservedLuxTime=" + TimeUtils.formatUptime(mLastObservedLuxTime));
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mDisplayPolicy=" + mDisplayPolicy);
        pw.println("  mShortTermModelAnchor=" + mShortTermModelAnchor);

        pw.println();
        mBrightnessMapper.dump(pw);
    }

    private boolean setLightSensorEnabled(boolean enable) {
        if (enable) {
            if (!mLightSensorEnabled) {
                mLightSensorEnabled = true;
                mLightSensorEnableTime = SystemClock.uptimeMillis();
                mCurrentLightSensorRate = mInitialLightSensorRate;
                mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                        mCurrentLightSensorRate * 1000, mHandler);
                return true;
            }
        } else if (mLightSensorEnabled) {
            mLightSensorEnabled = false;
            mAmbientLuxValid = !mResetAmbientLuxAfterWarmUpConfig;
            mRecentLightSamples = 0;
            mAmbientLightRingBuffer.clear();
            mCurrentLightSensorRate = -1;
            mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);
            mSensorManager.unregisterListener(mLightSensorListener);
        }
        return false;
    }

    private void handleLightSensorEvent(long time, float lux) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ALS", (int) lux);
        mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);

        if (mAmbientLightRingBuffer.size() == 0) {
            // switch to using the steady-state sample rate after grabbing the initial light sample
            adjustLightSensorRate(mNormalLightSensorRate);
        }
        applyLightSensorMeasurement(time, lux);
        updateAmbientLux(time);
    }

    private void applyLightSensorMeasurement(long time, float lux) {
        mRecentLightSamples++;
        mAmbientLightRingBuffer.prune(time - mAmbientLightHorizon);
        mAmbientLightRingBuffer.push(time, lux);

        // Remember this sample value.
        mLastObservedLux = lux;
        mLastObservedLuxTime = time;
    }

    private void adjustLightSensorRate(int lightSensorRate) {
        // if the light sensor rate changed, update the sensor listener
        if (lightSensorRate != mCurrentLightSensorRate) {
            if (DEBUG) {
                Slog.d(TAG, "adjustLightSensorRate: " +
                        "previousRate=" + mCurrentLightSensorRate + ", " +
                        "currentRate=" + lightSensorRate);
            }
            mCurrentLightSensorRate = lightSensorRate;
            mSensorManager.unregisterListener(mLightSensorListener);
            mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                    lightSensorRate * 1000, mHandler);
        }
    }

    private boolean setAutoBrightnessAdjustment(float adjustment) {
        return mBrightnessMapper.setAutoBrightnessAdjustment(adjustment);
    }

    private void setAmbientLux(float lux) {
        if (DEBUG) {
            Slog.d(TAG, "setAmbientLux(" + lux + ")");
        }
        if (lux < 0) {
            Slog.w(TAG, "Ambient lux was negative, ignoring and setting to 0");
            lux = 0;
        }
        mAmbientLux = lux;
        mBrighteningLuxThreshold = mDynamicHysteresis.getBrighteningThreshold(lux);
        mDarkeningLuxThreshold = mDynamicHysteresis.getDarkeningThreshold(lux);

        // If the short term model was invalidated and the change is drastic enough, reset it.
        if (!mShortTermModelValid && mShortTermModelAnchor != -1) {
            final float minAmbientLux =
                mShortTermModelAnchor - mShortTermModelAnchor * SHORT_TERM_MODEL_THRESHOLD_RATIO;
            final float maxAmbientLux =
                mShortTermModelAnchor + mShortTermModelAnchor * SHORT_TERM_MODEL_THRESHOLD_RATIO;
            if (minAmbientLux < mAmbientLux && mAmbientLux < maxAmbientLux) {
                if (DEBUG) {
                    Slog.d(TAG, "ShortTermModel: re-validate user data, ambient lux is " +
                            minAmbientLux + " < " + mAmbientLux + " < " + maxAmbientLux);
                }
                mShortTermModelValid = true;
            } else {
                Slog.d(TAG, "ShortTermModel: reset data, ambient lux is " + mAmbientLux +
                        "(" + minAmbientLux + ", " + maxAmbientLux + ")");
                resetShortTermModel();
            }
        }
    }

    private float calculateAmbientLux(long now, long horizon) {
        if (DEBUG) {
            Slog.d(TAG, "calculateAmbientLux(" + now + ", " + horizon + ")");
        }
        final int N = mAmbientLightRingBuffer.size();
        if (N == 0) {
            Slog.e(TAG, "calculateAmbientLux: No ambient light readings available");
            return -1;
        }

        // Find the first measurement that is just outside of the horizon.
        int endIndex = 0;
        final long horizonStartTime = now - horizon;
        for (int i = 0; i < N-1; i++) {
            if (mAmbientLightRingBuffer.getTime(i + 1) <= horizonStartTime) {
                endIndex++;
            } else {
                break;
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "calculateAmbientLux: selected endIndex=" + endIndex + ", point=(" +
                    mAmbientLightRingBuffer.getTime(endIndex) + ", " +
                    mAmbientLightRingBuffer.getLux(endIndex) + ")");
        }
        float sum = 0;
        float totalWeight = 0;
        long endTime = AMBIENT_LIGHT_PREDICTION_TIME_MILLIS;
        for (int i = N - 1; i >= endIndex; i--) {
            long eventTime = mAmbientLightRingBuffer.getTime(i);
            if (i == endIndex && eventTime < horizonStartTime) {
                // If we're at the final value, make sure we only consider the part of the sample
                // within our desired horizon.
                eventTime = horizonStartTime;
            }
            final long startTime = eventTime - now;
            float weight = calculateWeight(startTime, endTime);
            float lux = mAmbientLightRingBuffer.getLux(i);
            if (DEBUG) {
                Slog.d(TAG, "calculateAmbientLux: [" + startTime + ", " + endTime + "]: " +
                        "lux=" + lux + ", " +
                        "weight=" + weight);
            }
            totalWeight += weight;
            sum += lux * weight;
            endTime = startTime;
        }
        if (DEBUG) {
            Slog.d(TAG, "calculateAmbientLux: " +
                    "totalWeight=" + totalWeight + ", " +
                    "newAmbientLux=" + (sum / totalWeight));
        }
        return sum / totalWeight;
    }

    private float calculateWeight(long startDelta, long endDelta) {
        return weightIntegral(endDelta) - weightIntegral(startDelta);
    }

    // Evaluates the integral of y = x + mWeightingIntercept. This is always positive for the
    // horizon we're looking at and provides a non-linear weighting for light samples.
    private float weightIntegral(long x) {
        return x * (x * 0.5f + mWeightingIntercept);
    }

    private long nextAmbientLightBrighteningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = N - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) <= mBrighteningLuxThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + mBrighteningLightDebounceConfig;
    }

    private long nextAmbientLightDarkeningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = N - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) >= mDarkeningLuxThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + mDarkeningLightDebounceConfig;
    }

    private void updateAmbientLux() {
        long time = SystemClock.uptimeMillis();
        mAmbientLightRingBuffer.prune(time - mAmbientLightHorizon);
        updateAmbientLux(time);
    }

    private void updateAmbientLux(long time) {
        // If the light sensor was just turned on then immediately update our initial
        // estimate of the current ambient light level.
        if (!mAmbientLuxValid) {
            final long timeWhenSensorWarmedUp =
                mLightSensorWarmUpTimeConfig + mLightSensorEnableTime;
            if (time < timeWhenSensorWarmedUp) {
                if (DEBUG) {
                    Slog.d(TAG, "updateAmbientLux: Sensor not  ready yet: " +
                            "time=" + time + ", " +
                            "timeWhenSensorWarmedUp=" + timeWhenSensorWarmedUp);
                }
                mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX,
                        timeWhenSensorWarmedUp);
                return;
            }
            setAmbientLux(calculateAmbientLux(time, AMBIENT_LIGHT_SHORT_HORIZON_MILLIS));
            mAmbientLuxValid = true;
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Initializing: " +
                        "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", " +
                        "mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
        }

        long nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
        long nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        // Essentially, we calculate both a slow ambient lux, to ensure there's a true long-term
        // change in lighting conditions, and a fast ambient lux to determine what the new
        // brightness situation is since the slow lux can be quite slow to converge.
        //
        // Note that both values need to be checked for sufficient change before updating the
        // proposed ambient light value since the slow value might be sufficiently far enough away
        // from the fast value to cause a recalculation while its actually just converging on
        // the fast value still.
        float slowAmbientLux = calculateAmbientLux(time, AMBIENT_LIGHT_LONG_HORIZON_MILLIS);
        float fastAmbientLux = calculateAmbientLux(time, AMBIENT_LIGHT_SHORT_HORIZON_MILLIS);

        if ((slowAmbientLux >= mBrighteningLuxThreshold &&
             fastAmbientLux >= mBrighteningLuxThreshold &&
             nextBrightenTransition <= time)
             ||
            (slowAmbientLux <= mDarkeningLuxThreshold &&
             fastAmbientLux <= mDarkeningLuxThreshold &&
             nextDarkenTransition <= time)) {
            setAmbientLux(fastAmbientLux);
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: " +
                        ((fastAmbientLux > mAmbientLux) ? "Brightened" : "Darkened") + ": " +
                        "mBrighteningLuxThreshold=" + mBrighteningLuxThreshold + ", " +
                        "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", " +
                        "mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
            nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
            nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        }
        long nextTransitionTime = Math.min(nextDarkenTransition, nextBrightenTransition);
        // If one of the transitions is ready to occur, but the total weighted ambient lux doesn't
        // exceed the necessary threshold, then it's possible we'll get a transition time prior to
        // now. Rather than continually checking to see whether the weighted lux exceeds the
        // threshold, schedule an update for when we'd normally expect another light sample, which
        // should be enough time to decide whether we should actually transition to the new
        // weighted ambient lux or not.
        nextTransitionTime =
                nextTransitionTime > time ? nextTransitionTime : time + mNormalLightSensorRate;
        if (DEBUG) {
            Slog.d(TAG, "updateAmbientLux: Scheduling ambient lux update for " +
                    nextTransitionTime + TimeUtils.formatUptime(nextTransitionTime));
        }
        mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX, nextTransitionTime);
    }

    private void updateAutoBrightness(boolean sendUpdate) {
        if (!mAmbientLuxValid) {
            return;
        }

        float value = mBrightnessMapper.getBrightness(mAmbientLux);

        int newScreenAutoBrightness =
                clampScreenBrightness(Math.round(value * PowerManager.BRIGHTNESS_ON));
        if (mScreenAutoBrightness != newScreenAutoBrightness) {
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: " +
                        "mScreenAutoBrightness=" + mScreenAutoBrightness + ", " +
                        "newScreenAutoBrightness=" + newScreenAutoBrightness);
            }

            mScreenAutoBrightness = newScreenAutoBrightness;
            if (sendUpdate) {
                mCallbacks.updateBrightness();
            }
        }
    }

    private int clampScreenBrightness(int value) {
        return MathUtils.constrain(value,
                mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum);
    }

    private void prepareBrightnessAdjustmentSample() {
        if (!mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = true;
            mBrightnessAdjustmentSampleOldLux = mAmbientLuxValid ? mAmbientLux : -1;
            mBrightnessAdjustmentSampleOldBrightness = mScreenAutoBrightness;
        } else {
            mHandler.removeMessages(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE);
        }

        mHandler.sendEmptyMessageDelayed(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE,
                BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS);
    }

    private void cancelBrightnessAdjustmentSample() {
        if (mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = false;
            mHandler.removeMessages(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE);
        }
    }

    private void collectBrightnessAdjustmentSample() {
        if (mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = false;
            if (mAmbientLuxValid && mScreenAutoBrightness >= 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Auto-brightness adjustment changed by user: " +
                            "lux=" + mAmbientLux + ", " +
                            "brightness=" + mScreenAutoBrightness + ", " +
                            "ring=" + mAmbientLightRingBuffer);
                }

                EventLog.writeEvent(EventLogTags.AUTO_BRIGHTNESS_ADJ,
                        mBrightnessAdjustmentSampleOldLux,
                        mBrightnessAdjustmentSampleOldBrightness,
                        mAmbientLux,
                        mScreenAutoBrightness);
            }
        }
    }

    private final class AutomaticBrightnessHandler extends Handler {
        public AutomaticBrightnessHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_AMBIENT_LUX:
                    updateAmbientLux();
                    break;

                case MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE:
                    collectBrightnessAdjustmentSample();
                    break;

                case MSG_INVALIDATE_SHORT_TERM_MODEL:
                    invalidateShortTermModel();
                    break;
            }
        }
    }

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                final long time = SystemClock.uptimeMillis();
                final float lux = event.values[0];
                handleLightSensorEvent(time, lux);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    /** Callbacks to request updates to the display's power state. */
    interface Callbacks {
        void updateBrightness();
    }

    /**
     * A ring buffer of ambient light measurements sorted by time.
     *
     * Each entry consists of a timestamp and a lux measurement, and the overall buffer is sorted
     * from oldest to newest.
     */
    private static final class AmbientLightRingBuffer {
        // Proportional extra capacity of the buffer beyond the expected number of light samples
        // in the horizon
        private static final float BUFFER_SLACK = 1.5f;
        private float[] mRingLux;
        private long[] mRingTime;
        private int mCapacity;

        // The first valid element and the next open slot.
        // Note that if mCount is zero then there are no valid elements.
        private int mStart;
        private int mEnd;
        private int mCount;

        public AmbientLightRingBuffer(long lightSensorRate, int ambientLightHorizon) {
            mCapacity = (int) Math.ceil(ambientLightHorizon * BUFFER_SLACK / lightSensorRate);
            mRingLux = new float[mCapacity];
            mRingTime = new long[mCapacity];
        }

        public float getLux(int index) {
            return mRingLux[offsetOf(index)];
        }

        public long getTime(int index) {
            return mRingTime[offsetOf(index)];
        }

        public void push(long time, float lux) {
            int next = mEnd;
            if (mCount == mCapacity) {
                int newSize = mCapacity * 2;

                float[] newRingLux = new float[newSize];
                long[] newRingTime = new long[newSize];
                int length = mCapacity - mStart;
                System.arraycopy(mRingLux, mStart, newRingLux, 0, length);
                System.arraycopy(mRingTime, mStart, newRingTime, 0, length);
                if (mStart != 0) {
                    System.arraycopy(mRingLux, 0, newRingLux, length, mStart);
                    System.arraycopy(mRingTime, 0, newRingTime, length, mStart);
                }
                mRingLux = newRingLux;
                mRingTime = newRingTime;

                next = mCapacity;
                mCapacity = newSize;
                mStart = 0;
            }
            mRingTime[next] = time;
            mRingLux[next] = lux;
            mEnd = next + 1;
            if (mEnd == mCapacity) {
                mEnd = 0;
            }
            mCount++;
        }

        public void prune(long horizon) {
            if (mCount == 0) {
                return;
            }

            while (mCount > 1) {
                int next = mStart + 1;
                if (next >= mCapacity) {
                    next -= mCapacity;
                }
                if (mRingTime[next] > horizon) {
                    // Some light sensors only produce data upon a change in the ambient light
                    // levels, so we need to consider the previous measurement as the ambient light
                    // level for all points in time up until we receive a new measurement. Thus, we
                    // always want to keep the youngest element that would be removed from the
                    // buffer and just set its measurement time to the horizon time since at that
                    // point it is the ambient light level, and to remove it would be to drop a
                    // valid data point within our horizon.
                    break;
                }
                mStart = next;
                mCount -= 1;
            }

            if (mRingTime[mStart] < horizon) {
                mRingTime[mStart] = horizon;
            }
        }

        public int size() {
            return mCount;
        }

        public void clear() {
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        @Override
        public String toString() {
            StringBuffer buf = new StringBuffer();
            buf.append('[');
            for (int i = 0; i < mCount; i++) {
                final long next = i + 1 < mCount ? getTime(i + 1) : SystemClock.uptimeMillis();
                if (i != 0) {
                    buf.append(", ");
                }
                buf.append(getLux(i));
                buf.append(" / ");
                buf.append(next - getTime(i));
                buf.append("ms");
            }
            buf.append(']');
            return buf.toString();
        }

        private int offsetOf(int index) {
            if (index >= mCount || index < 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            index += mStart;
            if (index >= mCapacity) {
                index -= mCapacity;
            }
            return index;
        }
    }
}
