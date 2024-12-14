/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.common.split;

import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

import static com.android.wm.shell.common.split.SplitLayout.PARALLAX_ALIGN_CENTER;
import static com.android.wm.shell.common.split.SplitLayout.PARALLAX_DISMISSING;
import static com.android.wm.shell.common.split.SplitLayout.PARALLAX_FLEX;
import static com.android.wm.shell.common.split.SplitLayout.PARALLAX_NONE;
import static com.android.wm.shell.shared.animation.Interpolators.DIM_INTERPOLATOR;
import static com.android.wm.shell.shared.animation.Interpolators.SLOWDOWN_INTERPOLATOR;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 * This class governs how and when parallax and dimming effects are applied to task surfaces,
 * usually when the divider is being moved around by the user (or during an animation).
 */
class ResizingEffectPolicy {
    private final SplitLayout mSplitLayout;
    /** The parallax algorithm we are currently using. */
    private final int mParallaxType;

    int mShrinkSide = DOCKED_INVALID;

    // The current dismissing side.
    int mDismissingSide = DOCKED_INVALID;

    /**
     * A {@link Point} that stores a single x and y value, representing the parallax translation
     * we use on the app that the divider is moving toward. The app is either shrinking in size or
     * getting pushed off the screen.
     */
    final Point mRetreatingSideParallax = new Point();
    /**
     * A {@link Point} that stores a single x and y value, representing the parallax translation
     * we use on the app that the divider is moving away from. The app is either growing in size or
     * getting pulled onto the screen.
     */
    final Point mAdvancingSideParallax = new Point();

    // The dimming value to hint the dismissing side and progress.
    float mDismissingDimValue = 0.0f;

    /**
     * Content bounds for the app that the divider is moving toward. This is the content that is
     * currently drawn at the start of the divider movement. It stays unchanged throughout the
     * divider's movement.
     */
    final Rect mRetreatingContent = new Rect();
    /**
     * Surface bounds for the app that the divider is moving toward. This is the "canvas" on
     * which an app could potentially be drawn. It changes on every frame as the divider moves
     * around.
     */
    final Rect mRetreatingSurface = new Rect();
    /**
     * Content bounds for the app that the divider is moving away from. This is the content that
     * is currently drawn at the start of the divider movement. It stays unchanged throughout
     * the divider's movement.
     */
    final Rect mAdvancingContent = new Rect();
    /**
     * Surface bounds for the app that the divider is moving away from. This is the "canvas" on
     * which an app could potentially be drawn. It changes on every frame as the divider moves
     * around.
     */
    final Rect mAdvancingSurface = new Rect();

    final Rect mTempRect = new Rect();
    final Rect mTempRect2 = new Rect();

    ResizingEffectPolicy(int parallaxType, SplitLayout splitLayout) {
        mParallaxType = parallaxType;
        mSplitLayout = splitLayout;
    }

