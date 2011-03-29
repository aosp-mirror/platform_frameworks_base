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
 * YuvImage contains YUV data and provides a method that compresses a region of
 * the YUV data to a Jpeg. The YUV data should be provided as a single byte
 * array irrespective of the number of image planes in it.
 * Currently only ImageFormat.NV21 and ImageFormat.YUY2 are supported.
 *
 * To compress a rectangle region in the YUV data, users have to specify the
 * region by left, top, width and height.
 */
public class YuvImage {

    /**
     * Number of bytes of temp storage we use for communicating between the
     * native compressor and the java OutputStream.
     */
    private final static int WORKING_COMPRESS_STORAGE = 4096;

   /**
     * The YUV format as defined in {@link ImageFormat}.
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
     * The width of the image.
     */
    private int mWidth;

    /**
     * The height of the the image.
     */
    private int mHeight;

    /**
     * Construct an YuvImage.
     *
     * @param yuv     The YUV data. In the case of more than one image plane, all the planes must be
     *                concatenated into a single byte array.
     * @param format  The YUV data format as defined in {@link ImageFormat}.
     * @param width   The width of the YuvImage.
     * @param height  The height of the YuvImage.
     * @param strides (Optional) Row bytes of each image plane. If yuv contains padding, the stride
     *                of each image must be provided. If strides is null, the method assumes no
     *                padding and derives the row bytes by format and width itself.
     * @throws IllegalArgumentException if format is not support; width or height <= 0; or yuv is
     *                null.
     */
    public YuvImage(byte[] yuv, int format, int width, int height, int[] strides) {
        if (format != ImageFormat.NV21 &&
                format != ImageFormat.YUY2) {
            throw new IllegalArgumentException(
                    "only support ImageFormat.NV21 " +
                    "and ImageFormat.YUY2 for now");
        }

        if (width <= 0  || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must large than 0");
        }

        if (yuv == null) {
            throw new IllegalArgumentException("yuv cannot be null");
        }

        if (strides == null) {
            mStrides = calculateStrides(width, format);
        } else {
            mStrides = strides;
        }

        mData = yuv;
        mFormat = format;
        mWidth = width;
        mHeight = height;
    }

    /**
     * Compress a rectangle region in the YuvImage to a jpeg.
     * Only ImageFormat.NV21 and ImageFormat.YUY2
     * are supported for now.
     *
     * @param rectangle The rectangle region to be compressed. The medthod checks if rectangle is
     *                  inside the image. Also, the method modifies rectangle if the chroma pixels
     *                  in it are not matched with the luma pixels in it.
     * @param quality   Hint to the compressor, 0-100. 0 meaning compress for
     *                  small size, 100 meaning compress for max quality.
     * @param stream    OutputStream to write the compressed data.
     * @return          True if the compression is successful.
     * @throws IllegalArgumentException if rectangle is invalid; quality is not within [0,
     *                  100]; or stream is null.
     */
    public boolean compressToJpeg(Rect rectangle, int quality, OutputStream stream) {
        Rect wholeImage = new Rect(0, 0, mWidth, mHeight);
        if (!wholeImage.contains(rectangle)) {
            throw new IllegalArgumentException(
                    "rectangle is not inside the image");
        }

        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }

        if (stream == null) {
            throw new IllegalArgumentException("stream cannot be null");
        }

        adjustRectangle(rectangle);
        int[] offsets = calculateOffsets(rectangle.left, rectangle.top);

        return nativeCompressToJpeg(mData, mFormat, rectangle.width(),
                rectangle.height(), offsets, mStrides, quality, stream,
                new byte[WORKING_COMPRESS_STORAGE]);
    }


   /**
     * @return the YUV data.
     */
    public byte[] getYuvData() {
        return mData;
    }

    /**
     * @return the YUV format as defined in {@link ImageFormat}.
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

    /**
     * @return the width of the image.
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * @return the height of the image.
     */
    public int getHeight() {
        return mHeight;
    }

    int[] calculateOffsets(int left, int top) {
        int[] offsets = null;
        if (mFormat == ImageFormat.NV21) {
            offsets = new int[] {top * mStrides[0] + left,
                  mHeight * mStrides[0] + top / 2 * mStrides[1]
                  + left / 2 * 2 };
            return offsets;
        }

        if (mFormat == ImageFormat.YUY2) {
            offsets = new int[] {top * mStrides[0] + left / 2 * 4};
            return offsets;
        }

        return offsets;
    }

    private int[] calculateStrides(int width, int format) {
        int[] strides = null;
        if (format == ImageFormat.NV21) {
            strides = new int[] {width, width};
            return strides;
        }

        if (format == ImageFormat.YUY2) {
            strides = new int[] {width * 2};
            return strides;
        }

        return strides;
    }

   private void adjustRectangle(Rect rect) {
       int width = rect.width();
       int height = rect.height();
       if (mFormat == ImageFormat.NV21) {
           // Make sure left, top, width and height are all even.
           width &= ~1;
           height &= ~1;
           rect.left &= ~1;
           rect.top &= ~1;
           rect.right = rect.left + width;
           rect.bottom = rect.top + height;
        }

        if (mFormat == ImageFormat.YUY2) {
            // Make sure left and width are both even.
            width &= ~1;
            rect.left &= ~1;
            rect.right = rect.left + width;
        }
    }

    //////////// native methods

    private static native boolean nativeCompressToJpeg(byte[] oriYuv,
            int format, int width, int height, int[] offsets, int[] strides,
            int quality, OutputStream stream, byte[] tempStorage);
}
