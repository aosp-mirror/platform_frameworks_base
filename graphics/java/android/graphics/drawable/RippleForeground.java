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
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.animation.RenderNodeAnimator;
import android.util.FloatProperty;
import android.util.MathUtils;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;

/**
 * Draws a ripple foreground.
 */
class RippleForeground extends RippleComponent {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    // Matches R.interpolator.fast_out_slow_in but as we have no context we can't just import that
    private static final TimeInterpolator DECELERATE_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    // Time it takes for the ripple to expand
    private static final int RIPPLE_ENTER_DURATION = 0;
    // Time it takes for the ripple to slide from the touch to the center point
    private static final int RIPPLE_ORIGIN_DURATION = 0;

    private static final int OPACITY_ENTER_DURATION = 0;
    private static final int OPACITY_EXIT_DURATION = 225;
    private static final int OPACITY_HOLD_DURATION = OPACITY_ENTER_DURATION + 225;

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

    // Software rendering properties.
    private float mOpacity = 0;

    // Values used to tween between the start and end positions.
    private float mTweenRadius = 0;
    private float mTweenX = 0;
    private float mTweenY = 0;

    /** Whether this ripple has finished its exit animation. */
    private boolean mHasFinishedExit;

    /** Whether we can use hardware acceleration for the exit animation. */
    private boolean mUsingProperties;

    private long mEnterStartedAtMillis;

    private ArrayList<RenderNodeAnimator> mPendingHwAnimators = new ArrayList<>();
    private ArrayList<RenderNodeAnimator> mRunningHwAnimators = new ArrayList<>();

    private ArrayList<Animator> mRunningSwAnimators = new ArrayList<>();

    /**
     * If set, force all ripple animations to not run on RenderThread, even if it would be
     * available.
     */
    private final boolean mForceSoftware;

    /**
     * If we have a bound, don't start from 0. Start from 60% of the max out of width and height.
     */
    private float mStartRadius = 0;

    public RippleForeground(RippleDrawable owner, Rect bounds, float startingX, float startingY,
            boolean forceSoftware) {
        super(owner, bounds);

        mForceSoftware = forceSoftware;
        mStartingX = startingX;
        mStartingY = startingY;

        // Take 60% of the maximum of the width and height, then divided half to get the radius.
        mStartRadius = Math.max(bounds.width(), bounds.height()) * 0.3f;
        clampStartingPosition();
    }

    @Override
    protected void onTargetRadiusChanged(float targetRadius) {
        clampStartingPosition();
        switchToUiThreadAnimation();
    }

    private void drawSoftware(Canvas c, Paint p) {
        final int origAlpha = p.getAlpha();
        final int alpha = (int) (origAlpha * mOpacity + 0.5f);
        final float radius = getCurrentRadius();
        if (alpha > 0 && radius > 0) {
            final float x = getCurrentX();
            final float y = getCurrentY();
            p.setAlpha(alpha);
            c.drawCircle(x, y, radius, p);
            p.setAlpha(origAlpha);
        }
    }

    private void startPending(RecordingCanvas c) {
        if (!mPendingHwAnimators.isEmpty()) {
            for (int i = 0; i < mPendingHwAnimators.size(); i++) {
                RenderNodeAnimator animator = mPendingHwAnimators.get(i);
                animator.setTarget(c);
                animator.start();
                mRunningHwAnimators.add(animator);
            }
            mPendingHwAnimators.clear();
        }
    }

    private void pruneHwFinished() {
        if (!mRunningHwAnimators.isEmpty()) {
            for (int i = mRunningHwAnimators.size() - 1; i >= 0; i--) {
                if (!mRunningHwAnimators.get(i).isRunning()) {
                    mRunningHwAnimators.remove(i);
                }
            }
        }
    }

    private void pruneSwFinished() {
        if (!mRunningSwAnimators.isEmpty()) {
            for (int i = mRunningSwAnimators.size() - 1; i >= 0; i--) {
                if (!mRunningSwAnimators.get(i).isRunning()) {
                    mRunningSwAnimators.remove(i);
                }
            }
        }
    }

