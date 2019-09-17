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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.annotation.IntDef;
import android.content.Context;
import android.hardware.biometrics.BiometricSourceType;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controller which coordinates all the biometric unlocking actions with the UI.
 */
public class BiometricUnlockController extends KeyguardUpdateMonitorCallback {

    private static final String TAG = "BiometricUnlockController";
    private static final boolean DEBUG_BIO_WAKELOCK = KeyguardConstants.DEBUG_BIOMETRIC_WAKELOCK;
    private static final long BIOMETRIC_WAKELOCK_TIMEOUT_MS = 15 * 1000;
    private static final String BIOMETRIC_WAKE_LOCK_NAME = "wake-and-unlock wakelock";

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_NONE,
            MODE_WAKE_AND_UNLOCK,
            MODE_WAKE_AND_UNLOCK_PULSING,
            MODE_SHOW_BOUNCER,
            MODE_ONLY_WAKE,
            MODE_UNLOCK_COLLAPSING,
            MODE_UNLOCK_FADING,
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
     * Mode in which fingerprint unlocks the device.
     */
    public static final int MODE_UNLOCK_COLLAPSING = 5;

    /**
     * Mode in which fingerprint wakes and unlocks the device from a dream.
     */
    public static final int MODE_WAKE_AND_UNLOCK_FROM_DREAM = 6;

    /**
     * Faster mode of dismissing the lock screen when we cross fade to an app
     * (used for keyguard bypass.)
     */
    public static final int MODE_UNLOCK_FADING = 7;

    /**
     * When bouncer is visible and will be dismissed.
     */
    public static final int MODE_DISMISS_BOUNCER = 8;

    /**
     * How much faster we collapse the lockscreen when authenticating with biometric.
     */
    private static final float BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR = 1.1f;

    private final NotificationMediaManager mMediaManager;
    private final PowerManager mPowerManager;
    private final Handler mHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private PowerManager.WakeLock mWakeLock;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarWindowController mStatusBarWindowController;
    private final Context mContext;
    private final int mWakeUpDelay;
    private int mMode;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private DozeScrimController mDozeScrimController;
    private KeyguardViewMediator mKeyguardViewMediator;
    private ScrimController mScrimController;
    private StatusBar mStatusBar;
    private int mPendingAuthenticatedUserId = -1;
    private BiometricSourceType mPendingAuthenticatedBioSourceType = null;
    private boolean mPendingShowBouncer;
    private boolean mHasScreenTurnedOnSinceAuthenticating;
    private boolean mFadedAwayAfterWakeAndUnlock;

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    public BiometricUnlockController(Context context,
            DozeScrimController dozeScrimController,
            KeyguardViewMediator keyguardViewMediator,
            ScrimController scrimController,
            StatusBar statusBar,
            KeyguardStateController keyguardStateController, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardBypassController keyguardBypassController) {
        this(context, dozeScrimController, keyguardViewMediator, scrimController, statusBar,
                keyguardStateController, handler, keyguardUpdateMonitor,
                context.getResources()
                        .getInteger(com.android.internal.R.integer.config_wakeUpDelayDoze),
                keyguardBypassController);
    }

