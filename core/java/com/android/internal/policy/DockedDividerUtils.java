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

import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

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
        sanitizeStackBounds(outRect);
    }

    public static void sanitizeStackBounds(Rect bounds) {
        if (bounds.left >= bounds.right) {
            bounds.left = bounds.right - 1;
        }
        if (bounds.top >= bounds.bottom) {
            bounds.top = bounds.bottom - 1;
        }
        if (bounds.right <= bounds.left) {
            bounds.right = bounds.left + 1;
        }
        if (bounds.bottom <= bounds.top) {
            bounds.bottom = bounds.top + 1;
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

    public static int calculateMiddlePosition(boolean isHorizontalDivision, Rect insets,
            int displayWidth, int displayHeight, int dividerSize) {
        int start = isHorizontalDivision ? insets.top : insets.left;
        int end = isHorizontalDivision
                ? displayHeight - insets.bottom
                : displayWidth - insets.right;
        return start + (end - start) / 2 - dividerSize / 2;
    }

    public static int getDockSideFromCreatedMode(boolean dockOnTopOrLeft,
            boolean isHorizontalDivision) {
        if (dockOnTopOrLeft) {
            if (isHorizontalDivision) {
                return DOCKED_TOP;
            } else {
                return DOCKED_LEFT;
            }
        } else {
            if (isHorizontalDivision) {
                return DOCKED_BOTTOM;
            } else {
                return DOCKED_RIGHT;
            }
        }
    }

    public static int invertDockSide(int dockSide) {
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                return WindowManager.DOCKED_RIGHT;
            case WindowManager.DOCKED_TOP:
                return WindowManager.DOCKED_BOTTOM;
            case WindowManager.DOCKED_RIGHT:
                return WindowManager.DOCKED_LEFT;
            case WindowManager.DOCKED_BOTTOM:
                return WindowManager.DOCKED_TOP;
            default:
                return WindowManager.DOCKED_INVALID;
        }
    }
}
