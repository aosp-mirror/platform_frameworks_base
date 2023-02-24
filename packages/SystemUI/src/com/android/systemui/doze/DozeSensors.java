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

import static com.android.systemui.doze.DozeLog.REASON_SENSOR_QUICK_PICKUP;
import static com.android.systemui.doze.DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS;
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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tracks and registers/unregisters sensors while the device is dozing based on the config
 * provided by {@link AmbientDisplayConfiguration} and parameters provided by {@link DozeParameters}
 *
 * Sensors registration depends on:
 *    - sensor existence/availability
 *    - user configuration (some can be toggled on/off via settings)
 *    - use of the proximity sensor (sometimes prox cannot be registered in certain display states)
 *    - touch state
 *    - device posture
 *
 * Sensors will trigger the provided Callback's {@link Callback#onSensorPulse} method.
 * These sensors include:
 *    - pickup gesture
 *    - single and double tap gestures
 *    - udfps long-press gesture
 *    - reach and presence gestures
 *    - quick pickup gesture (low-threshold pickup gesture)
 *
 * This class also registers a ProximitySensor that reports near/far events and will
 * trigger callbacks on the provided {@link mProxCallback}.
 */
public class DozeSensors {

    private static final boolean DEBUG = DozeService.DEBUG;
    private static final String TAG = "DozeSensors";
    private static final UiEventLogger UI_EVENT_LOGGER = new UiEventLoggerImpl();

    private final Context mContext;
    private final AsyncSensorManager mSensorManager;
    private final AmbientDisplayConfiguration mConfig;
    private final WakeLock mWakeLock;
    private final DozeLog mDozeLog;
    private final SecureSettings mSecureSettings;
    private final DevicePostureController mDevicePostureController;
    private final AuthController mAuthController;
    private final boolean mScreenOffUdfpsEnabled;

    // Sensors
    @VisibleForTesting
    protected TriggerSensor[] mTriggerSensors;
    private final ProximitySensor mProximitySensor;

    // Sensor callbacks
    private final Callback mSensorCallback; // receives callbacks on registered sensor events
    private final Consumer<Boolean> mProxCallback; // receives callbacks on near/far updates

    private final Handler mHandler = new Handler();
    private long mDebounceFrom;
    private boolean mSettingRegistered;
    private boolean mListening;
    private boolean mListeningTouchScreenSensors;
    private boolean mListeningProxSensors;
    private boolean mUdfpsEnrolled;

    @DevicePostureController.DevicePostureInt
    private int mDevicePosture;

    // whether to only register sensors that use prox when the display state is dozing or off
    private boolean mSelectivelyRegisterProxSensors;

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

