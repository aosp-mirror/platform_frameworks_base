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

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.util.MathUtils;
import android.view.MotionEvent;

import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionListener;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 */
public class UdfpsKeyguardViewController extends UdfpsAnimationViewController<UdfpsKeyguardView> {
    public static final String TAG = "UdfpsKeyguardViewCtrl";
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final LockscreenShadeTransitionController mLockScreenShadeTransitionController;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final SystemClock mSystemClock;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final UdfpsController mUdfpsController;
    @NonNull private final UnlockedScreenOffAnimationController
            mUnlockedScreenOffAnimationController;
    @NonNull private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final ValueAnimator mUnlockedScreenOffDozeAnimator = ValueAnimator.ofFloat(0f, 1f);

    private boolean mShowingUdfpsBouncer;
    private boolean mUdfpsRequested;
    private float mQsExpansion;
    private boolean mFaceDetectRunning;
    private int mStatusBarState;
    private float mTransitionToFullShadeProgress;
    private float mLastDozeAmount;
    private long mLastUdfpsBouncerShowTime = -1;
    private float mPanelExpansionFraction;
    private boolean mLaunchTransitionFadingAway;
    private boolean mIsLaunchingActivity;
    private float mActivityLaunchProgress;

    /**
     * hidden amount of pin/pattern/password bouncer
     * {@link KeyguardBouncer#EXPANSION_VISIBLE} (0f) to
     * {@link KeyguardBouncer#EXPANSION_HIDDEN} (1f)
     */
    private float mInputBouncerHiddenAmount;
    private boolean mIsGenericBouncerShowing; // whether UDFPS bouncer or input bouncer is visible

