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

import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.view.Display;

import com.android.internal.R;
import com.android.systemui.doze.dagger.BrightnessSensor;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.settings.SystemSettings;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
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

    /**
     * Just before the screen times out from user inactivity, DisplayPowerController dims the screen
     * brightness to the lower of {@link #mScreenBrightnessDim}, or the current brightness minus
     * this amount.
     */
    private final float mScreenBrightnessMinimumDimAmountFloat;
    private final Context mContext;
    private final DozeMachine.Service mDozeService;
    private final DozeHost mDozeHost;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final DisplayManager mDisplayManager;
    private final Optional<Sensor>[] mLightSensorOptional; // light sensors to use per posture
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DozeParameters mDozeParameters;
    private final DevicePostureController mDevicePostureController;
    private final DozeLog mDozeLog;
    private final SystemSettings mSystemSettings;
    private final int[] mSensorToBrightness;
    @Nullable
    private final float[] mSensorToBrightnessFloat;
    private final int[] mSensorToScrimOpacity;
    private final int mScreenBrightnessDim;
    private final float mScreenBrightnessDimFloat;

    @DevicePostureController.DevicePostureInt
    private int mDevicePosture;
    private boolean mRegistered;
    private int mDefaultDozeBrightness;
    private float mDefaultDozeBrightnessFloat;
    private boolean mPaused = false;
    private boolean mScreenOff = false;
    private int mLastSensorValue = -1;
    private DozeMachine.State mState = DozeMachine.State.UNINITIALIZED;

    /**
     * Debug value used for emulating various display brightness buckets:
     *
     * {@code am broadcast -p com.android.systemui -a com.android.systemui.doze.AOD_BRIGHTNESS
     * --ei brightness_bucket 1}
     */
    private int mDebugBrightnessBucket = -1;

    @Inject
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public DozeScreenBrightness(
            Context context,
            @WrappedService DozeMachine.Service service,
            AsyncSensorManager sensorManager,
            @BrightnessSensor Optional<Sensor>[] lightSensorOptional,
            DozeHost host, Handler handler,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            WakefulnessLifecycle wakefulnessLifecycle,
            DozeParameters dozeParameters,
            DevicePostureController devicePostureController,
            DozeLog dozeLog,
            SystemSettings systemSettings,
            DisplayManager displayManager) {
        mContext = context;
        mDozeService = service;
        mSensorManager = sensorManager;
        mDisplayManager = displayManager;
        mLightSensorOptional = lightSensorOptional;
        mDevicePostureController = devicePostureController;
        mDevicePosture = mDevicePostureController.getDevicePosture();
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mDozeParameters = dozeParameters;
        mDozeHost = host;
        mHandler = handler;
        mDozeLog = dozeLog;
        mSystemSettings = systemSettings;

        mScreenBrightnessMinimumDimAmountFloat = context.getResources().getFloat(
                R.dimen.config_screenBrightnessMinimumDimAmountFloat);

        mDefaultDozeBrightness = alwaysOnDisplayPolicy.defaultDozeBrightness;
        mDefaultDozeBrightnessFloat =
                mDisplayManager.getDefaultDozeBrightness(mContext.getDisplayId());
        mScreenBrightnessDim = alwaysOnDisplayPolicy.dimBrightness;
        mScreenBrightnessDimFloat = alwaysOnDisplayPolicy.dimBrightnessFloat;
        mSensorToBrightness = alwaysOnDisplayPolicy.screenBrightnessArray;
        mSensorToBrightnessFloat =
                mDisplayManager.getDozeBrightnessSensorValueToBrightness(mContext.getDisplayId());
        mSensorToScrimOpacity = alwaysOnDisplayPolicy.dimmingScrimArray;

        mDevicePostureController.addCallback(mDevicePostureCallback);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        mState = newState;
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
            case DOZE_SUSPEND_TRIGGERS:
                setLightSensorEnabled(false);
                resetBrightnessToDefault();
                break;
            case DOZE_AOD_PAUSED:
                setLightSensorEnabled(false);
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
        mDevicePostureController.removeCallback(mDevicePostureCallback);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, "DozeScreenBrightness.onSensorChanged" + event.values[0]);
        }
        try {
            if (mRegistered) {
                mLastSensorValue = (int) event.values[0];
                updateBrightnessAndReady(false /* force */);
            }
        } finally {
            Trace.endSection();
        }
    }

    public void updateBrightnessAndReady(boolean force) {
        if (force || mRegistered || mDebugBrightnessBucket != -1) {
            int sensorValue = mDebugBrightnessBucket == -1
                    ? mLastSensorValue : mDebugBrightnessBucket;
            boolean brightnessReady;
            if (shouldUseFloatBrightness()) {
                float brightness = computeBrightnessFloat(sensorValue);
                brightnessReady = brightness >= 0;
                if (brightnessReady) {
                    mDozeService.setDozeScreenBrightnessFloat(
                            clampToDimBrightnessForScreenOffFloat(
                                    clampToUserSettingFloat(brightness)));
                }
            } else {
                int brightness = computeBrightness(sensorValue);
                brightnessReady = brightness > 0;
                if (brightnessReady) {
                    mDozeService.setDozeScreenBrightness(
                            clampToDimBrightnessForScreenOff(clampToUserSetting(brightness)));
                }
            }

            int scrimOpacity = -1;
            if (!isLightSensorPresent()) {
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

    private boolean lightSensorSupportsCurrentPosture() {
        return mLightSensorOptional != null
                && mDevicePosture < mLightSensorOptional.length;
    }

    private boolean isLightSensorPresent() {
        if (!lightSensorSupportsCurrentPosture()) {
            return mLightSensorOptional != null && mLightSensorOptional[0].isPresent();
        }

        return mLightSensorOptional[mDevicePosture].isPresent();
    }

    private Sensor getLightSensor() {
        if (!lightSensorSupportsCurrentPosture()) {
            return null;
        }

        return mLightSensorOptional[mDevicePosture].get();
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

    private float computeBrightnessFloat(int sensorValue) {
        if (sensorValue < 0 || sensorValue >= mSensorToBrightnessFloat.length) {
            return -1;
        }
        return mSensorToBrightnessFloat[sensorValue];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void resetBrightnessToDefault() {
        if (shouldUseFloatBrightness()) {
            mDozeService.setDozeScreenBrightnessFloat(
                    clampToDimBrightnessForScreenOffFloat(
                            clampToUserSettingOrAutoBrightnessFloat(mDefaultDozeBrightnessFloat)));
        } else {
            mDozeService.setDozeScreenBrightness(
                    clampToDimBrightnessForScreenOff(
                            clampToUserSettingOrAutoBrightness(mDefaultDozeBrightness)));
        }
        mDozeHost.setAodDimmingScrim(0f);
    }

    private int clampToUserSetting(int brightness) {
        int screenBrightnessModeSetting = mSystemSettings.getIntForUser(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        if (screenBrightnessModeSetting == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return brightness;
        }

        return Math.min(brightness, getScreenBrightness());
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private float clampToUserSettingFloat(float brightness) {
        int screenBrightnessModeSetting = mSystemSettings.getIntForUser(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        if (screenBrightnessModeSetting == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return brightness;
        }

        return Math.min(brightness, getScreenBrightnessFloat());
    }

    private int clampToUserSettingOrAutoBrightness(int brightness) {
        return Math.min(brightness, getScreenBrightness());
    }

    private float clampToUserSettingOrAutoBrightnessFloat(float brightness) {
        return Math.min(brightness, getScreenBrightnessFloat());
    }

    /**
     * Gets the current screen brightness that may have been set by manually by the user
     * or by autobrightness.
     */
    private int getScreenBrightness() {
        return mSystemSettings.getIntForUser(
                Settings.System.SCREEN_BRIGHTNESS, Integer.MAX_VALUE,
                UserHandle.USER_CURRENT);
    }

    /**
     * Gets the current screen brightness that may have been set by manually by the user
     * or by autobrightness.
     */
    private float getScreenBrightnessFloat() {
        return mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
    }

    /**
     * Clamp the brightness to the dim brightness value used by PowerManagerService just before the
     * device times out and goes to sleep, if we are sleeping from a timeout. This ensures that we
     * don't raise the brightness back to the user setting before or during the screen off
     * animation.
     */
    private int clampToDimBrightnessForScreenOff(int brightness) {
        final boolean screenTurningOff =
                (mDozeParameters.shouldClampToDimBrightness()
                        || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP)
                && mState == DozeMachine.State.INITIALIZED;
        if (screenTurningOff
                && mWakefulnessLifecycle.getLastSleepReason() == GO_TO_SLEEP_REASON_TIMEOUT) {
            return Math.max(
                    PowerManager.BRIGHTNESS_OFF,
                    // Use the lower of either the dim brightness, or the current brightness reduced
                    // by the minimum dim amount. This is the same logic used in
                    // DisplayPowerController#updatePowerState to apply a minimum dim amount.
                    Math.min(
                            brightness - (int) Math.floor(
                                    mScreenBrightnessMinimumDimAmountFloat * 255),
                            mScreenBrightnessDim));
        } else {
            return brightness;
        }
    }

    /**
     * Clamp the brightness to the dim brightness value used by PowerManagerService just before the
     * device times out and goes to sleep, if we are sleeping from a timeout. This ensures that we
     * don't raise the brightness back to the user setting before or during the screen off
     * animation.
     */
    private float clampToDimBrightnessForScreenOffFloat(float brightness) {
        final boolean screenTurningOff =
                (mDozeParameters.shouldClampToDimBrightness()
                        || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP)
                && mState == DozeMachine.State.INITIALIZED;
        if (screenTurningOff
                && mWakefulnessLifecycle.getLastSleepReason() == GO_TO_SLEEP_REASON_TIMEOUT) {
            return Math.max(
                    PowerManager.BRIGHTNESS_MIN,
                    // Use the lower of either the dim brightness, or the current brightness reduced
                    // by the minimum dim amount. This is the same logic used in
                    // DisplayPowerController#updatePowerState to apply a minimum dim amount.
                    Math.min(brightness - mScreenBrightnessMinimumDimAmountFloat,
                            mScreenBrightnessDimFloat));
        } else {
            return brightness;
        }
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled && !mRegistered && isLightSensorPresent()) {
            // Wait until we get an event from the sensor until indicating ready.
            mRegistered = mSensorManager.registerListener(this, getLightSensor(),
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
        pw.println("DozeScreenBrightness:");
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        idpw.println("registered=" + mRegistered);
        idpw.println("posture=" + DevicePostureController.devicePostureToString(mDevicePosture));
        idpw.println("sensorToBrightness=" + Arrays.toString(mSensorToBrightness));
        idpw.println("sensorToBrightnessFloat=" + Arrays.toString(mSensorToBrightnessFloat));
        idpw.println("sensorToScrimOpacity=" + Arrays.toString(mSensorToScrimOpacity));
        idpw.println("screenBrightnessDim=" + mScreenBrightnessDim);
        idpw.println("screenBrightnessDimFloat=" + mScreenBrightnessDimFloat);
        idpw.println("mDefaultDozeBrightness=" + mDefaultDozeBrightness);
        idpw.println("mDefaultDozeBrightnessFloat=" + mDefaultDozeBrightnessFloat);
        idpw.println("mLastSensorValue=" + mLastSensorValue);
        idpw.println("shouldUseFloatBrightness()=" + shouldUseFloatBrightness());
    }

    private boolean shouldUseFloatBrightness() {
        return com.android.server.display.feature.flags.Flags.dozeBrightnessFloat()
                && mSensorToBrightnessFloat != null;
    }

    private final DevicePostureController.Callback mDevicePostureCallback =
            new DevicePostureController.Callback() {
        @Override
        public void onPostureChanged(int posture) {
            if (mDevicePosture == posture
                    || mLightSensorOptional.length < 2
                    || posture >= mLightSensorOptional.length) {
                return;
            }

            final Sensor oldSensor = mLightSensorOptional[mDevicePosture].get();
            final Sensor newSensor = mLightSensorOptional[posture].get();
            if (Objects.equals(oldSensor, newSensor)) {
                mDevicePosture = posture;
                // uses the same sensor for the new posture
                return;
            }

            // cancel the previous sensor:
            if (mRegistered) {
                setLightSensorEnabled(false);
                mDevicePosture = posture;
                setLightSensorEnabled(true);
            } else {
                mDevicePosture = posture;
            }
            mDozeLog.tracePostureChanged(mDevicePosture, "DozeScreenBrightness swap "
                    + "{" + oldSensor + "} => {" + newSensor + "}, mRegistered=" + mRegistered);
        }
    };
}
