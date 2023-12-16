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

import com.android.graphics.flags.Flags;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
     *  The color space of the image, defaults to SRGB
     */
    @NonNull private ColorSpace mColorSpace;

    /**
     * Array listing all supported ImageFormat that are supported by this class
     */
    private final static String[] sSupportedFormats =
            {"NV21", "YUY2", "YCBCR_P010", "YUV_420_888"};

    private static String printSupportedFormats() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sSupportedFormats.length; ++i) {
            sb.append(sSupportedFormats[i]);
            if (i != sSupportedFormats.length - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    /**
     * Array listing all supported HDR ColorSpaces that are supported by JPEG/R encoding
     */
    private final static ColorSpace.Named[] sSupportedJpegRHdrColorSpaces = {
        ColorSpace.Named.BT2020_HLG,
        ColorSpace.Named.BT2020_PQ
    };

    /**
     * Array listing all supported SDR ColorSpaces that are supported by JPEG/R encoding
     */
    private final static ColorSpace.Named[] sSupportedJpegRSdrColorSpaces = {
        ColorSpace.Named.SRGB,
        ColorSpace.Named.DISPLAY_P3
    };

    private static String printSupportedJpegRColorSpaces(boolean isHdr) {
        ColorSpace.Named[] colorSpaces = isHdr ? sSupportedJpegRHdrColorSpaces :
                sSupportedJpegRSdrColorSpaces;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colorSpaces.length; ++i) {
            sb.append(ColorSpace.get(colorSpaces[i]).getName());
            if (i != colorSpaces.length - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private static boolean isSupportedJpegRColorSpace(boolean isHdr, int colorSpace) {
        ColorSpace.Named[] colorSpaces = isHdr ? sSupportedJpegRHdrColorSpaces :
              sSupportedJpegRSdrColorSpaces;
        for (ColorSpace.Named cs : colorSpaces) {
            if (cs.ordinal() == colorSpace) {
                return true;
            }
        }
        return false;
    }


    /**
     * Construct an YuvImage. Use SRGB for as default {@link ColorSpace}.
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
        this(yuv, format, width, height, strides, ColorSpace.get(ColorSpace.Named.SRGB));
    }

    /**
     * Construct an YuvImage.
     *
     * @param yuv        The YUV data. In the case of more than one image plane, all the planes
     *                   must be concatenated into a single byte array.
     * @param format     The YUV data format as defined in {@link ImageFormat}.
     * @param width      The width of the YuvImage.
     * @param height     The height of the YuvImage.
     * @param strides    (Optional) Row bytes of each image plane. If yuv contains padding, the
     *                   stride of each image must be provided. If strides is null, the method
     *                   assumes no padding and derives the row bytes by format and width itself.
     * @param colorSpace The YUV image color space as defined in {@link ColorSpace}.
     *                   If the parameter is null, SRGB will be set as the default value.
     * @throws IllegalArgumentException if format is not support; width or height <= 0; or yuv is
     *                null.
     */
    public YuvImage(@NonNull byte[] yuv, int format, int width, int height,
            @Nullable int[] strides, @NonNull ColorSpace colorSpace) {
        if (format != ImageFormat.NV21 &&
                format != ImageFormat.YUY2 &&
                format != ImageFormat.YCBCR_P010 &&
                format != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException(
                    "only supports the following ImageFormat:" + printSupportedFormats());
        }

        if (width <= 0  || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must large than 0");
        }

        if (yuv == null) {
            throw new IllegalArgumentException("yuv cannot be null");
        }

        if (colorSpace == null) {
            throw new IllegalArgumentException("ColorSpace cannot be null");
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
        mColorSpace = colorSpace;
    }

    /**
     * Compress a rectangle region in the YuvImage to a jpeg.
     * For image format, only ImageFormat.NV21 and ImageFormat.YUY2 are supported.
     * For color space, only SRGB is supported.
     *
     * @param rectangle The rectangle region to be compressed. The medthod checks if rectangle is
     *                  inside the image. Also, the method modifies rectangle if the chroma pixels
     *                  in it are not matched with the luma pixels in it.
     * @param quality   Hint to the compressor, 0-100. 0 meaning compress for
     *                  small size, 100 meaning compress for max quality.
     * @param stream    OutputStream to write the compressed data.
     * @return          True if the compression is successful.
     * @throws IllegalArgumentException if rectangle is invalid; color space or image format
     *                  is not supported; quality is not within [0, 100]; or stream is null.
     */
    public boolean compressToJpeg(Rect rectangle, int quality, OutputStream stream) {
        if (mFormat != ImageFormat.NV21 && mFormat != ImageFormat.YUY2) {
            throw new IllegalArgumentException(
                    "Only ImageFormat.NV21 and ImageFormat.YUY2 are supported.");
        }
        if (mColorSpace.getId() != ColorSpace.Named.SRGB.ordinal()) {
            throw new IllegalArgumentException("Only SRGB color space is supported.");
        }

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
   * Compress the HDR image into JPEG/R format.
   *
   * Sample usage:
   *     hdr_image.compressToJpegR(sdr_image, 90, stream);
   *
   * For the SDR image, only YUV_420_888 image format is supported, and the following
   * color spaces are supported:
   *     ColorSpace.Named.SRGB,
   *     ColorSpace.Named.DISPLAY_P3
   *
   * For the HDR image, only YCBCR_P010 image format is supported, and the following
   * color spaces are supported:
   *     ColorSpace.Named.BT2020_HLG,
   *     ColorSpace.Named.BT2020_PQ
   *
   * @param sdr       The SDR image, only ImageFormat.YUV_420_888 is supported.
   * @param quality   Hint to the compressor, 0-100. 0 meaning compress for
   *                  small size, 100 meaning compress for max quality.
   * @param stream    OutputStream to write the compressed data.
   * @return          True if the compression is successful.
   * @throws IllegalArgumentException if input images are invalid; quality is not within [0,
   *                  100]; or stream is null.
   */
    public boolean compressToJpegR(@NonNull YuvImage sdr, int quality,
            @NonNull OutputStream stream) {
        byte[] emptyExif = new byte[0];
        return compressToJpegR(sdr, quality, stream, emptyExif);
    }

    /**
     * Compress the HDR image into JPEG/R format.
     *
     * Sample usage:
     *     hdr_image.compressToJpegR(sdr_image, 90, stream);
     *
     * For the SDR image, only YUV_420_888 image format is supported, and the following
     * color spaces are supported:
     *     ColorSpace.Named.SRGB,
     *     ColorSpace.Named.DISPLAY_P3
     *
     * For the HDR image, only YCBCR_P010 image format is supported, and the following
     * color spaces are supported:
     *     ColorSpace.Named.BT2020_HLG,
     *     ColorSpace.Named.BT2020_PQ
     *
     * @param sdr       The SDR image, only ImageFormat.YUV_420_888 is supported.
     * @param quality   Hint to the compressor, 0-100. 0 meaning compress for
     *                  small size, 100 meaning compress for max quality.
     * @param stream    OutputStream to write the compressed data.
     * @param exif      Exchangeable image file format.
     * @return          True if the compression is successful.
     * @throws IllegalArgumentException if input images are invalid; quality is not within [0,
     *                  100]; or stream is null.
     */
    @FlaggedApi(Flags.FLAG_YUV_IMAGE_COMPRESS_TO_ULTRA_HDR)
    public boolean compressToJpegR(@NonNull YuvImage sdr, int quality,
            @NonNull OutputStream stream, @NonNull byte[] exif) {
        if (sdr == null) {
            throw new IllegalArgumentException("SDR input cannot be null");
        }

        if (mData.length == 0 || sdr.getYuvData().length == 0) {
            throw new IllegalArgumentException("Input images cannot be empty");
        }

        if (mFormat != ImageFormat.YCBCR_P010 || sdr.getYuvFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException(
                "only support ImageFormat.YCBCR_P010 and ImageFormat.YUV_420_888");
        }

        if (sdr.getWidth() != mWidth || sdr.getHeight() != mHeight) {
            throw new IllegalArgumentException("HDR and SDR resolution mismatch");
        }

        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }

        if (stream == null) {
            throw new IllegalArgumentException("stream cannot be null");
        }

        if (!isSupportedJpegRColorSpace(true, mColorSpace.getId()) ||
                !isSupportedJpegRColorSpace(false, sdr.getColorSpace().getId())) {
            throw new IllegalArgumentException("Not supported color space. "
                + "SDR only supports: " + printSupportedJpegRColorSpaces(false)
                + "HDR only supports: " + printSupportedJpegRColorSpaces(true));
        }

      return nativeCompressToJpegR(mData, mColorSpace.getDataSpace(),
                                   sdr.getYuvData(), sdr.getColorSpace().getDataSpace(),
                                   mWidth, mHeight, quality, stream,
                                   new byte[WORKING_COMPRESS_STORAGE], exif,
                                   mStrides, sdr.getStrides());
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


    /**
     * @return the color space of the image.
     */
    public @NonNull ColorSpace getColorSpace() { return mColorSpace; }

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
        switch (format) {
          case ImageFormat.NV21:
            strides = new int[] {width, width};
            return strides;
          case ImageFormat.YCBCR_P010:
            strides = new int[] {width * 2, width * 2};
            return strides;
          case ImageFormat.YUV_420_888:
            strides = new int[] {width, (width + 1) / 2, (width + 1) / 2};
            return strides;
          case ImageFormat.YUY2:
            strides = new int[] {width * 2};
            return strides;
          default:
            throw new IllegalArgumentException(
                "only supports the following ImageFormat:" + printSupportedFormats());
        }
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

    private static native boolean nativeCompressToJpegR(byte[] hdr, int hdrColorSpaceId,
            byte[] sdr, int sdrColorSpaceId, int width, int height, int quality,
            OutputStream stream, byte[] tempStorage, byte[] exif,
            int[] hdrStrides, int[] sdrStrides);
}
