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

package com.android.wm.shell.common;

import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.SurfaceControl;

/**
 * Helpers for working with screenshots.
 */
public class ScreenshotUtils {

    /**
     * Take a screenshot of the specified SurfaceControl.
     *
     * @param t the transaction used to set changes on the resulting screenshot.
     * @param sc the SurfaceControl to take a screenshot of
     * @param crop the crop to use when capturing the screenshot
     * @param layer the layer to place the screenshot
     *
     * @return A SurfaceControl where the screenshot will be attached, or null if failed.
     */
    public static SurfaceControl takeScreenshot(SurfaceControl.Transaction t, SurfaceControl sc,
            Rect crop, int layer) {
        final SurfaceControl.ScreenshotHardwareBuffer buffer = SurfaceControl.captureLayers(
                new SurfaceControl.LayerCaptureArgs.Builder(sc)
                        .setSourceCrop(crop)
                        .setCaptureSecureLayers(true)
                        .setAllowProtected(true)
                        .build()
        );
        if (buffer == null || buffer.getHardwareBuffer() == null) {
            return null;
        }
        final GraphicBuffer graphicBuffer = GraphicBuffer.createFromHardwareBuffer(
                buffer.getHardwareBuffer());
        final SurfaceControl screenshot = new SurfaceControl.Builder()
                .setName("ScreenshotUtils screenshot")
                .setFormat(PixelFormat.TRANSLUCENT)
                .setSecure(buffer.containsSecureLayers())
                .setCallsite("ScreenshotUtils.takeScreenshot")
                .setBLASTLayer()
                .build();

        t.setBuffer(screenshot, graphicBuffer);
        t.setColorSpace(screenshot, buffer.getColorSpace());
        t.reparent(screenshot, sc);
        t.setLayer(screenshot, layer);
        t.show(screenshot);
        t.apply();
        return screenshot;
    }
}
