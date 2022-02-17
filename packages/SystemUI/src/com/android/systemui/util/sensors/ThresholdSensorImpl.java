/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.Execution;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Sensor that will only trigger beyond some lower and upper threshold.
 */
public class ThresholdSensorImpl implements ThresholdSensor {
    private static final String TAG = "ThresholdSensor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final AsyncSensorManager mSensorManager;
    private final Execution mExecution;
    private final Sensor mSensor;
    private final float mThreshold;
    private boolean mRegistered;
    private boolean mPaused;
    private List<Listener> mListeners = new ArrayList<>();
    private Boolean mLastBelow;
    private String mTag;
    private final float mThresholdLatch;
    private int mSensorDelay;

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            boolean below = event.values[0] < mThreshold;
            boolean above = event.values[0] >= mThresholdLatch;
            logDebug("Sensor value: " + event.values[0]);
            onSensorEvent(below, above, event.timestamp);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private ThresholdSensorImpl(AsyncSensorManager sensorManager, Sensor sensor,
            Execution execution,  float threshold, float thresholdLatch, int sensorDelay) {
        mSensorManager = sensorManager;
        mExecution = execution;
        mSensor = sensor;
        mThreshold = threshold;
        mThresholdLatch = thresholdLatch;
        mSensorDelay = sensorDelay;
    }

    @Override
    public void setTag(String tag) {
        mTag = tag;
    }

    @Override
    public void setDelay(int delay) {
        if (delay == mSensorDelay) {
            return;
        }

        mSensorDelay = delay;
        if (isLoaded()) {
            unregisterInternal();
            registerInternal();
        }
    }

    @Override
    public boolean isLoaded() {
        return mSensor != null;
    }

    @VisibleForTesting
    boolean isRegistered() {
        return mRegistered;
    }

    /**
     * Registers the listener with the sensor.
     *
     * Multiple listeners are not supported at this time.
     *
     * Returns true if the listener was successfully registered. False otherwise.
     */
    @Override
    public void register(Listener listener) {
        mExecution.assertIsMainThread();
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
        registerInternal();
    }

    @Override
    public void unregister(Listener listener) {
        mExecution.assertIsMainThread();
        mListeners.remove(listener);
        unregisterInternal();
    }

    /**
     * Unregister with the {@link SensorManager} without unsetting listeners on this object.
     */
    @Override
    public void pause() {
        mExecution.assertIsMainThread();
        mPaused = true;
        unregisterInternal();
    }

    /**
     * Register with the {@link SensorManager}. No-op if no listeners are registered on this object.
     */
    @Override
    public void resume() {
        mExecution.assertIsMainThread();
        mPaused = false;
        registerInternal();
    }

    private void alertListenersInternal(boolean below, long timestampNs) {
        List<Listener> listeners = new ArrayList<>(mListeners);
        listeners.forEach(listener ->
                listener.onThresholdCrossed(new ThresholdSensorEvent(below, timestampNs)));
    }

