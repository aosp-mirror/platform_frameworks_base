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

    private int[] mTempBuffer;

    /**
     * Checks whether a bitmap is grayscale. Grayscale here means "very close to a perfect
     * gray".
     */
    public boolean isGrayscale(Bitmap bitmap) {
        final int height = bitmap.getHeight();
        final int width = bitmap.getWidth();
        int size = height*width;

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
