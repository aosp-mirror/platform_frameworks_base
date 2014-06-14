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
import android.os.PowerManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.util.ArrayList;

/**
 * A touch handler of the Keyguard which is responsible for swiping the content left or right.
 */
public class KeyguardPageSwipeHelper {

    private static final float SWIPE_MAX_ICON_SCALE_AMOUNT = 2.0f;
    public static final float SWIPE_RESTING_ALPHA_AMOUNT = 0.5f;
    public static final long HINT_PHASE1_DURATION = 250;
    private static final long HINT_PHASE2_DURATION = 450;

    private final Context mContext;

    private FlingAnimationUtils mFlingAnimationUtils;
    private Callback mCallback;
    private int mTrackingPointer;
    private VelocityTracker mVelocityTracker;
    private boolean mSwipingInProgress;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mTranslation;
    private float mTranslationOnDown;
    private int mTouchSlop;
    private int mMinTranslationAmount;
    private int mMinFlingVelocity;
    private int mHintDistance;
    private PowerManager mPowerManager;
    private final View mLeftIcon;
    private final View mCenterIcon;
    private final View mRightIcon;
    private Interpolator mFastOutSlowIn;
    private Interpolator mBounceInterpolator;
    private Animator mSwipeAnimator;
    private boolean mCallbackCalled;

    KeyguardPageSwipeHelper(Callback callback, Context context) {
        mContext = context;
        mCallback = callback;
        mLeftIcon = mCallback.getLeftIcon();
        mCenterIcon = mCallback.getCenterIcon();
        mRightIcon = mCallback.getRightIcon();
        updateIcon(mLeftIcon, 1.0f, SWIPE_RESTING_ALPHA_AMOUNT, false);
        updateIcon(mCenterIcon, 1.0f, SWIPE_RESTING_ALPHA_AMOUNT, false);
        updateIcon(mRightIcon, 1.0f, SWIPE_RESTING_ALPHA_AMOUNT, false);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        initDimens();
    }

