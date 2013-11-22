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
     * <p>This format is guaranteed to be supported for camera preview images since
     * API level 12; for earlier API versions, check
     * {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
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
     *
     * @hide
     */
    public static final int RAW_SENSOR = 0x20;

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
            case BAYER_RGGB:
                return 16;
        }
        return -1;
    }
}