    @VisibleForTesting
    protected BiometricUnlockController(Context context,
            DozeScrimController dozeScrimController, KeyguardViewMediator keyguardViewMediator,
            ScrimController scrimController, StatusBar statusBar,
            KeyguardStateController keyguardStateController, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor, int wakeUpDelay,
            KeyguardBypassController keyguardBypassController) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mUpdateMonitor = keyguardUpdateMonitor;
        mUpdateMonitor.registerCallback(this);
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        Dependency.get(WakefulnessLifecycle.class).addObserver(mWakefulnessObserver);
        Dependency.get(ScreenLifecycle.class).addObserver(mScreenObserver);
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
        mDozeScrimController = dozeScrimController;
        mKeyguardViewMediator = keyguardViewMediator;
        mScrimController = scrimController;
        mStatusBar = statusBar;
        mKeyguardStateController = keyguardStateController;
        mHandler = handler;
        mWakeUpDelay = wakeUpDelay;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardBypassController.setUnlockController(this);
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
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
    public void onBiometricAcquired(BiometricSourceType biometricSourceType) {
        Trace.beginSection("BiometricUnlockController#onBiometricAcquired");
        releaseBiometricWakeLock();
        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (LatencyTracker.isEnabled(mContext)) {
                int action = LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK;
                if (biometricSourceType == BiometricSourceType.FACE) {
                    action = LatencyTracker.ACTION_FACE_WAKE_AND_UNLOCK;
                }
                LatencyTracker.getInstance(mContext).onActionStart(action);
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
    public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
        Trace.beginSection("BiometricUnlockController#onBiometricAuthenticated");
        if (mUpdateMonitor.isGoingToSleep()) {
            mPendingAuthenticatedUserId = userId;
            mPendingAuthenticatedBioSourceType = biometricSourceType;
            Trace.endSection();
            return;
        }
        mMetricsLogger.write(new LogMaker(MetricsEvent.BIOMETRIC_AUTH)
                .setType(MetricsEvent.TYPE_SUCCESS).setSubtype(toSubtype(biometricSourceType)));
        boolean unlockAllowed = mKeyguardBypassController.onBiometricAuthenticated(
                biometricSourceType);
        if (unlockAllowed) {
            mKeyguardViewMediator.userActivity();
            startWakeAndUnlock(biometricSourceType);
        } else {
            Log.d(TAG, "onBiometricAuthenticated aborted by bypass controller");
        }
    }

    public void startWakeAndUnlock(BiometricSourceType biometricSourceType) {
        startWakeAndUnlock(calculateMode(biometricSourceType));
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
            mStatusBarWindowController.setForceDozeBrightness(true);
        }
        // During wake and unlock, we need to draw black before waking up to avoid abrupt
        // brightness changes due to display state transitions.
        boolean alwaysOnEnabled = DozeParameters.getInstance(mContext).getAlwaysOn();
        boolean delayWakeUp = mode == MODE_WAKE_AND_UNLOCK && alwaysOnEnabled && mWakeUpDelay > 0;
        Runnable wakeUp = ()-> {
            if (!wasDeviceInteractive) {
                if (DEBUG_BIO_WAKELOCK) {
                    Log.i(TAG, "bio wakelock: Authenticated, waking up...");
                }
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                        "android.policy:BIOMETRIC");
            }
            if (delayWakeUp) {
                mKeyguardViewMediator.onWakeAndUnlocking();
            }
            Trace.beginSection("release wake-and-unlock");
            releaseBiometricWakeLock();
            Trace.endSection();
        };

