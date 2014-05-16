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
import android.view.HardwareCanvas;
import android.view.RenderNodeAnimator;
import android.view.animation.AccelerateInterpolator;

import java.util.ArrayList;

/**
 * Draws a Quantum Paper ripple.
 */
class Ripple {
    private static final TimeInterpolator INTERPOLATOR = new AccelerateInterpolator();

    private static final float GLOBAL_SPEED = 1.0f;
    private static final float WAVE_TOUCH_DOWN_ACCELERATION = 512.0f * GLOBAL_SPEED;
    private static final float WAVE_TOUCH_UP_ACCELERATION = 1024.0f * GLOBAL_SPEED;
    private static final float WAVE_OPACITY_DECAY_VELOCITY = 1.6f / GLOBAL_SPEED;
    private static final float WAVE_OUTER_OPACITY_VELOCITY = 1.2f * GLOBAL_SPEED;

    // Hardware animators.
    private final ArrayList<RenderNodeAnimator> mRunningAnimations = new ArrayList<>();
    private final ArrayList<RenderNodeAnimator> mPendingAnimations = new ArrayList<>();

    private final Drawable mOwner;

    /** Bounds used for computing max radius. */
    private final Rect mBounds;

    /** Full-opacity color for drawing this ripple. */
    private final int mColor;

    /** Maximum ripple radius. */
    private float mOuterRadius;

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
    private float mRadius = 0;
    private float mOuterX;
    private float mOuterY;
    private float mX;
    private float mY;

    private boolean mFinished;

    /** Whether we should be drawing hardware animations. */
    private boolean mHardwareAnimating;

    /** Whether we can use hardware acceleration for the exit animation. */
    private boolean mCanUseHardware;

    /**
     * Creates a new ripple.
     */
    public Ripple(Drawable owner, Rect bounds, int color) {
        mOwner = owner;
        mBounds = bounds;
        mColor = color | 0xFF000000;

        final float halfWidth = bounds.width() / 2.0f;
        final float halfHeight = bounds.height() / 2.0f;
        mOuterRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        mOuterX = 0;
        mOuterY = 0;
    }

    public void setRadius(float r) {
        mRadius = r;
        invalidateSelf();
    }

