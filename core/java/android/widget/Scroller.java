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
import android.hardware.SensorManager;
import android.util.FloatMath;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;


/**
 * This class encapsulates scrolling.  The duration of the scroll
 * is either specified along with the distance or depends on the initial fling velocity.
 * Past this time, the scrolling is automatically moved to its final stage and
 * computeScrollOffset() will always return false to indicate that scrolling is over.
 */
public class Scroller  {
    private int mMode;

    private MagneticScroller mScrollerX;
    private MagneticScroller mScrollerY;

    private final Interpolator mInterpolator;

    private static final int DEFAULT_DURATION = 250;
    private static final int SCROLL_MODE = 0;
    private static final int FLING_MODE = 1;

    // This controls the viscous fluid effect (how much of it)
    private final static float VISCOUS_FLUID_SCALE = 8.0f;
    private static float VISCOUS_FLUID_NORMALIZE;

    static {
        // Set a neutral value that will be used in the next call to viscousFluid().
        VISCOUS_FLUID_NORMALIZE = 1.0f;
        VISCOUS_FLUID_NORMALIZE = 1.0f / viscousFluid(1.0f);
    }

    /**
     * Create a Scroller with a viscous fluid scroll interpolator.
     */
    public Scroller(Context context) {
        this(context, null);
    }

    /**
     * Create a Scroller with the specified interpolator. If the interpolator is
     * null, the default (viscous) interpolator will be used.
     */
    public Scroller(Context context, Interpolator interpolator) {
        mScrollerX = new MagneticScroller();
        mScrollerY = new MagneticScroller();
        MagneticScroller.initializeFromContext(context);

        mInterpolator = interpolator;
    }

    /**
     * 
     * Returns whether the scroller has finished scrolling.
     * 
     * @return True if the scroller has finished scrolling, false otherwise.
     */
    public final boolean isFinished() {
        return mScrollerX.mFinished && mScrollerY.mFinished;
    }

    /**
     * Force the finished field to a particular value.
     * 
     * @param finished The new finished value.
     */
    public final void forceFinished(boolean finished) {
        mScrollerX.mFinished = mScrollerY.mFinished = finished;
    }

    /**
     * Returns how long the scroll event will take, in milliseconds.
     * 
     * @return The duration of the scroll in milliseconds.
     */
    public final int getDuration() {
        return Math.max(mScrollerX.mDuration, mScrollerY.mDuration);
    }

    /**
     * Returns the current X offset in the scroll.
     * 
     * @return The new X offset as an absolute distance from the origin.
     */
    public final int getCurrX() {
        return mScrollerX.mCurrentPosition;
    }

    /**
     * Returns the current Y offset in the scroll.
     * 
     * @return The new Y offset as an absolute distance from the origin.
     */
    public final int getCurrY() {
        return mScrollerY.mCurrentPosition;
    }

    /**
     * @hide
     * Returns the current velocity.
     * 
     * @return The original velocity less the deceleration, norm of the X and Y velocity vector.
     */
    public float getCurrVelocity() {
        float squaredNorm = mScrollerX.mCurrVelocity * mScrollerX.mCurrVelocity;
        squaredNorm += mScrollerY.mCurrVelocity * mScrollerY.mCurrVelocity;
        return FloatMath.sqrt(squaredNorm);
    }

    /**
     * Returns the start X offset in the scroll.
     * 
     * @return The start X offset as an absolute distance from the origin.
     */
    public final int getStartX() {
        return mScrollerX.mStart;
    }

    /**
     * Returns the start Y offset in the scroll.
     * 
     * @return The start Y offset as an absolute distance from the origin.
     */
    public final int getStartY() {
        return mScrollerY.mStart;
    }

    /**
     * Returns where the scroll will end. Valid only for "fling" scrolls.
     * 
     * @return The final X offset as an absolute distance from the origin.
     */
    public final int getFinalX() {
        return mScrollerX.mFinal;
    }

    /**
     * Returns where the scroll will end. Valid only for "fling" scrolls.
     * 
     * @return The final Y offset as an absolute distance from the origin.
     */
    public final int getFinalY() {
        return mScrollerY.mFinal;
    }

    /**
     * Call this when you want to know the new location. If it returns true, the
     * animation is not yet finished.
     */
    public boolean computeScrollOffset() {
        if (isFinished()) {
            return false;
        }

        switch (mMode) {
            case SCROLL_MODE:
                long time = AnimationUtils.currentAnimationTimeMillis();
                // Any scroller can be used for time, since they were started
                // together in scroll mode. We use X here.
                final long elapsedTime = time - mScrollerX.mStartTime;

                final int duration = mScrollerX.mDuration;
                if (elapsedTime < duration) {
                    float q = (float) (elapsedTime) / duration;

                    if (mInterpolator == null)
                        q = viscousFluid(q);
                    else
                        q = mInterpolator.getInterpolation(q);

                    mScrollerX.updateScroll(q);
                    mScrollerY.updateScroll(q);
                } else {
                    abortAnimation();
                }
                break;

            case FLING_MODE:
                if (!mScrollerX.mFinished) {
                    if (!mScrollerX.update()) {
                        if (!mScrollerX.continueWhenFinished()) {
                            mScrollerX.finish();
                        }
                    }
                }

                if (!mScrollerY.mFinished) {
                    if (!mScrollerY.update()) {
                        if (!mScrollerY.continueWhenFinished()) {
                            mScrollerY.finish();
                        }
                    }
                }

                break;
        }

        return true;
    }

