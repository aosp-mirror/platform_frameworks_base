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

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.graphics.Rect;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.Interpolators;

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
    public static final int TRANSITION_DIRECTION_TO_SPLIT_SCREEN = 4;
    public static final int TRANSITION_DIRECTION_REMOVE_STACK = 5;

    @IntDef(prefix = { "TRANSITION_DIRECTION_" }, value = {
            TRANSITION_DIRECTION_NONE,
            TRANSITION_DIRECTION_SAME,
            TRANSITION_DIRECTION_TO_PIP,
            TRANSITION_DIRECTION_TO_FULLSCREEN,
            TRANSITION_DIRECTION_TO_SPLIT_SCREEN,
            TRANSITION_DIRECTION_REMOVE_STACK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionDirection {}

    public static boolean isInPipDirection(@TransitionDirection int direction) {
        return direction == TRANSITION_DIRECTION_TO_PIP;
    }

    public static boolean isOutPipDirection(@TransitionDirection int direction) {
        return direction == TRANSITION_DIRECTION_TO_FULLSCREEN
                || direction == TRANSITION_DIRECTION_TO_SPLIT_SCREEN;
    }

    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;

    private PipTransitionAnimator mCurrentAnimator;

    private ThreadLocal<AnimationHandler> mSfAnimationHandlerThreadLocal =
            ThreadLocal.withInitial(() -> {
                AnimationHandler handler = new AnimationHandler();
                handler.setProvider(new SfVsyncFrameCallbackProvider());
                return handler;
            });

    @Inject
    PipAnimationController(PipSurfaceTransactionHelper helper) {
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
    PipTransitionAnimator getAnimator(SurfaceControl leash, Rect startBounds, Rect endBounds,
            Rect sourceHintRect) {
        if (mCurrentAnimator == null) {
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, startBounds, endBounds, sourceHintRect));
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_ALPHA
                && mCurrentAnimator.isRunning()) {
            // If we are still animating the fade into pip, then just move the surface and ensure
            // we update with the new destination bounds, but don't interrupt the existing animation
            // with a new bounds
            mCurrentAnimator.setDestinationBounds(endBounds);
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_BOUNDS
                && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.setDestinationBounds(endBounds);
            // construct new Rect instances in case they are recycled
            mCurrentAnimator.updateEndValue(new Rect(endBounds));
        } else {
            mCurrentAnimator.cancel();
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, startBounds, endBounds, sourceHintRect));
        }
        return mCurrentAnimator;
    }

    PipTransitionAnimator getCurrentAnimator() {
        return mCurrentAnimator;
    }

    private PipTransitionAnimator setupPipTransitionAnimator(PipTransitionAnimator animator) {
        animator.setSurfaceTransactionHelper(mSurfaceTransactionHelper);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.setFloatValues(FRACTION_START, FRACTION_END);
        animator.setAnimationHandler(mSfAnimationHandlerThreadLocal.get());
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

        protected T mCurrentValue;
        protected T mStartValue;
        private T mEndValue;
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
            if (mAnimationType == ANIM_TYPE_ALPHA) {
                onStartTransaction(mLeash, newSurfaceControlTransaction());
            }
        }

        void setCurrentValue(T value) {
            mCurrentValue = value;
        }

        boolean shouldApplyCornerRadius() {
            return !isOutPipDirection(mTransitionDirection);
        }

        boolean inScaleTransition() {
            if (mAnimationType != ANIM_TYPE_BOUNDS) return false;
            return !isInPipDirection(getTransitionDirection());
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
                    if (getTransitionDirection() == TRANSITION_DIRECTION_REMOVE_STACK) {
                        // while removing the pip stack, no extra work needs to be done here.
                        return;
                    }
                    getSurfaceTransactionHelper()
                            .resetScale(tx, leash, getDestinationBounds())
                            .crop(tx, leash, getDestinationBounds())
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.show(leash);
                    tx.apply();
                }

                @Override
                void updateEndValue(Float endValue) {
                    super.updateEndValue(endValue);
                    mStartValue = mCurrentValue;
                }
            };
        }

        static PipTransitionAnimator<Rect> ofBounds(SurfaceControl leash,
                Rect startValue, Rect endValue, Rect sourceHintRect) {
            // Just for simplicity we'll interpolate between the source rect hint insets and empty
            // insets to calculate the window crop
            final Rect initialStartValue = new Rect(startValue);
            final Rect sourceHintRectInsets = sourceHintRect != null
                    ? new Rect(sourceHintRect.left - startValue.left,
                            sourceHintRect.top - startValue.top,
                            startValue.right - sourceHintRect.right,
                            startValue.bottom - sourceHintRect.bottom)
                    : null;
            final Rect sourceInsets = new Rect(0, 0, 0, 0);

            // construct new Rect instances in case they are recycled
            return new PipTransitionAnimator<Rect>(leash, ANIM_TYPE_BOUNDS,
                    endValue, new Rect(startValue), new Rect(endValue)) {
                private final RectEvaluator mRectEvaluator = new RectEvaluator(new Rect());
                private final RectEvaluator mInsetsEvaluator = new RectEvaluator(new Rect());

                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final Rect start = getStartValue();
                    final Rect end = getEndValue();
                    Rect bounds = mRectEvaluator.evaluate(fraction, start, end);
                    setCurrentValue(bounds);
                    if (inScaleTransition()) {
                        if (isOutPipDirection(getTransitionDirection())) {
                            getSurfaceTransactionHelper().scale(tx, leash, end, bounds);
                        } else {
                            getSurfaceTransactionHelper().scale(tx, leash, start, bounds);
                        }
                    } else {
                        if (sourceHintRectInsets != null) {
                            Rect insets = mInsetsEvaluator.evaluate(fraction, sourceInsets,
                                    sourceHintRectInsets);
                            getSurfaceTransactionHelper().scaleAndCrop(tx, leash, initialStartValue,
                                    bounds, insets);
                        } else {
                            getSurfaceTransactionHelper().scale(tx, leash, start, bounds);
                        }
                    }
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    getSurfaceTransactionHelper()
                            .alpha(tx, leash, 1f)
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.show(leash);
                    tx.apply();
                }

                @Override
                void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    // NOTE: intentionally does not apply the transaction here.
                    // this end transaction should get executed synchronously with the final
                    // WindowContainerTransaction in task organizer
                    getSurfaceTransactionHelper()
                            .resetScale(tx, leash, getDestinationBounds())
                            .crop(tx, leash, getDestinationBounds());
                }

                @Override
                void updateEndValue(Rect endValue) {
                    super.updateEndValue(endValue);
                    if (mStartValue != null && mCurrentValue != null) {
                        mStartValue.set(mCurrentValue);
                    }
                }
            };
        }
    }
}
