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
import android.os.Trace;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Controls the screen brightness when dozing.
 */
public class DozeScreenBrightness implements DozeMachine.Part, SensorEventListener {
    private final Context mContext;
    private final DozeMachine.Service mDozeService;
    private final DozeHost mDozeHost;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final int[] mSensorToBrightness;
    private final int[] mSensorToScrimOpacity;

    private boolean mRegistered;
    private int mDefaultDozeBrightness;
    private boolean mPaused = false;
    private int mLastSensorValue = -1;

    public DozeScreenBrightness(Context context, DozeMachine.Service service,
            SensorManager sensorManager, Sensor lightSensor, DozeHost host,
            Handler handler, int defaultDozeBrightness, int[] sensorToBrightness,
            int[] sensorToScrimOpacity) {
        mContext = context;
        mDozeService = service;
        mSensorManager = sensorManager;
        mLightSensor = lightSensor;
        mDozeHost = host;
        mHandler = handler;

        mDefaultDozeBrightness = defaultDozeBrightness;
        mSensorToBrightness = sensorToBrightness;
        mSensorToScrimOpacity = sensorToScrimOpacity;
    }

    @VisibleForTesting
    public DozeScreenBrightness(Context context, DozeMachine.Service service,
            SensorManager sensorManager, Sensor lightSensor, DozeHost host,
            Handler handler, AlwaysOnDisplayPolicy policy) {
        this(context, service, sensorManager, lightSensor, host, handler,
                context.getResources().getInteger(
                        com.android.internal.R.integer.config_screenBrightnessDoze),
                policy.screenBrightnessArray, policy.dimmingScrimArray);
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
                setLightSensorEnabled(false);
                resetBrightnessToDefault();
                break;
            case FINISH:
                setLightSensorEnabled(false);
                break;
        }
        if (newState != DozeMachine.State.FINISH) {
            setPaused(newState == DozeMachine.State.DOZE_AOD_PAUSED);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Trace.beginSection("DozeScreenBrightness.onSensorChanged" + event.values[0]);
        try {
            if (mRegistered) {
                mLastSensorValue = (int) event.values[0];
                updateBrightnessAndReady();
            }
        } finally {
            Trace.endSection();
        }
    }

    private void updateBrightnessAndReady() {
        if (mRegistered) {
            int brightness = computeBrightness(mLastSensorValue);
            boolean brightnessReady = brightness > 0;
            if (brightnessReady) {
                mDozeService.setDozeScreenBrightness(brightness);
            }

            int scrimOpacity = -1;
            if (mPaused) {
                // If AOD is paused, force the screen black until the
                // sensor reports a new brightness. This ensures that when the screen comes on
                // again, it will only show after the brightness sensor has stabilized,
                // avoiding a potential flicker.
                scrimOpacity = 255;
            } else if (brightnessReady) {
                // Only unblank scrim once brightness is ready.
                scrimOpacity = computeScrimOpacity(mLastSensorValue);
            }
            if (scrimOpacity >= 0) {
                mDozeHost.setAodDimmingScrim(scrimOpacity / 255f);
            }
        }
    }

    private int computeScrimOpacity(int sensorValue) {
        if (sensorValue < 0 || sensorValue >= mSensorToScrimOpacity.length) {
            return -1;
        }
        return mSensorToScrimOpacity[sensorValue];
    }

    private int computeBrightness(int sensorValue) {
        if (sensorValue < 0 || sensorValue >= mSensorToBrightness.length) {
            return -1;
        }
        return mSensorToBrightness[sensorValue];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void resetBrightnessToDefault() {
        mDozeService.setDozeScreenBrightness(mDefaultDozeBrightness);
        mDozeHost.setAodDimmingScrim(0f);
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered && mLightSensor != null) {
            // Wait until we get an event from the sensor until indicating ready.
            mRegistered = mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            mLastSensorValue = -1;
        } else if (!enabled && mRegistered) {
            mSensorManager.unregisterListener(this);
            mRegistered = false;
            mLastSensorValue = -1;
            // Sensor is not enabled, hence we use the default brightness and are always ready.
        }
    }

    private void setPaused(boolean paused) {
        if (mPaused != paused) {
            mPaused = paused;
            updateBrightnessAndReady();
        }
    }

}
