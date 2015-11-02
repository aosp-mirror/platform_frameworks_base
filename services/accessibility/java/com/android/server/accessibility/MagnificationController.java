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

package com.android.server.accessibility;

import com.android.internal.R;
import com.android.server.LocalServices;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Property;
import android.util.Slog;
import android.view.MagnificationSpec;
import android.view.WindowManagerInternal;
import android.view.animation.DecelerateInterpolator;

/**
 * This class is used to control and query the state of display magnification
 * from the accessibility manager and related classes. It is responsible for
 * holding the current state of magnification and animation, and it handles
 * communication between the accessibility manager and window manager.
 */
class MagnificationController {
    private static final String LOG_TAG = ScreenMagnifier.class.getSimpleName();

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;
    private static final boolean DEBUG_MAGNIFICATION_CONTROLLER = false;

    private static final String PROPERTY_NAME_MAGNIFICATION_SPEC = "magnificationSpec";

    private final MagnificationSpec mSentMagnificationSpec = MagnificationSpec.obtain();
    private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();

    private final Region mMagnifiedBounds = new Region();
    private final Rect mTempRect = new Rect();

    private final AccessibilityManagerService mAms;
    private final WindowManagerInternal mWindowManager;
    private final ValueAnimator mTransformationAnimator;

    public MagnificationController(Context context, AccessibilityManagerService ams) {
        mAms = ams;
        mWindowManager = LocalServices.getService(WindowManagerInternal.class);

        final Property<MagnificationController, MagnificationSpec> property =
                Property.of(MagnificationController.class, MagnificationSpec.class,
                        PROPERTY_NAME_MAGNIFICATION_SPEC);
        final MagnificationSpecEvaluator evaluator = new MagnificationSpecEvaluator();
        final long animationDuration = context.getResources().getInteger(
                R.integer.config_longAnimTime);
        mTransformationAnimator = ObjectAnimator.ofObject(this, property, evaluator,
                mSentMagnificationSpec, mCurrentMagnificationSpec);
        mTransformationAnimator.setDuration(animationDuration);
        mTransformationAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
    }

    /**
     * @return {@code true} if magnification is active, e.g. the scale
     *         is > 1, {@code false} otherwise
     */
    public boolean isMagnifying() {
        return mCurrentMagnificationSpec.scale > 1.0f;
    }

    /**
     * Sets the magnified region.
     *
     * @param region the region to set
     *  @param updateSpec {@code true} to update the scale and center based on
     *                    the region bounds, {@code false} to leave them as-is
     */
    public void setMagnifiedRegion(Region region, boolean updateSpec) {
        mMagnifiedBounds.set(region);

        if (updateSpec) {
            final Rect magnifiedFrame = mTempRect;
            region.getBounds(magnifiedFrame);
            final float scale = mSentMagnificationSpec.scale;
            final float offsetX = mSentMagnificationSpec.offsetX;
            final float offsetY = mSentMagnificationSpec.offsetY;
            final float centerX = (-offsetX + magnifiedFrame.width() / 2) / scale;
            final float centerY = (-offsetY + magnifiedFrame.height() / 2) / scale;
            setScaleAndMagnifiedRegionCenter(scale, centerX, centerY, false);
        } else {
            mAms.onMagnificationStateChanged();
        }
    }

    /**
     * Returns whether the magnified region contains the specified
     * screen-relative coordinates.
     *
     * @param x the screen-relative X coordinate to check
     * @param y the screen-relative Y coordinate to check
     * @return {@code true} if the coordinate is contained within the
     *         magnified region, or {@code false} otherwise
     */
    public boolean magnifiedRegionContains(float x, float y) {
        return mMagnifiedBounds.contains((int) x, (int) y);
    }

