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
 * Utility functions to convert between color-spaces.
 *
 * Currently these methods are all CPU based native methods. These could be updated in the future
 * to provide other implementations.
 */
public class ColorSpace {

    /**
     * Convert YUV420-Planer data to RGBA8888.
     *
     * The input data is expected to be laid out in 3 planes. The width x height Y plane, followed
     * by the U and V planes, where each chroma value corresponds to a 2x2 luminance value block.
     * YUV to RGB conversion is done using the ITU-R BT.601 transformation. The output buffer must
     * be large enough to hold the data, and the dimensions must be multiples of 2.
     *
     * @param input data encoded in YUV420-Planar.
     * @param output buffer to hold RGBA8888 data.
     * @param width the width of the image (must be a multiple of 2)
     * @param height the height of the image (must be a multiple of 2)
     */
    public static void convertYuv420pToRgba8888(
            ByteBuffer input, ByteBuffer output, int width, int height) {
        expectInputSize(input, (3 * width * height) / 2);
        expectOutputSize(output, width * height * 4);
        nativeYuv420pToRgba8888(input, output, width, height);
    }

    /**
     * Convert ARGB8888 to RGBA8888.
     *
     * The input data is expected to be encoded in 8-bit interleaved ARGB channels. The output
     * buffer must be large enough to hold the data. The output buffer may be the same as the
     * input buffer.
     *
     * @param input data encoded in ARGB8888.
     * @param output buffer to hold RGBA8888 data.
     * @param width the width of the image
     * @param height the height of the image
     */
    public static void convertArgb8888ToRgba8888(
            ByteBuffer input, ByteBuffer output, int width, int height) {
        expectInputSize(input, width * height * 4);
        expectOutputSize(output, width * height * 4);
        nativeArgb8888ToRgba8888(input, output, width, height);
    }

    /**
     * Convert RGBA8888 to HSVA8888.
     *
     * The input data is expected to be encoded in 8-bit interleaved RGBA channels. The output
     * buffer must be large enough to hold the data. The output buffer may be the same as the
     * input buffer.
     *
     * @param input data encoded in RGBA8888.
     * @param output buffer to hold HSVA8888 data.
     * @param width the width of the image
     * @param height the height of the image
     */
    public static void convertRgba8888ToHsva8888(
            ByteBuffer input, ByteBuffer output, int width, int height) {
        expectInputSize(input, width * height * 4);
        expectOutputSize(output, width * height * 4);
        nativeRgba8888ToHsva8888(input, output, width, height);
    }

    /**
     * Convert RGBA8888 to YCbCrA8888.
     *
     * The input data is expected to be encoded in 8-bit interleaved RGBA channels. The output
     * buffer must be large enough to hold the data. The output buffer may be the same as the
     * input buffer.
     *
     * @param input data encoded in RGBA8888.
     * @param output buffer to hold YCbCrA8888 data.
     * @param width the width of the image
     * @param height the height of the image
     */
    public static void convertRgba8888ToYcbcra8888(
            ByteBuffer input, ByteBuffer output, int width, int height) {
        expectInputSize(input, width * height * 4);
        expectOutputSize(output, width * height * 4);
        nativeRgba8888ToYcbcra8888(input, output, width, height);
    }

    private static void expectInputSize(ByteBuffer input, int expectedSize) {
        if (input.remaining() < expectedSize) {
            throw new IllegalArgumentException("Input buffer's size does not fit given width "
                    + "and height! Expected: " + expectedSize + ", Got: " + input.remaining()
                    + ".");
        }
    }

    private static void expectOutputSize(ByteBuffer output, int expectedSize) {
        if (output.remaining() < expectedSize) {
            throw new IllegalArgumentException("Output buffer's size does not fit given width "
                    + "and height! Expected: " + expectedSize + ", Got: " + output.remaining()
                    + ".");
        }
    }

    private static native void nativeYuv420pToRgba8888(
            ByteBuffer input, ByteBuffer output, int width, int height);

    private static native void nativeArgb8888ToRgba8888(
            ByteBuffer input, ByteBuffer output, int width, int height);

    private static native void nativeRgba8888ToHsva8888(
            ByteBuffer input, ByteBuffer output, int width, int height);

    private static native void nativeRgba8888ToYcbcra8888(
            ByteBuffer input, ByteBuffer output, int width, int height);

    static {
        System.loadLibrary("smartcamera_jni");
    }

}