    private void registerInternal() {
        mExecution.assertIsMainThread();
        if (mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        logDebug("Registering sensor listener");
        mSensorManager.registerListener(mSensorEventListener, mSensor, mSensorDelay);
        mRegistered = true;
    }

    private void unregisterInternal() {
        mExecution.assertIsMainThread();
        if (!mRegistered) {
            return;
        }
        logDebug("Unregister sensor listener");
        mSensorManager.unregisterListener(mSensorEventListener);
        mRegistered = false;
        mLastBelow = null;  // Forget what we know.
    }

    /**
     * Call when the sensor reports a new value.
     *
     * Separate below-threshold and above-thresholds are specified. this allows latching behavior,
     * where a different threshold can be specified for triggering the sensor depending on if it's
     * going from above to below or below to above. To outside listeners of this class, the class
     * still appears entirely binary.
     */
    private void onSensorEvent(boolean belowThreshold, boolean aboveThreshold, long timestampNs) {
        mExecution.assertIsMainThread();
        if (!mRegistered) {
            return;
        }
        if (mLastBelow != null) {
            // If we last reported below and are not yet above, change nothing.
            if (mLastBelow && !aboveThreshold) {
                return;
            }
            // If we last reported above and are not yet below, change nothing.
            if (!mLastBelow && !belowThreshold) {
                return;
            }
        }
        mLastBelow = belowThreshold;
        logDebug("Alerting below: " + belowThreshold);
        alertListenersInternal(belowThreshold, timestampNs);
    }

    @Override
    public String getName() {
        return mSensor != null ? mSensor.getName() : null;
    }

    @Override
    public String getType() {
        return mSensor != null ? mSensor.getStringType() : null;
    }

    @Override
    public String toString() {
        return String.format("{isLoaded=%s, registered=%s, paused=%s, threshold=%s, sensor=%s}",
                isLoaded(), mRegistered, mPaused, mThreshold, mSensor);
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }

    /**
     * Use to build a ThresholdSensor. Should only be used once per sensor built, since
     * parameters are not reset after calls to build(). For ease of retrievingnew Builders, use
     * {@link BuilderFactory}.
     */
    public static class Builder {
        private final Resources mResources;
        private final AsyncSensorManager mSensorManager;
        private final Execution mExecution;
        private int mSensorDelay = SensorManager.SENSOR_DELAY_NORMAL;;
        private float mThresholdValue;
        private float mThresholdLatchValue;
        private Sensor mSensor;
        private boolean mSensorSet;
        private boolean mThresholdSet;
        private boolean mThresholdLatchValueSet;

        @Inject
        Builder(@Main Resources resources, AsyncSensorManager sensorManager, Execution execution) {
            mResources = resources;
            mSensorManager = sensorManager;
            mExecution = execution;
        }

        Builder setSensorDelay(int sensorDelay) {
            mSensorDelay = sensorDelay;
            return this;
        }
        /**
         * If requiresWakeUp is false, the first sensor with sensorType (regardless of whether the
         * sensor is a wakeup sensor or not) will be set.
         */
        Builder setSensorResourceId(int sensorResourceId, boolean requireWakeUp) {
            setSensorType(mResources.getString(sensorResourceId), requireWakeUp);
            return this;
        }

        Builder setThresholdResourceId(int thresholdResourceId) {
            try {
                setThresholdValue(mResources.getFloat(thresholdResourceId));
            } catch (Resources.NotFoundException e) {
                // no-op
            }
            return this;
        }

        Builder setThresholdLatchResourceId(int thresholdLatchResourceId) {
            try {
                setThresholdLatchValue(mResources.getFloat(thresholdLatchResourceId));
            } catch (Resources.NotFoundException e) {
                // no-op
            }
            return this;
        }

        /**
         * If requiresWakeUp is false, the first sensor with sensorType (regardless of whether the
         * sensor is a wakeup sensor or not) will be set.
         */
        Builder setSensorType(String sensorType, boolean requireWakeUp) {
            Sensor sensor = findSensorByType(sensorType, requireWakeUp);
            if (sensor != null) {
                setSensor(sensor);
            }
            return this;
        }

        Builder setThresholdValue(float thresholdValue) {
            mThresholdValue = thresholdValue;
            mThresholdSet = true;
            if (!mThresholdLatchValueSet) {
                mThresholdLatchValue = mThresholdValue;
            }
            return this;
        }

        Builder setThresholdLatchValue(float thresholdLatchValue) {
            mThresholdLatchValue = thresholdLatchValue;
            mThresholdLatchValueSet = true;
            return this;
        }

        Builder setSensor(Sensor sensor) {
            mSensor = sensor;
            mSensorSet = true;
            return this;
        }

        /**
         * Creates a {@link ThresholdSensor} backed by a {@link ThresholdSensorImpl}.
         */
        public ThresholdSensor build() {
            if (!mSensorSet) {
                throw new IllegalStateException("A sensor was not successfully set.");
            }

            if (!mThresholdSet) {
                throw new IllegalStateException("A threshold was not successfully set.");
            }

            if (mThresholdValue > mThresholdLatchValue) {
                throw new IllegalStateException(
                        "Threshold must be less than or equal to Threshold Latch");
            }

            return new ThresholdSensorImpl(
                    mSensorManager, mSensor, mExecution,
                    mThresholdValue, mThresholdLatchValue, mSensorDelay);
        }

        @VisibleForTesting
        Sensor findSensorByType(String sensorType, boolean requireWakeUp) {
            if (TextUtils.isEmpty(sensorType)) {
                return null;
            }

            List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            Sensor sensor = null;
            for (Sensor s : sensorList) {
                if (sensorType.equals(s.getStringType())) {
                    sensor = s;
                    if (!requireWakeUp || sensor.isWakeUpSensor()) {
                        break;
                    }
                }
            }

            return sensor;
        }
    }

    /**
     * Factory that creates a new ThresholdSensorImpl.Builder. In general, Builders should not be
     * reused after creating a ThresholdSensor or else there may be default threshold and sensor
     * values set from the previous built sensor.
     */
    public static class BuilderFactory {
        private final Resources mResources;
        private final AsyncSensorManager mSensorManager;
        private final Execution mExecution;

        @Inject
        BuilderFactory(
                @Main Resources resources,
                AsyncSensorManager sensorManager,
                Execution execution) {
            mResources = resources;
            mSensorManager = sensorManager;
            mExecution = execution;
        }

        ThresholdSensorImpl.Builder createBuilder() {
            return new Builder(mResources, mSensorManager, mExecution);
        }
    }
}
