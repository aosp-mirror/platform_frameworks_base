/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.FloatProperty;
import android.util.MathUtils;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;
import android.view.animation.LinearInterpolator;

/**
 * Draws a ripple foreground.
 */
class RippleForeground extends RippleComponent {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final TimeInterpolator DECELERATE_INTERPOLATOR = new LogDecelerateInterpolator(
            400f, 1.4f, 0);

    // Pixel-based accelerations and velocities.
    private static final float WAVE_TOUCH_DOWN_ACCELERATION = 1024;
    private static final float WAVE_TOUCH_UP_ACCELERATION = 3400;
    private static final float WAVE_OPACITY_DECAY_VELOCITY = 3;

    // Bounded ripple animation properties.
    private static final int BOUNDED_ORIGIN_EXIT_DURATION = 300;
    private static final int BOUNDED_RADIUS_EXIT_DURATION = 800;
    private static final int BOUNDED_OPACITY_EXIT_DURATION = 400;
    private static final float MAX_BOUNDED_RADIUS = 350;

    private static final int RIPPLE_ENTER_DELAY = 80;
    private static final int OPACITY_ENTER_DURATION_FAST = 120;

    // Parent-relative values for starting position.
    private float mStartingX;
    private float mStartingY;
    private float mClampedStartingX;
    private float mClampedStartingY;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropPaint;
    private CanvasProperty<Float> mPropRadius;
    private CanvasProperty<Float> mPropX;
    private CanvasProperty<Float> mPropY;

    // Target values for tween animations.
    private float mTargetX = 0;
    private float mTargetY = 0;

    /** Ripple target radius used when bounded. Not used for clamping. */
    private float mBoundedRadius = 0;

    // Software rendering properties.
    private float mOpacity = 1;

    // Values used to tween between the start and end positions.
    private float mTweenRadius = 0;
    private float mTweenX = 0;
    private float mTweenY = 0;

    /** Whether this ripple is bounded. */
    private boolean mIsBounded;

    /** Whether this ripple has finished its exit animation. */
    private boolean mHasFinishedExit;

    public RippleForeground(RippleDrawable owner, Rect bounds, float startingX, float startingY,
            boolean isBounded) {
        super(owner, bounds);

        mIsBounded = isBounded;
        mStartingX = startingX;
        mStartingY = startingY;

        if (isBounded) {
            mBoundedRadius = MAX_BOUNDED_RADIUS * 0.9f
                    + (float) (MAX_BOUNDED_RADIUS * Math.random() * 0.1);
        } else {
            mBoundedRadius = 0;
        }
    }

    @Override
    protected void onTargetRadiusChanged(float targetRadius) {
        clampStartingPosition();
    }

    @Override
    protected boolean drawSoftware(Canvas c, Paint p) {
        boolean hasContent = false;

        final int origAlpha = p.getAlpha();
        final int alpha = (int) (origAlpha * mOpacity + 0.5f);
        final float radius = getCurrentRadius();
        if (alpha > 0 && radius > 0) {
            final float x = getCurrentX();
            final float y = getCurrentY();
            p.setAlpha(alpha);
            c.drawCircle(x, y, radius, p);
            p.setAlpha(origAlpha);
            hasContent = true;
        }

        return hasContent;
    }

