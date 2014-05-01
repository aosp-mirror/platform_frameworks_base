/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.graphics.drawable;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.view.animation.DecelerateInterpolator;

/**
 * Draws a Quantum Paper ripple.
 */
class Ripple {
    private static final TimeInterpolator INTERPOLATOR = new DecelerateInterpolator();

    /** Starting radius for a ripple. */
    private static final int STARTING_RADIUS_DP = 16;

    /** Radius when finger is outside view bounds. */
    private static final int OUTSIDE_RADIUS_DP = 16;

    /** Radius when finger is inside view bounds. */
    private static final int INSIDE_RADIUS_DP = 96;

    /** Margin when constraining outside touches (fraction of outer radius). */
    private static final float OUTSIDE_MARGIN = 0.8f;

    /** Resistance factor when constraining outside touches. */
    private static final float OUTSIDE_RESISTANCE = 0.7f;

    /** Minimum alpha value during a pulse animation. */
    private static final float PULSE_MIN_ALPHA = 0.5f;

    /** Duration for animating the trailing edge of the ripple. */
    private static final int EXIT_DURATION = 600;

    /** Duration for animating the leading edge of the ripple. */
    private static final int ENTER_DURATION = 400;

    /** Duration for animating the ripple alpha in and out. */
    private static final int FADE_DURATION = 50;

    /** Minimum elapsed time between start of enter and exit animations. */
    private static final int EXIT_MIN_DELAY = 200;

    /** Duration for animating between inside and outside touch. */
    private static final int OUTSIDE_DURATION = 300;

    /** Duration for animating pulses. */
    private static final int PULSE_DURATION = 400;

    /** Interval between pulses while inside and fully entered. */
    private static final int PULSE_INTERVAL = 400;

    /** Delay before pulses start. */
    private static final int PULSE_DELAY = 500;

    private final Drawable mOwner;

    /** Bounds used for computing max radius and containment. */
    private final Rect mBounds;

    /** Configured maximum ripple radius when the center is outside the bounds. */
    private final int mMaxOutsideRadius;

    /** Configured maximum ripple radius. */
    private final int mMaxInsideRadius;

    private ObjectAnimator mEnter;
    private ObjectAnimator mExit;

    /** Maximum ripple radius. */
    private int mMaxRadius;

    private float mOuterRadius;
    private float mInnerRadius;
    private float mAlphaMultiplier;

    /** Center x-coordinate. */
    private float mX;

    /** Center y-coordinate. */
    private float mY;

    /** Whether the center is within the parent bounds. */
    private boolean mInsideBounds;

    /** Whether to pulse this ripple. */
    private boolean mPulseEnabled;

    /** Temporary hack since we can't check finished state of animator. */
    private boolean mExitFinished;

    /** Whether this ripple has ever moved. */
    private boolean mHasMoved;

    /**
     * Creates a new ripple.
     */
    public Ripple(Drawable owner, Rect bounds, float density, boolean pulseEnabled) {
        mOwner = owner;
        mBounds = bounds;
        mPulseEnabled = pulseEnabled;

        mOuterRadius = (int) (density * STARTING_RADIUS_DP + 0.5f);
        mMaxOutsideRadius = (int) (density * OUTSIDE_RADIUS_DP + 0.5f);
        mMaxInsideRadius = (int) (density * INSIDE_RADIUS_DP + 0.5f);
        mMaxRadius = Math.min(mMaxInsideRadius, Math.max(bounds.width(), bounds.height()));
    }

    public void setOuterRadius(float r) {
        mOuterRadius = r;
        invalidateSelf();
    }

    public float getOuterRadius() {
        return mOuterRadius;
    }

    public void setInnerRadius(float r) {
        mInnerRadius = r;
        invalidateSelf();
    }

    public float getInnerRadius() {
        return mInnerRadius;
    }

    public void setAlphaMultiplier(float a) {
        mAlphaMultiplier = a;
        invalidateSelf();
    }

    public float getAlphaMultiplier() {
        return mAlphaMultiplier;
    }

    /**
     * Returns whether this ripple has finished exiting.
     */
    public boolean isFinished() {
        return mExitFinished;
    }

    /**
     * Called when the bounds change.
     */
    public void onBoundsChanged() {
        mMaxRadius = Math.min(mMaxInsideRadius, Math.max(mBounds.width(), mBounds.height()));

        updateInsideBounds();
    }

    private void updateInsideBounds() {
        final boolean insideBounds = mBounds.contains((int) (mX + 0.5f), (int) (mY + 0.5f));
        if (mInsideBounds != insideBounds || !mHasMoved) {
            mInsideBounds = insideBounds;
            mHasMoved = true;

            if (insideBounds) {
                enter();
            } else {
                outside();
            }
        }
    }

