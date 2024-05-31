/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;

import android.annotation.NonNull;
import android.graphics.Point;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.DozeInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.util.Assert;
import com.android.systemui.util.CopyOnLoopListenerSet;
import com.android.systemui.util.IListenerSet;

import dagger.Lazy;

import kotlinx.coroutines.ExperimentalCoroutinesApi;

import javax.inject.Inject;

/**
 * Implementation of DozeHost for SystemUI.
 */
@ExperimentalCoroutinesApi @SysUISingleton
public final class DozeServiceHost implements DozeHost {
    private static final String TAG = "DozeServiceHost";
    private final IListenerSet<Callback> mCallbacks = new CopyOnLoopListenerSet<>();
    private final DozeLog mDozeLog;
    private final PowerManager mPowerManager;
    private boolean mAnimateWakeup;
    private boolean mIgnoreTouchWhilePulsing;
    private Runnable mPendingScreenOffCallback;
    @VisibleForTesting
    boolean mWakeLockScreenPerformsAuth = SystemProperties.getBoolean(
            "persist.sysui.wake_performs_auth", true);
    private boolean mDozingRequested;
    private boolean mPulsing;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final HeadsUpManager mHeadsUpManager;
    private final BatteryController mBatteryController;
    private final ScrimController mScrimController;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final DozeScrimController mDozeScrimController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final AuthController mAuthController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final ShadeLockscreenInteractor mShadeLockscreenInteractor;
    private View mAmbientIndicationContainer;
    private CentralSurfaces mCentralSurfaces;
    private boolean mAlwaysOnSuppressed;
    private boolean mPulsePending;
    private DozeInteractor mDozeInteractor;

    @Inject
    public DozeServiceHost(DozeLog dozeLog, PowerManager powerManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            DeviceProvisionedController deviceProvisionedController,
            HeadsUpManager headsUpManager, BatteryController batteryController,
            ScrimController scrimController,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            Lazy<AssistManager> assistManagerLazy,
            DozeScrimController dozeScrimController, KeyguardUpdateMonitor keyguardUpdateMonitor,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationShadeWindowController notificationShadeWindowController,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            AuthController authController,
            NotificationIconAreaController notificationIconAreaController,
            ShadeLockscreenInteractor shadeLockscreenInteractor,
            DozeInteractor dozeInteractor) {
        super();
        mDozeLog = dozeLog;
        mPowerManager = powerManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mHeadsUpManager = headsUpManager;
        mBatteryController = batteryController;
        mScrimController = scrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mAssistManagerLazy = assistManagerLazy;
        mDozeScrimController = dozeScrimController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPulseExpansionHandler = pulseExpansionHandler;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationWakeUpCoordinator = notificationWakeUpCoordinator;
        mAuthController = authController;
        mNotificationIconAreaController = notificationIconAreaController;
        mShadeLockscreenInteractor = shadeLockscreenInteractor;
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        mDozeInteractor = dozeInteractor;
    }

    // TODO: we should try to not pass status bar in here if we can avoid it.

    /**
     * Initialize instance with objects only available later during execution.
     */
    public void initialize(
            CentralSurfaces centralSurfaces,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationShadeWindowViewController notificationShadeWindowViewController,
            View ambientIndicationContainer) {
        mCentralSurfaces = centralSurfaces;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationShadeWindowViewController = notificationShadeWindowViewController;
        mAmbientIndicationContainer = ambientIndicationContainer;
    }


    @Override
    public String toString() {
        return "PSB.DozeServiceHost[mCallbacks=" + mCallbacks.size() + "]";
    }

    void firePowerSaveChanged(boolean active) {
        Assert.isMainThread();
        for (Callback callback : mCallbacks) {
            callback.onPowerSaveChanged(active);
        }
    }

    /**
     * Notify the registered callback about SPFS fingerprint acquisition started event.
     */
    public void fireSideFpsAcquisitionStarted() {
        Assert.isMainThread();
        for (Callback callback : mCallbacks) {
            callback.onSideFingerprintAcquisitionStarted();
        }
    }

    void fireNotificationPulse(NotificationEntry entry) {
        Runnable pulseSuppressedListener = () -> {
            if (NotificationIconContainerRefactor.isEnabled()) {
                mHeadsUpManager.removeNotification(
                        entry.getKey(), /* releaseImmediately= */ true, /* animate= */ false);
            } else {
                entry.setPulseSuppressed(true);
                mNotificationIconAreaController.updateAodNotificationIcons();
            }
        };
        Assert.isMainThread();
        for (Callback callback : mCallbacks) {
            callback.onNotificationAlerted(pulseSuppressedListener);
        }
    }

    boolean getDozingRequested() {
        return mDozingRequested;
    }

    public boolean isPulsing() {
        return mPulsing;
    }


    @Override
    public void addCallback(@NonNull Callback callback) {
        Assert.isMainThread();
        mCallbacks.addIfAbsent(callback);
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        Assert.isMainThread();
        mCallbacks.remove(callback);
    }

