/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.brightness;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_LUX;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Trace;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.HysteresisLevels;
import com.android.server.display.config.SensorData;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;

/**
 * Manages light sensor subscription and notifies its listeners about ambient lux changes based on
 * configuration
 */
public class LightSensorController {
    // How long the current sensor reading is assumed to be valid beyond the current time.
    // This provides a bit of prediction, as well as ensures that the weight for the last sample is
    // non-zero, which in turn ensures that the total weight is non-zero.
    private static final long AMBIENT_LIGHT_PREDICTION_TIME_MILLIS = 100;

    // Proportional extra capacity of the buffer beyond the expected number of light samples
    // in the horizon
    private static final float BUFFER_SLACK = 1.5f;

    private boolean mLoggingEnabled;
    private boolean mLightSensorEnabled;
    private long mLightSensorEnableTime;
    // The current light sensor event rate in milliseconds.
    private int mCurrentLightSensorRate = -1;
    // The number of light samples collected since the light sensor was enabled.
    private int mRecentLightSamples;
    private float mAmbientLux;
    // True if mAmbientLux holds a valid value.
    private boolean mAmbientLuxValid;
    // The last ambient lux value prior to passing the darkening or brightening threshold.
    private float mPreThresholdLux;
    // The most recent light sample.
    private float mLastObservedLux = INVALID_LUX;
    // The time of the most light recent sample.
    private long mLastObservedLuxTime;
    // The last calculated ambient light level (long time window).
    private float mSlowAmbientLux;
    // The last calculated ambient light level (short time window).
    private float mFastAmbientLux;
    private volatile boolean mIsIdleMode;
    // The ambient light level threshold at which to brighten or darken the screen.
    private float mAmbientBrighteningThreshold;
    private float mAmbientDarkeningThreshold;

    private final LightSensorControllerConfig mConfig;

    // The light sensor, or null if not available or needed.
    @Nullable
    private final Sensor mLightSensor;

    // A ring buffer containing all of the recent ambient light sensor readings.
    private final AmbientLightRingBuffer mAmbientLightRingBuffer;

    private final Injector mInjector;

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                final long time = mClock.uptimeMillis();
                final float lux = event.values[0];
                handleLightSensorEvent(time, lux);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    // Runnable used to delay ambient lux update when:
    // 1) update triggered before configured warm up time
    // 2) next brightening or darkening transition need to happen
    private final Runnable mAmbientLuxUpdater = this::updateAmbientLux;

    private final Clock mClock;

    private final Handler mHandler;

    private final String mTag;

    private LightSensorListener mListener;

    public LightSensorController(
            SensorManager sensorManager,
            Looper looper,
            int displayId,
            LightSensorControllerConfig config) {
        this(config, new RealInjector(sensorManager, displayId), new LightSensorHandler(looper));
    }

    @VisibleForTesting
    LightSensorController(
            LightSensorControllerConfig config,
            Injector injector,
            Handler handler) {
        if (config.mNormalLightSensorRate <= 0) {
            throw new IllegalArgumentException("lightSensorRate must be above 0");
        }
        mInjector = injector;
        int bufferInitialCapacity = (int) Math.ceil(
                config.mAmbientLightHorizonLong * BUFFER_SLACK / config.mNormalLightSensorRate);
        mClock = injector.getClock();
        mHandler = handler;
        mAmbientLightRingBuffer = new AmbientLightRingBuffer(bufferInitialCapacity, mClock);
        mConfig = config;
        mLightSensor = mInjector.getLightSensor(mConfig);
        mTag = mInjector.getTag();
    }

    public void setListener(LightSensorListener listener) {
        mListener = listener;
    }

