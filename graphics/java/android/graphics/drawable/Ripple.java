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
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.MathUtils;
import android.view.HardwareCanvas;
import android.view.RenderNodeAnimator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;

/**
 * Draws a Material ripple.
 */
class Ripple {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final TimeInterpolator DECEL_INTERPOLATOR = new LogInterpolator();

    private static final float GLOBAL_SPEED = 1.0f;
    private static final float WAVE_TOUCH_DOWN_ACCELERATION = 1024.0f * GLOBAL_SPEED;
    private static final float WAVE_TOUCH_UP_ACCELERATION = 3400.0f * GLOBAL_SPEED;
    private static final float WAVE_OPACITY_DECAY_VELOCITY = 3.0f / GLOBAL_SPEED;

    private static final long RIPPLE_ENTER_DELAY = 80;

    // Hardware animators.
    private final ArrayList<RenderNodeAnimator> mRunningAnimations =
            new ArrayList<RenderNodeAnimator>();

    private final RippleDrawable mOwner;

    /** Bounds used for computing max radius. */
    private final Rect mBounds;

    /** Maximum ripple radius. */
    private float mOuterRadius;

    /** Screen density used to adjust pixel-based velocities. */
    private float mDensity;

    private float mStartingX;
    private float mStartingY;
    private float mClampedStartingX;
    private float mClampedStartingY;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropPaint;
    private CanvasProperty<Float> mPropRadius;
    private CanvasProperty<Float> mPropX;
    private CanvasProperty<Float> mPropY;

    // Software animators.
    private ObjectAnimator mAnimRadius;
    private ObjectAnimator mAnimOpacity;
    private ObjectAnimator mAnimX;
    private ObjectAnimator mAnimY;

    // Temporary paint used for creating canvas properties.
    private Paint mTempPaint;

    // Software rendering properties.
    private float mOpacity = 1;
    private float mOuterX;
    private float mOuterY;

    // Values used to tween between the start and end positions.
    private float mTweenRadius = 0;
    private float mTweenX = 0;
    private float mTweenY = 0;

    /** Whether we should be drawing hardware animations. */
    private boolean mHardwareAnimating;

    /** Whether we can use hardware acceleration for the exit animation. */
    private boolean mCanUseHardware;

    /** Whether we have an explicit maximum radius. */
    private boolean mHasMaxRadius;

    /** Whether we were canceled externally and should avoid self-removal. */
    private boolean mCanceled;

    private boolean mHasPendingHardwareExit;
    private int mPendingRadiusDuration;
    private int mPendingOpacityDuration;

    /**
     * Creates a new ripple.
     */
    public Ripple(RippleDrawable owner, Rect bounds, float startingX, float startingY) {
        mOwner = owner;
        mBounds = bounds;

        mStartingX = startingX;
        mStartingY = startingY;
    }

    public void setup(int maxRadius, float density) {
        if (maxRadius != RippleDrawable.RADIUS_AUTO) {
            mHasMaxRadius = true;
            mOuterRadius = maxRadius;
        } else {
            final float halfWidth = mBounds.width() / 2.0f;
            final float halfHeight = mBounds.height() / 2.0f;
            mOuterRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        }

        mOuterX = 0;
        mOuterY = 0;
        mDensity = density;

        clampStartingPosition();
    }

    public boolean isHardwareAnimating() {
        return mHardwareAnimating;
    }

    private void clampStartingPosition() {
        final float cX = mBounds.exactCenterX();
        final float cY = mBounds.exactCenterY();
        final float dX = mStartingX - cX;
        final float dY = mStartingY - cY;
        final float r = mOuterRadius;
        if (dX * dX + dY * dY > r * r) {
            // Point is outside the circle, clamp to the circumference.
            final double angle = Math.atan2(dY, dX);
            mClampedStartingX = cX + (float) (Math.cos(angle) * r);
            mClampedStartingY = cY + (float) (Math.sin(angle) * r);
        } else {
            mClampedStartingX = mStartingX;
            mClampedStartingY = mStartingY;
        }
    }

