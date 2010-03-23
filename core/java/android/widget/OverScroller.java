/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

/**
 * This class encapsulates scrolling with the ability to overshoot the bounds
 * of a scrolling operation. This class attempts to be a drop-in replacement
 * for {@link android.widget.Scroller} in most cases.
 * 
 * @hide Pending API approval
 */
public class OverScroller extends Scroller {

    // Identical to mScrollers, but casted to MagneticOverScroller. 
    private MagneticOverScroller mOverScrollerX;
    private MagneticOverScroller mOverScrollerY;

    /**
     * Creates an OverScroller with a viscous fluid scroll interpolator.
     * @param context
     */
    public OverScroller(Context context) {
        this(context, null);
    }

    /**
     * Creates an OverScroller with default edge bounce coefficients.
     * @param context The context of this application.
     * @param interpolator The scroll interpolator. If null, a default (viscous) interpolator will
     * be used.
     */
    public OverScroller(Context context, Interpolator interpolator) {
        this(context, interpolator, MagneticOverScroller.DEFAULT_BOUNCE_COEFFICIENT,
                MagneticOverScroller.DEFAULT_BOUNCE_COEFFICIENT);
    }

    /**
     * Creates an OverScroller.
     * @param context The context of this application.
     * @param interpolator The scroll interpolator. If null, a default (viscous) interpolator will
     * be used.
     * @param bounceCoefficientX A value between 0 and 1 that will determine the proportion of the
     * velocity which is preserved in the bounce when the horizontal edge is reached. A null value
     * means no bounce.
     * @param bounceCoefficientY Same as bounceCoefficientX but for the vertical direction.
     */
    public OverScroller(Context context, Interpolator interpolator,
            float bounceCoefficientX, float bounceCoefficientY) {
        super(context, interpolator);
        mOverScrollerX.setBounceCoefficient(bounceCoefficientX);
        mOverScrollerY.setBounceCoefficient(bounceCoefficientY);
    }

    @Override
    void instantiateScrollers() {
        mScrollerX = mOverScrollerX = new MagneticOverScroller();
        mScrollerY = mOverScrollerY = new MagneticOverScroller();
    }

    /**
     * Call this when you want to 'spring back' into a valid coordinate range.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param minX Minimum valid X value
     * @param maxX Maximum valid X value
     * @param minY Minimum valid Y value
     * @param maxY Minimum valid Y value
     * @return true if a springback was initiated, false if startX and startY were
     *          already within the valid range.
     */
    public boolean springback(int startX, int startY, int minX, int maxX, int minY, int maxY) {
        mMode = FLING_MODE;

        // Make sure both methods are called.
        final boolean spingbackX = mOverScrollerX.springback(startX, minX, maxX);
        final boolean spingbackY = mOverScrollerY.springback(startY, minY, maxY);
        return spingbackX || spingbackY;
    }

