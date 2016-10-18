/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.DozeParameters;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;

public class DozeService extends DreamService {
    private static final String TAG = "DozeService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_BASE = "com.android.systemui.doze";
    private static final String PULSE_ACTION = ACTION_BASE + ".pulse";

    /**
     * If true, reregisters all trigger sensors when the screen turns off.
     */
    private static final boolean REREGISTER_ALL_SENSORS_ON_SCREEN_OFF = true;

    private final String mTag = String.format(TAG + ".%08x", hashCode());
    private final Context mContext = this;
    private final DozeParameters mDozeParameters = new DozeParameters(mContext);
    private final Handler mHandler = new Handler();

    private DozeHost mHost;
    private SensorManager mSensorManager;
    private TriggerSensor[] mSensors;
    private TriggerSensor mPickupSensor;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private UiModeManager mUiModeManager;
    private boolean mDreaming;
    private boolean mPulsing;
    private boolean mBroadcastReceiverRegistered;
    private boolean mDisplayStateSupported;
    private boolean mPowerSaveActive;
    private boolean mCarMode;
    private long mNotificationPulseTime;

    private AmbientDisplayConfiguration mConfig;

    public DozeService() {
        if (DEBUG) Log.d(mTag, "new DozeService()");
        setDebug(DEBUG);
    }

    @Override
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpOnHandler(fd, pw, args);
        pw.print("  mDreaming: "); pw.println(mDreaming);
        pw.print("  mPulsing: "); pw.println(mPulsing);
        pw.print("  mWakeLock: held="); pw.println(mWakeLock.isHeld());
        pw.print("  mHost: "); pw.println(mHost);
        pw.print("  mBroadcastReceiverRegistered: "); pw.println(mBroadcastReceiverRegistered);
        for (TriggerSensor s : mSensors) {
            pw.print("  sensor: ");
            pw.println(s);
        }
        pw.print("  mDisplayStateSupported: "); pw.println(mDisplayStateSupported);
        pw.print("  mPowerSaveActive: "); pw.println(mPowerSaveActive);
        pw.print("  mCarMode: "); pw.println(mCarMode);
        pw.print("  mNotificationPulseTime: "); pw.println(
                DozeLog.FORMAT.format(new Date(mNotificationPulseTime
                        - SystemClock.elapsedRealtime() + System.currentTimeMillis())));
        mDozeParameters.dump(pw);
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(mTag, "onCreate");
        super.onCreate();

        if (getApplication() instanceof SystemUIApplication) {
            final SystemUIApplication app = (SystemUIApplication) getApplication();
            mHost = app.getComponent(DozeHost.class);
        }
        if (mHost == null) Log.w(TAG, "No doze service host found.");