    /**
     * Draws the ripple using the specified paint.
     */
    public boolean draw(Canvas c, Paint p) {
        final Rect bounds = mBounds;
        final float outerRadius = mOuterRadius;
        final float innerRadius = mInnerRadius;
        final float alphaMultiplier = mAlphaMultiplier;

        // Cache the paint alpha so we can restore it later.
        final int paintAlpha = p.getAlpha();
        final int alpha = (int) (paintAlpha * alphaMultiplier + 0.5f);

        // Apply resistance effect when outside bounds.
        final float x;
        final float y;
        if (mInsideBounds) {
            x = mX;
            y = mY;
        } else {
            // TODO: We need to do this outside of draw() so that our dirty
            // bounds accurately reflect resistance.
            x = looseConstrain(mX, bounds.left, bounds.right,
                    mOuterRadius * OUTSIDE_MARGIN, OUTSIDE_RESISTANCE);
            y = looseConstrain(mY, bounds.top, bounds.bottom,
                    mOuterRadius * OUTSIDE_MARGIN, OUTSIDE_RESISTANCE);
        }

        final boolean hasContent;
        if (alphaMultiplier <= 0 || innerRadius >= outerRadius) {
            // Nothing to draw.
            hasContent = false;
        } else if (innerRadius > 0) {
            // Draw a ring.
            final float strokeWidth = outerRadius - innerRadius;
            final float strokeRadius = innerRadius + strokeWidth / 2.0f;
            p.setAlpha(alpha);
            p.setStyle(Style.STROKE);
            p.setStrokeWidth(strokeWidth);
            c.drawCircle(x, y, strokeRadius, p);
            hasContent = true;
        } else if (outerRadius > 0) {
            // Draw a circle.
            p.setAlpha(alpha);
            p.setStyle(Style.FILL);
            c.drawCircle(x, y, outerRadius, p);
            hasContent = true;
        } else {
            hasContent = false;
        }

        p.setAlpha(paintAlpha);
        return hasContent;
    }

    /**
     * Returns the maximum bounds for this ripple.
     */
    public void getBounds(Rect bounds) {
        final int x = (int) mX;
        final int y = (int) mY;
        final int maxRadius = mMaxRadius;
        bounds.set(x - maxRadius, y - maxRadius, x + maxRadius, y + maxRadius);
    }

    /**
     * Updates the center coordinates.
     */
    public void move(float x, float y) {
        mX = x;
        mY = y;

        updateInsideBounds();
        invalidateSelf();
    }

    /**
     * Starts the exit animation. If {@link #enter()} was called recently, the
     * animation may be postponed.
     */
    public void exit() {
        mExitFinished = false;

        final ObjectAnimator exit = ObjectAnimator.ofFloat(this, "innerRadius", 0, mMaxRadius);
        exit.setAutoCancel(true);
        exit.setDuration(EXIT_DURATION);
        exit.setInterpolator(INTERPOLATOR);
        exit.addListener(mAnimationListener);

        if (mEnter != null && mEnter.isStarted()) {
            // If we haven't been running the enter animation for long enough,
            // delay the exit animator.
            final int elapsed = (int) (mEnter.getAnimatedFraction() * mEnter.getDuration());
            final int delay = Math.max(0, EXIT_MIN_DELAY - elapsed);
            exit.setStartDelay(delay);
        }

        exit.start();

        final ObjectAnimator fade = ObjectAnimator.ofFloat(this, "alphaMultiplier", 0);
        fade.setAutoCancel(true);
        fade.setDuration(EXIT_DURATION);
        fade.start();

        mExit = exit;
    }

    private void invalidateSelf() {
        mOwner.invalidateSelf();
    }

    /**
     * Starts the enter animation.
     */
    private void enter() {
        final ObjectAnimator enter = ObjectAnimator.ofFloat(this, "outerRadius", mMaxRadius);
        enter.setAutoCancel(true);
        enter.setDuration(ENTER_DURATION);
        enter.setInterpolator(INTERPOLATOR);
        enter.start();

        final ObjectAnimator fade = ObjectAnimator.ofFloat(this, "alphaMultiplier", 1);
        fade.setAutoCancel(true);
        fade.setDuration(FADE_DURATION);
        fade.start();

        // TODO: Starting with a delay will still cancel the fade in.
        if (false && mPulseEnabled) {
            final ObjectAnimator pulse = ObjectAnimator.ofFloat(
                    this, "alphaMultiplier", 1, PULSE_MIN_ALPHA);
            pulse.setAutoCancel(true);
            pulse.setDuration(PULSE_DURATION + PULSE_INTERVAL);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setStartDelay(PULSE_DELAY);
            pulse.start();
        }

        mEnter = enter;
    }

    /**
     * Starts the outside transition animation.
     */
    private void outside() {
        final float targetRadius = mMaxOutsideRadius;
        final ObjectAnimator outside = ObjectAnimator.ofFloat(this, "outerRadius", targetRadius);
        outside.setAutoCancel(true);
        outside.setDuration(OUTSIDE_DURATION);
        outside.setInterpolator(INTERPOLATOR);
        outside.start();

        final ObjectAnimator fade = ObjectAnimator.ofFloat(this, "alphaMultiplier", 1);
        fade.setAutoCancel(true);
        fade.setDuration(FADE_DURATION);
        fade.start();
    }

    /**
     * Constrains a value within a specified asymptotic margin outside a minimum
     * and maximum.
     */
    private static float looseConstrain(float value, float min, float max, float margin,
            float factor) {
        // TODO: Can we use actual spring physics here?
        if (value < min) {
            return min - Math.min(margin, (float) Math.pow(min - value, factor));
        } else if (value > max) {
            return max + Math.min(margin, (float) Math.pow(value - max, factor));
        } else {
            return value;
        }
    }

    private final AnimatorListener mAnimationListener = new AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (animation == mExit) {
                mExitFinished = true;
                mOuterRadius = 0;
                mInnerRadius = 0;
                mAlphaMultiplier = 1;
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }
    };
}