    /**
     * Calculates the desired parallax values and stores them in {@link #mRetreatingSideParallax}
     * and {@link #mAdvancingSideParallax}. These values will be then be applied in
     * {@link #adjustRootSurface}.
     *
     * @param position    The divider's position on the screen (x-coordinate in left-right split,
     *                    y-coordinate in top-bottom split).
     */
    void applyDividerPosition(
            int position, boolean isLeftRightSplit, DividerSnapAlgorithm snapAlgorithm) {
        mDismissingSide = DOCKED_INVALID;
        mRetreatingSideParallax.set(0, 0);
        mAdvancingSideParallax.set(0, 0);
        mDismissingDimValue = 0;
        Rect displayBounds = mSplitLayout.getRootBounds();

        int totalDismissingDistance = 0;
        if (position < snapAlgorithm.getFirstSplitTarget().position) {
            mDismissingSide = isLeftRightSplit ? DOCKED_LEFT : DOCKED_TOP;
            totalDismissingDistance = snapAlgorithm.getDismissStartTarget().position
                    - snapAlgorithm.getFirstSplitTarget().position;
        } else if (position > snapAlgorithm.getLastSplitTarget().position) {
            mDismissingSide = isLeftRightSplit ? DOCKED_RIGHT : DOCKED_BOTTOM;
            totalDismissingDistance = snapAlgorithm.getLastSplitTarget().position
                    - snapAlgorithm.getDismissEndTarget().position;
        }

        final boolean topLeftShrink = isLeftRightSplit
                ? position < mSplitLayout.getTopLeftContentBounds().right
                : position < mSplitLayout.getTopLeftContentBounds().bottom;
        if (topLeftShrink) {
            mShrinkSide = isLeftRightSplit ? DOCKED_LEFT : DOCKED_TOP;
            mRetreatingContent.set(mSplitLayout.getTopLeftContentBounds());
            mRetreatingSurface.set(mSplitLayout.getTopLeftBounds());
            mAdvancingContent.set(mSplitLayout.getBottomRightContentBounds());
            mAdvancingSurface.set(mSplitLayout.getBottomRightBounds());
        } else {
            mShrinkSide = isLeftRightSplit ? DOCKED_RIGHT : DOCKED_BOTTOM;
            mRetreatingContent.set(mSplitLayout.getBottomRightContentBounds());
            mRetreatingSurface.set(mSplitLayout.getBottomRightBounds());
            mAdvancingContent.set(mSplitLayout.getTopLeftContentBounds());
            mAdvancingSurface.set(mSplitLayout.getTopLeftBounds());
        }

        if (mDismissingSide != DOCKED_INVALID) {
            float fraction =
                    Math.max(0, Math.min(snapAlgorithm.calculateDismissingFraction(position), 1f));
            mDismissingDimValue = DIM_INTERPOLATOR.getInterpolation(fraction);
            if (mParallaxType == PARALLAX_DISMISSING) {
                fraction = calculateParallaxDismissingFraction(fraction, mDismissingSide);
                if (isLeftRightSplit) {
                    mRetreatingSideParallax.x = (int) (fraction * totalDismissingDistance);
                } else {
                    mRetreatingSideParallax.y = (int) (fraction * totalDismissingDistance);
                }
            }
        }

        if (mParallaxType == PARALLAX_ALIGN_CENTER) {
            if (isLeftRightSplit) {
                mRetreatingSideParallax.x =
                        (mRetreatingSurface.width() - mRetreatingContent.width()) / 2;
            } else {
                mRetreatingSideParallax.y =
                        (mRetreatingSurface.height() - mRetreatingContent.height()) / 2;
            }
        } else if (mParallaxType == PARALLAX_FLEX) {
            // Whether an app is getting pushed offscreen by the divider.
            boolean isRetreatingOffscreen = !displayBounds.contains(mRetreatingSurface);
            // Whether an app was getting pulled onscreen at the beginning of the drag.
            boolean advancingSideStartedOffscreen = !displayBounds.contains(mAdvancingContent);

            // The simpler case when an app gets pushed offscreen (e.g. 50:50 -> 90:10)
            if (isRetreatingOffscreen && !advancingSideStartedOffscreen) {
                // On the left side, we use parallax to simulate the contents sticking to the
                // divider. This is because surfaces naturally expand to the bottom and right,
                // so when a surface's area expands, the contents stick to the left. This is
                // correct behavior on the right-side surface, but not the left.
                if (topLeftShrink) {
                    if (isLeftRightSplit) {
                        mRetreatingSideParallax.x =
                                mRetreatingSurface.width() - mRetreatingContent.width();
                    } else {
                        mRetreatingSideParallax.y =
                                mRetreatingSurface.height() - mRetreatingContent.height();
                    }
                }
                // All other cases (e.g. 10:90 -> 50:50, 10:90 -> 90:10, 10:90 -> dismiss)
            } else {
                mTempRect.set(mRetreatingSurface);
                Point rootOffset = new Point();
                // 10:90 -> 50:50, 10:90, or dismiss right
                if (advancingSideStartedOffscreen) {
                    // We have to handle a complicated case here to keep the parallax smooth.
                    // When the divider crosses the 50% mark, the retreating-side app surface
                    // will start expanding offscreen. This is expected and unavoidable, but
                    // makes the parallax look disjointed. In order to preserve the illusion,
                    // we add another offset (rootOffset) to simulate the surface staying
                    // onscreen.
                    mTempRect.intersect(displayBounds);
                    if (mRetreatingSurface.left < displayBounds.left) {
                        rootOffset.x = displayBounds.left - mRetreatingSurface.left;
                    }
                    if (mRetreatingSurface.top < displayBounds.top) {
                        rootOffset.y = displayBounds.top - mRetreatingSurface.top;
                    }

                    // On the left side, we again have to simulate the contents sticking to the
                    // divider.
                    if (!topLeftShrink) {
                        if (isLeftRightSplit) {
                            mAdvancingSideParallax.x =
                                    mAdvancingSurface.width() - mAdvancingContent.width();
                        } else {
                            mAdvancingSideParallax.y =
                                    mAdvancingSurface.height() - mAdvancingContent.height();
                        }
                    }
                }

                // In all these cases, the shrinking app also receives a center parallax.
                if (isLeftRightSplit) {
                    mRetreatingSideParallax.x = rootOffset.x
                            + ((mTempRect.width() - mRetreatingContent.width()) / 2);
                } else {
                    mRetreatingSideParallax.y = rootOffset.y
                            + ((mTempRect.height() - mRetreatingContent.height()) / 2);
                }
            }
        }
    }

