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

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.display.AmbientDisplayConfiguration;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.Formatter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.Assert;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Handles triggers for ambient state changes.
 */
public class DozeTriggers implements DozeMachine.Part {

    private static final String TAG = "DozeTriggers";
    private static final boolean DEBUG = DozeService.DEBUG;

    /** adb shell am broadcast -a com.android.systemui.doze.pulse com.android.systemui */
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";

    /**
     * Last value sent by the wake-display sensor.
     * Assuming that the screen should start on.
     */
    private static boolean sWakeDisplaySensorState = true;

    private static final int PROXIMITY_TIMEOUT_DELAY_MS = 500;

    private final Context mContext;
    private final DozeMachine mMachine;
    private final DozeSensors mDozeSensors;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeParameters mDozeParameters;
    private final AsyncSensorManager mSensorManager;
    private final Handler mHandler;
    private final WakeLock mWakeLock;
    private final boolean mAllowPulseTriggers;
    private final UiModeManager mUiModeManager;
    private final TriggerReceiver mBroadcastReceiver = new TriggerReceiver();
    private final DockEventListener mDockEventListener = new DockEventListener();
    private final DockManager mDockManager;
    private final ProximitySensor.ProximityCheck mProxCheck;

    private long mNotificationPulseTime;
    private boolean mPulsePending;

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    public DozeTriggers(Context context, DozeMachine machine, DozeHost dozeHost,
            AlarmManager alarmManager, AmbientDisplayConfiguration config,
            DozeParameters dozeParameters, AsyncSensorManager sensorManager, Handler handler,
            WakeLock wakeLock, boolean allowPulseTriggers, DockManager dockManager) {
        mContext = context;
        mMachine = machine;
        mDozeHost = dozeHost;
        mConfig = config;
        mDozeParameters = dozeParameters;
        mSensorManager = sensorManager;
        mHandler = handler;
        mWakeLock = wakeLock;
        mAllowPulseTriggers = allowPulseTriggers;
        mDozeSensors = new DozeSensors(context, alarmManager, mSensorManager, dozeParameters,
                config, wakeLock, this::onSensor, this::onProximityFar,
                dozeParameters.getPolicy());
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mDockManager = dockManager;
        mProxCheck = new ProximitySensor.ProximityCheck(
                new ProximitySensor(mContext, mSensorManager, null),
                mHandler);
    }

