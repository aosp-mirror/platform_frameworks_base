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

package com.android.server.power;

import android.animation.ValueAnimator;
import android.util.IntProperty;
import android.view.Choreographer;

/**
 * A custom animator that progressively updates a property value at
 * a given variable rate until it reaches a particular target value.
 */
final class RampAnimator<T> {
    private final T mObject;
    private final IntProperty<T> mProperty;
    private final Choreographer mChoreographer;

    private int mCurrentValue;
    private int mTargetValue;
    private int mRate;

    private boolean mAnimating;
    private float mAnimatedValue; // higher precision copy of mCurrentValue
    private long mLastFrameTimeNanos;

    private boolean mFirstTime = true;

    public RampAnimator(T object, IntProperty<T> property) {
        mObject = object;
        mProperty = property;
        mChoreographer = Choreographer.getInstance();
    }

    /**
     * Starts animating towards the specified value.
     *
     * If this is the first time the property is being set, the value jumps
     * directly to the target.
     *
     * @param target The target value.
     * @param rate The convergence rate, in units per second.
     * @return True if the target differs from the previous target.
     */
    public boolean animateTo(int target, int rate) {
        // Immediately jump to the target the first time.
        if (mFirstTime) {
            mFirstTime = false;
            mProperty.setValue(mObject, target);
            mCurrentValue = target;
            return true;
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
            postCallback();
        }

        return changed;
    }

    private void postCallback() {
        mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, mCallback, null);
    }

    private final Runnable mCallback = new Runnable() {
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
            final int oldCurrentValue = mCurrentValue;
            mCurrentValue = Math.round(mAnimatedValue);

            if (oldCurrentValue != mCurrentValue) {
                mProperty.setValue(mObject, mCurrentValue);
            }

            if (mTargetValue != mCurrentValue) {
                postCallback();
            } else {
                mAnimating = false;
            }
        }
    };
}