    DozeSensors(
            Context context,
            AsyncSensorManager sensorManager,
            DozeParameters dozeParameters,
            AmbientDisplayConfiguration config,
            WakeLock wakeLock,
            Callback sensorCallback,
            Consumer<Boolean> proxCallback,
            DozeLog dozeLog,
            ProximitySensor proximitySensor,
            SecureSettings secureSettings,
            AuthController authController,
            DevicePostureController devicePostureController
    ) {
        mContext = context;
        mSensorManager = sensorManager;
        mConfig = config;
        mWakeLock = wakeLock;
        mProxCallback = proxCallback;
        mSecureSettings = secureSettings;
        mSensorCallback = sensorCallback;
        mDozeLog = dozeLog;
        mProximitySensor = proximitySensor;
        mProximitySensor.setTag(TAG);
        mSelectivelyRegisterProxSensors = dozeParameters.getSelectivelyRegisterSensorsUsingProx();
        mListeningProxSensors = !mSelectivelyRegisterProxSensors;
        mScreenOffUdfpsEnabled =
                config.screenOffUdfpsEnabled(KeyguardUpdateMonitor.getCurrentUser());
        mDevicePostureController = devicePostureController;
        mDevicePosture = mDevicePostureController.getDevicePosture();
        mAuthController = authController;

        mUdfpsEnrolled =
                mAuthController.isUdfpsEnrolled(KeyguardUpdateMonitor.getCurrentUser());
        mAuthController.addCallback(mAuthControllerCallback);
        mTriggerSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        dozeParameters.getPulseOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION,
                        false /* touchCoords */,
                        false /* touchscreen */),
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PICK_UP_GESTURE,
                        true /* settingDef */,
                        config.dozePickupSensorAvailable(),
                        DozeLog.REASON_SENSOR_PICKUP, false /* touchCoords */,
                        false /* touchscreen */,
                        false /* ignoresSetting */,
                        false /* requires prox */,
                        true /* immediatelyReRegister */),
                new TriggerSensor(
                        findSensor(config.doubleTapSensorType()),
                        Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        dozeParameters.doubleTapReportsTouchCoordinates(),
                        true /* touchscreen */),
                new TriggerSensor(
                        findSensors(config.tapSensorTypeMapping()),
                        Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                        true /* settingDef */,
                        true /* configured */,
                        DozeLog.REASON_SENSOR_TAP,
                        false /* reports touch coordinates */,
                        true /* touchscreen */,
                        false /* ignoresSetting */,
                        dozeParameters.singleTapUsesProx(mDevicePosture) /* requiresProx */,
                        true /* immediatelyReRegister */,
                        mDevicePosture),
                new TriggerSensor(
                        findSensor(config.longPressSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                        false /* settingDef */,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */,
                        false /* ignoresSetting */,
                        dozeParameters.longPressUsesProx() /* requiresProx */,
                        true /* immediatelyReRegister */),
                new TriggerSensor(
                        findSensor(config.udfpsLongPressSensorType()),
                        "doze_pulse_on_auth",
                        true /* settingDef */,
                        udfpsLongPressConfigured(),
                        DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS,
                        true /* reports touch coordinates */,
                        true /* touchscreen */,
                        false /* ignoresSetting */,
                        dozeParameters.longPressUsesProx(),
                        false /* immediatelyReRegister */),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_DISPLAY),
                        Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE,
                        mConfig.wakeScreenGestureAvailable()
                          && mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT),
                        DozeLog.REASON_SENSOR_WAKE_UP_PRESENCE,
                        false /* reports touch coordinates */,
                        false /* touchscreen */),
                new PluginSensor(
                        new SensorManagerPlugin.Sensor(TYPE_WAKE_LOCK_SCREEN),
                        Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE,
                        mConfig.wakeScreenGestureAvailable(),
                        DozeLog.PULSE_REASON_SENSOR_WAKE_REACH,
                        false /* reports touch coordinates */,
                        false /* touchscreen */,
                        mConfig.getWakeLockScreenDebounce()),
                new TriggerSensor(
                        findSensor(config.quickPickupSensorType()),
                        Settings.Secure.DOZE_QUICK_PICKUP_GESTURE,
                        true /* setting default */,
                        quickPickUpConfigured(),
                        DozeLog.REASON_SENSOR_QUICK_PICKUP,
                        false /* requiresTouchCoordinates */,
                        false /* requiresTouchscreen */,
                        false /* ignoresSetting */,
                        false /* requiresProx */,
                        true /* immediatelyReRegister */),
        };
        setProxListening(false);  // Don't immediately start listening when we register.
        mProximitySensor.register(
                proximityEvent -> {
                    if (proximityEvent != null) {
                        mProxCallback.accept(!proximityEvent.getBelow());
                    }
                });

        mDevicePostureController.addCallback(mDevicePostureCallback);
    }

    private boolean udfpsLongPressConfigured() {
        return mUdfpsEnrolled
                && (mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT) || mScreenOffUdfpsEnabled);
    }

    private boolean quickPickUpConfigured() {
        return mUdfpsEnrolled
                && mConfig.quickPickupSensorEnabled(KeyguardUpdateMonitor.getCurrentUser());
    }

    /**
     *  Unregister all sensors and callbacks.
     */
    public void destroy() {
        // Unregisters everything, which is enough to allow gc.
        for (TriggerSensor triggerSensor : mTriggerSensors) {
            triggerSensor.setListening(false);
        }
        mProximitySensor.destroy();

        mDevicePostureController.removeCallback(mDevicePostureCallback);
        mAuthController.removeCallback(mAuthControllerCallback);
    }

    /**
     * Temporarily disable some sensors to avoid turning on the device while the user is
     * turning it off.
     */
    public void requestTemporaryDisable() {
        mDebounceFrom = SystemClock.uptimeMillis();
    }

    private Sensor findSensor(String type) {
        return findSensor(mSensorManager, type, null);
    }

    @NonNull
    private Sensor[] findSensors(@NonNull String[] types) {
        Sensor[] sensorMap = new Sensor[DevicePostureController.SUPPORTED_POSTURES_SIZE];

        // Map of sensorType => Sensor, so we reuse the same sensor if it's the same between
        // postures
        Map<String, Sensor> typeToSensorMap = new HashMap<>();
        for (int i = 0; i < types.length; i++) {
            String sensorType = types[i];
            if (!typeToSensorMap.containsKey(sensorType)) {
                typeToSensorMap.put(sensorType, findSensor(sensorType));
            }
            sensorMap[i] = typeToSensorMap.get(sensorType);
        }

        return sensorMap;
    }

    /**
     * Utility method to find a {@link Sensor} for the supplied string type and string name.
     *
     * Return the first sensor in the list that matches the specified inputs. Ignores type or name
     * if the input is null or empty.
     *
     * @param type sensorType
     * @parm name sensorName, to differentiate between sensors with the same type
     */
    public static Sensor findSensor(SensorManager sensorManager, String type, String name) {
        final boolean isNameSpecified = !TextUtils.isEmpty(name);
        final boolean isTypeSpecified = !TextUtils.isEmpty(type);
        if (isNameSpecified || isTypeSpecified) {
            final List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            for (Sensor sensor : sensors) {
                if ((!isNameSpecified || name.equals(sensor.getName()))
                        && (!isTypeSpecified || type.equals(sensor.getStringType()))) {
                    return sensor;
                }
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
     * If sensors should be registered and sending signals.
     */
    public void setListening(boolean listen, boolean includeTouchScreenSensors,
            boolean lowPowerStateOrOff) {
        final boolean shouldRegisterProxSensors =
                !mSelectivelyRegisterProxSensors || lowPowerStateOrOff;
        if (mListening == listen && mListeningTouchScreenSensors == includeTouchScreenSensors
                && mListeningProxSensors == shouldRegisterProxSensors) {
            return;
        }
        mListening = listen;
        mListeningTouchScreenSensors = includeTouchScreenSensors;
        mListeningProxSensors = shouldRegisterProxSensors;
        updateListening();
    }

    /**
     * Registers/unregisters sensors based on internal state.
     */
    private void updateListening() {
        boolean anyListening = false;
        for (TriggerSensor s : mTriggerSensors) {
            boolean listen = mListening
                    && (!s.mRequiresTouchscreen || mListeningTouchScreenSensors)
                    && (!s.mRequiresProx || mListeningProxSensors);
            s.setListening(listen);
            if (listen) {
                anyListening = true;
            }
        }

        if (!anyListening) {
            mSecureSettings.unregisterContentObserver(mSettingsObserver);
        } else if (!mSettingRegistered) {
            for (TriggerSensor s : mTriggerSensors) {
                s.registerSettingsObserver(mSettingsObserver);
            }
        }
        mSettingRegistered = anyListening;
    }

    /** Set the listening state of only the sensors that require the touchscreen. */
    public void setTouchscreenSensorsListening(boolean listening) {
        for (TriggerSensor sensor : mTriggerSensors) {
            if (sensor.mRequiresTouchscreen) {
                sensor.setListening(listening);
            }
        }
    }

    public void onUserSwitched() {
        for (TriggerSensor s : mTriggerSensors) {
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
            for (TriggerSensor s : mTriggerSensors) {
                s.updateListening();
            }
        }
    };

    /** Ignore the setting value of only the sensors that require the touchscreen. */
    public void ignoreTouchScreenSensorsSettingInterferingWithDocking(boolean ignore) {
        for (TriggerSensor sensor : mTriggerSensors) {
            if (sensor.mRequiresTouchscreen) {
                sensor.ignoreSetting(ignore);
            }
        }
    }

    /** Dump current state */
    public void dump(PrintWriter pw) {
        pw.println("mListening=" + mListening);
        pw.println("mDevicePosture="
                + DevicePostureController.devicePostureToString(mDevicePosture));
        pw.println("mListeningTouchScreenSensors=" + mListeningTouchScreenSensors);
        pw.println("mSelectivelyRegisterProxSensors=" + mSelectivelyRegisterProxSensors);
        pw.println("mListeningProxSensors=" + mListeningProxSensors);
        pw.println("mScreenOffUdfpsEnabled=" + mScreenOffUdfpsEnabled);
        pw.println("mUdfpsEnrolled=" + mUdfpsEnrolled);
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        for (TriggerSensor s : mTriggerSensors) {
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
        @NonNull final Sensor[] mSensors; // index = posture, value = sensor
        boolean mConfigured;
        final int mPulseReason;
        private final String mSetting;
        private final boolean mReportsTouchCoordinates;
        private final boolean mSettingDefault;
        private final boolean mRequiresTouchscreen;
        private final boolean mRequiresProx;

        // Whether to immediately re-register this sensor after the sensor is triggered.
        // If false, the sensor registration will be updated on the next AOD state transition.
        private final boolean mImmediatelyReRegister;

        protected boolean mRequested;
        protected boolean mRegistered;
        protected boolean mDisabled;
        protected boolean mIgnoresSetting;
        private @DevicePostureController.DevicePostureInt int mPosture;

        TriggerSensor(
                Sensor sensor,
                String setting,
                boolean configured,
                int pulseReason,
                boolean reportsTouchCoordinates,
                boolean requiresTouchscreen
        ) {
            this(
                    sensor,
                    setting,
                    true /* settingDef */,
                    configured,
                    pulseReason,
                    reportsTouchCoordinates,
                    requiresTouchscreen,
                    false /* ignoresSetting */,
                    false /* requiresProx */,
                    true /* immediatelyReRegister */
            );
        }

        TriggerSensor(
                Sensor sensor,
                String setting,
                boolean settingDef,
                boolean configured,
                int pulseReason,
                boolean reportsTouchCoordinates,
                boolean requiresTouchscreen,
                boolean ignoresSetting,
                boolean requiresProx,
                boolean immediatelyReRegister
        ) {
            this(
                    new Sensor[]{ sensor },
                    setting,
                    settingDef,
                    configured,
                    pulseReason,
                    reportsTouchCoordinates,
                    requiresTouchscreen,
                    ignoresSetting,
                    requiresProx,
                    immediatelyReRegister,
                    DevicePostureController.DEVICE_POSTURE_UNKNOWN
            );
        }

        TriggerSensor(
                @NonNull Sensor[] sensors,
                String setting,
                boolean settingDef,
                boolean configured,
                int pulseReason,
                boolean reportsTouchCoordinates,
                boolean requiresTouchscreen,
                boolean ignoresSetting,
                boolean requiresProx,
                boolean immediatelyReRegister,
                @DevicePostureController.DevicePostureInt int posture
        ) {
            mSensors = sensors;
            mSetting = setting;
            mSettingDefault = settingDef;
            mConfigured = configured;
            mPulseReason = pulseReason;
            mReportsTouchCoordinates = reportsTouchCoordinates;
            mRequiresTouchscreen = requiresTouchscreen;
            mIgnoresSetting = ignoresSetting;
            mRequiresProx = requiresProx;
            mPosture = posture;
            mImmediatelyReRegister = immediatelyReRegister;
        }

        /**
         * @return true if the sensor changed based for the new posture
         */
        public boolean setPosture(@DevicePostureController.DevicePostureInt int posture) {
            if (mPosture == posture
                    || mSensors.length < 2
                    || posture >= mSensors.length) {
                return false;
            }

            Sensor oldSensor = mSensors[mPosture];
            Sensor newSensor = mSensors[posture];
            if (Objects.equals(oldSensor, newSensor)) {
                mPosture = posture;
                // uses the same sensor for the new posture
                return false;
            }

            // cancel the previous sensor:
            if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, oldSensor);
                if (DEBUG) {
                    Log.d(TAG, "posture changed, cancelTriggerSensor[" + oldSensor + "] "
                            + rt);
                }
                mRegistered = false;
            }

            // update the new sensor:
            mPosture = posture;
            updateListening();
            mDozeLog.tracePostureChanged(mPosture, "DozeSensors swap "
                    + "{" + oldSensor + "} => {" + newSensor + "}, mRegistered=" + mRegistered);
            return true;
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

        /**
         * Update configured state.
         */
        public void setConfigured(boolean configured) {
            if (mConfigured == configured) return;
            mConfigured = configured;
            updateListening();
        }

        public void updateListening() {
            final Sensor sensor = mSensors[mPosture];

            if (!mConfigured || sensor == null) return;
            if (mRequested && !mDisabled && (enabledBySetting() || mIgnoresSetting)) {
                if (!mRegistered) {
                    mRegistered = mSensorManager.requestTriggerSensor(this, sensor);
                    if (DEBUG) {
                        Log.d(TAG, "requestTriggerSensor[" + sensor + "] " + mRegistered);
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "requestTriggerSensor[" + sensor + "] already registered");
                    }
                }
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, sensor);
                if (DEBUG) {
                    Log.d(TAG, "cancelTriggerSensor[" + sensor + "] " + rt);
                }
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
            StringBuilder sb = new StringBuilder();
            sb.append("{")
                    .append("mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mIgnoresSetting=").append(mIgnoresSetting)
                    .append(", mSensors=").append(Arrays.toString(mSensors));
            if (mSensors.length > 2) {
                sb.append(", mPosture=")
                        .append(DevicePostureController.devicePostureToString(mDevicePosture));
            }
            return sb.append("}").toString();
        }

        @Override
        @AnyThread
        public void onTrigger(TriggerEvent event) {
            final Sensor sensor = mSensors[mPosture];
            mDozeLog.traceSensor(mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                if (DEBUG) Log.d(TAG, "onTrigger: " + triggerEventToString(event));
                if (sensor != null && sensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    UI_EVENT_LOGGER.log(DozeSensorsUiEvent.ACTION_AMBIENT_GESTURE_PICKUP);
                }

                mRegistered = false;
                float screenX = -1;
                float screenY = -1;
                if (mReportsTouchCoordinates && event.values.length >= 2) {
                    screenX = event.values[0];
                    screenY = event.values[1];
                }
                mSensorCallback.onSensorPulse(mPulseReason, screenX, screenY, event.values);
                if (!mRegistered && mImmediatelyReRegister) {
                    updateListening();
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
            mDozeLog.traceSensor(mPulseReason);
            mHandler.post(mWakeLock.wrap(() -> {
                final long now = SystemClock.uptimeMillis();
                if (now < mDebounceFrom + mDebounce) {
                    Log.d(TAG, "onSensorEvent dropped: " + triggerEventToString(event));
                    return;
                }
                if (DEBUG) Log.d(TAG, "onSensorEvent: " + triggerEventToString(event));
                mSensorCallback.onSensorPulse(mPulseReason, -1, -1, event.getValues());
            }));
        }
    }

    private final DevicePostureController.Callback mDevicePostureCallback = posture -> {
        if (mDevicePosture == posture) {
            return;
        }
        mDevicePosture = posture;

        for (TriggerSensor triggerSensor : mTriggerSensors) {
            triggerSensor.setPosture(mDevicePosture);
        }
    };

    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onAllAuthenticatorsRegistered() {
            updateUdfpsEnrolled();
        }

        @Override
        public void onEnrollmentsChanged() {
            updateUdfpsEnrolled();
        }

        private void updateUdfpsEnrolled() {
            mUdfpsEnrolled = mAuthController.isUdfpsEnrolled(
                    KeyguardUpdateMonitor.getCurrentUser());
            for (TriggerSensor sensor : mTriggerSensors) {
                if (REASON_SENSOR_QUICK_PICKUP == sensor.mPulseReason) {
                    sensor.setConfigured(quickPickUpConfigured());
                } else if (REASON_SENSOR_UDFPS_LONG_PRESS == sensor.mPulseReason) {
                    sensor.setConfigured(udfpsLongPressConfigured());
                }
            }
        }
    };

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
