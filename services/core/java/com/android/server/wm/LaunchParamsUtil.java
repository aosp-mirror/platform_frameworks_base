/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Size;
import android.view.View;

/**
 * The static class that defines some utility constants and functions that are shared among launch
 * params modifiers.
 */
class LaunchParamsUtil {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "LaunchParamsUtil" : TAG_ATM;
    private static final boolean DEBUG = false;

    // Screen size of Nexus 5x
    static final int DEFAULT_PORTRAIT_FREEFORM_WIDTH_DP = 412;
    static final int DEFAULT_PORTRAIT_FREEFORM_HEIGHT_DP = 732;

    // One of the most common tablet sizes that are small enough to fit in most large screens.
    private static final int DEFAULT_LANDSCAPE_FREEFORM_WIDTH_DP = 1064;
    private static final int DEFAULT_LANDSCAPE_FREEFORM_HEIGHT_DP = 600;

    private static final int DISPLAY_EDGE_OFFSET_DP = 27;

    private static final Rect TMP_STABLE_BOUNDS = new Rect();

    private LaunchParamsUtil() {}

    /**
     * Gets centered bounds of width x height. If inOutBounds is not empty, the result bounds
     * centers at its center or displayArea's app bounds center if inOutBounds is empty.
     */
    static void centerBounds(@NonNull TaskDisplayArea displayArea, int width, int height,
            @NonNull Rect inOutBounds) {
        if (inOutBounds.isEmpty()) {
            displayArea.getStableRect(inOutBounds);
        }
        final int left = inOutBounds.centerX() - width / 2;
        final int top = inOutBounds.centerY() - height / 2;
        inOutBounds.set(left, top, left + width, top + height);
    }

    /**
     * Calculate the default size for a freeform environment. |defaultSize| is used as the default
     * DP size, but if this is null, the portrait phone size is used.
     */
    static Size getDefaultFreeformSize(@NonNull ActivityRecord activityRecord,
                                       @NonNull TaskDisplayArea displayArea,
                                       @NonNull ActivityInfo.WindowLayout layout, int orientation,
                                       @NonNull Rect stableBounds) {
        // Get window size based on Nexus 5x screen, we assume that this is enough to show content
        // of activities.
        final float density = (float) displayArea.getConfiguration().densityDpi / DENSITY_DEFAULT;
        final int freeformWidthInDp = (orientation == SCREEN_ORIENTATION_LANDSCAPE)
                ? DEFAULT_LANDSCAPE_FREEFORM_WIDTH_DP : DEFAULT_PORTRAIT_FREEFORM_WIDTH_DP;
        final int freeformHeightInDp = (orientation == SCREEN_ORIENTATION_LANDSCAPE)
                ? DEFAULT_LANDSCAPE_FREEFORM_HEIGHT_DP : DEFAULT_PORTRAIT_FREEFORM_HEIGHT_DP;
        final int freeformWidth = (int) (freeformWidthInDp * density + 0.5f);
        final int freeformHeight = (int) (freeformHeightInDp * density + 0.5f);

        // Minimum layout requirements.
        final int layoutMinWidth = (layout == null) ? -1 : layout.minWidth;
        final int layoutMinHeight = (layout == null) ? -1 : layout.minHeight;

        // Max size, which is letterboxing/pillarboxing in displayArea. That's to say the large
        // dimension of default size is the small dimension of displayArea size, and the small
        // dimension of default size is calculated to keep the same aspect ratio as the
        // displayArea's. Here we use stable bounds of displayArea because that indicates the area
        // that isn't occupied by system widgets (e.g. sysbar and navbar).
        final int portraitHeight = Math.min(stableBounds.width(), stableBounds.height());
        final int otherDimension = Math.max(stableBounds.width(), stableBounds.height());
        final int portraitWidth = (portraitHeight * portraitHeight) / otherDimension;
        final int maxWidth = (orientation == SCREEN_ORIENTATION_LANDSCAPE) ? portraitHeight
                : portraitWidth;
        final int maxHeight = (orientation == SCREEN_ORIENTATION_LANDSCAPE) ? portraitWidth
                : portraitHeight;
        final int width = Math.min(maxWidth, Math.max(freeformWidth, layoutMinWidth));
        final int height = Math.min(maxHeight, Math.max(freeformHeight, layoutMinHeight));
        final float aspectRatio = (float) Math.max(width, height) / (float) Math.min(width, height);

        // Aspect ratio requirements.
        final float minAspectRatio = activityRecord.getMinAspectRatio();
        final float maxAspectRatio = activityRecord.info.getMaxAspectRatio();

        // Adjust the width and height to the aspect ratio requirements.
        int adjWidth = width;
        int adjHeight = height;
        if (minAspectRatio >= 1 && aspectRatio < minAspectRatio) {
            // The aspect ratio is below the minimum, adjust it to the minimum.
            if (orientation == SCREEN_ORIENTATION_LANDSCAPE) {
                // Fix the width, scale the height.
                adjHeight = (int) (adjWidth / minAspectRatio + 0.5f);
            } else {
                // Fix the height, scale the width.
                adjWidth = (int) (adjHeight / minAspectRatio + 0.5f);
            }
        } else if (maxAspectRatio >= 1 && aspectRatio > maxAspectRatio) {
            // The aspect ratio exceeds the maximum, adjust it to the maximum.
            if (orientation == SCREEN_ORIENTATION_LANDSCAPE) {
                // Fix the width, scale the height.
                adjHeight = (int) (adjWidth / maxAspectRatio + 0.5f);
            } else {
                // Fix the height, scale the width.
                adjWidth = (int) (adjHeight / maxAspectRatio + 0.5f);
            }
        }

        return new Size(adjWidth, adjHeight);
    }