        setWindowless(true);

        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mConfig = new AmbientDisplayConfiguration(mContext);
        mSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        mDozeParameters.getPulseOnSigMotion(),
                        mDozeParameters.getVibrateOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION),
                mPickupSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                        mConfig.pulseOnPickupAvailable(), mDozeParameters.getVibrateOnPickup(),
                        DozeLog.PULSE_REASON_SENSOR_PICKUP),
                new TriggerSensor(
                        findSensorWithType(mConfig.doubleTapSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP, true,
                        mDozeParameters.getVibrateOnPickup(),
                        DozeLog.PULSE_REASON_SENSOR_DOUBLE_TAP)
        };
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);
        mDisplayStateSupported = mDozeParameters.getDisplayStateSupported();
        mUiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
        turnDisplayOff();
    }

    @Override
    public void onAttachedToWindow() {
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        super.onAttachedToWindow();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();

        if (mHost == null) {
            finish();
            return;
        }

        mPowerSaveActive = mHost.isPowerSaveActive();
        mCarMode = mUiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        if (DEBUG) Log.d(mTag, "onDreamingStarted canDoze=" + canDoze() + " mPowerSaveActive="
                + mPowerSaveActive + " mCarMode=" + mCarMode);
        if (mPowerSaveActive) {
            finishToSavePower();
            return;
        }
        if (mCarMode) {
            finishForCarMode();
            return;
        }

        mDreaming = true;
        listenForPulseSignals(true);

        // Ask the host to get things ready to start dozing.
        // Once ready, we call startDozing() at which point the CPU may suspend
        // and we will need to acquire a wakelock to do work.
        mHost.startDozing(mWakeLock.wrap(() -> {
            if (mDreaming) {
                startDozing();

                // From this point until onDreamingStopped we will need to hold a
                // wakelock whenever we are doing work.  Note that we never call
                // stopDozing because can we just keep dozing until the bitter end.
            }
        }));
    }

    @Override
    public void onDreamingStopped() {
        if (DEBUG) Log.d(mTag, "onDreamingStopped isDozing=" + isDozing());
        super.onDreamingStopped();

        if (mHost == null) {
            return;
        }

        mDreaming = false;
        listenForPulseSignals(false);

        // Tell the host that it's over.
        mHost.stopDozing();
    }

    private void requestPulse(final int reason) {
        requestPulse(reason, false /* performedProxCheck */);
    }

    private void requestPulse(final int reason, boolean performedProxCheck) {
        if (mHost != null && mDreaming && !mPulsing) {
            // Let the host know we want to pulse.  Wait for it to be ready, then
            // turn the screen on.  When finished, turn the screen off again.
            // Here we need a wakelock to stay awake until the pulse is finished.
            mWakeLock.acquire();
            mPulsing = true;
            if (!mDozeParameters.getProxCheckBeforePulse()) {
                // skip proximity check
                continuePulsing(reason);
                return;
            }
            final long start = SystemClock.uptimeMillis();
            if (performedProxCheck) {
                // the caller already performed a successful proximity check; we'll only do one to
                // capture statistics, continue pulsing immediately.
                continuePulsing(reason);
            }
            // perform a proximity check
            new ProximityCheck() {
                @Override
                public void onProximityResult(int result) {
                    final boolean isNear = result == RESULT_NEAR;
                    final long end = SystemClock.uptimeMillis();
                    DozeLog.traceProximityResult(mContext, isNear, end - start, reason);
                    if (performedProxCheck) {
                        // we already continued
                        return;
                    }
                    // avoid pulsing in pockets
                    if (isNear) {
                        mPulsing = false;
                        mWakeLock.release();
                        return;
                    }

                    // not in-pocket, continue pulsing
                    continuePulsing(reason);
                }
            }.check();
        }
    }

    private void continuePulsing(int reason) {
        if (mHost.isPulsingBlocked()) {
            mPulsing = false;
            mWakeLock.release();
            return;
        }
        mHost.pulseWhileDozing(new DozeHost.PulseCallback() {
            @Override
            public void onPulseStarted() {
                if (mPulsing && mDreaming) {
                    turnDisplayOn();
                }
            }

            @Override
            public void onPulseFinished() {
                if (mPulsing && mDreaming) {
                    mPulsing = false;
                    if (REREGISTER_ALL_SENSORS_ON_SCREEN_OFF) {
                        reregisterAllSensors();
                    }
                    turnDisplayOff();
                }
                mWakeLock.release(); // needs to be unconditional to balance acquire
            }
        }, reason);
    }

    private void turnDisplayOff() {
        if (DEBUG) Log.d(mTag, "Display off");
        setDozeScreenState(Display.STATE_OFF);
    }

    private void turnDisplayOn() {
        if (DEBUG) Log.d(mTag, "Display on");
        setDozeScreenState(mDisplayStateSupported ? Display.STATE_DOZE : Display.STATE_ON);
    }

    private void finishToSavePower() {
        Log.w(mTag, "Exiting ambient mode due to low power battery saver");
        finish();
    }

    private void finishForCarMode() {
        Log.w(mTag, "Exiting ambient mode, not allowed in car mode");
        finish();
    }

    private void listenForPulseSignals(boolean listen) {
        if (DEBUG) Log.d(mTag, "listenForPulseSignals: " + listen);
        for (TriggerSensor s : mSensors) {
            s.setListening(listen);
        }
        listenForBroadcasts(listen);
        listenForNotifications(listen);
    }

    private void reregisterAllSensors() {
        for (TriggerSensor s : mSensors) {
            s.setListening(false);
        }
        for (TriggerSensor s : mSensors) {
            s.setListening(true);
        }
    }

    private void listenForBroadcasts(boolean listen) {
        if (listen) {
            final IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            mContext.registerReceiver(mBroadcastReceiver, filter);

            for (TriggerSensor s : mSensors) {
                if (s.mConfigured && !TextUtils.isEmpty(s.mSetting)) {
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Secure.getUriFor(s.mSetting), false /* descendants */,
                            mSettingsObserver, UserHandle.USER_ALL);
                }
            }
            mBroadcastReceiverRegistered = true;
        } else {
            if (mBroadcastReceiverRegistered) {
                mContext.unregisterReceiver(mBroadcastReceiver);
                mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            }
            mBroadcastReceiverRegistered = false;
        }
    }

    private void listenForNotifications(boolean listen) {
        if (listen) {
            mHost.addCallback(mHostCallback);
        } else {
            mHost.removeCallback(mHostCallback);
        }
    }

    private void requestNotificationPulse() {
        if (DEBUG) Log.d(mTag, "requestNotificationPulse");
        if (!mConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) return;
        mNotificationPulseTime = SystemClock.elapsedRealtime();
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
    }

    private static String triggerEventToString(TriggerEvent event) {
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

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DEBUG) Log.d(mTag, "Received pulse intent");
                requestPulse(DozeLog.PULSE_REASON_INTENT);
            }
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(intent.getAction())) {
                mCarMode = true;
                if (mCarMode && mDreaming) {
                    finishForCarMode();
                }
            }
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                for (TriggerSensor s : mSensors) {
                    s.updateListener();
                }
            }
        }
    };

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

    private final DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onNewNotifications() {
            if (DEBUG) Log.d(mTag, "onNewNotifications (noop)");
            // noop for now
        }

        @Override
        public void onBuzzBeepBlinked() {
            if (DEBUG) Log.d(mTag, "onBuzzBeepBlinked");
            requestNotificationPulse();
        }

        @Override
        public void onNotificationLight(boolean on) {
            if (DEBUG) Log.d(mTag, "onNotificationLight (noop) on=" + on);
            // noop for now
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            mPowerSaveActive = active;
            if (mPowerSaveActive && mDreaming) {
                finishToSavePower();
            }
        }
    };

    private Sensor findSensorWithType(String type) {
        if (TextUtils.isEmpty(type)) {
            return null;
        }
        List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensorList) {
            if (type.equals(s.getStringType())) {
                return s;
            }
        }
        return null;
    }

    private class TriggerSensor extends TriggerEventListener {
        final Sensor mSensor;
        final boolean mConfigured;
        final boolean mDebugVibrate;
        final int mPulseReason;
        final String mSetting;

        private boolean mRequested;
        private boolean mRegistered;
        private boolean mDisabled;

        public TriggerSensor(Sensor sensor, String setting, boolean configured,
                boolean debugVibrate, int pulseReason) {
            mSensor = sensor;
            mSetting = setting;
            mConfigured = configured;
            mDebugVibrate = debugVibrate;
            mPulseReason = pulseReason;
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
                if (DEBUG) Log.d(mTag, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(mTag, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
        }

        private boolean enabledBySetting() {
            if (TextUtils.isEmpty(mSetting)) {
                return true;
            }
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), mSetting, 1,
                    UserHandle.USER_CURRENT) != 0;
        }

        @Override
        public String toString() {
            return new StringBuilder("{mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mDebugVibrate=").append(mDebugVibrate)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        public void onTrigger(TriggerEvent event) {
            mWakeLock.acquire();
            try {
                if (DEBUG) Log.d(mTag, "onTrigger: " + triggerEventToString(event));
                boolean sensorPerformsProxCheck = false;
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(mContext, MetricsEvent.ACTION_AMBIENT_GESTURE, subType);
                    sensorPerformsProxCheck = mDozeParameters.getPickupSubtypePerformsProxCheck(
                            subType);
                }
                if (mDebugVibrate) {
                    final Vibrator v = (Vibrator) mContext.getSystemService(
                            Context.VIBRATOR_SERVICE);
                    if (v != null) {
                        v.vibrate(1000, new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                    }
                }

                mRegistered = false;
                requestPulse(mPulseReason, sensorPerformsProxCheck);
                updateListener();  // reregister, this sensor only fires once

                // record pickup gesture, also keep track of whether we might have been triggered
                // by recent vibration.
                final long timeSinceNotification = SystemClock.elapsedRealtime()
                        - mNotificationPulseTime;
                final boolean withinVibrationThreshold =
                        timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    DozeLog.tracePickupPulse(mContext, withinVibrationThreshold);
                }
            } finally {
                mWakeLock.release();
            }
        }
    }

    private abstract class ProximityCheck implements SensorEventListener, Runnable {
        private static final int TIMEOUT_DELAY_MS = 500;

        protected static final int RESULT_UNKNOWN = 0;
        protected static final int RESULT_NEAR = 1;
        protected static final int RESULT_FAR = 2;

        private final String mTag = DozeService.this.mTag + ".ProximityCheck";

        private boolean mRegistered;
        private boolean mFinished;
        private float mMaxRange;

        abstract public void onProximityResult(int result);

        public void check() {
            if (mFinished || mRegistered) return;
            final Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (sensor == null) {
                if (DEBUG) Log.d(mTag, "No sensor found");
                finishWithResult(RESULT_UNKNOWN);
                return;
            }
            // the pickup sensor interferes with the prox event, disable it until we have a result
            mPickupSensor.setDisabled(true);

            mMaxRange = sensor.getMaximumRange();
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0,
                    mHandler);
            mHandler.postDelayed(this, TIMEOUT_DELAY_MS);
            mRegistered = true;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length == 0) {
                if (DEBUG) Log.d(mTag, "Event has no values!");
                finishWithResult(RESULT_UNKNOWN);
            } else {
                if (DEBUG) Log.d(mTag, "Event: value=" + event.values[0] + " max=" + mMaxRange);
                final boolean isNear = event.values[0] < mMaxRange;
                finishWithResult(isNear ? RESULT_NEAR : RESULT_FAR);
            }
        }

        @Override
        public void run() {
            if (DEBUG) Log.d(mTag, "No event received before timeout");
            finishWithResult(RESULT_UNKNOWN);
        }

        private void finishWithResult(int result) {
            if (mFinished) return;
            if (mRegistered) {
                mHandler.removeCallbacks(this);
                mSensorManager.unregisterListener(this);
                // we're done - reenable the pickup sensor
                mPickupSensor.setDisabled(false);
                mRegistered = false;
            }
            onProximityResult(result);
            mFinished = true;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // noop
        }
    }
}
