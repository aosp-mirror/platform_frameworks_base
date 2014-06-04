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
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.MathUtils;
import android.view.HardwareCanvas;
import android.view.RenderNodeAnimator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;

/**
 * Draws a Quantum Paper ripple.
 */
class Ripple {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();

    private static final float GLOBAL_SPEED = 1.0f;
    private static final float WAVE_TOUCH_DOWN_ACCELERATION = 512.0f * GLOBAL_SPEED;
    private static final float WAVE_TOUCH_UP_ACCELERATION = 1024.0f * GLOBAL_SPEED;
    private static final float WAVE_OPACITY_DECAY_VELOCITY = 1.6f / GLOBAL_SPEED;
    private static final float WAVE_OUTER_OPACITY_VELOCITY = 1.2f * GLOBAL_SPEED;

    // Hardware animators.
    private final ArrayList<RenderNodeAnimator> mRunningAnimations = new ArrayList<>();
    private final ArrayList<RenderNodeAnimator> mPendingAnimations = new ArrayList<>();

    private final RippleDrawable mOwner;

    /** Bounds used for computing max radius. */
    private final Rect mBounds;

    /** Full-opacity color for drawing this ripple. */
    private int mColor;

    /** Maximum ripple radius. */
    private float mOuterRadius;

    /** Screen density used to adjust pixel-based velocities. */
    private float mDensity;

    private float mStartingX;
    private float mStartingY;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropPaint;
    private CanvasProperty<Float> mPropRadius;
    private CanvasProperty<Float> mPropX;
    private CanvasProperty<Float> mPropY;
    private CanvasProperty<Paint> mPropOuterPaint;
    private CanvasProperty<Float> mPropOuterRadius;
    private CanvasProperty<Float> mPropOuterX;
    private CanvasProperty<Float> mPropOuterY;

    // Software animators.
    private ObjectAnimator mAnimRadius;
    private ObjectAnimator mAnimOpacity;
    private ObjectAnimator mAnimOuterOpacity;
    private ObjectAnimator mAnimX;
    private ObjectAnimator mAnimY;

    // Software rendering properties.
    private float mOuterOpacity = 0;
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

    /**
     * Creates a new ripple.
     */
    public Ripple(RippleDrawable owner, Rect bounds, float startingX, float startingY) {
        mOwner = owner;
        mBounds = bounds;
        mStartingX = startingX;
        mStartingY = startingY;
    }

    public void setup(int maxRadius, int color, float density) {
        mColor = color | 0xFF000000;

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
    }

    public void onHotspotBoundsChanged() {
        if (!mHasMaxRadius) {
            final float halfWidth = mBounds.width() / 2.0f;
            final float halfHeight = mBounds.height() / 2.0f;
            mOuterRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        }
    }

    public void setOpacity(float a) {
        mOpacity = a;
        invalidateSelf();
    }

    public float getOpacity() {
        return mOpacity;
    }

    public void setOuterOpacity(float a) {
        mOuterOpacity = a;
        invalidateSelf();
    }

    public float getOuterOpacity() {
        return mOuterOpacity;
    }

    public void setRadiusGravity(float r) {
        mTweenRadius = r;
        invalidateSelf();
    }

    public float getRadiusGravity() {
        return mTweenRadius;
    }

    public void setXGravity(float x) {
        mTweenX = x;
        invalidateSelf();
    }

    public float getXGravity() {
        return mTweenX;
    }

