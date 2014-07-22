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

import com.android.server.LocalServices;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.MathUtils;
import android.util.Spline;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.Arrays;

class AutomaticBrightnessController {
    private static final String TAG = "AutomaticBrightnessController";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    // If true, enables the use of the screen auto-brightness adjustment setting.
    private static final boolean USE_SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT = true;

    // The maximum range of gamma adjustment possible using the screen
    // auto-brightness adjustment setting.
    private static final float SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT_MAX_GAMMA = 3.0f;

    // Light sensor event rate in milliseconds.
    private static final int LIGHT_SENSOR_RATE_MILLIS = 1000;

    // Period of time in which to consider light samples in milliseconds.
    private static final int AMBIENT_LIGHT_HORIZON = 10000;

    // Stability requirements in milliseconds for accepting a new brightness level.  This is used
    // for debouncing the light sensor.  Different constants are used to debounce the light sensor
    // when adapting to brighter or darker environments.  This parameter controls how quickly
    // brightness changes occur in response to an observed change in light level that exceeds the
    // hysteresis threshold.
    private static final long BRIGHTENING_LIGHT_DEBOUNCE = 4000;
    private static final long DARKENING_LIGHT_DEBOUNCE = 8000;

    // Hysteresis constraints for brightening or darkening.
    // The recent lux must have changed by at least this fraction relative to the
    // current ambient lux before a change will be considered.
    private static final float BRIGHTENING_LIGHT_HYSTERESIS = 0.10f;
    private static final float DARKENING_LIGHT_HYSTERESIS = 0.20f;

    // The intercept used for the weighting calculation. This is used in order to keep all possible
    // weighting values positive.
    private static final int WEIGHTING_INTERCEPT = AMBIENT_LIGHT_HORIZON;

    // How long the current sensor reading is assumed to be valid beyond the current time.
    // This provides a bit of prediction, as well as ensures that the weight for the last sample is
    // non-zero, which in turn ensures that the total weight is non-zero.
    private static final long AMBIENT_LIGHT_PREDICTION_TIME_MILLIS = 100;

    // If true, enables the use of the current time as an auto-brightness adjustment.
    // The basic idea here is to expand the dynamic range of auto-brightness
    // when it is especially dark outside.  The light sensor tends to perform
    // poorly at low light levels so we compensate for it by making an
    // assumption about the environment.
    private static final boolean USE_TWILIGHT_ADJUSTMENT =
            PowerManager.useTwilightAdjustmentFeature();

    // Specifies the maximum magnitude of the time of day adjustment.
    private static final float TWILIGHT_ADJUSTMENT_MAX_GAMMA = 1.5f;

    // The amount of time after or before sunrise over which to start adjusting
    // the gamma.  We want the change to happen gradually so that it is below the
    // threshold of perceptibility and so that the adjustment has maximum effect
    // well after dusk.
    private static final long TWILIGHT_ADJUSTMENT_TIME = DateUtils.HOUR_IN_MILLIS * 2;

    private static final int MSG_UPDATE_AMBIENT_LUX = 1;

    // Callbacks for requesting updates to the the display's power state
    private final Callbacks mCallbacks;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The light sensor, or null if not available or needed.
    private final Sensor mLightSensor;

    // The twilight service.
    private final TwilightManager mTwilight;

    // The auto-brightness spline adjustment.
    // The brightness values have been scaled to a range of 0..1.
    private final Spline mScreenAutoBrightnessSpline;

    // The minimum and maximum screen brightnesses.
    private final int mScreenBrightnessRangeMinimum;
    private final int mScreenBrightnessRangeMaximum;

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

    // The screen auto-brightness adjustment factor in the range -1 (dimmer) to 1 (brighter)
    private float mScreenAutoBrightnessAdjustment = 0.0f;

    // The last screen auto-brightness gamma.  (For printing in dump() only.)
    private float mLastScreenAutoBrightnessGamma = 1.0f;

