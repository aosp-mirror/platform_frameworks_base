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

package com.android.systemui.util;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

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
    private final float mThreshold;

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public synchronized void onSensorChanged(SensorEvent event) {
            onSensorEvent(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
    private boolean mNear;
    private List<ProximitySensorListener> mListeners = new ArrayList<>();
    private String mTag = null;

    @Inject
    public ProximitySensor(Context context, AsyncSensorManager sensorManager) {
        mSensorManager = sensorManager;
        Sensor sensor = findCustomProxSensor(context, sensorManager);

        if (sensor == null) {
            mUsingBrightnessSensor = false;
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        } else {
            mUsingBrightnessSensor = true;
        }
        mSensor = sensor;
        if (mSensor != null) {
            if (mUsingBrightnessSensor) {
                mThreshold = getBrightnessSensorThreshold(context.getResources());
            } else {
                mThreshold = mSensor.getMaximumRange();
            }
        } else {
            mThreshold = 0;
        }
    }

    public void setTag(String tag) {
        mTag = tag;
    }

    /**
     * Returns a brightness sensor that can be used for proximity purposes.
     *
     * @deprecated This method exists for legacy purposes. Use the containing class directly.
     */
    @Deprecated
    public static Sensor findCustomProxSensor(Context context, SensorManager sensorManager) {
        String sensorType = context.getString(R.string.proximity_sensor_type);
        if (sensorType.isEmpty()) {
            return null;
        }

        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);
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
     * Returns a threshold value that can be used along with {@link #findCustomProxSensor}
     *
     * @deprecated This method exists for legacy purposes. Use the containing class directly.
     */
    @Deprecated
    public static float getBrightnessSensorThreshold(Resources resources) {
        return resources.getFloat(R.dimen.proximity_sensor_threshold);
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
     * added.
     */
    public boolean register(ProximitySensorListener listener) {
        if (!getSensorAvailable()) {
            return false;
        }

        logDebug("using brightness sensor? " + mUsingBrightnessSensor);
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            logDebug("registering sensor listener");
            mSensorManager.registerListener(
                    mSensorEventListener, mSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        return true;
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
            logDebug("unregistering sensor listener");
            mSensorManager.unregisterListener(mSensorEventListener);
        }
    }

    public boolean isNear() {
        return getSensorAvailable() && mNear;
    }

    private void onSensorEvent(SensorEvent event) {
        if (mUsingBrightnessSensor) {
            mNear = event.values[0] <= mThreshold;
        } else {
            mNear = event.values[0] < mThreshold;
        }
        mListeners.forEach(proximitySensorListener ->
                proximitySensorListener.onProximitySensorEvent(
                        new ProximityEvent(mNear, event.timestamp)));
    }

    /** Implement to be notified of ProximityEvents. */
    public interface ProximitySensorListener {
        /** Called when the ProximitySensor changes. */
        void onProximitySensorEvent(ProximityEvent proximityEvent);
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
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }
}
