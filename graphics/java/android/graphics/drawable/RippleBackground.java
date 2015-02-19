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
import android.graphics.Color;
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
class RippleBackground {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();

    private static final float GLOBAL_SPEED = 1.0f;
    private static final float WAVE_OPACITY_DECAY_VELOCITY = 3.0f / GLOBAL_SPEED;
    private static final float WAVE_OUTER_OPACITY_EXIT_VELOCITY_MAX = 4.5f * GLOBAL_SPEED;
    private static final float WAVE_OUTER_OPACITY_EXIT_VELOCITY_MIN = 1.5f * GLOBAL_SPEED;
    private static final float WAVE_OUTER_SIZE_INFLUENCE_MAX = 200f;
    private static final float WAVE_OUTER_SIZE_INFLUENCE_MIN = 40f;

    private static final int ENTER_DURATION = 667;
    private static final int ENTER_DURATION_FAST = 100;

    // Hardware animators.
    private final ArrayList<RenderNodeAnimator> mRunningAnimations =
            new ArrayList<RenderNodeAnimator>();

    private final RippleDrawable mOwner;

    /** Bounds used for computing max radius. */
    private final Rect mBounds;

    /** ARGB color for drawing this ripple. */
    private int mColor;

    /** Maximum ripple radius. */
    private float mOuterRadius;

    /** Screen density used to adjust pixel-based velocities. */
    private float mDensity;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropOuterPaint;
    private CanvasProperty<Float> mPropOuterRadius;
    private CanvasProperty<Float> mPropOuterX;
    private CanvasProperty<Float> mPropOuterY;

    // Software animators.
    private ObjectAnimator mAnimOuterOpacity;

    // Temporary paint used for creating canvas properties.
    private Paint mTempPaint;

    // Software rendering properties.
    private float mOuterOpacity = 0;
    private float mOuterX;
    private float mOuterY;

    /** Whether we should be drawing hardware animations. */
    private boolean mHardwareAnimating;

    /** Whether we can use hardware acceleration for the exit animation. */
    private boolean mCanUseHardware;

    /** Whether we have an explicit maximum radius. */
    private boolean mHasMaxRadius;

    private boolean mHasPendingHardwareExit;
    private int mPendingOpacityDuration;
    private int mPendingInflectionDuration;
    private int mPendingInflectionOpacity;

    /**
     * Creates a new ripple.
     */
    public RippleBackground(RippleDrawable owner, Rect bounds) {
        mOwner = owner;
        mBounds = bounds;
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
    }

    public void onHotspotBoundsChanged() {
        if (!mHasMaxRadius) {
            final float halfWidth = mBounds.width() / 2.0f;
            final float halfHeight = mBounds.height() / 2.0f;
            mOuterRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        }
    }

    @SuppressWarnings("unused")
    public void setOuterOpacity(float a) {
        mOuterOpacity = a;
        invalidateSelf();
    }

    @SuppressWarnings("unused")
    public float getOuterOpacity() {
        return mOuterOpacity;
    }