    public AutomaticBrightnessController(Callbacks callbacks, Looper looper,
            SensorManager sensorManager, Spline autoBrightnessSpline,
            int lightSensorWarmUpTime, int brightnessMin, int brightnessMax) {
        mCallbacks = callbacks;
        mTwilight = LocalServices.getService(TwilightManager.class);
        mSensorManager = sensorManager;
        mScreenAutoBrightnessSpline = autoBrightnessSpline;
        mScreenBrightnessRangeMinimum = brightnessMin;
        mScreenBrightnessRangeMaximum = brightnessMax;
        mLightSensorWarmUpTimeConfig = lightSensorWarmUpTime;

        mHandler = new AutomaticBrightnessHandler(looper);
        mAmbientLightRingBuffer = new AmbientLightRingBuffer();

        if (!DEBUG_PRETEND_LIGHT_SENSOR_ABSENT) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        if (USE_TWILIGHT_ADJUSTMENT) {
            mTwilight.registerListener(mTwilightListener, mHandler);
        }
    }

    public int getAutomaticScreenBrightness() {
        return mScreenAutoBrightness;
    }

    public void configure(boolean enable, float adjustment) {
        boolean changed = setLightSensorEnabled(enable);
        changed |= setScreenAutoBrightnessAdjustment(adjustment);
        if (changed) {
            updateAutoBrightness(false /*sendUpdate*/);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Automatic Brightness Controller Configuration:");
        pw.println("  mScreenAutoBrightnessSpline=" + mScreenAutoBrightnessSpline);
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);

        pw.println();
        pw.println("Automatic Brightness Controller State:");
        pw.println("  mLightSensor=" + mLightSensor);
        pw.println("  mTwilight.getCurrentState()=" + mTwilight.getCurrentState());
        pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("  mLightSensorEnableTime=" + TimeUtils.formatUptime(mLightSensorEnableTime));
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mBrighteningLuxThreshold=" + mBrighteningLuxThreshold);
        pw.println("  mDarkeningLuxThreshold=" + mDarkeningLuxThreshold);
        pw.println("  mLastObservedLux=" + mLastObservedLux);
        pw.println("  mLastObservedLuxTime=" + TimeUtils.formatUptime(mLastObservedLuxTime));
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mScreenAutoBrightnessAdjustment=" + mScreenAutoBrightnessAdjustment);
        pw.println("  mLastScreenAutoBrightnessGamma=" + mLastScreenAutoBrightnessGamma);
    }

