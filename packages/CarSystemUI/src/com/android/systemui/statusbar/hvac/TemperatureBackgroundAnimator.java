/*
 * Copyright (c) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.hvac;

import static com.android.systemui.statusbar.hvac.AnimatedTemperatureView.isHorizontal;
import static com.android.systemui.statusbar.hvac.AnimatedTemperatureView.isLeft;
import static com.android.systemui.statusbar.hvac.AnimatedTemperatureView.isTop;
import static com.android.systemui.statusbar.hvac.AnimatedTemperatureView.isVertical;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AnticipateInterpolator;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Controls circular reveal animation of temperature background
 */
class TemperatureBackgroundAnimator {

    private static final AnticipateInterpolator ANTICIPATE_INTERPOLATOR =
            new AnticipateInterpolator();
    private static final float MAX_OPACITY = .6f;

    private final View mAnimatedView;

    private int mPivotX;
    private int mPivotY;
    private int mGoneRadius;
    private int mOvershootRadius;
    private int mRestingRadius;
    private int mBumpRadius;

    @CircleState
    private int mCircleState;

    private Animator mCircularReveal;
    private boolean mAnimationsReady;

    @IntDef({CircleState.GONE, CircleState.ENTERING, CircleState.OVERSHOT, CircleState.RESTING,
            CircleState.RESTED, CircleState.BUMPING, CircleState.BUMPED, CircleState.EXITING})
    private @interface CircleState {
        int GONE = 0;
        int ENTERING = 1;
        int OVERSHOT = 2;
        int RESTING = 3;
        int RESTED = 4;
        int BUMPING = 5;
        int BUMPED = 6;
        int EXITING = 7;
    }

