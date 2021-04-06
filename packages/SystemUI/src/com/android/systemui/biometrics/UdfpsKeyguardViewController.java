/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.annotation.NonNull;
import android.hardware.biometrics.BiometricSourceType;

import androidx.annotation.Nullable;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 *
 * Highlights the udfps icon when:
 * - Face authentication has failed
 * - Face authentication has been run for > 2 seconds
 */
public class UdfpsKeyguardViewController extends UdfpsAnimationViewController<UdfpsKeyguardView> {
    private static final long AFTER_FACE_AUTH_HINT_DELAY = 2000;

    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final DelayableExecutor mExecutor;
    @NonNull private final KeyguardViewMediator mKeyguardViewMediator;

    @Nullable private Runnable mCancelRunnable;
    private boolean mShowBouncer;
    private boolean mQsExpanded;
    private boolean mFaceDetectRunning;
    private boolean mHintShown;
    private boolean mTransitioningFromHome;
    private int mStatusBarState;

    protected UdfpsKeyguardViewController(
            @NonNull UdfpsKeyguardView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull StatusBar statusBar,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull DelayableExecutor mainDelayableExecutor,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardViewMediator keyguardViewMediator) {
        super(view, statusBarStateController, statusBar, dumpManager);
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mExecutor = mainDelayableExecutor;
        mKeyguardViewMediator = keyguardViewMediator;
    }

    @Override
    @NonNull String getTag() {
        return "UdfpsKeyguardViewController";
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mHintShown = false;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        updateFaceDetectRunning(mKeyguardUpdateMonitor.isFaceDetectionRunning());

        final float dozeAmount = mStatusBarStateController.getDozeAmount();
        mStatusBarStateController.addCallback(mStateListener);
        mStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
        mStateListener.onStateChanged(mStatusBarStateController.getState());
        mKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mFaceDetectRunning = false;

        mStatusBarStateController.removeCallback(mStateListener);
        mAlternateAuthInterceptor.hideAlternateAuthBouncer();
        mKeyguardViewManager.setAlternateAuthInterceptor(null);
        mTransitioningFromHome = false;

        if (mCancelRunnable != null) {
            mCancelRunnable.run();
            mCancelRunnable = null;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("mShowBouncer=" + mShowBouncer);
        pw.println("mFaceDetectRunning=" + mFaceDetectRunning);
        pw.println("mTransitioningFromHomeToKeyguard=" + mTransitioningFromHome);
    }

    /**
     * Overrides non-bouncer show logic in shouldPauseAuth to still auth.
     */
    private void showBouncer(boolean show) {
        if (mShowBouncer == show) {
            return;
        }

        mShowBouncer = show;
        updatePauseAuth();
        if (mShowBouncer) {
            mView.animateUdfpsBouncer();
        } else {
            mView.animateAwayUdfpsBouncer(() -> mKeyguardViewManager.cancelPostAuthActions());
        }
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication. On the keyguard, we may want to show udfps when the shade
     * is expanded, so this can be overridden with the showBouncer method.
     */
    public boolean shouldPauseAuth() {
        if (mShowBouncer) {
            return false;
        }

        if (mStatusBarState != KEYGUARD) {
            return true;
        }

        if (mTransitioningFromHome && mKeyguardViewMediator.isAnimatingScreenOff()) {
            return true;
        }

        if (mQsExpanded) {
            return true;
        }

        return false;
    }

    private void cancelDelayedHint() {
        if (mCancelRunnable != null) {
            mCancelRunnable.run();
            mCancelRunnable = null;
        }
    }

    private void updateFaceDetectRunning(boolean running) {
        if (mFaceDetectRunning == running) {
            return;
        }

        // show udfps hint a few seconds after face auth started running
        if (!mFaceDetectRunning && running && !mHintShown && mCancelRunnable == null) {
            // Face detect started running, show udfps hint after a delay
            mCancelRunnable = mExecutor.executeDelayed(() -> showHint(false),
                    AFTER_FACE_AUTH_HINT_DELAY);
        }

        mFaceDetectRunning = running;
    }

    private void showHint(boolean forceShow) {
        cancelDelayedHint();
        if (!mHintShown || forceShow) {
            mHintShown = true;
            mView.animateHint();
        }
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            if (linear != 0) showBouncer(false);
            mView.onDozeAmountChanged(linear, eased);
            if (linear == 1f) {
                // transition has finished
                mTransitioningFromHome = false;
            }
            updatePauseAuth();
        }

        @Override
        public void onStatePreChange(int oldState, int newState) {
            mTransitioningFromHome = oldState == StatusBarState.SHADE
                    && newState == StatusBarState.KEYGUARD;
            updatePauseAuth();
        }

        @Override
        public void onStateChanged(int statusBarState) {
            mStatusBarState = statusBarState;
            mView.setStatusBarState(statusBarState);
        }
    };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == BiometricSourceType.FACE) {
                        updateFaceDetectRunning(running);
                    }
                }

                public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == BiometricSourceType.FACE) {
                        // show udfps hint when face auth fails
                        showHint(true);
                    }
                }

                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType, boolean isStrongBiometric) {
                    if (biometricSourceType == BiometricSourceType.FACE) {
                        // cancel delayed hint if face auth succeeded
                        cancelDelayedHint();
                    }
                }
            };

    private final StatusBarKeyguardViewManager.AlternateAuthInterceptor mAlternateAuthInterceptor =
            new StatusBarKeyguardViewManager.AlternateAuthInterceptor() {
                @Override
                public boolean showAlternateAuthBouncer() {
                    if (mShowBouncer) {
                        return false;
                    }

                    showBouncer(true);
                    return true;
                }

                @Override
                public boolean hideAlternateAuthBouncer() {
                    if (!mShowBouncer) {
                        return false;
                    }

                    showBouncer(false);
                    return true;
                }

                @Override
                public boolean isShowingAlternateAuthBouncer() {
                    return mShowBouncer;
                }

                @Override
                public boolean isAnimating() {
                    return mView.isAnimating();
                }

                @Override
                public void setQsExpanded(boolean expanded) {
                    mQsExpanded = expanded;
                    updatePauseAuth();
                }

                @Override
                public void dump(PrintWriter pw) {
                    pw.print(getTag());
                }
            };
}
