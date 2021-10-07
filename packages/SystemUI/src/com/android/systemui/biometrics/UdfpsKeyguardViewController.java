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
import android.content.res.Configuration;
import android.util.MathUtils;
import android.view.MotionEvent;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 */
public class UdfpsKeyguardViewController extends UdfpsAnimationViewController<UdfpsKeyguardView> {
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final DelayableExecutor mExecutor;
    @NonNull private final KeyguardViewMediator mKeyguardViewMediator;
    @NonNull private final LockscreenShadeTransitionController mLockScreenShadeTransitionController;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final UdfpsController mUdfpsController;

    private boolean mShowingUdfpsBouncer;
    private boolean mUdfpsRequested;
    private boolean mQsExpanded;
    private boolean mFaceDetectRunning;
    private int mStatusBarState;
    private float mTransitionToFullShadeProgress;
    private float mLastDozeAmount;

    private float mStatusBarExpansion;
    private boolean mLaunchTransitionFadingAway;

    /**
     * hidden amount of pin/pattern/password bouncer
     * {@link KeyguardBouncer#EXPANSION_VISIBLE} (0f) to
     * {@link KeyguardBouncer#EXPANSION_HIDDEN} (1f)
     */
    private float mInputBouncerHiddenAmount;
    private boolean mIsBouncerVisible;

    protected UdfpsKeyguardViewController(
            @NonNull UdfpsKeyguardView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull StatusBar statusBar,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull DelayableExecutor mainDelayableExecutor,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardViewMediator keyguardViewMediator,
            @NonNull LockscreenShadeTransitionController transitionController,
            @NonNull ConfigurationController configurationController,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull UdfpsController udfpsController) {
        super(view, statusBarStateController, statusBar, dumpManager);
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mExecutor = mainDelayableExecutor;
        mKeyguardViewMediator = keyguardViewMediator;
        mLockScreenShadeTransitionController = transitionController;
        mConfigurationController = configurationController;
        mKeyguardStateController = keyguardStateController;
        mUdfpsController = udfpsController;
    }

    @Override
    @NonNull String getTag() {
        return "UdfpsKeyguardViewController";
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        final float dozeAmount = mStatusBarStateController.getDozeAmount();
        mLastDozeAmount = dozeAmount;
        mStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
        mStatusBarStateController.addCallback(mStateListener);

        mUdfpsRequested = false;

        mLaunchTransitionFadingAway = mKeyguardStateController.isLaunchTransitionFadingAway();
        mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mStatusBarState = mStatusBarStateController.getState();
        mQsExpanded = mKeyguardViewManager.isQsExpanded();
        mInputBouncerHiddenAmount = KeyguardBouncer.EXPANSION_HIDDEN;
        mIsBouncerVisible = mKeyguardViewManager.bouncerIsOrWillBeShowing();
        mConfigurationController.addCallback(mConfigurationListener);
        mStatusBar.addExpansionChangedListener(mStatusBarExpansionChangedListener);
        updateAlpha();
        updatePauseAuth();

        mKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        mLockScreenShadeTransitionController.setUdfpsKeyguardViewController(this);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mFaceDetectRunning = false;

        mKeyguardStateController.removeCallback(mKeyguardStateControllerCallback);
        mStatusBarStateController.removeCallback(mStateListener);
        mKeyguardViewManager.removeAlternateAuthInterceptor(mAlternateAuthInterceptor);
        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false);
        mConfigurationController.removeCallback(mConfigurationListener);
        mStatusBar.removeExpansionChangedListener(mStatusBarExpansionChangedListener);
        if (mLockScreenShadeTransitionController.getUdfpsKeyguardViewController() == this) {
            mLockScreenShadeTransitionController.setUdfpsKeyguardViewController(null);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("mShowingUdfpsBouncer=" + mShowingUdfpsBouncer);
        pw.println("mFaceDetectRunning=" + mFaceDetectRunning);
        pw.println("mStatusBarState=" + StatusBarState.toShortString(mStatusBarState));
        pw.println("mQsExpanded=" + mQsExpanded);
        pw.println("mIsBouncerVisible=" + mIsBouncerVisible);
        pw.println("mInputBouncerHiddenAmount=" + mInputBouncerHiddenAmount);
        pw.println("mStatusBarExpansion=" + mStatusBarExpansion);
        pw.println("unpausedAlpha=" + mView.getUnpausedAlpha());
        pw.println("mUdfpsRequested=" + mUdfpsRequested);
        pw.println("mView.mUdfpsRequested=" + mView.mUdfpsRequested);
        pw.println("mLaunchTransitionFadingAway=" + mLaunchTransitionFadingAway);
    }

    /**
     * Overrides non-bouncer show logic in shouldPauseAuth to still show icon.
     * @return whether the udfpsBouncer has been newly shown or hidden
     */
    private boolean showUdfpsBouncer(boolean show) {
        if (mShowingUdfpsBouncer == show) {
            return false;
        }

        boolean udfpsAffordanceWasNotShowing = shouldPauseAuth();
        mShowingUdfpsBouncer = show;
        if (mShowingUdfpsBouncer) {
            if (udfpsAffordanceWasNotShowing) {
                mView.animateInUdfpsBouncer(null);
            }

            if (mKeyguardViewManager.isOccluded()) {
                mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(true);
            }

            mView.announceForAccessibility(mView.getContext().getString(
                    R.string.accessibility_fingerprint_bouncer));
        } else {
            mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false);
        }
        updateAlpha();
        updatePauseAuth();
        return true;
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication. On the keyguard, we may want to show udfps when the shade
     * is expanded, so this can be overridden with the showBouncer method.
     */
    public boolean shouldPauseAuth() {
        if (mShowingUdfpsBouncer) {
            return false;
        }

        if (mUdfpsRequested && !mNotificationShadeExpanded
                && (!mIsBouncerVisible
                || mInputBouncerHiddenAmount != KeyguardBouncer.EXPANSION_VISIBLE)) {
            return false;
        }

        if (mLaunchTransitionFadingAway) {
            return true;
        }

        if (mStatusBarState != KEYGUARD) {
            return true;
        }

        if (mQsExpanded) {
            return true;
        }

        if (mInputBouncerHiddenAmount < .5f || mIsBouncerVisible) {
            return true;
        }

        return false;
    }

