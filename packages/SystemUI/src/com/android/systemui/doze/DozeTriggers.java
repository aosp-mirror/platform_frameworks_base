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

import static android.app.StatusBarManager.SESSION_KEYGUARD;

import static com.android.systemui.doze.DozeMachine.State.DOZE_SUSPEND_TRIGGERS;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeMachine.State;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.Assert;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximityCheck;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Handles triggers for ambient state changes.
 */
@DozeScope
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
    private final SessionTracker mSessionTracker;
    private DozeMachine mMachine;
    private final DozeLog mDozeLog;
    private final DozeSensors mDozeSensors;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeParameters mDozeParameters;
    private final AsyncSensorManager mSensorManager;
    private final WakeLock mWakeLock;
    private final boolean mAllowPulseTriggers;
    private final TriggerReceiver mBroadcastReceiver = new TriggerReceiver();
    private final DockEventListener mDockEventListener = new DockEventListener();
    private final DockManager mDockManager;
    private final ProximityCheck mProxCheck;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final AuthController mAuthController;
    private final KeyguardStateController mKeyguardStateController;
    private final UserTracker mUserTracker;
    private final UiEventLogger mUiEventLogger;

    private long mNotificationPulseTime;
    private Runnable mAodInterruptRunnable;

    /** see {@link #onProximityFar} prox for callback */
    private boolean mWantProxSensor;
    private boolean mWantTouchScreenSensors;
    private boolean mWantSensors;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mDozeSensors.onUserSwitched();
                }
            };

    @VisibleForTesting
    public enum DozingUpdateUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Dozing updated due to notification.")
        DOZING_UPDATE_NOTIFICATION(433),

        @UiEvent(doc = "Dozing updated due to sigmotion.")
        DOZING_UPDATE_SIGMOTION(434),

        @UiEvent(doc = "Dozing updated because sensor was picked up.")
        DOZING_UPDATE_SENSOR_PICKUP(435),

        @UiEvent(doc = "Dozing updated because sensor was double tapped.")
        DOZING_UPDATE_SENSOR_DOUBLE_TAP(436),

        @UiEvent(doc = "Dozing updated because sensor was long squeezed.")
        DOZING_UPDATE_SENSOR_LONG_SQUEEZE(437),

        @UiEvent(doc = "Dozing updated due to docking.")
        DOZING_UPDATE_DOCKING(438),

        @UiEvent(doc = "Dozing updated because sensor woke up.")
        DOZING_UPDATE_SENSOR_WAKEUP(439),

        @UiEvent(doc = "Dozing updated because sensor woke up the lockscreen.")
        DOZING_UPDATE_SENSOR_WAKE_LOCKSCREEN(440),

        @UiEvent(doc = "Dozing updated because sensor was tapped.")
        DOZING_UPDATE_SENSOR_TAP(441),

        @UiEvent(doc = "Dozing updated because on display auth was triggered from AOD.")
        DOZING_UPDATE_AUTH_TRIGGERED(657),

        @UiEvent(doc = "Dozing updated because quick pickup sensor woke up.")
        DOZING_UPDATE_QUICK_PICKUP(708),

        @UiEvent(doc = "Dozing updated - sensor wakeup timed out (from quick pickup or presence)")
        DOZING_UPDATE_WAKE_TIMEOUT(794);

        private final int mId;

        DozingUpdateUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }

        static DozingUpdateUiEvent fromReason(int reason) {
            switch (reason) {
                case 1: return DOZING_UPDATE_NOTIFICATION;
                case 2: return DOZING_UPDATE_SIGMOTION;
                case 3: return DOZING_UPDATE_SENSOR_PICKUP;
                case 4: return DOZING_UPDATE_SENSOR_DOUBLE_TAP;
                case 5: return DOZING_UPDATE_SENSOR_LONG_SQUEEZE;
                case 6: return DOZING_UPDATE_DOCKING;
                case 7: return DOZING_UPDATE_SENSOR_WAKEUP;
                case 8: return DOZING_UPDATE_SENSOR_WAKE_LOCKSCREEN;
                case 9: return DOZING_UPDATE_SENSOR_TAP;
                case 10: return DOZING_UPDATE_AUTH_TRIGGERED;
                case 11: return DOZING_UPDATE_QUICK_PICKUP;
                default: return null;
            }
        }
    }

    @Inject
    public DozeTriggers(Context context, DozeHost dozeHost,
            AmbientDisplayConfiguration config,
            DozeParameters dozeParameters, AsyncSensorManager sensorManager,
            WakeLock wakeLock, DockManager dockManager,
            ProximitySensor proximitySensor,
            ProximityCheck proxCheck,
            DozeLog dozeLog, BroadcastDispatcher broadcastDispatcher,
            SecureSettings secureSettings, AuthController authController,
            UiEventLogger uiEventLogger,
            SessionTracker sessionTracker,
            KeyguardStateController keyguardStateController,
            DevicePostureController devicePostureController,
            UserTracker userTracker) {
        mContext = context;
        mDozeHost = dozeHost;
        mConfig = config;
        mDozeParameters = dozeParameters;
        mSensorManager = sensorManager;
        mWakeLock = wakeLock;
        mAllowPulseTriggers = true;
        mSessionTracker = sessionTracker;

        mDozeSensors = new DozeSensors(mContext.getResources(), mSensorManager, dozeParameters,
                config, wakeLock, this::onSensor, this::onProximityFar, dozeLog, proximitySensor,
                secureSettings, authController, devicePostureController, userTracker);
        mDockManager = dockManager;
        mProxCheck = proxCheck;
        mDozeLog = dozeLog;
        mBroadcastDispatcher = broadcastDispatcher;
        mAuthController = authController;
        mUiEventLogger = uiEventLogger;
        mKeyguardStateController = keyguardStateController;
        mUserTracker = userTracker;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void destroy() {
        mDozeSensors.destroy();
        mProxCheck.destroy();
    }

    private void onNotification(Runnable onPulseSuppressedListener) {
        if (DozeMachine.DEBUG) {
            Log.d(TAG, "requestNotificationPulse");
        }
        if (!sWakeDisplaySensorState) {
            Log.d(TAG, "Wake display false. Pulse denied.");
            runIfNotNull(onPulseSuppressedListener);
            mDozeLog.tracePulseDropped("wakeDisplaySensor");
            return;
        }
        mNotificationPulseTime = SystemClock.elapsedRealtime();
        if (!mConfig.pulseOnNotificationEnabled(mUserTracker.getUserId())) {
            runIfNotNull(onPulseSuppressedListener);
            mDozeLog.tracePulseDropped("pulseOnNotificationsDisabled");
            return;
        }
        if (mDozeHost.isAlwaysOnSuppressed()) {
            runIfNotNull(onPulseSuppressedListener);
            mDozeLog.tracePulseDropped("dozeSuppressed");
            return;
        }
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION, false /* performedProxCheck */,
                onPulseSuppressedListener);
        mDozeLog.traceNotificationPulse();
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
                mDozeLog.traceProximityResult(
                        near != null && near,
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
        boolean isWakeOnPresence = pulseReason == DozeLog.REASON_SENSOR_WAKE_UP_PRESENCE;
        boolean isWakeOnReach = pulseReason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH;
        boolean isUdfpsLongPress = pulseReason == DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS;
        boolean isQuickPickup = pulseReason == DozeLog.REASON_SENSOR_QUICK_PICKUP;
        boolean isWakeDisplayEvent = isQuickPickup || ((isWakeOnPresence || isWakeOnReach)
                && rawValues != null && rawValues.length > 0 && rawValues[0] != 0);

        if (isWakeOnPresence) {
            onWakeScreen(isWakeDisplayEvent,
                    mMachine.isExecutingTransition() ? null : mMachine.getState(),
                    pulseReason);
        } else if (isLongPress) {
            requestPulse(pulseReason, true /* alreadyPerformedProxCheck */,
                    null /* onPulseSuppressedListener */);
        } else if (isWakeOnReach || isQuickPickup) {
            if (isWakeDisplayEvent) {
                requestPulse(pulseReason, true /* alreadyPerformedProxCheck */,
                        null /* onPulseSuppressedListener */);
            }
        } else {
            proximityCheckThenCall((isNear) -> {
                if (isNear != null && isNear) {
                    // In pocket, drop event.
                    mDozeLog.traceSensorEventDropped(pulseReason, "prox reporting near");
                    return;
                }
                if (isDoubleTap || isTap) {
                    if (screenX != -1 && screenY != -1) {
                        mDozeHost.onSlpiTap(screenX, screenY);
                    }
                    gentleWakeUp(pulseReason);
                } else if (isPickup) {
                    if (shouldDropPickupEvent())  {
                        mDozeLog.traceSensorEventDropped(pulseReason, "keyguard occluded");
                        return;
                    }
                    gentleWakeUp(pulseReason);
                } else if (isUdfpsLongPress) {
                    if (canPulse(mMachine.getState(), true)) {
                        mDozeLog.d("updfsLongPress - setting aodInterruptRunnable to run when "
                                + "the display is on");
                        // Since the gesture won't be received by the UDFPS view, we need to
                        // manually inject an event once the display is ON
                        mAodInterruptRunnable = () ->
                                mAuthController.onAodInterrupt((int) screenX, (int) screenY,
                                        rawValues[3] /* major */, rawValues[4] /* minor */);
                    } else {
                        mDozeLog.d("udfpsLongPress - Not sending aodInterrupt. "
                                + "Unsupported doze state.");
                    }
                    requestPulse(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS, true, null);
                } else {
                    mDozeHost.extendPulse(pulseReason);
                }
            }, true /* alreadyPerformedProxCheck */, pulseReason);
        }

        if (isPickup && !shouldDropPickupEvent()) {
            final long timeSinceNotification =
                    SystemClock.elapsedRealtime() - mNotificationPulseTime;
            final boolean withinVibrationThreshold =
                    timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
            mDozeLog.tracePickupWakeUp(withinVibrationThreshold);
        }
    }

    private boolean shouldDropPickupEvent() {
        return mKeyguardStateController.isOccluded();
    }

    private void gentleWakeUp(@DozeLog.Reason int reason) {
        // Log screen wake up reason (lift/pickup, tap, double-tap)
        Optional.ofNullable(DozingUpdateUiEvent.fromReason(reason))
                .ifPresent(uiEventEnum -> mUiEventLogger.log(uiEventEnum, getKeyguardSessionId()));
        if (mDozeParameters.getDisplayNeedsBlanking()) {
            // Let's prepare the display to wake-up by drawing black.
            // This will cover the hardware wake-up sequence, where the display
            // becomes black for a few frames.
            mDozeHost.setAodDimmingScrim(1f);
        }
        mMachine.wakeUp(reason);
    }

    private void onProximityFar(boolean far) {
        // Proximity checks are asynchronous and the user might have interacted with the phone
        // when a new event is arriving. This means that a state transition might have happened
        // and the proximity check is now obsolete.
        if (mMachine.isExecutingTransition()) {
            mDozeLog.d("onProximityFar called during transition. Ignoring sensor response.");
            return;
        }

        final boolean near = !far;
        final DozeMachine.State state = mMachine.getState();
        final boolean paused = (state == DozeMachine.State.DOZE_AOD_PAUSED);
        final boolean pausing = (state == DozeMachine.State.DOZE_AOD_PAUSING);
        final boolean aod = (state == DozeMachine.State.DOZE_AOD);

        if (state == DozeMachine.State.DOZE_PULSING
                || state == DozeMachine.State.DOZE_PULSING_BRIGHT) {
            mDozeLog.traceSetIgnoreTouchWhilePulsing(near);
            mDozeHost.onIgnoreTouchWhilePulsing(near);
        }

        if (far && (paused || pausing)) {
            mDozeLog.d("Prox FAR, unpausing AOD");
            mMachine.requestState(DozeMachine.State.DOZE_AOD);
        } else if (near && aod) {
            mDozeLog.d("Prox NEAR, starting pausing AOD countdown");
            mMachine.requestState(DozeMachine.State.DOZE_AOD_PAUSING);
        }
    }

    /**
     * When a wake screen event is received from a sensor
     * @param wake {@code true} when it's time to wake up, {@code false} when we should sleep.
     * @param state The current state, or null if the state could not be determined due to enqueued
     *              transitions.
     */
    private void onWakeScreen(boolean wake, @Nullable DozeMachine.State state, int reason) {
        mDozeLog.traceWakeDisplay(wake, reason);
        sWakeDisplaySensorState = wake;

        if (wake) {
            proximityCheckThenCall((isNear) -> {
                if (isNear != null && isNear) {
                    // In pocket, drop event.
                    return;
                }
                if (state == DozeMachine.State.DOZE) {
                    mMachine.requestState(DozeMachine.State.DOZE_AOD);
                    // Log sensor triggered
                    Optional.ofNullable(DozingUpdateUiEvent.fromReason(reason))
                            .ifPresent(uiEventEnum ->
                                    mUiEventLogger.log(uiEventEnum, getKeyguardSessionId()));
                }
            }, false /* alreadyPerformedProxCheck */, reason);
        } else {
            boolean paused = (state == DozeMachine.State.DOZE_AOD_PAUSED);
            boolean pausing = (state == DozeMachine.State.DOZE_AOD_PAUSING);

            if (!pausing && !paused) {
                mMachine.requestState(DozeMachine.State.DOZE);
                // log wake timeout
                mUiEventLogger.log(DozingUpdateUiEvent.DOZING_UPDATE_WAKE_TIMEOUT);
            }
        }
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        if (oldState == DOZE_SUSPEND_TRIGGERS && (newState != FINISH
                && newState != UNINITIALIZED)) {
            // Register callbacks that were unregistered when we switched to
            // DOZE_SUSPEND_TRIGGERS state.
            registerCallbacks();
        }
        switch (newState) {
            case INITIALIZED:
                mAodInterruptRunnable = null;
                sWakeDisplaySensorState = true;
                registerCallbacks();
                mDozeSensors.requestTemporaryDisable();
                break;
            case DOZE:
            case DOZE_AOD:
                mAodInterruptRunnable = null;
                mWantProxSensor = newState != DozeMachine.State.DOZE;
                mWantSensors = true;
                mWantTouchScreenSensors = true;
                if (newState == DozeMachine.State.DOZE_AOD && !sWakeDisplaySensorState) {
                    onWakeScreen(false, newState, DozeLog.REASON_SENSOR_WAKE_UP_PRESENCE);
                }
                break;
            case DOZE_AOD_PAUSED:
            case DOZE_AOD_PAUSING:
                mWantProxSensor = true;
                break;
            case DOZE_PULSING:
            case DOZE_PULSING_BRIGHT:
                mWantProxSensor = true;
                mWantTouchScreenSensors = false;
                break;
            case DOZE_AOD_DOCKED:
                mWantProxSensor = false;
                mWantTouchScreenSensors = false;
                break;
            case DOZE_PULSE_DONE:
                mDozeSensors.requestTemporaryDisable();
                break;
            case DOZE_SUSPEND_TRIGGERS:
            case FINISH:
                stopListeningToAllTriggers();
                break;
            default:
        }
        mDozeSensors.setListening(mWantSensors, mWantTouchScreenSensors);
    }

    private void registerCallbacks() {
        mBroadcastReceiver.register(mBroadcastDispatcher);
        mDockManager.addListener(mDockEventListener);
        mDozeHost.addCallback(mHostCallback);
        mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
    }

    private void unregisterCallbacks() {
        mBroadcastReceiver.unregister(mBroadcastDispatcher);
        mDozeHost.removeCallback(mHostCallback);
        mDockManager.removeListener(mDockEventListener);
        mUserTracker.removeCallback(mUserChangedCallback);
    }

    private void stopListeningToAllTriggers() {
        unregisterCallbacks();
        mDozeSensors.setListening(false, false);
        mDozeSensors.setProxListening(false);
        mWantSensors = false;
        mWantProxSensor = false;
        mWantTouchScreenSensors = false;
    }

    @Override
    public void onScreenState(int state) {
        mDozeSensors.onScreenState(state);
        final boolean lowPowerStateOrOff = state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_OFF;
        mDozeSensors.setProxListening(mWantProxSensor && lowPowerStateOrOff);
        mDozeSensors.setListening(mWantSensors, mWantTouchScreenSensors, lowPowerStateOrOff);

        if (mAodInterruptRunnable != null && state == Display.STATE_ON) {
            mAodInterruptRunnable.run();
            mAodInterruptRunnable = null;
        }
    }

    private void requestPulse(final int reason, boolean performedProxCheck,
            Runnable onPulseSuppressedListener) {
        Assert.isMainThread();
        mDozeHost.extendPulse(reason);

        // we can't determine the dozing state if we're currently transitioning
        final DozeMachine.State dozeState =
                mMachine.isExecutingTransition() ? null : mMachine.getState();

        // When already pulsing we're allowed to show the wallpaper directly without
        // requesting a new pulse.
        if (dozeState == DozeMachine.State.DOZE_PULSING
                && reason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH) {
            mMachine.requestState(DozeMachine.State.DOZE_PULSING_BRIGHT);
            return;
        }

        if (!mAllowPulseTriggers || mDozeHost.isPulsePending()
                || !canPulse(dozeState, performedProxCheck)) {
            if (!mAllowPulseTriggers) {
                mDozeLog.tracePulseDropped("requestPulse - !mAllowPulseTriggers");
            } else if (mDozeHost.isPulsePending()) {
                mDozeLog.tracePulseDropped("requestPulse - pulsePending");
            } else if (!canPulse(dozeState, performedProxCheck)) {
                mDozeLog.tracePulseDropped("requestPulse - dozeState cannot pulse", dozeState);
            }
            runIfNotNull(onPulseSuppressedListener);
            return;
        }

        mDozeHost.setPulsePending(true);
        proximityCheckThenCall((isNear) -> {
            if (isNear != null && isNear) {
                // in pocket, abort pulse
                mDozeLog.tracePulseDropped("requestPulse - inPocket");
                mDozeHost.setPulsePending(false);
                runIfNotNull(onPulseSuppressedListener);
            } else {
                // not in pocket, continue pulsing
                final boolean isPulsePending = mDozeHost.isPulsePending();
                mDozeHost.setPulsePending(false);
                if (!isPulsePending || mDozeHost.isPulsingBlocked()
                        || !canPulse(dozeState, performedProxCheck)) {
                    if (!isPulsePending) {
                        mDozeLog.tracePulseDropped("continuePulseRequest - pulse no longer"
                                + " pending, pulse was cancelled before it could start"
                                + " transitioning to pulsing state.");
                    } else if (mDozeHost.isPulsingBlocked()) {
                        mDozeLog.tracePulseDropped("continuePulseRequest - pulsingBlocked");
                    } else if (!canPulse(dozeState, performedProxCheck)) {
                        mDozeLog.tracePulseDropped("continuePulseRequest"
                                + " - doze state cannot pulse", dozeState);
                    }
                    runIfNotNull(onPulseSuppressedListener);
                    return;
                }

                mMachine.requestPulse(reason);
            }
        }, !mDozeParameters.getProxCheckBeforePulse() || performedProxCheck, reason);

        // Logs request pulse reason on AOD screen.
        Optional.ofNullable(DozingUpdateUiEvent.fromReason(reason))
                .ifPresent(uiEventEnum -> mUiEventLogger.log(uiEventEnum, getKeyguardSessionId()));
    }

    private boolean canPulse(DozeMachine.State dozeState, boolean pulsePerformedProximityCheck) {
        final boolean dozePausedOrPausing = dozeState == State.DOZE_AOD_PAUSED
                || dozeState == State.DOZE_AOD_PAUSING;
        return dozeState == DozeMachine.State.DOZE
                || dozeState == DozeMachine.State.DOZE_AOD
                || dozeState == DozeMachine.State.DOZE_AOD_DOCKED
                || (dozePausedOrPausing && pulsePerformedProximityCheck);
    }

    @Nullable
    private InstanceId getKeyguardSessionId() {
        return mSessionTracker.getSessionId(SESSION_KEYGUARD);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println(" mAodInterruptRunnable=" + mAodInterruptRunnable);

        pw.print(" notificationPulseTime=");
        pw.println(Formatter.formatShortElapsedTime(mContext, mNotificationPulseTime));

        pw.println(" DozeHost#isPulsePending=" + mDozeHost.isPulsePending());
        pw.println("DozeSensors:");
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw);
        idpw.increaseIndent();
        mDozeSensors.dump(idpw);
    }

    private class TriggerReceiver extends BroadcastReceiver {
        private boolean mRegistered;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DozeMachine.DEBUG) Log.d(TAG, "Received pulse intent");
                requestPulse(DozeLog.PULSE_REASON_INTENT, false, /* performedProxCheck */
                        null /* onPulseSuppressedListener */);
            }
        }

        public void register(BroadcastDispatcher broadcastDispatcher) {
            if (mRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(PULSE_ACTION);
            broadcastDispatcher.registerReceiver(this, filter);
            mRegistered = true;
        }

        public void unregister(BroadcastDispatcher broadcastDispatcher) {
            if (!mRegistered) {
                return;
            }
            broadcastDispatcher.unregisterReceiver(this);
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
    };
}
