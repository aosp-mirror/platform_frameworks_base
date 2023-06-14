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

import static android.view.Display.DEFAULT_DISPLAY;

import android.graphics.Bitmap;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;
import android.window.ScreenCapture.SynchronousScreenCaptureListener;

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
        Log.d(TAG, "Taking fullscreen screenshot");
        // Take the screenshot
        final SynchronousScreenCaptureListener syncScreenCapture =
                ScreenCapture.createSyncCaptureListener();
        try {
            WindowManagerGlobal.getWindowManagerService().captureDisplay(DEFAULT_DISPLAY, null,
                    syncScreenCapture);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        final ScreenshotHardwareBuffer screenshotBuffer = syncScreenCapture.getBuffer();
        final Bitmap screenShot = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();
        if (screenShot == null) {
            Log.e(TAG, "Failed to take fullscreen screenshot");
            return null;
        }

        // Optimization
        screenShot.setHasAlpha(false);

        return screenShot;
    }
}
