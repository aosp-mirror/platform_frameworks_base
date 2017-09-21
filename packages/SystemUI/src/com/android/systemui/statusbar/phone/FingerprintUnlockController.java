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
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.LatencyTracker;
import com.android.systemui.Dependency;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import java.io.PrintWriter;

/**
 * Controller which coordinates all the fingerprint unlocking actions with the UI.
 */
public class FingerprintUnlockController extends KeyguardUpdateMonitorCallback {

    private static final String TAG = "FingerprintController";
    private static final boolean DEBUG_FP_WAKELOCK = KeyguardConstants.DEBUG_FP_WAKELOCK;
    private static final long FINGERPRINT_WAKELOCK_TIMEOUT_MS = 15 * 1000;
    private static final String FINGERPRINT_WAKE_LOCK_NAME = "wake-and-unlock wakelock";

    /**
     * Mode in which we don't need to wake up the device when we get a fingerprint.
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
     * Mode in which we only wake up the device, and keyguard was not showing when we acquired a
     * fingerprint.
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
     * How much faster we collapse the lockscreen when authenticating with fingerprint.
     */
    private static final float FINGERPRINT_COLLAPSE_SPEEDUP_FACTOR = 1.1f;

    private PowerManager mPowerManager;
    private Handler mHandler = new Handler();
    private PowerManager.WakeLock mWakeLock;
    private KeyguardUpdateMonitor mUpdateMonitor;
    private int mMode;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private StatusBarWindowManager mStatusBarWindowManager;
    private DozeScrimController mDozeScrimController;
    private KeyguardViewMediator mKeyguardViewMediator;
    private ScrimController mScrimController;
    private StatusBar mStatusBar;
    private final UnlockMethodCache mUnlockMethodCache;
    private final Context mContext;
    private int mPendingAuthenticatedUserId = -1;
    private boolean mPendingShowBouncer;
    private boolean mHasScreenTurnedOnSinceAuthenticating;

