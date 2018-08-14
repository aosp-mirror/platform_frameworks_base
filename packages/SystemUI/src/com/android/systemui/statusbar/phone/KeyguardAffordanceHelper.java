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

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.KeyguardAffordanceView;

/**
 * A touch handler of the keyguard which is responsible for launching phone and camera affordances.
 */
public class KeyguardAffordanceHelper {

    public static final float SWIPE_RESTING_ALPHA_AMOUNT = 0.5f;
    public static final long HINT_PHASE1_DURATION = 200;
    private static final long HINT_PHASE2_DURATION = 350;
    private static final float BACKGROUND_RADIUS_SCALE_FACTOR = 0.25f;
    private static final int HINT_CIRCLE_OPEN_DURATION = 500;

    private final Context mContext;
    private final Callback mCallback;

    private FlingAnimationUtils mFlingAnimationUtils;
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
    private Animator mSwipeAnimator;
    private FalsingManager mFalsingManager;
    private int mMinBackgroundRadius;
    private boolean mMotionCancelled;
    private int mTouchTargetSize;
    private View mTargetedView;
    private boolean mTouchSlopExeeded;
    private AnimatorListenerAdapter mFlingEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSwipeAnimator = null;
            mSwipingInProgress = false;
            mTargetedView = null;
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
        updateIcon(mLeftIcon, 0.0f, mLeftIcon.getRestingAlpha(), false, false, true, false);
        updateIcon(mCenterIcon, 0.0f, mCenterIcon.getRestingAlpha(), false, false, true, false);
        updateIcon(mRightIcon, 0.0f, mRightIcon.getRestingAlpha(), false, false, true, false);
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
        mTouchTargetSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_affordance_touch_target_size);
        mHintGrowAmount =
                mContext.getResources().getDimensionPixelSize(R.dimen.hint_grow_amount_sideways);
        mFlingAnimationUtils = new FlingAnimationUtils(mContext, 0.4f);
        mFalsingManager = FalsingManager.getInstance(mContext);
    }

    private void initIcons() {
        mLeftIcon = mCallback.getLeftIcon();
        mCenterIcon = mCallback.getCenterIcon();
        mRightIcon = mCallback.getRightIcon();
        updatePreviews();
    }

    public void updatePreviews() {
        mLeftIcon.setPreviewView(mCallback.getLeftPreview());
        mRightIcon.setPreviewView(mCallback.getRightPreview());
    }

    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (mMotionCancelled && action != MotionEvent.ACTION_DOWN) {
            return false;
        }
        final float y = event.getY();
        final float x = event.getX();

        boolean isUp = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                View targetView = getIconAtPosition(x, y);
                if (targetView == null || (mTargetedView != null && mTargetedView != targetView)) {
                    mMotionCancelled = true;
                    return false;
                }
                if (mTargetedView != null) {
                    cancelAnimation();
                } else {
                    mTouchSlopExeeded = false;
                }
                startSwiping(targetView);
                mInitialTouchX = x;
                mInitialTouchY = y;
                mTranslationOnDown = mTranslation;
                initVelocityTracker();
                trackMovement(event);
                mMotionCancelled = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mMotionCancelled = true;
                endMotion(true /* forceSnapBack */, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                trackMovement(event);
                float xDist = x - mInitialTouchX;
                float yDist = y - mInitialTouchY;
                float distance = (float) Math.hypot(xDist, yDist);
                if (!mTouchSlopExeeded && distance > mTouchSlop) {
                    mTouchSlopExeeded = true;
                }
                if (mSwipingInProgress) {
                    if (mTargetedView == mRightIcon) {
                        distance = mTranslationOnDown - distance;
                        distance = Math.min(0, distance);
                    } else {
                        distance = mTranslationOnDown + distance;
                        distance = Math.max(0, distance);
                    }
                    setTranslation(distance, false /* isReset */, false /* animateReset */);
                }
                break;

            case MotionEvent.ACTION_UP:
                isUp = true;
            case MotionEvent.ACTION_CANCEL:
                boolean hintOnTheRight = mTargetedView == mRightIcon;
                trackMovement(event);
                endMotion(!isUp, x, y);
                if (!mTouchSlopExeeded && isUp) {
                    mCallback.onIconClicked(hintOnTheRight);
                }
                break;
        }
        return true;
    }

    private void startSwiping(View targetView) {
        mCallback.onSwipingStarted(targetView == mRightIcon);
        mSwipingInProgress = true;
        mTargetedView = targetView;
    }

    private View getIconAtPosition(float x, float y) {
        if (leftSwipePossible() && isOnIcon(mLeftIcon, x, y)) {
            return mLeftIcon;
        }
        if (rightSwipePossible() && isOnIcon(mRightIcon, x, y)) {
            return mRightIcon;
        }
        return null;
    }

    public boolean isOnAffordanceIcon(float x, float y) {
        return isOnIcon(mLeftIcon, x, y) || isOnIcon(mRightIcon, x, y);
    }

    private boolean isOnIcon(View icon, float x, float y) {
        float iconX = icon.getX() + icon.getWidth() / 2.0f;
        float iconY = icon.getY() + icon.getHeight() / 2.0f;
        double distance = Math.hypot(x - iconX, y - iconY);
        return distance <= mTouchTargetSize / 2;
    }

    private void endMotion(boolean forceSnapBack, float lastX, float lastY) {
        if (mSwipingInProgress) {
            flingWithCurrentVelocity(forceSnapBack, lastX, lastY);
        } else {
            mTargetedView = null;
        }
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
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

    public void startHintAnimation(boolean right,
            Runnable onFinishedListener) {
        cancelAnimation();
        startHintAnimationPhase1(right, onFinishedListener);
    }

    private void startHintAnimationPhase1(final boolean right, final Runnable onFinishedListener) {
        final KeyguardAffordanceView targetView = right ? mRightIcon : mLeftIcon;
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
                    mTargetedView = null;
                    onFinishedListener.run();
                } else {
                    startUnlockHintAnimationPhase2(right, onFinishedListener);
                }
            }
        });
        animator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        animator.setDuration(HINT_PHASE1_DURATION);
        animator.start();
        mSwipeAnimator = animator;
        mTargetedView = targetView;
    }

    /**
     * Phase 2: Move back.
     */
    private void startUnlockHintAnimationPhase2(boolean right, final Runnable onFinishedListener) {
        ValueAnimator animator = getAnimatorToRadius(right, 0);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnimator = null;
                mTargetedView = null;
                onFinishedListener.run();
            }
        });
        animator.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
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
                updateIconsFromTranslation(targetView);
            }
        });
        return animator;
    }

    private void cancelAnimation() {
        if (mSwipeAnimator != null) {
            mSwipeAnimator.cancel();
        }
    }

    private void flingWithCurrentVelocity(boolean forceSnapBack, float lastX, float lastY) {
        float vel = getCurrentVelocity(lastX, lastY);

        // We snap back if the current translation is not far enough
        boolean snapBack = false;
        if (mCallback.needsAntiFalsing()) {
            snapBack = snapBack || mFalsingManager.isFalseTouch();
        }
        snapBack = snapBack || isBelowFalsingThreshold();

        // or if the velocity is in the opposite direction.
        boolean velIsInWrongDirection = vel * mTranslation < 0;
        snapBack |= Math.abs(vel) > mMinFlingVelocity && velIsInWrongDirection;
        vel = snapBack ^ velIsInWrongDirection ? 0 : vel;
        fling(vel, snapBack || forceSnapBack, mTranslation < 0);
    }

    private boolean isBelowFalsingThreshold() {
        return Math.abs(mTranslation) < Math.abs(mTranslationOnDown) + getMinTranslationAmount();
    }

    private int getMinTranslationAmount() {
        float factor = mCallback.getAffordanceFalsingFactor();
        return (int) (mMinTranslationAmount * factor);
    }

    private void fling(float vel, final boolean snapBack, boolean right) {
        float target = right ? -mCallback.getMaxTranslationDistance()
                : mCallback.getMaxTranslationDistance();
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
            startFinishingCircleAnimation(vel * 0.375f, mAnimationEndRunnable, right);
            mCallback.onAnimationToSideStarted(right, mTranslation, vel);
        } else {
            reset(true);
        }
        animator.start();
        mSwipeAnimator = animator;
        if (snapBack) {
            mCallback.onSwipingAborted();
        }
    }

    private void startFinishingCircleAnimation(float velocity, Runnable mAnimationEndRunnable,
            boolean right) {
        KeyguardAffordanceView targetView = right ? mRightIcon : mLeftIcon;
        targetView.finishAnimation(velocity, mAnimationEndRunnable);
    }

    private void setTranslation(float translation, boolean isReset, boolean animateReset) {
        translation = rightSwipePossible() ? translation : Math.max(0, translation);
        translation = leftSwipePossible() ? translation : Math.min(0, translation);
        float absTranslation = Math.abs(translation);
        if (translation != mTranslation || isReset) {
            KeyguardAffordanceView targetView = translation > 0 ? mLeftIcon : mRightIcon;
            KeyguardAffordanceView otherView = translation > 0 ? mRightIcon : mLeftIcon;
            float alpha = absTranslation / getMinTranslationAmount();

            // We interpolate the alpha of the other icons to 0
            float fadeOutAlpha = 1.0f - alpha;
            fadeOutAlpha = Math.max(fadeOutAlpha, 0.0f);

            boolean animateIcons = isReset && animateReset;
            boolean forceNoCircleAnimation = isReset && !animateReset;
            float radius = getRadiusFromTranslation(absTranslation);
            boolean slowAnimation = isReset && isBelowFalsingThreshold();
            if (!isReset) {
                updateIcon(targetView, radius, alpha + fadeOutAlpha * targetView.getRestingAlpha(),
                        false, false, false, false);
            } else {
                updateIcon(targetView, 0.0f, fadeOutAlpha * targetView.getRestingAlpha(),
                        animateIcons, slowAnimation, true /* isReset */, forceNoCircleAnimation);
            }
            updateIcon(otherView, 0.0f, fadeOutAlpha * otherView.getRestingAlpha(),
                    animateIcons, slowAnimation, isReset, forceNoCircleAnimation);
            updateIcon(mCenterIcon, 0.0f, fadeOutAlpha * mCenterIcon.getRestingAlpha(),
                    animateIcons, slowAnimation, isReset, forceNoCircleAnimation);

            mTranslation = translation;
        }
    }

    private void updateIconsFromTranslation(KeyguardAffordanceView targetView) {
        float absTranslation = Math.abs(mTranslation);
        float alpha = absTranslation / getMinTranslationAmount();

        // We interpolate the alpha of the other icons to 0
        float fadeOutAlpha =  1.0f - alpha;
        fadeOutAlpha = Math.max(0.0f, fadeOutAlpha);

        // We interpolate the alpha of the targetView to 1
        KeyguardAffordanceView otherView = targetView == mRightIcon ? mLeftIcon : mRightIcon;
        updateIconAlpha(targetView, alpha + fadeOutAlpha * targetView.getRestingAlpha(), false);
        updateIconAlpha(otherView, fadeOutAlpha * otherView.getRestingAlpha(), false);
        updateIconAlpha(mCenterIcon, fadeOutAlpha * mCenterIcon.getRestingAlpha(), false);
    }

    private float getTranslationFromRadius(float circleSize) {
        float translation = (circleSize - mMinBackgroundRadius)
                / BACKGROUND_RADIUS_SCALE_FACTOR;
        return translation > 0.0f ? translation + mTouchSlop : 0.0f;
    }

    private float getRadiusFromTranslation(float translation) {
        if (translation <= mTouchSlop) {
            return 0.0f;
        }
        return (translation - mTouchSlop)  * BACKGROUND_RADIUS_SCALE_FACTOR + mMinBackgroundRadius;
    }

    public void animateHideLeftRightIcon() {
        cancelAnimation();
        updateIcon(mRightIcon, 0f, 0f, true, false, false, false);
        updateIcon(mLeftIcon, 0f, 0f, true, false, false, false);
    }

    private void updateIcon(KeyguardAffordanceView view, float circleRadius, float alpha,
                            boolean animate, boolean slowRadiusAnimation, boolean force,
                            boolean forceNoCircleAnimation) {
        if (view.getVisibility() != View.VISIBLE && !force) {
            return;
        }
        if (forceNoCircleAnimation) {
            view.setCircleRadiusWithoutAnimation(circleRadius);
        } else {
            view.setCircleRadius(circleRadius, slowRadiusAnimation);
        }
        updateIconAlpha(view, alpha, animate);
    }

    private void updateIconAlpha(KeyguardAffordanceView view, float alpha, boolean animate) {
        float scale = getScale(alpha, view);
        alpha = Math.min(1.0f, alpha);
        view.setImageAlpha(alpha, animate);
        view.setImageScale(scale, animate);
    }

    private float getScale(float alpha, KeyguardAffordanceView icon) {
        float scale = alpha / icon.getRestingAlpha() * 0.2f +
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

    private float getCurrentVelocity(float lastX, float lastY) {
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        float aX = mVelocityTracker.getXVelocity();
        float aY = mVelocityTracker.getYVelocity();
        float bX = lastX - mInitialTouchX;
        float bY = lastY - mInitialTouchY;
        float bLen = (float) Math.hypot(bX, bY);
        // Project the velocity onto the distance vector: a * b / |b|
        float projectedVelocity = (aX * bX + aY * bY) / bLen;
        if (mTargetedView == mRightIcon) {
            projectedVelocity = -projectedVelocity;
        }
        return projectedVelocity;
    }

    public void onConfigurationChanged() {
        initDimens();
        initIcons();
    }

    public void onRtlPropertiesChanged() {
        initIcons();
    }

    public void reset(boolean animate) {
        cancelAnimation();
        setTranslation(0.0f, true /* isReset */, animate);
        mMotionCancelled = true;
        if (mSwipingInProgress) {
            mCallback.onSwipingAborted();
            mSwipingInProgress = false;
        }
    }

    public boolean isSwipingInProgress() {
        return mSwipingInProgress;
    }

    public void launchAffordance(boolean animate, boolean left) {
        if (mSwipingInProgress) {
            // We don't want to mess with the state if the user is actually swiping already.
            return;
        }
        KeyguardAffordanceView targetView = left ? mLeftIcon : mRightIcon;
        KeyguardAffordanceView otherView = left ? mRightIcon : mLeftIcon;
        startSwiping(targetView);

        // Do not animate the circle expanding if the affordance isn't visible,
        // otherwise the circle will be meaningless.
        if (targetView.getVisibility() != View.VISIBLE) {
            animate = false;
        }

        if (animate) {
            fling(0, false, !left);
            updateIcon(otherView, 0.0f, 0, true, false, true, false);
            updateIcon(mCenterIcon, 0.0f, 0, true, false, true, false);
        } else {
            mCallback.onAnimationToSideStarted(!left, mTranslation, 0);
            mTranslation = left ? mCallback.getMaxTranslationDistance()
                    : mCallback.getMaxTranslationDistance();
            updateIcon(mCenterIcon, 0.0f, 0.0f, false, false, true, false);
            updateIcon(otherView, 0.0f, 0.0f, false, false, true, false);
            targetView.instantFinishAnimation();
            mFlingEndListener.onAnimationEnd(null);
            mAnimationEndRunnable.run();
        }
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

        float getMaxTranslationDistance();

        void onSwipingStarted(boolean rightIcon);

        void onSwipingAborted();

        void onIconClicked(boolean rightIcon);

        KeyguardAffordanceView getLeftIcon();

        KeyguardAffordanceView getCenterIcon();

        KeyguardAffordanceView getRightIcon();

        View getLeftPreview();

        View getRightPreview();

        /**
         * @return The factor the minimum swipe amount should be multiplied with.
         */
        float getAffordanceFalsingFactor();

        boolean needsAntiFalsing();
    }
}
