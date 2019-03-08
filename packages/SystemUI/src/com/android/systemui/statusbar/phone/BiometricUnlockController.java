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

import android.content.Context;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;

/**
 * Controller which coordinates all the biometric unlocking actions with the UI.
 */
public class BiometricUnlockController extends KeyguardUpdateMonitorCallback {

    private static final String TAG = "BiometricUnlockController";
    private static final boolean DEBUG_BIO_WAKELOCK = KeyguardConstants.DEBUG_BIOMETRIC_WAKELOCK;
    private static final long BIOMETRIC_WAKELOCK_TIMEOUT_MS = 15 * 1000;
    private static final String BIOMETRIC_WAKE_LOCK_NAME = "wake-and-unlock wakelock";

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
    public static final int MODE_UNLOCK = 5;

    /**
     * Mode in which fingerprint brings up the bouncer because fingerprint unlocking is currently
     * not allowed.
     */
    public static final int MODE_DISMISS_BOUNCER = 6;

    /**
     * Mode in which fingerprint wakes and unlocks the device from a dream.
     */
    public static final int MODE_WAKE_AND_UNLOCK_FROM_DREAM = 7;

    /**
     * How much faster we collapse the lockscreen when authenticating with biometric.
     */
    private static final float BIOMETRIC_COLLAPSE_SPEEDUP_FACTOR = 1.1f;

    /**
     * If face unlock dismisses the lock screen or keeps user on keyguard by default on this device.
     */
    private final boolean mFaceDismissesKeyguardByDefault;

    /**
     * If face unlock dismisses the lock screen or keeps user on keyguard for the current user.
     */
    @VisibleForTesting
    protected boolean mFaceDismissesKeyguard;

    private final NotificationMediaManager mMediaManager;
    private final PowerManager mPowerManager;
    private final Handler mHandler;
    private PowerManager.WakeLock mWakeLock;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final UnlockMethodCache mUnlockMethodCache;
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

    private final TunerService.Tunable mFaceDismissedKeyguardTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            int defaultValue = mFaceDismissesKeyguardByDefault ? 1 : 0;
            mFaceDismissesKeyguard = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
                    defaultValue, KeyguardUpdateMonitor.getCurrentUser()) != 0;
        }
    };

    public BiometricUnlockController(Context context,
            DozeScrimController dozeScrimController,
            KeyguardViewMediator keyguardViewMediator,
            ScrimController scrimController,
            StatusBar statusBar,
            UnlockMethodCache unlockMethodCache, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            TunerService tunerService) {
        this(context, dozeScrimController, keyguardViewMediator, scrimController, statusBar,
                unlockMethodCache, handler, keyguardUpdateMonitor, tunerService,
                context.getResources()
                        .getInteger(com.android.internal.R.integer.config_wakeUpDelayDoze),
                context.getResources().getBoolean(R.bool.config_faceAuthDismissesKeyguard));
    }

    @VisibleForTesting
    protected BiometricUnlockController(Context context,
                                     DozeScrimController dozeScrimController,
                                     KeyguardViewMediator keyguardViewMediator,
                                     ScrimController scrimController,
                                     StatusBar statusBar,
                                     UnlockMethodCache unlockMethodCache, Handler handler,
                                     KeyguardUpdateMonitor keyguardUpdateMonitor,
                                     TunerService tunerService,
                                     int wakeUpDelay,
                                     boolean faceDismissesKeyguard) {
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
        mUnlockMethodCache = unlockMethodCache;
        mHandler = handler;
        mWakeUpDelay = wakeUpDelay;
        mFaceDismissesKeyguardByDefault = faceDismissesKeyguard;
        tunerService.addTunable(mFaceDismissedKeyguardTunable,
                Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD);
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
        startWakeAndUnlock(calculateMode(biometricSourceType));
    }

    public void startWakeAndUnlock(int mode) {
        // TODO(b/62444020): remove when this bug is fixed
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

        if (!delayWakeUp) {
            wakeUp.run();
        }
        switch (mMode) {
            case MODE_DISMISS_BOUNCER:
                Trace.beginSection("MODE_DISMISS");
                mStatusBarKeyguardViewManager.notifyKeyguardAuthenticated(
                        false /* strongAuth */);
                Trace.endSection();
                break;
            case MODE_UNLOCK:
            case MODE_SHOW_BOUNCER:
                Trace.beginSection("MODE_UNLOCK or MODE_SHOW_BOUNCER");
                if (!wasDeviceInteractive) {
                    mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
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
        mPendingAuthenticatedUserId = -1;
        mPendingAuthenticatedBioSourceType = null;
    }

    @Override
    public void onFinishedGoingToSleep(int why) {
        Trace.beginSection("BiometricUnlockController#onFinishedGoingToSleep");
        if (mPendingAuthenticatedUserId != -1) {

            // Post this to make sure it's executed after the device is fully locked.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onBiometricAuthenticated(mPendingAuthenticatedUserId,
                            mPendingAuthenticatedBioSourceType);
                }
            });
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

    private int calculateMode(BiometricSourceType biometricSourceType) {
        boolean unlockingAllowed = mUpdateMonitor.isUnlockingWithBiometricAllowed();
        boolean deviceDreaming = mUpdateMonitor.isDreaming();
        boolean faceStayingOnKeyguard = biometricSourceType == BiometricSourceType.FACE
                && !mFaceDismissesKeyguard;

        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mStatusBarKeyguardViewManager.isShowing()) {
                return MODE_ONLY_WAKE;
            } else if (mDozeScrimController.isPulsing() && unlockingAllowed) {
                return faceStayingOnKeyguard ? MODE_NONE : MODE_WAKE_AND_UNLOCK_PULSING;
            } else if (unlockingAllowed || !mUnlockMethodCache.isMethodSecure()) {
                return MODE_WAKE_AND_UNLOCK;
            } else {
                return MODE_SHOW_BOUNCER;
            }
        }
        if (unlockingAllowed && deviceDreaming && !faceStayingOnKeyguard) {
            return MODE_WAKE_AND_UNLOCK_FROM_DREAM;
        }
        if (mStatusBarKeyguardViewManager.isShowing()) {
            if (mStatusBarKeyguardViewManager.isBouncerShowing() && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed) {
                return faceStayingOnKeyguard ? MODE_ONLY_WAKE : MODE_UNLOCK;
            } else if (!mStatusBarKeyguardViewManager.isBouncerShowing()) {
                return MODE_SHOW_BOUNCER;
            }
        }
        return MODE_NONE;
    }

    @Override
    public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
        cleanup();
    }

    @Override
    public void onBiometricError(int msgId, String errString,
            BiometricSourceType biometricSourceType) {
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

    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
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
     * Successful authentication with fingerprint, face, or iris when the screen was either
     * on or off.
     */
    public boolean isBiometricUnlock() {
        return isWakeAndUnlock() || mMode == MODE_UNLOCK;
    }
}