    @Override
    public boolean listenForTouchesOutsideView() {
        return true;
    }

    @Override
    public void onTouchOutsideView() {
        maybeShowInputBouncer();
    }

    /**
     * If we were previously showing the udfps bouncer, hide it and instead show the regular
     * (pin/pattern/password) bouncer.
     *
     * Does nothing if we weren't previously showing the udfps bouncer.
     */
    private void maybeShowInputBouncer() {
        if (mShowingUdfpsBouncer) {
            mKeyguardViewManager.showBouncer(true);
            mKeyguardViewManager.resetAlternateAuth(false);
        }
    }

    /**
     * Set the progress we're currently transitioning to the full shade. 0.0f means we're not
     * transitioning yet, while 1.0f means we've fully dragged down.
     */
    public void setTransitionToFullShadeProgress(float progress) {
        mTransitionToFullShadeProgress = progress;
        updateAlpha();
    }

    private void updateAlpha() {
        // fade icon on transitions to showing the status bar, but if mUdfpsRequested, then
        // the keyguard is occluded by some application - so instead use the input bouncer
        // hidden amount to determine the fade
        float expansion = mUdfpsRequested ? mInputBouncerHiddenAmount : mStatusBarExpansion;
        int alpha = mShowingUdfpsBouncer ? 255
                : (int) MathUtils.constrain(
                    MathUtils.map(.5f, .9f, 0f, 255f, expansion),
                    0f, 255f);
        if (!mShowingUdfpsBouncer) {
            alpha *= (1.0f - mTransitionToFullShadeProgress);
        }
        mView.setUnpausedAlpha(alpha);
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            if (mLastDozeAmount < linear) {
                showUdfpsBouncer(false);
            }
            mView.onDozeAmountChanged(linear, eased);
            mLastDozeAmount = linear;
            updatePauseAuth();
        }

        @Override
        public void onStateChanged(int statusBarState) {
            mStatusBarState = statusBarState;
            mView.setStatusBarState(statusBarState);
            updatePauseAuth();
        }
    };

    private final StatusBarKeyguardViewManager.AlternateAuthInterceptor mAlternateAuthInterceptor =
            new StatusBarKeyguardViewManager.AlternateAuthInterceptor() {
                @Override
                public boolean showAlternateAuthBouncer() {
                    return showUdfpsBouncer(true);
                }

                @Override
                public boolean hideAlternateAuthBouncer() {
                    return showUdfpsBouncer(false);
                }

                @Override
                public boolean isShowingAlternateAuthBouncer() {
                    return mShowingUdfpsBouncer;
                }

                @Override
                public void requestUdfps(boolean request, int color) {
                    mUdfpsRequested = request;
                    mView.requestUdfps(request, color);
                    updateAlpha();
                    updatePauseAuth();
                }

                @Override
                public boolean isAnimating() {
                    return false;
                }

                @Override
                public void setQsExpanded(boolean expanded) {
                    mQsExpanded = expanded;
                    updatePauseAuth();
                }

                @Override
                public boolean onTouch(MotionEvent event) {
                    if (mTransitionToFullShadeProgress != 0) {
                        return false;
                    }
                    return mUdfpsController.onTouch(event);
                }

                @Override
                public void setBouncerExpansionChanged(float expansion) {
                    mInputBouncerHiddenAmount = expansion;
                    updateAlpha();
                    updatePauseAuth();
                }

                @Override
                public void onBouncerVisibilityChanged() {
                    mIsBouncerVisible = mKeyguardViewManager.isBouncerShowing();
                    if (!mIsBouncerVisible) {
                        mInputBouncerHiddenAmount = 1f;
                    } else if (mKeyguardViewManager.isBouncerShowing()) {
                        mInputBouncerHiddenAmount = 0f;
                    }
                    updateAlpha();
                    updatePauseAuth();
                }

                @Override
                public void dump(PrintWriter pw) {
                    pw.println(getTag());
                }
            };

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onUiModeChanged() {
                    mView.updateColor();
                }

                @Override
                public void onThemeChanged() {
                    mView.updateColor();
                }

                @Override
                public void onOverlayChanged() {
                    mView.updateColor();
                }

                @Override
                public void onConfigChanged(Configuration newConfig) {
                    mView.updateColor();
                }
            };

    private final StatusBar.ExpansionChangedListener mStatusBarExpansionChangedListener =
            new StatusBar.ExpansionChangedListener() {
                @Override
                public void onExpansionChanged(float expansion, boolean expanded) {
                    mStatusBarExpansion = expansion;
                    updateAlpha();
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onLaunchTransitionFadingAwayChanged() {
                    mLaunchTransitionFadingAway =
                            mKeyguardStateController.isLaunchTransitionFadingAway();
                    updatePauseAuth();
                }
            };
}