    /**
     * Start scrolling by providing a starting point and the distance to travel.
     * The scroll will use the default value of 250 milliseconds for the
     * duration.
     * 
     * @param startX Starting horizontal scroll offset in pixels. Positive
     *        numbers will scroll the content to the left.
     * @param startY Starting vertical scroll offset in pixels. Positive numbers
     *        will scroll the content up.
     * @param dx Horizontal distance to travel. Positive numbers will scroll the
     *        content to the left.
     * @param dy Vertical distance to travel. Positive numbers will scroll the
     *        content up.
     */
    public void startScroll(int startX, int startY, int dx, int dy) {
        startScroll(startX, startY, dx, dy, DEFAULT_DURATION);
    }

    /**
     * Start scrolling by providing a starting point and the distance to travel.
     * 
     * @param startX Starting horizontal scroll offset in pixels. Positive
     *        numbers will scroll the content to the left.
     * @param startY Starting vertical scroll offset in pixels. Positive numbers
     *        will scroll the content up.
     * @param dx Horizontal distance to travel. Positive numbers will scroll the
     *        content to the left.
     * @param dy Vertical distance to travel. Positive numbers will scroll the
     *        content up.
     * @param duration Duration of the scroll in milliseconds.
     */
    public void startScroll(int startX, int startY, int dx, int dy, int duration) {
        mMode = SCROLL_MODE;
        mScrollerX.startScroll(startX, dx, duration);
        mScrollerY.startScroll(startY, dy, duration);
    }

