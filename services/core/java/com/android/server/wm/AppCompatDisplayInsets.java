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

package com.android.server.wm;


import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Surface;

/**
 * The precomputed insets of the display in each rotation. This is used to make the size
 * compatibility mode activity compute the configuration without relying on its current display.
 */
class AppCompatDisplayInsets {
    /** The original rotation the compat insets were computed in. */
    final @Surface.Rotation int mOriginalRotation;
    /** The original requested orientation for the activity. */
    final @Configuration.Orientation int mOriginalRequestedOrientation;
    /** The container width on rotation 0. */
    private final int mWidth;
    /** The container height on rotation 0. */
    private final int mHeight;
    /** Whether the {@link Task} windowingMode represents a floating window*/
    final boolean mIsFloating;
    /**
     * Whether is letterboxed because of fixed orientation or aspect ratio when
     * the unresizable activity is first shown.
     */
    final boolean mIsInFixedOrientationOrAspectRatioLetterbox;
    /**
     * The nonDecorInsets for each rotation. Includes the navigation bar and cutout insets. It
     * is used to compute the appBounds.
     */
    final Rect[] mNonDecorInsets = new Rect[4];
    /**
     * The stableInsets for each rotation. Includes the status bar inset and the
     * nonDecorInsets. It is used to compute {@link Configuration#screenWidthDp} and
     * {@link Configuration#screenHeightDp}.
     */
    final Rect[] mStableInsets = new Rect[4];

    /** Constructs the environment to simulate the bounds behavior of the given container. */
    AppCompatDisplayInsets(@NonNull DisplayContent display, @NonNull ActivityRecord container,
            @Nullable Rect letterboxedContainerBounds, boolean useOverrideInsets) {
        mOriginalRotation = display.getRotation();
        mIsFloating = container.getWindowConfiguration().tasksAreFloating();
        mOriginalRequestedOrientation = container.getRequestedConfigurationOrientation();
        if (mIsFloating) {
            final Rect containerBounds = container.getWindowConfiguration().getBounds();
            mWidth = containerBounds.width();
            mHeight = containerBounds.height();
            // For apps in freeform, the task bounds are the parent bounds from the app's
            // perspective. No insets because within a window.
            final Rect emptyRect = new Rect();
            for (int rotation = 0; rotation < 4; rotation++) {
                mNonDecorInsets[rotation] = emptyRect;
                mStableInsets[rotation] = emptyRect;
            }
            mIsInFixedOrientationOrAspectRatioLetterbox = false;
            return;
        }

        final Task task = container.getTask();

        mIsInFixedOrientationOrAspectRatioLetterbox = letterboxedContainerBounds != null;

        // Store the bounds of the Task for the non-resizable activity to use in size compat
        // mode so that the activity will not be resized regardless the windowing mode it is
        // currently in.
        // When an activity needs to be letterboxed because of fixed orientation or aspect
        // ratio, use resolved bounds instead of task bounds since the activity will be
        // displayed within these even if it is in size compat mode.
        final Rect filledContainerBounds = mIsInFixedOrientationOrAspectRatioLetterbox
                ? letterboxedContainerBounds
                : task != null ? task.getBounds() : display.getBounds();
        final boolean useActivityRotation = container.hasFixedRotationTransform()
                && mIsInFixedOrientationOrAspectRatioLetterbox;
        final int filledContainerRotation = useActivityRotation
                ? container.getWindowConfiguration().getRotation()
                : display.getConfiguration().windowConfiguration.getRotation();
        final Point dimensions = getRotationZeroDimensions(
                filledContainerBounds, filledContainerRotation);
        mWidth = dimensions.x;
        mHeight = dimensions.y;

        // Bounds of the filled container if it doesn't fill the display.
        final Rect unfilledContainerBounds =
                filledContainerBounds.equals(display.getBounds()) ? null : new Rect();
        final DisplayPolicy policy = display.getDisplayPolicy();
        for (int rotation = 0; rotation < 4; rotation++) {
            mNonDecorInsets[rotation] = new Rect();
            mStableInsets[rotation] = new Rect();
            final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
            final int dw = rotated ? display.mBaseDisplayHeight : display.mBaseDisplayWidth;
            final int dh = rotated ? display.mBaseDisplayWidth : display.mBaseDisplayHeight;
            final DisplayPolicy.DecorInsets.Info decorInfo =
                    policy.getDecorInsetsInfo(rotation, dw, dh);
            if (useOverrideInsets) {
                mStableInsets[rotation].set(decorInfo.mOverrideConfigInsets);
                mNonDecorInsets[rotation].set(decorInfo.mOverrideNonDecorInsets);
            } else {
                mStableInsets[rotation].set(decorInfo.mConfigInsets);
                mNonDecorInsets[rotation].set(decorInfo.mNonDecorInsets);
            }

            if (unfilledContainerBounds == null) {
                continue;
            }
            // The insets is based on the display, but the container may be smaller than the
            // display, so update the insets to exclude parts that are not intersected with the
            // container.
            unfilledContainerBounds.set(filledContainerBounds);
            display.rotateBounds(
                    filledContainerRotation,
                    rotation,
                    unfilledContainerBounds);
            updateInsetsForBounds(unfilledContainerBounds, dw, dh, mNonDecorInsets[rotation]);
            updateInsetsForBounds(unfilledContainerBounds, dw, dh, mStableInsets[rotation]);
        }
    }

