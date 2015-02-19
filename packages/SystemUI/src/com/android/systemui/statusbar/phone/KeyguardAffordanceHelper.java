/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.KeyguardAffordanceView;

/**
 * A touch handler of the keyguard which is responsible for launching phone and camera affordances.
 */
public class KeyguardAffordanceHelper {

    public static final float SWIPE_RESTING_ALPHA_AMOUNT = 0.5f;
    public static final long HINT_PHASE1_DURATION = 200;
    private static final long HINT_PHASE2_DURATION = 350;
    private static final float BACKGROUND_RADIUS_SCALE_FACTOR = 0.15f;
    private static final int HINT_CIRCLE_OPEN_DURATION = 500;

    private final Context mContext;

    private FlingAnimationUtils mFlingAnimationUtils;
    private Callback mCallback;
    private VelocityTracker mVelocityTracker;
    private boolean mSwipingInProgress;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mTranslation;
    private float mTranslationOnDown;
    private int mTouchSlop;
    private int mMinTranslationAmount;
    private int mMinFlingVelocity;
    private int mHintGrowAmount;
    private KeyguardAffordanceView mLeftIcon;
    private KeyguardAffordanceView mCenterIcon;
    private KeyguardAffordanceView mRightIcon;
    private Interpolator mAppearInterpolator;
    private Interpolator mDisappearInterpolator;
    private Animator mSwipeAnimator;
    private int mMinBackgroundRadius;
    private boolean mMotionPerformedByUser;
    private boolean mMotionCancelled;
    private AnimatorListenerAdapter mFlingEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSwipeAnimator = null;
            setSwipingInProgress(false);
        }
    };
    private Runnable mAnimationEndRunnable = new Runnable() {
        @Override
        public void run() {
            mCallback.onAnimationToSideEnded();
        }
    };

    KeyguardAffordanceHelper(Callback callback, Context context) {
        mContext = context;
        mCallback = callback;
        initIcons();
        updateIcon(mLeftIcon, 0.0f, SWIPE_RESTING_ALPHA_AMOUNT, false, false);
        updateIcon(mCenterIcon, 0.0f, SWIPE_RESTING_ALPHA_AMOUNT, false, false);
        updateIcon(mRightIcon, 0.0f, SWIPE_RESTING_ALPHA_AMOUNT, false, false);
        initDimens();
    }

    private void initDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mTouchSlop = configuration.getScaledPagingTouchSlop();
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mMinTranslationAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_min_swipe_amount);
        mMinBackgroundRadius = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_affordance_min_background_radius);
        mHintGrowAmount =
                mContext.getResources().getDimensionPixelSize(R.dimen.hint_grow_amount_sideways);
        mFlingAnimationUtils = new FlingAnimationUtils(mContext, 0.4f);
        mAppearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mDisappearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_linear_in);
    }

    private void initIcons() {
        mLeftIcon = mCallback.getLeftIcon();
        mLeftIcon.setIsLeft(true);
        mCenterIcon = mCallback.getCenterIcon();
        mRightIcon = mCallback.getRightIcon();
        mRightIcon.setIsLeft(false);
        mLeftIcon.setPreviewView(mCallback.getLeftPreview());
        mRightIcon.setPreviewView(mCallback.getRightPreview());
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (mMotionCancelled && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        final float y = event.getY();
        final float x = event.getX();

        boolean isUp = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mSwipingInProgress) {
                    cancelAnimation();
                }
                mInitialTouchY = y;
                mInitialTouchX = x;
                mTranslationOnDown = mTranslation;
                initVelocityTracker();
                trackMovement(event);
                mMotionPerformedByUser = false;
                mMotionCancelled = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mMotionCancelled = true;
                endMotion(event, true /* forceSnapBack */);
                break;
            case MotionEvent.ACTION_MOVE:
                final float w = x - mInitialTouchX;
                trackMovement(event);
                if (((leftSwipePossible() && w > mTouchSlop)
                        || (rightSwipePossible() && w < -mTouchSlop))
                        && Math.abs(w) > Math.abs(y - mInitialTouchY)
                        && !mSwipingInProgress) {
                    cancelAnimation();
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mTranslationOnDown = mTranslation;
                    setSwipingInProgress(true);
                }
                if (mSwipingInProgress) {
                    setTranslation(mTranslationOnDown + x - mInitialTouchX, false, false);
                }
                break;

            case MotionEvent.ACTION_UP:
                isUp = true;
            case MotionEvent.ACTION_CANCEL:
                trackMovement(event);
                endMotion(event, !isUp);
                break;
        }
        return true;
    }

    private void endMotion(MotionEvent event, boolean forceSnapBack) {
        if (mSwipingInProgress) {
            flingWithCurrentVelocity(forceSnapBack);
        }
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void setSwipingInProgress(boolean inProgress) {
        mSwipingInProgress = inProgress;
        if (inProgress) {
            mCallback.onSwipingStarted();
        }
    }

    private boolean rightSwipePossible() {
        return mRightIcon.getVisibility() == View.VISIBLE;
    }

    private boolean leftSwipePossible() {
        return mLeftIcon.getVisibility() == View.VISIBLE;
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    public void startHintAnimation(boolean right, Runnable onFinishedListener) {

        startHintAnimationPhase1(right, onFinishedListener);
    }

    private void startHintAnimationPhase1(final boolean right, final Runnable onFinishedListener) {
        final KeyguardAffordanceView targetView = right ? mRightIcon : mLeftIcon;
        targetView.showArrow(true);
        ValueAnimator animator = getAnimatorToRadius(right, mHintGrowAmount);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    mSwipeAnimator = null;
                    onFinishedListener.run();
                    targetView.showArrow(false);
                } else {
                    startUnlockHintAnimationPhase2(right, onFinishedListener);
                }
            }
        });
        animator.setInterpolator(mAppearInterpolator);
        animator.setDuration(HINT_PHASE1_DURATION);
        animator.start();
        mSwipeAnimator = animator;
    }

    /**
     * Phase 2: Move back.
     */
    private void startUnlockHintAnimationPhase2(boolean right, final Runnable onFinishedListener) {
        final KeyguardAffordanceView targetView = right ? mRightIcon : mLeftIcon;
        ValueAnimator animator = getAnimatorToRadius(right, 0);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnimator = null;
                targetView.showArrow(false);
                onFinishedListener.run();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                targetView.showArrow(false);
            }
        });
        animator.setInterpolator(mDisappearInterpolator);
        animator.setDuration(HINT_PHASE2_DURATION);
        animator.setStartDelay(HINT_CIRCLE_OPEN_DURATION);
        animator.start();
        mSwipeAnimator = animator;
    }

    private ValueAnimator getAnimatorToRadius(final boolean right, int radius) {
        final KeyguardAffordanceView targetView = right ? mRightIcon : mLeftIcon;
        ValueAnimator animator = ValueAnimator.ofFloat(targetView.getCircleRadius(), radius);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float newRadius = (float) animation.getAnimatedValue();
                targetView.setCircleRadiusWithoutAnimation(newRadius);
                float translation = getTranslationFromRadius(newRadius);
                mTranslation = right ? -translation : translation;
                updateIconsFromRadius(targetView, newRadius);
            }
        });
        return animator;
    }

    private void cancelAnimation() {
        if (mSwipeAnimator != null) {
            mSwipeAnimator.cancel();
        }
    }

    private void flingWithCurrentVelocity(boolean forceSnapBack) {
        float vel = getCurrentVelocity();

        // We snap back if the current translation is not far enough
        boolean snapBack = isBelowFalsingThreshold();

        // or if the velocity is in the opposite direction.
        boolean velIsInWrongDirection = vel * mTranslation < 0;
        snapBack |= Math.abs(vel) > mMinFlingVelocity && velIsInWrongDirection;
        vel = snapBack ^ velIsInWrongDirection ? 0 : vel;
        fling(vel, snapBack || forceSnapBack);
    }

    private boolean isBelowFalsingThreshold() {
        return Math.abs(mTranslation) < Math.abs(mTranslationOnDown) + getMinTranslationAmount();
    }

    private int getMinTranslationAmount() {
        float factor = mCallback.getAffordanceFalsingFactor();
        return (int) (mMinTranslationAmount * factor);
    }

    private void fling(float vel, final boolean snapBack) {
        float target = mTranslation < 0 ? -mCallback.getPageWidth() : mCallback.getPageWidth();
        target = snapBack ? 0 : target;

        ValueAnimator animator = ValueAnimator.ofFloat(mTranslation, target);
        mFlingAnimationUtils.apply(animator, mTranslation, target, vel);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTranslation = (float) animation.getAnimatedValue();
            }
        });
        animator.addListener(mFlingEndListener);
        if (!snapBack) {
            startFinishingCircleAnimation(vel * 0.375f, mAnimationEndRunnable);
            mCallback.onAnimationToSideStarted(mTranslation < 0, mTranslation, vel);
        } else {
            reset(true);
        }
        animator.start();
        mSwipeAnimator = animator;
    }

    private void startFinishingCircleAnimation(float velocity, Runnable mAnimationEndRunnable) {
        KeyguardAffordanceView targetView = mTranslation > 0 ? mLeftIcon : mRightIcon;
        targetView.finishAnimation(velocity, mAnimationEndRunnable);
    }

    private void setTranslation(float translation, boolean isReset, boolean animateReset) {
        translation = rightSwipePossible() ? translation : Math.max(0, translation);
        translation = leftSwipePossible() ? translation : Math.min(0, translation);
        float absTranslation = Math.abs(translation);
        if (absTranslation > Math.abs(mTranslationOnDown) + getMinTranslationAmount() ||
                mMotionPerformedByUser) {
            mMotionPerformedByUser = true;
        }
        if (translation != mTranslation || isReset) {
            KeyguardAffordanceView targetView = translation > 0 ? mLeftIcon : mRightIcon;
            KeyguardAffordanceView otherView = translation > 0 ? mRightIcon : mLeftIcon;
            float alpha = absTranslation / getMinTranslationAmount();

            // We interpolate the alpha of the other icons to 0
            float fadeOutAlpha = SWIPE_RESTING_ALPHA_AMOUNT * (1.0f - alpha);
            fadeOutAlpha = Math.max(0.0f, fadeOutAlpha);

            // We interpolate the alpha of the targetView to 1
            alpha = fadeOutAlpha + alpha;

            boolean animateIcons = isReset && animateReset;
            float radius = getRadiusFromTranslation(absTranslation);
            boolean slowAnimation = isReset && isBelowFalsingThreshold();
            if (!isReset) {
                updateIcon(targetView, radius, alpha, false, false);
            } else {
                updateIcon(targetView, 0.0f, fadeOutAlpha, animateIcons, slowAnimation);
            }
            updateIcon(otherView, 0.0f, fadeOutAlpha, animateIcons, slowAnimation);
            updateIcon(mCenterIcon, 0.0f, fadeOutAlpha, animateIcons, slowAnimation);

            mTranslation = translation;
        }
    }

    private void updateIconsFromRadius(KeyguardAffordanceView targetView, float newRadius) {
        float alpha = newRadius / mMinBackgroundRadius;

        // We interpolate the alpha of the other icons to 0
        float fadeOutAlpha = SWIPE_RESTING_ALPHA_AMOUNT * (1.0f - alpha);
        fadeOutAlpha = Math.max(0.0f, fadeOutAlpha);

        // We interpolate the alpha of the targetView to 1
        alpha = fadeOutAlpha + alpha;
        KeyguardAffordanceView otherView = targetView == mRightIcon ? mLeftIcon : mRightIcon;
        updateIconAlpha(targetView, alpha, false);
        updateIconAlpha(otherView, fadeOutAlpha, false);
        updateIconAlpha(mCenterIcon, fadeOutAlpha, false);
    }

    private float getTranslationFromRadius(float circleSize) {
        float translation = (circleSize - mMinBackgroundRadius) / BACKGROUND_RADIUS_SCALE_FACTOR;
        return Math.max(0, translation);
    }

    private float getRadiusFromTranslation(float translation) {
        return translation * BACKGROUND_RADIUS_SCALE_FACTOR + mMinBackgroundRadius;
    }

    public void animateHideLeftRightIcon() {
        updateIcon(mRightIcon, 0f, 0f, true, false);
        updateIcon(mLeftIcon, 0f, 0f, true, false);
    }

    private void updateIcon(KeyguardAffordanceView view, float circleRadius, float alpha,
            boolean animate, boolean slowRadiusAnimation) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        view.setCircleRadius(circleRadius, slowRadiusAnimation);
        updateIconAlpha(view, alpha, animate);
    }

    private void updateIconAlpha(KeyguardAffordanceView view, float alpha, boolean animate) {
        float scale = getScale(alpha);
        alpha = Math.min(1.0f, alpha);
        view.setImageAlpha(alpha, animate);
        view.setImageScale(scale, animate);
    }

    private float getScale(float alpha) {
        float scale = alpha / SWIPE_RESTING_ALPHA_AMOUNT * 0.2f +
                KeyguardAffordanceView.MIN_ICON_SCALE_AMOUNT;
        return Math.min(scale, KeyguardAffordanceView.MAX_ICON_SCALE_AMOUNT);
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(event);
        }
    }

    private void initVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentVelocity() {
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getXVelocity();
    }

    public void onConfigurationChanged() {
        initDimens();
        initIcons();
    }

    public void onRtlPropertiesChanged() {
        initIcons();
    }

    public void reset(boolean animate) {
        if (mSwipeAnimator != null) {
            mSwipeAnimator.cancel();
        }
        setTranslation(0.0f, true, animate);
        setSwipingInProgress(false);
    }

    public interface Callback {

        /**
         * Notifies the callback when an animation to a side page was started.
         *
         * @param rightPage Is the page animated to the right page?
         */
        void onAnimationToSideStarted(boolean rightPage, float translation, float vel);

        /**
         * Notifies the callback the animation to a side page has ended.
         */
        void onAnimationToSideEnded();

        float getPageWidth();

        void onSwipingStarted();

        KeyguardAffordanceView getLeftIcon();

        KeyguardAffordanceView getCenterIcon();

        KeyguardAffordanceView getRightIcon();

        View getLeftPreview();

        View getRightPreview();

        /**
         * @return The factor the minimum swipe amount should be multiplied with.
         */
        float getAffordanceFalsingFactor();
    }
}
