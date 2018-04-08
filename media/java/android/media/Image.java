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

import java.nio.ByteBuffer;
import java.lang.AutoCloseable;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;

/**
 * <p>A single complete image buffer to use with a media source such as a
 * {@link MediaCodec} or a
 * {@link android.hardware.camera2.CameraDevice CameraDevice}.</p>
 *
 * <p>This class allows for efficient direct application access to the pixel
 * data of the Image through one or more
 * {@link java.nio.ByteBuffer ByteBuffers}. Each buffer is encapsulated in a
 * {@link Plane} that describes the layout of the pixel data in that plane. Due
 * to this direct access, and unlike the {@link android.graphics.Bitmap Bitmap} class,
 * Images are not directly usable as UI resources.</p>
 *
 * <p>Since Images are often directly produced or consumed by hardware
 * components, they are a limited resource shared across the system, and should
 * be closed as soon as they are no longer needed.</p>
 *
 * <p>For example, when using the {@link ImageReader} class to read out Images
 * from various media sources, not closing old Image objects will prevent the
 * availability of new Images once
 * {@link ImageReader#getMaxImages the maximum outstanding image count} is
 * reached. When this happens, the function acquiring new Images will typically
 * throw an {@link IllegalStateException}.</p>
 *
 * @see ImageReader
 */
public abstract class Image implements AutoCloseable {
    /**
     * @hide
     */
    protected boolean mIsImageValid = false;

    /**
     * @hide
     */
    protected Image() {
    }

    /**
     * Throw IllegalStateException if the image is invalid (already closed).
     *
     * @hide
     */
    protected void throwISEIfImageIsInvalid() {
        if (!mIsImageValid) {
            throw new IllegalStateException("Image is already closed");
        }
    }
    /**
     * Get the format for this image. This format determines the number of
     * ByteBuffers needed to represent the image, and the general layout of the
     * pixel data in each in ByteBuffer.
     *
     * <p>
     * The format is one of the values from
     * {@link android.graphics.ImageFormat ImageFormat}. The mapping between the
     * formats and the planes is as follows:
     * </p>
     *
     * <table>
     * <tr>
     *   <th>Format</th>
     *   <th>Plane count</th>
     *   <th>Layout details</th>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#JPEG JPEG}</td>
     *   <td>1</td>
     *   <td>Compressed data, so row and pixel strides are 0. To uncompress, use
     *      {@link android.graphics.BitmapFactory#decodeByteArray BitmapFactory#decodeByteArray}.
     *   </td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#YUV_420_888 YUV_420_888}</td>
     *   <td>3</td>
     *   <td>A luminance plane followed by the Cb and Cr chroma planes.
     *     The chroma planes have half the width and height of the luminance
     *     plane (4:2:0 subsampling). Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#YUV_422_888 YUV_422_888}</td>
     *   <td>3</td>
     *   <td>A luminance plane followed by the Cb and Cr chroma planes.
     *     The chroma planes have half the width and the full height of the luminance
     *     plane (4:2:2 subsampling). Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#YUV_444_888 YUV_444_888}</td>
     *   <td>3</td>
     *   <td>A luminance plane followed by the Cb and Cr chroma planes.
     *     The chroma planes have the same width and height as that of the luminance
     *     plane (4:4:4 subsampling). Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#FLEX_RGB_888 FLEX_RGB_888}</td>
     *   <td>3</td>
     *   <td>A R (red) plane followed by the G (green) and B (blue) planes.
     *     All planes have the same widths and heights.
     *     Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#FLEX_RGBA_8888 FLEX_RGBA_8888}</td>
     *   <td>4</td>
     *   <td>A R (red) plane followed by the G (green), B (blue), and
     *     A (alpha) planes. All planes have the same widths and heights.
     *     Each pixel sample in each plane has 8 bits.
     *     Each plane has its own row stride and pixel stride.</td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#RAW_SENSOR RAW_SENSOR}</td>
     *   <td>1</td>
     *   <td>A single plane of raw sensor image data, with 16 bits per color
     *     sample. The details of the layout need to be queried from the source of
     *     the raw sensor data, such as
     *     {@link android.hardware.camera2.CameraDevice CameraDevice}.
     *   </td>
     * </tr>
     * <tr>
     *   <td>{@link android.graphics.ImageFormat#RAW_PRIVATE RAW_PRIVATE}</td>
     *   <td>1</td>
     *   <td>A single plane of raw sensor image data of private layout.
     *   The details of the layout is implementation specific. Row stride and
     *   pixel stride are undefined for this format. Calling {@link Plane#getRowStride()}
     *   or {@link Plane#getPixelStride()} on RAW_PRIVATE image will cause
     *   UnSupportedOperationException being thrown.
     *   </td>
     * </tr>
     * </table>
     *
     * @see android.graphics.ImageFormat
     */
    public abstract int getFormat();