    public void setYGravity(float y) {
        mTweenY = y;
        invalidateSelf();
    }

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
            cancelHardwareAnimations();
        }
        mCanUseHardware = canUseHardware;

        final boolean hasContent;
        if (canUseHardware && mHardwareAnimating) {
            hasContent = drawHardware((HardwareCanvas) c);
        } else {
            hasContent = drawSoftware(c, p);
        }

        return hasContent;
    }

    private boolean drawHardware(HardwareCanvas c) {
        // If we have any pending hardware animations, cancel any running
        // animations and start those now.
        final ArrayList<RenderNodeAnimator> pendingAnimations = mPendingAnimations;
        final int N = pendingAnimations == null ? 0 : pendingAnimations.size();
        if (N > 0) {
            cancelHardwareAnimations();

            for (int i = 0; i < N; i++) {
                pendingAnimations.get(i).setTarget(c);
                pendingAnimations.get(i).start();
            }

            mRunningAnimations.addAll(pendingAnimations);
            pendingAnimations.clear();
        }

        c.drawCircle(mPropOuterX, mPropOuterY, mPropOuterRadius, mPropOuterPaint);
        c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);

        return true;
    }

    private boolean drawSoftware(Canvas c, Paint p) {
        boolean hasContent = false;

        // Cache the paint alpha so we can restore it later.
        final int paintAlpha = p.getAlpha();

        final int outerAlpha = (int) (255 * mOuterOpacity + 0.5f);
        if (outerAlpha > 0 && mOuterRadius > 0) {
            p.setAlpha(outerAlpha);
            p.setStyle(Style.FILL);
            c.drawCircle(mOuterX, mOuterY, mOuterRadius, p);
            hasContent = true;
        }

        final int alpha = (int) (255 * mOpacity + 0.5f);
        final float radius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        if (alpha > 0 && radius > 0) {
            final float x = MathUtils.lerp(mStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
            final float y = MathUtils.lerp(mStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);
            p.setAlpha(alpha);
            p.setStyle(Style.FILL);
            c.drawCircle(x, y, radius, p);
            hasContent = true;
        }

        p.setAlpha(paintAlpha);

        return hasContent;
    }

    /**
     * Returns the maximum bounds of the ripple relative to the ripple center.
     */
    public void getBounds(Rect bounds) {
        final int outerX = (int) mOuterX;
        final int outerY = (int) mOuterY;
        final int r = (int) mOuterRadius;
        bounds.set(outerX - r, outerY - r, outerX + r, outerY + r);
    }

    /**
     * Specifies the starting position relative to the drawable bounds. No-op if
     * the ripple has already entered.
     */
    public void move(float x, float y) {
        mStartingX = x;
        mStartingY = y;
    }

    /**
     * Starts the enter animation.
     */
    public void enter() {
        final int radiusDuration = (int)
                (1000 * Math.sqrt(mOuterRadius / WAVE_TOUCH_DOWN_ACCELERATION * mDensity) + 0.5);
        final int outerDuration = (int) (1000 * 1.0f / WAVE_OUTER_OPACITY_VELOCITY);

        final ObjectAnimator radius = ObjectAnimator.ofFloat(this, "radiusGravity", 1);
        radius.setAutoCancel(true);
        radius.setDuration(radiusDuration);
        radius.setInterpolator(LINEAR_INTERPOLATOR);

        final ObjectAnimator cX = ObjectAnimator.ofFloat(this, "xGravity", 1);
        cX.setAutoCancel(true);
        cX.setDuration(radiusDuration);

        final ObjectAnimator cY = ObjectAnimator.ofFloat(this, "yGravity", 1);
        cY.setAutoCancel(true);
        cY.setDuration(radiusDuration);

        final ObjectAnimator outer = ObjectAnimator.ofFloat(this, "outerOpacity", 0, 1);
        outer.setAutoCancel(true);
        outer.setDuration(outerDuration);
        outer.setInterpolator(LINEAR_INTERPOLATOR);

        mAnimRadius = radius;
        mAnimOuterOpacity = outer;
        mAnimX = cX;
        mAnimY = cY;

        // Enter animations always run on the UI thread, since it's unlikely
        // that anything interesting is happening until the user lifts their
        // finger.
        radius.start();
        outer.start();
        cX.start();
        cY.start();
    }

    /**
     * Starts the exit animation.
     */
    public void exit() {
        cancelSoftwareAnimations();

        final float radius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        final float remaining;
        if (mAnimRadius != null && mAnimRadius.isRunning()) {
            remaining = mOuterRadius - radius;
        } else {
            remaining = mOuterRadius;
        }

        final int radiusDuration = (int) (1000 * Math.sqrt(remaining / (WAVE_TOUCH_UP_ACCELERATION
                + WAVE_TOUCH_DOWN_ACCELERATION) * mDensity) + 0.5);
        final int opacityDuration = (int) (1000 * mOpacity / WAVE_OPACITY_DECAY_VELOCITY + 0.5f);

        // Determine at what time the inner and outer opacity intersect.
        // inner(t) = mOpacity - t * WAVE_OPACITY_DECAY_VELOCITY / 1000
        // outer(t) = mOuterOpacity + t * WAVE_OUTER_OPACITY_VELOCITY / 1000
        final int outerInflection = Math.max(0, (int) (1000 * (mOpacity - mOuterOpacity)
                / (WAVE_OPACITY_DECAY_VELOCITY + WAVE_OUTER_OPACITY_VELOCITY) + 0.5f));
        final int inflectionOpacity = (int) (255 * (mOuterOpacity + outerInflection
                * WAVE_OUTER_OPACITY_VELOCITY / 1000) + 0.5f);

        if (mCanUseHardware) {
            exitHardware(radiusDuration, opacityDuration, outerInflection, inflectionOpacity);
        } else {
            exitSoftware(radiusDuration, opacityDuration, outerInflection, inflectionOpacity);
        }
    }

    private void exitHardware(int radiusDuration, int opacityDuration, int outerInflection,
            int inflectionOpacity) {
        mPendingAnimations.clear();

        final float startX = MathUtils.lerp(mStartingX - mBounds.exactCenterX(), mOuterX, mTweenX);
        final float startY = MathUtils.lerp(mStartingY - mBounds.exactCenterY(), mOuterY, mTweenY);
        final Paint outerPaint = new Paint();
        outerPaint.setAntiAlias(true);
        outerPaint.setColor(mColor);
        outerPaint.setAlpha((int) (255 * mOuterOpacity + 0.5f));
        outerPaint.setStyle(Style.FILL);
        mPropOuterPaint = CanvasProperty.createPaint(outerPaint);
        mPropOuterRadius = CanvasProperty.createFloat(mOuterRadius);
        mPropOuterX = CanvasProperty.createFloat(mOuterX);
        mPropOuterY = CanvasProperty.createFloat(mOuterY);

        final float startRadius = MathUtils.lerp(0, mOuterRadius, mTweenRadius);
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(mColor);
        paint.setAlpha((int) (255 * mOpacity + 0.5f));
        paint.setStyle(Style.FILL);
        mPropPaint = CanvasProperty.createPaint(paint);
        mPropRadius = CanvasProperty.createFloat(startRadius);
        mPropX = CanvasProperty.createFloat(startX);
        mPropY = CanvasProperty.createFloat(startY);

        final RenderNodeAnimator radiusAnim = new RenderNodeAnimator(mPropRadius, mOuterRadius);
        radiusAnim.setDuration(radiusDuration);
        radiusAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final RenderNodeAnimator xAnim = new RenderNodeAnimator(mPropX, mOuterX);
        xAnim.setDuration(radiusDuration);
        xAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final RenderNodeAnimator yAnim = new RenderNodeAnimator(mPropY, mOuterY);
        yAnim.setDuration(radiusDuration);
        yAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final RenderNodeAnimator opacityAnim = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacityAnim.setDuration(opacityDuration);
        opacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final RenderNodeAnimator outerOpacityAnim;
        if (outerInflection > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacityAnim = new RenderNodeAnimator(
                    mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, inflectionOpacity);
            outerOpacityAnim.setDuration(outerInflection);
            outerOpacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - outerInflection;
            if (outerDuration > 0) {
                final RenderNodeAnimator outerFadeOutAnim = new RenderNodeAnimator(
                        mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
                outerFadeOutAnim.setDuration(outerDuration);
                outerFadeOutAnim.setInterpolator(LINEAR_INTERPOLATOR);
                outerFadeOutAnim.setStartDelay(outerInflection);
                outerFadeOutAnim.setStartValue(inflectionOpacity);
                outerFadeOutAnim.addListener(mAnimationListener);

                mPendingAnimations.add(outerFadeOutAnim);
            } else {
                outerOpacityAnim.addListener(mAnimationListener);
            }
        } else {
            outerOpacityAnim = new RenderNodeAnimator(
                    mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
            outerOpacityAnim.setInterpolator(LINEAR_INTERPOLATOR);
            outerOpacityAnim.setDuration(opacityDuration);
            outerOpacityAnim.addListener(mAnimationListener);
        }

        mPendingAnimations.add(radiusAnim);
        mPendingAnimations.add(opacityAnim);
        mPendingAnimations.add(outerOpacityAnim);
        mPendingAnimations.add(xAnim);
        mPendingAnimations.add(yAnim);

        mHardwareAnimating = true;

        invalidateSelf();
    }

    private void exitSoftware(int radiusDuration, int opacityDuration, int outerInflection,
            int inflectionOpacity) {
        final ObjectAnimator radiusAnim = ObjectAnimator.ofFloat(this, "radiusGravity", 1);
        radiusAnim.setAutoCancel(true);
        radiusAnim.setDuration(radiusDuration);
        radiusAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final ObjectAnimator xAnim = ObjectAnimator.ofFloat(this, "xGravity", 1);
        xAnim.setAutoCancel(true);
        xAnim.setDuration(radiusDuration);
        xAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final ObjectAnimator yAnim = ObjectAnimator.ofFloat(this, "yGravity", 1);
        yAnim.setAutoCancel(true);
        yAnim.setDuration(radiusDuration);
        yAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final ObjectAnimator opacityAnim = ObjectAnimator.ofFloat(this, "opacity", 0);
        opacityAnim.setAutoCancel(true);
        opacityAnim.setDuration(opacityDuration);
        opacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

        final ObjectAnimator outerOpacityAnim;
        if (outerInflection > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacityAnim = ObjectAnimator.ofFloat(this,
                    "outerOpacity", inflectionOpacity / 255.0f);
            outerOpacityAnim.setAutoCancel(true);
            outerOpacityAnim.setDuration(outerInflection);
            outerOpacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - outerInflection;
            if (outerDuration > 0) {
                outerOpacityAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        final ObjectAnimator outerFadeOutAnim = ObjectAnimator.ofFloat(Ripple.this,
                                "outerOpacity", 0);
                        outerFadeOutAnim.setAutoCancel(true);
                        outerFadeOutAnim.setDuration(outerDuration);
                        outerFadeOutAnim.setInterpolator(LINEAR_INTERPOLATOR);
                        outerFadeOutAnim.addListener(mAnimationListener);

                        mAnimOuterOpacity = outerFadeOutAnim;

                        outerFadeOutAnim.start();
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        animation.removeListener(this);
                    }
                });
            } else {
                outerOpacityAnim.addListener(mAnimationListener);
            }
        } else {
            outerOpacityAnim = ObjectAnimator.ofFloat(this, "outerOpacity", 0);
            outerOpacityAnim.setAutoCancel(true);
            outerOpacityAnim.setDuration(opacityDuration);
            outerOpacityAnim.addListener(mAnimationListener);
        }

        mAnimRadius = radiusAnim;
        mAnimOpacity = opacityAnim;
        mAnimOuterOpacity = outerOpacityAnim;
        mAnimX = opacityAnim;
        mAnimY = opacityAnim;

        radiusAnim.start();
        opacityAnim.start();
        outerOpacityAnim.start();
        xAnim.start();
        yAnim.start();
    }

    /**
     * Cancel all animations.
     */
    public void cancel() {
        cancelSoftwareAnimations();
        cancelHardwareAnimations();
    }

    private void cancelSoftwareAnimations() {
        if (mAnimRadius != null) {
            mAnimRadius.cancel();
        }

        if (mAnimOpacity != null) {
            mAnimOpacity.cancel();
        }

        if (mAnimOuterOpacity != null) {
            mAnimOuterOpacity.cancel();
        }

        if (mAnimX != null) {
            mAnimX.cancel();
        }

        if (mAnimY != null) {
            mAnimY.cancel();
        }
    }

    /**
     * Cancels any running hardware animations.
     */
    private void cancelHardwareAnimations() {
        final ArrayList<RenderNodeAnimator> runningAnimations = mRunningAnimations;
        final int N = runningAnimations == null ? 0 : runningAnimations.size();
        for (int i = 0; i < N; i++) {
            runningAnimations.get(i).cancel();
        }

        runningAnimations.clear();
    }

    private void removeSelf() {
        // The owner will invalidate itself.
        mOwner.removeRipple(this);
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
}
