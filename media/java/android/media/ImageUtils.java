/*
 * Copyright 2015 The Android Open Source Project
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
import android.graphics.PixelFormat;
import android.media.Image.Plane;
import android.util.Size;

import java.nio.ByteBuffer;

/**
 * Package private utility class for hosting commonly used Image related methods.
 */
class ImageUtils {

    /**
     * Only a subset of the formats defined in
     * {@link android.graphics.ImageFormat ImageFormat} and
     * {@link android.graphics.PixelFormat PixelFormat} are supported by
     * ImageReader. When reading RGB data from a surface, the formats defined in
     * {@link android.graphics.PixelFormat PixelFormat} can be used; when
     * reading YUV, JPEG or raw sensor data (for example, from the camera or video
     * decoder), formats from {@link android.graphics.ImageFormat ImageFormat}
     * are used.
     */
    public static int getNumPlanesForFormat(int format) {
        switch (format) {
            case ImageFormat.YV12:
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
                return 3;
            case ImageFormat.NV16:
                return 2;
            case PixelFormat.RGB_565:
            case PixelFormat.RGBA_8888:
            case PixelFormat.RGBX_8888:
            case PixelFormat.RGB_888:
            case ImageFormat.JPEG:
            case ImageFormat.YUY2:
            case ImageFormat.Y8:
            case ImageFormat.Y16:
            case ImageFormat.RAW_SENSOR:
            case ImageFormat.RAW10:
                return 1;
            case ImageFormat.PRIVATE:
                return 0;
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid format specified %d", format));
        }
    }

    /**
     * <p>
     * Copy source image data to destination Image.
     * </p>
     * <p>
     * Only support the copy between two non-{@link ImageFormat#PRIVATE PRIVATE} format
     * images with same properties (format, size, etc.). The data from the
     * source image will be copied to the byteBuffers from the destination Image
     * starting from position zero, and the destination image will be rewound to
     * zero after copy is done.
     * </p>
     *
     * @param src The source image to be copied from.
     * @param dst The destination image to be copied to.
     * @throws IllegalArgumentException If the source and destination images
     *             have different format, or one of the images is not copyable.
     */
    public static void imageCopy(Image src, Image dst) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Images should be non-null");
        }
        if (src.getFormat() != dst.getFormat()) {
            throw new IllegalArgumentException("Src and dst images should have the same format");
        }
        if (src.getFormat() == ImageFormat.PRIVATE ||
                dst.getFormat() == ImageFormat.PRIVATE) {
            throw new IllegalArgumentException("PRIVATE format images are not copyable");
        }
        if (!(dst.getOwner() instanceof ImageWriter)) {
            throw new IllegalArgumentException("Destination image is not from ImageWriter. Only"
                    + " the images from ImageWriter are writable");
        }
        Size srcSize = new Size(src.getWidth(), src.getHeight());
        Size dstSize = new Size(dst.getWidth(), dst.getHeight());
        if (!srcSize.equals(dstSize)) {
            throw new IllegalArgumentException("source image size " + srcSize + " is different"
                    + " with " + "destination image size " + dstSize);
        }

        Plane[] srcPlanes = src.getPlanes();
        Plane[] dstPlanes = dst.getPlanes();
        ByteBuffer srcBuffer = null;
        ByteBuffer dstBuffer = null;
        for (int i = 0; i < srcPlanes.length; i++) {
            srcBuffer = srcPlanes[i].getBuffer();
            int srcPos = srcBuffer.position();
            srcBuffer.rewind();
            dstBuffer = dstPlanes[i].getBuffer();
            dstBuffer.rewind();
            dstBuffer.put(srcBuffer);
            srcBuffer.position(srcPos);
            dstBuffer.rewind();
        }
    }
}
