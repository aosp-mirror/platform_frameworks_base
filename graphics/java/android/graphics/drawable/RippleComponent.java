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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;

import java.util.ArrayList;

/**
 * Abstract class that handles hardware/software hand-off and lifecycle for
 * animated ripple foreground and background components.
 */
abstract class RippleComponent {
    private final RippleDrawable mOwner;

    /** Bounds used for computing max radius. May be modified by the owner. */
    protected final Rect mBounds;

    /** Whether we can use hardware acceleration for the exit animation. */
    private boolean mHasDisplayListCanvas;

    private boolean mHasPendingHardwareAnimator;
    private RenderNodeAnimatorSet mHardwareAnimator;

    private Animator mSoftwareAnimator;

    /** Whether we have an explicit maximum radius. */
    private boolean mHasMaxRadius;

    /** How big this ripple should be when fully entered. */
    protected float mTargetRadius;

    /** Screen density used to adjust pixel-based constants. */
    protected float mDensity;

    /**
     * If set, force all ripple animations to not run on RenderThread, even if it would be
     * available.
     */
    private final boolean mForceSoftware;

    public RippleComponent(RippleDrawable owner, Rect bounds, boolean forceSoftware) {
        mOwner = owner;
        mBounds = bounds;
        mForceSoftware = forceSoftware;
    }

    public void onBoundsChange() {
        if (!mHasMaxRadius) {
            mTargetRadius = getTargetRadius(mBounds);
            onTargetRadiusChanged(mTargetRadius);
        }
    }

    public final void setup(float maxRadius, float density) {
        if (maxRadius >= 0) {
            mHasMaxRadius = true;
            mTargetRadius = maxRadius;
        } else {
            mTargetRadius = getTargetRadius(mBounds);
        }

        mDensity = density;

        onTargetRadiusChanged(mTargetRadius);
    }