    @Override
    protected boolean drawHardware(DisplayListCanvas c) {
        c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);
        return true;
    }

    /**
     * Returns the maximum bounds of the ripple relative to the ripple center.
     */
    public void getBounds(Rect bounds) {
        final int outerX = (int) mTargetX;
        final int outerY = (int) mTargetY;
        final int r = (int) mTargetRadius + 1;
        bounds.set(outerX - r, outerY - r, outerX + r, outerY + r);
    }

    /**
     * Specifies the starting position relative to the drawable bounds. No-op if
     * the ripple has already entered.
     */
    public void move(float x, float y) {
        mStartingX = x;
        mStartingY = y;

        clampStartingPosition();
    }

    /**
     * @return {@code true} if this ripple has finished its exit animation
     */
    public boolean hasFinishedExit() {
        return mHasFinishedExit;
    }

    @Override
    protected Animator createSoftwareEnter(boolean fast) {
        // Bounded ripples don't have enter animations.
        if (mIsBounded) {
            return null;
        }

        final int duration = (int)
                (1000 * Math.sqrt(mTargetRadius / WAVE_TOUCH_DOWN_ACCELERATION * mDensity) + 0.5);

        final ObjectAnimator tweenRadius = ObjectAnimator.ofFloat(this, TWEEN_RADIUS, 1);
        tweenRadius.setAutoCancel(true);
        tweenRadius.setDuration(duration);
        tweenRadius.setInterpolator(LINEAR_INTERPOLATOR);
        tweenRadius.setStartDelay(RIPPLE_ENTER_DELAY);

        final ObjectAnimator tweenOrigin = ObjectAnimator.ofFloat(this, TWEEN_ORIGIN, 1);
        tweenOrigin.setAutoCancel(true);
        tweenOrigin.setDuration(duration);
        tweenOrigin.setInterpolator(LINEAR_INTERPOLATOR);
        tweenOrigin.setStartDelay(RIPPLE_ENTER_DELAY);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 1);
        opacity.setAutoCancel(true);
        opacity.setDuration(OPACITY_ENTER_DURATION_FAST);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        final AnimatorSet set = new AnimatorSet();
        set.play(tweenOrigin).with(tweenRadius).with(opacity);

        return set;
    }

    private float getCurrentX() {
        return MathUtils.lerp(mClampedStartingX - mBounds.exactCenterX(), mTargetX, mTweenX);
    }

    private float getCurrentY() {
        return MathUtils.lerp(mClampedStartingY - mBounds.exactCenterY(), mTargetY, mTweenY);
    }

    private int getRadiusExitDuration() {
        final float remainingRadius = mTargetRadius - getCurrentRadius();
        return (int) (1000 * Math.sqrt(remainingRadius / (WAVE_TOUCH_UP_ACCELERATION
                + WAVE_TOUCH_DOWN_ACCELERATION) * mDensity) + 0.5);
    }

    private float getCurrentRadius() {
        return MathUtils.lerp(0, mTargetRadius, mTweenRadius);
    }

    private int getOpacityExitDuration() {
        return (int) (1000 * mOpacity / WAVE_OPACITY_DECAY_VELOCITY + 0.5f);
    }

    /**
     * Compute target values that are dependent on bounding.
     */
    private void computeBoundedTargetValues() {
        mTargetX = (mClampedStartingX - mBounds.exactCenterX()) * .7f;
        mTargetY = (mClampedStartingY - mBounds.exactCenterY()) * .7f;
        mTargetRadius = mBoundedRadius;
    }

    @Override
    protected Animator createSoftwareExit() {
        final int radiusDuration;
        final int originDuration;
        final int opacityDuration;
        if (mIsBounded) {
            computeBoundedTargetValues();

            radiusDuration = BOUNDED_RADIUS_EXIT_DURATION;
            originDuration = BOUNDED_ORIGIN_EXIT_DURATION;
            opacityDuration = BOUNDED_OPACITY_EXIT_DURATION;
        } else {
            radiusDuration = getRadiusExitDuration();
            originDuration = radiusDuration;
            opacityDuration = getOpacityExitDuration();
        }

        final ObjectAnimator tweenRadius = ObjectAnimator.ofFloat(this, TWEEN_RADIUS, 1);
        tweenRadius.setAutoCancel(true);
        tweenRadius.setDuration(radiusDuration);
        tweenRadius.setInterpolator(DECELERATE_INTERPOLATOR);

        final ObjectAnimator tweenOrigin = ObjectAnimator.ofFloat(this, TWEEN_ORIGIN, 1);
        tweenOrigin.setAutoCancel(true);
        tweenOrigin.setDuration(originDuration);
        tweenOrigin.setInterpolator(DECELERATE_INTERPOLATOR);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 0);
        opacity.setAutoCancel(true);
        opacity.setDuration(opacityDuration);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        final AnimatorSet set = new AnimatorSet();
        set.play(tweenOrigin).with(tweenRadius).with(opacity);
        set.addListener(mAnimationListener);

        return set;
    }

    @Override
    protected RenderNodeAnimatorSet createHardwareExit(Paint p) {
        final int radiusDuration;
        final int originDuration;
        final int opacityDuration;
        if (mIsBounded) {
            computeBoundedTargetValues();

            radiusDuration = BOUNDED_RADIUS_EXIT_DURATION;
            originDuration = BOUNDED_ORIGIN_EXIT_DURATION;
            opacityDuration = BOUNDED_OPACITY_EXIT_DURATION;
        } else {
            radiusDuration = getRadiusExitDuration();
            originDuration = radiusDuration;
            opacityDuration = getOpacityExitDuration();
        }

        final float startX = getCurrentX();
        final float startY = getCurrentY();
        final float startRadius = getCurrentRadius();

        p.setAlpha((int) (p.getAlpha() * mOpacity + 0.5f));

        mPropPaint = CanvasProperty.createPaint(p);
        mPropRadius = CanvasProperty.createFloat(startRadius);
        mPropX = CanvasProperty.createFloat(startX);
        mPropY = CanvasProperty.createFloat(startY);

        final RenderNodeAnimator radius = new RenderNodeAnimator(mPropRadius, mTargetRadius);
        radius.setDuration(radiusDuration);
        radius.setInterpolator(DECELERATE_INTERPOLATOR);

        final RenderNodeAnimator x = new RenderNodeAnimator(mPropX, mTargetX);
        x.setDuration(originDuration);
        x.setInterpolator(DECELERATE_INTERPOLATOR);

        final RenderNodeAnimator y = new RenderNodeAnimator(mPropY, mTargetY);
        y.setDuration(originDuration);
        y.setInterpolator(DECELERATE_INTERPOLATOR);

        final RenderNodeAnimator opacity = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacity.setDuration(opacityDuration);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);
        opacity.addListener(mAnimationListener);

        final RenderNodeAnimatorSet set = new RenderNodeAnimatorSet();
        set.add(radius);
        set.add(opacity);
        set.add(x);
        set.add(y);

        return set;
    }

    @Override
    protected void jumpValuesToExit() {
        mOpacity = 0;
        mTweenX = 1;
        mTweenY = 1;
        mTweenRadius = 1;
    }

    /**
     * Clamps the starting position to fit within the ripple bounds.
     */
    private void clampStartingPosition() {
        final float cX = mBounds.exactCenterX();
        final float cY = mBounds.exactCenterY();
        final float dX = mStartingX - cX;
        final float dY = mStartingY - cY;
        final float r = mTargetRadius;
        if (dX * dX + dY * dY > r * r) {
            // Point is outside the circle, clamp to the perimeter.
            final double angle = Math.atan2(dY, dX);
            mClampedStartingX = cX + (float) (Math.cos(angle) * r);
            mClampedStartingY = cY + (float) (Math.sin(angle) * r);
        } else {
            mClampedStartingX = mStartingX;
            mClampedStartingY = mStartingY;
        }
    }

    private final AnimatorListenerAdapter mAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animator) {
            mHasFinishedExit = true;
        }
    };

    /**
    * Interpolator with a smooth log deceleration.
    */
    private static final class LogDecelerateInterpolator implements TimeInterpolator {
        private final float mBase;
        private final float mDrift;
        private final float mTimeScale;
        private final float mOutputScale;

        public LogDecelerateInterpolator(float base, float timeScale, float drift) {
            mBase = base;
            mDrift = drift;
            mTimeScale = 1f / timeScale;

            mOutputScale = 1f / computeLog(1f);
        }

        private float computeLog(float t) {
            return 1f - (float) Math.pow(mBase, -t * mTimeScale) + (mDrift * t);
        }

        @Override
        public float getInterpolation(float t) {
            return computeLog(t) * mOutputScale;
        }
    }

    /**
     * Property for animating radius between its initial and target values.
     */
    private static final FloatProperty<RippleForeground> TWEEN_RADIUS =
            new FloatProperty<RippleForeground>("tweenRadius") {
        @Override
        public void setValue(RippleForeground object, float value) {
            object.mTweenRadius = value;
            object.invalidateSelf();
        }

        @Override
        public Float get(RippleForeground object) {
            return object.mTweenRadius;
        }
    };

    /**
     * Property for animating origin between its initial and target values.
     */
    private static final FloatProperty<RippleForeground> TWEEN_ORIGIN =
            new FloatProperty<RippleForeground>("tweenOrigin") {
                @Override
                public void setValue(RippleForeground object, float value) {
                    object.mTweenX = value;
                    object.mTweenY = value;
                    object.invalidateSelf();
                }

                @Override
                public Float get(RippleForeground object) {
                    return object.mTweenX;
                }
            };

    /**
     * Property for animating opacity between 0 and its target value.
     */
    private static final FloatProperty<RippleForeground> OPACITY =
            new FloatProperty<RippleForeground>("opacity") {
        @Override
        public void setValue(RippleForeground object, float value) {
            object.mOpacity = value;
            object.invalidateSelf();
        }

        @Override
        public Float get(RippleForeground object) {
            return object.mOpacity;
        }
    };
}
