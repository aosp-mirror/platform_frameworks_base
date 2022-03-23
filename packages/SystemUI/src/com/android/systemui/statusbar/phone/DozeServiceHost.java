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
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import java.util.ArrayList;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Implementation of DozeHost for SystemUI.
 */
@SysUISingleton
public final class DozeServiceHost implements DozeHost {
    private static final String TAG = "DozeServiceHost";
    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final DozeLog mDozeLog;
    private final PowerManager mPowerManager;
    private boolean mAnimateWakeup;
    private boolean mAnimateScreenOff;
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
    private final HeadsUpManagerPhone mHeadsUpManagerPhone;
    private final BatteryController mBatteryController;
    private final ScrimController mScrimController;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final KeyguardViewMediator mKeyguardViewMediator;
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
    private NotificationPanelViewController mNotificationPanel;
    private View mAmbientIndicationContainer;
    private StatusBar mStatusBar;
    private boolean mSuppressed;

    @Inject
    public DozeServiceHost(DozeLog dozeLog, PowerManager powerManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            DeviceProvisionedController deviceProvisionedController,
            HeadsUpManagerPhone headsUpManagerPhone, BatteryController batteryController,
            ScrimController scrimController,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            KeyguardViewMediator keyguardViewMediator,
            Lazy<AssistManager> assistManagerLazy,
            DozeScrimController dozeScrimController, KeyguardUpdateMonitor keyguardUpdateMonitor,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationShadeWindowController notificationShadeWindowController,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            AuthController authController,
            NotificationIconAreaController notificationIconAreaController) {
        super();
        mDozeLog = dozeLog;
        mPowerManager = powerManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mHeadsUpManagerPhone = headsUpManagerPhone;
        mBatteryController = batteryController;
        mScrimController = scrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mKeyguardViewMediator = keyguardViewMediator;
        mAssistManagerLazy = assistManagerLazy;
        mDozeScrimController = dozeScrimController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPulseExpansionHandler = pulseExpansionHandler;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationWakeUpCoordinator = notificationWakeUpCoordinator;
        mAuthController = authController;
        mNotificationIconAreaController = notificationIconAreaController;
    }

    // TODO: we should try to not pass status bar in here if we can avoid it.

    /**
     * Initialize instance with objects only available later during execution.
     */
    public void initialize(
            StatusBar statusBar,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationShadeWindowViewController notificationShadeWindowViewController,
            NotificationPanelViewController notificationPanel,
            View ambientIndicationContainer) {
        mStatusBar = statusBar;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationPanel = notificationPanel;
        mNotificationShadeWindowViewController = notificationShadeWindowViewController;
        mAmbientIndicationContainer = ambientIndicationContainer;
    }


    @Override
    public String toString() {
        return "PSB.DozeServiceHost[mCallbacks=" + mCallbacks.size() + "]";
    }

    void firePowerSaveChanged(boolean active) {
        for (Callback callback : mCallbacks) {
            callback.onPowerSaveChanged(active);
        }
    }

    void fireNotificationPulse(NotificationEntry entry) {
        Runnable pulseSuppressedListener = () -> {
            entry.setPulseSuppressed(true);
            mNotificationIconAreaController.updateAodNotificationIcons();
        };
        for (Callback callback : mCallbacks) {
            callback.onNotificationAlerted(pulseSuppressedListener);
        }
    }

    boolean getDozingRequested() {
        return mDozingRequested;
    }

    boolean isPulsing() {
        return mPulsing;
    }


