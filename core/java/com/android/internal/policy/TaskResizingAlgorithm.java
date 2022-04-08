/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.policy;

import android.annotation.IntDef;
import android.graphics.Point;
import android.graphics.Rect;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Given a move coordinate (x, y), the original taks bounds and relevant details, calculate the new
 * bounds.
 *
 * @hide
 */
public class TaskResizingAlgorithm {

    @IntDef(flag = true,
            value = {
                    CTRL_NONE,
                    CTRL_LEFT,
                    CTRL_RIGHT,
                    CTRL_TOP,
                    CTRL_BOTTOM
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CtrlType {}

    public static final int CTRL_NONE   = 0x0;
    public static final int CTRL_LEFT   = 0x1;
    public static final int CTRL_RIGHT  = 0x2;
    public static final int CTRL_TOP    = 0x4;
    public static final int CTRL_BOTTOM = 0x8;

    // The minimal aspect ratio which needs to be met to count as landscape (or 1/.. for portrait).
    // Note: We do not use the 1.33 from the CDD here since the user is allowed to use what ever
    // aspect he desires.
    @VisibleForTesting
    public static final float MIN_ASPECT = 1.2f;

    /**
     * Given a (x, y) point and its original starting down point and its original bounds, calculate
     * and return a new resized bound.
     * @param x the new moved X point.
     * @param y the new moved Y point.
     * @param startDragX the original starting X point.
     * @param startDragY the original starting Y point.
     * @param originalBounds the original bound before resize.
     * @param ctrlType The type of resize operation.
     * @param minVisibleWidth The minimal width required for the new size.
     * @param minVisibleHeight The minimal height required for the new size.
     * @param maxVisibleSize The maximum size allowed.
     * @param preserveOrientation
     * @param startOrientationWasLandscape
     * @return
     */
    public static Rect resizeDrag(float x, float y, float startDragX, float startDragY,
            Rect originalBounds, int ctrlType, int minVisibleWidth, int minVisibleHeight,
            Point maxVisibleSize, boolean preserveOrientation,
            boolean startOrientationWasLandscape) {
        // This is a resizing operation.
        // We need to keep various constraints:
        // 1. mMinVisible[Width/Height] <= [width/height] <= mMaxVisibleSize.[x/y]
        // 2. The orientation is kept - if required.
        final int deltaX = Math.round(x - startDragX);
        final int deltaY = Math.round(y - startDragY);
        int left = originalBounds.left;
        int top = originalBounds.top;
        int right = originalBounds.right;
        int bottom = originalBounds.bottom;

        // Calculate the resulting width and height of the drag operation.
        int width = right - left;
        int height = bottom - top;
        if ((ctrlType & CTRL_LEFT) != 0) {
            width = Math.max(minVisibleWidth, Math.min(width - deltaX, maxVisibleSize.x));
        } else if ((ctrlType & CTRL_RIGHT) != 0) {
            width = Math.max(minVisibleWidth, Math.min(width + deltaX, maxVisibleSize.x));
        }
        if ((ctrlType & CTRL_TOP) != 0) {
            height = Math.max(minVisibleHeight, Math.min(height - deltaY, maxVisibleSize.y));
        } else if ((ctrlType & CTRL_BOTTOM) != 0) {
            height = Math.max(minVisibleHeight, Math.min(height + deltaY, maxVisibleSize.y));
        }

        // If we have to preserve the orientation - check that we are doing so.
        final float aspect = (float) width / (float) height;
        if (preserveOrientation && ((startOrientationWasLandscape && aspect < MIN_ASPECT)
                || (!startOrientationWasLandscape && aspect > (1.0 / MIN_ASPECT)))) {
            // Calculate 2 rectangles fulfilling all requirements for either X or Y being the major
            // drag axis. What ever is producing the bigger rectangle will be chosen.
            int width1;
            int width2;
            int height1;
            int height2;
            if (startOrientationWasLandscape) {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(minVisibleWidth, Math.min(maxVisibleSize.x, width));
                height1 = Math.min(height, Math.round((float) width1 / MIN_ASPECT));
                if (height1 < minVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = minVisibleHeight;
                    width1 = Math.max(minVisibleWidth,
                            Math.min(maxVisibleSize.x, Math.round((float) height1 * MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(minVisibleHeight, Math.min(maxVisibleSize.y, height));
                width2 = Math.max(width, Math.round((float) height2 * MIN_ASPECT));
                if (width2 < minVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = minVisibleWidth;
                    height2 = Math.max(minVisibleHeight,
                            Math.min(maxVisibleSize.y, Math.round((float) width2 / MIN_ASPECT)));
                }
            } else {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(minVisibleWidth, Math.min(maxVisibleSize.x, width));
                height1 = Math.max(height, Math.round((float) width1 * MIN_ASPECT));
                if (height1 < minVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = minVisibleHeight;
                    width1 = Math.max(minVisibleWidth,
                            Math.min(maxVisibleSize.x, Math.round((float) height1 / MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(minVisibleHeight, Math.min(maxVisibleSize.y, height));
                width2 = Math.min(width, Math.round((float) height2 / MIN_ASPECT));
                if (width2 < minVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = minVisibleWidth;
                    height2 = Math.max(minVisibleHeight,
                            Math.min(maxVisibleSize.y, Math.round((float) width2 * MIN_ASPECT)));
                }
            }

            // Use the bigger of the two rectangles if the major change was positive, otherwise
            // do the opposite.
            final boolean grows = width > (right - left) || height > (bottom - top);
            if (grows == (width1 * height1 > width2 * height2)) {
                width = width1;
                height = height1;
            } else {
                width = width2;
                height = height2;
            }
        }

        // Generate the final bounds by keeping the opposite drag edge constant.
        if ((ctrlType & CTRL_LEFT) != 0) {
            left = right - width;
        } else { // Note: The right might have changed - if we pulled at the right or not.
            right = left + width;
        }
        if ((ctrlType & CTRL_TOP) != 0) {
            top = bottom - height;
        } else { // Note: The height might have changed - if we pulled at the bottom or not.
            bottom = top + height;
        }
        return new Rect(left, top, right, bottom);
    }
}