    private static float getTargetRadius(Rect bounds) {
        final float halfWidth = bounds.width() / 2.0f;
        final float halfHeight = bounds.height() / 2.0f;
        return (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
    }

    /**
     * Starts a ripple enter animation.
     *
     * @param fast whether the ripple should enter quickly
     */
    public final void enter(boolean fast) {
        cancel();

        mSoftwareAnimator = createSoftwareEnter(fast);

        if (mSoftwareAnimator != null) {
            mSoftwareAnimator.start();
        }
    }

    /**
     * Starts a ripple exit animation.
     */
    public final void exit() {
        cancel();

        if (mHasDisplayListCanvas) {
            // We don't have access to a canvas here, but we expect one on the
            // next frame. We'll start the render thread animation then.
            mHasPendingHardwareAnimator = true;

            // Request another frame.
            invalidateSelf();
        } else {
            mSoftwareAnimator = createSoftwareExit();
            mSoftwareAnimator.start();
        }
    }

    /**
     * Cancels all animations. Software animation values are left in the
     * current state, while hardware animation values jump to the end state.
     */
    public void cancel() {
        cancelSoftwareAnimations();
        endHardwareAnimations();
    }

    /**
     * Ends all animations, jumping values to the end state.
     */
    public void end() {
        endSoftwareAnimations();
        endHardwareAnimations();
    }

    /**
     * Draws the ripple to the canvas, inheriting the paint's color and alpha
     * properties.
     *
     * @param c the canvas to which the ripple should be drawn
     * @param p the paint used to draw the ripple
     * @return {@code true} if something was drawn, {@code false} otherwise
     */
    public boolean draw(Canvas c, Paint p) {
        final boolean hasDisplayListCanvas = !mForceSoftware && c.isHardwareAccelerated()
                && c instanceof DisplayListCanvas;
        if (mHasDisplayListCanvas != hasDisplayListCanvas) {
            mHasDisplayListCanvas = hasDisplayListCanvas;

            if (!hasDisplayListCanvas) {
                // We've switched from hardware to non-hardware mode. Panic.
                endHardwareAnimations();
            }
        }

        if (hasDisplayListCanvas) {
            final DisplayListCanvas hw = (DisplayListCanvas) c;
            startPendingAnimation(hw, p);

            if (mHardwareAnimator != null) {
                return drawHardware(hw);
            }
        }

        return drawSoftware(c, p);
    }

    /**
     * Populates {@code bounds} with the maximum drawing bounds of the ripple
     * relative to its center. The resulting bounds should be translated into
     * parent drawable coordinates before use.
     *
     * @param bounds the rect to populate with drawing bounds
     */
    public void getBounds(Rect bounds) {
        final int r = (int) Math.ceil(mTargetRadius);
        bounds.set(-r, -r, r, r);
    }

    /**
     * Starts the pending hardware animation, if available.
     *
     * @param hw hardware canvas on which the animation should draw
     * @param p paint whose properties the hardware canvas should use
     */
    private void startPendingAnimation(DisplayListCanvas hw, Paint p) {
        if (mHasPendingHardwareAnimator) {
            mHasPendingHardwareAnimator = false;

            mHardwareAnimator = createHardwareExit(new Paint(p));
            mHardwareAnimator.start(hw);

            // Preemptively jump the software values to the end state now that
            // the hardware exit has read whatever values it needs.
            jumpValuesToExit();
        }
    }

    /**
     * Cancels any current software animations, leaving the values in their
     * current state.
     */
    private void cancelSoftwareAnimations() {
        if (mSoftwareAnimator != null) {
            mSoftwareAnimator.cancel();
            mSoftwareAnimator = null;
        }
    }

    /**
     * Ends any current software animations, jumping the values to their end
     * state.
     */
    private void endSoftwareAnimations() {
        if (mSoftwareAnimator != null) {
            mSoftwareAnimator.end();
            mSoftwareAnimator = null;
        }
    }

    /**
     * Ends any pending or current hardware animations.
     * <p>
     * Hardware animations can't synchronize values back to the software
     * thread, so there is no "cancel" equivalent.
     */
    private void endHardwareAnimations() {
        if (mHardwareAnimator != null) {
            mHardwareAnimator.end();
            mHardwareAnimator = null;
        }

        if (mHasPendingHardwareAnimator) {
            mHasPendingHardwareAnimator = false;

            // Manually jump values to their exited state. Normally we'd do that
            // later when starting the hardware exit, but we're aborting early.
            jumpValuesToExit();
        }
    }

    protected final void invalidateSelf() {
        mOwner.invalidateSelf(false);
    }

    protected final boolean isHardwareAnimating() {
        return mHardwareAnimator != null && mHardwareAnimator.isRunning()
                || mHasPendingHardwareAnimator;
    }

    protected final void onHotspotBoundsChanged() {
        if (!mHasMaxRadius) {
            final float halfWidth = mBounds.width() / 2.0f;
            final float halfHeight = mBounds.height() / 2.0f;
            final float targetRadius = (float) Math.sqrt(halfWidth * halfWidth
                    + halfHeight * halfHeight);

            onTargetRadiusChanged(targetRadius);
        }
    }

    /**
     * Called when the target radius changes.
     *
     * @param targetRadius the new target radius
     */
    protected void onTargetRadiusChanged(float targetRadius) {
        // Stub.
    }

    protected abstract Animator createSoftwareEnter(boolean fast);

    protected abstract Animator createSoftwareExit();

    protected abstract RenderNodeAnimatorSet createHardwareExit(Paint p);

    protected abstract boolean drawHardware(DisplayListCanvas c);

    protected abstract boolean drawSoftware(Canvas c, Paint p);

    /**
     * Called when the hardware exit is cancelled. Jumps software values to end
     * state to ensure that software and hardware values are synchronized.
     */
    protected abstract void jumpValuesToExit();

    public static class RenderNodeAnimatorSet {
        private final ArrayList<RenderNodeAnimator> mAnimators = new ArrayList<>();

        public void add(RenderNodeAnimator anim) {
            mAnimators.add(anim);
        }

        public void clear() {
            mAnimators.clear();
        }

        public void start(DisplayListCanvas target) {
            if (target == null) {
                throw new IllegalArgumentException("Hardware canvas must be non-null");
            }

            final ArrayList<RenderNodeAnimator> animators = mAnimators;
            final int N = animators.size();
            for (int i = 0; i < N; i++) {
                final RenderNodeAnimator anim = animators.get(i);
                anim.setTarget(target);
                anim.start();
            }
        }

        public void cancel() {
            final ArrayList<RenderNodeAnimator> animators = mAnimators;
            final int N = animators.size();
            for (int i = 0; i < N; i++) {
                final RenderNodeAnimator anim = animators.get(i);
                anim.cancel();
            }
        }

        public void end() {
            final ArrayList<RenderNodeAnimator> animators = mAnimators;
            final int N = animators.size();
            for (int i = 0; i < N; i++) {
                final RenderNodeAnimator anim = animators.get(i);
                anim.end();
            }
        }

        public boolean isRunning() {
            final ArrayList<RenderNodeAnimator> animators = mAnimators;
            final int N = animators.size();
            for (int i = 0; i < N; i++) {
                final RenderNodeAnimator anim = animators.get(i);
                if (anim.isRunning()) {
                    return true;
                }
            }
            return false;
        }
    }
}