    /**
     * The width of the image in pixels. For formats where some color channels
     * are subsampled, this is the width of the largest-resolution plane.
     */
    public abstract int getWidth();

    /**
     * The height of the image in pixels. For formats where some color channels
     * are subsampled, this is the height of the largest-resolution plane.
     */
    public abstract int getHeight();

    /**
     * Get the timestamp associated with this frame.
     * <p>
     * The timestamp is measured in nanoseconds, and is normally monotonically
     * increasing. The timestamps for the images from different sources may have
     * different timebases therefore may not be comparable. The specific meaning and
     * timebase of the timestamp depend on the source providing images. See
     * {@link android.hardware.Camera Camera},
     * {@link android.hardware.camera2.CameraDevice CameraDevice},
     * {@link MediaPlayer} and {@link MediaCodec} for more details.
     * </p>
     */
    public abstract long getTimestamp();

    /**
     * Get the transformation associated with this frame.
     * @return The window transformation that needs to be applied for this frame.
     * @hide
     */
    public abstract int getTransform();

    /**
     * Get the scaling mode associated with this frame.
     * @return The scaling mode that needs to be applied for this frame.
     * @hide
     */
    public abstract int getScalingMode();

    /**
     * Get the {@link android.hardware.HardwareBuffer HardwareBuffer} handle of the input image
     * intended for GPU and/or hardware access.
     * <p>
     * The returned {@link android.hardware.HardwareBuffer HardwareBuffer} shall not be used
     * after  {@link Image#close Image.close()} has been called.
     * </p>
     * @return the HardwareBuffer associated with this Image or null if this Image doesn't support
     * this feature (e.g. {@link android.media.ImageWriter ImageWriter} or
     * {@link android.media.MediaCodec MediaCodec} don't).
     */
    @Nullable
    public HardwareBuffer getHardwareBuffer() {
        throwISEIfImageIsInvalid();
        return null;
    }

    /**
     * Set the timestamp associated with this frame.
     * <p>
     * The timestamp is measured in nanoseconds, and is normally monotonically
     * increasing. The timestamps for the images from different sources may have
     * different timebases therefore may not be comparable. The specific meaning and
     * timebase of the timestamp depend on the source providing images. See
     * {@link android.hardware.Camera Camera},
     * {@link android.hardware.camera2.CameraDevice CameraDevice},
     * {@link MediaPlayer} and {@link MediaCodec} for more details.
     * </p>
     * <p>
     * For images dequeued from {@link ImageWriter} via
     * {@link ImageWriter#dequeueInputImage()}, it's up to the application to
     * set the timestamps correctly before sending them back to the
     * {@link ImageWriter}, or the timestamp will be generated automatically when
     * {@link ImageWriter#queueInputImage queueInputImage()} is called.
     * </p>
     *
     * @param timestamp The timestamp to be set for this image.
     */
    public void setTimestamp(long timestamp) {
        throwISEIfImageIsInvalid();
        return;
    }

    private Rect mCropRect;

    /**
     * Get the crop rectangle associated with this frame.
     * <p>
     * The crop rectangle specifies the region of valid pixels in the image,
     * using coordinates in the largest-resolution plane.
     */
    public Rect getCropRect() {
        throwISEIfImageIsInvalid();

        if (mCropRect == null) {
            return new Rect(0, 0, getWidth(), getHeight());
        } else {
            return new Rect(mCropRect); // return a copy
        }
    }

    /**
     * Set the crop rectangle associated with this frame.
     * <p>
     * The crop rectangle specifies the region of valid pixels in the image,
     * using coordinates in the largest-resolution plane.
     */
    public void setCropRect(Rect cropRect) {
        throwISEIfImageIsInvalid();

        if (cropRect != null) {
            cropRect = new Rect(cropRect);  // make a copy
            if (!cropRect.intersect(0, 0, getWidth(), getHeight())) {
                cropRect.setEmpty();
            }
        }
        mCropRect = cropRect;
    }

