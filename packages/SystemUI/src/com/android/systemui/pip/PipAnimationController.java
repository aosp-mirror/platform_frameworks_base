/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.pip;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Controller class of PiP animations (both from and to PiP mode).
 */
public class PipAnimationController {
    private static final float FRACTION_START = 0f;
    private static final float FRACTION_END = 1f;

    public static final int ANIM_TYPE_BOUNDS = 0;
    public static final int ANIM_TYPE_ALPHA = 1;

    @IntDef(prefix = { "ANIM_TYPE_" }, value = {
            ANIM_TYPE_BOUNDS,
            ANIM_TYPE_ALPHA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnimationType {}

    public static final int TRANSITION_DIRECTION_NONE = 0;
    public static final int TRANSITION_DIRECTION_SAME = 1;
    public static final int TRANSITION_DIRECTION_TO_PIP = 2;
    public static final int TRANSITION_DIRECTION_TO_FULLSCREEN = 3;

    @IntDef(prefix = { "TRANSITION_DIRECTION_" }, value = {
            TRANSITION_DIRECTION_NONE,
            TRANSITION_DIRECTION_SAME,
            TRANSITION_DIRECTION_TO_PIP,
            TRANSITION_DIRECTION_TO_FULLSCREEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionDirection {}

    private final Interpolator mFastOutSlowInInterpolator;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;

    private PipTransitionAnimator mCurrentAnimator;

    @Inject
    PipAnimationController(Context context, PipSurfaceTransactionHelper helper) {
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mSurfaceTransactionHelper = helper;
    }

    @SuppressWarnings("unchecked")
    PipTransitionAnimator getAnimator(SurfaceControl leash,
            Rect destinationBounds, float alphaStart, float alphaEnd) {
        if (mCurrentAnimator == null) {
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofAlpha(leash, destinationBounds, alphaStart, alphaEnd));
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_ALPHA
                && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.updateEndValue(alphaEnd);
        } else {
            mCurrentAnimator.cancel();
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofAlpha(leash, destinationBounds, alphaStart, alphaEnd));
        }
        return mCurrentAnimator;
    }

    @SuppressWarnings("unchecked")
    PipTransitionAnimator getAnimator(SurfaceControl leash, Rect startBounds, Rect endBounds) {
        if (mCurrentAnimator == null) {
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, startBounds, endBounds));
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_BOUNDS
                && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.setDestinationBounds(endBounds);
            // construct new Rect instances in case they are recycled
            mCurrentAnimator.updateEndValue(new Rect(endBounds));
        } else {
            mCurrentAnimator.cancel();
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, startBounds, endBounds));
        }
        return mCurrentAnimator;
    }

    private PipTransitionAnimator setupPipTransitionAnimator(PipTransitionAnimator animator) {
        animator.setSurfaceTransactionHelper(mSurfaceTransactionHelper);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.setFloatValues(FRACTION_START, FRACTION_END);
        return animator;
    }

    /**
     * Additional callback interface for PiP animation
     */
    public static class PipAnimationCallback {
        /**
         * Called when PiP animation is started.
         */
        public void onPipAnimationStart(PipTransitionAnimator animator) {}

        /**
         * Called when PiP animation is ended.
         */
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipTransitionAnimator animator) {}

        /**
         * Called when PiP animation is cancelled.
         */
        public void onPipAnimationCancel(PipTransitionAnimator animator) {}
    }

    /**
     * Animator for PiP transition animation which supports both alpha and bounds animation.
     * @param <T> Type of property to animate, either alpha (float) or bounds (Rect)
     */
    public abstract static class PipTransitionAnimator<T> extends ValueAnimator implements
            ValueAnimator.AnimatorUpdateListener,
            ValueAnimator.AnimatorListener {
        private final SurfaceControl mLeash;
        private final @AnimationType int mAnimationType;
        private final Rect mDestinationBounds = new Rect();

        private T mStartValue;
        private T mEndValue;
        private T mCurrentValue;
        private PipAnimationCallback mPipAnimationCallback;
        private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
                mSurfaceControlTransactionFactory;
        private PipSurfaceTransactionHelper mSurfaceTransactionHelper;
        private @TransitionDirection int mTransitionDirection;

        private PipTransitionAnimator(SurfaceControl leash, @AnimationType int animationType,
                Rect destinationBounds, T startValue, T endValue) {
            mLeash = leash;
            mAnimationType = animationType;
            mDestinationBounds.set(destinationBounds);
            mStartValue = startValue;
            mEndValue = endValue;
            addListener(this);
            addUpdateListener(this);
            mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
            mTransitionDirection = TRANSITION_DIRECTION_NONE;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mCurrentValue = mStartValue;
            onStartTransaction(mLeash, newSurfaceControlTransaction());
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationStart(this);
            }
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            applySurfaceControlTransaction(mLeash, newSurfaceControlTransaction(),
                    animation.getAnimatedFraction());
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentValue = mEndValue;
            final SurfaceControl.Transaction tx = newSurfaceControlTransaction();
            onEndTransaction(mLeash, tx);
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationEnd(tx, this);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationCancel(this);
            }
        }

        @Override public void onAnimationRepeat(Animator animation) {}

        @AnimationType int getAnimationType() {
            return mAnimationType;
        }

        PipTransitionAnimator<T> setPipAnimationCallback(PipAnimationCallback callback) {
            mPipAnimationCallback = callback;
            return this;
        }

        @TransitionDirection int getTransitionDirection() {
            return mTransitionDirection;
        }

        PipTransitionAnimator<T> setTransitionDirection(@TransitionDirection int direction) {
            if (direction != TRANSITION_DIRECTION_SAME) {
                mTransitionDirection = direction;
            }
            return this;
        }

        T getStartValue() {
            return mStartValue;
        }

        T getEndValue() {
            return mEndValue;
        }

        Rect getDestinationBounds() {
            return mDestinationBounds;
        }

        void setDestinationBounds(Rect destinationBounds) {
            mDestinationBounds.set(destinationBounds);
        }

        void setCurrentValue(T value) {
            mCurrentValue = value;
        }

        boolean shouldApplyCornerRadius() {
            return mTransitionDirection != TRANSITION_DIRECTION_TO_FULLSCREEN;
        }

        boolean inScaleTransition() {
            if (mAnimationType != ANIM_TYPE_BOUNDS) return false;
            final int direction = getTransitionDirection();
            return direction != TRANSITION_DIRECTION_TO_FULLSCREEN
                    && direction != TRANSITION_DIRECTION_TO_PIP;
        }

        /**
         * Updates the {@link #mEndValue}.
         *
         * NOTE: Do not forget to call {@link #setDestinationBounds(Rect)} for bounds animation.
         * This is typically used when we receive a shelf height adjustment during the bounds
         * animation. In which case we can update the end bounds and keep the existing animation
         * running instead of cancelling it.
         */
        void updateEndValue(T endValue) {
            mEndValue = endValue;
            mStartValue = mCurrentValue;
        }

        SurfaceControl.Transaction newSurfaceControlTransaction() {
            return mSurfaceControlTransactionFactory.getTransaction();
        }

        @VisibleForTesting
        void setSurfaceControlTransactionFactory(
                PipSurfaceTransactionHelper.SurfaceControlTransactionFactory factory) {
            mSurfaceControlTransactionFactory = factory;
        }

        PipSurfaceTransactionHelper getSurfaceTransactionHelper() {
            return mSurfaceTransactionHelper;
        }

        void setSurfaceTransactionHelper(PipSurfaceTransactionHelper helper) {
            mSurfaceTransactionHelper = helper;
        }

        void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {}

        void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {}

        abstract void applySurfaceControlTransaction(SurfaceControl leash,
                SurfaceControl.Transaction tx, float fraction);

        static PipTransitionAnimator<Float> ofAlpha(SurfaceControl leash,
                Rect destinationBounds, float startValue, float endValue) {
            return new PipTransitionAnimator<Float>(leash, ANIM_TYPE_ALPHA,
                    destinationBounds, startValue, endValue) {
                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final float alpha = getStartValue() * (1 - fraction) + getEndValue() * fraction;
                    setCurrentValue(alpha);
                    getSurfaceTransactionHelper().alpha(tx, leash, alpha);
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    getSurfaceTransactionHelper()
                            .crop(tx, leash, getDestinationBounds())
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.apply();
                }
            };
        }

        static PipTransitionAnimator<Rect> ofBounds(SurfaceControl leash,
                Rect startValue, Rect endValue) {
            // construct new Rect instances in case they are recycled
            return new PipTransitionAnimator<Rect>(leash, ANIM_TYPE_BOUNDS,
                    endValue, new Rect(startValue), new Rect(endValue)) {
                private final Rect mTmpRect = new Rect();

                private int getCastedFractionValue(float start, float end, float fraction) {
                    return (int) (start * (1 - fraction) + end * fraction + .5f);
                }

                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final Rect start = getStartValue();
                    final Rect end = getEndValue();
                    mTmpRect.set(
                            getCastedFractionValue(start.left, end.left, fraction),
                            getCastedFractionValue(start.top, end.top, fraction),
                            getCastedFractionValue(start.right, end.right, fraction),
                            getCastedFractionValue(start.bottom, end.bottom, fraction));
                    setCurrentValue(mTmpRect);
                    if (inScaleTransition()) {
                        getSurfaceTransactionHelper().scale(tx, leash, start, mTmpRect);
                    } else {
                        getSurfaceTransactionHelper().crop(tx, leash, mTmpRect);
                    }
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    getSurfaceTransactionHelper()
                            .alpha(tx, leash, 1f)
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.apply();
                }

                @Override
                void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    if (!inScaleTransition()) return;
                    // NOTE: intentionally does not apply the transaction here.
                    // this end transaction should get executed synchronously with the final
                    // WindowContainerTransaction in task organizer
                    getSurfaceTransactionHelper().resetScale(tx, leash, getDestinationBounds());
                }
            };
        }
    }
}
