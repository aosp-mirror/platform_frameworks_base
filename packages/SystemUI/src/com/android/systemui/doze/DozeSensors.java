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

import static com.android.systemui.plugins.SensorManagerPlugin.Sensor.TYPE_WAKE_DISPLAY;
import static com.android.systemui.plugins.SensorManagerPlugin.Sensor.TYPE_WAKE_LOCK_SCREEN;

import android.annotation.AnyThread;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.AsyncSensorManager;
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
    private final ContentResolver mResolver;
    private final TriggerSensor mPickupSensor;
    private final DozeParameters mDozeParameters;
    private final AmbientDisplayConfiguration mConfig;
    private final WakeLock mWakeLock;
    private final Consumer<Boolean> mProxCallback;
    private final Callback mCallback;
    @VisibleForTesting
    protected TriggerSensor[] mSensors;

    private final Handler mHandler = new Handler();
    private final ProxSensor mProxSensor;
    private long mDebounceFrom;
    private boolean mSettingRegistered;
    private boolean mListening;
    private boolean mPaused;

    public DozeSensors(Context context, AlarmManager alarmManager, SensorManager sensorManager,
            DozeParameters dozeParameters, AmbientDisplayConfiguration config, WakeLock wakeLock,
            Callback callback, Consumer<Boolean> proxCallback, AlwaysOnDisplayPolicy policy) {
        mContext = context;
        mAlarmManager = alarmManager;
        mSensorManager = sensorManager;
        mDozeParameters = dozeParameters;
        mConfig = config;
        mWakeLock = wakeLock;
        mProxCallback = proxCallback;
        mResolver = mContext.getContentResolver();

        boolean alwaysOn = mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT);
        mSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        dozeParameters.getPulseOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION, false /* touchCoords */,
                        false /* touchscreen */),
                mPickupSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PICK_UP_GESTURE,
                        true /* settingDef */,
                        config.dozePickupSensorAvailable(),
                        DozeLog.REASON_SENSOR_PICKUP, false /* touchCoords */,
                        false /* touchscreen */,
                        false /* ignoresSetting */,
                        mDozeParameters.getPickupPerformsProxCheck()),
                new TriggerSensor(
                        findSensorWithType(config.doubleTapSensorType()),
                        Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        dozeParameters.doubleTapReportsTouchCoordinates(),
                        true /* touchscreen */),
                new TriggerSensor(
                        findSensorWithType(config.tapSensorType()),
                        Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_TAP,
                        false /* reports touch coordinates */,
                        true /* touchscreen */),
                new TriggerSensor(
                        findSensorWithType(config.longPressSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                        false /* settingDef */,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_DISPLAY),
                        Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE,
                        mConfig.wakeScreenGestureAvailable() && alwaysOn,
                        DozeLog.REASON_SENSOR_WAKE_UP,
                        false /* reports touch coordinates */,
                        false /* touchscreen */),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_LOCK_SCREEN),
                        Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE,
                        mConfig.wakeScreenGestureAvailable(),
                        DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN,
                        false /* reports touch coordinates */,
                        false /* touchscreen */, mConfig.getWakeLockScreenDebounce()),
        };

        mProxSensor = new ProxSensor(policy);
        mCallback = callback;
    }

    /**
     * Temporarily disable some sensors to avoid turning on the device while the user is
     * turning it off.
     */
    public void requestTemporaryDisable() {
        mDebounceFrom = SystemClock.uptimeMillis();
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

    /**
     * If sensors should be registered and sending signals.
     */
    public void setListening(boolean listen) {
        if (mListening == listen) {
            return;
        }
        mListening = listen;
        updateListening();
    }

    /**
     * Unregister sensors, when listening, unless they are prox gated.
     * @see #setListening(boolean)
     */
    public void setPaused(boolean paused) {
        if (mPaused == paused) {
            return;
        }
        mPaused = paused;
        updateListening();
    }

    /**
     * Registers/unregisters sensors based on internal state.
     */
    public void updateListening() {
        boolean anyListening = false;
        for (TriggerSensor s : mSensors) {
            // We don't want to be listening while we're PAUSED (prox sensor is covered)
            // except when the sensor is already gated by prox.
            boolean listen = mListening && (!mPaused || s.performsProxCheck());
            s.setListening(listen);
            if (listen) {
                anyListening = true;
            }
        }

        if (!anyListening) {
            mResolver.unregisterContentObserver(mSettingsObserver);
        } else if (!mSettingRegistered) {
            for (TriggerSensor s : mSensors) {
                s.registerSettingsObserver(mSettingsObserver);
            }
        }
        mSettingRegistered = anyListening;
    }

    /** Set the listening state of only the sensors that require the touchscreen. */
    public void setTouchscreenSensorsListening(boolean listening) {
        for (TriggerSensor sensor : mSensors) {
            if (sensor.mRequiresTouchscreen) {
                sensor.setListening(listening);
            }
        }
    }

    public void onUserSwitched() {
        for (TriggerSensor s : mSensors) {
            s.updateListening();
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
                s.updateListening();
            }
        }
    };

    public void setDisableSensorsInterferingWithProximity(boolean disable) {
        mPickupSensor.setDisabled(disable);
    }

    /** Ignore the setting value of only the sensors that require the touchscreen. */
    public void ignoreTouchScreenSensorsSettingInterferingWithDocking(boolean ignore) {
        for (TriggerSensor sensor : mSensors) {
            if (sensor.mRequiresTouchscreen) {
                sensor.ignoreSetting(ignore);
            }
        }
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        for (TriggerSensor s : mSensors) {
            pw.print("  Sensor: "); pw.println(s.toString());
        }
        pw.print("  ProxSensor: "); pw.println(mProxSensor.toString());
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
        final Sensor mSensor;
        final boolean mUsingBrightnessSensor;

        public ProxSensor(AlwaysOnDisplayPolicy policy) {
            mPolicy = policy;
            mCooldownTimer = new AlarmTimeout(mAlarmManager, this::updateRegistered,
                    "prox_cooldown", mHandler);

            // The default prox sensor can be noisy, so let's use a prox gated brightness sensor
            // if available.
            Sensor sensor = DozeSensors.findSensorWithType(mSensorManager,
                    mContext.getString(R.string.doze_brightness_sensor_type));
            mUsingBrightnessSensor = sensor != null;
            if (sensor == null) {
                sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            }
            mSensor = sensor;
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
                mRegistered = mSensorManager.registerListener(this, mSensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            } else {
                mSensorManager.unregisterListener(this);
                mRegistered = false;
                mCurrentlyFar = null;
            }
        }

        @Override
        public void onSensorChanged(android.hardware.SensorEvent event) {
            if (DEBUG) Log.d(TAG, "onSensorChanged " + event);

            if (mUsingBrightnessSensor) {
                // The custom brightness sensor is gated by the proximity sensor and will return 0
                // whenever prox is covered.
                mCurrentlyFar = event.values[0] > 0;
            } else {
                mCurrentlyFar = event.values[0] >= event.sensor.getMaximumRange();
            }
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
            return String.format("{registered=%s, requested=%s, coolingDown=%s, currentlyFar=%s,"
                    + " sensor=%s}", mRegistered, mRequested, mCooldownTimer.isScheduled(),
                    mCurrentlyFar, mSensor);
        }
    }

    @VisibleForTesting
    class TriggerSensor extends TriggerEventListener {
        final Sensor mSensor;
        final boolean mConfigured;
        final int mPulseReason;
        private final String mSetting;
        private final boolean mReportsTouchCoordinates;
        private final boolean mSettingDefault;
        private final boolean mRequiresTouchscreen;
        private final boolean mSensorPerformsProxCheck;

        protected boolean mRequested;
        protected boolean mRegistered;
        protected boolean mDisabled;
        protected boolean mIgnoresSetting;

        public TriggerSensor(Sensor sensor, String setting, boolean configured, int pulseReason,
                boolean reportsTouchCoordinates, boolean requiresTouchscreen) {
            this(sensor, setting, true /* settingDef */, configured, pulseReason,
                    reportsTouchCoordinates, requiresTouchscreen);
        }

        public TriggerSensor(Sensor sensor, String setting, boolean settingDef,
                boolean configured, int pulseReason, boolean reportsTouchCoordinates,
                boolean requiresTouchscreen) {
            this(sensor, setting, settingDef, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen, false /* ignoresSetting */,
                    false /* sensorPerformsProxCheck */);
        }

        private TriggerSensor(Sensor sensor, String setting, boolean settingDef,
                boolean configured, int pulseReason, boolean reportsTouchCoordinates,
                boolean requiresTouchscreen, boolean ignoresSetting,
                boolean sensorPerformsProxCheck) {
            mSensor = sensor;
            mSetting = setting;
            mSettingDefault = settingDef;
            mConfigured = configured;
            mPulseReason = pulseReason;
            mReportsTouchCoordinates = reportsTouchCoordinates;
            mRequiresTouchscreen = requiresTouchscreen;
            mIgnoresSetting = ignoresSetting;
            mSensorPerformsProxCheck = sensorPerformsProxCheck;
        }

        public void setListening(boolean listen) {
            if (mRequested == listen) return;
            mRequested = listen;
            updateListening();
        }

        public void setDisabled(boolean disabled) {
            if (mDisabled == disabled) return;
            mDisabled = disabled;
            updateListening();
        }

        public void ignoreSetting(boolean ignored) {
            if (mIgnoresSetting == ignored) return;
            mIgnoresSetting = ignored;
            updateListening();
        }

        public void updateListening() {
            if (!mConfigured || mSensor == null) return;
            if (mRequested && !mDisabled && (enabledBySetting() || mIgnoresSetting)
                    && !mRegistered) {
                mRegistered = mSensorManager.requestTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
        }

        protected boolean enabledBySetting() {
            if (!mConfig.enabled(UserHandle.USER_CURRENT)) {
                return false;
            } else if (TextUtils.isEmpty(mSetting)) {
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
                    .append(", mIgnoresSetting=").append(mIgnoresSetting)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        @AnyThread
        public void onTrigger(TriggerEvent event) {
            DozeLog.traceSensor(mContext, mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                if (DEBUG) Log.d(TAG, "onTrigger: " + triggerEventToString(event));
                if (mSensor != null && mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(
                            mContext, MetricsProto.MetricsEvent.ACTION_AMBIENT_GESTURE,
                            subType);
                }

                mRegistered = false;
                float screenX = -1;
                float screenY = -1;
                if (mReportsTouchCoordinates && event.values.length >= 2) {
                    screenX = event.values[0];
                    screenY = event.values[1];
                }
                mCallback.onSensorPulse(mPulseReason, mSensorPerformsProxCheck, screenX, screenY,
                        event.values);
                if (!mRegistered) {
                    updateListening();  // reregister, this sensor only fires once
                }
            }));
        }

        /**
         * If the sensor itself performs proximity checks, to avoid pocket dialing.
         * Gated sensors don't need to be stopped when the {@link DozeMachine} is
         * {@link DozeMachine.State#DOZE_AOD_PAUSED}.
         */
        public boolean performsProxCheck() {
            return mSensorPerformsProxCheck;
        }

        public void registerSettingsObserver(ContentObserver settingsObserver) {
            if (mConfigured && !TextUtils.isEmpty(mSetting)) {
                mResolver.registerContentObserver(
                        Settings.Secure.getUriFor(mSetting), false /* descendants */,
                        mSettingsObserver, UserHandle.USER_ALL);
            }
        }

        protected String triggerEventToString(TriggerEvent event) {
            if (event == null) return null;
            final StringBuilder sb = new StringBuilder("SensorEvent[")
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

    /**
     * A Sensor that is injected via plugin.
     */
    @VisibleForTesting
    class PluginSensor extends TriggerSensor implements SensorManagerPlugin.SensorEventListener {

        final SensorManagerPlugin.Sensor mPluginSensor;
        private long mDebounce;

        PluginSensor(SensorManagerPlugin.Sensor sensor, String setting, boolean configured,
                int pulseReason, boolean reportsTouchCoordinates, boolean requiresTouchscreen) {
            this(sensor, setting, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen, 0L /* debounce */);
        }

        PluginSensor(SensorManagerPlugin.Sensor sensor, String setting, boolean configured,
                int pulseReason, boolean reportsTouchCoordinates, boolean requiresTouchscreen,
                long debounce) {
            super(null, setting, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen);
            mPluginSensor = sensor;
            mDebounce = debounce;
        }

        @Override
        public void updateListening() {
            if (!mConfigured) return;
            AsyncSensorManager asyncSensorManager = (AsyncSensorManager) mSensorManager;
            if (mRequested && !mDisabled && (enabledBySetting() || mIgnoresSetting)
                    && !mRegistered) {
                asyncSensorManager.registerPluginListener(mPluginSensor, this);
                mRegistered = true;
                if (DEBUG) Log.d(TAG, "registerPluginListener");
            } else if (mRegistered) {
                asyncSensorManager.unregisterPluginListener(mPluginSensor, this);
                mRegistered = false;
                if (DEBUG) Log.d(TAG, "unregisterPluginListener");
            }
        }

        @Override
        public String toString() {
            return new StringBuilder("{mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mIgnoresSetting=").append(mIgnoresSetting)
                    .append(", mSensor=").append(mPluginSensor).append("}").toString();
        }

        private String triggerEventToString(SensorManagerPlugin.SensorEvent event) {
            if (event == null) return null;
            final StringBuilder sb = new StringBuilder("PluginTriggerEvent[")
                    .append(event.getSensor()).append(',')
                    .append(event.getVendorType());
            if (event.getValues() != null) {
                for (int i = 0; i < event.getValues().length; i++) {
                    sb.append(',').append(event.getValues()[i]);
                }
            }
            return sb.append(']').toString();
        }

        @Override
        public void onSensorChanged(SensorManagerPlugin.SensorEvent event) {
            DozeLog.traceSensor(mContext, mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                final long now = SystemClock.uptimeMillis();
                if (now < mDebounceFrom + mDebounce) {
                    Log.d(TAG, "onSensorEvent dropped: " + triggerEventToString(event));
                    return;
                }
                if (DEBUG) Log.d(TAG, "onSensorEvent: " + triggerEventToString(event));
                mCallback.onSensorPulse(mPulseReason, true /* sensorPerformsProxCheck */, -1, -1,
                        event.getValues());
            }));
        }
    }

    public interface Callback {

        /**
         * Called when a sensor requests a pulse
         * @param pulseReason Requesting sensor, e.g. {@link DozeLog#REASON_SENSOR_PICKUP}
         * @param sensorPerformedProxCheck true if the sensor already checked for FAR proximity.
         * @param screenX the location on the screen where the sensor fired or -1
 *                if the sensor doesn't support reporting screen locations.
         * @param screenY the location on the screen where the sensor fired or -1
         * @param rawValues raw values array from the event.
         */
        void onSensorPulse(int pulseReason, boolean sensorPerformedProxCheck,
                float screenX, float screenY, float[] rawValues);
    }
}