    @Override
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY) {
        fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY, 0, 0);
    }

    /**
     * Start scrolling based on a fling gesture. The distance traveled will
     * depend on the initial velocity of the fling.
     * 
     * @param startX Starting point of the scroll (X)
     * @param startY Starting point of the scroll (Y)
     * @param velocityX Initial velocity of the fling (X) measured in pixels per
     *            second.
     * @param velocityY Initial velocity of the fling (Y) measured in pixels per
     *            second
     * @param minX Minimum X value. The scroller will not scroll past this point
     *            unless overX > 0. If overfling is allowed, it will use minX as
     *            a springback boundary.
     * @param maxX Maximum X value. The scroller will not scroll past this point
     *            unless overX > 0. If overfling is allowed, it will use maxX as
     *            a springback boundary.
     * @param minY Minimum Y value. The scroller will not scroll past this point
     *            unless overY > 0. If overfling is allowed, it will use minY as
     *            a springback boundary.
     * @param maxY Maximum Y value. The scroller will not scroll past this point
     *            unless overY > 0. If overfling is allowed, it will use maxY as
     *            a springback boundary.
     * @param overX Overfling range. If > 0, horizontal overfling in either
     *            direction will be possible.
     * @param overY Overfling range. If > 0, vertical overfling in either
     *            direction will be possible.
     */
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY, int overX, int overY) {
        mMode = FLING_MODE;
        mOverScrollerX.fling(startX, velocityX, minX, maxX, overX);
        mOverScrollerY.fling(startY, velocityY, minY, maxY, overY);
    }

    void notifyHorizontalBoundaryReached(int startX, int finalX) {
        mOverScrollerX.springback(startX, finalX, finalX);
    }

    void notifyVerticalBoundaryReached(int startY, int finalY) {
        mOverScrollerY.springback(startY, finalY, finalY);
    }

    void notifyHorizontalEdgeReached(int startX, int finalX, int overX) {
        mOverScrollerX.notifyEdgeReached(startX, finalX, overX);
    }

    void notifyVerticalEdgeReached(int startY, int finalY, int overY) {
        mOverScrollerY.notifyEdgeReached(startY, finalY, overY);
    }

    /**
     * Returns whether the current Scroller is currently returning to a valid position.
     * Valid bounds were provided by the
     * {@link #fling(int, int, int, int, int, int, int, int, int, int)} method.
     * 
     * One should check this value before calling
     * {@link startScroll(int, int, int, int)} as the interpolation currently in progress to restore
     * a valid position will then be stopped. The caller has to take into account the fact that the
     * started scroll will start from an overscrolled position.
     * 
     * @return true when the current position is overscrolled and interpolated back to a valid value.
     */
    public boolean isOverscrolled() {
        return ((!mOverScrollerX.mFinished &&
                mOverScrollerX.mState != MagneticOverScroller.TO_EDGE) ||
                (!mOverScrollerY.mFinished &&
                        mOverScrollerY.mState != MagneticOverScroller.TO_EDGE));
    }

    static class MagneticOverScroller extends Scroller.MagneticScroller {
        private static final int TO_EDGE = 0;
        private static final int TO_BOUNDARY = 1;
        private static final int TO_BOUNCE = 2;

        private int mState = TO_EDGE;

        // The allowed overshot distance before boundary is reached.
        private int mOver;

        // Duration in milliseconds to go back from edge to edge. Springback is half of it.
        private static final int OVERSCROLL_SPRINGBACK_DURATION = 200;

        // Oscillation period
        private static final float TIME_COEF =
            1000.0f * (float) Math.PI / OVERSCROLL_SPRINGBACK_DURATION;

        // If the velocity is smaller than this value, no bounce is triggered
        // when the edge limits are reached (would result in a zero pixels
        // displacement anyway).
        private static final float MINIMUM_VELOCITY_FOR_BOUNCE = 140.0f;

        // Proportion of the velocity that is preserved when the edge is reached.
        private static final float DEFAULT_BOUNCE_COEFFICIENT = 0.16f;

        private float mBounceCoefficient = DEFAULT_BOUNCE_COEFFICIENT;

        void setBounceCoefficient(float coefficient) {
            mBounceCoefficient = coefficient;
        }

        boolean springback(int start, int min, int max) {
            mFinished = true;

            mStart = start;
            mVelocity = 0;

            mStartTime = AnimationUtils.currentAnimationTimeMillis();
            mDuration = 0;

            if (start < min) {
                startSpringback(start, min, false);
            } else if (start > max) {
                startSpringback(start, max, true);
            }

            return !mFinished;
        }

        private void startSpringback(int start, int end, boolean positive) {
            mFinished = false;
            mState = TO_BOUNCE;
            mStart = mFinal = end;
            mDuration = OVERSCROLL_SPRINGBACK_DURATION;
            mStartTime -= OVERSCROLL_SPRINGBACK_DURATION / 2;
            mVelocity = (int) (Math.abs(end - start) * TIME_COEF * (positive ? 1.0 : -1.0f));
        }

        void fling(int start, int velocity, int min, int max, int over) {
            mState = TO_EDGE;
            mOver = over;

            super.fling(start, velocity, min, max);

            if (start > max) {
                if (start >= max + over) {
                    springback(max + over, min, max);
                } else {
                    if (velocity <= 0) {
                        springback(start, min, max);
                    } else {
                        long time = AnimationUtils.currentAnimationTimeMillis();
                        final double durationSinceEdge =
                            Math.atan((start-max) * TIME_COEF / velocity) / TIME_COEF;
                        mStartTime = (int) (time - 1000.0f * durationSinceEdge);

                        // Simulate a bounce that started from edge
                        mStart = max;

                        mVelocity = (int) (velocity / Math.cos(durationSinceEdge * TIME_COEF));

                        onEdgeReached();
                    }
                }
            } else {
                if (start < min) {
                    if (start <= min - over) {
                        springback(min - over, min, max);
                    } else {
                        if (velocity >= 0) {
                            springback(start, min, max);
                        } else {
                            long time = AnimationUtils.currentAnimationTimeMillis();
                            final double durationSinceEdge =
                                Math.atan((start-min) * TIME_COEF / velocity) / TIME_COEF;
                            mStartTime = (int) (time - 1000.0f * durationSinceEdge);

                            // Simulate a bounce that started from edge
                            mStart = min;

                            mVelocity = (int) (velocity / Math.cos(durationSinceEdge * TIME_COEF));

                            onEdgeReached();
                        }

                    }
                }
            }
        }

        void notifyEdgeReached(int start, int end, int over) {
            mDeceleration = getDeceleration(mVelocity);

            // Local time, used to compute edge crossing time.
            float timeCurrent = mCurrVelocity / mDeceleration;
            final int distance = end - start;
            float timeEdge = -(float) Math.sqrt((2.0f * distance / mDeceleration)
                    + (timeCurrent * timeCurrent));

            mVelocity = (int) (mDeceleration * timeEdge);

            // Simulate a symmetric bounce that started from edge
            mStart = end;

            mOver = over;

            long time = AnimationUtils.currentAnimationTimeMillis();
            mStartTime = (int) (time - 1000.0f * (timeCurrent - timeEdge));

            onEdgeReached();
        }

        private void onEdgeReached() {
            // mStart, mVelocity and mStartTime were adjusted to their values when edge was reached.
            final float distance = mVelocity / TIME_COEF;

            if (Math.abs(distance) < mOver) {
                // Spring force will bring us back to final position
                mState = TO_BOUNCE;
                mFinal = mStart;
                mDuration = OVERSCROLL_SPRINGBACK_DURATION;
            } else {
                // Velocity is too high, we will hit the boundary limit
                mState = TO_BOUNDARY;
                int over = mVelocity > 0 ? mOver : -mOver;
                mFinal = mStart + over;
                mDuration = (int) (1000.0f * Math.asin(over / distance) / TIME_COEF);
            }
        }

        @Override
        boolean continueWhenFinished() {
            switch (mState) {
                case TO_EDGE:
                    // Duration from start to null velocity
                    int duration = (int) (-1000.0f * mVelocity / mDeceleration);
                    if (mDuration < duration) {
                        // If the animation was clamped, we reached the edge
                        mStart = mFinal;
                        // Speed when edge was reached
                        mVelocity = (int) (mVelocity + mDeceleration * mDuration / 1000.0f);
                        mStartTime += mDuration;
                        onEdgeReached();
                    } else {
                        // Normal stop, no need to continue
                        return false;
                    }
                    break;
                case TO_BOUNDARY:
                    mStartTime += mDuration;
                    startSpringback(mFinal, mFinal - (mVelocity > 0 ? mOver:-mOver), mVelocity > 0);
                    break;
                case TO_BOUNCE:
                    //mVelocity = (int) (mVelocity * BOUNCE_COEFFICIENT);
                    mVelocity = (int) (mVelocity * mBounceCoefficient);
                    if (Math.abs(mVelocity) < MINIMUM_VELOCITY_FOR_BOUNCE) {
                        return false;
                    }
                    mStartTime += mDuration;
                    break;
            }

            update();
            return true;
        }

        /*
         * Update the current position and velocity for current time. Returns
         * true if update has been done and false if animation duration has been
         * reached.
         */
        @Override
        boolean update() {
            final long time = AnimationUtils.currentAnimationTimeMillis();
            final long duration = time - mStartTime;

            if (duration > mDuration) {
                return false;
            }

            double distance;
            final float t = duration / 1000.0f;
            if (mState == TO_EDGE) {
                mCurrVelocity = mVelocity + mDeceleration * t;
                distance = mVelocity * t + mDeceleration * t * t / 2.0f;
            } else {
                final float d = t * TIME_COEF;
                mCurrVelocity = mVelocity * (float)Math.cos(d);
                distance = mVelocity / TIME_COEF * Math.sin(d);
            }

            mCurrentPosition = mStart + (int) distance;
            return true;
        }
    }
}
