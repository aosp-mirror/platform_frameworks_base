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

package com.android.internal.policy;

import android.graphics.Rect;
import android.view.WindowManager;

/**
 * Utility functions for docked stack divider used by both window manager and System UI.
 *
 * @hide
 */
public class DockedDividerUtils {

    public static void calculateBoundsForPosition(int position, int dockSide, Rect outRect,
            int displayWidth, int displayHeight, int dividerSize) {
        outRect.set(0, 0, displayWidth, displayHeight);
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                outRect.right = position;
                break;
            case WindowManager.DOCKED_TOP:
                outRect.bottom = position;
                break;
            case WindowManager.DOCKED_RIGHT:
                outRect.left = position + dividerSize;
                break;
            case WindowManager.DOCKED_BOTTOM:
                outRect.top = position + dividerSize;
                break;
        }
        if (outRect.left > outRect.right) {
            outRect.left = outRect.right;
        }
        if (outRect.top > outRect.bottom) {
            outRect.top = outRect.bottom;
        }
        if (outRect.right < outRect.left) {
            outRect.right = outRect.left;
        }
        if (outRect.bottom < outRect.top) {
            outRect.bottom = outRect.top;
        }
    }

    public static int calculatePositionForBounds(Rect bounds, int dockSide, int dividerSize) {
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                return bounds.right;
            case WindowManager.DOCKED_TOP:
                return bounds.bottom;
            case WindowManager.DOCKED_RIGHT:
                return bounds.left - dividerSize;
            case WindowManager.DOCKED_BOTTOM:
                return bounds.top - dividerSize;
            default:
                return 0;
        }
    }
}
