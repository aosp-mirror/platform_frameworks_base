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

package androidx.window.util;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.app.Activity;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Util class for both Sidecar and Extensions.
 */
public final class ExtensionHelper {

    private ExtensionHelper() {
        // Util class, no instances should be created.
    }

    /**
     * Rotates the input rectangle specified in default display orientation to the current display
     * rotation.
     */
    public static void rotateRectToDisplayRotation(int displayId, Rect inOutRect) {
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
     * Rotates the input rectangle within parent bounds for a given delta.
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

    /** Transforms rectangle from absolute coordinate space to the window coordinate space. */
    public static void transformToWindowSpaceRect(Activity activity, Rect inOutRect) {
        Rect windowRect = getWindowBounds(activity);
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
     * Gets the current window bounds in absolute coordinates.
     */
    @Nullable
    private static Rect getWindowBounds(@NonNull Activity activity) {
        return activity.getWindowManager().getCurrentWindowMetrics().getBounds();
    }

    /**
     * Checks if both dimensions of the given rect are zero at the same time.
     */
    public static boolean isZero(@NonNull Rect rect) {
        return rect.height() == 0 && rect.width() == 0;
    }
}
