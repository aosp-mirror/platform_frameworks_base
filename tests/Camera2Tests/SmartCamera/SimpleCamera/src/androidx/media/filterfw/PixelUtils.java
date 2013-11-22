/*
 * Copyright 2013 The Android Open Source Project
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
package androidx.media.filterfw;

import java.nio.ByteBuffer;

/**
 * A collection of utilities to deal with pixel operations on ByteBuffers.
 */
public class PixelUtils {

    /**
     * Copy pixels from one buffer to another, applying a transformation.
     *
     * <p>The transformation is specified by specifying the initial offset in the output buffer, the
     * stride (in pixels) between each pixel, and the stride (in pixels) between each row. The row
     * stride is measured as the number of pixels between the start of each row.</p>
     *
     * <p>Note that this method is native for efficiency reasons. It does NOT do any bounds checking
     * other than making sure the buffers are of sufficient size. This means that you can corrupt
     * memory if specifying incorrect stride values!</p>
     *
     * @param input The input buffer containing pixel data.
     * @param output The output buffer to hold the transformed pixel data.
     * @param width The width of the input image.
     * @param height The height of the input image.
     * @param offset The start offset in the output (in pixels)
     * @param pixStride The stride between each pixel (in pixels)
     * @param rowStride The stride between the start of each row (in pixels)
     */
    public static void copyPixels(ByteBuffer input,
            ByteBuffer output,
            int width,
            int height,
            int offset,
            int pixStride,
            int rowStride) {
        if (input.remaining() != output.remaining()) {
            throw new IllegalArgumentException("Input and output buffers must have the same size!");
        } else if (input.remaining() % 4 != 0) {
            throw new IllegalArgumentException("Input buffer size must be a multiple of 4!");
        } else if (output.remaining() % 4 != 0) {
            throw new IllegalArgumentException("Output buffer size must be a multiple of 4!");
        } else if ((width * height * 4) != input.remaining()) {
            throw new IllegalArgumentException(
                    "Input buffer size does not match given dimensions!");
        } else if ((width * height * 4) != output.remaining()) {
            throw new IllegalArgumentException(
                    "Output buffer size does not match given dimensions!");
        }
        nativeCopyPixels(input, output, width, height, offset, pixStride, rowStride);
    }

    private static native void nativeCopyPixels(ByteBuffer input,
            ByteBuffer output,
            int width,
            int height,
            int offset,
            int pixStride,
            int rowStride);

    static {
        System.loadLibrary("smartcamera_jni");
    }

}
