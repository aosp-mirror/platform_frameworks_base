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
import android.graphics.Canvas;
import android.graphics.Point;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

/**
 * Helper class used to take screenshots.
 *
 * TODO: logic below was copied and pasted from UiAutomation; it should be refactored into a common
 * component that could be used by both (Shell and UiAutomation).
 */
final class Screenshooter {

    private static final String TAG = "Screenshooter";

    /** Rotation constant: Freeze rotation to 0 degrees (natural orientation) */
    public static final int ROTATION_FREEZE_0 = Surface.ROTATION_0;

    /** Rotation constant: Freeze rotation to 90 degrees . */
    public static final int ROTATION_FREEZE_90 = Surface.ROTATION_90;

    /** Rotation constant: Freeze rotation to 180 degrees . */
    public static final int ROTATION_FREEZE_180 = Surface.ROTATION_180;

    /** Rotation constant: Freeze rotation to 270 degrees . */
    public static final int ROTATION_FREEZE_270 = Surface.ROTATION_270;

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

        final float screenshotWidth;
        final float screenshotHeight;

        final int rotation = display.getRotation();
        switch (rotation) {
            case ROTATION_FREEZE_0: {
                screenshotWidth = displayWidth;
                screenshotHeight = displayHeight;
            } break;
            case ROTATION_FREEZE_90: {
                screenshotWidth = displayHeight;
                screenshotHeight = displayWidth;
            } break;
            case ROTATION_FREEZE_180: {
                screenshotWidth = displayWidth;
                screenshotHeight = displayHeight;
            } break;
            case ROTATION_FREEZE_270: {
                screenshotWidth = displayHeight;
                screenshotHeight = displayWidth;
            } break;
            default: {
                throw new IllegalArgumentException("Invalid rotation: "
                        + rotation);
            }
        }

        Log.d(TAG, "Taking screenshot of dimensions " + displayWidth + " x " + displayHeight);
        // Take the screenshot
        Bitmap screenShot =
                SurfaceControl.screenshot((int) screenshotWidth, (int) screenshotHeight);
        if (screenShot == null) {
            Log.e(TAG, "Failed to take screenshot of dimensions " + screenshotWidth + " x "
                    + screenshotHeight);
            return null;
        }

        // Rotate the screenshot to the current orientation
        if (rotation != ROTATION_FREEZE_0) {
            Bitmap unrotatedScreenShot = Bitmap.createBitmap(displayWidth, displayHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(unrotatedScreenShot);
            canvas.translate(unrotatedScreenShot.getWidth() / 2,
                    unrotatedScreenShot.getHeight() / 2);
            canvas.rotate(getDegreesForRotation(rotation));
            canvas.translate(- screenshotWidth / 2, - screenshotHeight / 2);
            canvas.drawBitmap(screenShot, 0, 0, null);
            canvas.setBitmap(null);
            screenShot.recycle();
            screenShot = unrotatedScreenShot;
        }

        // Optimization
        screenShot.setHasAlpha(false);

        return screenShot;
    }

    private static float getDegreesForRotation(int value) {
        switch (value) {
            case Surface.ROTATION_90: {
                return 360f - 90f;
            }
            case Surface.ROTATION_180: {
                return 360f - 180f;
            }
            case Surface.ROTATION_270: {
                return 360f - 270f;
            } default: {
                return 0;
            }
        }
    }

}
