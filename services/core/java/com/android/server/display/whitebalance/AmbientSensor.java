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
import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.server.display.utils.History;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * The DisplayWhiteBalanceController uses the AmbientSensor to detect changes in the ambient
 * brightness and color temperature.
 *
 * The AmbientSensor listens on an actual sensor, derives the ambient brightness or color
 * temperature from its events, and calls back into the DisplayWhiteBalanceController to report it.
 */
abstract class AmbientSensor {

    protected String mTag;
    protected boolean mLoggingEnabled;

    private final Handler mHandler;

    protected final SensorManager mSensorManager;
    protected Sensor mSensor;

    private boolean mEnabled;

    private int mRate; // Milliseconds

    // The total events count and the most recent events are kept for debugging purposes.
    private int mEventsCount;
    private static final int HISTORY_SIZE = 50;
    private History mEventsHistory;

    /**
     * @param tag
     *      The tag used for dumping and logging.
     * @param handler
     *      The handler used to determine which thread to run on.
     * @param sensorManager
     *      The sensor manager used to acquire necessary sensors.
     * @param rate
     *      The sensor rate.
     *
     * @throws IllegalArgumentException
     *      - rate is not positive.
     * @throws NullPointerException
     *      - handler is null;
     *      - sensorManager is null.
     * @throws IllegalStateException
     *      - Cannot find the necessary sensor.
     */
    AmbientSensor(String tag, @NonNull Handler handler, @NonNull SensorManager sensorManager,
            int rate) {
        validateArguments(handler, sensorManager, rate);
        mTag = tag;
        mLoggingEnabled = false;
        mHandler = handler;
        mSensorManager = sensorManager;
        mEnabled = false;
        mRate = rate;
        mEventsCount = 0;
        mEventsHistory = new History(HISTORY_SIZE);
    }

    /**
     * Enable/disable the sensor.
     *
     * @param enabled
     *      Whether the sensor should be on/off.
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
     * Dump the state.
     *
     * @param writer
     *      The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println("  " + mTag);
        writer.println("    mLoggingEnabled=" + mLoggingEnabled);
        writer.println("    mHandler=" + mHandler);
        writer.println("    mSensorManager=" + mSensorManager);
        writer.println("    mSensor=" + mSensor);
        writer.println("    mEnabled=" + mEnabled);
        writer.println("    mRate=" + mRate);
        writer.println("    mEventsCount=" + mEventsCount);
        writer.println("    mEventsHistory=" + mEventsHistory);
    }


    private static void validateArguments(Handler handler, SensorManager sensorManager, int rate) {
        Objects.requireNonNull(handler, "handler cannot be null");
        Objects.requireNonNull(sensorManager, "sensorManager cannot be null");
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
    }

    protected abstract void update(float value);

    private boolean enable() {
        if (mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(mTag, "enabling");
        }
        mEnabled = true;
        startListening();
        return true;
    }

    private boolean disable() {
        if (!mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(mTag, "disabling");
        }
        mEnabled = false;
        mEventsCount = 0;
        stopListening();
        return true;
    }

    private void startListening() {
        if (mSensorManager == null) {
            return;
        }
        mSensorManager.registerListener(mListener, mSensor, mRate * 1000, mHandler);
    }

    private void stopListening() {
        if (mSensorManager == null) {
            return;
        }
        mSensorManager.unregisterListener(mListener);
    }

    private void handleNewEvent(float value) {
        // This shouldn't really happen, except for the race condition where the sensor is disabled
        // with an event already in the handler queue, in which case we discard that event.
        if (!mEnabled) {
            return;
        }
        if (mLoggingEnabled) {
            Slog.d(mTag, "handle new event: " + value);
        }
        mEventsCount++;
        mEventsHistory.add(value);
        update(value);
    }

    private SensorEventListener mListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            final float value = event.values[0];
            handleNewEvent(value);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }

    };

    /**
     * A sensor that reports the ambient brightness.
     */
    static class AmbientBrightnessSensor extends AmbientSensor {

        private static final String TAG = "AmbientBrightnessSensor";