    /**
     * Populates the specified rect with the bounds of the magnified
     * region.
     *
     * @param outBounds rect to populate with the bounds of the magnified
     *                  region
     */
    public void getMagnifiedBounds(Rect outBounds) {
        mMagnifiedBounds.getBounds(outBounds);
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @return the scale
     */
    public float getScale() {
        return mCurrentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the X offset
     */
    public float getOffsetX() {
        return mCurrentMagnificationSpec.offsetX;
    }

    /**
     * Returns the Y offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the Y offset
     */
    public float getOffsetY() {
        return mCurrentMagnificationSpec.offsetY;
    }

    /**
     * Returns the scale currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the scale currently used by the window manager
     */
    public float getSentScale() {
        return mSentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the X offset currently used by the window manager
     */
    public float getSentOffsetX() {
        return mSentMagnificationSpec.offsetX;
    }

    /**
     * Returns the Y offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the Y offset currently used by the window manager
     */
    public float getSentOffsetY() {
        return mSentMagnificationSpec.offsetY;
    }

    /**
     * Resets the magnification scale and center, optionally animating the
     * transition.
     *
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     */
    public void reset(boolean animate) {
        if (mTransformationAnimator.isRunning()) {
            mTransformationAnimator.cancel();
        }
        mCurrentMagnificationSpec.clear();
        if (animate) {
            animateMagnificationSpec(mSentMagnificationSpec,
                    mCurrentMagnificationSpec);
        } else {
            setMagnificationSpec(mCurrentMagnificationSpec);
        }
        final Rect bounds = mTempRect;
        bounds.setEmpty();
        mAms.onMagnificationStateChanged();
    }

    /**
     * Scales the magnified region around the specified pivot point,
     * optionally animating the transition. If animation is disabled, the
     * transition is immediate.
     *
     * @param scale the target scale, must be >= 1
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     */
    public void setScale(float scale, float pivotX, float pivotY, boolean animate) {
        final Rect magnifiedFrame = mTempRect;
        mMagnifiedBounds.getBounds(magnifiedFrame);
        final MagnificationSpec spec = mCurrentMagnificationSpec;
        final float oldScale = spec.scale;
        final float oldCenterX = (-spec.offsetX + magnifiedFrame.width() / 2) / oldScale;
        final float oldCenterY = (-spec.offsetY + magnifiedFrame.height() / 2) / oldScale;
        final float normPivotX = (-spec.offsetX + pivotX) / oldScale;
        final float normPivotY = (-spec.offsetY + pivotY) / oldScale;
        final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
        final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
        final float centerX = normPivotX + offsetX;
        final float centerY = normPivotY + offsetY;
        setScaleAndMagnifiedRegionCenter(scale, centerX, centerY, animate);
    }

    /**
     * Sets the center of the magnified region, optionally animating the
     * transition. If animation is disabled, the transition is immediate.
     *
     * @param centerX the screen-relative X coordinate around which to
     *                center
     * @param centerY the screen-relative Y coordinate around which to
     *                center
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     */
    public void setMagnifiedRegionCenter(float centerX, float centerY, boolean animate) {
        setScaleAndMagnifiedRegionCenter(mCurrentMagnificationSpec.scale, centerX, centerY,
                animate);
    }

    /**
     * Sets the scale and center of the magnified region, optionally
     * animating the transition. If animation is disabled, the transition
     * is immediate.
     *
     * @param scale the target scale, must be >= 1
     * @param centerX the screen-relative X coordinate around which to
     *                center and scale
     * @param centerY the screen-relative Y coordinate around which to
     *                center and scale
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     */
    public void setScaleAndMagnifiedRegionCenter(float scale, float centerX, float centerY,
            boolean animate) {
        if (Float.compare(mCurrentMagnificationSpec.scale, scale) == 0
                && Float.compare(mCurrentMagnificationSpec.offsetX, centerX) == 0
                && Float.compare(mCurrentMagnificationSpec.offsetY, centerY) == 0) {
            return;
        }
        if (mTransformationAnimator.isRunning()) {
            mTransformationAnimator.cancel();
        }
        if (DEBUG_MAGNIFICATION_CONTROLLER) {
            Slog.i(LOG_TAG, "scale: " + scale + " offsetX: " + centerX + " offsetY: " + centerY);
        }
        updateMagnificationSpec(scale, centerX, centerY);
        if (animate) {
            animateMagnificationSpec(mSentMagnificationSpec,
                    mCurrentMagnificationSpec);
        } else {
            setMagnificationSpec(mCurrentMagnificationSpec);
        }
        mAms.onMagnificationStateChanged();
    }

    /**
     * Offsets the center of the magnified region.
     *
     * @param offsetX the amount in pixels to offset the X center
     * @param offsetY the amount in pixels to offset the Y center
     */
    public void offsetMagnifiedRegionCenter(float offsetX, float offsetY) {
        final float nonNormOffsetX = mCurrentMagnificationSpec.offsetX - offsetX;
        mCurrentMagnificationSpec.offsetX = Math.min(Math.max(nonNormOffsetX,
                getMinOffsetX()), 0);
        final float nonNormOffsetY = mCurrentMagnificationSpec.offsetY - offsetY;
        mCurrentMagnificationSpec.offsetY = Math.min(Math.max(nonNormOffsetY,
                getMinOffsetY()), 0);
        setMagnificationSpec(mCurrentMagnificationSpec);
    }

    private void updateMagnificationSpec(float scale, float magnifiedCenterX,
            float magnifiedCenterY) {
        final Rect magnifiedFrame = mTempRect;
        mMagnifiedBounds.getBounds(magnifiedFrame);
        mCurrentMagnificationSpec.scale = scale;
        final int viewportWidth = magnifiedFrame.width();
        final float nonNormOffsetX = viewportWidth / 2 - magnifiedCenterX * scale;
        mCurrentMagnificationSpec.offsetX = Math.min(Math.max(nonNormOffsetX,
                getMinOffsetX()), 0);
        final int viewportHeight = magnifiedFrame.height();
        final float nonNormOffsetY = viewportHeight / 2 - magnifiedCenterY * scale;
        mCurrentMagnificationSpec.offsetY = Math.min(Math.max(nonNormOffsetY,
                getMinOffsetY()), 0);
    }

    private float getMinOffsetX() {
        final Rect magnifiedFrame = mTempRect;
        mMagnifiedBounds.getBounds(magnifiedFrame);
        final float viewportWidth = magnifiedFrame.width();
        return viewportWidth - viewportWidth * mCurrentMagnificationSpec.scale;
    }

    private float getMinOffsetY() {
        final Rect magnifiedFrame = mTempRect;
        mMagnifiedBounds.getBounds(magnifiedFrame);
        final float viewportHeight = magnifiedFrame.height();
        return viewportHeight - viewportHeight * mCurrentMagnificationSpec.scale;
    }

    private void animateMagnificationSpec(MagnificationSpec fromSpec,
            MagnificationSpec toSpec) {
        mTransformationAnimator.setObjectValues(fromSpec, toSpec);
        mTransformationAnimator.start();
    }

    private void setMagnificationSpec(MagnificationSpec spec) {
        if (DEBUG_SET_MAGNIFICATION_SPEC) {
            Slog.i(LOG_TAG, "Sending: " + spec);
        }
        mSentMagnificationSpec.scale = spec.scale;
        mSentMagnificationSpec.offsetX = spec.offsetX;
        mSentMagnificationSpec.offsetY = spec.offsetY;
        mWindowManager.setMagnificationSpec(MagnificationSpec.obtain(spec));
    }

    private static class MagnificationSpecEvaluator implements TypeEvaluator<MagnificationSpec> {
        private final MagnificationSpec mTempTransformationSpec = MagnificationSpec.obtain();

        @Override
        public MagnificationSpec evaluate(float fraction, MagnificationSpec fromSpec,
                MagnificationSpec toSpec) {
            final MagnificationSpec result = mTempTransformationSpec;
            result.scale = fromSpec.scale + (toSpec.scale - fromSpec.scale) * fraction;
            result.offsetX = fromSpec.offsetX + (toSpec.offsetX - fromSpec.offsetX) * fraction;
            result.offsetY = fromSpec.offsetY + (toSpec.offsetY - fromSpec.offsetY) * fraction;
            return result;
        }
    }
}