    /**
     * Gets the width and height of the {@code container} when it is not rotated, so that after
     * the display is rotated, we can calculate the bounds by rotating the dimensions.
     * @see #getBoundsByRotation
     */
    @NonNull
    private static Point getRotationZeroDimensions(final @NonNull Rect bounds,
            @Surface.Rotation int rotation) {
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int width = bounds.width();
        final int height = bounds.height();
        return rotated ? new Point(height, width) : new Point(width, height);
    }

    /**
     * Updates the display insets to exclude the parts that are not intersected with the given
     * bounds.
     */
    private static void updateInsetsForBounds(@NonNull Rect bounds, int displayWidth,
            int displayHeight, @NonNull Rect inset) {
        inset.left = Math.max(0, inset.left - bounds.left);
        inset.top = Math.max(0, inset.top - bounds.top);
        inset.right = Math.max(0, bounds.right - displayWidth + inset.right);
        inset.bottom = Math.max(0, bounds.bottom - displayHeight + inset.bottom);
    }

    void getBoundsByRotation(@NonNull Rect outBounds, @Surface.Rotation int rotation) {
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int dw = rotated ? mHeight : mWidth;
        final int dh = rotated ? mWidth : mHeight;
        outBounds.set(0, 0, dw, dh);
    }

    void getFrameByOrientation(@NonNull Rect outBounds,
            @Configuration.Orientation int orientation) {
        final int longSide = Math.max(mWidth, mHeight);
        final int shortSide = Math.min(mWidth, mHeight);
        final boolean isLandscape = orientation == ORIENTATION_LANDSCAPE;
        outBounds.set(0, 0, isLandscape ? longSide : shortSide,
                isLandscape ? shortSide : longSide);
    }

    /** Gets the horizontal centered container bounds for size compatibility mode. */
    void getContainerBounds(@NonNull Rect outAppBounds, @NonNull Rect outBounds,
            @Surface.Rotation int rotation, @Configuration.Orientation int orientation,
            boolean orientationRequested, boolean isFixedToUserRotation) {
        getFrameByOrientation(outBounds, orientation);
        if (mIsFloating) {
            outAppBounds.set(outBounds);
            return;
        }

        getBoundsByRotation(outAppBounds, rotation);
        final int dW = outAppBounds.width();
        final int dH = outAppBounds.height();
        final boolean isOrientationMismatched =
                ((outBounds.width() > outBounds.height()) != (dW > dH));

        if (isOrientationMismatched && isFixedToUserRotation && orientationRequested) {
            // The orientation is mismatched but the display cannot rotate. The bounds will fit
            // to the short side of container.
            if (orientation == ORIENTATION_LANDSCAPE) {
                outBounds.bottom = (int) ((float) dW * dW / dH);
                outBounds.right = dW;
            } else {
                outBounds.bottom = dH;
                outBounds.right = (int) ((float) dH * dH / dW);
            }
            outBounds.offset(getCenterOffset(mWidth, outBounds.width()), 0 /* dy */);
        }
        outAppBounds.set(outBounds);

        if (isOrientationMismatched) {
            // One side of container is smaller than the requested size, then it will be scaled
            // and the final position will be calculated according to the parent container and
            // scale, so the original size shouldn't be shrunk by insets.
            final Rect insets = mNonDecorInsets[rotation];
            outBounds.offset(insets.left, insets.top);
            outAppBounds.offset(insets.left, insets.top);
        } else if (rotation != ROTATION_UNDEFINED) {
            // Ensure the app bounds won't overlap with insets.
            TaskFragment.intersectWithInsetsIfFits(outAppBounds, outBounds,
                    mNonDecorInsets[rotation]);
        }
    }

    /** @return The horizontal / vertical offset of putting the content in the center of viewport.*/
    private static int getCenterOffset(int viewportDim, int contentDim) {
        return (int) ((viewportDim - contentDim + 1) * 0.5f);
    }
}
