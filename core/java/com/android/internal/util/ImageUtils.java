/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;

/**
 * Utility class for image analysis and processing.
 *
 * @hide
 */
public class ImageUtils {

    // Amount (max is 255) that two channels can differ before the color is no longer "gray".
    private static final int TOLERANCE = 20;

    // Alpha amount for which values below are considered transparent.
    private static final int ALPHA_TOLERANCE = 50;

    // Size of the smaller bitmap we're actually going to scan.
    private static final int COMPACT_BITMAP_SIZE = 64; // pixels

    private int[] mTempBuffer;
    private Bitmap mTempCompactBitmap;
    private Canvas mTempCompactBitmapCanvas;
    private Paint mTempCompactBitmapPaint;
    private final Matrix mTempMatrix = new Matrix();

    /**
     * Checks whether a bitmap is grayscale. Grayscale here means "very close to a perfect
     * gray".
     *
     * Instead of scanning every pixel in the bitmap, we first resize the bitmap to no more than
     * COMPACT_BITMAP_SIZE^2 pixels using filtering. The hope is that any non-gray color elements
     * will survive the squeezing process, contaminating the result with color.
     */
    public boolean isGrayscale(Bitmap bitmap) {
        int height = bitmap.getHeight();
        int width = bitmap.getWidth();

        // shrink to a more manageable (yet hopefully no more or less colorful) size
        if (height > COMPACT_BITMAP_SIZE || width > COMPACT_BITMAP_SIZE) {
            if (mTempCompactBitmap == null) {
                mTempCompactBitmap = Bitmap.createBitmap(
                        COMPACT_BITMAP_SIZE, COMPACT_BITMAP_SIZE, Bitmap.Config.ARGB_8888
                );
                mTempCompactBitmapCanvas = new Canvas(mTempCompactBitmap);
                mTempCompactBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                mTempCompactBitmapPaint.setFilterBitmap(true);
            }
            mTempMatrix.reset();
            mTempMatrix.setScale(
                    (float) COMPACT_BITMAP_SIZE / width,
                    (float) COMPACT_BITMAP_SIZE / height,
                    0, 0);
            mTempCompactBitmapCanvas.drawColor(0, PorterDuff.Mode.SRC); // select all, erase
            mTempCompactBitmapCanvas.drawBitmap(bitmap, mTempMatrix, mTempCompactBitmapPaint);
            bitmap = mTempCompactBitmap;
            width = height = COMPACT_BITMAP_SIZE;
        }

        final int size = height*width;
        ensureBufferSize(size);
        bitmap.getPixels(mTempBuffer, 0, width, 0, 0, width, height);
        for (int i = 0; i < size; i++) {
            if (!isGrayscale(mTempBuffer[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Makes sure that {@code mTempBuffer} has at least length {@code size}.
     */
    private void ensureBufferSize(int size) {
        if (mTempBuffer == null || mTempBuffer.length < size) {
            mTempBuffer = new int[size];
        }
    }

    /**
     * Classifies a color as grayscale or not. Grayscale here means "very close to a perfect
     * gray"; if all three channels are approximately equal, this will return true.
     *
     * Note that really transparent colors are always grayscale.
     */
    public static boolean isGrayscale(int color) {
        int alpha = 0xFF & (color >> 24);
        if (alpha < ALPHA_TOLERANCE) {
            return true;
        }

        int r = 0xFF & (color >> 16);
        int g = 0xFF & (color >> 8);
        int b = 0xFF & color;

        return Math.abs(r - g) < TOLERANCE
                && Math.abs(r - b) < TOLERANCE
                && Math.abs(g - b) < TOLERANCE;
    }
}
