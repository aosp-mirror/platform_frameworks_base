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

package com.android.systemui.util.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Simple wrapper around SensorManager customized for the Proximity sensor.
 */
public class ProximitySensor {
    private static final String TAG = "ProxSensor";
    private static final boolean DEBUG = false;

    private final Sensor mSensor;
    private final AsyncSensorManager mSensorManager;
    private final boolean mUsingBrightnessSensor;
    private final float mMaxRange;
    private List<ProximitySensorListener> mListeners = new ArrayList<>();
    private String mTag = null;
    @VisibleForTesting ProximityEvent mLastEvent;
    private int mSensorDelay = SensorManager.SENSOR_DELAY_NORMAL;
    private boolean mPaused;
    private boolean mRegistered;

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public synchronized void onSensorChanged(SensorEvent event) {
            onSensorEvent(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    @Inject
    public ProximitySensor(Context context, AsyncSensorManager sensorManager) {
        mSensorManager = sensorManager;
        Sensor sensor = findBrightnessSensor(context);

        if (sensor == null) {
            mUsingBrightnessSensor = false;
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        } else {
            mUsingBrightnessSensor = true;
        }
        mSensor = sensor;
        if (mSensor != null) {
            mMaxRange = mSensor.getMaximumRange();
        } else {
            mMaxRange = 0;
        }
    }

    public void setTag(String tag) {
        mTag = tag;
    }

    public void setSensorDelay(int sensorDelay) {
        mSensorDelay = sensorDelay;
    }

    /**
     * Unregister with the {@link SensorManager} without unsetting listeners on this object.
     */
    public void pause() {
        mPaused = true;
        unregisterInternal();
    }

    /**
     * Register with the {@link SensorManager}. No-op if no listeners are registered on this object.
     */
    public void resume() {
        mPaused = false;
        registerInternal();
    }

    private Sensor findBrightnessSensor(Context context) {
        String sensorType = context.getString(R.string.doze_brightness_sensor_type);
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

    /**
     * Returns true if we are registered with the SensorManager.
     */
    public boolean isRegistered() {
        return mRegistered;
    }

    /**
     * Returns {@code false} if a Proximity sensor is not available.
     */
    public boolean getSensorAvailable() {
        return mSensor != null;
    }

    /**
     * Add a listener.
     *
     * Registers itself with the {@link SensorManager} if this is the first listener
     * added. If a cool down is currently running, the sensor will be registered when it is over.
     */
    public boolean register(ProximitySensorListener listener) {
        if (!getSensorAvailable()) {
            return false;
        }

        mListeners.add(listener);
        registerInternal();

        return true;
    }

    protected void registerInternal() {
        if (mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        logDebug("Using brightness sensor? " + mUsingBrightnessSensor);
        logDebug("Registering sensor listener");
        mRegistered = true;
        mSensorManager.registerListener(mSensorEventListener, mSensor, mSensorDelay);
    }

    /**
     * Remove a listener.
     *
     * If all listeners are removed from an instance of this class,
     * it will unregister itself with the SensorManager.
     */
    public void unregister(ProximitySensorListener listener) {
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            unregisterInternal();
        }
    }

    protected void unregisterInternal() {
        if (!mRegistered) {
            return;
        }
        logDebug("unregistering sensor listener");
        mSensorManager.unregisterListener(mSensorEventListener);
        mRegistered = false;
    }

    public Boolean isNear() {
        return getSensorAvailable() && mLastEvent != null ? mLastEvent.getNear() : null;
    }

    /** Update all listeners with the last value this class received from the sensor. */
    public void alertListeners() {
        mListeners.forEach(proximitySensorListener ->
                proximitySensorListener.onSensorEvent(mLastEvent));
    }

    private void onSensorEvent(SensorEvent event) {
        boolean near = event.values[0] < mMaxRange;
        if (mUsingBrightnessSensor) {
            near = event.values[0] == 0;
        }
        mLastEvent = new ProximityEvent(near, event.timestamp);
        alertListeners();
    }

    @Override
    public String toString() {
        return String.format("{registered=%s, paused=%s, near=%s, sensor=%s}",
                isRegistered(), mPaused, isNear(), mSensor);
    }

    /**
     * Convenience class allowing for briefly checking the proximity sensor.
     */
    public static class ProximityCheck implements Runnable {

        private final ProximitySensor mSensor;
        private final Handler mHandler;
        private List<Consumer<Boolean>> mCallbacks = new ArrayList<>();

        @Inject
        public ProximityCheck(ProximitySensor sensor, Handler handler) {
            mSensor = sensor;
            mSensor.setTag("prox_check");
            mHandler = handler;
            mSensor.pause();
            ProximitySensorListener listener = proximityEvent -> {
                mCallbacks.forEach(
                        booleanConsumer ->
                                booleanConsumer.accept(
                                        proximityEvent == null ? null : proximityEvent.getNear()));
                mCallbacks.clear();
                mSensor.pause();
            };
            mSensor.register(listener);
        }

        /** Set a descriptive tag for the sensors registration. */
        public void setTag(String tag) {
            mSensor.setTag(tag);
        }

        @Override
        public void run() {
            mSensor.pause();
            mSensor.alertListeners();
        }

        /**
         * Query the proximity sensor, timing out if no result.
         */
        public void check(long timeoutMs, Consumer<Boolean> callback) {
            if (!mSensor.getSensorAvailable()) {
                callback.accept(null);
            }
            mCallbacks.add(callback);
            if (!mSensor.isRegistered()) {
                mSensor.resume();
                mHandler.postDelayed(this, timeoutMs);
            }
        }
    }

    /** Implement to be notified of ProximityEvents. */
    public interface ProximitySensorListener {
        /** Called when the ProximitySensor changes. */
        void onSensorEvent(ProximityEvent proximityEvent);
    }

    /**
     * Returned when the near/far state of a {@link ProximitySensor} changes.
     */
    public static class ProximityEvent {
        private final boolean mNear;
        private final long mTimestampNs;

        public ProximityEvent(boolean near, long timestampNs) {
            mNear = near;
            mTimestampNs = timestampNs;
        }

        public boolean getNear() {
            return mNear;
        }

        public long getTimestampNs() {
            return mTimestampNs;
        }

        public long getTimestampMs() {
            return mTimestampNs / 1000000;
        }

        @Override
        public String toString() {
            return String.format((Locale) null, "{near=%s, timestamp_ns=%d}", mNear, mTimestampNs);
        }

    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }
}