        if (!delayWakeUp && mMode != MODE_NONE) {
            wakeUp.run();
        }
        switch (mMode) {
            case MODE_DISMISS_BOUNCER:
            case MODE_UNLOCK_FADING:
                Trace.beginSection("MODE_DISMISS_BOUNCER or MODE_UNLOCK_FADING");
                mStatusBarKeyguardViewManager.notifyKeyguardAuthenticated(
                        false /* strongAuth */);
                Trace.endSection();
                break;
            case MODE_UNLOCK_COLLAPSING:
            case MODE_SHOW_BOUNCER:
                Trace.beginSection("MODE_UNLOCK_COLLAPSING or MODE_SHOW_BOUNCER");
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
                mStatusBarWindowController.setStatusBarFocusable(false);
                if (delayWakeUp) {
                    mHandler.postDelayed(wakeUp, mWakeUpDelay);
                } else {
                    mKeyguardViewMediator.onWakeAndUnlocking();
                }
                if (mStatusBar.getNavigationBarView() != null) {
                    mStatusBar.getNavigationBarView().setWakeAndUnlocking(true);
                }
                Trace.endSection();
                break;
            case MODE_ONLY_WAKE:
            case MODE_NONE:
                break;
        }
        mStatusBar.notifyBiometricAuthModeChanged();
        Trace.endSection();
    }

    private void showBouncer() {
        if (mMode == MODE_SHOW_BOUNCER) {
            mStatusBarKeyguardViewManager.showBouncer(false);
        }
        mStatusBarKeyguardViewManager.animateCollapsePanels(
                BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR);
        mPendingShowBouncer = false;
    }

    @Override
    public void onStartedGoingToSleep(int why) {
        resetMode();
        mFadedAwayAfterWakeAndUnlock = false;
        mPendingAuthenticatedUserId = -1;
        mPendingAuthenticatedBioSourceType = null;
    }

    @Override
    public void onFinishedGoingToSleep(int why) {
        Trace.beginSection("BiometricUnlockController#onFinishedGoingToSleep");
        BiometricSourceType pendingType = mPendingAuthenticatedBioSourceType;
        int pendingUserId = mPendingAuthenticatedUserId;
        if (pendingUserId != -1 && pendingType != null) {
            // Post this to make sure it's executed after the device is fully locked.
            mHandler.post(() -> onBiometricAuthenticated(pendingUserId, pendingType));
        }
        mPendingAuthenticatedUserId = -1;
        mPendingAuthenticatedBioSourceType = null;
        Trace.endSection();
    }

    public boolean hasPendingAuthentication() {
        return mPendingAuthenticatedUserId != -1
                && mUpdateMonitor.isUnlockingWithBiometricAllowed()
                && mPendingAuthenticatedUserId == KeyguardUpdateMonitor.getCurrentUser();
    }

    public int getMode() {
        return mMode;
    }

    private @WakeAndUnlockMode int calculateMode(BiometricSourceType biometricSourceType) {
        if (biometricSourceType == BiometricSourceType.FACE
                || biometricSourceType == BiometricSourceType.IRIS) {
            return calculateModeForPassiveAuth();
        } else {
            return calculateModeForFingerprint();
        }
    }

    private @WakeAndUnlockMode int calculateModeForFingerprint() {
        boolean unlockingAllowed = mUpdateMonitor.isUnlockingWithBiometricAllowed();
        boolean deviceDreaming = mUpdateMonitor.isDreaming();

        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mStatusBarKeyguardViewManager.isShowing()) {
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
        if (mStatusBarKeyguardViewManager.isShowing()) {
            if (mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing() && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed) {
                return MODE_UNLOCK_COLLAPSING;
            } else if (!mStatusBarKeyguardViewManager.isBouncerShowing()) {
                return MODE_SHOW_BOUNCER;
            }
        }
        return MODE_NONE;
    }

    private @WakeAndUnlockMode int calculateModeForPassiveAuth() {
        boolean unlockingAllowed = mUpdateMonitor.isUnlockingWithBiometricAllowed();
        boolean deviceDreaming = mUpdateMonitor.isDreaming();
        boolean bypass = mKeyguardBypassController.getBypassEnabled();

        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mStatusBarKeyguardViewManager.isShowing()) {
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
        if (mStatusBarKeyguardViewManager.isShowing()) {
            if (mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing() && unlockingAllowed) {
                if (bypass && mKeyguardBypassController.canPlaySubtleWindowAnimations()) {
                    return MODE_UNLOCK_FADING;
                } else {
                    return MODE_DISMISS_BOUNCER;
                }
            } else if (unlockingAllowed) {
                return bypass ? MODE_UNLOCK_FADING : MODE_NONE;
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
        cleanup();
    }

    @Override
    public void onBiometricError(int msgId, String errString,
            BiometricSourceType biometricSourceType) {
        mMetricsLogger.write(new LogMaker(MetricsEvent.BIOMETRIC_AUTH)
                .setType(MetricsEvent.TYPE_ERROR).setSubtype(toSubtype(biometricSourceType))
                .addTaggedData(MetricsEvent.FIELD_BIOMETRIC_AUTH_ERROR, msgId));
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
                mStatusBarWindowController.setForceDozeBrightness(false);
            }
        }, StatusBar.FADE_KEYGUARD_DURATION_PULSING);
    }

    public void finishKeyguardFadingAway() {
        if (isWakeAndUnlock()) {
            mFadedAwayAfterWakeAndUnlock = true;
        }
        resetMode();
    }

    private void resetMode() {
        mMode = MODE_NONE;
        mStatusBarWindowController.setForceDozeBrightness(false);
        if (mStatusBar.getNavigationBarView() != null) {
            mStatusBar.getNavigationBarView().setWakeAndUnlocking(false);
        }
        mStatusBar.notifyBiometricAuthModeChanged();
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

    public void dump(PrintWriter pw) {
        pw.println(" BiometricUnlockController:");
        pw.print("   mMode="); pw.println(mMode);
        pw.print("   mWakeLock="); pw.println(mWakeLock);
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
        return isWakeAndUnlock() || mMode == MODE_UNLOCK_COLLAPSING || mMode == MODE_UNLOCK_FADING;
    }

    /**
     * Successful authentication with fingerprint, face, or iris when the lockscreen fades away
     */
    public boolean isUnlockFading() {
        return mMode == MODE_UNLOCK_FADING;
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
}