    /**
     * Draws the ripple centered at (0,0) using the specified paint.
     */
    public boolean draw(Canvas c, Paint p) {
        mColor = p.getColor();

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

    public boolean shouldDraw() {
        return (mCanUseHardware && mHardwareAnimating) || (mOuterOpacity > 0 && mOuterRadius > 0);
    }

    private boolean drawHardware(HardwareCanvas c, Paint p) {
        if (mHasPendingHardwareExit) {
            cancelHardwareAnimations(false);
            startPendingHardwareExit(c, p);
        }

        c.drawCircle(mPropOuterX, mPropOuterY, mPropOuterRadius, mPropOuterPaint);

        return true;
    }

    private boolean drawSoftware(Canvas c, Paint p) {
        boolean hasContent = false;

        final int paintAlpha = p.getAlpha();
        final int alpha = (int) (paintAlpha * mOuterOpacity + 0.5f);
        final float radius = mOuterRadius;
        if (alpha > 0 && radius > 0) {
            p.setAlpha(alpha);
            c.drawCircle(mOuterX, mOuterY, radius, p);
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
     * Starts the enter animation.
     */
    public void enter(boolean fast) {
        cancel();

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, "outerOpacity", 0, 1);
        opacity.setAutoCancel(true);
        opacity.setDuration(fast ? ENTER_DURATION_FAST : ENTER_DURATION);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        mAnimOuterOpacity = opacity;

        // Enter animations always run on the UI thread, since it's unlikely
        // that anything interesting is happening until the user lifts their
        // finger.
        opacity.start();
    }

    /**
     * Starts the exit animation.
     */
    public void exit() {
        cancel();

        // Scale the outer max opacity and opacity velocity based
        // on the size of the outer radius.
        final int opacityDuration = (int) (1000 / WAVE_OPACITY_DECAY_VELOCITY + 0.5f);
        final float outerSizeInfluence = MathUtils.constrain(
                (mOuterRadius - WAVE_OUTER_SIZE_INFLUENCE_MIN * mDensity)
                / (WAVE_OUTER_SIZE_INFLUENCE_MAX * mDensity), 0, 1);
        final float outerOpacityVelocity = MathUtils.lerp(WAVE_OUTER_OPACITY_EXIT_VELOCITY_MIN,
                WAVE_OUTER_OPACITY_EXIT_VELOCITY_MAX, outerSizeInfluence);

        // Determine at what time the inner and outer opacity intersect.
        // inner(t) = mOpacity - t * WAVE_OPACITY_DECAY_VELOCITY / 1000
        // outer(t) = mOuterOpacity + t * WAVE_OUTER_OPACITY_VELOCITY / 1000
        final int inflectionDuration = Math.max(0, (int) (1000 * (1 - mOuterOpacity)
                / (WAVE_OPACITY_DECAY_VELOCITY + outerOpacityVelocity) + 0.5f));
        final int inflectionOpacity = (int) (Color.alpha(mColor) * (mOuterOpacity
                + inflectionDuration * outerOpacityVelocity * outerSizeInfluence / 1000) + 0.5f);

        if (mCanUseHardware) {
            createPendingHardwareExit(opacityDuration, inflectionDuration, inflectionOpacity);
        } else {
            exitSoftware(opacityDuration, inflectionDuration, inflectionOpacity);
        }
    }

    private void createPendingHardwareExit(
            int opacityDuration, int inflectionDuration, int inflectionOpacity) {
        mHasPendingHardwareExit = true;
        mPendingOpacityDuration = opacityDuration;
        mPendingInflectionDuration = inflectionDuration;
        mPendingInflectionOpacity = inflectionOpacity;

        // The animation will start on the next draw().
        invalidateSelf();
    }

    private void startPendingHardwareExit(HardwareCanvas c, Paint p) {
        mHasPendingHardwareExit = false;

        final int opacityDuration = mPendingOpacityDuration;
        final int inflectionDuration = mPendingInflectionDuration;
        final int inflectionOpacity = mPendingInflectionOpacity;

        final Paint outerPaint = getTempPaint(p);
        outerPaint.setAlpha((int) (outerPaint.getAlpha() * mOuterOpacity + 0.5f));
        mPropOuterPaint = CanvasProperty.createPaint(outerPaint);
        mPropOuterRadius = CanvasProperty.createFloat(mOuterRadius);
        mPropOuterX = CanvasProperty.createFloat(mOuterX);
        mPropOuterY = CanvasProperty.createFloat(mOuterY);

        final RenderNodeAnimator outerOpacityAnim;
        if (inflectionDuration > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacityAnim = new RenderNodeAnimator(mPropOuterPaint,
                    RenderNodeAnimator.PAINT_ALPHA, inflectionOpacity);
            outerOpacityAnim.setDuration(inflectionDuration);
            outerOpacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - inflectionDuration;
            if (outerDuration > 0) {
                final RenderNodeAnimator outerFadeOutAnim = new RenderNodeAnimator(
                        mPropOuterPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
                outerFadeOutAnim.setDuration(outerDuration);
                outerFadeOutAnim.setInterpolator(LINEAR_INTERPOLATOR);
                outerFadeOutAnim.setStartDelay(inflectionDuration);
                outerFadeOutAnim.setStartValue(inflectionOpacity);
                outerFadeOutAnim.addListener(mAnimationListener);
                outerFadeOutAnim.setTarget(c);
                outerFadeOutAnim.start();

                mRunningAnimations.add(outerFadeOutAnim);
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

        outerOpacityAnim.setTarget(c);
        outerOpacityAnim.start();

        mRunningAnimations.add(outerOpacityAnim);

        mHardwareAnimating = true;

        // Set up the software values to match the hardware end values.
        mOuterOpacity = 0;
    }

    /**
     * Jump all animations to their end state. The caller is responsible for
     * removing the ripple from the list of animating ripples.
     */
    public void jump() {
        endSoftwareAnimations();
        cancelHardwareAnimations(true);
    }

    private void endSoftwareAnimations() {
        if (mAnimOuterOpacity != null) {
            mAnimOuterOpacity.end();
            mAnimOuterOpacity = null;
        }
    }

    private Paint getTempPaint(Paint original) {
        if (mTempPaint == null) {
            mTempPaint = new Paint();
        }
        mTempPaint.set(original);
        return mTempPaint;
    }

    private void exitSoftware(int opacityDuration, int inflectionDuration, int inflectionOpacity) {
        final ObjectAnimator outerOpacityAnim;
        if (inflectionDuration > 0) {
            // Outer opacity continues to increase for a bit.
            outerOpacityAnim = ObjectAnimator.ofFloat(this,
                    "outerOpacity", inflectionOpacity / 255.0f);
            outerOpacityAnim.setAutoCancel(true);
            outerOpacityAnim.setDuration(inflectionDuration);
            outerOpacityAnim.setInterpolator(LINEAR_INTERPOLATOR);

            // Chain the outer opacity exit animation.
            final int outerDuration = opacityDuration - inflectionDuration;
            if (outerDuration > 0) {
                outerOpacityAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        final ObjectAnimator outerFadeOutAnim = ObjectAnimator.ofFloat(
                                RippleBackground.this, "outerOpacity", 0);
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

        mAnimOuterOpacity = outerOpacityAnim;

        outerOpacityAnim.start();
    }

    /**
     * Cancel all animations. The caller is responsible for removing
     * the ripple from the list of animating ripples.
     */
    public void cancel() {
        cancelSoftwareAnimations();
        cancelHardwareAnimations(false);
    }

    private void cancelSoftwareAnimations() {
        if (mAnimOuterOpacity != null) {
            mAnimOuterOpacity.cancel();
            mAnimOuterOpacity = null;
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
                mOuterOpacity = 0;
            }
        }

        mHardwareAnimating = false;
    }

    private void invalidateSelf() {
        mOwner.invalidateSelf();
    }

    private final AnimatorListenerAdapter mAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mHardwareAnimating = false;
        }
    };
}
