/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;

import java.io.PrintWriter;

/**
 * Controls the light sensor when the screen is off. The sensor used here does not report lux values
 * but an index that needs to be mapped to a lux value.
 */
public class ScreenOffBrightnessSensorController implements SensorEventListener {
    private static final String TAG = "ScreenOffBrightnessSensorController";

    private static final int SENSOR_INVALID_VALUE = -1;
    private static final long SENSOR_VALUE_VALID_TIME_MILLIS = 1500;

    private final Handler mHandler;
    private final Clock mClock;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final int[] mSensorValueToLux;

    private boolean mRegistered;
    private int mLastSensorValue = SENSOR_INVALID_VALUE;
    private long mSensorDisableTime = -1;

    // The mapper to translate ambient lux to screen brightness in the range [0, 1.0].
    @Nullable
    private final BrightnessMappingStrategy mBrightnessMapper;

    public ScreenOffBrightnessSensorController(
            SensorManager sensorManager,
            Sensor lightSensor,
            Handler handler,
            Clock clock,
            int[] sensorValueToLux,
            BrightnessMappingStrategy brightnessMapper) {
        mSensorManager = sensorManager;
        mLightSensor = lightSensor;
        mHandler = handler;
        mClock = clock;
        mSensorValueToLux = sensorValueToLux;
        mBrightnessMapper = brightnessMapper;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mRegistered) {
            mLastSensorValue = (int) event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * Changes the state of the associated light sensor
     */
    public void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered) {
            // Wait until we get an event from the sensor indicating ready.
            mRegistered = mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            mLastSensorValue = SENSOR_INVALID_VALUE;
        } else if (!enabled && mRegistered) {
            mSensorManager.unregisterListener(this);
            mRegistered = false;
            mSensorDisableTime = mClock.uptimeMillis();
        }
    }

    /**
     * Stops the associated sensor, and cleans up the state
     */
    public void stop() {
        setLightSensorEnabled(false);
    }

    /**
     * Gets the automatic screen brightness based on the ambient lux
     */
    public float getAutomaticScreenBrightness() {
        if (mLastSensorValue < 0 || mLastSensorValue >= mSensorValueToLux.length
                || (!mRegistered
                && mClock.uptimeMillis() - mSensorDisableTime > SENSOR_VALUE_VALID_TIME_MILLIS)) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }

        int lux = mSensorValueToLux[mLastSensorValue];
        if (lux < 0) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }

        return mBrightnessMapper.getBrightness(lux);
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        pw.println("Screen Off Brightness Sensor Controller:");
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        idpw.println("registered=" + mRegistered);
        idpw.println("lastSensorValue=" + mLastSensorValue);
    }

    /** Functional interface for providing time. */
    public interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }
}
