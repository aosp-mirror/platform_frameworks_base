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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
public final class ImageReader implements AutoCloseable {

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
     * through getNextImage(). Must be greater than 0.
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

        mNumPlanes = getNumPlanesFromFormat();

        nativeInit(new WeakReference<ImageReader>(this), width, height, format, maxImages);

        mSurface = nativeGetSurface();
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
        return mSurface;
    }

    /**
     * <p>Get the next Image from the ImageReader's queue. Returns {@code null}
     * if no new image is available.</p>
     *
     * @return a new frame of image data, or {@code null} if no image data is
     * available.
     */
    public Image getNextImage() {
        SurfaceImage si = new SurfaceImage();
        if (nativeImageSetup(si)) {
            // create SurfacePlane objects
            si.createSurfacePlanes();
            si.setImageValid(true);
            return si;
        }
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

        si.clearSurfacePlanes();
        nativeReleaseImage(i);
        si.setImageValid(false);
    }

    /**
     * Register a listener to be invoked when a new image becomes available
     * from the ImageReader.
     * @param listener the listener that will be run
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     */
   public void setImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        mImageListener = listener;

        Looper looper;
        mHandler = handler;
        if (mHandler == null) {
            if ((looper = Looper.myLooper()) != null) {
                mHandler = new Handler();
            } else {
                throw new IllegalArgumentException(
                        "Looper doesn't exist in the calling thread");
            }
        }
    }

    /**
     * Callback interface for being notified that a new image is available.
     * The onImageAvailable is called per image basis, that is, callback fires for every new frame
     * available from ImageReader.
     */
    public interface OnImageAvailableListener {
        /**
         * Callback that is called when a new image is available from ImageReader.
         * @param reader the ImageReader the callback is associated with.
         * @see ImageReader
         * @see Image
         */
        void onImageAvailable(ImageReader reader);
    }

    /**
     * Free up all the resources associated with this ImageReader. After
     * Calling this method, this ImageReader can not be used. calling
     * any methods on this ImageReader and Images previously provided by {@link #getNextImage}
     * will result in an IllegalStateException, and attempting to read from
     * ByteBuffers returned by an earlier {@code Plane#getBuffer} call will
     * have undefined behavior.
     */
    @Override
    public void close() {
        nativeClose();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private int getNumPlanesFromFormat() {
        switch (mFormat) {
            case ImageFormat.YV12:
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
                return 3;
            case ImageFormat.NV16:
                return 2;
            case ImageFormat.RGB_565:
            case ImageFormat.JPEG:
            case ImageFormat.YUY2:
            case ImageFormat.Y8:
            case ImageFormat.Y16:
            case ImageFormat.RAW_SENSOR:
                return 1;
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid format specified %d", mFormat));
        }
    }

    /**
     * Called from Native code when an Event happens.
     */
    private static void postEventFromNative(Object selfRef) {
        WeakReference weakSelf = (WeakReference)selfRef;
        final ImageReader ir = (ImageReader)weakSelf.get();
        if (ir == null) {
            return;
        }

        if (ir.mHandler != null) {
            ir.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ir.mImageListener.onImageAvailable(ir);
                }
              });
        }
    }

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxImages;
    private final int mNumPlanes;
    private final Surface mSurface;

    private Handler mHandler;
    private OnImageAvailableListener mImageListener;

    /**
     * This field is used by native code, do not access or modify.
     */
    private long mNativeContext;

    private class SurfaceImage implements android.media.Image {
        public SurfaceImage() {
            mIsImageValid = false;
        }

        @Override
        public void close() {
            if (mIsImageValid) {
                ImageReader.this.releaseImage(this);
            }
        }

        public ImageReader getReader() {
            return ImageReader.this;
        }

        @Override
        public int getFormat() {
            if (mIsImageValid) {
                return ImageReader.this.mFormat;
            } else {
                throw new IllegalStateException("Image is already released");
            }
        }

        @Override
        public int getWidth() {
            if (mIsImageValid) {
                return ImageReader.this.mWidth;
            } else {
                throw new IllegalStateException("Image is already released");
            }
        }

        @Override
        public int getHeight() {
            if (mIsImageValid) {
                return ImageReader.this.mHeight;
            } else {
                throw new IllegalStateException("Image is already released");
            }
        }

        @Override
        public long getTimestamp() {
            if (mIsImageValid) {
                return mTimestamp;
            } else {
                throw new IllegalStateException("Image is already released");
            }
        }

        @Override
        public Plane[] getPlanes() {
            if (mIsImageValid) {
                // Shallow copy is fine.
                return mPlanes.clone();
            } else {
                throw new IllegalStateException("Image is already released");
            }
        }

        @Override
        protected final void finalize() throws Throwable {
            try {
                close();
            } finally {
                super.finalize();
            }
        }

        private void setImageValid(boolean isValid) {
            mIsImageValid = isValid;
        }

        private boolean isImageValid() {
            return mIsImageValid;
        }

        private void clearSurfacePlanes() {
            if (mIsImageValid) {
                for (int i = 0; i < mPlanes.length; i++) {
                    if (mPlanes[i] != null) {
                        mPlanes[i].clearBuffer();
                        mPlanes[i] = null;
                    }
                }
            }
        }

        private void createSurfacePlanes() {
            mPlanes = new SurfacePlane[ImageReader.this.mNumPlanes];
            for (int i = 0; i < ImageReader.this.mNumPlanes; i++) {
                mPlanes[i] = nativeCreatePlane(i);
            }
        }
        private class SurfacePlane implements android.media.Image.Plane {
            // SurfacePlane instance is created by native code when a new SurfaceImage is created
            private SurfacePlane(int index, int rowStride, int pixelStride) {
                mIndex = index;
                mRowStride = rowStride;
                mPixelStride = pixelStride;
            }

            @Override
            public ByteBuffer getBuffer() {
                if (SurfaceImage.this.isImageValid() == false) {
                    throw new IllegalStateException("Image is already released");
                }
                if (mBuffer != null) {
                    return mBuffer;
                } else {
                    mBuffer = SurfaceImage.this.nativeImageGetBuffer(mIndex);
                    // Set the byteBuffer order according to host endianness (native order),
                    // otherwise, the byteBuffer order defaults to ByteOrder.BIG_ENDIAN.
                    return mBuffer.order(ByteOrder.nativeOrder());
                }
            }

            @Override
            public int getPixelStride() {
                if (SurfaceImage.this.isImageValid()) {
                    return mPixelStride;
                } else {
                    throw new IllegalStateException("Image is already released");
                }
            }

            @Override
            public int getRowStride() {
                if (SurfaceImage.this.isImageValid()) {
                    return mRowStride;
                } else {
                    throw new IllegalStateException("Image is already released");
                }
            }

            private void clearBuffer() {
                mBuffer = null;
            }

            final private int mIndex;
            final private int mPixelStride;
            final private int mRowStride;

            private ByteBuffer mBuffer;
        }

        /**
         * This field is used to keep track of native object and used by native code only.
         * Don't modify.
         */
        private long mLockedBuffer;

        /**
         * This field is set by native code during nativeImageSetup().
         */
        private long mTimestamp;

        private SurfacePlane[] mPlanes;
        private boolean mIsImageValid;

        private synchronized native ByteBuffer nativeImageGetBuffer(int idx);
        private synchronized native SurfacePlane nativeCreatePlane(int idx);
    }

    private synchronized native void nativeInit(Object weakSelf, int w, int h,
                                                    int fmt, int maxImgs);
    private synchronized native void nativeClose();
    private synchronized native void nativeReleaseImage(Image i);
    private synchronized native Surface nativeGetSurface();
    private synchronized native boolean nativeImageSetup(Image i);

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static native void nativeClassInit();
    static {
        System.loadLibrary("media_jni");
        nativeClassInit();
    }
}
