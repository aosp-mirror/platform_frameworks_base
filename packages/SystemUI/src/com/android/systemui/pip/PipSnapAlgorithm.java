/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Size;

import javax.inject.Inject;

/**
 * Calculates the snap targets and the snap position for the PIP given a position and a velocity.
 * All bounds are relative to the display top/left.
 */
public class PipSnapAlgorithm {

    private final Context mContext;

    private final float mDefaultSizePercent;
    private final float mMinAspectRatioForMinSize;
    private final float mMaxAspectRatioForMinSize;

    @Inject
    public PipSnapAlgorithm(Context context) {
        Resources res = context.getResources();
        mContext = context;
        mDefaultSizePercent = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultSizePercent);
        mMaxAspectRatioForMinSize = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureAspectRatioLimitForMinSize);
        mMinAspectRatioForMinSize = 1f / mMaxAspectRatioForMinSize;
    }

    /**
     * @return returns a fraction that describes where along the {@param movementBounds} the
     *         {@param stackBounds} are. If the {@param stackBounds} are not currently on the
     *         {@param movementBounds} exactly, then they will be snapped to the movement bounds.
     *
     *         The fraction is defined in a clockwise fashion against the {@param movementBounds}:
     *
     *            0   1
     *          4 +---+ 1
     *            |   |
     *          3 +---+ 2
     *            3   2
     */
    public float getSnapFraction(Rect stackBounds, Rect movementBounds) {
        final Rect tmpBounds = new Rect();
        snapRectToClosestEdge(stackBounds, movementBounds, tmpBounds);
        final float widthFraction = (float) (tmpBounds.left - movementBounds.left) /
                movementBounds.width();
        final float heightFraction = (float) (tmpBounds.top - movementBounds.top) /
                movementBounds.height();
        if (tmpBounds.top == movementBounds.top) {
            return widthFraction;
        } else if (tmpBounds.left == movementBounds.right) {
            return 1f + heightFraction;
        } else if (tmpBounds.top == movementBounds.bottom) {
            return 2f + (1f - widthFraction);
        } else {
            return 3f + (1f - heightFraction);
        }
    }

    /**
     * Moves the {@param stackBounds} along the {@param movementBounds} to the given snap fraction.
     * See {@link #getSnapFraction(Rect, Rect)}.
     *
     * The fraction is define in a clockwise fashion against the {@param movementBounds}:
     *
     *    0   1
     *  4 +---+ 1
     *    |   |
     *  3 +---+ 2
     *    3   2
     */
    public void applySnapFraction(Rect stackBounds, Rect movementBounds, float snapFraction) {
        if (snapFraction < 1f) {
            int offset = movementBounds.left + (int) (snapFraction * movementBounds.width());
            stackBounds.offsetTo(offset, movementBounds.top);
        } else if (snapFraction < 2f) {
            snapFraction -= 1f;
            int offset = movementBounds.top + (int) (snapFraction * movementBounds.height());
            stackBounds.offsetTo(movementBounds.right, offset);
        } else if (snapFraction < 3f) {
            snapFraction -= 2f;
            int offset = movementBounds.left + (int) ((1f - snapFraction) * movementBounds.width());
            stackBounds.offsetTo(offset, movementBounds.bottom);
        } else {
            snapFraction -= 3f;
            int offset = movementBounds.top + (int) ((1f - snapFraction) * movementBounds.height());
            stackBounds.offsetTo(movementBounds.left, offset);
        }
    }

    /**
     * Adjusts {@param movementBoundsOut} so that it is the movement bounds for the given
     * {@param stackBounds}.
     */
    public void getMovementBounds(Rect stackBounds, Rect insetBounds, Rect movementBoundsOut,
            int bottomOffset) {
        // Adjust the right/bottom to ensure the stack bounds never goes offscreen
        movementBoundsOut.set(insetBounds);
        movementBoundsOut.right = Math.max(insetBounds.left, insetBounds.right -
                stackBounds.width());
        movementBoundsOut.bottom = Math.max(insetBounds.top, insetBounds.bottom -
                stackBounds.height());
        movementBoundsOut.bottom -= bottomOffset;
    }

    /**
     * @return the size of the PiP at the given {@param aspectRatio}, ensuring that the minimum edge
     * is at least {@param minEdgeSize}.
     */
    public Size getSizeForAspectRatio(float aspectRatio, float minEdgeSize, int displayWidth,
            int displayHeight) {
        final int smallestDisplaySize = Math.min(displayWidth, displayHeight);
        final int minSize = (int) Math.max(minEdgeSize, smallestDisplaySize * mDefaultSizePercent);

        final int width;
        final int height;
        if (aspectRatio <= mMinAspectRatioForMinSize || aspectRatio > mMaxAspectRatioForMinSize) {
            // Beyond these points, we can just use the min size as the shorter edge
            if (aspectRatio <= 1) {
                // Portrait, width is the minimum size
                width = minSize;
                height = Math.round(width / aspectRatio);
            } else {
                // Landscape, height is the minimum size
                height = minSize;
                width = Math.round(height * aspectRatio);
            }
        } else {
            // Within these points, we ensure that the bounds fit within the radius of the limits
            // at the points
            final float widthAtMaxAspectRatioForMinSize = mMaxAspectRatioForMinSize * minSize;
            final float radius = PointF.length(widthAtMaxAspectRatioForMinSize, minSize);
            height = (int) Math.round(Math.sqrt((radius * radius) /
                    (aspectRatio * aspectRatio + 1)));
            width = Math.round(height * aspectRatio);
        }
        return new Size(width, height);
    }

    /**
     * @return the adjusted size so that it conforms to the given aspectRatio, ensuring that the
     * minimum edge is at least minEdgeSize.
     */
    public Size getSizeForAspectRatio(Size size, float aspectRatio, float minEdgeSize) {
        final int smallestSize = Math.min(size.getWidth(), size.getHeight());
        final int minSize = (int) Math.max(minEdgeSize, smallestSize);

        final int width;
        final int height;
        if (aspectRatio <= 1) {
            // Portrait, width is the minimum size.
            width = minSize;
            height = Math.round(width / aspectRatio);
        } else {
            // Landscape, height is the minimum size
            height = minSize;
            width = Math.round(height * aspectRatio);
        }
        return new Size(width, height);
    }

    /**
     * Snaps the {@param stackBounds} to the closest edge of the {@param movementBounds} and writes
     * the new bounds out to {@param boundsOut}.
     */
    public void snapRectToClosestEdge(Rect stackBounds, Rect movementBounds, Rect boundsOut) {
        final int boundedLeft = Math.max(movementBounds.left, Math.min(movementBounds.right,
                stackBounds.left));
        final int boundedTop = Math.max(movementBounds.top, Math.min(movementBounds.bottom,
                stackBounds.top));
        boundsOut.set(stackBounds);

        // Otherwise, just find the closest edge
        final int fromLeft = Math.abs(stackBounds.left - movementBounds.left);
        final int fromTop = Math.abs(stackBounds.top - movementBounds.top);
        final int fromRight = Math.abs(movementBounds.right - stackBounds.left);
        final int fromBottom = Math.abs(movementBounds.bottom - stackBounds.top);
        final int shortest = Math.min(Math.min(fromLeft, fromRight), Math.min(fromTop, fromBottom));
        if (shortest == fromLeft) {
            boundsOut.offsetTo(movementBounds.left, boundedTop);
        } else if (shortest == fromTop) {
            boundsOut.offsetTo(boundedLeft, movementBounds.top);
        } else if (shortest == fromRight) {
            boundsOut.offsetTo(movementBounds.right, boundedTop);
        } else {
            boundsOut.offsetTo(boundedLeft, movementBounds.bottom);
        }
    }
}
