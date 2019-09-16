/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

/**
 * Helper functions to retrieve system window sizes and positions.
 */
public class WindowUtils {

    public static Rect getDisplayBounds() {
        Point display = new Point();
        WindowManager wm =
                (WindowManager) InstrumentationRegistry.getContext().getSystemService(
                        Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealSize(display);
        return new Rect(0, 0, display.x, display.y);
    }

    private static int getCurrentRotation() {
        WindowManager wm =
                (WindowManager) InstrumentationRegistry.getContext().getSystemService(
                        Context.WINDOW_SERVICE);
        return wm.getDefaultDisplay().getRotation();
    }

    public static Rect getDisplayBounds(int requestedRotation) {
        Rect displayBounds = getDisplayBounds();
        int currentDisplayRotation = getCurrentRotation();

        boolean displayIsRotated = (currentDisplayRotation == Surface.ROTATION_90 ||
                currentDisplayRotation == Surface.ROTATION_270);

        boolean requestedDisplayIsRotated = requestedRotation == Surface.ROTATION_90 ||
                requestedRotation == Surface.ROTATION_270;

        // if the current orientation changes with the requested rotation,
        // flip height and width of display bounds.
        if (displayIsRotated != requestedDisplayIsRotated) {
            return new Rect(0, 0, displayBounds.height(), displayBounds.width());
        }

        return new Rect(0, 0, displayBounds.width(), displayBounds.height());
    }


    public static Rect getAppPosition(int requestedRotation) {
        Rect displayBounds = getDisplayBounds();
        int currentDisplayRotation = getCurrentRotation();

        boolean displayIsRotated = currentDisplayRotation == Surface.ROTATION_90 ||
                currentDisplayRotation == Surface.ROTATION_270;

        boolean requestedAppIsRotated = requestedRotation == Surface.ROTATION_90 ||
                requestedRotation == Surface.ROTATION_270;

        // display size will change if the display is reflected. Flip height and width of app if the
        // requested rotation is different from the current rotation.
        if (displayIsRotated != requestedAppIsRotated) {
            return new Rect(0, 0, displayBounds.height(), displayBounds.width());
        }

        return new Rect(0, 0, displayBounds.width(), displayBounds.height());
    }

    public static Rect getStatusBarPosition(int requestedRotation) {
        Resources resources = InstrumentationRegistry.getContext().getResources();
        String resourceName;
        Rect displayBounds = getDisplayBounds();
        int width;
        if (requestedRotation == Surface.ROTATION_0 || requestedRotation == Surface.ROTATION_180) {
            resourceName = "status_bar_height_portrait";
            width = Math.min(displayBounds.width(), displayBounds.height());
        } else {
            resourceName = "status_bar_height_landscape";
            width = Math.max(displayBounds.width(), displayBounds.height());
        }

        int resourceId = resources.getIdentifier(resourceName, "dimen", "android");
        int height = resources.getDimensionPixelSize(resourceId);

        return new Rect(0, 0, width, height);
    }

    public static Rect getNavigationBarPosition(int requestedRotation) {
        Resources resources = InstrumentationRegistry.getContext().getResources();
        Rect displayBounds = getDisplayBounds();
        int displayWidth = Math.min(displayBounds.width(), displayBounds.height());
        int displayHeight = Math.max(displayBounds.width(), displayBounds.height());
        int resourceId;
        if (requestedRotation == Surface.ROTATION_0 || requestedRotation == Surface.ROTATION_180) {
            resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
            int height = resources.getDimensionPixelSize(resourceId);
            return new Rect(0, displayHeight - height, displayWidth, displayHeight);
        } else {
            resourceId = resources.getIdentifier("navigation_bar_width", "dimen", "android");
            int width = resources.getDimensionPixelSize(resourceId);
            // swap display dimensions in landscape or seascape mode
            int temp = displayHeight;
            displayHeight = displayWidth;
            displayWidth = temp;
            if (requestedRotation == Surface.ROTATION_90) {
                return new Rect(0, 0, width, displayHeight);
            } else {
                return new Rect(displayWidth - width, 0, displayWidth, displayHeight);
            }
        }
    }

    public static int getNavigationBarHeight() {
        Resources resources = InstrumentationRegistry.getContext().getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        return resources.getDimensionPixelSize(resourceId);
    }

    public static int getDockedStackDividerInset() {
        Resources resources = InstrumentationRegistry.getContext().getResources();
        int resourceId = resources.getIdentifier("docked_stack_divider_insets", "dimen",
                "android");
        return resources.getDimensionPixelSize(resourceId);
    }
}
