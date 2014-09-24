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
import android.graphics.PixelFormat;
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
 * {@link android.hardware.camera2.CameraDevice}, and
 * {@link android.renderscript.Allocation RenderScript Allocations}. The image
 * sizes and formats that can be used with each source vary, and should be
 * checked in the documentation for the specific API.</p>
 *
 * <p>The image data is encapsulated in {@link Image} objects, and multiple such
 * objects can be accessed at the same time, up to the number specified by the
 * {@code maxImages} constructor parameter. New images sent to an ImageReader
 * through its {@link Surface} are queued until accessed through the {@link #acquireLatestImage}
 * or {@link #acquireNextImage} call. Due to memory limits, an image source will
 * eventually stall or drop Images in trying to render to the Surface if the
 * ImageReader does not obtain and release Images at a rate equal to the
 * production rate.</p>
 */
public class ImageReader implements AutoCloseable {

    /**
     * Returned by nativeImageSetup when acquiring the image was successful.
     */
    private static final int ACQUIRE_SUCCESS = 0;
    /**
     * Returned by nativeImageSetup when we couldn't acquire the buffer,
     * because there were no buffers available to acquire.
     */
    private static final int ACQUIRE_NO_BUFS = 1;
    /**
     * Returned by nativeImageSetup when we couldn't acquire the buffer
     * because the consumer has already acquired {@maxImages} and cannot
     * acquire more than that.
     */
    private static final int ACQUIRE_MAX_IMAGES = 2;

    /**
     * <p>Create a new reader for images of the desired size and format.</p>
     *
     * <p>The {@code maxImages} parameter determines the maximum number of {@link Image}
     * objects that can be be acquired from the {@code ImageReader}
     * simultaneously. Requesting more buffers will use up more memory, so it is
     * important to use only the minimum number necessary for the use case.</p>
     *
     * <p>The valid sizes and formats depend on the source of the image
     * data.</p>
     *
     * @param width
     *            The default width in pixels of the Images that this reader will produce.
     * @param height
     *            The default height in pixels of the Images that this reader will produce.
     * @param format
     *            The format of the Image that this reader will produce. This
     *            must be one of the {@link android.graphics.ImageFormat} or
     *            {@link android.graphics.PixelFormat} constants. Note that
     *            not all formats is supported, like ImageFormat.NV21.
     * @param maxImages
     *            The maximum number of images the user will want to
     *            access simultaneously. This should be as small as possible to limit
     *            memory use. Once maxImages Images are obtained by the user, one of them
     *            has to be released before a new Image will become available for access
     *            through {@link #acquireLatestImage()} or {@link #acquireNextImage()}.
     *            Must be greater than 0.
     *
     * @see Image
     */
    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader(width, height, format, maxImages);
    }

    /**
     * @hide
     */
    protected ImageReader(int width, int height, int format, int maxImages) {
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

        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        mNumPlanes = getNumPlanesFromFormat();

        nativeInit(new WeakReference<ImageReader>(this), width, height, format, maxImages);

        mSurface = nativeGetSurface();
    }

    /**
     * The default width of {@link Image Images}, in pixels.
     *
     * <p>The width may be overridden by the producer sending buffers to this
     * ImageReader's Surface. If so, the actual width of the images can be
     * found using {@link Image#getWidth}.</p>
     *
     * @return the expected width of an Image
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * The default height of {@link Image Images}, in pixels.
     *
     * <p>The height may be overridden by the producer sending buffers to this
     * ImageReader's Surface. If so, the actual height of the images can be
     * found using {@link Image#getHeight}.</p>
     *
     * @return the expected height of an Image
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * The default {@link ImageFormat image format} of {@link Image Images}.
     *
     * <p>Some color formats may be overridden by the producer sending buffers to
     * this ImageReader's Surface if the default color format allows. ImageReader
     * guarantees that all {@link Image Images} acquired from ImageReader
     * (for example, with {@link #acquireNextImage}) will have a "compatible"
     * format to what was specified in {@link #newInstance}.
     * As of now, each format is only compatible to itself.
     * The actual format of the images can be found using {@link Image#getFormat}.</p>
     *
     * @return the expected format of an Image
     *
     * @see ImageFormat
     */
    public int getImageFormat() {
        return mFormat;
    }

    /**
     * Maximum number of images that can be acquired from the ImageReader by any time (for example,
     * with {@link #acquireNextImage}).
     *
     * <p>An image is considered acquired after it's returned by a function from ImageReader, and
     * until the Image is {@link Image#close closed} to release the image back to the ImageReader.
     * </p>
     *
     * <p>Attempting to acquire more than {@code maxImages} concurrently will result in the
     * acquire function throwing a {@link IllegalStateException}. Furthermore,
     * while the max number of images have been acquired by the ImageReader user, the producer
     * enqueueing additional images may stall until at least one image has been released. </p>
     *
     * @return Maximum number of images for this ImageReader.
     *
     * @see Image#close
     */
    public int getMaxImages() {
        return mMaxImages;
    }

    /**
     * <p>Get a {@link Surface} that can be used to produce {@link Image Images} for this
     * {@code ImageReader}.</p>
     *
     * <p>Until valid image data is rendered into this {@link Surface}, the
     * {@link #acquireNextImage} method will return {@code null}. Only one source
     * can be producing data into this Surface at the same time, although the
     * same {@link Surface} can be reused with a different API once the first source is
     * disconnected from the {@link Surface}.</p>
     *
     * @return A {@link Surface} to use for a drawing target for various APIs.
     */
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * <p>
     * Acquire the latest {@link Image} from the ImageReader's queue, dropping older
     * {@link Image images}. Returns {@code null} if no new image is available.
     * </p>
     * <p>
     * This operation will acquire all the images possible from the ImageReader,
     * but {@link #close} all images that aren't the latest. This function is
     * recommended to use over {@link #acquireNextImage} for most use-cases, as it's
     * more suited for real-time processing.
     * </p>
     * <p>
     * Note that {@link #getMaxImages maxImages} should be at least 2 for
     * {@link #acquireLatestImage} to be any different than {@link #acquireNextImage} -
     * discarding all-but-the-newest {@link Image} requires temporarily acquiring two
     * {@link Image Images} at once. Or more generally, calling {@link #acquireLatestImage}
     * with less than two images of margin, that is
     * {@code (maxImages - currentAcquiredImages < 2)} will not discard as expected.
     * </p>
     * <p>
     * This operation will fail by throwing an {@link IllegalStateException} if
     * {@code maxImages} have been acquired with {@link #acquireLatestImage} or
     * {@link #acquireNextImage}. In particular a sequence of {@link #acquireLatestImage}
     * calls greater than {@link #getMaxImages} without calling {@link Image#close} in-between
     * will exhaust the underlying queue. At such a time, {@link IllegalStateException}
     * will be thrown until more images are
     * released with {@link Image#close}.
     * </p>
     *
     * @return latest frame of image data, or {@code null} if no image data is available.
     * @throws IllegalStateException if too many images are currently acquired
     */
    public Image acquireLatestImage() {
        Image image = acquireNextImage();
        if (image == null) {
            return null;
        }
        try {
            for (;;) {
                Image next = acquireNextImageNoThrowISE();
                if (next == null) {
                    Image result = image;
                    image = null;
                    return result;
                }
                image.close();
                image = next;
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * Don't throw IllegalStateException if there are too many images acquired.
     *
     * @return Image if acquiring succeeded, or null otherwise.
     *
     * @hide
     */
    public Image acquireNextImageNoThrowISE() {
        SurfaceImage si = new SurfaceImage();
        return acquireNextSurfaceImage(si) == ACQUIRE_SUCCESS ? si : null;
    }

    /**
     * Attempts to acquire the next image from the underlying native implementation.
     *
     * <p>
     * Note that unexpected failures will throw at the JNI level.
     * </p>
     *
     * @param si A blank SurfaceImage.
     * @return One of the {@code ACQUIRE_*} codes that determine success or failure.
     *
     * @see #ACQUIRE_MAX_IMAGES
     * @see #ACQUIRE_NO_BUFS
     * @see #ACQUIRE_SUCCESS
     */
    private int acquireNextSurfaceImage(SurfaceImage si) {

        int status = nativeImageSetup(si);

        switch (status) {
            case ACQUIRE_SUCCESS:
                si.createSurfacePlanes();
                si.setImageValid(true);
            case ACQUIRE_NO_BUFS:
            case ACQUIRE_MAX_IMAGES:
                break;
            default:
                throw new AssertionError("Unknown nativeImageSetup return code " + status);
        }

        return status;
    }

    /**
     * <p>
     * Acquire the next Image from the ImageReader's queue. Returns {@code null} if
     * no new image is available.
     * </p>
     *
     * <p><i>Warning:</i> Consider using {@link #acquireLatestImage()} instead, as it will
     * automatically release older images, and allow slower-running processing routines to catch
     * up to the newest frame. Usage of {@link #acquireNextImage} is recommended for
     * batch/background processing. Incorrectly using this function can cause images to appear
     * with an ever-increasing delay, followed by a complete stall where no new images seem to
     * appear.
     * </p>
     *
     * <p>
     * This operation will fail by throwing an {@link IllegalStateException} if
     * {@code maxImages} have been acquired with {@link #acquireNextImage} or
     * {@link #acquireLatestImage}. In particular a sequence of {@link #acquireNextImage} or
     * {@link #acquireLatestImage} calls greater than {@link #getMaxImages maxImages} without
     * calling {@link Image#close} in-between will exhaust the underlying queue. At such a time,
     * {@link IllegalStateException} will be thrown until more images are released with
     * {@link Image#close}.
     * </p>
     *
     * @return a new frame of image data, or {@code null} if no image data is available.
     * @throws IllegalStateException if {@code maxImages} images are currently acquired
     * @see #acquireLatestImage
     */
    public Image acquireNextImage() {
        SurfaceImage si = new SurfaceImage();
        int status = acquireNextSurfaceImage(si);

        switch (status) {
            case ACQUIRE_SUCCESS:
                return si;
            case ACQUIRE_NO_BUFS:
                return null;
            case ACQUIRE_MAX_IMAGES:
                throw new IllegalStateException(
                        String.format(
                                "maxImages (%d) has already been acquired, " +
                                "call #close before acquiring more.", mMaxImages));
            default:
                throw new AssertionError("Unknown nativeImageSetup return code " + status);
        }
    }

    /**
     * <p>Return the frame to the ImageReader for reuse.</p>
     */
    private void releaseImage(Image i) {
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
     *
     * @param listener
     *            The listener that will be run.
     * @param handler
     *            The handler on which the listener should be invoked, or null
     *            if the listener should be invoked on the calling thread's looper.
     * @throws IllegalArgumentException
     *            If no handler specified and the calling thread has no looper.
     */
    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        synchronized (mListenerLock) {
            if (listener != null) {
                Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
                if (looper == null) {
                    throw new IllegalArgumentException(
                            "handler is null but the current thread is not a looper");
                }
                if (mListenerHandler == null || mListenerHandler.getLooper() != looper) {
                    mListenerHandler = new ListenerHandler(looper);
                }
                mListener = listener;
            } else {
                mListener = null;
                mListenerHandler = null;
            }
        }
    }

    /**
     * Callback interface for being notified that a new image is available.
     *
     * <p>
     * The onImageAvailable is called per image basis, that is, callback fires for every new frame
     * available from ImageReader.
     * </p>
     */
    public interface OnImageAvailableListener {
        /**
         * Callback that is called when a new image is available from ImageReader.
         *
         * @param reader the ImageReader the callback is associated with.
         * @see ImageReader
         * @see Image
         */
        void onImageAvailable(ImageReader reader);
    }

    /**
     * Free up all the resources associated with this ImageReader.
     *
     * <p>
     * After calling this method, this ImageReader can not be used. Calling
     * any methods on this ImageReader and Images previously provided by
     * {@link #acquireNextImage} or {@link #acquireLatestImage}
     * will result in an {@link IllegalStateException}, and attempting to read from
     * {@link ByteBuffer ByteBuffers} returned by an earlier
     * {@link Image.Plane#getBuffer Plane#getBuffer} call will
     * have undefined behavior.
     * </p>
     */
    @Override
    public void close() {
        setOnImageAvailableListener(null, null);
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

    /**
     * Only a subset of the formats defined in
     * {@link android.graphics.ImageFormat ImageFormat} and
     * {@link android.graphics.PixelFormat PixelFormat} are supported by
     * ImageReader. When reading RGB data from a surface, the formats defined in
     * {@link android.graphics.PixelFormat PixelFormat} can be used, when
     * reading YUV, JPEG or raw sensor data (for example, from camera or video
     * decoder), formats from {@link android.graphics.ImageFormat ImageFormat}
     * are used.
     */
    private int getNumPlanesFromFormat() {
        switch (mFormat) {
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
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid format specified %d", mFormat));
        }
    }

    /**
     * Called from Native code when an Event happens.
     *
     * This may be called from an arbitrary Binder thread, so access to the ImageReader must be
     * synchronized appropriately.
     */
    private static void postEventFromNative(Object selfRef) {
        @SuppressWarnings("unchecked")
        WeakReference<ImageReader> weakSelf = (WeakReference<ImageReader>)selfRef;
        final ImageReader ir = weakSelf.get();
        if (ir == null) {
            return;
        }

        final Handler handler;
        synchronized (ir.mListenerLock) {
            handler = ir.mListenerHandler;
        }
        if (handler != null) {
            handler.sendEmptyMessage(0);
        }
    }


    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxImages;
    private final int mNumPlanes;
    private final Surface mSurface;

    private final Object mListenerLock = new Object();
    private OnImageAvailableListener mListener;
    private ListenerHandler mListenerHandler;

    /**
     * This field is used by native code, do not access or modify.
     */
    private long mNativeContext;

    /**
     * This custom handler runs asynchronously so callbacks don't get queued behind UI messages.
     */
    private final class ListenerHandler extends Handler {
        public ListenerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            OnImageAvailableListener listener;
            synchronized (mListenerLock) {
                listener = mListener;
            }
            if (listener != null) {
                listener.onImageAvailable(ImageReader.this);
            }
        }
    }

    private class SurfaceImage extends android.media.Image {
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
                mPlanes[i] = nativeCreatePlane(i, ImageReader.this.mFormat);
            }
        }
        private class SurfacePlane extends android.media.Image.Plane {
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
                    mBuffer = SurfaceImage.this.nativeImageGetBuffer(mIndex,
                            ImageReader.this.mFormat);
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

        private synchronized native ByteBuffer nativeImageGetBuffer(int idx, int readerFormat);
        private synchronized native SurfacePlane nativeCreatePlane(int idx, int readerFormat);
    }

    private synchronized native void nativeInit(Object weakSelf, int w, int h,
                                                    int fmt, int maxImgs);
    private synchronized native void nativeClose();
    private synchronized native void nativeReleaseImage(Image i);
    private synchronized native Surface nativeGetSurface();

    /**
     * @return A return code {@code ACQUIRE_*}
     *
     * @see #ACQUIRE_SUCCESS
     * @see #ACQUIRE_NO_BUFS
     * @see #ACQUIRE_MAX_IMAGES
     */
    private synchronized native int nativeImageSetup(Image i);

    /**
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static native void nativeClassInit();
    static {
        System.loadLibrary("media_jni");
        nativeClassInit();
    }
}
