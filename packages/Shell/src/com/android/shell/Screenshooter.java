/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.shell;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;

/**
 * Helper class used to take screenshots.
 *
 * TODO: logic below was copied and pasted from UiAutomation; it should be refactored into a common
 * component that could be used by both (Shell and UiAutomation).
 */
final class Screenshooter {

    private static final String TAG = "Screenshooter";

    /**
     * Takes a screenshot.
     *
     * @return The screenshot bitmap on success, null otherwise.
     */
    static Bitmap takeScreenshot() {
        Display display = DisplayManagerGlobal.getInstance()
                .getRealDisplay(Display.DEFAULT_DISPLAY);
        Point displaySize = new Point();
        display.getRealSize(displaySize);
        final int displayWidth = displaySize.x;
        final int displayHeight = displaySize.y;

        int rotation = display.getRotation();
        Rect crop = new Rect(0, 0, displayWidth, displayHeight);
        Log.d(TAG, "Taking screenshot of dimensions " + displayWidth + " x " + displayHeight);
        // Take the screenshot
        Bitmap screenShot =
                SurfaceControl.screenshot(crop, displayWidth, displayHeight, rotation);
        if (screenShot == null) {
            Log.e(TAG, "Failed to take screenshot of dimensions " + displayWidth + " x "
                    + displayHeight);
            return null;
        }

        // Optimization
        screenShot.setHasAlpha(false);

        return screenShot;
    }
}