    /**
     * @return for a specified {@code fraction}, this returns an adjusted value that simulates a
     * slowing down parallax effect
     */
    private float calculateParallaxDismissingFraction(float fraction, int dockSide) {
        float result = SLOWDOWN_INTERPOLATOR.getInterpolation(fraction) / 3.5f;

        // Less parallax at the top, just because.
        if (dockSide == WindowManager.DOCKED_TOP) {
            result /= 2f;
        }
        return result;
    }

    /** Applies the calculated parallax and dimming values to task surfaces. */
    void adjustRootSurface(SurfaceControl.Transaction t,
            SurfaceControl leash1, SurfaceControl leash2) {
        SurfaceControl retreatingLeash = null;
        SurfaceControl advancingLeash = null;

        if (mParallaxType == PARALLAX_DISMISSING) {
            switch (mDismissingSide) {
                case DOCKED_TOP:
                case DOCKED_LEFT:
                    retreatingLeash = leash1;
                    mTempRect.set(mSplitLayout.getTopLeftBounds());
                    advancingLeash = leash2;
                    mTempRect2.set(mSplitLayout.getBottomRightBounds());
                    break;
                case DOCKED_BOTTOM:
                case DOCKED_RIGHT:
                    retreatingLeash = leash2;
                    mTempRect.set(mSplitLayout.getBottomRightBounds());
                    advancingLeash = leash1;
                    mTempRect2.set(mSplitLayout.getTopLeftBounds());
                    break;
            }
        } else if (mParallaxType == PARALLAX_ALIGN_CENTER || mParallaxType == PARALLAX_FLEX) {
            switch (mShrinkSide) {
                case DOCKED_TOP:
                case DOCKED_LEFT:
                    retreatingLeash = leash1;
                    mTempRect.set(mSplitLayout.getTopLeftBounds());
                    advancingLeash = leash2;
                    mTempRect2.set(mSplitLayout.getBottomRightBounds());
                    break;
                case DOCKED_BOTTOM:
                case DOCKED_RIGHT:
                    retreatingLeash = leash2;
                    mTempRect.set(mSplitLayout.getBottomRightBounds());
                    advancingLeash = leash1;
                    mTempRect2.set(mSplitLayout.getTopLeftBounds());
                    break;
            }
        }
        if (mParallaxType != PARALLAX_NONE
                && retreatingLeash != null && advancingLeash != null) {
            t.setPosition(retreatingLeash, mTempRect.left + mRetreatingSideParallax.x,
                    mTempRect.top + mRetreatingSideParallax.y);
            // Transform the screen-based split bounds to surface-based crop bounds.
            mTempRect.offsetTo(-mRetreatingSideParallax.x, -mRetreatingSideParallax.y);
            t.setWindowCrop(retreatingLeash, mTempRect);

            t.setPosition(advancingLeash, mTempRect2.left + mAdvancingSideParallax.x,
                    mTempRect2.top + mAdvancingSideParallax.y);
            // Transform the screen-based split bounds to surface-based crop bounds.
            mTempRect2.offsetTo(-mAdvancingSideParallax.x, -mAdvancingSideParallax.y);
            t.setWindowCrop(advancingLeash, mTempRect2);
        }
    }

    void adjustDimSurface(SurfaceControl.Transaction t,
            SurfaceControl dimLayer1, SurfaceControl dimLayer2) {
        SurfaceControl targetDimLayer;
        switch (mDismissingSide) {
            case DOCKED_TOP:
            case DOCKED_LEFT:
                targetDimLayer = dimLayer1;
                break;
            case DOCKED_BOTTOM:
            case DOCKED_RIGHT:
                targetDimLayer = dimLayer2;
                break;
            case DOCKED_INVALID:
            default:
                t.setAlpha(dimLayer1, 0).hide(dimLayer1);
                t.setAlpha(dimLayer2, 0).hide(dimLayer2);
                return;
        }
        t.setAlpha(targetDimLayer, mDismissingDimValue)
                .setVisibility(targetDimLayer, mDismissingDimValue > 0.001f);
    }
}
