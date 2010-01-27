/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import java.io.OutputStream;

/**
 * @hide pending API council approval
 *
 * YuvImage contains YUV data and provides a method that compresses a region of
 * the YUV data to a Jpeg. The YUV data should be provided as a single byte
 * array irrespective of the number of image planes in it. The stride of each
 * image plane should be provided as well.
 *
 * To compress a rectangle region in the YUV data, users have to specify a
 * region by width, height and offsets, where each image plane has a
 * corresponding offset. All offsets are measured as a displacement in bytes
 * from yuv[0], where yuv[0] is the beginning of the yuv data.
 */
public class YuvImage {

    /**
     * Number of bytes of temp storage we use for communicating between the
     * native compressor and the java OutputStream.
     */
    private final static int WORKING_COMPRESS_STORAGE = 4096;

   /**
     * The YUV format as defined in {@link PixelFormat}.
     */
    private int mFormat;

    /**
     * The raw YUV data.
     * In the case of more than one image plane, the image planes must be
     * concatenated into a single byte array.
     */
    private byte[] mData;

    /**
     * The number of row bytes in each image plane.
     */
    private int[] mStrides;

    /**
     * Construct an YuvImage.
     *
     * @param yuv The YUV data. In the case of more than one image plane, all the planes must be
     *            concatenated into a single byte array.
     * @param format The YUV data format as defined in {@link PixelFormat}.
     * @param strides Row bytes of each image plane.
     */
    public YuvImage(byte[] yuv, int format, int[] strides) {
        if ((yuv == null) || (strides == null)) {
            throw new IllegalArgumentException(
                    "yuv or strides cannot be null");
        }
        mData = yuv;
        mFormat = format;
        mStrides = strides;
    }

    /**
     * Compress a rectangle region in the YuvImage to a jpeg.
     * Only PixelFormat.YCbCr_420_SP and PixelFormat.YCbCr_422_I
     * are supported for now.
     *
     * @param width The width of the rectangle region.
     * @param height The height of the rectangle region.
     * @param offsets The offsets of the rectangle region in each image plane.
     *                The offsets are measured as a displacement in bytes from
     *                yuv[0], where yuv[0] is the beginning of the yuv data.
     * @param quality  Hint to the compressor, 0-100. 0 meaning compress for
     *                 small size, 100 meaning compress for max quality.
     * @param stream   The outputstream to write the compressed data.
     *
     * @return true if successfully compressed to the specified stream.
     *
     */
    public boolean compressToJpeg(int width, int height, int[] offsets, int quality,
            OutputStream stream) {
        if (!validate(mFormat, width, height, offsets)) {
            return false;
        }

        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }

        if (stream == null) {
            throw new NullPointerException();
        }

        return nativeCompressToJpeg(mData, mFormat, width, height, offsets,
                mStrides, quality, stream, new byte[WORKING_COMPRESS_STORAGE]);
    }

    /**
     * @return the YUV data.
     */
    public byte[] getYuvData() {
        return mData;
    }

    /**
     * @return the YUV format as defined in {@link PixelFormat}.
     */
    public int getYuvFormat() {
        return mFormat;
    }

    /**
     * @return the number of row bytes in each image plane.
     */
    public int[] getStrides() {
        return mStrides;
    }

    protected boolean validate(int format, int width, int height, int[] offsets) {
        if (format != PixelFormat.YCbCr_420_SP &&
                format != PixelFormat.YCbCr_422_I) {
            throw new IllegalArgumentException(
                    "only support PixelFormat.YCbCr_420_SP " +
                    "and PixelFormat.YCbCr_422_I for now");
        }

        if (offsets.length != mStrides.length) {
            throw new IllegalArgumentException(
                    "the number of image planes are mismatched");
        }

        if (width <= 0  || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must large than 0");
        }

        int requiredSize;
        if (format == PixelFormat.YCbCr_420_SP) {
            requiredSize = height * mStrides[0] +(height >> 1) * mStrides[1];
        } else {
            requiredSize = height * mStrides[0];
        }

        if (requiredSize > mData.length) {
            throw new IllegalArgumentException(
                    "width or/and height is larger than the yuv data");
        }

        return true;
    }

    //////////// native methods

    private static native boolean nativeCompressToJpeg(byte[] oriYuv,
            int format, int width, int height, int[] offsets, int[] strides,
            int quality, OutputStream stream, byte[] tempStorage);
}