    /**
     * Start scrolling based on a fling gesture. The distance traveled will
     * depend on the initial velocity of the fling. Velocity is slowed down by a
     * constant deceleration until it reaches 0 or the limits are reached.
     * 
     * @param startX Starting point of the scroll (X)
     * @param startY Starting point of the scroll (Y)
     * @param velocityX Initial velocity of the fling (X) measured in pixels per
     *            second.
     * @param velocityY Initial velocity of the fling (Y) measured in pixels per
     *            second.
     * @param minX Minimum X value. The scroller will not scroll past this
     *            point.
     * @param maxX Maximum X value. The scroller will not scroll past this
     *            point.
     * @param minY Minimum Y value. The scroller will not scroll past this
     *            point.
     * @param maxY Maximum Y value. The scroller will not scroll past this
     *            point.
     */
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY) {
        mMode = FLING_MODE;
        mScrollerX.fling(startX, velocityX, minX, maxX);
        mScrollerY.fling(startY, velocityY, minY, maxY);
    }

    static float viscousFluid(float x) {
        x *= VISCOUS_FLUID_SCALE;
        if (x < 1.0f) {
            x -= (1.0f - (float)Math.exp(-x));
        } else {
            float start = 0.36787944117f;   // 1/e == exp(-1)
            x = 1.0f - (float)Math.exp(1.0f - x);
            x = start + x * (1.0f - start);
        }
        x *= VISCOUS_FLUID_NORMALIZE;
        return x;
    }

    /**
     * Stops the animation. Contrary to {@link #forceFinished(boolean)},
     * aborting the animating cause the scroller to move to the final x and y
     * position
     * 
     * @see #forceFinished(boolean)
     */
    public void abortAnimation() {
        mScrollerX.finish();
        mScrollerY.finish();
    }

    /**
     * Extend the scroll animation. This allows a running animation to scroll
     * further and longer, when used with {@link #setFinalX(int)} or {@link #setFinalY(int)}.
     * 
     * @param extend Additional time to scroll in milliseconds.
     * @see #setFinalX(int)
     * @see #setFinalY(int)
     */
    public void extendDuration(int extend) {
        mScrollerX.extendDuration(extend);
        mScrollerY.extendDuration(extend);
    }

    /**
     * Returns the time elapsed since the beginning of the scrolling.
     * 
     * @return The elapsed time in milliseconds.
     */
    public int timePassed() {
        final long time = AnimationUtils.currentAnimationTimeMillis();
        final long startTime = Math.min(mScrollerX.mStartTime, mScrollerY.mStartTime);
        return (int) (time - startTime);
    }

    /**
     * Sets the final position (X) for this scroller.
     * 
     * @param newX The new X offset as an absolute distance from the origin.
     * @see #extendDuration(int)
     * @see #setFinalY(int)
     */
    public void setFinalX(int newX) {
        mScrollerX.setFinalPosition(newX);
    }

    /**
     * Sets the final position (Y) for this scroller.
     * 
     * @param newY The new Y offset as an absolute distance from the origin.
     * @see #extendDuration(int)
     * @see #setFinalX(int)
     */
    public void setFinalY(int newY) {
        mScrollerY.setFinalPosition(newY);
    }

    static class MagneticScroller {
        // Initial position
        int mStart;

        // Current position
        int mCurrentPosition;

        // Final position
        int mFinal;

        // Initial velocity
        int mVelocity;

        // Current velocity
        float mCurrVelocity;

        // Constant current deceleration
        float mDeceleration;

        // Animation starting time, in system milliseconds
        long mStartTime;

        // Animation duration, in milliseconds
        int mDuration;

        // Whether the animation is currently in progress
        boolean mFinished;

        // Constant gravity value, used to scale deceleration
        static float GRAVITY;

        static void initializeFromContext(Context context) {
            final float ppi = context.getResources().getDisplayMetrics().density * 160.0f;
            GRAVITY = SensorManager.GRAVITY_EARTH // g (m/s^2)
                    * 39.37f // inch/meter
                    * ppi // pixels per inch
                    * ViewConfiguration.getScrollFriction();
        }

        MagneticScroller() {
            mFinished = true;
        }

        void updateScroll(float q) {
            mCurrentPosition = mStart + Math.round(q * (mFinal - mStart));
        }

        /*
         * Update the current position and velocity for current time. Returns
         * true if update has been done and false if animation duration has been
         * reached.
         */
        boolean update() {
            final long time = AnimationUtils.currentAnimationTimeMillis();
            final long duration = time - mStartTime;

            if (duration > mDuration) {
                return false;
            }

            final float t = duration / 1000.0f;
            mCurrVelocity = mVelocity + mDeceleration * t;
            final float distance = mVelocity * t + mDeceleration * t * t / 2.0f;
            mCurrentPosition = mStart + (int) distance;

            return true;
        }

        /*
         * Get a signed deceleration that will reduce the velocity.
         */
        static float getDeceleration(int velocity) {
            return velocity > 0 ? -GRAVITY : GRAVITY;
        }

        /*
         * Returns the time (in milliseconds) it will take to go from start to end.
         */
        static int computeDuration(int start, int end, float initialVelocity, float deceleration) {
            final int distance = start - end;
            final float discriminant = initialVelocity * initialVelocity - 2.0f * deceleration
                    * distance;
            if (discriminant >= 0.0f) {
                float delta = (float) Math.sqrt(discriminant);
                if (deceleration < 0.0f) {
                    delta = -delta;
                }
                return (int) (1000.0f * (-initialVelocity - delta) / deceleration);
            }

            // End position can not be reached
            return 0;
        }

        void startScroll(int start, int distance, int duration) {
            mFinished = false;

            mStart = start;
            mFinal = start + distance;

            mStartTime = AnimationUtils.currentAnimationTimeMillis();
            mDuration = duration;

            // Unused
            mDeceleration = 0.0f;
            mVelocity = 0;
        }

        void fling(int start, int velocity, int min, int max) {
            mFinished = false;

            mStart = start;
            mStartTime = AnimationUtils.currentAnimationTimeMillis();

            mVelocity = velocity;

            mDeceleration = getDeceleration(velocity);

            // A start from an invalid position immediately brings back to a valid position
            if (mStart < min) {
                mDuration = 0;
                mFinal = min;
                return;
            }

            if (mStart > max) {
                mDuration = 0;
                mFinal = max;
                return;
            }

            // Duration are expressed in milliseconds
            mDuration = (int) (-1000.0f * velocity / mDeceleration);

            mFinal = start - Math.round((velocity * velocity) / (2.0f * mDeceleration));

            // Clamp to a valid final position
            if (mFinal < min) {
                mFinal = min;
                mDuration = computeDuration(mStart, min, mVelocity, mDeceleration);
            }

            if (mFinal > max) {
                mFinal = max;
                mDuration = computeDuration(mStart, max, mVelocity, mDeceleration);
            }
        }

        void finish() {
            mCurrentPosition = mFinal;
            // Not reset since WebView relies on this value for fast fling.
            // mCurrVelocity = 0.0f;
            mFinished = true;
        }

        boolean continueWhenFinished() {
            return false;
        }

        void setFinalPosition(int position) {
            mFinal = position;
            mFinished = false;
        }

        void extendDuration(int extend) {
            final long time = AnimationUtils.currentAnimationTimeMillis();
            final int elapsedTime = (int) (time - mStartTime);
            mDuration = elapsedTime + extend;
            mFinished = false;
        }
    }
}
