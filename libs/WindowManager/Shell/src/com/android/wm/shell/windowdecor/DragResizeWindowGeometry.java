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

package com.android.wm.shell.windowdecor;

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;

import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_LEFT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Size;
import android.view.MotionEvent;

import java.util.Objects;

/**
 * Geometry for an input region for a particular window.
 */
final class DragResizeWindowGeometry {
    private final int mTaskCornerRadius;
    private final Size mTaskSize;
    // The size of the handle applied to the edges of the window, for the user to drag resize.
    private final int mResizeHandleThickness;
    // The size of the rectangle applied to the corners of the window, for the user to drag resize.
    private final int mCornerSize;
    // The bounds for the corner drag region, which can resize the task in two directions.
    private final @NonNull Rect mLeftTopCornerBounds;
    private final @NonNull Rect mRightTopCornerBounds;
    private final @NonNull Rect mLeftBottomCornerBounds;
    private final @NonNull Rect mRightBottomCornerBounds;
    // The bounds for each edge drag region, which can resize the task in one direction.
    private final @NonNull Rect mTopEdgeBounds;
    private final @NonNull Rect mLeftEdgeBounds;
    private final @NonNull Rect mRightEdgeBounds;
    private final @NonNull Rect mBottomEdgeBounds;

    DragResizeWindowGeometry(int taskCornerRadius, @NonNull Size taskSize,
            int resizeHandleThickness, int cornerSize) {
        mTaskCornerRadius = taskCornerRadius;
        mTaskSize = taskSize;
        mResizeHandleThickness = resizeHandleThickness;
        mCornerSize = cornerSize;

        // Save touch areas in each corner.
        final int cornerRadius = mCornerSize / 2;
        mLeftTopCornerBounds = new Rect(
                -cornerRadius,
                -cornerRadius,
                cornerRadius,
                cornerRadius);
        mRightTopCornerBounds = new Rect(
                mTaskSize.getWidth() - cornerRadius,
                -cornerRadius,
                mTaskSize.getWidth() + cornerRadius,
                cornerRadius);
        mLeftBottomCornerBounds = new Rect(
                -cornerRadius,
                mTaskSize.getHeight() - cornerRadius,
                cornerRadius,
                mTaskSize.getHeight() + cornerRadius);
        mRightBottomCornerBounds = new Rect(
                mTaskSize.getWidth() - cornerRadius,
                mTaskSize.getHeight() - cornerRadius,
                mTaskSize.getWidth() + cornerRadius,
                mTaskSize.getHeight() + cornerRadius);

        // Save touch areas for each edge.
        mTopEdgeBounds = new Rect(
                -mResizeHandleThickness,
                -mResizeHandleThickness,
                mTaskSize.getWidth() + mResizeHandleThickness,
                0);
        mLeftEdgeBounds = new Rect(
                -mResizeHandleThickness,
                0,
                0,
                mTaskSize.getHeight());
        mRightEdgeBounds = new Rect(
                mTaskSize.getWidth(),
                0,
                mTaskSize.getWidth() + mResizeHandleThickness,
                mTaskSize.getHeight());
        mBottomEdgeBounds = new Rect(
                -mResizeHandleThickness,
                mTaskSize.getHeight(),
                mTaskSize.getWidth() + mResizeHandleThickness,
                mTaskSize.getHeight() + mResizeHandleThickness);
    }

    /**
     * Returns the size of the task this geometry is calculated for.
     */
    @NonNull Size getTaskSize() {
        // Safe to return directly since size is immutable.
        return mTaskSize;
    }

    /**
     * Returns the union of all regions that can be touched for drag resizing; the corners window
     * edges.
     */
    void union(@NonNull Region region) {
        // Apply the edge resize regions.
        region.union(mTopEdgeBounds);
        region.union(mLeftEdgeBounds);
        region.union(mRightEdgeBounds);
        region.union(mBottomEdgeBounds);

        // Apply the corners as well.
        region.union(mLeftTopCornerBounds);
        region.union(mRightTopCornerBounds);
        region.union(mLeftBottomCornerBounds);
        region.union(mRightBottomCornerBounds);
    }

    /**
     * Returns if this MotionEvent should be handled, based on its source and position.
     */
    boolean shouldHandleEvent(@NonNull MotionEvent e, @NonNull Point offset) {
        return shouldHandleEvent(e, isTouchEvent(e), offset);
    }

    /**
     * Returns if this MotionEvent should be handled, based on its source and position.
     */
    boolean shouldHandleEvent(@NonNull MotionEvent e, boolean isTouch, @NonNull Point offset) {
        boolean result;
        final float x = e.getX(0) + offset.x;
        final float y = e.getY(0) + offset.y;
        if (isTouch) {
            result = isInCornerBounds(x, y);
        } else {
            result = isInResizeHandleBounds(x, y);
        }
        return result;
    }

