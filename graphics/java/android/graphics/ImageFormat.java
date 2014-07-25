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
     * RGB format used for pictures encoded as RGB_565. See
     * {@link android.hardware.Camera.Parameters#setPictureFormat(int)}.
     */
    public static final int RGB_565 = 4;

    /**
     * <p>Android YUV format.</p>
     *
     * <p>This format is exposed to software decoders and applications.</p>
     *
     * <p>YV12 is a 4:2:0 YCrCb planar format comprised of a WxH Y plane followed
     * by (W/2) x (H/2) Cr and Cb planes.</p>
     *
     * <p>This format assumes
     * <ul>
     * <li>an even width</li>
     * <li>an even height</li>
     * <li>a horizontal stride multiple of 16 pixels</li>
     * <li>a vertical stride equal to the height</li>
     * </ul>
     * </p>
     *
     * <pre> y_size = stride * height
     * c_stride = ALIGN(stride/2, 16)
     * c_size = c_stride * height/2
     * size = y_size + c_size * 2
     * cr_offset = y_size
     * cb_offset = y_size + c_size</pre>
     *
     * <p>For the {@link android.hardware.camera2} API, the {@link #YUV_420_888} format is
     * recommended for YUV output instead.</p>
     *
     * <p>For the older camera API, this format is guaranteed to be supported for
     * {@link android.hardware.Camera} preview images since API level 12; for earlier API versions,
     * check {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     *
     * <p>Note that for camera preview callback use (see
     * {@link android.hardware.Camera#setPreviewCallback}), the
     * <var>stride</var> value is the smallest possible; that is, it is equal
     * to:
     *
     * <pre>stride = ALIGN(width, 16)</pre>
     *
     * @see android.hardware.Camera.Parameters#setPreviewCallback
     * @see android.hardware.Camera.Parameters#setPreviewFormat
     * @see android.hardware.Camera.Parameters#getSupportedPreviewFormats
     * </p>
     */
    public static final int YV12 = 0x32315659;

    /**
     * <p>Android Y8 format.</p>
     *
     * <p>Y8 is a YUV planar format comprised of a WxH Y plane only, with each pixel
     * being represented by 8 bits. It is equivalent to just the Y plane from {@link #YV12}
     * format.</p>
     *
     * <p>This format assumes
     * <ul>
     * <li>an even width</li>
     * <li>an even height</li>
     * <li>a horizontal stride multiple of 16 pixels</li>
     * </ul>
     * </p>
     *
     * <pre> y_size = stride * height </pre>
     *
     * <p>For example, the {@link android.media.Image} object can provide data
     * in this format from a {@link android.hardware.camera2.CameraDevice}
     * through a {@link android.media.ImageReader} object if this format is
     * supported by {@link android.hardware.camera2.CameraDevice}.</p>
     *
     * @see android.media.Image
     * @see android.media.ImageReader
     * @see android.hardware.camera2.CameraDevice
     *
     * @hide
     */
    public static final int Y8 = 0x20203859;

    /**
     * <p>Android Y16 format.</p>
     *
     * Y16 is a YUV planar format comprised of a WxH Y plane, with each pixel
     * being represented by 16 bits. It is just like {@link #Y8}, but has 16
     * bits per pixel (little endian).</p>
     *
     * <p>This format assumes
     * <ul>
     * <li>an even width</li>
     * <li>an even height</li>
     * <li>a horizontal stride multiple of 16 pixels</li>
     * </ul>
     * </p>
     *
     * <pre> y_size = stride * height </pre>
     *
     * <p>For example, the {@link android.media.Image} object can provide data
     * in this format from a {@link android.hardware.camera2.CameraDevice}
     * through a {@link android.media.ImageReader} object if this format is
     * supported by {@link android.hardware.camera2.CameraDevice}.</p>
     *
     * @see android.media.Image
     * @see android.media.ImageReader
     * @see android.hardware.camera2.CameraDevice
     *
     * @hide
     */
    public static final int Y16 = 0x20363159;

    /**
     * YCbCr format, used for video.
     *
     * <p>For the {@link android.hardware.camera2} API, the {@link #YUV_420_888} format is
     * recommended for YUV output instead.</p>
     *
     * <p>Whether this format is supported by the old camera API can be determined by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.</p>
     *
     */
    public static final int NV16 = 0x10;

    /**
     * YCrCb format used for images, which uses the NV21 encoding format.
     *
     * <p>This is the default format
     * for {@link android.hardware.Camera} preview images, when not otherwise set with
     * {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}.</p>
     *
     * <p>For the {@link android.hardware.camera2} API, the {@link #YUV_420_888} format is
     * recommended for YUV output instead.</p>
     */
    public static final int NV21 = 0x11;

    /**
     * YCbCr format used for images, which uses YUYV (YUY2) encoding format.
     *
     * <p>For the {@link android.hardware.camera2} API, the {@link #YUV_420_888} format is
     * recommended for YUV output instead.</p>
     *
     * <p>This is an alternative format for {@link android.hardware.Camera} preview images. Whether
     * this format is supported by the camera hardware can be determined by
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.</p>
     */
    public static final int YUY2 = 0x14;

    /**
     * Compressed JPEG format.
     *
     * <p>This format is always supported as an output format for the
     * {@link android.hardware.camera2} API, and as a picture format for the older
     * {@link android.hardware.Camera} API</p>
     */
    public static final int JPEG = 0x100;

    /**
     * <p>Multi-plane Android YUV format</p>
     *
     * <p>This format is a generic YCbCr format, capable of describing any 4:2:0
     * chroma-subsampled planar or semiplanar buffer (but not fully interleaved),
     * with 8 bits per color sample.</p>
     *
     * <p>Images in this format are always represented by three separate buffers
     * of data, one for each color plane. Additional information always
     * accompanies the buffers, describing the row stride and the pixel stride
     * for each plane.</p>
     *
     * <p>The order of planes in the array returned by
     * {@link android.media.Image#getPlanes() Image#getPlanes()} is guaranteed such that
     * plane #0 is always Y, plane #1 is always U (Cb), and plane #2 is always V (Cr).</p>
     *
     * <p>The Y-plane is guaranteed not to be interleaved with the U/V planes
     * (in particular, pixel stride is always 1 in
     * {@link android.media.Image.Plane#getPixelStride() yPlane.getPixelStride()}).</p>
     *
     * <p>The U/V planes are guaranteed to have the same row stride and pixel stride
     * (in particular,
     * {@link android.media.Image.Plane#getRowStride() uPlane.getRowStride()}
     * == {@link android.media.Image.Plane#getRowStride() vPlane.getRowStride()} and
     * {@link android.media.Image.Plane#getPixelStride() uPlane.getPixelStride()}
     * == {@link android.media.Image.Plane#getPixelStride() vPlane.getPixelStride()};
     * ).</p>
     *
     * <p>For example, the {@link android.media.Image} object can provide data
     * in this format from a {@link android.hardware.camera2.CameraDevice}
     * through a {@link android.media.ImageReader} object.</p>
     *
     * @see android.media.Image
     * @see android.media.ImageReader
     * @see android.hardware.camera2.CameraDevice
     */
    public static final int YUV_420_888 = 0x23;

    /**
     * <p>General raw camera sensor image format, usually representing a
     * single-channel Bayer-mosaic image. Each pixel color sample is stored with
     * 16 bits of precision.</p>
     *
     * <p>The layout of the color mosaic, the maximum and minimum encoding
     * values of the raw pixel data, the color space of the image, and all other
     * needed information to interpret a raw sensor image must be queried from
     * the {@link android.hardware.camera2.CameraDevice} which produced the
     * image.</p>
     */
    public static final int RAW_SENSOR = 0x20;

    /**
     * <p>
     * Android 10-bit raw format
     * </p>
     * <p>
     * This is a single-plane, 10-bit per pixel, densely packed (in each row),
     * unprocessed format, usually representing raw Bayer-pattern images coming
     * from an image sensor.
     * </p>
     * <p>
     * In an image buffer with this format, starting from the first pixel of
     * each row, each 4 consecutive pixels are packed into 5 bytes (40 bits).
     * Each one of the first 4 bytes contains the top 8 bits of each pixel, The
     * fifth byte contains the 2 least significant bits of the 4 pixels, the
     * exact layout data for each 4 consecutive pixels is illustrated below
     * ({@code Pi[j]} stands for the jth bit of the ith pixel):
     * </p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center"></th>
     * <th align="center">bit 7</th>
     * <th align="center">bit 6</th>
     * <th align="center">bit 5</th>
     * <th align="center">bit 4</th>
     * <th align="center">bit 3</th>
     * <th align="center">bit 2</th>
     * <th align="center">bit 1</th>
     * <th align="center">bit 0</th>
     * </tr>
     * </thead> <tbody>
     * <tr>
     * <td align="center">Byte 0:</td>
     * <td align="center">P0[9]</td>
     * <td align="center">P0[8]</td>
     * <td align="center">P0[7]</td>
     * <td align="center">P0[6]</td>
     * <td align="center">P0[5]</td>
     * <td align="center">P0[4]</td>
     * <td align="center">P0[3]</td>
     * <td align="center">P0[2]</td>
     * </tr>
     * <tr>
     * <td align="center">Byte 1:</td>
     * <td align="center">P1[9]</td>
     * <td align="center">P1[8]</td>
     * <td align="center">P1[7]</td>
     * <td align="center">P1[6]</td>
     * <td align="center">P1[5]</td>
     * <td align="center">P1[4]</td>
     * <td align="center">P1[3]</td>
     * <td align="center">P1[2]</td>
     * </tr>
     * <tr>
     * <td align="center">Byte 2:</td>
     * <td align="center">P2[9]</td>
     * <td align="center">P2[8]</td>
     * <td align="center">P2[7]</td>
     * <td align="center">P2[6]</td>
     * <td align="center">P2[5]</td>
     * <td align="center">P2[4]</td>
     * <td align="center">P2[3]</td>
     * <td align="center">P2[2]</td>
     * </tr>
     * <tr>
     * <td align="center">Byte 3:</td>
     * <td align="center">P3[9]</td>
     * <td align="center">P3[8]</td>
     * <td align="center">P3[7]</td>
     * <td align="center">P3[6]</td>
     * <td align="center">P3[5]</td>
     * <td align="center">P3[4]</td>
     * <td align="center">P3[3]</td>
     * <td align="center">P3[2]</td>
     * </tr>
     * <tr>
     * <td align="center">Byte 4:</td>
     * <td align="center">P3[1]</td>
     * <td align="center">P3[0]</td>
     * <td align="center">P2[1]</td>
     * <td align="center">P2[0]</td>
     * <td align="center">P1[1]</td>
     * <td align="center">P1[0]</td>
     * <td align="center">P0[1]</td>
     * <td align="center">P0[0]</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>
     * This format assumes
     * <ul>
     * <li>a width multiple of 4 pixels</li>
     * <li>an even height</li>
     * </ul>
     * </p>
     *
     * <pre>size = row stride * height</pre> where the row stride is in <em>bytes</em>,
     * not pixels.
     *
     * <p>
     * Since this is a densely packed format, the pixel stride is always 0. The
     * application must use the pixel data layout defined in above table to
     * access each row data. When row stride is equal to {@code width * (10 / 8)}, there
     * will be no padding bytes at the end of each row, the entire image data is
     * densely packed. When stride is larger than {@code width * (10 / 8)}, padding
     * bytes will be present at the end of each row.
     * </p>
     * <p>
     * For example, the {@link android.media.Image} object can provide data in
     * this format from a {@link android.hardware.camera2.CameraDevice} (if
     * supported) through a {@link android.media.ImageReader} object. The
     * {@link android.media.Image#getPlanes() Image#getPlanes()} will return a
     * single plane containing the pixel data. The pixel stride is always 0 in
     * {@link android.media.Image.Plane#getPixelStride()}, and the
     * {@link android.media.Image.Plane#getRowStride()} describes the vertical
     * neighboring pixel distance (in bytes) between adjacent rows.
     * </p>
     *
     * @see android.media.Image
     * @see android.media.ImageReader
     * @see android.hardware.camera2.CameraDevice
     */
    public static final int RAW10 = 0x25;

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
            case Y8:
                return 8;
            case Y16:
                return 16;
            case NV21:
                return 12;
            case YUV_420_888:
                return 12;
            case RAW_SENSOR:
                return 16;
            case RAW10:
                return 10;
        }
        return -1;
    }

    /**
     * Determine whether or not this is a public-visible {@code format}.
     *
     * <p>In particular, {@code @hide} formats will return {@code false}.</p>
     *
     * <p>Any other formats (including UNKNOWN) will return {@code false}.</p>
     *
     * @param format an integer format
     * @return a boolean
     *
     * @hide
     */
    public static boolean isPublicFormat(int format) {
        switch (format) {
            case RGB_565:
            case NV16:
            case YUY2:
            case YV12:
            case JPEG:
            case NV21:
            case YUV_420_888:
            case RAW_SENSOR:
            case RAW10:
                return true;
        }

        return false;
    }
}
