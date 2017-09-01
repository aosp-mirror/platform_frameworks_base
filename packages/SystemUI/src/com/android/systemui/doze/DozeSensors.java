/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.AnyThread;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.List;
import java.util.function.Consumer;

public class DozeSensors {

    private static final boolean DEBUG = DozeService.DEBUG;

    private static final String TAG = "DozeSensors";

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final SensorManager mSensorManager;
    private final TriggerSensor[] mSensors;
    private final ContentResolver mResolver;
    private final TriggerSensor mPickupSensor;
    private final DozeParameters mDozeParameters;
    private final AmbientDisplayConfiguration mConfig;
    private final WakeLock mWakeLock;
    private final Consumer<Boolean> mProxCallback;
    private final Callback mCallback;

    private final Handler mHandler = new Handler();
    private final ProxSensor mProxSensor;


    public DozeSensors(Context context, AlarmManager alarmManager, SensorManager sensorManager,
            DozeParameters dozeParameters,
            AmbientDisplayConfiguration config, WakeLock wakeLock, Callback callback,
            Consumer<Boolean> proxCallback, AlwaysOnDisplayPolicy policy) {
        mContext = context;
        mAlarmManager = alarmManager;
        mSensorManager = sensorManager;
        mDozeParameters = dozeParameters;
        mConfig = config;
        mWakeLock = wakeLock;
        mProxCallback = proxCallback;
        mResolver = mContext.getContentResolver();

        mSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        dozeParameters.getPulseOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION, false /* touchCoords */,
                        false /* touchscreen */),
                mPickupSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                        config.pulseOnPickupAvailable(),
                        DozeLog.PULSE_REASON_SENSOR_PICKUP, false /* touchCoords */,
                        false /* touchscreen */),
                new TriggerSensor(
                        findSensorWithType(config.doubleTapSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_DOUBLE_TAP,
                        dozeParameters.doubleTapReportsTouchCoordinates(),
                        true /* touchscreen */),
                new TriggerSensor(
                        findSensorWithType(config.longPressSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                        false /* settingDef */,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */),
        };

        mProxSensor = new ProxSensor(policy);
        mCallback = callback;
    }

    private Sensor findSensorWithType(String type) {
        return findSensorWithType(mSensorManager, type);
    }

    static Sensor findSensorWithType(SensorManager sensorManager, String type) {
        if (TextUtils.isEmpty(type)) {
            return null;
        }
        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensorList) {
            if (type.equals(s.getStringType())) {
                return s;
            }
        }
        return null;
    }

    public void setListening(boolean listen) {
        for (TriggerSensor s : mSensors) {
            s.setListening(listen);
            if (listen) {
                s.registerSettingsObserver(mSettingsObserver);
            }
        }
        if (!listen) {
            mResolver.unregisterContentObserver(mSettingsObserver);
        }
    }

    /** Set the listening state of only the sensors that require the touchscreen. */
    public void setTouchscreenSensorsListening(boolean listening) {
        for (TriggerSensor sensor : mSensors) {
            if (sensor.mRequiresTouchscreen) {
                sensor.setListening(listening);
            }
        }
    }

    public void reregisterAllSensors() {
        for (TriggerSensor s : mSensors) {
            s.setListening(false);
        }
        for (TriggerSensor s : mSensors) {
            s.setListening(true);
        }
    }

    public void onUserSwitched() {
        for (TriggerSensor s : mSensors) {
            s.updateListener();
        }
    }

    public void setProxListening(boolean listen) {
        mProxSensor.setRequested(listen);
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId != ActivityManager.getCurrentUser()) {
                return;
            }
            for (TriggerSensor s : mSensors) {
                s.updateListener();
            }
        }
    };

    public void setDisableSensorsInterferingWithProximity(boolean disable) {
        mPickupSensor.setDisabled(disable);
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        for (TriggerSensor s : mSensors) {
            pw.print("Sensor: "); pw.println(s.toString());
        }
        pw.print("ProxSensor: "); pw.println(mProxSensor.toString());
    }

    /**
     * @return true if prox is currently far, false if near or null if unknown.
     */
    public Boolean isProximityCurrentlyFar() {
        return mProxSensor.mCurrentlyFar;
    }

    private class ProxSensor implements SensorEventListener {

        boolean mRequested;
        boolean mRegistered;
        Boolean mCurrentlyFar;
        long mLastNear;
        final AlarmTimeout mCooldownTimer;
        final AlwaysOnDisplayPolicy mPolicy;


        public ProxSensor(AlwaysOnDisplayPolicy policy) {
            mPolicy = policy;
            mCooldownTimer = new AlarmTimeout(mAlarmManager, this::updateRegistered,
                    "prox_cooldown", mHandler);
        }

        void setRequested(boolean requested) {
            if (mRequested == requested) {
                // Send an update even if we don't re-register.
                mHandler.post(() -> {
                    if (mCurrentlyFar != null) {
                        mProxCallback.accept(mCurrentlyFar);
                    }
                });
                return;
            }
            mRequested = requested;
            updateRegistered();
        }

        private void updateRegistered() {
            setRegistered(mRequested && !mCooldownTimer.isScheduled());
        }

        private void setRegistered(boolean register) {
            if (mRegistered == register) {
                return;
            }
            if (register) {
                mRegistered = mSensorManager.registerListener(this,
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY),
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            } else {
                mSensorManager.unregisterListener(this);
                mRegistered = false;
                mCurrentlyFar = null;
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            mCurrentlyFar = event.values[0] >= event.sensor.getMaximumRange();
            mProxCallback.accept(mCurrentlyFar);

            long now = SystemClock.elapsedRealtime();
            if (mCurrentlyFar == null) {
                // Sensor has been unregistered by the proxCallback. Do nothing.
            } else if (!mCurrentlyFar) {
                mLastNear = now;
            } else if (mCurrentlyFar && now - mLastNear < mPolicy.proxCooldownTriggerMs) {
                // If the last near was very recent, we might be using more power for prox
                // wakeups than we're saving from turning of the screen. Instead, turn it off
                // for a while.
                mCooldownTimer.schedule(mPolicy.proxCooldownPeriodMs,
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
                updateRegistered();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public String toString() {
            return String.format("{registered=%s, requested=%s, coolingDown=%s, currentlyFar=%s}",
                    mRegistered, mRequested, mCooldownTimer.isScheduled(), mCurrentlyFar);
        }
    }

    private class TriggerSensor extends TriggerEventListener {
        final Sensor mSensor;
        final boolean mConfigured;
        final int mPulseReason;
        final String mSetting;
        final boolean mReportsTouchCoordinates;
        final boolean mSettingDefault;
        final boolean mRequiresTouchscreen;

        private boolean mRequested;
        private boolean mRegistered;
        private boolean mDisabled;

        public TriggerSensor(Sensor sensor, String setting, boolean configured, int pulseReason,
                boolean reportsTouchCoordinates, boolean requiresTouchscreen) {
            this(sensor, setting, true /* settingDef */, configured, pulseReason,
                    reportsTouchCoordinates, requiresTouchscreen);
        }

        public TriggerSensor(Sensor sensor, String setting, boolean settingDef,
                boolean configured, int pulseReason, boolean reportsTouchCoordinates,
                boolean requiresTouchscreen) {
            mSensor = sensor;
            mSetting = setting;
            mSettingDefault = settingDef;
            mConfigured = configured;
            mPulseReason = pulseReason;
            mReportsTouchCoordinates = reportsTouchCoordinates;
            mRequiresTouchscreen = requiresTouchscreen;
        }

        public void setListening(boolean listen) {
            if (mRequested == listen) return;
            mRequested = listen;
            updateListener();
        }

        public void setDisabled(boolean disabled) {
            if (mDisabled == disabled) return;
            mDisabled = disabled;
            updateListener();
        }

        public void updateListener() {
            if (!mConfigured || mSensor == null) return;
            if (mRequested && !mDisabled && enabledBySetting() && !mRegistered) {
                mRegistered = mSensorManager.requestTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
        }

        private boolean enabledBySetting() {
            if (TextUtils.isEmpty(mSetting)) {
                return true;
            }
            return Settings.Secure.getIntForUser(mResolver, mSetting, mSettingDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0;
        }

        @Override
        public String toString() {
            return new StringBuilder("{mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        @AnyThread
        public void onTrigger(TriggerEvent event) {
            DozeLog.traceSensor(mContext, mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                if (DEBUG) Log.d(TAG, "onTrigger: " + triggerEventToString(event));
                boolean sensorPerformsProxCheck = false;
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(
                            mContext, MetricsProto.MetricsEvent.ACTION_AMBIENT_GESTURE,
                            subType);
                    sensorPerformsProxCheck =
                            mDozeParameters.getPickupSubtypePerformsProxCheck(subType);
                }

                mRegistered = false;
                float screenX = -1;
                float screenY = -1;
                if (mReportsTouchCoordinates && event.values.length >= 2) {
                    screenX = event.values[0];
                    screenY = event.values[1];
                }
                mCallback.onSensorPulse(mPulseReason, sensorPerformsProxCheck, screenX, screenY);
                updateListener();  // reregister, this sensor only fires once
            }));
        }

        public void registerSettingsObserver(ContentObserver settingsObserver) {
            if (mConfigured && !TextUtils.isEmpty(mSetting)) {
                mResolver.registerContentObserver(
                        Settings.Secure.getUriFor(mSetting), false /* descendants */,
                        mSettingsObserver, UserHandle.USER_ALL);
            }
        }

        private String triggerEventToString(TriggerEvent event) {
            if (event == null) return null;
            final StringBuilder sb = new StringBuilder("TriggerEvent[")
                    .append(event.timestamp).append(',')
                    .append(event.sensor.getName());
            if (event.values != null) {
                for (int i = 0; i < event.values.length; i++) {
                    sb.append(',').append(event.values[i]);
                }
            }
            return sb.append(']').toString();
        }
    }

    public interface Callback {

        /**
         * Called when a sensor requests a pulse
         * @param pulseReason Requesting sensor, e.g. {@link DozeLog#PULSE_REASON_SENSOR_PICKUP}
         * @param sensorPerformedProxCheck true if the sensor already checked for FAR proximity.
         * @param screenX the location on the screen where the sensor fired or -1
         *                if the sensor doesn't support reporting screen locations.
         * @param screenY the location on the screen where the sensor fired or -1
         *                if the sensor doesn't support reporting screen locations.
         */
        void onSensorPulse(int pulseReason, boolean sensorPerformedProxCheck,
                float screenX, float screenY);
    }
}
