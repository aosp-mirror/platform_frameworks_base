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
     * Creates a Scroller with the specified interpolator. If the interpolator is
     * null, the default (viscous) interpolator will be used.
     */
    public OverScroller(Context context, Interpolator interpolator) {
        super(context, interpolator);
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
        return mOverScrollerX.springback(startX, minX, maxX)
                || mOverScrollerY.springback(startY, minY, maxY);
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
     * Returns whether the current Scroller position is overscrolled or still within the minimum and
     * maximum bounds provided in the
     * {@link #fling(int, int, int, int, int, int, int, int, int, int)} method.
     * 
     * One should check this value before calling
     * {@link startScroll(int, int, int, int)} as the interpolation currently in progress to restore
     * a valid position will then be stopped. The caller has to take into account the fact that the
     * started scroll will start from an overscrolled position.
     * 
     * @return true when the current position is overscrolled.
     */
    public boolean isOverscrolled() {
        return ((!mOverScrollerX.mFinished && mOverScrollerX.mState != MagneticOverScroller.TO_EDGE) ||
                (!mOverScrollerY.mFinished && mOverScrollerY.mState != MagneticOverScroller.TO_EDGE));
    }

    static class MagneticOverScroller extends Scroller.MagneticScroller {
        private static final int TO_EDGE = 0;
        private static final int TO_BOUNDARY = 1;
        private static final int TO_BOUNCE = 2;

        private int mState = TO_EDGE;

        // The allowed overshot distance before boundary is reached.
        private int mOver;

        // When the scroll goes beyond the edges limits, the deceleration is
        // multiplied by this coefficient, so that the return to a valid
        // position is faster.
        private static final float OVERSCROLL_DECELERATION_COEF = 16.0f;

        // If the velocity is smaller than this value, no bounce is triggered
        // when the edge limits are reached (would result in a zero pixels
        // displacement anyway).
        private static final float MINIMUM_VELOCITY_FOR_BOUNCE = 200.0f;

        // Could be made public for tuning, but applications would no longer
        // have the same look and feel.
        private static final float BOUNCE_COEFFICIENT = 0.4f;

        /*
         * Get a signed deceleration that will reduce the velocity.
         */
        @Override
        float getDeceleration(int velocity) {
            float decelerationY = super.getDeceleration(velocity);
            if (mState != TO_EDGE) {
                decelerationY *= OVERSCROLL_DECELERATION_COEF;
            }
            return decelerationY;
        }

        boolean springback(int start, int min, int max) {
            mFinished = true;

            mStart = start;
            mVelocity = 0;

            mStartTime = AnimationUtils.currentAnimationTimeMillis();
            mDuration = 0;

            if (start < min) {
                startSpringback(start, min, -1);
            } else if (start > max) {
                startSpringback(start, max, 1);
            }

            return !mFinished;
        }

        private void startSpringback(int start, int end, int sign) {
            mFinished = false;
            mState = TO_BOUNCE;
            mDeceleration = getDeceleration(sign);
            mFinal = end;
            mDuration = (int) (1000.0f * Math.sqrt(2.0f * (end - start) / mDeceleration));
        }

        void fling(int start, int velocity, int min, int max, int over) {
            mState = TO_EDGE;
            mOver = over;

            super.fling(start, velocity, min, max);

            if (mStart > max) {
                if (mStart >= max + over) {
                    springback(max + over, min, max);
                } else {
                    // Make sure the deceleration brings us back to edge
                    mVelocity = velocity > 0 ? velocity : -velocity;
                    mCurrVelocity = velocity;
                    notifyEdgeReached(start, max, over);
                }
            } else {
                if (mStart < min) {
                    if (mStart <= min - over) {
                        springback(min - over, min, max);
                    } else {
                        // Make sure the deceleration brings us back to edge
                        mVelocity = velocity < 0 ? velocity : -velocity;
                        mCurrVelocity = velocity;
                        notifyEdgeReached(start, min, over);
                    }
                }
            }
        }

        void notifyEdgeReached(int start, int end, int over) {
            // Compute post-edge deceleration
            mState = TO_BOUNDARY;
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

        void onEdgeReached() {
            // mStart, mVelocity and mStartTime were adjusted to their values when edge was reached.
            mState = TO_BOUNDARY;
            mDeceleration = getDeceleration(mVelocity);

            int distance = Math.round((mVelocity * mVelocity) / (2.0f * mDeceleration));

            if (Math.abs(distance) < mOver) {
                // Deceleration will bring us back to final position
                mState = TO_BOUNCE;
                mFinal = mStart;
                mDuration = (int) (-2000.0f * mVelocity / mDeceleration);
            } else {
                // Velocity is too high, we will hit the boundary limit
                mFinal = mStart + (mVelocity > 0 ? mOver : -mOver);
                mDuration = computeDuration(mStart, mFinal, mVelocity, mDeceleration);
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
                    mStart = mFinal;
                    mFinal = mStart - (mVelocity > 0 ? mOver : -mOver);
                    mVelocity = 0;
                    mDuration = (int) (1000.0f * Math.sqrt(Math.abs(2.0f * mOver / mDeceleration)));
                    mState = TO_BOUNCE;
                    break;
                case TO_BOUNCE:
                    float edgeVelocity = mVelocity + mDeceleration * mDuration / 1000.0f;
                    mVelocity = (int) (-edgeVelocity * BOUNCE_COEFFICIENT);
                    if (Math.abs(mVelocity) < MINIMUM_VELOCITY_FOR_BOUNCE) {
                        return false;
                    }
                    mStart = mFinal;
                    mStartTime += mDuration;
                    mDuration = (int) (-2000.0f * mVelocity / mDeceleration);
                    break;
            }

            update();
            return true;
        }
    }
}