    @Override
    public void startDozing() {
        if (!mDozingRequested) {
            mDozingRequested = true;
            updateDozing();
            mDozeLog.traceDozing(mStatusBarStateController.isDozing());
            mCentralSurfaces.updateIsKeyguard();
        }
    }

    void updateDozing() {
        Assert.isMainThread();

        boolean dozing;
        if (SceneContainerFlag.isEnabled()) {
            dozing = mDozingRequested && mDozeInteractor.canDozeFromCurrentScene();
        } else {
            dozing = mDozingRequested
                    && mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        }

        // When in wake-and-unlock we may not have received a change to StatusBarState
        // but we still should not be dozing, manually set to false.
        if (mBiometricUnlockControllerLazy.get().getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK) {
            dozing = false;
        }

        for (Callback callback : mCallbacks) {
            callback.onDozingChanged(dozing);
        }
        mDozeInteractor.setIsDozing(dozing);
        mStatusBarStateController.setIsDozing(dozing);
    }

    @Override
    public void pulseWhileDozing(@NonNull PulseCallback callback, int reason) {
        if (reason == DozeLog.PULSE_REASON_SENSOR_LONG_PRESS) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                                 "com.android.systemui:LONG_PRESS");
            mAssistManagerLazy.get().startAssist(new Bundle());
            return;
        }

        if (reason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH) {
            mScrimController.setWakeLockScreenSensorActive(true);
        }

        boolean passiveAuthInterrupt = reason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH
                        && mWakeLockScreenPerformsAuth;
        // Set the state to pulsing, so ScrimController will know what to do once we ask it to
        // execute the transition. The pulse callback will then be invoked when the scrims
        // are black, indicating that CentralSurfaces is ready to present the rest of the UI.
        mPulsing = true;
        mDozeScrimController.pulse(new PulseCallback() {
            @Override
            public void onPulseStarted() {
                callback.onPulseStarted(); // requestState(DozeMachine.State.DOZE_PULSING)
                mCentralSurfaces.updateNotificationPanelTouchState();
                setPulsing(true);
            }

            @Override
            public void onPulseFinished() {
                mPulsing = false;
                callback.onPulseFinished(); // requestState(DozeMachine.State.DOZE_PULSE_DONE)
                mCentralSurfaces.updateNotificationPanelTouchState();
                mScrimController.setWakeLockScreenSensorActive(false);
                setPulsing(false);
            }

            private void setPulsing(boolean pulsing) {
                mStatusBarKeyguardViewManager.setPulsing(pulsing);
                mShadeLockscreenInteractor.setPulsing(pulsing);
                mStatusBarStateController.setPulsing(pulsing);
                mIgnoreTouchWhilePulsing = false;
                if (mKeyguardUpdateMonitor != null && passiveAuthInterrupt) {
                    mKeyguardUpdateMonitor.onAuthInterruptDetected(pulsing /* active */);
                }
                mCentralSurfaces.updateScrimController();
                mPulseExpansionHandler.setPulsing(pulsing);
                mNotificationWakeUpCoordinator.setPulsing(pulsing);
            }
        }, reason);
        // DozeScrimController is in pulse state, now let's ask ScrimController to start
        // pulsing and draw the black frame, if necessary.
        mCentralSurfaces.updateScrimController();
    }

    @Override
    public void stopDozing() {
        if (mDozingRequested) {
            mDozingRequested = false;
            updateDozing();
            mDozeLog.traceDozing(mStatusBarStateController.isDozing());
        }
    }

    @Override
    public void onIgnoreTouchWhilePulsing(boolean ignore) {
        if (ignore != mIgnoreTouchWhilePulsing) {
            mDozeLog.tracePulseTouchDisabledByProx(ignore);
        }
        mIgnoreTouchWhilePulsing = ignore;
        if (mStatusBarStateController.isDozing() && ignore) {
            mNotificationShadeWindowViewController.cancelCurrentTouch();
        }
    }

    @Override
    public void dozeTimeTick() {
        mDozeInteractor.dozeTimeTick();
        mShadeLockscreenInteractor.dozeTimeTick();
        mAuthController.dozeTimeTick();
        if (mAmbientIndicationContainer instanceof DozeReceiver) {
            ((DozeReceiver) mAmbientIndicationContainer).dozeTimeTick();
        }
    }

    @Override
    public boolean isPowerSaveActive() {
        return mBatteryController.isAodPowerSave();
    }

    @Override
    public boolean isPulsingBlocked() {
        return mBiometricUnlockControllerLazy.get().getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
    }

    @Override
    public boolean isProvisioned() {
        return mDeviceProvisionedController.isDeviceProvisioned()
                && mDeviceProvisionedController.isCurrentUserSetup();
    }

    @Override
    public void extendPulse(int reason) {
        if (reason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH) {
            mScrimController.setWakeLockScreenSensorActive(true);
        }
        if (mDozeScrimController.isPulsing() && mHeadsUpManager.hasNotifications()) {
            mHeadsUpManager.extendHeadsUp();
        } else {
            mDozeScrimController.extendPulse();
        }
    }

    @Override
    public void stopPulsing() {
        setPulsePending(false); // prevent any pending pulses from continuing
        mDozeScrimController.pulseOutNow();
    }

    @Override
    public void setAnimateWakeup(boolean animateWakeup) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING) {
            // Too late to change the wakeup animation.
            return;
        }
        mAnimateWakeup = animateWakeup;
    }

    @Override
    public void onSlpiTap(float screenX, float screenY) {
        if (screenX < 0 || screenY < 0) return;
        dispatchTouchEventToAmbientIndicationContainer(screenX, screenY);

        mDozeInteractor.setLastTapToWakePosition(new Point((int) screenX, (int) screenY));
    }

    private void dispatchTouchEventToAmbientIndicationContainer(float screenX, float screenY) {
        if (mAmbientIndicationContainer != null
                && mAmbientIndicationContainer.getVisibility() == View.VISIBLE) {
            int[] locationOnScreen = new int[2];
            mAmbientIndicationContainer.getLocationOnScreen(locationOnScreen);
            float viewX = screenX - locationOnScreen[0];
            float viewY = screenY - locationOnScreen[1];
            if (0 <= viewX && viewX <= mAmbientIndicationContainer.getWidth()
                    && 0 <= viewY && viewY <= mAmbientIndicationContainer.getHeight()) {

                // Dispatch a tap
                long now = SystemClock.elapsedRealtime();
                MotionEvent ev = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_DOWN, screenX, screenY, 0);
                mAmbientIndicationContainer.dispatchTouchEvent(ev);
                ev.recycle();
                ev = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_UP, screenX, screenY, 0);
                mAmbientIndicationContainer.dispatchTouchEvent(ev);
                ev.recycle();
            }
        }
    }

    @Override
    public void setDozeScreenBrightness(int brightness) {
        mDozeLog.traceDozeScreenBrightness(brightness);
        mNotificationShadeWindowController.setDozeScreenBrightness(brightness);
    }

    @Override
    public void setAodDimmingScrim(float scrimOpacity) {
        mDozeLog.traceSetAodDimmingScrim(scrimOpacity);
        mScrimController.setAodFrontScrimAlpha(scrimOpacity);
    }

    @Override
    public void prepareForGentleSleep(Runnable onDisplayOffCallback) {
        if (mPendingScreenOffCallback != null) {
            Log.w(TAG, "Overlapping onDisplayOffCallback. Ignoring previous one.");
        }
        mPendingScreenOffCallback = onDisplayOffCallback;
        mCentralSurfaces.updateScrimController();
    }

    @Override
    public void cancelGentleSleep() {
        mPendingScreenOffCallback = null;
        if (mScrimController.getState() == ScrimState.OFF) {
            mCentralSurfaces.updateScrimController();
        }
    }

    /**
     * When the dozing host is waiting for scrims to fade out to change the display state.
     */
    public boolean hasPendingScreenOffCallback() {
        return mPendingScreenOffCallback != null;
    }

    /**
     * Executes an nullifies the pending display state callback.
     *
     * @see #hasPendingScreenOffCallback()
     * @see #prepareForGentleSleep(Runnable)
     */
    void executePendingScreenOffCallback() {
        if (mPendingScreenOffCallback == null) {
            return;
        }
        mPendingScreenOffCallback.run();
        mPendingScreenOffCallback = null;
    }

    boolean shouldAnimateWakeup() {
        return mAnimateWakeup;
    }

    boolean getIgnoreTouchWhilePulsing() {
        return mIgnoreTouchWhilePulsing;
    }

    /**
     * Suppresses always-on-display and waking up the display for notifications.
     * Does not disable wakeup gestures like pickup and tap.
     */
    void setAlwaysOnSuppressed(boolean suppressed) {
        if (suppressed == mAlwaysOnSuppressed) {
            return;
        }
        mAlwaysOnSuppressed = suppressed;
        Assert.isMainThread();
        for (Callback callback : mCallbacks) {
            callback.onAlwaysOnSuppressedChanged(suppressed);
        }
    }

    @Override
    public boolean isPulsePending() {
        return mPulsePending;
    }

    @Override
    public void setPulsePending(boolean isPulsePending) {
        mPulsePending = isPulsePending;
    }

    /**
     * Whether always-on-display is being suppressed. This does not affect wakeup gestures like
     * pickup and tap.
     */
    public boolean isAlwaysOnSuppressed() {
        return mAlwaysOnSuppressed;
    }

    final OnHeadsUpChangedListener mOnHeadsUpChangedListener = new OnHeadsUpChangedListener() {
        @Override
        public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
            if (mStatusBarStateController.isDozing() && isHeadsUp) {
                entry.setPulseSuppressed(false);
                fireNotificationPulse(entry);
                if (isPulsing()) {
                    mDozeScrimController.cancelPendingPulseTimeout();
                }
            }
            if (!isHeadsUp && !mHeadsUpManager.hasNotifications()) {
                // There are no longer any notifications to show.  We should end the
                // pulse now.
                stopPulsing();
            }
        }
    };
}