    private boolean isTouchEvent(@NonNull MotionEvent e) {
        return (e.getSource() & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN;
    }

    private boolean isInCornerBounds(float xf, float yf) {
        return calculateCornersCtrlType(xf, yf) != 0;
    }

    private boolean isInResizeHandleBounds(float x, float y) {
        return calculateResizeHandlesCtrlType(x, y) != 0;
    }

    /**
     * Returns the control type for the drag-resize, based on the touch regions and this
     * MotionEvent's coordinates.
     */
    @DragPositioningCallback.CtrlType
    int calculateCtrlType(boolean isTouch, float x, float y) {
        if (isTouch) {
            return calculateCornersCtrlType(x, y);
        }
        return calculateResizeHandlesCtrlType(x, y);
    }

    @DragPositioningCallback.CtrlType
    private int calculateCornersCtrlType(float x, float y) {
        int xi = (int) x;
        int yi = (int) y;
        if (mLeftTopCornerBounds.contains(xi, yi)) {
            return CTRL_TYPE_LEFT | CTRL_TYPE_TOP;
        }
        if (mLeftBottomCornerBounds.contains(xi, yi)) {
            return CTRL_TYPE_LEFT | CTRL_TYPE_BOTTOM;
        }
        if (mRightTopCornerBounds.contains(xi, yi)) {
            return CTRL_TYPE_RIGHT | CTRL_TYPE_TOP;
        }
        if (mRightBottomCornerBounds.contains(xi, yi)) {
            return CTRL_TYPE_RIGHT | CTRL_TYPE_BOTTOM;
        }
        return 0;
    }

    @DragPositioningCallback.CtrlType
    private int calculateResizeHandlesCtrlType(float x, float y) {
        int ctrlType = 0;
        // mTaskCornerRadius is only used in comparing with corner regions. Comparisons with
        // sides will use the bounds specified and not go into task bounds.
        if (x < mTaskCornerRadius) {
            ctrlType |= CTRL_TYPE_LEFT;
        }
        if (x > mTaskSize.getWidth() - mTaskCornerRadius) {
            ctrlType |= CTRL_TYPE_RIGHT;
        }
        if (y < mTaskCornerRadius) {
            ctrlType |= CTRL_TYPE_TOP;
        }
        if (y > mTaskSize.getHeight() - mTaskCornerRadius) {
            ctrlType |= CTRL_TYPE_BOTTOM;
        }
        // Check distances from the center if it's in one of four corners.
        if ((ctrlType & (CTRL_TYPE_LEFT | CTRL_TYPE_RIGHT)) != 0
                && (ctrlType & (CTRL_TYPE_TOP | CTRL_TYPE_BOTTOM)) != 0) {
            return checkDistanceFromCenter(ctrlType, x, y);
        }
        // Otherwise, we should make sure we don't resize tasks inside task bounds.
        return (x < 0 || y < 0 || x >= mTaskSize.getWidth() || y >= mTaskSize.getHeight())
                ? ctrlType : 0;
    }

    /**
     * If corner input is not within appropriate distance of corner radius, do not use it.
     * If input is not on a corner or is within valid distance, return ctrlType.
     */
    @DragPositioningCallback.CtrlType
    private int checkDistanceFromCenter(@DragPositioningCallback.CtrlType int ctrlType, float x,
            float y) {
        final Point cornerRadiusCenter = calculateCenterForCornerRadius(ctrlType);
        double distanceFromCenter = Math.hypot(x - cornerRadiusCenter.x, y - cornerRadiusCenter.y);

        if (distanceFromCenter < mTaskCornerRadius + mResizeHandleThickness
                && distanceFromCenter >= mTaskCornerRadius) {
            return ctrlType;
        }
        return 0;
    }

    /**
     * Returns center of rounded corner circle; this is simply the corner if radius is 0.
     */
    private Point calculateCenterForCornerRadius(@DragPositioningCallback.CtrlType int ctrlType) {
        int centerX;
        int centerY;

        switch (ctrlType) {
            case CTRL_TYPE_LEFT | CTRL_TYPE_TOP: {
                centerX = mTaskCornerRadius;
                centerY = mTaskCornerRadius;
                break;
            }
            case CTRL_TYPE_LEFT | CTRL_TYPE_BOTTOM: {
                centerX = mTaskCornerRadius;
                centerY = mTaskSize.getHeight() - mTaskCornerRadius;
                break;
            }
            case CTRL_TYPE_RIGHT | CTRL_TYPE_TOP: {
                centerX = mTaskSize.getWidth() - mTaskCornerRadius;
                centerY = mTaskCornerRadius;
                break;
            }
            case CTRL_TYPE_RIGHT | CTRL_TYPE_BOTTOM: {
                centerX = mTaskSize.getWidth() - mTaskCornerRadius;
                centerY = mTaskSize.getHeight() - mTaskCornerRadius;
                break;
            }
            default: {
                throw new IllegalArgumentException(
                        "ctrlType should be complex, but it's 0x" + Integer.toHexString(ctrlType));
            }
        }
        return new Point(centerX, centerY);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (!(obj instanceof DragResizeWindowGeometry other)) return false;

        return this.mTaskCornerRadius == other.mTaskCornerRadius
                && this.mTaskSize.equals(other.mTaskSize)
                && this.mResizeHandleThickness == other.mResizeHandleThickness
                && this.mCornerSize == other.mCornerSize
                && this.mLeftTopCornerBounds.equals(other.mLeftTopCornerBounds)
                && this.mRightTopCornerBounds.equals(other.mRightTopCornerBounds)
                && this.mLeftBottomCornerBounds.equals(other.mLeftBottomCornerBounds)
                && this.mRightBottomCornerBounds.equals(other.mRightBottomCornerBounds)
                && this.mTopEdgeBounds.equals(other.mTopEdgeBounds)
                && this.mLeftEdgeBounds.equals(other.mLeftEdgeBounds)
                && this.mRightEdgeBounds.equals(other.mRightEdgeBounds)
                && this.mBottomEdgeBounds.equals(other.mBottomEdgeBounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mTaskCornerRadius,
                mTaskSize,
                mResizeHandleThickness,
                mCornerSize,
                mLeftTopCornerBounds,
                mRightTopCornerBounds,
                mLeftBottomCornerBounds,
                mRightBottomCornerBounds,
                mTopEdgeBounds,
                mLeftEdgeBounds,
                mRightEdgeBounds,
                mBottomEdgeBounds);
    }
}
