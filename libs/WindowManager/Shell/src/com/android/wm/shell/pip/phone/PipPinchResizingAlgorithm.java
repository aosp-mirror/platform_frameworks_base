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
package com.android.wm.shell.pip.phone;

import android.graphics.Point;
import android.graphics.Rect;

/**
 * Helper class to calculate the new size given two-fingers pinch to resize.
 */
public class PipPinchResizingAlgorithm {
    private static final Rect TMP_RECT = new Rect();
    /**
     * Given inputs and requirements and current PiP bounds, return the new size.
     *
     * @param x0 x-coordinate of the primary input.
     * @param y0 y-coordinate of the primary input.
     * @param x1 x-coordinate of the secondary input.
     * @param y1 y-coordinate of the secondary input.
     * @param downx0 x-coordinate of the original down point of the primary input.
     * @param downy0 y-coordinate of the original down ponit of the primary input.
     * @param downx1 x-coordinate of the original down point of the secondary input.
     * @param downy1 y-coordinate of the original down point of the secondary input.
     * @param currentPipBounds current PiP bounds.
     * @param minVisibleWidth minimum visible width.
     * @param minVisibleHeight minimum visible height.
     * @param maxSize max size.
     * @return The new resized PiP bounds, sharing the same center.
     */
    public static Rect pinchResize(float x0, float y0, float x1, float y1,
            float downx0, float downy0, float downx1, float downy1, Rect currentPipBounds,
            int minVisibleWidth, int minVisibleHeight, Point maxSize) {

        int width = currentPipBounds.width();
        int height = currentPipBounds.height();
        int left = currentPipBounds.left;
        int top = currentPipBounds.top;
        int right = currentPipBounds.right;
        int bottom = currentPipBounds.bottom;
        final float aspect = (float) width / (float) height;
        final int widthDelta = Math.round(Math.abs(x0 - x1) - Math.abs(downx0 - downx1));
        final int heightDelta = Math.round(Math.abs(y0 - y1) - Math.abs(downy0 - downy1));
        final int dx = (int) ((x0 - downx0 + x1 - downx1) / 2);
        final int dy = (int) ((y0 - downy0 + y1 - downy1) / 2);

        width = Math.max(minVisibleWidth, Math.min(width + widthDelta, maxSize.x));
        height = Math.max(minVisibleHeight, Math.min(height + heightDelta, maxSize.y));

        // Calculate 2 rectangles fulfilling all requirements for either X or Y being the major
        // drag axis. What ever is producing the bigger rectangle will be chosen.
        int width1;
        int width2;
        int height1;
        int height2;
        if (aspect > 1.0f) {
            // Assuming that the width is our target we calculate the height.
            width1 = Math.max(minVisibleWidth, Math.min(maxSize.x, width));
            height1 = Math.round((float) width1 / aspect);
            if (height1 < minVisibleHeight) {
                // If the resulting height is too small we adjust to the minimal size.
                height1 = minVisibleHeight;
                width1 = Math.max(minVisibleWidth,
                        Math.min(maxSize.x, Math.round((float) height1 * aspect)));
            }
            // Assuming that the height is our target we calculate the width.
            height2 = Math.max(minVisibleHeight, Math.min(maxSize.y, height));
            width2 = Math.round((float) height2 * aspect);
            if (width2 < minVisibleWidth) {
                // If the resulting width is too small we adjust to the minimal size.
                width2 = minVisibleWidth;
                height2 = Math.max(minVisibleHeight,
                        Math.min(maxSize.y, Math.round((float) width2 / aspect)));
            }
        } else {
            // Assuming that the width is our target we calculate the height.
            width1 = Math.max(minVisibleWidth, Math.min(maxSize.x, width));
            height1 = Math.round((float) width1 / aspect);
            if (height1 < minVisibleHeight) {
                // If the resulting height is too small we adjust to the minimal size.
                height1 = minVisibleHeight;
                width1 = Math.max(minVisibleWidth,
                        Math.min(maxSize.x, Math.round((float) height1 * aspect)));
            }
            // Assuming that the height is our target we calculate the width.
            height2 = Math.max(minVisibleHeight, Math.min(maxSize.y, height));
            width2 = Math.round((float) height2 * aspect);
            if (width2 < minVisibleWidth) {
                // If the resulting width is too small we adjust to the minimal size.
                width2 = minVisibleWidth;
                height2 = Math.max(minVisibleHeight,
                        Math.min(maxSize.y, Math.round((float) width2 / aspect)));
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

        TMP_RECT.set(currentPipBounds.centerX() - width / 2,
                currentPipBounds.centerY() - height / 2,
                currentPipBounds.centerX() + width / 2,
                currentPipBounds.centerY() + height / 2);
        TMP_RECT.offset(dx, dy);
        return TMP_RECT;
    }
}
