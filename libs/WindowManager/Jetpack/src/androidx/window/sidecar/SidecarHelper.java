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

package androidx.window.sidecar;

import static android.view.Display.INVALID_DISPLAY;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.app.Activity;
import android.app.ActivityThread;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.annotation.Nullable;

class SidecarHelper {
    /**
     * Rotate the input rectangle specified in default display orientation to the current display
     * rotation.
     */
    static void rotateRectToDisplayRotation(Rect inOutRect, int displayId) {
        DisplayManagerGlobal dmGlobal = DisplayManagerGlobal.getInstance();
        DisplayInfo displayInfo = dmGlobal.getDisplayInfo(displayId);
        int rotation = displayInfo.rotation;

        boolean isSideRotation = rotation == ROTATION_90 || rotation == ROTATION_270;
        int displayWidth = isSideRotation ? displayInfo.logicalHeight : displayInfo.logicalWidth;
        int displayHeight = isSideRotation ? displayInfo.logicalWidth : displayInfo.logicalHeight;

        inOutRect.intersect(0, 0, displayWidth, displayHeight);

        rotateBounds(inOutRect, displayWidth, displayHeight, rotation);
    }

    /**
     * Rotate the input rectangle within parent bounds for a given delta.
     */
    private static void rotateBounds(Rect inOutRect, int parentWidth, int parentHeight,
            @Surface.Rotation int delta) {
        int origLeft = inOutRect.left;
        switch (delta) {
            case ROTATION_0:
                return;
            case ROTATION_90:
                inOutRect.left = inOutRect.top;
                inOutRect.top = parentWidth - inOutRect.right;
                inOutRect.right = inOutRect.bottom;
                inOutRect.bottom = parentWidth - origLeft;
                return;
            case ROTATION_180:
                inOutRect.left = parentWidth - inOutRect.right;
                inOutRect.right = parentWidth - origLeft;
                return;
            case ROTATION_270:
                inOutRect.left = parentHeight - inOutRect.bottom;
                inOutRect.bottom = inOutRect.right;
                inOutRect.right = parentHeight - inOutRect.top;
                inOutRect.top = origLeft;
                return;
        }
    }

    /** Transform rectangle from absolute coordinate space to the window coordinate space. */
    static void transformToWindowSpaceRect(Rect inOutRect, IBinder windowToken) {
        Rect windowRect = getWindowBounds(windowToken);
        if (windowRect == null) {
            inOutRect.setEmpty();
            return;
        }
        if (!Rect.intersects(inOutRect, windowRect)) {
            inOutRect.setEmpty();
            return;
        }
        inOutRect.intersect(windowRect);
        inOutRect.offset(-windowRect.left, -windowRect.top);
    }

    /**
     * Get the current window bounds in absolute coordinates.
     * NOTE: Only works with Activity windows.
     */
    @Nullable
    private static Rect getWindowBounds(IBinder windowToken) {
        Activity activity = ActivityThread.currentActivityThread().getActivity(windowToken);
        return activity != null
                ? activity.getWindowManager().getCurrentWindowMetrics().getBounds()
                : null;
    }

    /**
     * Check if this window is an Activity window that is in multi-window mode.
     */
    static boolean isInMultiWindow(IBinder windowToken) {
        Activity activity = ActivityThread.currentActivityThread().getActivity(windowToken);
        return activity != null && activity.isInMultiWindowMode();
    }

    /**
     * Get the id of the parent display for the window.
     * NOTE: Only works with Activity windows.
     */
    static int getWindowDisplay(IBinder windowToken) {
        Activity activity = ActivityThread.currentActivityThread().getActivity(windowToken);
        return activity != null
                ? activity.getWindowManager().getDefaultDisplay().getDisplayId() : INVALID_DISPLAY;
    }
}
