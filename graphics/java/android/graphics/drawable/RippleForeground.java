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
import android.view.HardwareCanvas;
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

    private static final int RIPPLE_ENTER_DELAY = 80;
    private static final int OPACITY_ENTER_DURATION_FAST = 120;

    private float mStartingX;
    private float mStartingY;
    private float mClampedStartingX;
    private float mClampedStartingY;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropPaint;
    private CanvasProperty<Float> mPropRadius;
    private CanvasProperty<Float> mPropX;
    private CanvasProperty<Float> mPropY;

    // Software rendering properties.
    private float mOpacity = 1;
    private float mOuterX;
    private float mOuterY;

    // Values used to tween between the start and end positions.
    private float mTweenRadius = 0;
    private float mTweenX = 0;
    private float mTweenY = 0;

    /** Whether this ripple has finished its exit animation. */
    private boolean mHasFinishedExit;

    public RippleForeground(RippleDrawable owner, Rect bounds, float startingX, float startingY) {
        super(owner, bounds);

        mStartingX = startingX;
        mStartingY = startingY;
    }

    @Override
    public void onSetup() {
        mOuterX = 0;
        mOuterY = 0;
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
        final float radius = MathUtils.lerp(0, mTargetRadius, mTweenRadius);
        if (alpha > 0 && radius > 0) {
            final float x = MathUtils.lerp(
                    mClampedStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
            final float y = MathUtils.lerp(
                    mClampedStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);
            p.setAlpha(alpha);
            c.drawCircle(x, y, radius, p);
            p.setAlpha(origAlpha);
            hasContent = true;
        }

        return hasContent;
    }

    @Override
    protected boolean drawHardware(HardwareCanvas c) {
        c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);
        return true;
    }

    /**
     * Returns the maximum bounds of the ripple relative to the ripple center.
     */
    public void getBounds(Rect bounds) {
        final int outerX = (int) mOuterX;
        final int outerY = (int) mOuterY;
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
        final int duration = (int)
                (1000 * Math.sqrt(mTargetRadius / WAVE_TOUCH_DOWN_ACCELERATION * mDensity) + 0.5);

        final ObjectAnimator tweenAll = ObjectAnimator.ofFloat(this, TWEEN_ALL, 1);
        tweenAll.setAutoCancel(true);
        tweenAll.setDuration(duration);
        tweenAll.setInterpolator(LINEAR_INTERPOLATOR);
        tweenAll.setStartDelay(RIPPLE_ENTER_DELAY);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 1);
        opacity.setAutoCancel(true);
        opacity.setDuration(OPACITY_ENTER_DURATION_FAST);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        final AnimatorSet set = new AnimatorSet();
        set.play(tweenAll).with(opacity);

        return set;
    }

    private int getRadiusExitDuration() {
        final float radius = MathUtils.lerp(0, mTargetRadius, mTweenRadius);
        final float remaining = mTargetRadius - radius;
        return (int) (1000 * Math.sqrt(remaining / (WAVE_TOUCH_UP_ACCELERATION
                + WAVE_TOUCH_DOWN_ACCELERATION) * mDensity) + 0.5);
    }

    private int getOpacityExitDuration() {
        return (int) (1000 * mOpacity / WAVE_OPACITY_DECAY_VELOCITY + 0.5f);
    }

    @Override
    protected Animator createSoftwareExit() {
        final int radiusDuration = getRadiusExitDuration();
        final int opacityDuration = getOpacityExitDuration();

        final ObjectAnimator tweenAll = ObjectAnimator.ofFloat(this, TWEEN_ALL, 1);
        tweenAll.setAutoCancel(true);
        tweenAll.setDuration(radiusDuration);
        tweenAll.setInterpolator(DECELERATE_INTERPOLATOR);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 0);
        opacity.setAutoCancel(true);
        opacity.setDuration(opacityDuration);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        final AnimatorSet set = new AnimatorSet();
        set.play(tweenAll).with(opacity);
        set.addListener(mAnimationListener);

        return set;
    }

    @Override
    protected RenderNodeAnimatorSet createHardwareExit(Paint p) {
        final int radiusDuration = getRadiusExitDuration();
        final int opacityDuration = getOpacityExitDuration();

        final float startX = MathUtils.lerp(
                mClampedStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
        final float startY = MathUtils.lerp(
                mClampedStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);

        final float startRadius = MathUtils.lerp(0, mTargetRadius, mTweenRadius);
        p.setAlpha((int) (p.getAlpha() * mOpacity + 0.5f));

        mPropPaint = CanvasProperty.createPaint(p);
        mPropRadius = CanvasProperty.createFloat(startRadius);
        mPropX = CanvasProperty.createFloat(startX);
        mPropY = CanvasProperty.createFloat(startY);

        final RenderNodeAnimator radius = new RenderNodeAnimator(mPropRadius, mTargetRadius);
        radius.setDuration(radiusDuration);
        radius.setInterpolator(DECELERATE_INTERPOLATOR);

        final RenderNodeAnimator x = new RenderNodeAnimator(mPropX, mOuterX);
        x.setDuration(radiusDuration);
        x.setInterpolator(DECELERATE_INTERPOLATOR);

        final RenderNodeAnimator y = new RenderNodeAnimator(mPropY, mOuterY);
        y.setDuration(radiusDuration);
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
     * Property for animating radius, center X, and center Y between their
     * initial and target values.
     */
    private static final FloatProperty<RippleForeground> TWEEN_ALL =
            new FloatProperty<RippleForeground>("tweenAll") {
        @Override
        public void setValue(RippleForeground object, float value) {
            object.mTweenRadius = value;
            object.mTweenX = value;
            object.mTweenY = value;
            object.invalidateSelf();
        }

        @Override
        public Float get(RippleForeground object) {
            return object.mTweenRadius;
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
