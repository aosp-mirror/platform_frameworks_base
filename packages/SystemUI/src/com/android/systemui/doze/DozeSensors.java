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
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
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
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.Display;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public class DozeSensors {

    private static final boolean DEBUG = DozeService.DEBUG;
    private static final String TAG = "DozeSensors";
    private static final UiEventLogger UI_EVENT_LOGGER = new UiEventLoggerImpl();

    private final Context mContext;
    private final AsyncSensorManager mSensorManager;
    private final AmbientDisplayConfiguration mConfig;
    private final WakeLock mWakeLock;
    private final Consumer<Boolean> mProxCallback;
    private final SecureSettings mSecureSettings;
    private final Callback mCallback;
    @VisibleForTesting
    protected TriggerSensor[] mSensors;

    private final Handler mHandler = new Handler();
    private final ProximitySensor mProximitySensor;
    private long mDebounceFrom;
    private boolean mSettingRegistered;
    private boolean mListening;
    private boolean mListeningTouchScreenSensors;

    @VisibleForTesting
    public enum DozeSensorsUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "User performs pickup gesture that activates the ambient display")
        ACTION_AMBIENT_GESTURE_PICKUP(459);

        private final int mId;

        DozeSensorsUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    DozeSensors(Context context, AsyncSensorManager sensorManager,
            DozeParameters dozeParameters, AmbientDisplayConfiguration config, WakeLock wakeLock,
            Callback callback, Consumer<Boolean> proxCallback, DozeLog dozeLog,
            ProximitySensor proximitySensor, SecureSettings secureSettings,
            AuthController authController) {
        mContext = context;
        mSensorManager = sensorManager;
        mConfig = config;
        mWakeLock = wakeLock;
        mProxCallback = proxCallback;
        mSecureSettings = secureSettings;
        mCallback = callback;
        mProximitySensor = proximitySensor;

        boolean udfpsEnrolled =
                authController.isUdfpsEnrolled(KeyguardUpdateMonitor.getCurrentUser());
        boolean alwaysOn = mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT);
        mSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        dozeParameters.getPulseOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION, false /* touchCoords */,
                        false /* touchscreen */, dozeLog),
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PICK_UP_GESTURE,
                        true /* settingDef */,
                        config.dozePickupSensorAvailable(),
                        DozeLog.REASON_SENSOR_PICKUP, false /* touchCoords */,
                        false /* touchscreen */,
                        false /* ignoresSetting */,
                        dozeLog),
                new TriggerSensor(
                        findSensorWithType(config.doubleTapSensorType()),
                        Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        dozeParameters.doubleTapReportsTouchCoordinates(),
                        true /* touchscreen */,
                        dozeLog),
                new TriggerSensor(
                        findSensorWithType(config.tapSensorType()),
                        Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_TAP,
                        false /* reports touch coordinates */,
                        true /* touchscreen */,
                        dozeLog),
                new TriggerSensor(
                        findSensorWithType(config.longPressSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                        false /* settingDef */,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */,
                        dozeLog),
                new TriggerSensor(
                        findSensorWithType(config.udfpsLongPressSensorType()),
                        "doze_pulse_on_auth",
                        true /* settingDef */,
                        udfpsEnrolled,
                        DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */,
                        dozeLog),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_DISPLAY),
                        Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE,
                        mConfig.wakeScreenGestureAvailable() && alwaysOn,
                        DozeLog.REASON_SENSOR_WAKE_UP,
                        false /* reports touch coordinates */,
                        false /* touchscreen */,
                        dozeLog),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_LOCK_SCREEN),
                        Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE,
                        mConfig.wakeScreenGestureAvailable(),
                        DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN,
                        false /* reports touch coordinates */,
                        false /* touchscreen */,
                        mConfig.getWakeLockScreenDebounce(),
                        dozeLog),
                new TriggerSensor(
                        findSensorWithType(config.quickPickupSensorType()),
                        Settings.Secure.DOZE_QUICK_PICKUP_GESTURE,
                        true /* setting default */,
                        config.quickPickupSensorEnabled(KeyguardUpdateMonitor.getCurrentUser())
                                && udfpsEnrolled,
                        DozeLog.REASON_SENSOR_QUICK_PICKUP,
                        false /* touchCoords */,
                        false /* touchscreen */, dozeLog),
        };

        setProxListening(false);  // Don't immediately start listening when we register.
        mProximitySensor.register(
                proximityEvent -> {
                    if (proximityEvent != null) {
                        mProxCallback.accept(!proximityEvent.getBelow());
                    }
                });
    }

    /**
     *  Unregister any sensors.
     */
    public void destroy() {
        // Unregisters everything, which is enough to allow gc.
        for (TriggerSensor triggerSensor : mSensors) {
            triggerSensor.setListening(false);
        }
        mProximitySensor.pause();
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

    /**
     * Utility method to find a {@link Sensor} for the supplied string type.
     */
    public static Sensor findSensorWithType(SensorManager sensorManager, String type) {
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
    public void setListening(boolean listen, boolean includeTouchScreenSensors) {
        if (mListening == listen && mListeningTouchScreenSensors == includeTouchScreenSensors) {
            return;
        }
        mListening = listen;
        mListeningTouchScreenSensors = includeTouchScreenSensors;
        updateListening();
    }

    /**
     * Registers/unregisters sensors based on internal state.
     */
    private void updateListening() {
        boolean anyListening = false;
        for (TriggerSensor s : mSensors) {
            boolean listen = mListening
                    && (!s.mRequiresTouchscreen || mListeningTouchScreenSensors);
            s.setListening(listen);
            if (listen) {
                anyListening = true;
            }
        }

        if (!anyListening) {
            mSecureSettings.unregisterContentObserver(mSettingsObserver);
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

    void onScreenState(int state) {
        mProximitySensor.setSecondarySafe(
                state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND
                || state == Display.STATE_OFF);
    }

    public void setProxListening(boolean listen) {
        if (mProximitySensor.isRegistered() && listen) {
            mProximitySensor.alertListeners();
        } else {
            if (listen) {
                mProximitySensor.resume();
            } else {
                mProximitySensor.pause();
            }
        }
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
            if (userId != ActivityManager.getCurrentUser()) {
                return;
            }
            for (TriggerSensor s : mSensors) {
                s.updateListening();
            }
        }
    };

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
        pw.println("mListening=" + mListening);
        pw.println("mListeningTouchScreenSensors=" + mListeningTouchScreenSensors);
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        for (TriggerSensor s : mSensors) {
            idpw.println("Sensor: " + s.toString());
        }
        idpw.println("ProxSensor: " + mProximitySensor.toString());
    }

    /**
     * @return true if prox is currently near, false if far or null if unknown.
     */
    public Boolean isProximityCurrentlyNear() {
        return mProximitySensor.isNear();
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

        protected boolean mRequested;
        protected boolean mRegistered;
        protected boolean mDisabled;
        protected boolean mIgnoresSetting;
        protected final DozeLog mDozeLog;

        public TriggerSensor(Sensor sensor, String setting, boolean configured, int pulseReason,
                boolean reportsTouchCoordinates, boolean requiresTouchscreen, DozeLog dozeLog) {
            this(sensor, setting, true /* settingDef */, configured, pulseReason,
                    reportsTouchCoordinates, requiresTouchscreen, dozeLog);
        }

        public TriggerSensor(Sensor sensor, String setting, boolean settingDef,
                boolean configured, int pulseReason, boolean reportsTouchCoordinates,
                boolean requiresTouchscreen, DozeLog dozeLog) {
            this(sensor, setting, settingDef, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen, false /* ignoresSetting */, dozeLog);
        }

        private TriggerSensor(Sensor sensor, String setting, boolean settingDef,
                boolean configured, int pulseReason, boolean reportsTouchCoordinates,
                boolean requiresTouchscreen, boolean ignoresSetting, DozeLog dozeLog) {
            mSensor = sensor;
            mSetting = setting;
            mSettingDefault = settingDef;
            mConfigured = configured;
            mPulseReason = pulseReason;
            mReportsTouchCoordinates = reportsTouchCoordinates;
            mRequiresTouchscreen = requiresTouchscreen;
            mIgnoresSetting = ignoresSetting;
            mDozeLog = dozeLog;
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
            return mSecureSettings.getIntForUser(mSetting, mSettingDefault ? 1 : 0,
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
            mDozeLog.traceSensor(mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                if (DEBUG) Log.d(TAG, "onTrigger: " + triggerEventToString(event));
                if (mSensor != null && mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(
                            mContext, MetricsProto.MetricsEvent.ACTION_AMBIENT_GESTURE,
                            subType);
                    UI_EVENT_LOGGER.log(DozeSensorsUiEvent.ACTION_AMBIENT_GESTURE_PICKUP);
                }

                mRegistered = false;
                float screenX = -1;
                float screenY = -1;
                if (mReportsTouchCoordinates && event.values.length >= 2) {
                    screenX = event.values[0];
                    screenY = event.values[1];
                }
                mCallback.onSensorPulse(mPulseReason, screenX, screenY, event.values);
                if (!mRegistered) {
                    updateListening();  // reregister, this sensor only fires once
                }
            }));
        }

        public void registerSettingsObserver(ContentObserver settingsObserver) {
            if (mConfigured && !TextUtils.isEmpty(mSetting)) {
                mSecureSettings.registerContentObserverForUser(
                        mSetting, mSettingsObserver, UserHandle.USER_ALL);
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
                int pulseReason, boolean reportsTouchCoordinates, boolean requiresTouchscreen,
                DozeLog dozeLog) {
            this(sensor, setting, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen, 0L /* debounce */, dozeLog);
        }

        PluginSensor(SensorManagerPlugin.Sensor sensor, String setting, boolean configured,
                int pulseReason, boolean reportsTouchCoordinates, boolean requiresTouchscreen,
                long debounce, DozeLog dozeLog) {
            super(null, setting, configured, pulseReason, reportsTouchCoordinates,
                    requiresTouchscreen, dozeLog);
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
            mDozeLog.traceSensor(mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                final long now = SystemClock.uptimeMillis();
                if (now < mDebounceFrom + mDebounce) {
                    Log.d(TAG, "onSensorEvent dropped: " + triggerEventToString(event));
                    return;
                }
                if (DEBUG) Log.d(TAG, "onSensorEvent: " + triggerEventToString(event));
                mCallback.onSensorPulse(mPulseReason, -1, -1, event.getValues());
            }));
        }
    }

    public interface Callback {

        /**
         * Called when a sensor requests a pulse
         * @param pulseReason Requesting sensor, e.g. {@link DozeLog#REASON_SENSOR_PICKUP}
         * @param screenX the location on the screen where the sensor fired or -1
         *                if the sensor doesn't support reporting screen locations.
         * @param screenY the location on the screen where the sensor fired or -1
         * @param rawValues raw values array from the event.
         */
        void onSensorPulse(int pulseReason, float screenX, float screenY, float[] rawValues);
    }
}
