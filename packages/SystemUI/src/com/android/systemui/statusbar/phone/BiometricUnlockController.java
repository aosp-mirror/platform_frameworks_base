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

import static com.android.systemui.keyguard.WakefulnessLifecycle.UNKNOWN_LAST_WAKE_TIME;

import android.annotation.IntDef;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Trace;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.logging.BiometricUnlockLogger;
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
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

/**
 * Controller which coordinates all the biometric unlocking actions with the UI.
 */
@SysUISingleton
public class BiometricUnlockController extends KeyguardUpdateMonitorCallback implements Dumpable {
    private static final long RECENT_POWER_BUTTON_PRESS_THRESHOLD_MS = 400L;
    private static final long BIOMETRIC_WAKELOCK_TIMEOUT_MS = 15 * 1000;
    private static final String BIOMETRIC_WAKE_LOCK_NAME = "wake-and-unlock:wakelock";
    private static final UiEventLogger UI_EVENT_LOGGER = new UiEventLoggerImpl();
    private static final int UDFPS_ATTEMPTS_BEFORE_SHOW_BOUNCER = 3;

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
    private final com.android.systemui.shade.ShadeController mShadeController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final SessionTracker mSessionTracker;
    private final int mConsecutiveFpFailureThreshold;
    private int mMode;
    private BiometricSourceType mBiometricType;
    private KeyguardViewController mKeyguardViewController;
    private DozeScrimController mDozeScrimController;
    private KeyguardViewMediator mKeyguardViewMediator;
    private PendingAuthenticated mPendingAuthenticated = null;
    private boolean mHasScreenTurnedOnSinceAuthenticating;
    private boolean mFadedAwayAfterWakeAndUnlock;
    private Set<BiometricModeListener> mBiometricModeListeners = new HashSet<>();

    private final MetricsLogger mMetricsLogger;
    private final AuthController mAuthController;
    private final StatusBarStateController mStatusBarStateController;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final LatencyTracker mLatencyTracker;
    private final VibratorHelper mVibratorHelper;
    private final BiometricUnlockLogger mLogger;
    private final SystemClock mSystemClock;

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
    public BiometricUnlockController(
            DozeScrimController dozeScrimController,
            KeyguardViewMediator keyguardViewMediator,
            ShadeController shadeController,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            @Main Resources resources,
            KeyguardBypassController keyguardBypassController,
            MetricsLogger metricsLogger, DumpManager dumpManager,
            PowerManager powerManager,
            BiometricUnlockLogger biometricUnlockLogger,
            NotificationMediaManager notificationMediaManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            ScreenLifecycle screenLifecycle,
            AuthController authController,
            StatusBarStateController statusBarStateController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SessionTracker sessionTracker,
            LatencyTracker latencyTracker,
            ScreenOffAnimationController screenOffAnimationController,
            VibratorHelper vibrator,
            SystemClock systemClock
    ) {
        mPowerManager = powerManager;
        mShadeController = shadeController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mUpdateMonitor.registerCallback(this);
        mMediaManager = notificationMediaManager;
        mLatencyTracker = latencyTracker;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        screenLifecycle.addObserver(mScreenObserver);

        mNotificationShadeWindowController = notificationShadeWindowController;
        mDozeScrimController = dozeScrimController;
        mKeyguardViewMediator = keyguardViewMediator;
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
        mVibratorHelper = vibrator;
        mLogger = biometricUnlockLogger;
        mSystemClock = systemClock;

        dumpManager.registerDumpable(getClass().getName(), this);
    }

    public void setKeyguardViewController(KeyguardViewController keyguardViewController) {
        mKeyguardViewController = keyguardViewController;
    }

    /** Adds a {@link BiometricModeListener}. */
    public void addBiometricModeListener(BiometricModeListener listener) {
        mBiometricModeListeners.add(listener);
    }

    /** Removes a {@link BiometricModeListener}. */
    public void removeBiometricModeListener(BiometricModeListener listener) {
        mBiometricModeListeners.remove(listener);
    }

    private final Runnable mReleaseBiometricWakeLockRunnable = new Runnable() {
        @Override
        public void run() {
            mLogger.i("biometric wakelock: TIMEOUT!!");
            releaseBiometricWakeLock();
        }
    };

