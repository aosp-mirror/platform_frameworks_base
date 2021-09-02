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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.dagger.BrightnessSensor;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.sensors.AsyncSensorManager;

import java.io.PrintWriter;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Controls the screen brightness when dozing.
 */
@DozeScope
public class DozeScreenBrightness extends BroadcastReceiver implements DozeMachine.Part,
        SensorEventListener {
    private static final boolean DEBUG_AOD_BRIGHTNESS = SystemProperties
            .getBoolean("debug.aod_brightness", false);
    protected static final String ACTION_AOD_BRIGHTNESS =
            "com.android.systemui.doze.AOD_BRIGHTNESS";
    protected static final String BRIGHTNESS_BUCKET = "brightness_bucket";

    private final Context mContext;
    private final DozeMachine.Service mDozeService;
    private final DozeHost mDozeHost;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final Optional<Sensor> mLightSensorOptional;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DozeParameters mDozeParameters;
    private final DockManager mDockManager;
    private final int[] mSensorToBrightness;
    private final int[] mSensorToScrimOpacity;
    private final int mScreenBrightnessDim;

    private boolean mRegistered;
    private int mDefaultDozeBrightness;
    private boolean mPaused = false;
    private boolean mScreenOff = false;
    private int mLastSensorValue = -1;

    /**
     * Debug value used for emulating various display brightness buckets:
     *
     * {@code am broadcast -p com.android.systemui -a com.android.systemui.doze.AOD_BRIGHTNESS
     * --ei brightness_bucket 1}
     */
    private int mDebugBrightnessBucket = -1;

    @Inject
    public DozeScreenBrightness(Context context, @WrappedService DozeMachine.Service service,
            AsyncSensorManager sensorManager,
            @BrightnessSensor Optional<Sensor> lightSensorOptional, DozeHost host, Handler handler,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            WakefulnessLifecycle wakefulnessLifecycle,
            DozeParameters dozeParameters,
            DockManager dockManager) {
        mContext = context;
        mDozeService = service;
        mSensorManager = sensorManager;
        mLightSensorOptional = lightSensorOptional;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mDozeParameters = dozeParameters;
        mDozeHost = host;
        mHandler = handler;
        mDockManager = dockManager;

        mDefaultDozeBrightness = alwaysOnDisplayPolicy.defaultDozeBrightness;
        mScreenBrightnessDim = alwaysOnDisplayPolicy.dimBrightness;
        mSensorToBrightness = alwaysOnDisplayPolicy.screenBrightnessArray;
        mSensorToScrimOpacity = alwaysOnDisplayPolicy.dimmingScrimArray;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                resetBrightnessToDefault();
                break;
            case DOZE_AOD:
            case DOZE_REQUEST_PULSE:
            case DOZE_AOD_DOCKED:
                setLightSensorEnabled(true);
                break;
            case DOZE:
                setLightSensorEnabled(false);
                resetBrightnessToDefault();
                break;
            case FINISH:
                onDestroy();
                break;
        }
        if (newState != DozeMachine.State.FINISH) {
            setScreenOff(newState == DozeMachine.State.DOZE);
            setPaused(newState == DozeMachine.State.DOZE_AOD_PAUSED);
        }
    }

    private void onDestroy() {
        setLightSensorEnabled(false);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Trace.beginSection("DozeScreenBrightness.onSensorChanged" + event.values[0]);
        try {
            if (mRegistered) {
                mLastSensorValue = (int) event.values[0];
                updateBrightnessAndReady(false /* force */);
            }
        } finally {
            Trace.endSection();
        }
    }

    private void updateBrightnessAndReady(boolean force) {
        if (force || mRegistered || mDebugBrightnessBucket != -1) {
            int sensorValue = mDebugBrightnessBucket == -1
                    ? mLastSensorValue : mDebugBrightnessBucket;
            int brightness = computeBrightness(sensorValue);
            boolean brightnessReady = brightness > 0;
            if (brightnessReady) {
                mDozeService.setDozeScreenBrightness(clampToUserSetting(brightness));
            }

            int scrimOpacity = -1;
            if (!mLightSensorOptional.isPresent()) {
                // No light sensor, scrims are always transparent.
                scrimOpacity = 0;
            } else if (brightnessReady) {
                // Only unblank scrim once brightness is ready.
                scrimOpacity = computeScrimOpacity(sensorValue);
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
        mDozeService.setDozeScreenBrightness(
                clampToDimBrightnessForScreenOff(
                        clampToUserSetting(mDefaultDozeBrightness)));
        mDozeHost.setAodDimmingScrim(0f);
    }
    //TODO: brightnessfloat change usages to float.
    private int clampToUserSetting(int brightness) {
        int userSetting = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, Integer.MAX_VALUE,
                UserHandle.USER_CURRENT);
        return Math.min(brightness, userSetting);
    }

    /**
     * Clamp the brightness to the dim brightness value used by PowerManagerService just before the
     * device times out and goes to sleep, if we are sleeping from a timeout. This ensures that we
     * don't raise the brightness back to the user setting before playing the screen off animation.
     */
    private int clampToDimBrightnessForScreenOff(int brightness) {
        if (mDozeParameters.shouldControlUnlockedScreenOff()
                && mWakefulnessLifecycle.getLastSleepReason()
                == PowerManager.GO_TO_SLEEP_REASON_TIMEOUT) {
            return Math.min(mScreenBrightnessDim, brightness);
        } else {
            return brightness;
        }
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered && mLightSensorOptional.isPresent()) {
            // Wait until we get an event from the sensor until indicating ready.
            mRegistered = mSensorManager.registerListener(this, mLightSensorOptional.get(),
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
            updateBrightnessAndReady(false /* force */);
        }
    }

    private void setScreenOff(boolean screenOff) {
        if (mScreenOff != screenOff) {
            mScreenOff = screenOff;
            updateBrightnessAndReady(true /* force */);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        mDebugBrightnessBucket = intent.getIntExtra(BRIGHTNESS_BUCKET, -1);
        updateBrightnessAndReady(false /* force */);
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        pw.println("DozeScreenBrightnessSensorRegistered=" + mRegistered);
    }
}
