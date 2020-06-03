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
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.Assert;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

class ThresholdSensorImpl implements ThresholdSensor {
    private static final String TAG = "ThresholdSensor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final AsyncSensorManager mSensorManager;
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

    private ThresholdSensorImpl(AsyncSensorManager sensorManager,
            Sensor sensor, float threshold, float thresholdLatch, int sensorDelay) {
        mSensorManager = sensorManager;
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
        Assert.isMainThread();
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
        registerInternal();
    }

    @Override
    public void unregister(Listener listener) {
        Assert.isMainThread();
        mListeners.remove(listener);
        unregisterInternal();
    }

    /**
     * Unregister with the {@link SensorManager} without unsetting listeners on this object.
     */
    @Override
    public void pause() {
        Assert.isMainThread();
        mPaused = true;
        unregisterInternal();
    }

    /**
     * Register with the {@link SensorManager}. No-op if no listeners are registered on this object.
     */
    @Override
    public void resume() {
        Assert.isMainThread();
        mPaused = false;
        registerInternal();
    }

    private void alertListenersInternal(boolean below, long timestampNs) {
        List<Listener> listeners = new ArrayList<>(mListeners);
        listeners.forEach(listener ->
                listener.onThresholdCrossed(new ThresholdSensorEvent(below, timestampNs)));
    }

    private void registerInternal() {
        Assert.isMainThread();
        if (mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        logDebug("Registering sensor listener");
        mSensorManager.registerListener(mSensorEventListener, mSensor, mSensorDelay);
        mRegistered = true;
    }

    private void unregisterInternal() {
        Assert.isMainThread();
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
        Assert.isMainThread();
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
    public String toString() {
        return String.format("{registered=%s, paused=%s, threshold=%s, sensor=%s}",
                isLoaded(), mRegistered, mPaused, mThreshold, mSensor);
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }

    static class Builder {
        private final Resources mResources;
        private final AsyncSensorManager mSensorManager;
        private int mSensorDelay = SensorManager.SENSOR_DELAY_NORMAL;;
        private float mThresholdValue;
        private float mThresholdLatchValue;
        private Sensor mSensor;
        private boolean mSensorSet;
        private boolean mThresholdSet;
        private boolean mThresholdLatchValueSet;

        @Inject
        Builder(@Main Resources resources, AsyncSensorManager sensorManager) {
            mResources = resources;
            mSensorManager = sensorManager;
        }


        Builder setSensorDelay(int sensorDelay) {
            mSensorDelay = sensorDelay;
            return this;
        }

        Builder setSensorResourceId(int sensorResourceId) {
            setSensorType(mResources.getString(sensorResourceId));
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

        Builder setSensorType(String sensorType) {
            Sensor sensor = findSensorByType(sensorType);
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
                    mSensorManager, mSensor, mThresholdValue, mThresholdLatchValue, mSensorDelay);
        }

        private Sensor findSensorByType(String sensorType) {
            if (sensorType.isEmpty()) {
                return null;
            }

            List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            Sensor sensor = null;
            for (Sensor s : sensorList) {
                if (sensorType.equals(s.getStringType())) {
                    sensor = s;
                    break;
                }
            }

            return sensor;
        }
    }
}