    private void onNotification(Runnable onPulseSuppressedListener) {
        if (DozeMachine.DEBUG) {
            Log.d(TAG, "requestNotificationPulse");
        }
        if (!sWakeDisplaySensorState) {
            Log.d(TAG, "Wake display false. Pulse denied.");
            runIfNotNull(onPulseSuppressedListener);
            DozeLog.tracePulseDropped(mContext, "wakeDisplaySensor");
            return;
        }
        mNotificationPulseTime = SystemClock.elapsedRealtime();
        if (!mConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) {
            runIfNotNull(onPulseSuppressedListener);
            DozeLog.tracePulseDropped(mContext, "pulseOnNotificationsDisabled");
            return;
        }
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION, false /* performedProxCheck */,
                onPulseSuppressedListener);
        DozeLog.traceNotificationPulse(mContext);
    }

    private static void runIfNotNull(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    private void proximityCheckThenCall(Consumer<Boolean> callback,
            boolean alreadyPerformedProxCheck,
            int reason) {
        Boolean cachedProxNear = mDozeSensors.isProximityCurrentlyNear();
        if (alreadyPerformedProxCheck) {
            callback.accept(null);
        } else if (cachedProxNear != null) {
            callback.accept(cachedProxNear);
        } else {
            final long start = SystemClock.uptimeMillis();
            mProxCheck.check(PROXIMITY_TIMEOUT_DELAY_MS, near -> {
                final long end = SystemClock.uptimeMillis();
                DozeLog.traceProximityResult(
                        mContext,
                        near == null ? false : near,
                        end - start,
                        reason);
                callback.accept(near);
                mWakeLock.release(TAG);
            });
            mWakeLock.acquire(TAG);
        }
    }

    @VisibleForTesting
    void onSensor(int pulseReason, float screenX, float screenY, float[] rawValues) {
        boolean isDoubleTap = pulseReason == DozeLog.REASON_SENSOR_DOUBLE_TAP;
        boolean isTap = pulseReason == DozeLog.REASON_SENSOR_TAP;
        boolean isPickup = pulseReason == DozeLog.REASON_SENSOR_PICKUP;
        boolean isLongPress = pulseReason == DozeLog.PULSE_REASON_SENSOR_LONG_PRESS;
        boolean isWakeDisplay = pulseReason == DozeLog.REASON_SENSOR_WAKE_UP;
        boolean isWakeLockScreen = pulseReason == DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN;
        boolean wakeEvent = rawValues != null && rawValues.length > 0 && rawValues[0] != 0;

        if (isWakeDisplay) {
            onWakeScreen(wakeEvent, mMachine.isExecutingTransition() ? null : mMachine.getState());
        } else if (isLongPress) {
            requestPulse(pulseReason, true /* alreadyPerformedProxCheck */,
                    null /* onPulseSupressedListener */);
        } else if (isWakeLockScreen) {
            if (wakeEvent) {
                requestPulse(pulseReason, true /* alreadyPerformedProxCheck */,
                        null /* onPulseSupressedListener */);
            }
        } else {
            proximityCheckThenCall((result) -> {
                if (result) {
                    // In pocket, drop event.
                    return;
                }
                if (isDoubleTap || isTap) {
                    if (screenX != -1 && screenY != -1) {
                        mDozeHost.onSlpiTap(screenX, screenY);
                    }
                    gentleWakeUp(pulseReason);
                } else if (isPickup) {
                    gentleWakeUp(pulseReason);
                } else {
                    mDozeHost.extendPulse(pulseReason);
                }
            }, true /* alreadyPerformedProxCheck */, pulseReason);
        }

        if (isPickup) {
            final long timeSinceNotification =
                    SystemClock.elapsedRealtime() - mNotificationPulseTime;
            final boolean withinVibrationThreshold =
                    timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
            DozeLog.tracePickupWakeUp(mContext, withinVibrationThreshold);
        }
    }

    private void gentleWakeUp(int reason) {
        // Log screen wake up reason (lift/pickup, tap, double-tap)
        mMetricsLogger.write(new LogMaker(MetricsEvent.DOZING)
                .setType(MetricsEvent.TYPE_UPDATE)
                .setSubtype(reason));
        if (mDozeParameters.getDisplayNeedsBlanking()) {
            // Let's prepare the display to wake-up by drawing black.
            // This will cover the hardware wake-up sequence, where the display
            // becomes black for a few frames.
            mDozeHost.setAodDimmingScrim(1f);
        }
        mMachine.wakeUp();
    }

    private void onProximityFar(boolean far) {
        // Proximity checks are asynchronous and the user might have interacted with the phone
        // when a new event is arriving. This means that a state transition might have happened
        // and the proximity check is now obsolete.
        if (mMachine.isExecutingTransition()) {
            Log.w(TAG, "onProximityFar called during transition. Ignoring sensor response.");
            return;
        }

        final boolean near = !far;
        final DozeMachine.State state = mMachine.getState();
        final boolean paused = (state == DozeMachine.State.DOZE_AOD_PAUSED);
        final boolean pausing = (state == DozeMachine.State.DOZE_AOD_PAUSING);
        final boolean aod = (state == DozeMachine.State.DOZE_AOD);

        if (state == DozeMachine.State.DOZE_PULSING
                || state == DozeMachine.State.DOZE_PULSING_BRIGHT) {
            boolean ignoreTouch = near;
            if (DEBUG) {
                Log.i(TAG, "Prox changed, ignore touch = " + ignoreTouch);
            }
            mDozeHost.onIgnoreTouchWhilePulsing(ignoreTouch);
        }

        if (far && (paused || pausing)) {
            if (DEBUG) {
                Log.i(TAG, "Prox FAR, unpausing AOD");
            }
            mMachine.requestState(DozeMachine.State.DOZE_AOD);
        } else if (near && aod) {
            if (DEBUG) {
                Log.i(TAG, "Prox NEAR, pausing AOD");
            }
            mMachine.requestState(DozeMachine.State.DOZE_AOD_PAUSING);
        }
    }

    /**
     * When a wake screen event is received from a sensor
     * @param wake {@code true} when it's time to wake up, {@code false} when we should sleep.
     * @param state The current state, or null if the state could not be determined due to enqueued
     *              transitions.
     */
    private void onWakeScreen(boolean wake, @Nullable DozeMachine.State state) {
        DozeLog.traceWakeDisplay(wake);
        sWakeDisplaySensorState = wake;

        if (wake) {
            proximityCheckThenCall((result) -> {
                if (result) {
                    // In pocket, drop event.
                    return;
                }
                if (state == DozeMachine.State.DOZE) {
                    mMachine.requestState(DozeMachine.State.DOZE_AOD);
                    // Logs AOD open due to sensor wake up.
                    mMetricsLogger.write(new LogMaker(MetricsEvent.DOZING)
                            .setType(MetricsEvent.TYPE_OPEN)
                            .setSubtype(DozeLog.REASON_SENSOR_WAKE_UP));
                }
            }, true /* alreadyPerformedProxCheck */, DozeLog.REASON_SENSOR_WAKE_UP);
        } else {
            boolean paused = (state == DozeMachine.State.DOZE_AOD_PAUSED);
            boolean pausing = (state == DozeMachine.State.DOZE_AOD_PAUSING);
            if (!pausing && !paused) {
                mMachine.requestState(DozeMachine.State.DOZE);
                // Logs AOD close due to sensor wake up.
                mMetricsLogger.write(new LogMaker(MetricsEvent.DOZING)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(DozeLog.REASON_SENSOR_WAKE_UP));
            }
        }
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mBroadcastReceiver.register(mContext);
                mDozeHost.addCallback(mHostCallback);
                if (mDockManager != null) {
                    mDockManager.addListener(mDockEventListener);
                }
                mDozeSensors.requestTemporaryDisable();
                checkTriggersAtInit();
                break;
            case DOZE:
            case DOZE_AOD:
                mDozeSensors.setProxListening(newState != DozeMachine.State.DOZE);
                mDozeSensors.setListening(true);
                mDozeSensors.setPaused(false);
                if (newState == DozeMachine.State.DOZE_AOD && !sWakeDisplaySensorState) {
                    onWakeScreen(false, newState);
                }
                break;
            case DOZE_AOD_PAUSED:
            case DOZE_AOD_PAUSING:
                mDozeSensors.setProxListening(true);
                mDozeSensors.setPaused(true);
                break;
            case DOZE_PULSING:
            case DOZE_PULSING_BRIGHT:
                mDozeSensors.setTouchscreenSensorsListening(false);
                mDozeSensors.setProxListening(true);
                mDozeSensors.setPaused(false);
                break;
            case DOZE_PULSE_DONE:
                mDozeSensors.requestTemporaryDisable();
                // A pulse will temporarily disable sensors that require a touch screen.
                // Let's make sure that they are re-enabled when the pulse is over.
                mDozeSensors.updateListening();
                break;
            case FINISH:
                mBroadcastReceiver.unregister(mContext);
                mDozeHost.removeCallback(mHostCallback);
                if (mDockManager != null) {
                    mDockManager.removeListener(mDockEventListener);
                }
                mDozeSensors.setListening(false);
                mDozeSensors.setProxListening(false);
                break;
            default:
        }
    }

    private void checkTriggersAtInit() {
        if (mUiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR
                || mDozeHost.isPowerSaveActive()
                || mDozeHost.isBlockingDoze()
                || !mDozeHost.isProvisioned()) {
            mMachine.requestState(DozeMachine.State.FINISH);
        }
    }

    private void requestPulse(final int reason, boolean performedProxCheck,
            Runnable onPulseSuppressedListener) {
        Assert.isMainThread();
        mDozeHost.extendPulse(reason);

        // When already pulsing we're allowed to show the wallpaper directly without
        // requesting a new pulse.
        if (mMachine.getState() == DozeMachine.State.DOZE_PULSING
                && reason == DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN) {
            mMachine.requestState(DozeMachine.State.DOZE_PULSING_BRIGHT);
            return;
        }

        if (mPulsePending || !mAllowPulseTriggers || !canPulse()) {
            if (mAllowPulseTriggers) {
                DozeLog.tracePulseDropped(mContext, mPulsePending, mMachine.getState(),
                        mDozeHost.isPulsingBlocked());
            }
            runIfNotNull(onPulseSuppressedListener);
            return;
        }

        mPulsePending = true;
        proximityCheckThenCall((result) -> {
            if (result) {
                // in pocket, abort pulse
                DozeLog.tracePulseDropped(mContext, "inPocket");
                mPulsePending = false;
                runIfNotNull(onPulseSuppressedListener);
            } else {
                // not in pocket, continue pulsing
                continuePulseRequest(reason);
            }
        }, !mDozeParameters.getProxCheckBeforePulse() || performedProxCheck, reason);

        // Logs request pulse reason on AOD screen.
        mMetricsLogger.write(new LogMaker(MetricsEvent.DOZING)
                .setType(MetricsEvent.TYPE_UPDATE).setSubtype(reason));
    }

    private boolean canPulse() {
        return mMachine.getState() == DozeMachine.State.DOZE
                || mMachine.getState() == DozeMachine.State.DOZE_AOD;
    }

    private void continuePulseRequest(int reason) {
        mPulsePending = false;
        if (mDozeHost.isPulsingBlocked() || !canPulse()) {
            DozeLog.tracePulseDropped(mContext, mPulsePending, mMachine.getState(),
                    mDozeHost.isPulsingBlocked());
            return;
        }
        mMachine.requestPulse(reason);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" notificationPulseTime=");
        pw.println(Formatter.formatShortElapsedTime(mContext, mNotificationPulseTime));

        pw.println(" pulsePending=" + mPulsePending);
        pw.println("DozeSensors:");
        mDozeSensors.dump(pw);
    }

    private class TriggerReceiver extends BroadcastReceiver {
        private boolean mRegistered;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DozeMachine.DEBUG) Log.d(TAG, "Received pulse intent");
                requestPulse(DozeLog.PULSE_REASON_INTENT, false, /* performedProxCheck */
                        null /* onPulseSupressedListener */);
            }
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(intent.getAction())) {
                mMachine.requestState(DozeMachine.State.FINISH);
            }
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mDozeSensors.onUserSwitched();
            }
        }

        public void register(Context context) {
            if (mRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            context.registerReceiver(this, filter);
            mRegistered = true;
        }

        public void unregister(Context context) {
            if (!mRegistered) {
                return;
            }
            context.unregisterReceiver(this);
            mRegistered = false;
        }
    }

    private class DockEventListener implements DockManager.DockEventListener {
        @Override
        public void onEvent(int event) {
            if (DEBUG) Log.d(TAG, "dock event = " + event);
            switch (event) {
                case DockManager.STATE_DOCKED:
                case DockManager.STATE_DOCKED_HIDE:
                    mDozeSensors.ignoreTouchScreenSensorsSettingInterferingWithDocking(true);
                    break;
                case DockManager.STATE_NONE:
                    mDozeSensors.ignoreTouchScreenSensorsSettingInterferingWithDocking(false);
                    break;
                default:
                    // no-op
            }
        }
    }

    private DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onNotificationAlerted(Runnable onPulseSuppressedListener) {
            onNotification(onPulseSuppressedListener);
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            if (active) {
                mMachine.requestState(DozeMachine.State.FINISH);
            }
        }
    };
}
