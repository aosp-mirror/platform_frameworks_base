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

package com.android.systemui.statusbar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Utility class to calculate general fling animation when the finger is released.
 */
public class FlingAnimationUtils {

    private static final float LINEAR_OUT_SLOW_IN_X2 = 0.35f;
    private static final float LINEAR_OUT_FASTER_IN_Y2_MIN = 0.7f;
    private static final float LINEAR_OUT_FASTER_IN_Y2_MAX = 1f;
    private static final float MIN_VELOCITY_DP_PER_SECOND = 250;
    private static final float HIGH_VELOCITY_DP_PER_SECOND = 3000;

    /**
     * Crazy math. http://en.wikipedia.org/wiki/B%C3%A9zier_curve
     */
    private static final float LINEAR_OUT_SLOW_IN_START_GRADIENT = 1.0f / LINEAR_OUT_SLOW_IN_X2;

    private Interpolator mLinearOutSlowIn;
    private Interpolator mFastOutSlowIn;
    private Interpolator mFastOutLinearIn;

    private float mMinVelocityPxPerSecond;
    private float mMaxLengthSeconds;
    private float mHighVelocityPxPerSecond;

    public FlingAnimationUtils(Context ctx, float maxLengthSeconds) {
        mMaxLengthSeconds = maxLengthSeconds;
        mLinearOutSlowIn = new PathInterpolator(0, 0, LINEAR_OUT_SLOW_IN_X2, 1);
        mFastOutSlowIn
                = AnimationUtils.loadInterpolator(ctx, android.R.interpolator.fast_out_slow_in);
        mFastOutLinearIn
                = AnimationUtils.loadInterpolator(ctx, android.R.interpolator.fast_out_linear_in);
        mMinVelocityPxPerSecond
                = MIN_VELOCITY_DP_PER_SECOND * ctx.getResources().getDisplayMetrics().density;
        mHighVelocityPxPerSecond
                = HIGH_VELOCITY_DP_PER_SECOND * ctx.getResources().getDisplayMetrics().density;
    }

    /**
     * Applies the interpolator and length to the animator, such that the fling animation is
     * consistent with the finger motion.
     *
     * @param animator the animator to apply
     * @param currValue the current value
     * @param endValue the end value of the animator
     * @param velocity the current velocity of the motion
     */
    public void apply(ValueAnimator animator, float currValue, float endValue, float velocity) {
        apply(animator, currValue, endValue, velocity, Math.abs(endValue - currValue));
    }

    /**
     * Applies the interpolator and length to the animator, such that the fling animation is
     * consistent with the finger motion.
     *
     * @param animator the animator to apply
     * @param currValue the current value
     * @param endValue the end value of the animator
     * @param velocity the current velocity of the motion
     * @param maxDistance the maximum distance for this interaction; the maximum animation length
     *                    gets multiplied by the ratio between the actual distance and this value
     */
    public void apply(ValueAnimator animator, float currValue, float endValue, float velocity,
            float maxDistance) {
        float maxLengthSeconds = (float) (mMaxLengthSeconds
                * Math.sqrt(Math.abs(endValue - currValue) / maxDistance));
        float diff = Math.abs(endValue - currValue);
        float velAbs = Math.abs(velocity);
        float durationSeconds = LINEAR_OUT_SLOW_IN_START_GRADIENT * diff / velAbs;
        if (durationSeconds <= maxLengthSeconds) {
            animator.setInterpolator(mLinearOutSlowIn);
        } else if (velAbs >= mMinVelocityPxPerSecond) {

            // Cross fade between fast-out-slow-in and linear interpolator with current velocity.
            durationSeconds = maxLengthSeconds;
            VelocityInterpolator velocityInterpolator
                    = new VelocityInterpolator(durationSeconds, velAbs, diff);
            InterpolatorInterpolator superInterpolator = new InterpolatorInterpolator(
                    velocityInterpolator, mLinearOutSlowIn, mLinearOutSlowIn);
            animator.setInterpolator(superInterpolator);
        } else {

            // Just use a normal interpolator which doesn't take the velocity into account.
            durationSeconds = maxLengthSeconds;
            animator.setInterpolator(mFastOutSlowIn);
        }
        animator.setDuration((long) (durationSeconds * 1000));
    }

