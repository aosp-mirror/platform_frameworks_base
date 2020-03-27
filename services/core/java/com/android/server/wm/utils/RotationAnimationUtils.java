/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm.utils;

import static android.hardware.HardwareBuffer.RGBA_8888;

import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import java.nio.ByteBuffer;
import java.util.Arrays;


/** Helper functions for the {@link com.android.server.wm.ScreenRotationAnimation} class*/
public class RotationAnimationUtils {

    /**
     * Converts the provided {@link HardwareBuffer} and converts it to a bitmap to then sample the
     * luminance at the borders of the bitmap
     * @return the average luminance of all the pixels at the borders of the bitmap
     */
    public static float getMedianBorderLuma(HardwareBuffer hardwareBuffer, ColorSpace colorSpace) {
        if (hardwareBuffer == null || hardwareBuffer.getFormat() != RGBA_8888) {
            return 0;
        }

        ImageReader ir = ImageReader.newInstance(hardwareBuffer.getWidth(),
                hardwareBuffer.getHeight(), hardwareBuffer.getFormat(), 1);
        ir.getSurface().attachAndQueueBufferWithColorSpace(hardwareBuffer, colorSpace);
        Image image = ir.acquireLatestImage();
        if (image == null || image.getPlanes().length == 0) {
            return 0;
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        float[] borderLumas = new float[2 * width + 2 * height];

        // Grab the top and bottom borders
        int l = 0;
        for (int x = 0; x < width; x++) {
            borderLumas[l++] = getPixelLuminance(buffer, x, 0, pixelStride, rowStride);
            borderLumas[l++] = getPixelLuminance(buffer, x, height - 1, pixelStride, rowStride);
        }

        // Grab the left and right borders
        for (int y = 0; y < height; y++) {
            borderLumas[l++] = getPixelLuminance(buffer, 0, y, pixelStride, rowStride);
            borderLumas[l++] = getPixelLuminance(buffer, width - 1, y, pixelStride, rowStride);
        }

        // Cleanup
        ir.close();

        // Oh, is this too simple and inefficient for you?
        // How about implementing a O(n) solution? https://en.wikipedia.org/wiki/Median_of_medians
        Arrays.sort(borderLumas);
        return borderLumas[borderLumas.length / 2];
    }

    private static float getPixelLuminance(ByteBuffer buffer, int x, int y,
            int pixelStride, int rowStride) {
        int offset = y * rowStride + x * pixelStride;
        int pixel = 0;
        pixel |= (buffer.get(offset) & 0xff) << 16;     // R
        pixel |= (buffer.get(offset + 1) & 0xff) << 8;  // G
        pixel |= (buffer.get(offset + 2) & 0xff);       // B
        pixel |= (buffer.get(offset + 3) & 0xff) << 24; // A
        return Color.valueOf(pixel).luminance();
    }

    /**
     * Gets the average border luma by taking a screenshot of the {@param surfaceControl}.
     * @see #getMedianBorderLuma(HardwareBuffer, ColorSpace)
     */
    public static float getLumaOfSurfaceControl(Display display, SurfaceControl surfaceControl) {
        if (surfaceControl ==  null) {
            return 0;
        }

        Point size = new Point();
        display.getSize(size);
        Rect crop = new Rect(0, 0, size.x, size.y);
        SurfaceControl.ScreenshotHardwareBuffer buffer =
                SurfaceControl.captureLayers(surfaceControl, crop, 1);
        if (buffer == null) {
            return 0;
        }

        return RotationAnimationUtils.getMedianBorderLuma(buffer.getHardwareBuffer(),
                buffer.getColorSpace());
    }

    public static void createRotationMatrix(int rotation, int width, int height, Matrix outMatrix) {
        switch (rotation) {
            case Surface.ROTATION_0:
                outMatrix.reset();
                break;
            case Surface.ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(height, 0);
                break;
            case Surface.ROTATION_180:
                outMatrix.setRotate(180, 0, 0);
                outMatrix.postTranslate(width, height);
                break;
            case Surface.ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, width);
                break;
        }
    }
}