        // To decouple the DisplayWhiteBalanceController from the AmbientBrightnessSensor, the
        // DWBC implements Callbacks and passes itself to the ABS so it can call back into it
        // without knowing about it.
        @Nullable
        private Callbacks mCallbacks;

        /**
         * @param handler
         *      The handler used to determine which thread to run on.
         * @param sensorManager
         *      The sensor manager used to acquire necessary sensors.
         * @param rate
         *      The sensor rate.
         *
         * @throws IllegalArgumentException
         *      - rate is not positive.
         * @throws NullPointerException
         *      - handler is null;
         *      - sensorManager is null.
         * @throws IllegalStateException
         *      - Cannot find the light sensor.
         */
        AmbientBrightnessSensor(@NonNull Handler handler, @NonNull SensorManager sensorManager,
                int rate) {
            super(TAG, handler, sensorManager, rate);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (mSensor == null) {
                throw new IllegalStateException("cannot find light sensor");
            }
            mCallbacks = null;
        }

        /**
         * Set an object to call back to when the ambient brightness changes.
         *
         * @param callbacks
         *      The object to call back to.
         *
         * @return Whether the method succeeded or not.
         */
        public boolean setCallbacks(Callbacks callbacks) {
            if (mCallbacks == callbacks) {
                return false;
            }
            mCallbacks = callbacks;
            return true;
        }

        /**
         * See {@link AmbientSensor#dump base class}.
         */
        @Override
        public void dump(PrintWriter writer) {
            super.dump(writer);
            writer.println("    mCallbacks=" + mCallbacks);
        }

        interface Callbacks {
            void onAmbientBrightnessChanged(float value);
        }

        @Override
        protected void update(float value) {
            if (mCallbacks != null) {
                mCallbacks.onAmbientBrightnessChanged(value);
            }
        }

    }

    /**
     * A sensor that reports the ambient color temperature.
     */
    static class AmbientColorTemperatureSensor extends AmbientSensor {

        private static final String TAG = "AmbientColorTemperatureSensor";

        // To decouple the DisplayWhiteBalanceController from the
        // AmbientColorTemperatureSensor, the DWBC implements Callbacks and passes itself to the
        // ACTS so it can call back into it without knowing about it.
        @Nullable
        private Callbacks mCallbacks;

        /**
         * @param handler
         *      The handler used to determine which thread to run on.
         * @param sensorManager
         *      The sensor manager used to acquire necessary sensors.
         * @param name
         *      The color sensor name.
         * @param rate
         *      The sensor rate.
         *
         * @throws IllegalArgumentException
         *      - rate is not positive.
         * @throws NullPointerException
         *      - handler is null;
         *      - sensorManager is null.
         * @throws IllegalStateException
         *      - Cannot find the color sensor.
         */
        AmbientColorTemperatureSensor(@NonNull Handler handler,
                @NonNull SensorManager sensorManager, String name, int rate) {
            super(TAG, handler, sensorManager, rate);
            mSensor = null;
            for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor.getStringType().equals(name)) {
                    mSensor = sensor;
                    break;
                }
            }
            if (mSensor == null) {
                throw new IllegalStateException("cannot find sensor " + name);
            }
            mCallbacks = null;
        }

        /**
         * Set an object to call back to when the ambient color temperature changes.
         *
         * @param callbacks
         *      The object to call back to.
         *
         * @return Whether the method succeeded or not.
         */
        public boolean setCallbacks(Callbacks callbacks) {
            if (mCallbacks == callbacks) {
                return false;
            }
            mCallbacks = callbacks;
            return true;
        }

        /**
         * See {@link AmbientSensor#dump base class}.
         */
        @Override
        public void dump(PrintWriter writer) {
            super.dump(writer);
            writer.println("    mCallbacks=" + mCallbacks);
        }

        interface Callbacks {
            void onAmbientColorTemperatureChanged(float value);
        }

        @Override
        protected void update(float value) {
            if (mCallbacks != null) {
                mCallbacks.onAmbientColorTemperatureChanged(value);
            }
        }

    }

}
