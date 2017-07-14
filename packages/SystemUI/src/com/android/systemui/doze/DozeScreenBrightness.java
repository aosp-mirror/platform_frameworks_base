/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

/**
 * Controls the screen brightness when dozing.
 */
public class DozeScreenBrightness implements DozeMachine.Part, SensorEventListener {
    private final Context mContext;
    private final DozeMachine.Service mDozeService;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private boolean mRegistered;

    private final int mHighBrightness;
    private final int mLowBrightness;

    public DozeScreenBrightness(Context context, DozeMachine.Service service,
            SensorManager sensorManager, Sensor lightSensor, Handler handler) {
        mContext = context;
        mDozeService = service;
        mSensorManager = sensorManager;
        mLightSensor = lightSensor;
        mHandler = handler;

        mLowBrightness = context.getResources().getInteger(
                R.integer.config_doze_aod_brightness_low);
        mHighBrightness = context.getResources().getInteger(
                R.integer.config_doze_aod_brightness_high);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                resetBrightnessToDefault();
                break;
            case DOZE_AOD:
            case DOZE_REQUEST_PULSE:
                setLightSensorEnabled(true);
                break;
            case DOZE:
            case DOZE_AOD_PAUSED:
                setLightSensorEnabled(false);
                resetBrightnessToDefault();
                break;
            case FINISH:
                setLightSensorEnabled(false);
                break;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mRegistered) {
            mDozeService.setDozeScreenBrightness(computeBrightness((int) event.values[0]));
        }
    }

    private int computeBrightness(int sensorValue) {
        // The sensor reports 0 for off, 1 for low brightness and 2 for high brightness.
        // We currently use DozeScreenState for screen off, so we treat off as low brightness.
        if (sensorValue >= 2) {
            return mHighBrightness;
        } else {
            return mLowBrightness;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void resetBrightnessToDefault() {
        mDozeService.setDozeScreenBrightness(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze));
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered && mLightSensor != null) {
            mRegistered = mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else if (!enabled && mRegistered) {
            mSensorManager.unregisterListener(this);
            mRegistered = false;
        }
    }
}