    public void onHotspotBoundsChanged() {
        if (!mHasMaxRadius) {
            final float halfWidth = mBounds.width() / 2.0f;
            final float halfHeight = mBounds.height() / 2.0f;
            mOuterRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);

            clampStartingPosition();
        }
    }

    public void setOpacity(float a) {
        mOpacity = a;
        invalidateSelf();
    }

    public float getOpacity() {
        return mOpacity;
    }

    @SuppressWarnings("unused")
    public void setRadiusGravity(float r) {
        mTweenRadius = r;
        invalidateSelf();
    }

    @SuppressWarnings("unused")
    public float getRadiusGravity() {
        return mTweenRadius;
    }

    @SuppressWarnings("unused")
    public void setXGravity(float x) {
        mTweenX = x;
        invalidateSelf();
    }

    @SuppressWarnings("unused")
    public float getXGravity() {
        return mTweenX;
    }

    @SuppressWarnings("unused")
    public void setYGravity(float y) {
        mTweenY = y;
        invalidateSelf();
    }

    @SuppressWarnings("unused")
    public float getYGravity() {
        return mTweenY;
    }

    /**
     * Draws the ripple centered at (0,0) using the specified paint.
     */
    public boolean draw(Canvas c, Paint p) {
        final boolean canUseHardware = c.isHardwareAccelerated();
        if (mCanUseHardware != canUseHardware && mCanUseHardware) {
            // We've switched from hardware to non-hardware mode. Panic.
            cancelHardwareAnimations(true);
        }
        mCanUseHardware = canUseHardware;

        final boolean hasContent;
        if (canUseHardware && (mHardwareAnimating || mHasPendingHardwareExit)) {
            hasContent = drawHardware((HardwareCanvas) c, p);
        } else {
            hasContent = drawSoftware(c, p);
        }

        return hasContent;
    }

    private boolean drawHardware(HardwareCanvas c, Paint p) {
        if (mHasPendingHardwareExit) {
            cancelHardwareAnimations(false);
            startPendingHardwareExit(c, p);
        }

        c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);

        return true;
    }

    private boolean drawSoftware(Canvas c, Paint p) {
        boolean hasContent = false;

        final int paintAlpha = p.getAlpha();
        final int alpha = (int) (paintAlpha * mOpacity + 0.5f);
        final float radius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        if (alpha > 0 && radius > 0) {
            final float x = MathUtils.lerp(
                    mClampedStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
            final float y = MathUtils.lerp(
                    mClampedStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);
            p.setAlpha(alpha);
            c.drawCircle(x, y, radius, p);
            p.setAlpha(paintAlpha);
            hasContent = true;
        }

        return hasContent;
    }

    /**
     * Returns the maximum bounds of the ripple relative to the ripple center.
     */
    public void getBounds(Rect bounds) {
        final int outerX = (int) mOuterX;
        final int outerY = (int) mOuterY;
        final int r = (int) mOuterRadius + 1;
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
     * Starts the enter animation.
     */
    public void enter() {
        cancel();

        final int radiusDuration = (int)
                (1000 * Math.sqrt(mOuterRadius / WAVE_TOUCH_DOWN_ACCELERATION * mDensity) + 0.5);

        final ObjectAnimator radius = ObjectAnimator.ofFloat(this, "radiusGravity", 1);
        radius.setAutoCancel(true);
        radius.setDuration(radiusDuration);
        radius.setInterpolator(LINEAR_INTERPOLATOR);
        radius.setStartDelay(RIPPLE_ENTER_DELAY);

        final ObjectAnimator cX = ObjectAnimator.ofFloat(this, "xGravity", 1);
        cX.setAutoCancel(true);
        cX.setDuration(radiusDuration);
        cX.setInterpolator(LINEAR_INTERPOLATOR);
        cX.setStartDelay(RIPPLE_ENTER_DELAY);

        final ObjectAnimator cY = ObjectAnimator.ofFloat(this, "yGravity", 1);
        cY.setAutoCancel(true);
        cY.setDuration(radiusDuration);
        cY.setInterpolator(LINEAR_INTERPOLATOR);
        cY.setStartDelay(RIPPLE_ENTER_DELAY);

        mAnimRadius = radius;
        mAnimX = cX;
        mAnimY = cY;

        // Enter animations always run on the UI thread, since it's unlikely
        // that anything interesting is happening until the user lifts their
        // finger.
        radius.start();
        cX.start();
        cY.start();
    }

    /**
     * Starts the exit animation.
     */
    public void exit() {
        final float radius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        final float remaining;
        if (mAnimRadius != null && mAnimRadius.isRunning()) {
            remaining = mOuterRadius - radius;
        } else {
            remaining = mOuterRadius;
        }

        cancel();

        final int radiusDuration = (int) (1000 * Math.sqrt(remaining / (WAVE_TOUCH_UP_ACCELERATION
                + WAVE_TOUCH_DOWN_ACCELERATION) * mDensity) + 0.5);
        final int opacityDuration = (int) (1000 * mOpacity / WAVE_OPACITY_DECAY_VELOCITY + 0.5f);

        if (mCanUseHardware) {
            createPendingHardwareExit(radiusDuration, opacityDuration);
        } else {
            exitSoftware(radiusDuration, opacityDuration);
        }
    }

    private void createPendingHardwareExit(int radiusDuration, int opacityDuration) {
        mHasPendingHardwareExit = true;
        mPendingRadiusDuration = radiusDuration;
        mPendingOpacityDuration = opacityDuration;

        // The animation will start on the next draw().
        invalidateSelf();
    }

    private void startPendingHardwareExit(HardwareCanvas c, Paint p) {
        mHasPendingHardwareExit = false;

        final int radiusDuration = mPendingRadiusDuration;
        final int opacityDuration = mPendingOpacityDuration;

        final float startX = MathUtils.lerp(
                mClampedStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
        final float startY = MathUtils.lerp(
                mClampedStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);

        final float startRadius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        final Paint paint = getTempPaint(p);
        paint.setAlpha((int) (paint.getAlpha() * mOpacity + 0.5f));
        mPropPaint = CanvasProperty.createPaint(paint);
        mPropRadius = CanvasProperty.createFloat(startRadius);
        mPropX = CanvasProperty.createFloat(startX);
        mPropY = CanvasProperty.createFloat(startY);

        final RenderNodeAnimator radiusAnim = new RenderNodeAnimator(mPropRadius, mOuterRadius);
        radiusAnim.setDuration(radiusDuration);
        radiusAnim.setInterpolator(DECEL_INTERPOLATOR);
        radiusAnim.setTarget(c);
        radiusAnim.start();

        final RenderNodeAnimator xAnim = new RenderNodeAnimator(mPropX, mOuterX);
        xAnim.setDuration(radiusDuration);
        xAnim.setInterpolator(DECEL_INTERPOLATOR);
        xAnim.setTarget(c);
        xAnim.start();

        final RenderNodeAnimator yAnim = new RenderNodeAnimator(mPropY, mOuterY);
        yAnim.setDuration(radiusDuration);
        yAnim.setInterpolator(DECEL_INTERPOLATOR);
        yAnim.setTarget(c);
        yAnim.start();

        final RenderNodeAnimator opacityAnim = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacityAnim.setDuration(opacityDuration);
        opacityAnim.setInterpolator(LINEAR_INTERPOLATOR);
        opacityAnim.addListener(mAnimationListener);
        opacityAnim.setTarget(c);
        opacityAnim.start();

        mRunningAnimations.add(radiusAnim);
        mRunningAnimations.add(opacityAnim);
        mRunningAnimations.add(xAnim);
        mRunningAnimations.add(yAnim);

        mHardwareAnimating = true;

        // Set up the software values to match the hardware end values.
        mOpacity = 0;
        mTweenX = 1;
        mTweenY = 1;
        mTweenRadius = 1;
    }

    /**
     * Jump all animations to their end state. The caller is responsible for
     * removing the ripple from the list of animating ripples.
     */
    public void jump() {
        mCanceled = true;
        endSoftwareAnimations();
        cancelHardwareAnimations(true);
        mCanceled = false;
    }

    private void endSoftwareAnimations() {
        if (mAnimRadius != null) {
            mAnimRadius.end();
            mAnimRadius = null;
        }

        if (mAnimOpacity != null) {
            mAnimOpacity.end();
            mAnimOpacity = null;
        }

        if (mAnimX != null) {
            mAnimX.end();
            mAnimX = null;
        }

        if (mAnimY != null) {
            mAnimY.end();
            mAnimY = null;
        }
    }

    private Paint getTempPaint(Paint original) {
        if (mTempPaint == null) {
            mTempPaint = new Paint();
        }
        mTempPaint.set(original);
        return mTempPaint;
    }

    private void exitSoftware(int radiusDuration, int opacityDuration) {
        final ObjectAnimator radiusAnim = ObjectAnimator.ofFloat(this, "radiusGravity", 1);
        radiusAnim.setAutoCancel(true);
        radiusAnim.setDuration(radiusDuration);
        radiusAnim.setInterpolator(DECEL_INTERPOLATOR);

        final ObjectAnimator xAnim = ObjectAnimator.ofFloat(this, "xGravity", 1);
        xAnim.setAutoCancel(true);
        xAnim.setDuration(radiusDuration);
        xAnim.setInterpolator(DECEL_INTERPOLATOR);

        final ObjectAnimator yAnim = ObjectAnimator.ofFloat(this, "yGravity", 1);
        yAnim.setAutoCancel(true);
        yAnim.setDuration(radiusDuration);
        yAnim.setInterpolator(DECEL_INTERPOLATOR);

        final ObjectAnimator opacityAnim = ObjectAnimator.ofFloat(this, "opacity", 0);
        opacityAnim.setAutoCancel(true);
        opacityAnim.setDuration(opacityDuration);
        opacityAnim.setInterpolator(LINEAR_INTERPOLATOR);
        opacityAnim.addListener(mAnimationListener);

        mAnimRadius = radiusAnim;
        mAnimOpacity = opacityAnim;
        mAnimX = xAnim;
        mAnimY = yAnim;

        radiusAnim.start();
        opacityAnim.start();
        xAnim.start();
        yAnim.start();
    }

    /**
     * Cancels all animations. The caller is responsible for removing
     * the ripple from the list of animating ripples.
     */
    public void cancel() {
        mCanceled = true;
        cancelSoftwareAnimations();
        cancelHardwareAnimations(false);
        mCanceled = false;
    }

    private void cancelSoftwareAnimations() {
        if (mAnimRadius != null) {
            mAnimRadius.cancel();
            mAnimRadius = null;
        }

        if (mAnimOpacity != null) {
            mAnimOpacity.cancel();
            mAnimOpacity = null;
        }

        if (mAnimX != null) {
            mAnimX.cancel();
            mAnimX = null;
        }

        if (mAnimY != null) {
            mAnimY.cancel();
            mAnimY = null;
        }
    }

    /**
     * Cancels any running hardware animations.
     */
    private void cancelHardwareAnimations(boolean jumpToEnd) {
        final ArrayList<RenderNodeAnimator> runningAnimations = mRunningAnimations;
        final int N = runningAnimations.size();
        for (int i = 0; i < N; i++) {
            if (jumpToEnd) {
                runningAnimations.get(i).end();
            } else {
                runningAnimations.get(i).cancel();
            }
        }
        runningAnimations.clear();

        if (mHasPendingHardwareExit) {
            // If we had a pending hardware exit, jump to the end state.
            mHasPendingHardwareExit = false;

            if (jumpToEnd) {
                mOpacity = 0;
                mTweenX = 1;
                mTweenY = 1;
                mTweenRadius = 1;
            }
        }

        mHardwareAnimating = false;
    }

    private void removeSelf() {
        // The owner will invalidate itself.
        if (!mCanceled) {
            mOwner.removeRipple(this);
        }
    }

    private void invalidateSelf() {
        mOwner.invalidateSelf();
    }

    private final AnimatorListenerAdapter mAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            removeSelf();
        }
    };

    /**
    * Interpolator with a smooth log deceleration
    */
    private static final class LogInterpolator implements TimeInterpolator {
        @Override
        public float getInterpolation(float input) {
            return 1 - (float) Math.pow(400, -input * 1.4);
        }
    }
}