    @Override
    public void addCallback(@NonNull Callback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public void startDozing() {
        if (!mDozingRequested) {
            mDozingRequested = true;
            updateDozing();
            mDozeLog.traceDozing(mStatusBarStateController.isDozing());
            mStatusBar.updateIsKeyguard();
        }
    }

    void updateDozing() {
        // When in wake-and-unlock while pulsing, keep dozing state until fully unlocked.
        boolean
                dozing =
                mDozingRequested && mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        || mBiometricUnlockControllerLazy.get().getMode()
                        == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
        // When in wake-and-unlock we may not have received a change to StatusBarState
        // but we still should not be dozing, manually set to false.
        if (mBiometricUnlockControllerLazy.get().getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK) {
            dozing = false;
        }

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

        if (reason == DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN) {
            mScrimController.setWakeLockScreenSensorActive(true);
        }

        boolean passiveAuthInterrupt = reason == DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN
                        && mWakeLockScreenPerformsAuth;
        // Set the state to pulsing, so ScrimController will know what to do once we ask it to
        // execute the transition. The pulse callback will then be invoked when the scrims
        // are black, indicating that StatusBar is ready to present the rest of the UI.
        mPulsing = true;
        mDozeScrimController.pulse(new PulseCallback() {
            @Override
            public void onPulseStarted() {
                callback.onPulseStarted();
                mStatusBar.updateNotificationPanelTouchState();
                setPulsing(true);
            }

            @Override
            public void onPulseFinished() {
                mPulsing = false;
                callback.onPulseFinished();
                mStatusBar.updateNotificationPanelTouchState();
                mScrimController.setWakeLockScreenSensorActive(false);
                setPulsing(false);
            }

            private void setPulsing(boolean pulsing) {
                mStatusBarKeyguardViewManager.setPulsing(pulsing);
                mKeyguardViewMediator.setPulsing(pulsing);
                mNotificationPanel.setPulsing(pulsing);
                mStatusBarStateController.setPulsing(pulsing);
                mIgnoreTouchWhilePulsing = false;
                if (mKeyguardUpdateMonitor != null && passiveAuthInterrupt) {
                    mKeyguardUpdateMonitor.onAuthInterruptDetected(pulsing /* active */);
                }
                mStatusBar.updateScrimController();
                mPulseExpansionHandler.setPulsing(pulsing);
                mNotificationWakeUpCoordinator.setPulsing(pulsing);
            }
        }, reason);
        // DozeScrimController is in pulse state, now let's ask ScrimController to start
        // pulsing and draw the black frame, if necessary.
        mStatusBar.updateScrimController();
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
        mNotificationPanel.dozeTimeTick();
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
    public boolean isBlockingDoze() {
        if (mBiometricUnlockControllerLazy.get().hasPendingAuthentication()) {
            Log.i(StatusBar.TAG, "Blocking AOD because fingerprint has authenticated");
            return true;
        }
        return false;
    }

    @Override
    public void extendPulse(int reason) {
        if (reason == DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN) {
            mScrimController.setWakeLockScreenSensorActive(true);
        }
        if (mDozeScrimController.isPulsing() && mHeadsUpManagerPhone.hasNotifications()) {
            mHeadsUpManagerPhone.extendHeadsUp();
        } else {
            mDozeScrimController.extendPulse();
        }
    }

    @Override
    public void stopPulsing() {
        if (mDozeScrimController.isPulsing()) {
            mDozeScrimController.pulseOutNow();
        }
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
    public void setAnimateScreenOff(boolean animateScreenOff) {
        mAnimateScreenOff = animateScreenOff;
    }

    @Override
    public void onSlpiTap(float screenX, float screenY) {
        if (screenX > 0 && screenY > 0 && mAmbientIndicationContainer != null
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
        mStatusBar.updateScrimController();
    }

    @Override
    public void cancelGentleSleep() {
        mPendingScreenOffCallback = null;
        if (mScrimController.getState() == ScrimState.OFF) {
            mStatusBar.updateScrimController();
        }
    }

    /**
     * When the dozing host is waiting for scrims to fade out to change the display state.
     */
    boolean hasPendingScreenOffCallback() {
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

    boolean shouldAnimateScreenOff() {
        return mAnimateScreenOff;
    }

    boolean getIgnoreTouchWhilePulsing() {
        return mIgnoreTouchWhilePulsing;
    }

    void setDozeSuppressed(boolean suppressed) {
        if (suppressed == mSuppressed) {
            return;
        }
        mSuppressed = suppressed;
        mDozeLog.traceDozingSuppressed(mSuppressed);
        for (Callback callback : mCallbacks) {
            callback.onDozeSuppressedChanged(suppressed);
        }
    }

    public boolean isDozeSuppressed() {
        return mSuppressed;
    }
}
