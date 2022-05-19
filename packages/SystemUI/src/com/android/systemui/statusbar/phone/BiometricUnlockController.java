/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.app.StatusBarManager.SESSION_KEYGUARD;

import android.annotation.IntDef;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Controller which coordinates all the biometric unlocking actions with the UI.
 */
@SysUISingleton
public class BiometricUnlockController extends KeyguardUpdateMonitorCallback implements Dumpable {

    private static final String TAG = "BiometricUnlockCtrl";
    private static final boolean DEBUG_BIO_WAKELOCK = KeyguardConstants.DEBUG_BIOMETRIC_WAKELOCK;
    private static final long BIOMETRIC_WAKELOCK_TIMEOUT_MS = 15 * 1000;
    private static final String BIOMETRIC_WAKE_LOCK_NAME = "wake-and-unlock:wakelock";
    private static final UiEventLogger UI_EVENT_LOGGER = new UiEventLoggerImpl();
    private static final int FP_ATTEMPTS_BEFORE_SHOW_BOUNCER = 2;

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_NONE,
            MODE_WAKE_AND_UNLOCK,
            MODE_WAKE_AND_UNLOCK_PULSING,
            MODE_SHOW_BOUNCER,
            MODE_ONLY_WAKE,
            MODE_UNLOCK_COLLAPSING,
            MODE_DISMISS_BOUNCER,
            MODE_WAKE_AND_UNLOCK_FROM_DREAM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WakeAndUnlockMode {}

    /**
     * Mode in which we don't need to wake up the device when we authenticate.
     */
    public static final int MODE_NONE = 0;

    /**
     * Mode in which we wake up the device, and directly dismiss Keyguard. Active when we acquire
     * a fingerprint while the screen is off and the device was sleeping.
     */
    public static final int MODE_WAKE_AND_UNLOCK = 1;

    /**
     * Mode in which we wake the device up, and fade out the Keyguard contents because they were
     * already visible while pulsing in doze mode.
     */
    public static final int MODE_WAKE_AND_UNLOCK_PULSING = 2;

    /**
     * Mode in which we wake up the device, but play the normal dismiss animation. Active when we
     * acquire a fingerprint pulsing in doze mode.
     */
    public static final int MODE_SHOW_BOUNCER = 3;

    /**
     * Mode in which we only wake up the device, and keyguard was not showing when we authenticated.
     * */
    public static final int MODE_ONLY_WAKE = 4;

    /**
     * Mode in which fingerprint unlocks the device or passive auth (ie face auth) unlocks the
     * device while being requested when keyguard is occluded or showing.
     */
    public static final int MODE_UNLOCK_COLLAPSING = 5;

    /**
     * Mode in which fingerprint wakes and unlocks the device from a dream.
     */
    public static final int MODE_WAKE_AND_UNLOCK_FROM_DREAM = 6;

    /**
     * When bouncer is visible and will be dismissed.
     */
    public static final int MODE_DISMISS_BOUNCER = 7;

    /**
     * How much faster we collapse the lockscreen when authenticating with biometric.
     */
    private static final float BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR = 1.1f;

    private final NotificationMediaManager mMediaManager;
    private final PowerManager mPowerManager;
    private final Handler mHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private PowerManager.WakeLock mWakeLock;
    private final ShadeController mShadeController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final DozeParameters mDozeParameters;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final SessionTracker mSessionTracker;
    private final int mConsecutiveFpFailureThreshold;
    private int mMode;
    private BiometricSourceType mBiometricType;
    private KeyguardViewController mKeyguardViewController;
    private DozeScrimController mDozeScrimController;
    private KeyguardViewMediator mKeyguardViewMediator;
    private ScrimController mScrimController;
    private PendingAuthenticated mPendingAuthenticated = null;
    private boolean mPendingShowBouncer;
    private boolean mHasScreenTurnedOnSinceAuthenticating;
    private boolean mFadedAwayAfterWakeAndUnlock;
    private BiometricModeListener mBiometricModeListener;

    private final MetricsLogger mMetricsLogger;
    private final AuthController mAuthController;
    private final StatusBarStateController mStatusBarStateController;
    private final LatencyTracker mLatencyTracker;

    private long mLastFpFailureUptimeMillis;
    private int mNumConsecutiveFpFailures;