    /**
     * @return true if sensor registered, false if sensor already registered
     */
    public boolean enableLightSensorIfNeeded() {
        if (!mLightSensorEnabled) {
            mLightSensorEnabled = true;
            mLightSensorEnableTime = mClock.uptimeMillis();
            mCurrentLightSensorRate = mConfig.mInitialLightSensorRate;
            mInjector.registerLightSensorListener(
                    mLightSensorListener, mLightSensor, mCurrentLightSensorRate, mHandler);
            return true;
        }
        return false;
    }

    /**
     * @return true if sensor unregistered, false if sensor already unregistered
     */
    public boolean disableLightSensorIfNeeded() {
        if (mLightSensorEnabled) {
            mLightSensorEnabled = false;
            mAmbientLuxValid = !mConfig.mResetAmbientLuxAfterWarmUpConfig;
            if (!mAmbientLuxValid) {
                mPreThresholdLux = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            }
            mRecentLightSamples = 0;
            mAmbientLightRingBuffer.clear();
            mCurrentLightSensorRate = -1;
            mInjector.unregisterLightSensorListener(mLightSensorListener);
            return true;
        }
        return false;
    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        mLoggingEnabled = loggingEnabled;
    }

    /**
     * Updates BrightnessEvent with LightSensorController details
     */
    public void updateBrightnessEvent(BrightnessEvent brightnessEvent) {
        brightnessEvent.setPreThresholdLux(mPreThresholdLux);
    }

    /**
     * Print the object's debug information into the given stream.
     */
    public void dump(PrintWriter pw) {
        pw.println("LightSensorController state:");
        pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("  mLightSensorEnableTime=" + TimeUtils.formatUptime(mLightSensorEnableTime));
        pw.println("  mCurrentLightSensorRate=" + mCurrentLightSensorRate);
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAmbientLuxValid=" + mAmbientLuxValid);
        pw.println("  mPreThresholdLux=" + mPreThresholdLux);
        pw.println("  mLastObservedLux=" + mLastObservedLux);
        pw.println("  mLastObservedLuxTime=" + TimeUtils.formatUptime(mLastObservedLuxTime));
        pw.println("  mSlowAmbientLux=" + mSlowAmbientLux);
        pw.println("  mFastAmbientLux=" + mFastAmbientLux);
        pw.println("  mIsIdleMode=" + mIsIdleMode);
        pw.println("  mAmbientBrighteningThreshold=" + mAmbientBrighteningThreshold);
        pw.println("  mAmbientDarkeningThreshold=" + mAmbientDarkeningThreshold);
        pw.println("  mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        pw.println("  mLightSensor=" + mLightSensor);
        mConfig.dump(pw);
    }

    /**
     * This method should be called when this LightSensorController is no longer in use
     * i.e. when corresponding display removed
     */
    public void stop() {
        mHandler.removeCallbacksAndMessages(null);
        disableLightSensorIfNeeded();
    }

    public void setIdleMode(boolean isIdleMode) {
        mIsIdleMode = isIdleMode;
    }

    /**
     * returns true if LightSensorController holds valid ambient lux value
     */
    public boolean hasValidAmbientLux() {
        return mAmbientLuxValid;
    }

    /**
     * returns all last observed sensor values
     */
    public float[] getLastSensorValues() {
        return mAmbientLightRingBuffer.getAllLuxValues();
    }

    /**
     * returns all last observed sensor event timestamps
     */
    public long[] getLastSensorTimestamps() {
        return mAmbientLightRingBuffer.getAllTimestamps();
    }

    public float getLastObservedLux() {
        return mLastObservedLux;
    }

    private void handleLightSensorEvent(long time, float lux) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ALS", (int) lux);
        mHandler.removeCallbacks(mAmbientLuxUpdater);

        if (mAmbientLightRingBuffer.size() == 0) {
            // switch to using the steady-state sample rate after grabbing the initial light sample
            adjustLightSensorRate(mConfig.mNormalLightSensorRate);
        }
        applyLightSensorMeasurement(time, lux);
        updateAmbientLux(time);
    }