    TemperatureBackgroundAnimator(
            AnimatedTemperatureView parent,
            ImageView animatedView) {
        mAnimatedView = animatedView;
        mAnimatedView.setAlpha(0);

        parent.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        setupAnimations(parent.getGravity(), parent.getPivotOffset(),
                                parent.getPaddingRect(), parent.getWidth(), parent.getHeight()));
    }

    private void setupAnimations(int gravity, int pivotOffset, Rect paddingRect,
            int width, int height) {
        int padding;
        if (isHorizontal(gravity)) {
            mGoneRadius = pivotOffset;
            if (isLeft(gravity, mAnimatedView.getLayoutDirection())) {
                mPivotX = -pivotOffset;
                padding = paddingRect.right;
            } else {
                mPivotX = width + pivotOffset;
                padding = paddingRect.left;
            }
            mPivotY = height / 2;
            mOvershootRadius = pivotOffset + width;
        } else if (isVertical(gravity)) {
            mGoneRadius = pivotOffset;
            if (isTop(gravity)) {
                mPivotY = -pivotOffset;
                padding = paddingRect.bottom;
            } else {
                mPivotY = height + pivotOffset;
                padding = paddingRect.top;
            }
            mPivotX = width / 2;
            mOvershootRadius = pivotOffset + height;
        } else {
            mPivotX = width / 2;
            mPivotY = height / 2;
            mGoneRadius = 0;
            if (width > height) {
                mOvershootRadius = height;
                padding = Math.max(paddingRect.top, paddingRect.bottom);
            } else {
                mOvershootRadius = width;
                padding = Math.max(paddingRect.left, paddingRect.right);
            }
        }
        mRestingRadius = mOvershootRadius - padding;
        mBumpRadius = mOvershootRadius - padding / 3;
        mAnimationsReady = true;
    }

    boolean isOpen() {
        return mCircleState != CircleState.GONE;
    }

    void animateOpen() {
        if (!mAnimationsReady
                || !mAnimatedView.isAttachedToWindow()
                || mCircleState == CircleState.ENTERING) {
            return;
        }

        AnimatorSet set = new AnimatorSet();
        List<Animator> animators = new ArrayList<>();
        switch (mCircleState) {
            case CircleState.ENTERING:
                throw new AssertionError("Should not be able to reach this statement");
            case CircleState.GONE: {
                Animator startCircle = createEnterAnimator();
                markState(startCircle, CircleState.ENTERING);
                animators.add(startCircle);
                Animator holdOvershoot = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mOvershootRadius,
                                mOvershootRadius);
                holdOvershoot.setDuration(50);
                markState(holdOvershoot, CircleState.OVERSHOT);
                animators.add(holdOvershoot);
                Animator rest = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mOvershootRadius,
                                mRestingRadius);
                markState(rest, CircleState.RESTING);
                animators.add(rest);
                Animator holdRest = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mRestingRadius,
                                mRestingRadius);
                markState(holdRest, CircleState.RESTED);
                holdRest.setDuration(1000);
                animators.add(holdRest);
                Animator exit = createExitAnimator(mRestingRadius);
                markState(exit, CircleState.EXITING);
                animators.add(exit);
            }
            break;
            case CircleState.RESTED:
            case CircleState.RESTING:
            case CircleState.EXITING:
            case CircleState.OVERSHOT:
                int startRadius =
                        mCircleState == CircleState.OVERSHOT ? mOvershootRadius : mRestingRadius;
                Animator bump = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, startRadius,
                                mBumpRadius);
                bump.setDuration(50);
                markState(bump, CircleState.BUMPING);
                animators.add(bump);
                // fallthrough intentional
            case CircleState.BUMPED:
            case CircleState.BUMPING:
                Animator holdBump = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mBumpRadius,
                                mBumpRadius);
                holdBump.setDuration(100);
                markState(holdBump, CircleState.BUMPED);
                animators.add(holdBump);
                Animator rest = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mBumpRadius,
                                mRestingRadius);
                markState(rest, CircleState.RESTING);
                animators.add(rest);
                Animator holdRest = ViewAnimationUtils
                        .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mRestingRadius,
                                mRestingRadius);
                holdRest.setDuration(1000);
                markState(holdRest, CircleState.RESTED);
                animators.add(holdRest);
                Animator exit = createExitAnimator(mRestingRadius);
                markState(exit, CircleState.EXITING);
                animators.add(exit);
                break;
        }
        set.playSequentially(animators);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationStart(Animator animation) {
                if (mCircularReveal != null) {
                    mCircularReveal.cancel();
                }
                mCircularReveal = animation;
                mAnimatedView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCanceled) {
                    return;
                }
                mCircularReveal = null;
                mCircleState = CircleState.GONE;
                mAnimatedView.setVisibility(View.GONE);
            }
        });

        set.start();
    }

    private Animator createEnterAnimator() {
        AnimatorSet animatorSet = new AnimatorSet();
        Animator circularReveal = ViewAnimationUtils
                .createCircularReveal(mAnimatedView, mPivotX, mPivotY, mGoneRadius,
                        mOvershootRadius);
        Animator fade = ObjectAnimator.ofFloat(mAnimatedView, View.ALPHA, MAX_OPACITY);
        animatorSet.playTogether(circularReveal, fade);
        return animatorSet;
    }

    private Animator createExitAnimator(int startRadius) {
        AnimatorSet animatorSet = new AnimatorSet();
        Animator circularHide = ViewAnimationUtils
                .createCircularReveal(mAnimatedView, mPivotX, mPivotY, startRadius,
                        (mGoneRadius + startRadius) / 2);
        circularHide.setInterpolator(ANTICIPATE_INTERPOLATOR);
        Animator fade = ObjectAnimator.ofFloat(mAnimatedView, View.ALPHA, 0);
        fade.setStartDelay(50);
        animatorSet.playTogether(circularHide, fade);
        return animatorSet;
    }

    void hideCircle() {
        if (!mAnimationsReady || mCircleState == CircleState.GONE
                || mCircleState == CircleState.EXITING) {
            return;
        }

        int startRadius;
        switch (mCircleState) {
            // Unreachable, but here to exhaust switch cases
            //noinspection ConstantConditions
            case CircleState.EXITING:
                //noinspection ConstantConditions
            case CircleState.GONE:
                throw new AssertionError("Should not be able to reach this statement");
            case CircleState.BUMPED:
            case CircleState.BUMPING:
                startRadius = mBumpRadius;
                break;
            case CircleState.OVERSHOT:
                startRadius = mOvershootRadius;
                break;
            case CircleState.ENTERING:
            case CircleState.RESTED:
            case CircleState.RESTING:
                startRadius = mRestingRadius;
                break;
            default:
                throw new IllegalStateException("Unknown CircleState: " + mCircleState);
        }

        Animator hideAnimation = createExitAnimator(startRadius);
        if (startRadius == mRestingRadius) {
            hideAnimation.setInterpolator(ANTICIPATE_INTERPOLATOR);
        }
        hideAnimation.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationStart(Animator animation) {
                mCircleState = CircleState.EXITING;
                if (mCircularReveal != null) {
                    mCircularReveal.cancel();
                }
                mCircularReveal = animation;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCanceled) {
                    return;
                }
                mCircularReveal = null;
                mCircleState = CircleState.GONE;
                mAnimatedView.setVisibility(View.GONE);
            }
        });
        hideAnimation.start();
    }

    void stopAnimations() {
        if (mCircularReveal != null) {
            mCircularReveal.end();
        }
    }

    private void markState(Animator animator, @CircleState int startState) {
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mCircleState = startState;
            }
        });
    }
}