    public FingerprintUnlockController(Context context,
            DozeScrimController dozeScrimController,
            KeyguardViewMediator keyguardViewMediator,
            ScrimController scrimController,
            StatusBar statusBar,
            UnlockMethodCache unlockMethodCache) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(this);
        Dependency.get(WakefulnessLifecycle.class).addObserver(mWakefulnessObserver);
        Dependency.get(ScreenLifecycle.class).addObserver(mScreenObserver);
        mStatusBarWindowManager = Dependency.get(StatusBarWindowManager.class);
        mDozeScrimController = dozeScrimController;
        mKeyguardViewMediator = keyguardViewMediator;
        mScrimController = scrimController;
        mStatusBar = statusBar;
        mUnlockMethodCache = unlockMethodCache;
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    private final Runnable mReleaseFingerprintWakeLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_FP_WAKELOCK) {
                Log.i(TAG, "fp wakelock: TIMEOUT!!");
            }
            releaseFingerprintWakeLock();
        }
    };

    private void releaseFingerprintWakeLock() {
        if (mWakeLock != null) {
            mHandler.removeCallbacks(mReleaseFingerprintWakeLockRunnable);
            if (DEBUG_FP_WAKELOCK) {
                Log.i(TAG, "releasing fp wakelock");
            }
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    public void onFingerprintAcquired() {
        Trace.beginSection("FingerprintUnlockController#onFingerprintAcquired");
        releaseFingerprintWakeLock();
        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (LatencyTracker.isEnabled(mContext)) {
                LatencyTracker.getInstance(mContext).onActionStart(
                        LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK);
            }
            mWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, FINGERPRINT_WAKE_LOCK_NAME);
            Trace.beginSection("acquiring wake-and-unlock");
            mWakeLock.acquire();
            Trace.endSection();
            if (DEBUG_FP_WAKELOCK) {
                Log.i(TAG, "fingerprint acquired, grabbing fp wakelock");
            }
            mHandler.postDelayed(mReleaseFingerprintWakeLockRunnable,
                    FINGERPRINT_WAKELOCK_TIMEOUT_MS);
        }
        Trace.endSection();
    }

    private boolean pulsingOrAod() {
        boolean pulsing = mDozeScrimController.isPulsing();
        boolean dozingWithScreenOn = mStatusBar.isDozing() && !mStatusBar.isScreenFullyOff();
        return pulsing || dozingWithScreenOn;
    }

    @Override
    public void onFingerprintAuthenticated(int userId) {
        Trace.beginSection("FingerprintUnlockController#onFingerprintAuthenticated");
        if (mUpdateMonitor.isGoingToSleep()) {
            mPendingAuthenticatedUserId = userId;
            Trace.endSection();
            return;
        }
        startWakeAndUnlock(calculateMode());
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
            mStatusBarWindowManager.setForceDozeBrightness(true);
        }
        if (!wasDeviceInteractive) {
            if (DEBUG_FP_WAKELOCK) {
                Log.i(TAG, "fp wakelock: Authenticated, waking up...");
            }
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.policy:FINGERPRINT");
        }
        Trace.beginSection("release wake-and-unlock");
        releaseFingerprintWakeLock();
        Trace.endSection();
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
                    mStatusBar.updateMediaMetaData(false /* metaDataChanged */,
                            true /* allowEnterAnimation */);
                } else if (mMode == MODE_WAKE_AND_UNLOCK){
                    Trace.beginSection("MODE_WAKE_AND_UNLOCK");
                    mDozeScrimController.abortDoze();
                } else {
                    Trace.beginSection("MODE_WAKE_AND_UNLOCK_FROM_DREAM");
                    mUpdateMonitor.awakenFromDream();
                }
                mStatusBarWindowManager.setStatusBarFocusable(false);
                mKeyguardViewMediator.onWakeAndUnlocking();
                mScrimController.setWakeAndUnlocking();
                mDozeScrimController.setWakeAndUnlocking();
                if (mStatusBar.getNavigationBarView() != null) {
                    mStatusBar.getNavigationBarView().setWakeAndUnlocking(true);
                }
                Trace.endSection();
                break;
            case MODE_ONLY_WAKE:
            case MODE_NONE:
                break;
        }
        mStatusBar.notifyFpAuthModeChanged();
        Trace.endSection();
    }

    private void showBouncer() {
        mStatusBarKeyguardViewManager.animateCollapsePanels(
                FINGERPRINT_COLLAPSE_SPEEDUP_FACTOR);
        mPendingShowBouncer = false;
    }

    @Override
    public void onStartedGoingToSleep(int why) {
        resetMode();
        mPendingAuthenticatedUserId = -1;
    }

    @Override
    public void onFinishedGoingToSleep(int why) {
        Trace.beginSection("FingerprintUnlockController#onFinishedGoingToSleep");
        if (mPendingAuthenticatedUserId != -1) {

            // Post this to make sure it's executed after the device is fully locked.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onFingerprintAuthenticated(mPendingAuthenticatedUserId);
                }
            });
        }
        mPendingAuthenticatedUserId = -1;
        Trace.endSection();
    }

    public boolean hasPendingAuthentication() {
        return mPendingAuthenticatedUserId != -1
                && mUpdateMonitor.isUnlockingWithFingerprintAllowed()
                && mPendingAuthenticatedUserId == KeyguardUpdateMonitor.getCurrentUser();
    }

    public int getMode() {
        return mMode;
    }

    private int calculateMode() {
        boolean unlockingAllowed = mUpdateMonitor.isUnlockingWithFingerprintAllowed();
        boolean deviceDreaming = mUpdateMonitor.isDreaming();

        if (!mUpdateMonitor.isDeviceInteractive()) {
            if (!mStatusBarKeyguardViewManager.isShowing()) {
                return MODE_ONLY_WAKE;
            } else if (mDozeScrimController.isPulsing() && unlockingAllowed) {
                return MODE_WAKE_AND_UNLOCK_PULSING;
            } else if (unlockingAllowed || !mUnlockMethodCache.isMethodSecure()) {
                return MODE_WAKE_AND_UNLOCK;
            } else {
                return MODE_SHOW_BOUNCER;
            }
        }
        if (unlockingAllowed && deviceDreaming) {
            return MODE_WAKE_AND_UNLOCK_FROM_DREAM;
        }
        if (mStatusBarKeyguardViewManager.isShowing()) {
            if (mStatusBarKeyguardViewManager.isBouncerShowing() && unlockingAllowed) {
                return MODE_DISMISS_BOUNCER;
            } else if (unlockingAllowed) {
                return MODE_UNLOCK;
            } else if (!mStatusBarKeyguardViewManager.isBouncerShowing()) {
                return MODE_SHOW_BOUNCER;
            }
        }
        return MODE_NONE;
    }

    @Override
    public void onFingerprintAuthFailed() {
        cleanup();
    }

    @Override
    public void onFingerprintError(int msgId, String errString) {
        cleanup();
    }

    private void cleanup() {
        releaseFingerprintWakeLock();
    }

    public void startKeyguardFadingAway() {

        // Disable brightness override when the ambient contents are fully invisible.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mStatusBarWindowManager.setForceDozeBrightness(false);
            }
        }, StatusBar.FADE_KEYGUARD_DURATION_PULSING);
    }

    public void finishKeyguardFadingAway() {
        resetMode();
    }

    private void resetMode() {
        mMode = MODE_NONE;
        mStatusBarWindowManager.setForceDozeBrightness(false);
        if (mStatusBar.getNavigationBarView() != null) {
            mStatusBar.getNavigationBarView().setWakeAndUnlocking(false);
        }
        mStatusBar.notifyFpAuthModeChanged();
    }

    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedWakingUp() {
            if (mPendingShowBouncer) {
                FingerprintUnlockController.this.showBouncer();
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
        pw.println(" FingerprintUnlockController:");
        pw.print("   mMode="); pw.println(mMode);
        pw.print("   mWakeLock="); pw.println(mWakeLock);
    }
}
