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
import android.util.DisplayMetrics;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;

import java.util.ArrayList;

/**
 * Abstract class that handles size & positioning common to the ripple & focus states.
 */
abstract class RippleComponent {
    protected final RippleDrawable mOwner;

    /** Bounds used for computing max radius. May be modified by the owner. */
    protected final Rect mBounds;

    /** Whether we have an explicit maximum radius. */
    private boolean mHasMaxRadius;

    /** How big this ripple should be when fully entered. */
    protected float mTargetRadius;

    /** Screen density used to adjust pixel-based constants. */
    protected float mDensityScale;

    public RippleComponent(RippleDrawable owner, Rect bounds) {
        mOwner = owner;
        mBounds = bounds;
    }

    public void onBoundsChange() {
        if (!mHasMaxRadius) {
            mTargetRadius = getTargetRadius(mBounds);
            onTargetRadiusChanged(mTargetRadius);
        }
    }

    public final void setup(float maxRadius, int densityDpi) {
        if (maxRadius >= 0) {
            mHasMaxRadius = true;
            mTargetRadius = maxRadius;
        } else {
            mTargetRadius = getTargetRadius(mBounds);
        }

        mDensityScale = densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;

        onTargetRadiusChanged(mTargetRadius);
    }

    private static float getTargetRadius(Rect bounds) {
        final float halfWidth = bounds.width() / 2.0f;
        final float halfHeight = bounds.height() / 2.0f;
        return (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
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

    protected final void invalidateSelf() {
        mOwner.invalidateSelf(false);
    }

    protected final void onHotspotBoundsChanged() {
        if (!mHasMaxRadius) {
            mTargetRadius = getTargetRadius(mBounds);
            onTargetRadiusChanged(mTargetRadius);
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
}