    /**
     * Applies the interpolator and length to the animator, such that the fling animation is
     * consistent with the finger motion for the case when the animation is making something
     * disappear.
     *
     * @param animator the animator to apply
     * @param currValue the current value
     * @param endValue the end value of the animator
     * @param velocity the current velocity of the motion
     * @param maxDistance the maximum distance for this interaction; the maximum animation length
     *                    gets multiplied by the ratio between the actual distance and this value
     */
    public void applyDismissing(ValueAnimator animator, float currValue, float endValue,
            float velocity, float maxDistance) {
        float maxLengthSeconds = (float) (mMaxLengthSeconds
                * Math.pow(Math.abs(endValue - currValue) / maxDistance, 0.5f));
        float diff = Math.abs(endValue - currValue);
        float velAbs = Math.abs(velocity);
        float y2 = calculateLinearOutFasterInY2(velAbs);

        // The gradient at the start of the curve is just y2.
        float startGradient = y2;
        Interpolator mLinearOutFasterIn = new PathInterpolator(0, 0, 1, y2);
        float durationSeconds = startGradient * diff / velAbs;
        if (durationSeconds <= maxLengthSeconds) {
            animator.setInterpolator(mLinearOutFasterIn);
        } else if (velAbs >= mMinVelocityPxPerSecond) {

            // Cross fade between linear-out-faster-in and linear interpolator with current
            // velocity.
            durationSeconds = maxLengthSeconds;
            VelocityInterpolator velocityInterpolator
                    = new VelocityInterpolator(durationSeconds, velAbs, diff);
            InterpolatorInterpolator superInterpolator = new InterpolatorInterpolator(
                    velocityInterpolator, mLinearOutFasterIn, mLinearOutSlowIn);
            animator.setInterpolator(superInterpolator);
        } else {

            // Just use a normal interpolator which doesn't take the velocity into account.
            durationSeconds = maxLengthSeconds;
            animator.setInterpolator(mFastOutLinearIn);
        }
        animator.setDuration((long) (durationSeconds * 1000));
    }

    /**
     * Calculates the y2 control point for a linear-out-faster-in path interpolator depending on the
     * velocity. The faster the velocity, the more "linear" the interpolator gets.
     *
     * @param velocity the velocity of the gesture.
     * @return the y2 control point for a cubic bezier path interpolator
     */
    private float calculateLinearOutFasterInY2(float velocity) {
        float t = (velocity - mMinVelocityPxPerSecond)
                / (mHighVelocityPxPerSecond - mMinVelocityPxPerSecond);
        t = Math.max(0, Math.min(1, t));
        return (1 - t) * LINEAR_OUT_FASTER_IN_Y2_MIN + t * LINEAR_OUT_FASTER_IN_Y2_MAX;
    }

    /**
     * @return the minimum velocity a gesture needs to have to be considered a fling
     */
    public float getMinVelocityPxPerSecond() {
        return mMinVelocityPxPerSecond;
    }

    /**
     * An interpolator which interpolates two interpolators with an interpolator.
     */
    private static final class InterpolatorInterpolator implements Interpolator {

        private Interpolator mInterpolator1;
        private Interpolator mInterpolator2;
        private Interpolator mCrossfader;

        InterpolatorInterpolator(Interpolator interpolator1, Interpolator interpolator2,
                Interpolator crossfader) {
            mInterpolator1 = interpolator1;
            mInterpolator2 = interpolator2;
            mCrossfader = crossfader;
        }

        @Override
        public float getInterpolation(float input) {
            float t = mCrossfader.getInterpolation(input);
            return (1 - t) * mInterpolator1.getInterpolation(input)
                    + t * mInterpolator2.getInterpolation(input);
        }
    }

    /**
     * An interpolator which interpolates with a fixed velocity.
     */
    private static final class VelocityInterpolator implements Interpolator {

        private float mDurationSeconds;
        private float mVelocity;
        private float mDiff;

        private VelocityInterpolator(float durationSeconds, float velocity, float diff) {
            mDurationSeconds = durationSeconds;
            mVelocity = velocity;
            mDiff = diff;
        }

        @Override
        public float getInterpolation(float input) {
            float time = input * mDurationSeconds;
            return time * mVelocity / mDiff;
        }
    }
}