    private void applyLightSensorMeasurement(long time, float lux) {
        mRecentLightSamples++;
        mAmbientLightRingBuffer.prune(time - mConfig.mAmbientLightHorizonLong);
        mAmbientLightRingBuffer.push(time, lux);
        // Remember this sample value.
        mLastObservedLux = lux;
        mLastObservedLuxTime = time;
    }

    private void adjustLightSensorRate(int lightSensorRate) {
        // if the light sensor rate changed, update the sensor listener
        if (lightSensorRate != mCurrentLightSensorRate) {
            if (mLoggingEnabled) {
                Slog.d(mTag, "adjustLightSensorRate: "
                        + "previousRate=" + mCurrentLightSensorRate + ", "
                        + "currentRate=" + lightSensorRate);
            }
            mCurrentLightSensorRate = lightSensorRate;
            mInjector.unregisterLightSensorListener(mLightSensorListener);
            mInjector.registerLightSensorListener(
                    mLightSensorListener, mLightSensor, lightSensorRate, mHandler);
        }
    }

    private void setAmbientLux(float lux) {
        if (mLoggingEnabled) {
            Slog.d(mTag, "setAmbientLux(" + lux + ")");
        }
        if (lux < 0) {
            Slog.w(mTag, "Ambient lux was negative, ignoring and setting to 0");
            lux = 0;
        }
        mAmbientLux = lux;

        if (mIsIdleMode) {
            mAmbientBrighteningThreshold =
                    mConfig.mAmbientBrightnessThresholdsIdle.getBrighteningThreshold(lux);
            mAmbientDarkeningThreshold =
                    mConfig.mAmbientBrightnessThresholdsIdle.getDarkeningThreshold(lux);
        } else {
            mAmbientBrighteningThreshold =
                    mConfig.mAmbientBrightnessThresholds.getBrighteningThreshold(lux);
            mAmbientDarkeningThreshold =
                    mConfig.mAmbientBrightnessThresholds.getDarkeningThreshold(lux);
        }

        mListener.onAmbientLuxChange(mAmbientLux);
    }

    private float calculateAmbientLux(long now, long horizon) {
        if (mLoggingEnabled) {
            Slog.d(mTag, "calculateAmbientLux(" + now + ", " + horizon + ")");
        }
        final int size = mAmbientLightRingBuffer.size();
        if (size == 0) {
            Slog.e(mTag, "calculateAmbientLux: No ambient light readings available");
            return -1;
        }

        // Find the first measurement that is just outside of the horizon.
        int endIndex = 0;
        final long horizonStartTime = now - horizon;
        for (int i = 0; i < size - 1; i++) {
            if (mAmbientLightRingBuffer.getTime(i + 1) <= horizonStartTime) {
                endIndex++;
            } else {
                break;
            }
        }
        if (mLoggingEnabled) {
            Slog.d(mTag, "calculateAmbientLux: selected endIndex=" + endIndex + ", point=("
                    + mAmbientLightRingBuffer.getTime(endIndex) + ", "
                    + mAmbientLightRingBuffer.getLux(endIndex) + ")");
        }
        float sum = 0;
        float totalWeight = 0;
        long endTime = AMBIENT_LIGHT_PREDICTION_TIME_MILLIS;
        for (int i = size - 1; i >= endIndex; i--) {
            long eventTime = mAmbientLightRingBuffer.getTime(i);
            if (i == endIndex && eventTime < horizonStartTime) {
                // If we're at the final value, make sure we only consider the part of the sample
                // within our desired horizon.
                eventTime = horizonStartTime;
            }
            final long startTime = eventTime - now;
            float weight = calculateWeight(startTime, endTime);
            float lux = mAmbientLightRingBuffer.getLux(i);
            if (mLoggingEnabled) {
                Slog.d(mTag, "calculateAmbientLux: [" + startTime + ", " + endTime + "]: "
                        + "lux=" + lux + ", "
                        + "weight=" + weight);
            }
            totalWeight += weight;
            sum += lux * weight;
            endTime = startTime;
        }
        if (mLoggingEnabled) {
            Slog.d(mTag, "calculateAmbientLux: "
                    + "totalWeight=" + totalWeight + ", "
                    + "newAmbientLux=" + (sum / totalWeight));
        }
        return sum / totalWeight;
    }

