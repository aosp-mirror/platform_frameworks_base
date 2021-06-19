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

package com.android.keyguard;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Helper class for updating visibility of keyguard views based on keyguard and status bar state.
 * This logic is shared by both the keyguard status view and the keyguard user switcher.
 */
public class KeyguardVisibilityHelper {

    private View mView;
    private final KeyguardStateController mKeyguardStateController;
    private final DozeParameters mDozeParameters;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private boolean mAnimateYPos;
    private boolean mKeyguardViewVisibilityAnimating;
    private boolean mLastOccludedState = false;
    private final AnimationProperties mAnimationProperties = new AnimationProperties();

    public KeyguardVisibilityHelper(View view,
            KeyguardStateController keyguardStateController,
            DozeParameters dozeParameters,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            boolean animateYPos) {
        mView = view;
        mKeyguardStateController = keyguardStateController;
        mDozeParameters = dozeParameters;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mAnimateYPos = animateYPos;
    }

    public boolean isVisibilityAnimating() {
        return mKeyguardViewVisibilityAnimating;
    }

    /**
     * Set the visibility of a keyguard view based on some new state.
     */
    public void setViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mView.animate().cancel();
        boolean isOccluded = mKeyguardStateController.isOccluded();
        mKeyguardViewVisibilityAnimating = false;
        if ((!keyguardFadingAway && oldStatusBarState == KEYGUARD
                && statusBarState != KEYGUARD) || goingToFullShade) {
            mKeyguardViewVisibilityAnimating = true;
            mView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .withEndAction(
                            mAnimateKeyguardStatusViewGoneEndRunnable);
            if (keyguardFadingAway) {
                mView.animate()
                        .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                        .setDuration(mKeyguardStateController.getShortenedFadingAwayDuration())
                        .start();
            }
        } else if (oldStatusBarState == StatusBarState.SHADE_LOCKED && statusBarState == KEYGUARD) {
            mView.setVisibility(View.VISIBLE);
            mKeyguardViewVisibilityAnimating = true;
            mView.setAlpha(0f);
            mView.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable);
        } else if (statusBarState == KEYGUARD) {
            if (keyguardFadingAway) {
                mKeyguardViewVisibilityAnimating = true;
                ViewPropertyAnimator animator = mView.animate()
                        .alpha(0)
                        .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                        .withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable);
                if (mAnimateYPos) {
                    float target = mView.getY() - mView.getHeight() * 0.05f;
                    int delay = 0;
                    int duration = 125;
                    // We animate the Y property separately using the PropertyAnimator, as the panel
                    // view also needs to update the end position.
                    mAnimationProperties.setDuration(duration).setDelay(delay);
                    PropertyAnimator.cancelAnimation(mView, AnimatableProperty.Y);
                    PropertyAnimator.setProperty(mView, AnimatableProperty.Y, target,
                            mAnimationProperties,
                            true /* animate */);
                    animator.setDuration(duration)
                            .setStartDelay(delay);
                }
                animator.start();
            } else if (mLastOccludedState && !isOccluded) {
                // An activity was displayed over the lock screen, and has now gone away
                mView.setVisibility(View.VISIBLE);
                mView.setAlpha(0f);

                mView.animate()
                        .setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP)
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .alpha(1f)
                        .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable)
                        .start();
            } else if (mUnlockedScreenOffAnimationController
                        .isScreenOffLightRevealAnimationPlaying()) {
                mKeyguardViewVisibilityAnimating = true;

                // Ask the screen off animation controller to animate the keyguard visibility for us
                // since it may need to be cancelled due to keyguard lifecycle events.
                mUnlockedScreenOffAnimationController.animateInKeyguard(
                        mView, mAnimateKeyguardStatusViewVisibleEndRunnable);
            } else {
                mView.setVisibility(View.VISIBLE);
                mView.setAlpha(1f);
            }
        } else {
            mView.setVisibility(View.GONE);
            mView.setAlpha(1f);
        }

        mLastOccludedState = isOccluded;
    }

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
        mView.setVisibility(View.INVISIBLE);
    };

    private final Runnable mAnimateKeyguardStatusViewGoneEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
        mView.setVisibility(View.GONE);
    };

    private final Runnable mAnimateKeyguardStatusViewVisibleEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
    };
}