    private void initDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mMinTranslationAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_min_swipe_amount);
        mHintDistance =
                mContext.getResources().getDimensionPixelSize(R.dimen.hint_move_distance_sideways);
        mFlingAnimationUtils = new FlingAnimationUtils(mContext, 0.4f);
        mFastOutSlowIn = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        mBounceInterpolator = new BounceInterpolator();
    }

    public boolean onTouchEvent(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mSwipingInProgress) {
                    cancelAnimations();
                }
                mInitialTouchY = y;
                mInitialTouchX = x;
                mTranslationOnDown = mTranslation;
                initVelocityTracker();
                trackMovement(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                    mTranslationOnDown = mTranslation;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float w = x - mInitialTouchX;
                trackMovement(event);
                if (((leftSwipePossible() && w > mTouchSlop)
                        || (rightSwipePossible() && w < -mTouchSlop))
                        && Math.abs(w) > Math.abs(y - mInitialTouchY)
                        && !mSwipingInProgress) {
                    cancelAnimations();
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mTranslationOnDown = mTranslation;
                    mSwipingInProgress = true;
                }
                if (mSwipingInProgress) {
                    setTranslation(mTranslationOnDown + x - mInitialTouchX, false);
                    onUserActivity(event.getEventTime());
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTrackingPointer = -1;
                trackMovement(event);
                if (mSwipingInProgress) {
                    flingWithCurrentVelocity();
                }
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
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

    /**
     * Phase 1: Move everything sidewards.
     */
    private void startHintAnimationPhase1(boolean right, final Runnable onFinishedListener) {
        float target = right ? -mHintDistance : mHintDistance;
        startHintTranslationAnimations(target, HINT_PHASE1_DURATION, mFastOutSlowIn);
        ValueAnimator animator = ValueAnimator.ofFloat(mTranslation, target);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTranslation = (float) animation.getAnimatedValue();
            }
        });
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
                } else {
                    startUnlockHintAnimationPhase2(onFinishedListener);
                }
            }
        });
        animator.setInterpolator(mFastOutSlowIn);
        animator.setDuration(HINT_PHASE1_DURATION);
        animator.start();
        mSwipeAnimator = animator;
    }

    /**
     * Phase 2: Move back.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onFinishedListener) {
        startHintTranslationAnimations(0f /* target */, HINT_PHASE2_DURATION, mBounceInterpolator);
        ValueAnimator animator = ValueAnimator.ofFloat(mTranslation, 0f);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTranslation = (float) animation.getAnimatedValue();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnimator = null;
                onFinishedListener.run();
            }
        });
        animator.setInterpolator(mBounceInterpolator);
        animator.setDuration(HINT_PHASE2_DURATION);
        animator.start();
        mSwipeAnimator = animator;
    }

    private void startHintTranslationAnimations(float target, long duration,
            Interpolator interpolator) {
        ArrayList<View> targetViews = mCallback.getTranslationViews();
        for (View targetView : targetViews) {
            targetView.animate()
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .translationX(target);
        }
    }

    private void onUserActivity(long when) {
        mPowerManager.userActivity(when, false);
    }

    private void cancelAnimations() {
        ArrayList<View> targetViews = mCallback.getTranslationViews();
        for (View target : targetViews) {
            target.animate().cancel();
        }
        View targetView = mTranslation > 0 ? mLeftIcon : mRightIcon;
        targetView.animate().cancel();
        if (mSwipeAnimator != null) {
            mSwipeAnimator.cancel();
            hideInactiveIcons(true);
        }
    }

    private void flingWithCurrentVelocity() {
        float vel = getCurrentVelocity();

        // We snap back if the current translation is not far enough
        boolean snapBack = Math.abs(mTranslation) < mMinTranslationAmount;

        // or if the velocity is in the opposite direction.
        boolean velIsInWrongDirection = vel * mTranslation < 0;
        snapBack |= Math.abs(vel) > mMinFlingVelocity && velIsInWrongDirection;
        vel = snapBack ^ velIsInWrongDirection ? 0 : vel;
        fling(vel, snapBack);
    }

    private void fling(float vel, final boolean snapBack) {
        float target = mTranslation < 0 ? -mCallback.getPageWidth() : mCallback.getPageWidth();
        target = snapBack ? 0 : target;

        // translation Animation
        startTranslationAnimations(vel, target);

        // animate left / right icon
        startIconAnimation(vel, snapBack, target);

        ValueAnimator animator = ValueAnimator.ofFloat(mTranslation, target);
        mFlingAnimationUtils.apply(animator, mTranslation, target, vel);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTranslation = (float) animation.getAnimatedValue();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnimator = null;
                mSwipingInProgress = false;
                if (!snapBack && !mCallbackCalled && !mCancelled) {

                    // ensure that the callback is called eventually
                    mCallback.onAnimationToSideStarted(mTranslation < 0);
                    mCallbackCalled = true;
                }
            }
        });
        if (!snapBack) {
            mCallbackCalled = false;
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                int frameNumber;

                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (frameNumber == 2 && !mCallbackCalled) {

                        // we have to wait for the second frame for this call,
                        // until the render thread has definitely kicked in, to avoid a lag.
                        mCallback.onAnimationToSideStarted(mTranslation < 0);
                        mCallbackCalled = true;
                    }
                    frameNumber++;
                }
            });
        } else {
            showAllIcons(true);
        }
        animator.start();
        mSwipeAnimator = animator;
    }

    private void startTranslationAnimations(float vel, float target) {
        ArrayList<View> targetViews = mCallback.getTranslationViews();
        for (View targetView : targetViews) {
            ViewPropertyAnimator animator = targetView.animate();
            mFlingAnimationUtils.apply(animator, mTranslation, target, vel);
            animator.translationX(target);
        }
    }

    private void startIconAnimation(float vel, boolean snapBack, float target) {
        float scale = snapBack ? 1.0f : SWIPE_MAX_ICON_SCALE_AMOUNT;
        float alpha = snapBack ? SWIPE_RESTING_ALPHA_AMOUNT : 1.0f;
        View targetView = mTranslation > 0
                ? mLeftIcon
                : mRightIcon;
        if (targetView.getVisibility() == View.VISIBLE) {
            ViewPropertyAnimator iconAnimator = targetView.animate();
            mFlingAnimationUtils.apply(iconAnimator, mTranslation, target, vel);
            iconAnimator.scaleX(scale);
            iconAnimator.scaleY(scale);
            iconAnimator.alpha(alpha);
        }
    }

    private void setTranslation(float translation, boolean isReset) {
        translation = rightSwipePossible() ? translation : Math.max(0, translation);
        translation = leftSwipePossible() ? translation : Math.min(0, translation);
        if (translation != mTranslation || isReset) {
            ArrayList<View> translatedViews = mCallback.getTranslationViews();
            for (View view : translatedViews) {
                view.setTranslationX(translation);
            }
            if (translation == 0.0f) {
                boolean animate = !isReset;
                showAllIcons(animate);
            } else {
                View targetView = translation > 0 ? mLeftIcon : mRightIcon;
                float progress = Math.abs(translation) / mCallback.getPageWidth();
                progress = Math.min(progress, 1.0f);
                float alpha = SWIPE_RESTING_ALPHA_AMOUNT * (1.0f - progress) + progress;
                float scale = (1.0f - progress) + progress * SWIPE_MAX_ICON_SCALE_AMOUNT;
                updateIcon(targetView, scale, alpha, false);
                View otherView = translation < 0 ? mLeftIcon : mRightIcon;
                if (mTranslation * translation <= 0) {
                    // The sign of the translation has changed so we need to hide the other icons
                    updateIcon(otherView, 0, 0, true);
                    updateIcon(mCenterIcon, 0, 0, true);
                }
            }
            mTranslation = translation;
        }
    }

    public void showAllIcons(boolean animate) {
        float scale = 1.0f;
        float alpha = SWIPE_RESTING_ALPHA_AMOUNT;
        updateIcon(mRightIcon, scale, alpha, animate);
        updateIcon(mCenterIcon, scale, alpha, animate);
        updateIcon(mLeftIcon, scale, alpha, animate);
    }

    public void animateHideLeftRightIcon() {
        updateIcon(mRightIcon, 0f, 0f, true);
        updateIcon(mLeftIcon, 0f, 0f, true);
    }

    private void hideInactiveIcons(boolean animate){
        View otherView = mTranslation < 0 ? mLeftIcon : mRightIcon;
        updateIcon(otherView, 0, 0, animate);
        updateIcon(mCenterIcon, 0, 0, animate);
    }

    private void updateIcon(View view, float scale, float alpha, boolean animate) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!animate) {
            view.animate().cancel();
            view.setAlpha(alpha);
            view.setScaleX(scale);
            view.setScaleY(scale);
            // TODO: remove this invalidate once the property setters invalidate it properly
            view.invalidate();
        } else {
            if (view.getAlpha() != alpha || view.getScaleX() != scale) {
                view.animate()
                        .setInterpolator(mFastOutSlowIn)
                        .alpha(alpha)
                        .scaleX(scale)
                        .scaleY(scale);
            }
        }
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
    }

    public void reset() {
        if (mSwipeAnimator != null) {
            mSwipeAnimator.cancel();
        }
        ArrayList<View> targetViews = mCallback.getTranslationViews();
        for (View view : targetViews) {
            view.animate().cancel();
        }
        setTranslation(0.0f, true);
        mSwipingInProgress = false;
    }

    public boolean isSwipingInProgress() {
        return mSwipingInProgress;
    }

    public interface Callback {

        /**
         * Notifies the callback when an animation to a side page was started.
         *
         * @param rightPage Is the page animated to the right page?
         */
        void onAnimationToSideStarted(boolean rightPage);

        float getPageWidth();

        ArrayList<View> getTranslationViews();

        View getLeftIcon();

        View getCenterIcon();

        View getRightIcon();
    }
}