    /**
     * Get the array of pixel planes for this Image. The number of planes is
     * determined by the format of the Image. The application will get an empty
     * array if the image format is {@link android.graphics.ImageFormat#PRIVATE
     * PRIVATE}, because the image pixel data is not directly accessible. The
     * application can check the image format by calling
     * {@link Image#getFormat()}.
     */
    public abstract Plane[] getPlanes();

    /**
     * Free up this frame for reuse.
     * <p>
     * After calling this method, calling any methods on this {@code Image} will
     * result in an {@link IllegalStateException}, and attempting to read from
     * or write to {@link ByteBuffer ByteBuffers} returned by an earlier
     * {@link Plane#getBuffer} call will have undefined behavior. If the image
     * was obtained from {@link ImageWriter} via
     * {@link ImageWriter#dequeueInputImage()}, after calling this method, any
     * image data filled by the application will be lost and the image will be
     * returned to {@link ImageWriter} for reuse. Images given to
     * {@link ImageWriter#queueInputImage queueInputImage()} are automatically
     * closed.
     * </p>
     */
    @Override
    public abstract void close();

    /**
     * <p>
     * Check if the image can be attached to a new owner (e.g. {@link ImageWriter}).
     * </p>
     * <p>
     * This is a package private method that is only used internally.
     * </p>
     *
     * @return true if the image is attachable to a new owner, false if the image is still attached
     *         to its current owner, or the image is a stand-alone image and is not attachable to
     *         a new owner.
     */
    boolean isAttachable() {
        throwISEIfImageIsInvalid();

        return false;
    }

    /**
     * <p>
     * Get the owner of the {@link Image}.
     * </p>
     * <p>
     * The owner of an {@link Image} could be {@link ImageReader}, {@link ImageWriter},
     * {@link MediaCodec} etc. This method returns the owner that produces this image, or null
     * if the image is stand-alone image or the owner is unknown.
     * </p>
     * <p>
     * This is a package private method that is only used internally.
     * </p>
     *
     * @return The owner of the Image.
     */
    Object getOwner() {
        throwISEIfImageIsInvalid();

        return null;
    }

    /**
     * Get native context (buffer pointer) associated with this image.
     * <p>
     * This is a package private method that is only used internally. It can be
     * used to get the native buffer pointer and passed to native, which may be
     * passed to {@link ImageWriter#attachAndQueueInputImage} to avoid a reverse
     * JNI call.
     * </p>
     *
     * @return native context associated with this Image.
     */
    long getNativeContext() {
        throwISEIfImageIsInvalid();

        return 0;
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
    public static abstract class Plane {
        /**
         * @hide
         */
        protected Plane() {
        }

        /**
         * <p>The row stride for this color plane, in bytes.</p>
         *
         * <p>This is the distance between the start of two consecutive rows of
         * pixels in the image. Note that row stried is undefined for some formats
         * such as
         * {@link android.graphics.ImageFormat#RAW_PRIVATE RAW_PRIVATE},
         * and calling getRowStride on images of these formats will
         * cause an UnsupportedOperationException being thrown.
         * For formats where row stride is well defined, the row stride
         * is always greater than 0.</p>
         */
        public abstract int getRowStride();
        /**
         * <p>The distance between adjacent pixel samples, in bytes.</p>
         *
         * <p>This is the distance between two consecutive pixel values in a row
         * of pixels. It may be larger than the size of a single pixel to
         * account for interleaved image data or padded formats.
         * Note that pixel stride is undefined for some formats such as
         * {@link android.graphics.ImageFormat#RAW_PRIVATE RAW_PRIVATE},
         * and calling getPixelStride on images of these formats will
         * cause an UnsupportedOperationException being thrown.
         * For formats where pixel stride is well defined, the pixel stride
         * is always greater than 0.</p>
         */
        public abstract int getPixelStride();
        /**
         * <p>Get a direct {@link java.nio.ByteBuffer ByteBuffer}
         * containing the frame data.</p>
         *
         * <p>In particular, the buffer returned will always have
         * {@link java.nio.ByteBuffer#isDirect isDirect} return {@code true}, so
         * the underlying data could be mapped as a pointer in JNI without doing
         * any copies with {@code GetDirectBufferAddress}.</p>
         *
         * <p>For raw formats, each plane is only guaranteed to contain data
         * up to the last pixel in the last row. In other words, the stride
         * after the last row may not be mapped into the buffer. This is a
         * necessary requirement for any interleaved format.</p>
         *
         * @return the byte buffer containing the image data for this plane.
         */
        public abstract ByteBuffer getBuffer();
    }

}