    private static final class PendingAuthenticated {
        public final int userId;
        public final BiometricSourceType biometricSourceType;
        public final boolean isStrongBiometric;

        PendingAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            this.userId = userId;
            this.biometricSourceType = biometricSourceType;
            this.isStrongBiometric = isStrongBiometric;
        }
    }

    @VisibleForTesting
    public enum BiometricUiEvent implements UiEventLogger.UiEventEnum {

        @UiEvent(doc = "A biometric event of type fingerprint succeeded.")
        BIOMETRIC_FINGERPRINT_SUCCESS(396),

        @UiEvent(doc = "A biometric event of type fingerprint failed.")
        BIOMETRIC_FINGERPRINT_FAILURE(397),

        @UiEvent(doc = "A biometric event of type fingerprint errored.")
        BIOMETRIC_FINGERPRINT_ERROR(398),

        @UiEvent(doc = "A biometric event of type face unlock succeeded.")
        BIOMETRIC_FACE_SUCCESS(399),

        @UiEvent(doc = "A biometric event of type face unlock failed.")
        BIOMETRIC_FACE_FAILURE(400),

        @UiEvent(doc = "A biometric event of type face unlock errored.")
        BIOMETRIC_FACE_ERROR(401),

        @UiEvent(doc = "A biometric event of type iris succeeded.")
        BIOMETRIC_IRIS_SUCCESS(402),

        @UiEvent(doc = "A biometric event of type iris failed.")
        BIOMETRIC_IRIS_FAILURE(403),

        @UiEvent(doc = "A biometric event of type iris errored.")
        BIOMETRIC_IRIS_ERROR(404),

        @UiEvent(doc = "Bouncer was shown as a result of consecutive failed UDFPS attempts.")
        BIOMETRIC_BOUNCER_SHOWN(916);

        private final int mId;

        BiometricUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }

        static final Map<BiometricSourceType, BiometricUiEvent> ERROR_EVENT_BY_SOURCE_TYPE = Map.of(
                BiometricSourceType.FINGERPRINT, BiometricUiEvent.BIOMETRIC_FINGERPRINT_ERROR,
                BiometricSourceType.FACE, BiometricUiEvent.BIOMETRIC_FACE_ERROR,
                BiometricSourceType.IRIS, BiometricUiEvent.BIOMETRIC_IRIS_ERROR
        );

        static final Map<BiometricSourceType, BiometricUiEvent> SUCCESS_EVENT_BY_SOURCE_TYPE =
                Map.of(
                    BiometricSourceType.FINGERPRINT, BiometricUiEvent.BIOMETRIC_FINGERPRINT_SUCCESS,
                    BiometricSourceType.FACE, BiometricUiEvent.BIOMETRIC_FACE_SUCCESS,
                    BiometricSourceType.IRIS, BiometricUiEvent.BIOMETRIC_IRIS_SUCCESS
                );

        static final Map<BiometricSourceType, BiometricUiEvent> FAILURE_EVENT_BY_SOURCE_TYPE =
                Map.of(
                    BiometricSourceType.FINGERPRINT, BiometricUiEvent.BIOMETRIC_FINGERPRINT_FAILURE,
                    BiometricSourceType.FACE, BiometricUiEvent.BIOMETRIC_FACE_FAILURE,
                    BiometricSourceType.IRIS, BiometricUiEvent.BIOMETRIC_IRIS_FAILURE
                );
    }

    private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final ScreenOffAnimationController mScreenOffAnimationController;

    @Inject
    public BiometricUnlockController(DozeScrimController dozeScrimController,
            KeyguardViewMediator keyguardViewMediator, ScrimController scrimController,
            ShadeController shadeController,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            @Main Resources resources,
            KeyguardBypassController keyguardBypassController, DozeParameters dozeParameters,
            MetricsLogger metricsLogger, DumpManager dumpManager,
            PowerManager powerManager,
            NotificationMediaManager notificationMediaManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            ScreenLifecycle screenLifecycle,
            AuthController authController,
            StatusBarStateController statusBarStateController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SessionTracker sessionTracker,
            LatencyTracker latencyTracker,
            ScreenOffAnimationController screenOffAnimationController) {
        mPowerManager = powerManager;
        mShadeController = shadeController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mDozeParameters = dozeParameters;
        mUpdateMonitor.registerCallback(this);
        mMediaManager = notificationMediaManager;
        mLatencyTracker = latencyTracker;
        wakefulnessLifecycle.addObserver(mWakefulnessObserver);
        screenLifecycle.addObserver(mScreenObserver);

        mNotificationShadeWindowController = notificationShadeWindowController;
        mDozeScrimController = dozeScrimController;
        mKeyguardViewMediator = keyguardViewMediator;
        mScrimController = scrimController;
        mKeyguardStateController = keyguardStateController;
        mHandler = handler;
        mConsecutiveFpFailureThreshold = resources.getInteger(
                R.integer.fp_consecutive_failure_time_ms);
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardBypassController.setUnlockController(this);
        mMetricsLogger = metricsLogger;
        mAuthController = authController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mSessionTracker = sessionTracker;
        mScreenOffAnimationController = screenOffAnimationController;
        dumpManager.registerDumpable(getClass().getName(), this);
    }

    public void setKeyguardViewController(KeyguardViewController keyguardViewController) {
        mKeyguardViewController = keyguardViewController;
    }

    /** Sets a {@link BiometricModeListener}. */
    public void setBiometricModeListener(BiometricModeListener biometricModeListener) {
        mBiometricModeListener = biometricModeListener;
    }

    private final Runnable mReleaseBiometricWakeLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_BIO_WAKELOCK) {
                Log.i(TAG, "biometric wakelock: TIMEOUT!!");
            }
            releaseBiometricWakeLock();
        }
    };

    private void releaseBiometricWakeLock() {
        if (mWakeLock != null) {
            mHandler.removeCallbacks(mReleaseBiometricWakeLockRunnable);
            if (DEBUG_BIO_WAKELOCK) {
                Log.i(TAG, "releasing biometric wakelock");
            }
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    public void onBiometricAcquired(BiometricSourceType biometricSourceType,
            int acquireInfo) {
        if (BiometricSourceType.FINGERPRINT == biometricSourceType
                && acquireInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD) {
            return;
        } else if (BiometricSourceType.FACE == biometricSourceType
                && acquireInfo != BiometricFaceConstants.FACE_ACQUIRED_GOOD) {
            return;
        }
        Trace.beginSection("BiometricUnlockController#onBiometricAcquired");
        releaseBiometricWakeLock();
        if (mStatusBarStateController.isDozing()) {
            if (mLatencyTracker.isEnabled()) {
                int action = LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK;
                if (biometricSourceType == BiometricSourceType.FACE) {
                    action = LatencyTracker.ACTION_FACE_WAKE_AND_UNLOCK;
                }
                mLatencyTracker.onActionStart(action);
            }
            mWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, BIOMETRIC_WAKE_LOCK_NAME);
            Trace.beginSection("acquiring wake-and-unlock");
            mWakeLock.acquire();
            Trace.endSection();
            if (DEBUG_BIO_WAKELOCK) {
                Log.i(TAG, "biometric acquired, grabbing biometric wakelock");
            }
            mHandler.postDelayed(mReleaseBiometricWakeLockRunnable,
                    BIOMETRIC_WAKELOCK_TIMEOUT_MS);
        }
        Trace.endSection();
    }

    private boolean pulsingOrAod() {
        final ScrimState scrimState = mScrimController.getState();
        return scrimState == ScrimState.AOD
                || scrimState == ScrimState.PULSING;
    }

    @Override
    public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) {
        Trace.beginSection("BiometricUnlockController#onBiometricAuthenticated");
        if (mUpdateMonitor.isGoingToSleep()) {
            mPendingAuthenticated = new PendingAuthenticated(userId, biometricSourceType,
                    isStrongBiometric);
            Trace.endSection();
            return;
        }
        mBiometricType = biometricSourceType;
        mMetricsLogger.write(new LogMaker(MetricsEvent.BIOMETRIC_AUTH)
                .setType(MetricsEvent.TYPE_SUCCESS).setSubtype(toSubtype(biometricSourceType)));
        Optional.ofNullable(BiometricUiEvent.SUCCESS_EVENT_BY_SOURCE_TYPE.get(biometricSourceType))
                .ifPresent(event -> UI_EVENT_LOGGER.log(event, getSessionId()));

        boolean unlockAllowed =
                mKeyguardStateController.isOccluded()
                        || mKeyguardBypassController.onBiometricAuthenticated(
                                biometricSourceType, isStrongBiometric);
        if (unlockAllowed) {
            mKeyguardViewMediator.userActivity();
            startWakeAndUnlock(biometricSourceType, isStrongBiometric);
        } else {
            Log.d(TAG, "onBiometricAuthenticated aborted by bypass controller");
        }
    }

    public void startWakeAndUnlock(BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) {
        startWakeAndUnlock(calculateMode(biometricSourceType, isStrongBiometric));
    }

    public void startWakeAndUnlock(@WakeAndUnlockMode int mode) {
        Log.v(TAG, "startWakeAndUnlock(" + mode + ")");
        boolean wasDeviceInteractive = mUpdateMonitor.isDeviceInteractive();
        mMode = mode;
        mHasScreenTurnedOnSinceAuthenticating = false;
        if (mMode == MODE_WAKE_AND_UNLOCK_PULSING && pulsingOrAod()) {
            // If we are waking the device up while we are pulsing the clock and the
            // notifications would light up first, creating an unpleasant animation.
            // Defer changing the screen brightness by forcing doze brightness on our window
            // until the clock and the notifications are faded out.
            mNotificationShadeWindowController.setForceDozeBrightness(true);
        }
        // During wake and unlock, we need to draw black before waking up to avoid abrupt
        // brightness changes due to display state transitions.
        boolean alwaysOnEnabled = mDozeParameters.getAlwaysOn();
        Runnable wakeUp = ()-> {
            if (!wasDeviceInteractive) {
                if (DEBUG_BIO_WAKELOCK) {
                    Log.i(TAG, "bio wakelock: Authenticated, waking up...");
                }
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                        "android.policy:BIOMETRIC");
            }
            Trace.beginSection("release wake-and-unlock");
            releaseBiometricWakeLock();
            Trace.endSection();
        };

        if (mMode != MODE_NONE) {
            wakeUp.run();
        }
        switch (mMode) {
            case MODE_DISMISS_BOUNCER:
                Trace.beginSection("MODE_DISMISS_BOUNCER");
                mKeyguardViewController.notifyKeyguardAuthenticated(
                        false /* strongAuth */);
                Trace.endSection();
                break;
            case MODE_UNLOCK_COLLAPSING:
                Trace.beginSection("MODE_UNLOCK_COLLAPSING");
                if (!wasDeviceInteractive) {
                    mPendingShowBouncer = true;
                } else {
                    // If the keyguard unlock controller is going to handle the unlock animation, it
                    // will fling the panel collapsed when it's ready.
                    if (!mKeyguardUnlockAnimationController.willHandleUnlockAnimation()) {
                        mShadeController.animateCollapsePanels(
                                CommandQueue.FLAG_EXCLUDE_NONE,
                                true /* force */,
                                false /* delayed */,
                                BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR);
                    }
                    mPendingShowBouncer = false;
                    mKeyguardViewController.notifyKeyguardAuthenticated(
                            false /* strongAuth */);
                }
                Trace.endSection();
                break;
            case MODE_SHOW_BOUNCER:
                Trace.beginSection("MODE_SHOW_BOUNCER");
                if (!wasDeviceInteractive) {
                    mPendingShowBouncer = true;
                } else {
                    showBouncer();
                }
                Trace.endSection();
                break;
            case MODE_WAKE_AND_UNLOCK_FROM_DREAM:
            case MODE_WAKE_AND_UNLOCK_PULSING:
            case MODE_WAKE_AND_UNLOCK:
                if (mMode == MODE_WAKE_AND_UNLOCK_PULSING) {
                    Trace.beginSection("MODE_WAKE_AND_UNLOCK_PULSING");
                    mMediaManager.updateMediaMetaData(false /* metaDataChanged */,
                            true /* allowEnterAnimation */);
                } else if (mMode == MODE_WAKE_AND_UNLOCK){
                    Trace.beginSection("MODE_WAKE_AND_UNLOCK");
                } else {
                    Trace.beginSection("MODE_WAKE_AND_UNLOCK_FROM_DREAM");
                    mUpdateMonitor.awakenFromDream();
                }
                mNotificationShadeWindowController.setNotificationShadeFocusable(false);
                mKeyguardViewMediator.onWakeAndUnlocking();
                Trace.endSection();
                break;
            case MODE_ONLY_WAKE:
            case MODE_NONE:
                break;
        }
        onModeChanged(mMode);
        if (mBiometricModeListener != null) {
            mBiometricModeListener.notifyBiometricAuthModeChanged();
        }
        Trace.endSection();
    }

    private void onModeChanged(@WakeAndUnlockMode int mode) {
        if (mBiometricModeListener != null) {
            mBiometricModeListener.onModeChanged(mode);
        }
    }

    private void showBouncer() {
        if (mMode == MODE_SHOW_BOUNCER) {
            mKeyguardViewController.showBouncer(true);
        }
        mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */,
                false /* delayed */, BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR);
        mPendingShowBouncer = false;
    }

    public boolean hasPendingAuthentication() {
        return mPendingAuthenticated != null
                && mUpdateMonitor
                    .isUnlockingWithBiometricAllowed(mPendingAuthenticated.isStrongBiometric)
                && mPendingAuthenticated.userId == KeyguardUpdateMonitor.getCurrentUser();
    }

    public int getMode() {
        return mMode;
    }

    private @WakeAndUnlockMode int calculateMode(BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) {
        if (biometricSourceType == BiometricSourceType.FACE
                || biometricSourceType == BiometricSourceType.IRIS) {
            return calculateModeForPassiveAuth(isStrongBiometric);
        } else {
            return calculateModeForFingerprint(isStrongBiometric);
        }
    }

    private @WakeAndUnlockMode int calculateModeForFingerprint(boolean isStrongBiometric) {
        boolean unlockingAllowed =
                mUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric);
        boolean deviceDreaming = mUpdateMonitor.isDreaming();

        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mKeyguardViewController.isShowing()
                    && !mScreenOffAnimationController.isKeyguardShowDelayed()) {
                return MODE_ONLY_WAKE;
            } else if (mDozeScrimController.isPulsing() && unlockingAllowed) {
                return MODE_WAKE_AND_UNLOCK_PULSING;
            } else if (unlockingAllowed || !mKeyguardStateController.isMethodSecure()) {
                return MODE_WAKE_AND_UNLOCK;
            } else {
                return MODE_SHOW_BOUNCER;
            }
        }
        if (unlockingAllowed && deviceDreaming) {
            return MODE_WAKE_AND_UNLOCK_FROM_DREAM;
        }
        if (mKeyguardViewController.isShowing()) {
            if (mKeyguardViewController.bouncerIsOrWillBeShowing() && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed) {
                return MODE_UNLOCK_COLLAPSING;
            } else if (!mKeyguardViewController.isBouncerShowing()) {
                return MODE_SHOW_BOUNCER;
            }
        }
        return MODE_NONE;
    }

    private @WakeAndUnlockMode int calculateModeForPassiveAuth(boolean isStrongBiometric) {
        boolean unlockingAllowed =
                mUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric);
        boolean deviceDreaming = mUpdateMonitor.isDreaming();
        boolean bypass = mKeyguardBypassController.getBypassEnabled()
                || mAuthController.isUdfpsFingerDown();
        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mKeyguardViewController.isShowing()) {
                return bypass ? MODE_WAKE_AND_UNLOCK : MODE_ONLY_WAKE;
            } else if (!unlockingAllowed) {
                return bypass ? MODE_SHOW_BOUNCER : MODE_NONE;
            } else if (mDozeScrimController.isPulsing()) {
                return bypass ? MODE_WAKE_AND_UNLOCK_PULSING : MODE_ONLY_WAKE;
            } else {
                if (bypass) {
                    // Wake-up fading out nicely
                    return MODE_WAKE_AND_UNLOCK_PULSING;
                } else {
                    // We could theoretically return MODE_NONE, but this means that the device
                    // would be not interactive, unlocked, and the user would not see the device
                    // state.
                    return MODE_ONLY_WAKE;
                }
            }
        }
        if (unlockingAllowed && deviceDreaming) {
            return bypass ? MODE_WAKE_AND_UNLOCK_FROM_DREAM : MODE_ONLY_WAKE;
        }
        if (unlockingAllowed && mKeyguardStateController.isOccluded()) {
            return MODE_UNLOCK_COLLAPSING;
        }
        if (mKeyguardViewController.isShowing()) {
            if ((mKeyguardViewController.bouncerIsOrWillBeShowing()
                    || mKeyguardBypassController.getAltBouncerShowing()) && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed && (bypass || mAuthController.isUdfpsFingerDown())) {
                return MODE_UNLOCK_COLLAPSING;
            } else {
                return bypass ? MODE_SHOW_BOUNCER : MODE_NONE;
            }
        }
        return MODE_NONE;
    }

    @Override
    public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
        mMetricsLogger.write(new LogMaker(MetricsEvent.BIOMETRIC_AUTH)
                .setType(MetricsEvent.TYPE_FAILURE).setSubtype(toSubtype(biometricSourceType)));
        Optional.ofNullable(BiometricUiEvent.FAILURE_EVENT_BY_SOURCE_TYPE.get(biometricSourceType))
                .ifPresent(event -> UI_EVENT_LOGGER.log(event, getSessionId()));

        if (mLatencyTracker.isEnabled()) {
            int action = LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK;
            if (biometricSourceType == BiometricSourceType.FACE) {
                action = LatencyTracker.ACTION_FACE_WAKE_AND_UNLOCK;
            }
            mLatencyTracker.onActionCancel(action);
        }

        if (biometricSourceType == BiometricSourceType.FINGERPRINT
                && mUpdateMonitor.isUdfpsSupported()) {
            long currUptimeMillis = SystemClock.uptimeMillis();
            if (currUptimeMillis - mLastFpFailureUptimeMillis < mConsecutiveFpFailureThreshold) {
                mNumConsecutiveFpFailures += 1;
            } else {
                mNumConsecutiveFpFailures = 1;
            }
            mLastFpFailureUptimeMillis = currUptimeMillis;

            if (mNumConsecutiveFpFailures >= FP_ATTEMPTS_BEFORE_SHOW_BOUNCER) {
                startWakeAndUnlock(MODE_SHOW_BOUNCER);
                UI_EVENT_LOGGER.log(BiometricUiEvent.BIOMETRIC_BOUNCER_SHOWN, getSessionId());
                mNumConsecutiveFpFailures = 0;
            }
        }
        cleanup();
    }

    @Override
    public void onBiometricError(int msgId, String errString,
            BiometricSourceType biometricSourceType) {
        mMetricsLogger.write(new LogMaker(MetricsEvent.BIOMETRIC_AUTH)
                .setType(MetricsEvent.TYPE_ERROR).setSubtype(toSubtype(biometricSourceType))
                .addTaggedData(MetricsEvent.FIELD_BIOMETRIC_AUTH_ERROR, msgId));
        Optional.ofNullable(BiometricUiEvent.ERROR_EVENT_BY_SOURCE_TYPE.get(biometricSourceType))
                .ifPresent(event -> UI_EVENT_LOGGER.log(event, getSessionId()));

        // if we're on the shade and we're locked out, immediately show the bouncer
        if (biometricSourceType == BiometricSourceType.FINGERPRINT
                && (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT
                || msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT)
                && mUpdateMonitor.isUdfpsSupported()
                && (mStatusBarStateController.getState() == StatusBarState.SHADE
                    || mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED)) {
            startWakeAndUnlock(MODE_SHOW_BOUNCER);
            UI_EVENT_LOGGER.log(BiometricUiEvent.BIOMETRIC_BOUNCER_SHOWN, getSessionId());
        }
        cleanup();
    }

    private void cleanup() {
        releaseBiometricWakeLock();
    }

    public void startKeyguardFadingAway() {

        // Disable brightness override when the ambient contents are fully invisible.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mNotificationShadeWindowController.setForceDozeBrightness(false);
            }
        }, CentralSurfaces.FADE_KEYGUARD_DURATION_PULSING);
    }

    public void finishKeyguardFadingAway() {
        if (isWakeAndUnlock()) {
            mFadedAwayAfterWakeAndUnlock = true;
        }
        resetMode();
    }

    private void resetMode() {
        mMode = MODE_NONE;
        mBiometricType = null;
        mNotificationShadeWindowController.setForceDozeBrightness(false);
        if (mBiometricModeListener != null) {
            mBiometricModeListener.onResetMode();
            mBiometricModeListener.notifyBiometricAuthModeChanged();
        }
        mNumConsecutiveFpFailures = 0;
        mLastFpFailureUptimeMillis = 0;
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onFinishedWakingUp() {
                    if (mPendingShowBouncer) {
                        BiometricUnlockController.this.showBouncer();
                    }
                }

                @Override
                public void onStartedGoingToSleep() {
                    resetMode();
                    mFadedAwayAfterWakeAndUnlock = false;
                    mPendingAuthenticated = null;
                }

                @Override
                public void onFinishedGoingToSleep() {
                    Trace.beginSection("BiometricUnlockController#onFinishedGoingToSleep");
                    if (mPendingAuthenticated != null) {
                        PendingAuthenticated pendingAuthenticated = mPendingAuthenticated;
                        // Post this to make sure it's executed after the device is fully locked.
                        mHandler.post(() -> onBiometricAuthenticated(pendingAuthenticated.userId,
                                pendingAuthenticated.biometricSourceType,
                                pendingAuthenticated.isStrongBiometric));
                        mPendingAuthenticated = null;
                    }
                    Trace.endSection();
                }
            };

    private final ScreenLifecycle.Observer mScreenObserver =
            new ScreenLifecycle.Observer() {
                @Override
                public void onScreenTurnedOn() {
                    mHasScreenTurnedOnSinceAuthenticating = true;
                }
            };

    public boolean hasScreenTurnedOnSinceAuthenticating() {
        return mHasScreenTurnedOnSinceAuthenticating;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(" BiometricUnlockController:");
        pw.print("   mMode="); pw.println(mMode);
        pw.print("   mWakeLock="); pw.println(mWakeLock);
        if (mUpdateMonitor.isUdfpsSupported()) {
            pw.print("   mNumConsecutiveFpFailures="); pw.println(mNumConsecutiveFpFailures);
            pw.print("   time since last failure=");
            pw.println(SystemClock.uptimeMillis() - mLastFpFailureUptimeMillis);
        }
    }

    /**
     * Successful authentication with fingerprint, face, or iris that wakes up the device.
     */
    public boolean isWakeAndUnlock() {
        return mMode == MODE_WAKE_AND_UNLOCK
                || mMode == MODE_WAKE_AND_UNLOCK_PULSING
                || mMode == MODE_WAKE_AND_UNLOCK_FROM_DREAM;
    }

    /**
     * Successful authentication with fingerprint, face, or iris that wakes up the device.
     * This will return {@code true} even after the keyguard fades away.
     */
    public boolean unlockedByWakeAndUnlock() {
        return  isWakeAndUnlock() || mFadedAwayAfterWakeAndUnlock;
    }

    /**
     * Successful authentication with fingerprint, face, or iris when the screen was either
     * on or off.
     */
    public boolean isBiometricUnlock() {
        return isWakeAndUnlock() || mMode == MODE_UNLOCK_COLLAPSING;
    }

    /**
     * Successful authentication with fingerprint, face, or iris when the lockscreen fades away
     */
    public BiometricSourceType getBiometricType() {
        return mBiometricType;
    }

    private @Nullable InstanceId getSessionId() {
        return mSessionTracker.getSessionId(SESSION_KEYGUARD);
    }
    /**
     * Translates biometric source type for logging purpose.
     */
    private int toSubtype(BiometricSourceType biometricSourceType) {
        switch (biometricSourceType) {
            case FINGERPRINT:
                return 0;
            case FACE:
                return 1;
            case IRIS:
                return 2;
            default:
                return 3;
        }
    }

    /** An interface to interact with the {@link BiometricUnlockController}. */
    public interface BiometricModeListener {
        /** Called when {@code mMode} is reset to {@link #MODE_NONE}. */
        void onResetMode();
        /** Called when {@code mMode} has changed in {@link #startWakeAndUnlock(int)}. */
        void onModeChanged(@WakeAndUnlockMode int mode);
        /** Called after processing {@link #onModeChanged(int)}. */
        void notifyBiometricAuthModeChanged();
    }
}