    protected UdfpsKeyguardViewController(
            @NonNull UdfpsKeyguardView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull PanelExpansionStateManager panelExpansionStateManager,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull DumpManager dumpManager,
            @NonNull LockscreenShadeTransitionController transitionController,
            @NonNull ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            @NonNull SystemUIDialogManager systemUIDialogManager,
            @NonNull UdfpsController udfpsController,
            @NonNull ActivityLaunchAnimator activityLaunchAnimator) {
        super(view, statusBarStateController, panelExpansionStateManager, systemUIDialogManager,
                dumpManager);
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockScreenShadeTransitionController = transitionController;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mKeyguardStateController = keyguardStateController;
        mUdfpsController = udfpsController;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mActivityLaunchAnimator = activityLaunchAnimator;

        mUnlockedScreenOffDozeAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        mUnlockedScreenOffDozeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mUnlockedScreenOffDozeAnimator.addUpdateListener(
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mView.onDozeAmountChanged(
                                animation.getAnimatedFraction(),
                                (float) animation.getAnimatedValue(),
                                /* animatingBetweenAodAndLockScreen */ false);
                    }
                });
    }

    @Override
    @NonNull protected String getTag() {
        return "UdfpsKeyguardViewController";
    }

    @Override
    public void onInit() {
        super.onInit();
        mKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        final float dozeAmount = getStatusBarStateController().getDozeAmount();
        mLastDozeAmount = dozeAmount;
        mStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
        getStatusBarStateController().addCallback(mStateListener);

        mUdfpsRequested = false;

        mLaunchTransitionFadingAway = mKeyguardStateController.isLaunchTransitionFadingAway();
        mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mStatusBarState = getStatusBarStateController().getState();
        mQsExpansion = mKeyguardViewManager.getQsExpansion();
        updateGenericBouncerVisibility();
        mConfigurationController.addCallback(mConfigurationListener);
        getPanelExpansionStateManager().addExpansionListener(mPanelExpansionListener);
        updateAlpha();
        updatePauseAuth();

        mKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        mLockScreenShadeTransitionController.setUdfpsKeyguardViewController(this);
        mActivityLaunchAnimator.addListener(mActivityLaunchAnimatorListener);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mFaceDetectRunning = false;

        mKeyguardStateController.removeCallback(mKeyguardStateControllerCallback);
        getStatusBarStateController().removeCallback(mStateListener);
        mKeyguardViewManager.removeAlternateAuthInterceptor(mAlternateAuthInterceptor);
        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false);
        mConfigurationController.removeCallback(mConfigurationListener);
        getPanelExpansionStateManager().removeExpansionListener(mPanelExpansionListener);
        if (mLockScreenShadeTransitionController.getUdfpsKeyguardViewController() == this) {
            mLockScreenShadeTransitionController.setUdfpsKeyguardViewController(null);
        }
        mActivityLaunchAnimator.removeListener(mActivityLaunchAnimatorListener);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        super.dump(pw, args);
        pw.println("mShowingUdfpsBouncer=" + mShowingUdfpsBouncer);
        pw.println("mFaceDetectRunning=" + mFaceDetectRunning);
        pw.println("mStatusBarState=" + StatusBarState.toString(mStatusBarState));
        pw.println("mTransitionToFullShadeProgress=" + mTransitionToFullShadeProgress);
        pw.println("mQsExpansion=" + mQsExpansion);
        pw.println("mIsGenericBouncerShowing=" + mIsGenericBouncerShowing);
        pw.println("mInputBouncerHiddenAmount=" + mInputBouncerHiddenAmount);
        pw.println("mPanelExpansionFraction=" + mPanelExpansionFraction);
        pw.println("unpausedAlpha=" + mView.getUnpausedAlpha());
        pw.println("mUdfpsRequested=" + mUdfpsRequested);
        pw.println("mLaunchTransitionFadingAway=" + mLaunchTransitionFadingAway);
        pw.println("mLastDozeAmount=" + mLastDozeAmount);

        mView.dump(pw);
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
            mLastUdfpsBouncerShowTime = mSystemClock.uptimeMillis();
        }
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

        updateGenericBouncerVisibility();
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

        if (mUdfpsRequested && !getNotificationShadeVisible()
                && (!mIsGenericBouncerShowing
                || mInputBouncerHiddenAmount != KeyguardBouncer.EXPANSION_VISIBLE)
                && mKeyguardStateController.isShowing()) {
            return false;
        }

        if (mLaunchTransitionFadingAway) {
            return true;
        }

        // Only pause auth if we're not on the keyguard AND we're not transitioning to doze
        // (ie: dozeAmount = 0f). For the UnlockedScreenOffAnimation, the statusBarState is
        // delayed. However, we still animate in the UDFPS affordance with the 
        // mUnlockedScreenOffDozeAnimator.
        if (mStatusBarState != KEYGUARD && mLastDozeAmount == 0f) {
            return true;
        }

        if (mInputBouncerHiddenAmount < .5f) {
            return true;
        }

        if (mView.getUnpausedAlpha() < (255 * .1)) {
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
     * Does nothing if we weren't previously showing the UDFPS bouncer.
     */
    private void maybeShowInputBouncer() {
        if (mShowingUdfpsBouncer && hasUdfpsBouncerShownWithMinTime()) {
            mKeyguardViewManager.showBouncer(true);
        }
    }

    /**
     * Whether the udfps bouncer has shown for at least 200ms before allowing touches outside
     * of the udfps icon area to dismiss the udfps bouncer and show the pin/pattern/password
     * bouncer.
     */
    private boolean hasUdfpsBouncerShownWithMinTime() {
        return (mSystemClock.uptimeMillis() - mLastUdfpsBouncerShowTime) > 200;
    }

    /**
     * Set the progress we're currently transitioning to the full shade. 0.0f means we're not
     * transitioning yet, while 1.0f means we've fully dragged down.
     *
     * For example, start swiping down to expand the notification shade from the empty space in
     * the middle of the lock screen.
     */
    public void setTransitionToFullShadeProgress(float progress) {
        mTransitionToFullShadeProgress = progress;
        updateAlpha();
    }

    /**
     * Update alpha for the UDFPS lock screen affordance. The AoD UDFPS visual affordance's
     * alpha is based on the doze amount.
     */
    @Override
    public void updateAlpha() {
        // Fade icon on transitions to showing the status bar or bouncer, but if mUdfpsRequested,
        // then the keyguard is occluded by some application - so instead use the input bouncer
        // hidden amount to determine the fade.
        float expansion = mUdfpsRequested ? mInputBouncerHiddenAmount : mPanelExpansionFraction;

        int alpha = mShowingUdfpsBouncer ? 255
                : (int) MathUtils.constrain(
                    MathUtils.map(.5f, .9f, 0f, 255f, expansion),
                    0f, 255f);

        if (!mShowingUdfpsBouncer) {
            // swipe from top of the lockscreen to expand full QS:
            alpha *= (1.0f - Interpolators.EMPHASIZED_DECELERATE.getInterpolation(mQsExpansion));

            // swipe from the middle (empty space) of lockscreen to expand the notification shade:
            alpha *= (1.0f - mTransitionToFullShadeProgress);

            // Fade out the icon if we are animating an activity launch over the lockscreen and the
            // activity didn't request the UDFPS.
            if (mIsLaunchingActivity && !mUdfpsRequested) {
                alpha *= (1.0f - mActivityLaunchProgress);
            }

            // Fade out alpha when a dialog is shown
            // Fade in alpha when a dialog is hidden
            alpha *= mView.getDialogSuggestedAlpha();
        }
        mView.setUnpausedAlpha(alpha);
    }

    /**
     * Updates mIsGenericBouncerShowing (whether any bouncer is showing) and updates the
     * mInputBouncerHiddenAmount to reflect whether the input bouncer is fully showing or not.
     */
    private void updateGenericBouncerVisibility() {
        mIsGenericBouncerShowing = mKeyguardViewManager.isBouncerShowing(); // includes altBouncer
        final boolean altBouncerShowing = mKeyguardViewManager.isShowingAlternateAuth();
        if (altBouncerShowing || !mKeyguardViewManager.bouncerIsOrWillBeShowing()) {
            mInputBouncerHiddenAmount = 1f;
        } else if (mIsGenericBouncerShowing) {
            // input bouncer is fully showing
            mInputBouncerHiddenAmount = 0f;
        }
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            if (mLastDozeAmount < linear) {
                showUdfpsBouncer(false);
            }
            mUnlockedScreenOffDozeAnimator.cancel();
            final boolean animatingFromUnlockedScreenOff =
                    mUnlockedScreenOffAnimationController.isAnimationPlaying();
            if (animatingFromUnlockedScreenOff && linear != 0f) {
                // we manually animate the fade in of the UDFPS icon since the unlocked
                // screen off animation prevents the doze amounts to be incrementally eased in
                mUnlockedScreenOffDozeAnimator.start();
            } else {
                mView.onDozeAmountChanged(linear, eased,
                    /* animatingBetweenAodAndLockScreen */ true);
            }

            mLastDozeAmount = linear;
            updatePauseAuth();
        }

        @Override
        public void onStateChanged(int statusBarState) {
            mStatusBarState = statusBarState;
            mView.setStatusBarState(statusBarState);
            updateAlpha();
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

                /**
                 * Set the amount qs is expanded. Forxample, swipe down from the top of the
                 * lock screen to start the full QS expansion.
                 */
                @Override
                public void setQsExpansion(float qsExpansion) {
                    mQsExpansion = qsExpansion;
                    updateAlpha();
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

                /**
                 * Only called on primary auth bouncer changes, not on whether the UDFPS bouncer
                 * visibility changes.
                 */
                @Override
                public void onBouncerVisibilityChanged() {
                    updateGenericBouncerVisibility();
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
                public void onConfigChanged(Configuration newConfig) {
                    mView.updateColor();
                }
            };

    private final PanelExpansionListener mPanelExpansionListener = new PanelExpansionListener() {
        @Override
        public void onPanelExpansionChanged(
                float fraction, boolean expanded, boolean tracking) {
            mPanelExpansionFraction =
                    mKeyguardViewManager.bouncerIsInTransit() ? BouncerPanelExpansionCalculator
                            .aboutToShowBouncerProgress(fraction) : fraction;
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

    private final ActivityLaunchAnimator.Listener mActivityLaunchAnimatorListener =
            new ActivityLaunchAnimator.Listener() {
                @Override
                public void onLaunchAnimationStart() {
                    mIsLaunchingActivity = true;
                    mActivityLaunchProgress = 0f;
                    updateAlpha();
                }

                @Override
                public void onLaunchAnimationEnd() {
                    mIsLaunchingActivity = false;
                    updateAlpha();
                }

                @Override
                public void onLaunchAnimationProgress(float linearProgress) {
                    mActivityLaunchProgress = linearProgress;
                    updateAlpha();
                }
            };
}
