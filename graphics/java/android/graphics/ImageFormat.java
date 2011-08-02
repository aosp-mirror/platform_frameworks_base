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

public class ImageFormat {
    /*
     * these constants are chosen to be binary compatible with their previous
     * location in PixelFormat.java
     */

    public static final int UNKNOWN = 0;

    /**
     * RGB format used for pictures encoded as RGB_565 see
     * {@link android.hardware.Camera.Parameters#setPictureFormat(int)}.
     */
    public static final int RGB_565 = 4;

    /**
     * Android YUV format:
     *
     * This format is exposed to software decoders and applications.
     *
     * YV12 is a 4:2:0 YCrCb planar format comprised of a WxH Y plane followed
     * by (W/2) x (H/2) Cr and Cb planes.
     *
     * This format assumes
     * - an even width
     * - an even height
     * - a horizontal stride multiple of 16 pixels
     * - a vertical stride equal to the height
     *
     *   y_size = stride * height
     *   c_size = ALIGN(stride/2, 16) * height/2
     *   size = y_size + c_size * 2
     *   cr_offset = y_size
     *   cb_offset = y_size + c_size
     *
     * Whether this format is supported by the camera hardware can be determined
     * by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     */
    public static final int YV12 = 0x32315659;

    /**
     * YCbCr format, used for video. Whether this format is supported by the
     * camera hardware can be determined by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     */
    public static final int NV16 = 0x10;

    /**
     * YCrCb format used for images, which uses the NV21 encoding format. This
     * is the default format for camera preview images, when not otherwise set
     * with {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}.
     */
    public static final int NV21 = 0x11;

    /**
     * YCbCr format used for images, which uses YUYV (YUY2) encoding format.
     * This is an alternative format for camera preview images. Whether this
     * format is supported by the camera hardware can be determined by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     */
    public static final int YUY2 = 0x14;

    /**
     * Encoded formats. These are not necessarily supported by the hardware.
     */
    public static final int JPEG = 0x100;

    /**
     * Raw bayer format used for images, which is 10 bit precision samples
     * stored in 16 bit words. The filter pattern is RGGB. Whether this format
     * is supported by the camera hardware can be determined by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     *
     * @hide
     */
    public static final int BAYER_RGGB = 0x200;

    /**
     * Use this function to retrieve the number of bits per pixel of an
     * ImageFormat.
     *
     * @param format
     * @return the number of bits per pixel of the given format or -1 if the
     *         format doesn't exist or is not supported.
     */
    public static int getBitsPerPixel(int format) {
        switch (format) {
            case RGB_565:
                return 16;
            case NV16:
                return 16;
            case YUY2:
                return 16;
            case YV12:
                return 12;
            case NV21:
                return 12;
            case BAYER_RGGB:
                return 16;
        }
        return -1;
    }
}
