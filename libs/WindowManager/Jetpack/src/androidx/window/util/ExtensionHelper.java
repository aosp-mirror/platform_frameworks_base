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

import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.SuppressLint;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.util.RotationUtils;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.UiContext;
import androidx.annotation.VisibleForTesting;

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
     *
     * @param displayId the display id.
     * @param rotation the target rotation relative to the default display orientation.
     * @param inOutRect the input/output Rect as specified in the default display orientation.
     */
    public static void rotateRectToDisplayRotation(
            int displayId, @Surface.Rotation int rotation, @NonNull Rect inOutRect) {
        final DisplayManagerGlobal dmGlobal = DisplayManagerGlobal.getInstance();
        final DisplayInfo displayInfo = dmGlobal.getDisplayInfo(displayId);

        rotateRectToDisplayRotation(displayInfo, rotation, inOutRect);
    }

    // We suppress the Lint error CheckResult for Rect#intersect because in case the displayInfo and
    // folding features are out of sync, e.g. when a foldable devices is unfolding, it is acceptable
    // to provide the original folding feature Rect even if they don't intersect.
    @SuppressLint("RectIntersectReturnValueIgnored")
    @VisibleForTesting
    static void rotateRectToDisplayRotation(@NonNull DisplayInfo displayInfo,
            @Surface.Rotation int rotation, @NonNull Rect inOutRect) {
        // The inOutRect is specified in the default display orientation, so here we need to get
        // the display width and height in the default orientation to perform the intersection and
        // rotation.
        final boolean isSideRotation =
                displayInfo.rotation == ROTATION_90 || displayInfo.rotation == ROTATION_270;
        final int baseDisplayWidth =
                isSideRotation ? displayInfo.logicalHeight : displayInfo.logicalWidth;
        final int baseDisplayHeight =
                isSideRotation ? displayInfo.logicalWidth : displayInfo.logicalHeight;

        inOutRect.intersect(0, 0, baseDisplayWidth, baseDisplayHeight);

        RotationUtils.rotateBounds(inOutRect, baseDisplayWidth, baseDisplayHeight, rotation);
    }

    /** Transforms rectangle from absolute coordinate space to the window coordinate space. */
    public static void transformToWindowSpaceRect(@NonNull @UiContext Context context,
            Rect inOutRect) {
        transformToWindowSpaceRect(getWindowBounds(context), inOutRect);
    }

    /** @see ExtensionHelper#transformToWindowSpaceRect(Context, Rect) */
    public static void transformToWindowSpaceRect(@NonNull WindowConfiguration windowConfiguration,
            Rect inOutRect) {
        transformToWindowSpaceRect(windowConfiguration.getBounds(), inOutRect);
    }

    private static void transformToWindowSpaceRect(@NonNull Rect bounds, @NonNull Rect inOutRect) {
        if (!inOutRect.intersect(bounds)) {
            inOutRect.setEmpty();
            return;
        }
        inOutRect.offset(-bounds.left, -bounds.top);
    }

    /**
     * Gets the current window bounds in absolute coordinates.
     */
    @NonNull
    private static Rect getWindowBounds(@NonNull @UiContext Context context) {
        return context.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
    }

    /**
     * Checks if both dimensions of the given rect are zero at the same time.
     */
    public static boolean isZero(@NonNull Rect rect) {
        return rect.height() == 0 && rect.width() == 0;
    }
}