    private void drawHardware(RecordingCanvas c, Paint p) {
        startPending(c);
        pruneHwFinished();
        if (mPropPaint != null) {
            mUsingProperties = true;
            c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);
        } else {
            mUsingProperties = false;
            drawSoftware(c, p);
        }
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

    private long computeFadeOutDelay() {
        long timeSinceEnter = AnimationUtils.currentAnimationTimeMillis() - mEnterStartedAtMillis;
        if (timeSinceEnter > 0 && timeSinceEnter < OPACITY_HOLD_DURATION) {
            return OPACITY_HOLD_DURATION - timeSinceEnter;
        }
        return 0;
    }

    private void startSoftwareEnter() {
        for (int i = 0; i < mRunningSwAnimators.size(); i++) {
            mRunningSwAnimators.get(i).cancel();
        }
        mRunningSwAnimators.clear();

        final ObjectAnimator tweenRadius = ObjectAnimator.ofFloat(this, TWEEN_RADIUS, 1);
        tweenRadius.setDuration(RIPPLE_ENTER_DURATION);
        tweenRadius.setInterpolator(DECELERATE_INTERPOLATOR);
        tweenRadius.start();
        mRunningSwAnimators.add(tweenRadius);

        final ObjectAnimator tweenOrigin = ObjectAnimator.ofFloat(this, TWEEN_ORIGIN, 1);
        tweenOrigin.setDuration(RIPPLE_ORIGIN_DURATION);
        tweenOrigin.setInterpolator(DECELERATE_INTERPOLATOR);
        tweenOrigin.start();
        mRunningSwAnimators.add(tweenOrigin);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 1);
        opacity.setDuration(OPACITY_ENTER_DURATION);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);
        opacity.start();
        mRunningSwAnimators.add(opacity);
    }

    private void startSoftwareExit() {
        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 0);
        opacity.setDuration(OPACITY_EXIT_DURATION);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);
        opacity.addListener(mAnimationListener);
        opacity.setStartDelay(computeFadeOutDelay());
        opacity.start();
        mRunningSwAnimators.add(opacity);
    }

    private void startHardwareEnter() {
        if (mForceSoftware) { return; }
        mPropX = CanvasProperty.createFloat(getCurrentX());
        mPropY = CanvasProperty.createFloat(getCurrentY());
        mPropRadius = CanvasProperty.createFloat(getCurrentRadius());
        final Paint paint = mOwner.getRipplePaint();
        mPropPaint = CanvasProperty.createPaint(paint);

        final RenderNodeAnimator radius = new RenderNodeAnimator(mPropRadius, mTargetRadius);
        radius.setDuration(RIPPLE_ORIGIN_DURATION);
        radius.setInterpolator(DECELERATE_INTERPOLATOR);
        mPendingHwAnimators.add(radius);

        final RenderNodeAnimator x = new RenderNodeAnimator(mPropX, mTargetX);
        x.setDuration(RIPPLE_ORIGIN_DURATION);
        x.setInterpolator(DECELERATE_INTERPOLATOR);
        mPendingHwAnimators.add(x);

        final RenderNodeAnimator y = new RenderNodeAnimator(mPropY, mTargetY);
        y.setDuration(RIPPLE_ORIGIN_DURATION);
        y.setInterpolator(DECELERATE_INTERPOLATOR);
        mPendingHwAnimators.add(y);

        final RenderNodeAnimator opacity = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, paint.getAlpha());
        opacity.setDuration(OPACITY_ENTER_DURATION);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);
        opacity.setStartValue(0);
        mPendingHwAnimators.add(opacity);

        invalidateSelf();
    }

    private void startHardwareExit() {
        // Only run a hardware exit if we had a hardware enter to continue from
        if (mForceSoftware || mPropPaint == null) return;

        final RenderNodeAnimator opacity = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacity.setDuration(OPACITY_EXIT_DURATION);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);
        opacity.addListener(mAnimationListener);
        opacity.setStartDelay(computeFadeOutDelay());
        opacity.setStartValue(mOwner.getRipplePaint().getAlpha());
        mPendingHwAnimators.add(opacity);
        invalidateSelf();
    }

    /**
     * Starts a ripple enter animation.
     */
    public final void enter() {
        mEnterStartedAtMillis = AnimationUtils.currentAnimationTimeMillis();
        startSoftwareEnter();
        startHardwareEnter();
    }

    /**
     * Starts a ripple exit animation.
     */
    public final void exit() {
        startSoftwareExit();
        startHardwareExit();
    }

    private float getCurrentX() {
        return MathUtils.lerp(mClampedStartingX - mBounds.exactCenterX(), mTargetX, mTweenX);
    }

    private float getCurrentY() {
        return MathUtils.lerp(mClampedStartingY - mBounds.exactCenterY(), mTargetY, mTweenY);
    }

    private float getCurrentRadius() {
        return MathUtils.lerp(mStartRadius, mTargetRadius, mTweenRadius);
    }

    /**
     * Draws the ripple to the canvas, inheriting the paint's color and alpha
     * properties.
     *
     * @param c the canvas to which the ripple should be drawn
     * @param p the paint used to draw the ripple
     */
    public void draw(Canvas c, Paint p) {
        final boolean hasDisplayListCanvas = !mForceSoftware && c instanceof RecordingCanvas;

        pruneSwFinished();
        if (hasDisplayListCanvas) {
            final RecordingCanvas hw = (RecordingCanvas) c;
            drawHardware(hw, p);
        } else {
            drawSoftware(c, p);
        }
    }

    /**
     * Clamps the starting position to fit within the ripple bounds.
     */
    private void clampStartingPosition() {
        final float cX = mBounds.exactCenterX();
        final float cY = mBounds.exactCenterY();
        final float dX = mStartingX - cX;
        final float dY = mStartingY - cY;
        final float r = mTargetRadius - mStartRadius;
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

    /**
     * Ends all animations, jumping values to the end state.
     */
    public void end() {
        for (int i = 0; i < mRunningSwAnimators.size(); i++) {
            mRunningSwAnimators.get(i).end();
        }
        mRunningSwAnimators.clear();
        for (int i = 0; i < mRunningHwAnimators.size(); i++) {
            mRunningHwAnimators.get(i).end();
        }
        mRunningHwAnimators.clear();
    }

    private void onAnimationPropertyChanged() {
        if (!mUsingProperties) {
            invalidateSelf();
        }
    }

    private void clearHwProps() {
        mPropPaint = null;
        mPropRadius = null;
        mPropX = null;
        mPropY = null;
        mUsingProperties = false;
    }

    private final AnimatorListenerAdapter mAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animator) {
            mHasFinishedExit = true;
            pruneHwFinished();
            pruneSwFinished();

            if (mRunningHwAnimators.isEmpty()) {
                clearHwProps();
            }
        }
    };

    private void switchToUiThreadAnimation() {
        for (int i = 0; i < mRunningHwAnimators.size(); i++) {
            Animator animator = mRunningHwAnimators.get(i);
            animator.removeListener(mAnimationListener);
            animator.end();
        }
        mRunningHwAnimators.clear();
        clearHwProps();
        invalidateSelf();
    }

    /**
     * Property for animating radius between its initial and target values.
     */
    private static final FloatProperty<RippleForeground> TWEEN_RADIUS =
            new FloatProperty<RippleForeground>("tweenRadius") {
        @Override
        public void setValue(RippleForeground object, float value) {
            object.mTweenRadius = value;
            object.onAnimationPropertyChanged();
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
            object.onAnimationPropertyChanged();
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
            object.onAnimationPropertyChanged();
        }

        @Override
        public Float get(RippleForeground object) {
            return object.mOpacity;
        }
    };
}