    private float calculateWeight(long startDelta, long endDelta) {
        return weightIntegral(endDelta) - weightIntegral(startDelta);
    }

    // Evaluates the integral of y = x + mWeightingIntercept. This is always positive for the
    // horizon we're looking at and provides a non-linear weighting for light samples.
    private float weightIntegral(long x) {
        return x * (x * 0.5f + mConfig.mWeightingIntercept);
    }

    private long nextAmbientLightBrighteningTransition(long time) {
        final int size = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = size - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) <= mAmbientBrighteningThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + (mIsIdleMode ? mConfig.mBrighteningLightDebounceConfigIdle
                : mConfig.mBrighteningLightDebounceConfig);
    }

    private long nextAmbientLightDarkeningTransition(long time) {
        final int size = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = size - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) >= mAmbientDarkeningThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + (mIsIdleMode ? mConfig.mDarkeningLightDebounceConfigIdle
                : mConfig.mDarkeningLightDebounceConfig);
    }

    private void updateAmbientLux() {
        long time = mClock.uptimeMillis();
        mAmbientLightRingBuffer.prune(time - mConfig.mAmbientLightHorizonLong);
        updateAmbientLux(time);
    }

    private void updateAmbientLux(long time) {
        // If the light sensor was just turned on then immediately update our initial
        // estimate of the current ambient light level.
        if (!mAmbientLuxValid) {
            final long timeWhenSensorWarmedUp =
                    mConfig.mLightSensorWarmUpTimeConfig + mLightSensorEnableTime;
            if (time < timeWhenSensorWarmedUp) {
                if (mLoggingEnabled) {
                    Slog.d(mTag, "updateAmbientLux: Sensor not ready yet: "
                            + "time=" + time + ", "
                            + "timeWhenSensorWarmedUp=" + timeWhenSensorWarmedUp);
                }
                mHandler.postAtTime(mAmbientLuxUpdater, timeWhenSensorWarmedUp);
                return;
            }
            mAmbientLuxValid = true;
            setAmbientLux(calculateAmbientLux(time, mConfig.mAmbientLightHorizonShort));
            if (mLoggingEnabled) {
                Slog.d(mTag, "updateAmbientLux: Initializing: "
                        + "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", "
                        + "mAmbientLux=" + mAmbientLux);
            }
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
        mSlowAmbientLux = calculateAmbientLux(time, mConfig.mAmbientLightHorizonLong);
        mFastAmbientLux = calculateAmbientLux(time, mConfig.mAmbientLightHorizonShort);

        if ((mSlowAmbientLux >= mAmbientBrighteningThreshold
                && mFastAmbientLux >= mAmbientBrighteningThreshold
                && nextBrightenTransition <= time)
                || (mSlowAmbientLux <= mAmbientDarkeningThreshold
                && mFastAmbientLux <= mAmbientDarkeningThreshold
                && nextDarkenTransition <= time)) {
            mPreThresholdLux = mAmbientLux;
            setAmbientLux(mFastAmbientLux);
            if (mLoggingEnabled) {
                Slog.d(mTag, "updateAmbientLux: "
                        + ((mFastAmbientLux > mAmbientLux) ? "Brightened" : "Darkened") + ": "
                        + "mAmbientBrighteningThreshold=" + mAmbientBrighteningThreshold + ", "
                        + "mAmbientDarkeningThreshold=" + mAmbientDarkeningThreshold + ", "
                        + "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", "
                        + "mAmbientLux=" + mAmbientLux);
            }
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
        nextTransitionTime = nextTransitionTime > time ? nextTransitionTime
                : time + mConfig.mNormalLightSensorRate;
        if (mLoggingEnabled) {
            Slog.d(mTag, "updateAmbientLux: Scheduling ambient lux update for "
                    + nextTransitionTime + TimeUtils.formatUptime(nextTransitionTime));
        }
        mHandler.postAtTime(mAmbientLuxUpdater, nextTransitionTime);
    }

    public interface LightSensorListener {
        /**
         * Called when new ambient lux value is ready
         */
        void onAmbientLuxChange(float ambientLux);
    }

    private static final class LightSensorHandler extends Handler {
        private LightSensorHandler(Looper looper) {
            super(looper, /* callback= */ null, /* async= */ true);
        }
    }

    /**
     * A ring buffer of ambient light measurements sorted by time.
     * Each entry consists of a timestamp and a lux measurement, and the overall buffer is sorted
     * from oldest to newest.
     */
    @VisibleForTesting
    static final class AmbientLightRingBuffer {

        private float[] mRingLux;
        private long[] mRingTime;
        private int mCapacity;

        // The first valid element and the next open slot.
        // Note that if mCount is zero then there are no valid elements.
        private int mStart;
        private int mEnd;
        private int mCount;

        private final Clock mClock;

        @VisibleForTesting
        AmbientLightRingBuffer(int initialCapacity, Clock clock) {
            mCapacity = initialCapacity;
            mRingLux = new float[mCapacity];
            mRingTime = new long[mCapacity];
            mClock = clock;

        }

        @VisibleForTesting
        float getLux(int index) {
            return mRingLux[offsetOf(index)];
        }

        @VisibleForTesting
        float[] getAllLuxValues() {
            float[] values = new float[mCount];
            if (mCount == 0) {
                return values;
            }

            if (mStart < mEnd) {
                System.arraycopy(mRingLux, mStart, values, 0, mCount);
            } else {
                System.arraycopy(mRingLux, mStart, values, 0, mCapacity - mStart);
                System.arraycopy(mRingLux, 0, values, mCapacity - mStart, mEnd);
            }

            return values;
        }

        @VisibleForTesting
        long getTime(int index) {
            return mRingTime[offsetOf(index)];
        }

        @VisibleForTesting
        long[] getAllTimestamps() {
            long[] values = new long[mCount];
            if (mCount == 0) {
                return values;
            }

            if (mStart < mEnd) {
                System.arraycopy(mRingTime, mStart, values, 0, mCount);
            } else {
                System.arraycopy(mRingTime, mStart, values, 0, mCapacity - mStart);
                System.arraycopy(mRingTime, 0, values, mCapacity - mStart, mEnd);
            }

            return values;
        }

        @VisibleForTesting
        void push(long time, float lux) {
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

        @VisibleForTesting
        void prune(long horizon) {
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

        @VisibleForTesting
        int size() {
            return mCount;
        }

        @VisibleForTesting
        void clear() {
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append('[');
            for (int i = 0; i < mCount; i++) {
                final long next = i + 1 < mCount ? getTime(i + 1) : mClock.uptimeMillis();
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

    @VisibleForTesting
    interface Injector {
        Clock getClock();

        Sensor getLightSensor(LightSensorControllerConfig config);

        boolean registerLightSensorListener(
                SensorEventListener listener, Sensor sensor, int rate, Handler handler);

        void unregisterLightSensorListener(SensorEventListener listener);

        String getTag();

    }

    private static class RealInjector implements Injector {
        private final SensorManager mSensorManager;
        private final int mSensorFallbackType;

        private final String mTag;

        private RealInjector(SensorManager sensorManager, int displayId) {
            mSensorManager = sensorManager;
            mSensorFallbackType = displayId == Display.DEFAULT_DISPLAY
                    ? Sensor.TYPE_LIGHT : SensorUtils.NO_FALLBACK;
            mTag = "LightSensorController [" + displayId + "]";
        }

        @Override
        public Clock getClock() {
            return Clock.SYSTEM_CLOCK;
        }

        @Override
        public Sensor getLightSensor(LightSensorControllerConfig config) {
            return SensorUtils.findSensor(
                    mSensorManager, config.mAmbientLightSensor, mSensorFallbackType);
        }

        @Override
        public boolean registerLightSensorListener(
                SensorEventListener listener, Sensor sensor, int rate, Handler handler) {
            return mSensorManager.registerListener(listener, sensor, rate * 1000, handler);
        }

        @Override
        public void unregisterLightSensorListener(SensorEventListener listener) {
            mSensorManager.unregisterListener(listener);
        }

        @Override
        public String getTag() {
            return mTag;
        }
    }

    public static class LightSensorControllerConfig {
        // Steady-state light sensor event rate in milliseconds.
        private final int mNormalLightSensorRate;
        private final int mInitialLightSensorRate;

        // If true immediately after the screen is turned on the controller will try to adjust the
        // brightness based on the current sensor reads. If false, the controller will collect
        // more data
        // and only then decide whether to change brightness.
        private final boolean mResetAmbientLuxAfterWarmUpConfig;

        // Period of time in which to consider light samples for a short/long-term estimate of
        // ambient
        // light in milliseconds.
        private final int mAmbientLightHorizonShort;
        private final int mAmbientLightHorizonLong;


        // Amount of time to delay auto-brightness after screen on while waiting for
        // the light sensor to warm-up in milliseconds.
        // May be 0 if no warm-up is required.
        private final int mLightSensorWarmUpTimeConfig;


        // The intercept used for the weighting calculation. This is used in order to keep all
        // possible
        // weighting values positive.
        private final int mWeightingIntercept;

        // Configuration object for determining thresholds to change brightness dynamically
        private final HysteresisLevels mAmbientBrightnessThresholds;
        private final HysteresisLevels mAmbientBrightnessThresholdsIdle;


        // Stability requirements in milliseconds for accepting a new brightness level.  This is
        // used
        // for debouncing the light sensor.  Different constants are used to debounce the light
        // sensor
        // when adapting to brighter or darker environments.  This parameter controls how quickly
        // brightness changes occur in response to an observed change in light level that exceeds
        // the
        // hysteresis threshold.
        private final long mBrighteningLightDebounceConfig;
        private final long mDarkeningLightDebounceConfig;
        private final long mBrighteningLightDebounceConfigIdle;
        private final long mDarkeningLightDebounceConfigIdle;

        private final SensorData mAmbientLightSensor;

        @VisibleForTesting
        LightSensorControllerConfig(int initialLightSensorRate, int normalLightSensorRate,
                boolean resetAmbientLuxAfterWarmUpConfig, int ambientLightHorizonShort,
                int ambientLightHorizonLong, int lightSensorWarmUpTimeConfig,
                int weightingIntercept, HysteresisLevels ambientBrightnessThresholds,
                HysteresisLevels ambientBrightnessThresholdsIdle,
                long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
                long brighteningLightDebounceConfigIdle, long darkeningLightDebounceConfigIdle,
                SensorData ambientLightSensor) {
            mInitialLightSensorRate = initialLightSensorRate;
            mNormalLightSensorRate = normalLightSensorRate;
            mResetAmbientLuxAfterWarmUpConfig = resetAmbientLuxAfterWarmUpConfig;
            mAmbientLightHorizonShort = ambientLightHorizonShort;
            mAmbientLightHorizonLong = ambientLightHorizonLong;
            mLightSensorWarmUpTimeConfig = lightSensorWarmUpTimeConfig;
            mWeightingIntercept = weightingIntercept;
            mAmbientBrightnessThresholds = ambientBrightnessThresholds;
            mAmbientBrightnessThresholdsIdle = ambientBrightnessThresholdsIdle;
            mBrighteningLightDebounceConfig = brighteningLightDebounceConfig;
            mDarkeningLightDebounceConfig = darkeningLightDebounceConfig;
            mBrighteningLightDebounceConfigIdle = brighteningLightDebounceConfigIdle;
            mDarkeningLightDebounceConfigIdle = darkeningLightDebounceConfigIdle;
            mAmbientLightSensor = ambientLightSensor;
        }

        private void dump(PrintWriter pw) {
            pw.println("LightSensorControllerConfig:");
            pw.println("  mInitialLightSensorRate=" + mInitialLightSensorRate);
            pw.println("  mNormalLightSensorRate=" + mNormalLightSensorRate);
            pw.println("  mResetAmbientLuxAfterWarmUpConfig=" + mResetAmbientLuxAfterWarmUpConfig);
            pw.println("  mAmbientLightHorizonShort=" + mAmbientLightHorizonShort);
            pw.println("  mAmbientLightHorizonLong=" + mAmbientLightHorizonLong);
            pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);
            pw.println("  mWeightingIntercept=" + mWeightingIntercept);
            pw.println("  mAmbientBrightnessThresholds=");
            mAmbientBrightnessThresholds.dump(pw);
            pw.println("  mAmbientBrightnessThresholdsIdle=");
            mAmbientBrightnessThresholdsIdle.dump(pw);
            pw.println("  mBrighteningLightDebounceConfig=" + mBrighteningLightDebounceConfig);
            pw.println("  mDarkeningLightDebounceConfig=" + mDarkeningLightDebounceConfig);
            pw.println(
                    "  mBrighteningLightDebounceConfigIdle=" + mBrighteningLightDebounceConfigIdle);
            pw.println("  mDarkeningLightDebounceConfigIdle=" + mDarkeningLightDebounceConfigIdle);
            pw.println("  mAmbientLightSensor=" + mAmbientLightSensor);
        }

        /**
         * Creates LightSensorControllerConfig object form Resources and DisplayDeviceConfig
         */
        public static LightSensorControllerConfig create(Resources res, DisplayDeviceConfig ddc) {
            int lightSensorRate = res.getInteger(R.integer.config_autoBrightnessLightSensorRate);
            int initialLightSensorRate = res.getInteger(
                    R.integer.config_autoBrightnessInitialLightSensorRate);
            if (initialLightSensorRate == -1) {
                initialLightSensorRate = lightSensorRate;
            } else if (initialLightSensorRate > lightSensorRate) {
                Slog.w("LightSensorControllerConfig",
                        "Expected config_autoBrightnessInitialLightSensorRate ("
                                + initialLightSensorRate + ") to be less than or equal to "
                                + "config_autoBrightnessLightSensorRate (" + lightSensorRate
                                + ").");
            }

            boolean resetAmbientLuxAfterWarmUp = res.getBoolean(
                    R.bool.config_autoBrightnessResetAmbientLuxAfterWarmUp);
            int lightSensorWarmUpTimeConfig = res.getInteger(
                    R.integer.config_lightSensorWarmupTime);

            return new LightSensorControllerConfig(initialLightSensorRate, lightSensorRate,
                    resetAmbientLuxAfterWarmUp, ddc.getAmbientHorizonShort(),
                    ddc.getAmbientHorizonLong(), lightSensorWarmUpTimeConfig,
                    ddc.getAmbientHorizonLong(),
                    HysteresisLevels.getAmbientBrightnessThresholds(ddc),
                    HysteresisLevels.getAmbientBrightnessThresholdsIdle(ddc),
                    ddc.getAutoBrightnessBrighteningLightDebounce(),
                    ddc.getAutoBrightnessDarkeningLightDebounce(),
                    ddc.getAutoBrightnessBrighteningLightDebounceIdle(),
                    ddc.getAutoBrightnessDarkeningLightDebounceIdle(),
                    ddc.getAmbientLightSensor()
            );
        }
    }
}