    public float getRadius() {
        return mRadius;
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

    public void setX(float x) {
        mX = x;
        invalidateSelf();
    }

    public float getX() {
        return mX;
    }

    public void setY(float y) {
        mY = y;
        invalidateSelf();
    }

    public float getY() {
        return mY;
    }

    /**
     * Returns whether this ripple has finished exiting.
     */
    public boolean isFinished() {
        return mFinished;
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
        final float radius = mRadius;
        final float opacity = mOpacity;
        final float outerOpacity = mOuterOpacity;

        // Cache the paint alpha so we can restore it later.
        final int paintAlpha = p.getAlpha();
        final int alpha = (int) (255 * opacity + 0.5f);
        final int outerAlpha = (int) (255 * outerOpacity + 0.5f);

        boolean hasContent = false;

        if (outerAlpha > 0 && alpha > 0) {
            p.setAlpha(Math.min(alpha, outerAlpha));
            p.setStyle(Style.FILL);
            c.drawCircle(mOuterX, mOuterY, mOuterRadius, p);
            hasContent = true;
        }

        if (opacity > 0 && radius > 0) {
            p.setAlpha(alpha);
            p.setStyle(Style.FILL);
            c.drawCircle(mX, mY, radius, p);
            hasContent = true;
        }

        p.setAlpha(paintAlpha);

        return hasContent;
    }

    /**
     * Returns the maximum bounds for this ripple.
     */
    public void getBounds(Rect bounds) {
        final int outerX = (int) mOuterX;
        final int outerY = (int) mOuterY;
        final int r = (int) mOuterRadius;
        bounds.set(outerX - r, outerY - r, outerX + r, outerY + r);

        final int x = (int) mX;
        final int y = (int) mY;
        bounds.union(x - r, y - r, x + r, y + r);
    }

    /**
     * Starts the enter animation at the specified absolute coordinates.
     */
    public void enter(float x, float y) {
        mX = x - mBounds.exactCenterX();
        mY = y - mBounds.exactCenterY();

        final int radiusDuration = (int)
                (1000 * Math.sqrt(mOuterRadius / WAVE_TOUCH_DOWN_ACCELERATION) + 0.5);
        final int outerDuration = (int) (1000 * 1.0f / WAVE_OUTER_OPACITY_VELOCITY);

        final ObjectAnimator radius = ObjectAnimator.ofFloat(this, "radius", 0, mOuterRadius);
        radius.setAutoCancel(true);
        radius.setDuration(radiusDuration);

        final ObjectAnimator cX = ObjectAnimator.ofFloat(this, "x", mOuterX);
        cX.setAutoCancel(true);
        cX.setDuration(radiusDuration);

        final ObjectAnimator cY = ObjectAnimator.ofFloat(this, "y", mOuterY);
        cY.setAutoCancel(true);
        cY.setDuration(radiusDuration);

        final ObjectAnimator outer = ObjectAnimator.ofFloat(this, "outerOpacity", 0, 1);
        outer.setAutoCancel(true);
        outer.setDuration(outerDuration);

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

        final float remaining;
        if (mAnimRadius != null && mAnimRadius.isRunning()) {
            remaining = mOuterRadius - mRadius;
        } else {
            remaining = mOuterRadius;
        }

        final int radiusDuration = (int) (1000 * Math.sqrt(remaining / (WAVE_TOUCH_UP_ACCELERATION
                + WAVE_TOUCH_DOWN_ACCELERATION)) + 0.5);
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

        final Paint outerPaint = new Paint();
        outerPaint.setAntiAlias(true);
        outerPaint.setColor(mColor);
        outerPaint.setAlpha((int) (255 * mOuterOpacity + 0.5f));
        outerPaint.setStyle(Style.FILL);
        mPropOuterPaint = CanvasProperty.createPaint(outerPaint);
        mPropOuterRadius = CanvasProperty.createFloat(mOuterRadius);
        mPropOuterX = CanvasProperty.createFloat(mOuterX);
        mPropOuterY = CanvasProperty.createFloat(mOuterY);

        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(mColor);
        paint.setAlpha((int) (255 * mOpacity + 0.5f));
        paint.setStyle(Style.FILL);
        mPropPaint = CanvasProperty.createPaint(paint);
        mPropRadius = CanvasProperty.createFloat(mRadius);
        mPropX = CanvasProperty.createFloat(mX);
        mPropY = CanvasProperty.createFloat(mY);

        final RenderNodeAnimator radius = new RenderNodeAnimator(mPropRadius, mOuterRadius);
        radius.setDuration(radiusDuration);

        final RenderNodeAnimator x = new RenderNodeAnimator(mPropX, mOuterX);
        x.setDuration(radiusDuration);

        final RenderNodeAnimator y = new RenderNodeAnimator(mPropY, mOuterY);
        y.setDuration(radiusDuration);

        final RenderNodeAnimator opacity = new RenderNodeAnimator(mPropPaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacity.setDuration(opacityDuration);
        opacity.addListener(mAnimationListener);

        final RenderNodeAnimator outerOpacity;
        if (outerInflection > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacity = new RenderNodeAnimator(
                    mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, inflectionOpacity);
            outerOpacity.setDuration(outerInflection);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - outerInflection;
            if (outerDuration > 0) {
                final RenderNodeAnimator outerFadeOut = new RenderNodeAnimator(
                        mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
                outerFadeOut.setDuration(outerDuration);
                outerFadeOut.setStartDelay(outerInflection);

                mPendingAnimations.add(outerFadeOut);
            }
        } else {
            outerOpacity = new RenderNodeAnimator(
                    mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
            outerOpacity.setDuration(opacityDuration);
        }

        mPendingAnimations.add(radius);
        mPendingAnimations.add(opacity);
        mPendingAnimations.add(outerOpacity);
        mPendingAnimations.add(x);
        mPendingAnimations.add(y);

        mHardwareAnimating = true;

        invalidateSelf();
    }

    private void exitSoftware(int radiusDuration, int opacityDuration, int outerInflection,
            float inflectionOpacity) {
        final ObjectAnimator radius = ObjectAnimator.ofFloat(this, "radius", mOuterRadius);
        radius.setAutoCancel(true);
        radius.setDuration(radiusDuration);

        final ObjectAnimator x = ObjectAnimator.ofFloat(this, "x", mOuterX);
        x.setAutoCancel(true);
        x.setDuration(radiusDuration);

        final ObjectAnimator y = ObjectAnimator.ofFloat(this, "y", mOuterY);
        y.setAutoCancel(true);
        y.setDuration(radiusDuration);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, "opacity", 0);
        opacity.setAutoCancel(true);
        opacity.setDuration(opacityDuration);
        opacity.addListener(mAnimationListener);

        final ObjectAnimator outerOpacity;
        if (outerInflection > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacity = ObjectAnimator.ofFloat(this, "outerOpacity", inflectionOpacity);
            outerOpacity.setDuration(outerInflection);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - outerInflection;
            outerOpacity.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    final ObjectAnimator outerFadeOut = ObjectAnimator.ofFloat(Ripple.this,
                            "outerOpacity", 0);
                    outerFadeOut.setDuration(outerDuration);

                    mAnimOuterOpacity = outerFadeOut;

                    outerFadeOut.start();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    animation.removeListener(this);
                }
            });
        } else {
            outerOpacity = ObjectAnimator.ofFloat(this, "outerOpacity", 0);
            outerOpacity.setDuration(opacityDuration);
        }

        mAnimRadius = radius;
        mAnimOpacity = opacity;
        mAnimOuterOpacity = outerOpacity;
        mAnimX = opacity;
        mAnimY = opacity;

        radius.start();
        opacity.start();
        outerOpacity.start();
        x.start();
        y.start();
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

    private void invalidateSelf() {
        mOwner.invalidateSelf();
    }

    private final AnimatorListenerAdapter mAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mFinished = true;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mFinished = true;
        }
    };
}