    private boolean setLightSensorEnabled(boolean enable) {
        if (enable) {
            if (!mLightSensorEnabled) {
                mLightSensorEnabled = true;
                mLightSensorEnableTime = SystemClock.uptimeMillis();
                mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                        LIGHT_SENSOR_RATE_MILLIS * 1000, mHandler);
                return true;
            }
        } else {
            if (mLightSensorEnabled) {
                mLightSensorEnabled = false;
                mAmbientLuxValid = false;
                mRecentLightSamples = 0;
                mAmbientLightRingBuffer.clear();
                mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);
                mSensorManager.unregisterListener(mLightSensorListener);
            }
        }
        return false;
    }

    private void handleLightSensorEvent(long time, float lux) {
        mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);

        applyLightSensorMeasurement(time, lux);
        updateAmbientLux(time);
    }

    private void applyLightSensorMeasurement(long time, float lux) {
        mRecentLightSamples++;
        mAmbientLightRingBuffer.prune(time - AMBIENT_LIGHT_HORIZON);
        mAmbientLightRingBuffer.push(time, lux);

        // Remember this sample value.
        mLastObservedLux = lux;
        mLastObservedLuxTime = time;
    }

    private boolean setScreenAutoBrightnessAdjustment(float adjustment) {
        if (adjustment != mScreenAutoBrightnessAdjustment) {
            mScreenAutoBrightnessAdjustment = adjustment;
            return true;
        }
        return false;
    }

    private void setAmbientLux(float lux) {
        mAmbientLux = lux;
        mBrighteningLuxThreshold = mAmbientLux * (1.0f + BRIGHTENING_LIGHT_HYSTERESIS);
        mDarkeningLuxThreshold = mAmbientLux * (1.0f - DARKENING_LIGHT_HYSTERESIS);
    }

    private float calculateAmbientLux(long now) {
        final int N = mAmbientLightRingBuffer.size();
        if (N == 0) {
            Slog.e(TAG, "calculateAmbientLux: No ambient light readings available");
            return -1;
        }
        float sum = 0;
        float totalWeight = 0;
        long endTime = AMBIENT_LIGHT_PREDICTION_TIME_MILLIS;
        for (int i = N - 1; i >= 0; i--) {
            long startTime = (mAmbientLightRingBuffer.getTime(i) - now);
            float weight = calculateWeight(startTime, endTime);
            float lux = mAmbientLightRingBuffer.getLux(i);
            if (DEBUG) {
                Slog.d(TAG, "calculateAmbientLux: [" +
                        (startTime) + ", " +
                        (endTime) + "]: lux=" + lux + ", weight=" + weight);
            }
            totalWeight += weight;
            sum += mAmbientLightRingBuffer.getLux(i) * weight;
            endTime = startTime;
        }
        if (DEBUG) {
            Slog.d(TAG, "calculateAmbientLux: totalWeight=" + totalWeight +
                    ", newAmbientLux=" + (sum / totalWeight));
        }
        return sum / totalWeight;
    }

    private static float calculateWeight(long startDelta, long endDelta) {
        return weightIntegral(endDelta) - weightIntegral(startDelta);
    }

    // Evaluates the integral of y = x + WEIGHTING_INTERCEPT. This is always positive for the
    // horizon we're looking at and provides a non-linear weighting for light samples.
    private static float weightIntegral(long x) {
        return x * (x * 0.5f + WEIGHTING_INTERCEPT);
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
        return earliestValidTime + BRIGHTENING_LIGHT_DEBOUNCE;
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
        return earliestValidTime + DARKENING_LIGHT_DEBOUNCE;
    }

    private void updateAmbientLux() {
        long time = SystemClock.uptimeMillis();
        mAmbientLightRingBuffer.prune(time - AMBIENT_LIGHT_HORIZON);
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
                    Slog.d(TAG, "updateAmbientLux: Sensor not  ready yet: "
                            + "time=" + time
                            + ", timeWhenSensorWarmedUp=" + timeWhenSensorWarmedUp);
                }
                mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX,
                        timeWhenSensorWarmedUp);
                return;
            }
            setAmbientLux(calculateAmbientLux(time));
            mAmbientLuxValid = true;
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Initializing: "
                        + "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer
                        + ", mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
        }

        long nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
        long nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        float ambientLux = calculateAmbientLux(time);

        if (ambientLux >= mBrighteningLuxThreshold && nextBrightenTransition <= time
                || ambientLux <= mDarkeningLuxThreshold && nextDarkenTransition <= time) {
            setAmbientLux(ambientLux);
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: "
                        + ((ambientLux > mAmbientLux) ? "Brightened" : "Darkened") + ": "
                        + "mBrighteningLuxThreshold=" + mBrighteningLuxThreshold
                        + ", mAmbientLightRingBuffer=" + mAmbientLightRingBuffer
                        + ", mAmbientLux=" + mAmbientLux);
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
                nextTransitionTime > time ? nextTransitionTime : time + LIGHT_SENSOR_RATE_MILLIS;
        if (DEBUG) {
            Slog.d(TAG, "updateAmbientLux: Scheduling ambient lux update for "
                    + nextTransitionTime + TimeUtils.formatUptime(nextTransitionTime));
        }
        mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX, nextTransitionTime);
    }

    private void updateAutoBrightness(boolean sendUpdate) {
        if (!mAmbientLuxValid) {
            return;
        }

        float value = mScreenAutoBrightnessSpline.interpolate(mAmbientLux);
        float gamma = 1.0f;

        if (USE_SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT
                && mScreenAutoBrightnessAdjustment != 0.0f) {
            final float adjGamma = MathUtils.pow(SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT_MAX_GAMMA,
                    Math.min(1.0f, Math.max(-1.0f, -mScreenAutoBrightnessAdjustment)));
            gamma *= adjGamma;
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: adjGamma=" + adjGamma);
            }
        }

        if (USE_TWILIGHT_ADJUSTMENT) {
            TwilightState state = mTwilight.getCurrentState();
            if (state != null && state.isNight()) {
                final long now = System.currentTimeMillis();
                final float earlyGamma =
                        getTwilightGamma(now, state.getYesterdaySunset(), state.getTodaySunrise());
                final float lateGamma =
                        getTwilightGamma(now, state.getTodaySunset(), state.getTomorrowSunrise());
                gamma *= earlyGamma * lateGamma;
                if (DEBUG) {
                    Slog.d(TAG, "updateAutoBrightness: earlyGamma=" + earlyGamma
                            + ", lateGamma=" + lateGamma);
                }
            }
        }

        if (gamma != 1.0f) {
            final float in = value;
            value = MathUtils.pow(value, gamma);
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: gamma=" + gamma
                        + ", in=" + in + ", out=" + value);
            }
        }

        int newScreenAutoBrightness =
            clampScreenBrightness(Math.round(value * PowerManager.BRIGHTNESS_ON));
        if (mScreenAutoBrightness != newScreenAutoBrightness) {
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: mScreenAutoBrightness="
                        + mScreenAutoBrightness + ", newScreenAutoBrightness="
                        + newScreenAutoBrightness);
            }

            mScreenAutoBrightness = newScreenAutoBrightness;
            mLastScreenAutoBrightnessGamma = gamma;
            if (sendUpdate) {
                mCallbacks.updateBrightness();
            }
        }
    }

    private int clampScreenBrightness(int value) {
        return MathUtils.constrain(value,
                mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum);
    }

    private static float getTwilightGamma(long now, long lastSunset, long nextSunrise) {
        if (lastSunset < 0 || nextSunrise < 0
                || now < lastSunset || now > nextSunrise) {
            return 1.0f;
        }

        if (now < lastSunset + TWILIGHT_ADJUSTMENT_TIME) {
            return MathUtils.lerp(1.0f, TWILIGHT_ADJUSTMENT_MAX_GAMMA,
                    (float)(now - lastSunset) / TWILIGHT_ADJUSTMENT_TIME);
        }

        if (now > nextSunrise - TWILIGHT_ADJUSTMENT_TIME) {
            return MathUtils.lerp(1.0f, TWILIGHT_ADJUSTMENT_MAX_GAMMA,
                    (float)(nextSunrise - now) / TWILIGHT_ADJUSTMENT_TIME);
        }

        return TWILIGHT_ADJUSTMENT_MAX_GAMMA;
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

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged() {
            updateAutoBrightness(true /*sendUpdate*/);
        }
    };

    /** Callbacks to request updates to the display's power state. */
    interface Callbacks {
        void updateBrightness();
    }

    private static final class AmbientLightRingBuffer{
        // Proportional extra capacity of the buffer beyond the expected number of light samples
        // in the horizon
        private static final float BUFFER_SLACK = 1.5f;
        private static final int DEFAULT_CAPACITY =
            (int) Math.ceil(AMBIENT_LIGHT_HORIZON * BUFFER_SLACK / LIGHT_SENSOR_RATE_MILLIS);
        private float[] mRingLux;
        private long[] mRingTime;
        private int mCapacity;

        // The first valid element and the next open slot.
        // Note that if mCount is zero then there are no valid elements.
        private int mStart;
        private int mEnd;
        private int mCount;

        public AmbientLightRingBuffer() {
            this(DEFAULT_CAPACITY);
        }

        public AmbientLightRingBuffer(int initialCapacity) {
            mCapacity = initialCapacity;
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

        public boolean isEmpty() {
            return mCount == 0;
        }

        public void clear() {
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        @Override
        public String toString() {
            final int length = mCapacity - mStart;
            float[] lux = new float[mCount];
            long[] time = new long[mCount];

            if (mCount <= length) {
                System.arraycopy(mRingLux, mStart, lux, 0, mCount);
                System.arraycopy(mRingTime, mStart, time, 0, mCount);
            } else {
                System.arraycopy(mRingLux, mStart, lux, 0, length);
                System.arraycopy(mRingLux, 0, lux, length, mCount - length);

                System.arraycopy(mRingTime, mStart, time, 0, length);
                System.arraycopy(mRingTime, 0, time, length, mCount - length);
            }
            return "AmbientLightRingBuffer{mCapacity=" + mCapacity
                + ", mStart=" + mStart
                + ", mEnd=" + mEnd
                + ", mCount=" + mCount
                + ", mRingLux=" + Arrays.toString(lux)
                + ", mRingTime=" + Arrays.toString(time)
                + "}";
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
