/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

import android.graphics.ImageFormat;
import java.nio.ByteBuffer;
import java.lang.AutoCloseable;

/**
 * <p>A single complete image buffer to use with a media source such as a
 * {@link MediaCodec} or a
 * {@link android.hardware.photography.CameraDevice}.</p>
 *
 * <p>This class allows for efficient direct application access to the pixel
 * data of the Image through one or more
 * {@link java.nio.ByteBuffer ByteBuffers}. Each buffer is encapsulated in a
 * {@link Plane} that describes the layout of the pixel data in that plane. Due
 * to this direct access, and unlike the {@link android.graphics.Bitmap} class,
 * Images are not directly usable as as UI resources.</p>
 *
 * <p>Since Images are often directly produced or consumed by hardware
 * components, they are a limited resource shared across the system, and should
 * be closed as soon as they are no longer needed.</p>
 *
 * <p>For example, when using the {@link ImageReader} class to read out Images
 * from various media sources, not closing old Image objects will prevent the
 * availability of new Images once
 * {@link ImageReader#getMaxImages the maximum outstanding image count} is
 * reached.</p>
 *
 * @see ImageReader
 */
public abstract class Image implements AutoCloseable {
    /**
     * Get the format for this image. This format determines the number of
     * ByteBuffers needed to represent the image, and the general layout of the
     * pixel data in each in ByteBuffer.
     *
     * The format is one of the values from
     * {@link android.graphics.ImageFormat}. The mapping between the formats and
     * the planes is as follows:
     *
     * <table>
     * <th>
     *   <td>Format</td>
     *   <td>Plane count</td>
     *   <td>Layout details</td>
     * </th>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#JPEG}</td>
     *   <td>1</td>
     *   <td>Compressed data, so row and pixel strides are 0. To uncompress, use
     *      {@link android.graphics.BitmapFactory#decodeByteArray}.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#YUV_420_888}</td>
     *   <td>3</td>
     *   <td>A luminance plane followed by the Cb and Cr chroma planes.
     *     The chroma planes have half the width and height of the luminance
     *     plane (4:2:0 subsampling). Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#RAW_SENSOR}</td>
     *   <td>1</td>
     *   <td>A single plane of raw sensor image data, with 16 bits per color
     *     sample. The details of the layout need to be queried from the source of
     *     the raw sensor data, such as
     *     {@link android.hardware.photography.CameraDevice}.
     *   </td>
     * </tr>
     * </table>
     *
     * @see android.graphics.ImageFormat
     */
    public int getFormat() {
        return ImageFormat.UNKNOWN;
    }

    /**
     * The width of the image in pixels. For formats where some color channels
     * are subsampled, this is the width of the largest-resolution plane.
     */
    public int getWidth() {
        return 0;
    }

    /**
     * The height of the image in pixels. For formats where some color channels
     * are subsampled, this is the height of the largest-resolution plane.
     */
    public int getHeight() {
        return 0;
    }

    /**
     * Get the timestamp associated with this frame. The timestamp is measured
     * in nanoseconds, and is monotonically increasing. However, the zero point
     * and whether the timestamp can be compared against other sources of time
     * or images depend on the source of this image.
     */
    public long getTimestamp() {
        return 0;
    }

    /**
     * Get the array of pixel planes for this Image. The number of planes is
     * determined by the format of the Image.
     */
    public Plane[] getPlanes() {
        return null;
    }

    /**
     * Free up this frame for reuse. After calling this method, calling any
     * methods on this Image will result in an IllegalStateException, and
     * attempting to read from ByteBuffers returned by an earlier
     * {@code Plane#getBuffer} call will have undefined behavior.
     */
    public abstract void close();

    protected final void finalize() {
        close();
    }

    /**
     * <p>A single color plane of image data.</p>
     *
     * <p>The number and meaning of the planes in an Image are determined by the
     * format of the Image.</p>
     *
     * <p>Once the Image has been closed, any access to the the plane's
     * ByteBuffer will fail.</p>
     *
     * @see #getFormat
     */
    public static final class Plane {
        /**
         * <p>The row stride for this color plane, in bytes.
         *
         * <p>This is the distance between the start of two consecutive rows of
         * pixels in the image.</p>
         */
        public int getRowStride() {
            return 0;
        }

        /**
         * <p>The distance between adjacent pixel samples, in bytes.</p>
         *
         * <p>This is the distance between two consecutive pixel values in a row
         * of pixels. It may be larger than the size of a single pixel to
         * account for interleaved image data or padded formats.</p>
         */
        public int getPixelStride() {
            return 0;
        }

        /**
         * <p>Get a set of direct {@link java.nio.ByteBuffer byte buffers}
         * containing the frame data.</p>
         *
         * @return the byte buffer containing the image data for this plane.
         */
        public ByteBuffer getBuffer() {
            return null;
        }
    }

}