    private void releaseBiometricWakeLock() {
        if (mWakeLock != null) {
            mHandler.removeCallbacks(mReleaseBiometricWakeLockRunnable);
            mLogger.i("releasing biometric wakelock");
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
            mLogger.i("biometric acquired, grabbing biometric wakelock");
            mHandler.postDelayed(mReleaseBiometricWakeLockRunnable,
                    BIOMETRIC_WAKELOCK_TIMEOUT_MS);
        }
        Trace.endSection();
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
            mLogger.d("onBiometricAuthenticated aborted by bypass controller");
        }
    }

    public void startWakeAndUnlock(BiometricSourceType biometricSourceType,
                                   boolean isStrongBiometric) {
        int mode = calculateMode(biometricSourceType, isStrongBiometric);
        if (mode == MODE_WAKE_AND_UNLOCK
                || mode == MODE_WAKE_AND_UNLOCK_PULSING || mode == MODE_UNLOCK_COLLAPSING
                || mode == MODE_WAKE_AND_UNLOCK_FROM_DREAM || mode == MODE_DISMISS_BOUNCER) {
            vibrateSuccess(biometricSourceType);
        }
        startWakeAndUnlock(mode);
    }

    public void startWakeAndUnlock(@WakeAndUnlockMode int mode) {
        mLogger.logStartWakeAndUnlock(mode);
        boolean wasDeviceInteractive = mUpdateMonitor.isDeviceInteractive();
        mMode = mode;
        mHasScreenTurnedOnSinceAuthenticating = false;
        if (mMode == MODE_WAKE_AND_UNLOCK_PULSING) {
            // If we are waking the device up while we are pulsing the clock and the
            // notifications would light up first, creating an unpleasant animation.
            // Defer changing the screen brightness by forcing doze brightness on our window
            // until the clock and the notifications are faded out.
            mNotificationShadeWindowController.setForceDozeBrightness(true);
        }
        // During wake and unlock, we need to draw black before waking up to avoid abrupt
        // brightness changes due to display state transitions.
        Runnable wakeUp = ()-> {
            if (!wasDeviceInteractive || mUpdateMonitor.isDreaming()) {
                mLogger.i("bio wakelock: Authenticated, waking up...");
                mPowerManager.wakeUp(
                        mSystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_BIOMETRIC,
                        "android.policy:BIOMETRIC"
                );
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
                // If the keyguard unlock controller is going to handle the unlock animation, it
                // will fling the panel collapsed when it's ready.
                if (!mKeyguardUnlockAnimationController.willHandleUnlockAnimation()) {
                    mShadeController.animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_NONE,
                            true /* force */,
                            false /* delayed */,
                            BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR);
                }
                mKeyguardViewController.notifyKeyguardAuthenticated(
                        false /* strongAuth */);
                Trace.endSection();
                break;
            case MODE_SHOW_BOUNCER:
                Trace.beginSection("MODE_SHOW_BOUNCER");
                mKeyguardViewController.showPrimaryBouncer(true);
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
                    // Don't call awaken from Dream here. In order to avoid flickering, wait until
                    // later to awaken.
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
        Trace.endSection();
    }

    private void onModeChanged(@WakeAndUnlockMode int mode) {
        for (BiometricModeListener listener : mBiometricModeListeners) {
            listener.onModeChanged(mode);
        }
    }

    public boolean hasPendingAuthentication() {
        return mPendingAuthenticated != null
                && mUpdateMonitor
                    .isUnlockingWithBiometricAllowed(mPendingAuthenticated.isStrongBiometric)
                && mPendingAuthenticated.userId == KeyguardUpdateMonitor.getCurrentUser();
    }

    public @WakeAndUnlockMode int getMode() {
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
        final boolean unlockingAllowed =
                mUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric);
        final boolean deviceInteractive = mUpdateMonitor.isDeviceInteractive();
        final boolean keyguardShowing = mKeyguardStateController.isShowing();
        final boolean deviceDreaming = mUpdateMonitor.isDreaming();

        logCalculateModeForFingerprint(unlockingAllowed, deviceInteractive,
                keyguardShowing, deviceDreaming, isStrongBiometric);
        if (!deviceInteractive) {
            if (!keyguardShowing && !mScreenOffAnimationController.isKeyguardShowDelayed()) {
                if (mKeyguardStateController.isUnlocked()) {
                    return MODE_WAKE_AND_UNLOCK;
                }
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
        if (keyguardShowing) {
            if (mKeyguardViewController.primaryBouncerIsOrWillBeShowing() && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed) {
                return MODE_UNLOCK_COLLAPSING;
            } else if (!mKeyguardViewController.isBouncerShowing()) {
                return MODE_SHOW_BOUNCER;
            }
        }
        return MODE_NONE;
    }

    private void logCalculateModeForFingerprint(boolean unlockingAllowed, boolean deviceInteractive,
            boolean keyguardShowing, boolean deviceDreaming, boolean strongBiometric) {
        if (unlockingAllowed) {
            mLogger.logCalculateModeForFingerprintUnlockingAllowed(deviceInteractive,
                    keyguardShowing, deviceDreaming);
        } else {
            // if unlocking isn't allowed, log more information about why unlocking may not
            // have been allowed
            final int strongAuthFlags = mUpdateMonitor.getStrongAuthTracker().getStrongAuthForUser(
                    KeyguardUpdateMonitor.getCurrentUser());
            final boolean nonStrongBiometricAllowed =
                    mUpdateMonitor.getStrongAuthTracker()
                            .isNonStrongBiometricAllowedAfterIdleTimeout(
                                    KeyguardUpdateMonitor.getCurrentUser());

            mLogger.logCalculateModeForFingerprintUnlockingNotAllowed(strongBiometric,
                    strongAuthFlags, nonStrongBiometricAllowed, deviceInteractive, keyguardShowing);
        }
    }

    private @WakeAndUnlockMode int calculateModeForPassiveAuth(boolean isStrongBiometric) {
        final boolean deviceInteractive = mUpdateMonitor.isDeviceInteractive();
        final boolean isKeyguardShowing = mKeyguardStateController.isShowing();
        final boolean unlockingAllowed =
                mUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric);
        final boolean deviceDreaming = mUpdateMonitor.isDreaming();
        final boolean bypass = mKeyguardBypassController.getBypassEnabled()
                || mAuthController.isUdfpsFingerDown();

        logCalculateModeForPassiveAuth(unlockingAllowed, deviceInteractive, isKeyguardShowing,
                deviceDreaming, bypass, isStrongBiometric);
        if (!deviceInteractive) {
            if (!isKeyguardShowing) {
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
        if (isKeyguardShowing) {
            if ((mKeyguardViewController.primaryBouncerIsOrWillBeShowing()
                    || mKeyguardBypassController.getAltBouncerShowing()) && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed && bypass) {
                return MODE_UNLOCK_COLLAPSING;
            } else {
                return bypass ? MODE_SHOW_BOUNCER : MODE_NONE;
            }
        }
        return MODE_NONE;
    }

    private void logCalculateModeForPassiveAuth(boolean unlockingAllowed,
            boolean deviceInteractive, boolean keyguardShowing, boolean deviceDreaming,
            boolean bypass, boolean strongBiometric) {
        if (unlockingAllowed) {
            mLogger.logCalculateModeForPassiveAuthUnlockingAllowed(
                    deviceInteractive, keyguardShowing, deviceDreaming, bypass);
        } else {
            // if unlocking isn't allowed, log more information about why unlocking may not
            // have been allowed
            final int strongAuthFlags = mUpdateMonitor.getStrongAuthTracker().getStrongAuthForUser(
                    KeyguardUpdateMonitor.getCurrentUser());
            final boolean nonStrongBiometricAllowed =
                    mUpdateMonitor.getStrongAuthTracker()
                            .isNonStrongBiometricAllowedAfterIdleTimeout(
                                    KeyguardUpdateMonitor.getCurrentUser());

            mLogger.logCalculateModeForPassiveAuthUnlockingNotAllowed(
                    strongBiometric, strongAuthFlags, nonStrongBiometricAllowed,
                    deviceInteractive, keyguardShowing, bypass);
        }
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

        if (!mVibratorHelper.hasVibrator()
                && (!mUpdateMonitor.isDeviceInteractive() || mUpdateMonitor.isDreaming())) {
            mLogger.d("wakeup device on authentication failure (device doesn't have a vibrator)");
            startWakeAndUnlock(MODE_ONLY_WAKE);
        } else if (biometricSourceType == BiometricSourceType.FINGERPRINT
                && mUpdateMonitor.isUdfpsSupported()) {
            long currUptimeMillis = mSystemClock.uptimeMillis();
            if (currUptimeMillis - mLastFpFailureUptimeMillis < mConsecutiveFpFailureThreshold) {
                mNumConsecutiveFpFailures += 1;
            } else {
                mNumConsecutiveFpFailures = 1;
            }
            mLastFpFailureUptimeMillis = currUptimeMillis;

            if (mNumConsecutiveFpFailures >= UDFPS_ATTEMPTS_BEFORE_SHOW_BOUNCER) {
                mLogger.logUdfpsAttemptThresholdMet(mNumConsecutiveFpFailures);
                startWakeAndUnlock(MODE_SHOW_BOUNCER);
                UI_EVENT_LOGGER.log(BiometricUiEvent.BIOMETRIC_BOUNCER_SHOWN, getSessionId());
                mNumConsecutiveFpFailures = 0;
            }
        }

        // Suppress all face auth errors if fingerprint can be used to authenticate
        if ((biometricSourceType == BiometricSourceType.FACE
                && !mUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                KeyguardUpdateMonitor.getCurrentUser()))
                || (biometricSourceType == BiometricSourceType.FINGERPRINT)) {
            vibrateError(biometricSourceType);
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

        final boolean fingerprintLockout = biometricSourceType == BiometricSourceType.FINGERPRINT
                && (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT
                || msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT);
        if (fingerprintLockout) {
            mLogger.d("fingerprint locked out");
            startWakeAndUnlock(MODE_SHOW_BOUNCER);
            UI_EVENT_LOGGER.log(BiometricUiEvent.BIOMETRIC_BOUNCER_SHOWN, getSessionId());
        }

        cleanup();
    }

    // these haptics are for device-entry only
    private void vibrateSuccess(BiometricSourceType type) {
        if (mAuthController.isSfpsEnrolled(KeyguardUpdateMonitor.getCurrentUser())
                && lastWakeupFromPowerButtonWithinHapticThreshold()) {
            mLogger.d("Skip auth success haptic. Power button was recently pressed.");
            return;
        }
        mVibratorHelper.vibrateAuthSuccess(
                getClass().getSimpleName() + ", type =" + type + "device-entry::success");
    }

    private boolean lastWakeupFromPowerButtonWithinHapticThreshold() {
        final boolean lastWakeupFromPowerButton = mWakefulnessLifecycle.getLastWakeReason()
                == PowerManager.WAKE_REASON_POWER_BUTTON;
        return lastWakeupFromPowerButton
                && mWakefulnessLifecycle.getLastWakeTime() != UNKNOWN_LAST_WAKE_TIME
                && mSystemClock.uptimeMillis() - mWakefulnessLifecycle.getLastWakeTime()
                < RECENT_POWER_BUTTON_PRESS_THRESHOLD_MS;
    }

    private void vibrateError(BiometricSourceType type) {
        mVibratorHelper.vibrateAuthError(
                getClass().getSimpleName() + ", type =" + type + "device-entry::error");
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
        for (BiometricModeListener listener : mBiometricModeListeners) {
            listener.onResetMode();
        }
        mNumConsecutiveFpFailures = 0;
        mLastFpFailureUptimeMillis = 0;
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
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
    public void onKeyguardBouncerStateChanged(boolean bouncerIsOrWillBeShowing) {
        // When the bouncer is dismissed, treat this as a reset of the unlock mode. The user
        // may have gone back instead of successfully unlocking
        if (!bouncerIsOrWillBeShowing) {
            resetMode();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(" BiometricUnlockController:");
        pw.print("   mMode="); pw.println(mMode);
        pw.print("   mWakeLock="); pw.println(mWakeLock);
        if (mUpdateMonitor.isUdfpsSupported()) {
            pw.print("   mNumConsecutiveFpFailures="); pw.println(mNumConsecutiveFpFailures);
            pw.print("   time since last failure=");
            pw.println(mSystemClock.uptimeMillis() - mLastFpFailureUptimeMillis);
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
        default void onResetMode() {}
        /** Called when {@code mMode} has changed in {@link #startWakeAndUnlock(int)}. */
        default void onModeChanged(@WakeAndUnlockMode int mode) {}
    }
}
