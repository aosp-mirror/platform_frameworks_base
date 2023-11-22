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

import com.android.internal.display.BrightnessUtils;

/**
 * A custom animator that progressively updates a property value at
 * a given variable rate until it reaches a particular target value.
 * The ramping at the given rate is done in the perceptual space using
 * the HLG transfer functions.
 */
class RampAnimator<T> {
    private final T mObject;
    private final FloatProperty<T> mProperty;

    private final Clock mClock;

    private float mCurrentValue;

    // target in HLG space
    private float mTargetHlgValue;

    // target in linear space
    private float mTargetLinearValue;
    private float mRate;
    private float mAnimationIncreaseMaxTimeSecs;
    private float mAnimationDecreaseMaxTimeSecs;

    private boolean mAnimating;
    private float mAnimatedValue; // higher precision copy of mCurrentValue
    private long mLastFrameTimeNanos;

    private boolean mFirstTime = true;

    RampAnimator(T object, FloatProperty<T> property) {
        this(object, property, System::nanoTime);
    }

    RampAnimator(T object, FloatProperty<T> property, Clock clock) {
        mObject = object;
        mProperty = property;
        mClock = clock;
    }

    /**
     * Sets the maximum time that a brightness animation can take.
     */
    void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
            long animationRampDecreaseMaxTimeMillis) {
        mAnimationIncreaseMaxTimeSecs = (animationRampIncreaseMaxTimeMillis > 0)
                ? (animationRampIncreaseMaxTimeMillis / 1000.0f) : 0.0f;
        mAnimationDecreaseMaxTimeSecs = (animationRampDecreaseMaxTimeMillis > 0)
                ? (animationRampDecreaseMaxTimeMillis / 1000.0f) : 0.0f;
    }

    /**
     * Sets the animation target and the rate of this ramp animator.
     * Animation rate will be set ignoring maxTime animation limits
     * If this is the first time the property is being set or if the rate is 0,
     * the value jumps directly to the target.
     *
     * @param targetLinear The target value.
     * @param rate The convergence rate in units per second, or 0 to set the value immediately.
     * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
     *                              mAnimationDecreaseMaxTimeSecs should be respected when adjusting
     *                              animation speed
     * @return True if the target differs from the previous target.
     */
    boolean setAnimationTarget(float targetLinear, float rate, boolean ignoreAnimationLimits) {
        float maxIncreaseTimeSecs = ignoreAnimationLimits ? 0 : mAnimationIncreaseMaxTimeSecs;
        float maxDecreaseTimeSecs = ignoreAnimationLimits ? 0 : mAnimationDecreaseMaxTimeSecs;
        return setAnimationTarget(targetLinear, rate, maxIncreaseTimeSecs, maxDecreaseTimeSecs);
    }
    private boolean setAnimationTarget(float targetLinear, float rate,
            float maxIncreaseTimeSecs, float maxDecreaseTimeSecs) {
        // Convert the target from the linear into the HLG space.
        final float target = BrightnessUtils.convertLinearToGamma(targetLinear);

        // Immediately jump to the target the first time.
        if (mFirstTime || rate <= 0) {
            if (mFirstTime || target != mCurrentValue) {
                mFirstTime = false;
                mRate = 0;
                mTargetHlgValue = target;
                mTargetLinearValue = targetLinear;
                mCurrentValue = target;
                setPropertyValue(target);
                mAnimating = false;
                return true;
            }
            return false;
        }

        // Adjust the rate so that we do not exceed our maximum animation time.
        if (target > mCurrentValue && maxIncreaseTimeSecs > 0.0f
                && ((target - mCurrentValue) / rate) > maxIncreaseTimeSecs) {
            rate = (target - mCurrentValue) / maxIncreaseTimeSecs;
        } else if (target < mCurrentValue && maxDecreaseTimeSecs > 0.0f
                && ((mCurrentValue - target) / rate) > maxDecreaseTimeSecs) {
            rate = (mCurrentValue - target) / maxDecreaseTimeSecs;
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
                || (target <= mCurrentValue && mCurrentValue <= mTargetHlgValue)
                || (mTargetHlgValue <= mCurrentValue && mCurrentValue <= target)) {
            mRate = rate;
        }

        final boolean changed = (mTargetHlgValue != target);
        mTargetHlgValue = target;
        mTargetLinearValue = targetLinear;

        // Start animating.
        if (!mAnimating && target != mCurrentValue) {
            mAnimating = true;
            mAnimatedValue = mCurrentValue;
            mLastFrameTimeNanos = mClock.nanoTime();
        }

        return changed;
    }

    /**
     * Returns true if the animation is running.
     */
    boolean isAnimating() {
        return mAnimating;
    }

    /**
     * Sets the brightness property by converting the given value from HLG space
     * into linear space.
     */
    private void setPropertyValue(float val) {
        // To avoid linearVal inconsistency when converting to HLG and back to linear space
        // used original target linear value for final animation step
        float linearVal =
                val == mTargetHlgValue ? mTargetLinearValue : BrightnessUtils.convertGammaToLinear(
                        val);
        mProperty.setValue(mObject, linearVal);
    }

    void performNextAnimationStep(long frameTimeNanos) {
        final float timeDelta = (frameTimeNanos - mLastFrameTimeNanos) * 0.000000001f;
        mLastFrameTimeNanos = frameTimeNanos;

        // Advance the animated value towards the target at the specified rate
        // and clamp to the target. This gives us the new current value but
        // we keep the animated value around to allow for fractional increments
        // towards the target.
        final float scale = ValueAnimator.getDurationScale();
        if (scale == 0) {
            // Animation off.
            mAnimatedValue = mTargetHlgValue;
        } else {
            final float amount = timeDelta * mRate / scale;
            if (mTargetHlgValue > mCurrentValue) {
                mAnimatedValue = Math.min(mAnimatedValue + amount, mTargetHlgValue);
            } else {
                mAnimatedValue = Math.max(mAnimatedValue - amount, mTargetHlgValue);
            }
        }
        final float oldCurrentValue = mCurrentValue;
        mCurrentValue = mAnimatedValue;
        if (oldCurrentValue != mCurrentValue) {
            setPropertyValue(mCurrentValue);
        }
        if (mTargetHlgValue == mCurrentValue) {
            mAnimating = false;
        }
    }

    public interface Listener {
        void onAnimationEnd();
    }

    interface Clock {
        /**
         * Returns current system time in nanoseconds.
         */
        long nanoTime();
    }

    static class DualRampAnimator<T> {
        private final Choreographer mChoreographer;
        private final RampAnimator<T> mFirst;
        private final RampAnimator<T> mSecond;

        private Listener mListener;
        private boolean mAwaitingCallback;

        DualRampAnimator(T object, FloatProperty<T> firstProperty,
                FloatProperty<T> secondProperty) {
            mChoreographer = Choreographer.getInstance();
            mFirst = new RampAnimator<>(object, firstProperty);
            mSecond = new RampAnimator<>(object, secondProperty);
        }

        /**
         * Sets the maximum time that a brightness animation can take.
         */
        public void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
                long animationRampDecreaseMaxTimeMillis) {
            mFirst.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis);
            mSecond.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis);
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
         * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
         *                              mAnimationDecreaseMaxTimeSecs should be respected
         *                              when adjusting animation speed
         * @return True if either target differs from the previous target.
         */
        public boolean animateTo(float linearFirstTarget, float linearSecondTarget, float rate,
                boolean ignoreAnimationLimits) {
            boolean animationTargetChanged = mFirst.setAnimationTarget(linearFirstTarget, rate,
                    ignoreAnimationLimits);
            animationTargetChanged |= mSecond.setAnimationTarget(linearSecondTarget, rate,
                    ignoreAnimationLimits);
            boolean shouldBeAnimating = isAnimating();

            if (shouldBeAnimating != mAwaitingCallback) {
                if (shouldBeAnimating) {
                    mAwaitingCallback = true;
                    postAnimationCallback();
                } else if (mAwaitingCallback) {
                    mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                            mAnimationCallback, null);
                    mAwaitingCallback = false;
                }
            }
            return animationTargetChanged;
        }

        /**
        * Sets a listener to watch for animation events.
        */
        public void setListener(Listener listener) {
            mListener = listener;
        }

        /**
        * Returns true if the animation is running.
        */
        public boolean isAnimating() {
            return mFirst.isAnimating() || mSecond.isAnimating();
        }

        private void postAnimationCallback() {
            mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, mAnimationCallback, null);
        }

        private final Runnable mAnimationCallback = new Runnable() {
            @Override // Choreographer callback
            public void run() {
                long frameTimeNanos = mChoreographer.getFrameTimeNanos();
                mFirst.performNextAnimationStep(frameTimeNanos);
                mSecond.performNextAnimationStep(frameTimeNanos);
                if (isAnimating()) {
                    postAnimationCallback();
                } else {
                    if (mListener != null) {
                        mListener.onAnimationEnd();
                    }
                    mAwaitingCallback = false;
                }
            }
        };
    }
}
