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

import android.view.Surface;
import java.lang.AutoCloseable;

/**
 * <p>The ImageReader class allows direct application access to image data
 * rendered into a {@link android.view.Surface}</p>
 *
 * <p>Several Android media API classes accept Surface objects as targets to
 * render to, including {@link MediaPlayer}, {@link MediaCodec},
 * {@link android.hardware.photography.CameraDevice}, and
 * {@link android.renderscript.Allocation RenderScript Allocations}. The image
 * sizes and formats that can be used with each source vary, and should be
 * checked in the documentation for the specific API.</p>
 *
 * <p>The image data is encapsulated in {@link Image} objects, and multiple such
 * objects can be accessed at the same time, up to the number specified by the
 * {@code maxImages} constructor parameter. New images sent to an ImageReader
 * through its Surface are queued until accessed through the
 * {@link #getNextImage} call. Due to memory limits, an image source will
 * eventually stall or drop Images in trying to render to the Surface if the
 * ImageReader does not obtain and release Images at a rate equal to the
 * production rate.</p>
 */
public final class ImageReader {

    /**
     * <p>Create a new reader for images of the desired size and format.</p>
     *
     * <p>The maxImages parameter determines the maximum number of {@link Image}
     * objects that can be be acquired from the ImageReader
     * simultaneously. Requesting more buffers will use up more memory, so it is
     * important to use only the minimum number necessary for the use case.</p>
     *
     * <p>The valid sizes and formats depend on the source of the image
     * data.</p>
     *
     * @param width the width in pixels of the Images that this reader will
     * produce.
     * @param height the height in pixels of the Images that this reader will
     * produce.
     * @param format the format of the Image that this reader will produce. This
     * must be one of the {@link android.graphics.ImageFormat} constants.
     * @param maxImages the maximum number of images the user will want to
     * access simultaneously. This should be as small as possible to limit
     * memory use. Once maxImages Images are obtained by the user, one of them
     * has to be released before a new Image will become available for access
     * through getImage(). Must be greater than 0.
     *
     * @see Image
     */
    public ImageReader(int width, int height, int format, int maxImages) {
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mMaxImages = maxImages;

        if (width < 1 || height < 1) {
            throw new IllegalArgumentException(
                "The image dimensions must be positive");
        }
        if (mMaxImages < 1) {
            throw new IllegalArgumentException(
                "Maximum outstanding image count must be at least 1");
        }
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getImageFormat() {
        return mFormat;
    }

    public int getMaxImages() {
        return mMaxImages;
    }

    /**
     * <p>Get a Surface that can be used to produce Images for this
     * ImageReader.</p>
     *
     * <p>Until valid image data is rendered into this Surface, the
     * {@link #getNextImage} method will return {@code null}. Only one source
     * can be producing data into this Surface at the same time, although the
     * same Surface can be reused with a different API once the first source is
     * disconnected from the Surface.</p>
     *
     * @return A Surface to use for a drawing target for various APIs.
     */
    public Surface getSurface() {
        return null;
    }

    /**
     * <p>Get the next Image from the ImageReader's queue. Returns {@code null}
     * if no new image is available.</p>
     *
     * @return a new frame of image data, or {@code null} if no image data is
     * available.
     */
    public Image getNextImage() {
        return null;
    }

    /**
     * <p>Return the frame to the ImageReader for reuse.</p>
     */
    public void releaseImage(Image i) {
        if (! (i instanceof SurfaceImage) ) {
            throw new IllegalArgumentException(
                "This image was not produced by an ImageReader");
        }
        SurfaceImage si = (SurfaceImage) i;
        if (si.getReader() != this) {
            throw new IllegalArgumentException(
                "This image was not produced by this ImageReader");
        }
    }

    public void setOnImageAvailableListener(OnImageAvailableListener l) {
        mImageListener = l;
    }

    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxImages;

    private OnImageAvailableListener mImageListener;

    private class SurfaceImage extends android.media.Image {
        public SurfaceImage() {
        }

        @Override
        public void close() {
            ImageReader.this.releaseImage(this);
        }

        public ImageReader getReader() {
            return ImageReader.this;
        }
    }
}
