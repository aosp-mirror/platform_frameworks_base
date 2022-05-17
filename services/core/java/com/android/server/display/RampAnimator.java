/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import android.animation.ValueAnimator;
import android.util.FloatProperty;
import android.view.Choreographer;

/**
 * A custom animator that progressively updates a property value at
 * a given variable rate until it reaches a particular target value.
 * The ramping at the given rate is done in the perceptual space using
 * the HLG transfer functions.
 */
class RampAnimator<T> {
    private final T mObject;
    private final FloatProperty<T> mProperty;
    private final Choreographer mChoreographer;

    private float mCurrentValue;
    private float mTargetValue;
    private float mRate;

    private boolean mAnimating;
    private float mAnimatedValue; // higher precision copy of mCurrentValue
    private long mLastFrameTimeNanos;

    private boolean mFirstTime = true;

    private Listener mListener;

    public RampAnimator(T object, FloatProperty<T> property) {
        mObject = object;
        mProperty = property;
        mChoreographer = Choreographer.getInstance();
    }

    /**
     * Starts animating towards the specified value.
     *
     * If this is the first time the property is being set or if the rate is 0,
     * the value jumps directly to the target.
     *
     * @param target The target value.
     * @param rate The convergence rate in units per second, or 0 to set the value immediately.
     * @return True if the target differs from the previous target.
     */
    public boolean animateTo(float targetLinear, float rate) {
        // Convert the target from the linear into the HLG space.
        final float target = BrightnessUtils.convertLinearToGamma(targetLinear);

        // Immediately jump to the target the first time.
        if (mFirstTime || rate <= 0) {
            if (mFirstTime || target != mCurrentValue) {
                mFirstTime = false;
                mRate = 0;
                mTargetValue = target;
                mCurrentValue = target;
                setPropertyValue(target);
                if (mAnimating) {
                    mAnimating = false;
                    cancelAnimationCallback();
                }
                if (mListener != null) {
                    mListener.onAnimationEnd();
                }
                return true;
            }
            return false;
        }

        // Adjust the rate based on the closest target.
        // If a faster rate is specified, then use the new rate so that we converge
        // more rapidly based on the new request.
        // If a slower rate is specified, then use the new rate only if the current
        // value is somewhere in between the new and the old target meaning that
        // we will be ramping in a different direction to get there.
        // Otherwise, continue at the previous rate.
        if (!mAnimating
                || rate > mRate
                || (target <= mCurrentValue && mCurrentValue <= mTargetValue)
                || (mTargetValue <= mCurrentValue && mCurrentValue <= target)) {
            mRate = rate;
        }

        final boolean changed = (mTargetValue != target);
        mTargetValue = target;

        // Start animating.
        if (!mAnimating && target != mCurrentValue) {
            mAnimating = true;
            mAnimatedValue = mCurrentValue;
            mLastFrameTimeNanos = System.nanoTime();
            postAnimationCallback();
        }

        return changed;
    }

    /**
     * Returns true if the animation is running.
     */
    public boolean isAnimating() {
        return mAnimating;
    }

    /**
     * Sets a listener to watch for animation events.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Sets the brightness property by converting the given value from HLG space
     * into linear space.
     */
    private void setPropertyValue(float val) {
        final float linearVal = BrightnessUtils.convertGammaToLinear(val);
        mProperty.setValue(mObject, linearVal);
    }

    private void postAnimationCallback() {
        mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, mAnimationCallback, null);
    }

    private void cancelAnimationCallback() {
        mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION, mAnimationCallback, null);
    }

    private final Runnable mAnimationCallback = new Runnable() {
        @Override // Choreographer callback
        public void run() {
            final long frameTimeNanos = mChoreographer.getFrameTimeNanos();
            final float timeDelta = (frameTimeNanos - mLastFrameTimeNanos)
                    * 0.000000001f;
            mLastFrameTimeNanos = frameTimeNanos;

            // Advance the animated value towards the target at the specified rate
            // and clamp to the target. This gives us the new current value but
            // we keep the animated value around to allow for fractional increments
            // towards the target.
            final float scale = ValueAnimator.getDurationScale();
            if (scale == 0) {
                // Animation off.
                mAnimatedValue = mTargetValue;
            } else {
                final float amount = timeDelta * mRate / scale;
                if (mTargetValue > mCurrentValue) {
                    mAnimatedValue = Math.min(mAnimatedValue + amount, mTargetValue);
                } else {
                    mAnimatedValue = Math.max(mAnimatedValue - amount, mTargetValue);
                }
            }
            final float oldCurrentValue = mCurrentValue;
            mCurrentValue = mAnimatedValue;
            if (oldCurrentValue != mCurrentValue) {
                setPropertyValue(mCurrentValue);
            }
            if (mTargetValue != mCurrentValue) {
                postAnimationCallback();
            } else {
                mAnimating = false;
                if (mListener != null) {
                    mListener.onAnimationEnd();
                }
            }
        }
    };

    public interface Listener {
        void onAnimationEnd();
    }

    static class DualRampAnimator<T> {
        private final RampAnimator<T> mFirst;
        private final RampAnimator<T> mSecond;
        private final Listener mInternalListener = new Listener() {
            @Override
            public void onAnimationEnd() {
                if (mListener != null && !isAnimating()) {
                    mListener.onAnimationEnd();
                }
            }
        };

        private Listener mListener;

        DualRampAnimator(T object, FloatProperty<T> firstProperty,
                FloatProperty<T> secondProperty) {
            mFirst = new RampAnimator(object, firstProperty);
            mFirst.setListener(mInternalListener);
            mSecond = new RampAnimator(object, secondProperty);
            mSecond.setListener(mInternalListener);
        }

        /**
         * Starts animating towards the specified values.
         *
         * If this is the first time the property is being set or if the rate is 0,
         * the value jumps directly to the target.
         *
         * @param linearFirstTarget The first target value in linear space.
         * @param linearSecondTarget The second target value in linear space.
         * @param rate The convergence rate in units per second, or 0 to set the value immediately.
         * @return True if either target differs from the previous target.
         */
        public boolean animateTo(float linearFirstTarget, float linearSecondTarget, float rate) {
            final boolean firstRetval = mFirst.animateTo(linearFirstTarget, rate);
            final boolean secondRetval = mSecond.animateTo(linearSecondTarget, rate);
            return firstRetval && secondRetval;
        }

        public void setListener(Listener listener) {
            mListener = listener;
        }

        public boolean isAnimating() {
            return mFirst.isAnimating() && mSecond.isAnimating();
        }
    }
}