    static void adjustBoundsToFitInDisplayArea(@NonNull TaskDisplayArea displayArea,
                                               int layoutDirection,
                                               @NonNull ActivityInfo.WindowLayout layout,
                                               @NonNull Rect inOutBounds) {
        // Give a small margin between the window bounds and the display bounds.
        final Rect stableBounds = TMP_STABLE_BOUNDS;
        displayArea.getStableRect(stableBounds);
        final float density = (float) displayArea.getConfiguration().densityDpi / DENSITY_DEFAULT;
        final int displayEdgeOffset = (int) (DISPLAY_EDGE_OFFSET_DP * density + 0.5f);
        stableBounds.inset(displayEdgeOffset, displayEdgeOffset);

        if (stableBounds.width() < inOutBounds.width()
                || stableBounds.height() < inOutBounds.height()) {
            final float heightShrinkRatio = stableBounds.width() / (float) inOutBounds.width();
            final float widthShrinkRatio =
                    stableBounds.height() / (float) inOutBounds.height();
            final float shrinkRatio = Math.min(heightShrinkRatio, widthShrinkRatio);
            // Minimum layout requirements.
            final int layoutMinWidth = (layout == null) ? -1 : layout.minWidth;
            final int layoutMinHeight = (layout == null) ? -1 : layout.minHeight;
            int adjustedWidth = Math.max(layoutMinWidth, (int) (inOutBounds.width() * shrinkRatio));
            int adjustedHeight = Math.max(layoutMinHeight,
                    (int) (inOutBounds.height() * shrinkRatio));
            if (stableBounds.width() < adjustedWidth
                    || stableBounds.height() < adjustedHeight) {
                // There is no way for us to fit the bounds in the displayArea without breaking min
                // size constraints. Set the min size to make visible as much content as possible.
                final int left = layoutDirection == View.LAYOUT_DIRECTION_RTL
                        ? stableBounds.right - adjustedWidth
                        : stableBounds.left;
                inOutBounds.set(left, stableBounds.top, left + adjustedWidth,
                        stableBounds.top + adjustedHeight);
                return;
            }
            inOutBounds.set(inOutBounds.left, inOutBounds.top,
                    inOutBounds.left + adjustedWidth, inOutBounds.top + adjustedHeight);
        }

        final int dx;
        if (inOutBounds.right > stableBounds.right) {
            // Right edge is out of displayArea.
            dx = stableBounds.right - inOutBounds.right;
        } else if (inOutBounds.left < stableBounds.left) {
            // Left edge is out of displayArea.
            dx = stableBounds.left - inOutBounds.left;
        } else {
            // Vertical edges are all in displayArea.
            dx = 0;
        }

        final int dy;
        if (inOutBounds.top < stableBounds.top) {
            // Top edge is out of displayArea.
            dy = stableBounds.top - inOutBounds.top;
        } else if (inOutBounds.bottom > stableBounds.bottom) {
            // Bottom edge is out of displayArea.
            dy = stableBounds.bottom - inOutBounds.bottom;
        } else {
            // Horizontal edges are all in displayArea.
            dy = 0;
        }
        inOutBounds.offset(dx, dy);
    }
}
