/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.util.RotationUtils;
import android.view.DisplayCutout;
import android.view.Surface;

import com.android.internal.R;

/**
 * Utility functions for system bars used by both window manager and System UI.
 *
 * @hide
 */
public final class SystemBarUtils {

    /**
     * Gets the status bar height.
     */
    public static int getStatusBarHeight(Context context) {
        return getStatusBarHeight(context.getResources(), context.getDisplay().getCutout());
    }

    /**
     * Gets the status bar height with a specific display cutout.
     */
    public static int getStatusBarHeight(Resources res, DisplayCutout cutout) {
        final int defaultSize = res.getDimensionPixelSize(R.dimen.status_bar_height_default);
        final int safeInsetTop = cutout == null ? 0 : cutout.getSafeInsetTop();
        final int waterfallInsetTop = cutout == null ? 0 : cutout.getWaterfallInsets().top;
        // The status bar height should be:
        // Max(top cutout size, (status bar default height + waterfall top size))
        return Math.max(safeInsetTop, defaultSize + waterfallInsetTop);
    }

    /**
     * Gets the status bar height for a specific rotation.
     */
    public static int getStatusBarHeightForRotation(
            Context context, @Surface.Rotation int targetRot) {
        final int rotation = context.getDisplay().getRotation();
        final DisplayCutout cutout = context.getDisplay().getCutout();

        Insets insets = cutout == null ? Insets.NONE : Insets.of(cutout.getSafeInsets());
        Insets waterfallInsets = cutout == null ? Insets.NONE : cutout.getWaterfallInsets();
        // rotate insets to target rotation if needed.
        if (rotation != targetRot) {
            if (!insets.equals(Insets.NONE)) {
                insets = RotationUtils.rotateInsets(
                        insets, RotationUtils.deltaRotation(rotation, targetRot));
            }
            if (!waterfallInsets.equals(Insets.NONE)) {
                waterfallInsets = RotationUtils.rotateInsets(
                        waterfallInsets, RotationUtils.deltaRotation(rotation, targetRot));
            }
        }
        final int defaultSize =
                context.getResources().getDimensionPixelSize(R.dimen.status_bar_height);
        // The status bar height should be:
        // Max(top cutout size, (status bar default height + waterfall top size))
        return Math.max(insets.top, defaultSize + waterfallInsets.top);
    }

    /**
     * Gets the height of area above QQS where battery/time go in notification panel. The height
     * equals to status bar height if status bar height is bigger than the
     * {@link R.dimen#quick_qs_offset_height}.
     */
    public static int getQuickQsOffsetHeight(Context context) {
        final int defaultSize = context.getResources().getDimensionPixelSize(
                R.dimen.quick_qs_offset_height);
        final int statusBarHeight = getStatusBarHeight(context);
        // Equals to status bar height if status bar height is bigger.
        return Math.max(defaultSize, statusBarHeight);
    }
}
