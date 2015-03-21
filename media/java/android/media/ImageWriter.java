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

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.NioUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * The ImageWriter class allows an application to produce Image data into a
 * {@link android.view.Surface}, and have it be consumed by another component like
 * {@link android.hardware.camera2.CameraDevice CameraDevice}.
 * </p>
 * <p>
 * Several Android API classes can provide input {@link android.view.Surface
 * Surface} objects for ImageWriter to produce data into, including
 * {@link MediaCodec MediaCodec} (encoder),
 * {@link android.hardware.camera2.CameraDevice CameraDevice} (reprocessing
 * input), {@link ImageReader}, etc.
 * </p>
 * <p>
 * The input Image data is encapsulated in {@link Image} objects. To produce
 * Image data into a destination {@link android.view.Surface Surface}, the
 * application can get an input Image via {@link #dequeueInputImage} then write
 * Image data into it. Multiple such {@link Image} objects can be dequeued at
 * the same time and queued back in any order, up to the number specified by the
 * {@code maxImages} constructor parameter.
 * </p>
 * <p>
 * If the application already has an Image from {@link ImageReader}, the
 * application can directly queue this Image into ImageWriter (via
 * {@link #queueInputImage}), potentially with zero buffer copies. For the opaque
 * Images produced by an opaque ImageReader (created by
 * {@link ImageReader#newOpaqueInstance}), this is the only way to send Image
 * data to ImageWriter, as the Image data aren't accessible by the application.
 * </p>
 * Once new input Images are queued into an ImageWriter, it's up to the downstream
 * components (e.g. {@link ImageReader} or
 * {@link android.hardware.camera2.CameraDevice}) to consume the Images. If the
 * downstream components cannot consume the Images at least as fast as the
 * ImageWriter production rate, the {@link #dequeueInputImage} call will eventually
 * block and the application will have to drop input frames. </p>
 */
public class ImageWriter implements AutoCloseable {
    private final Object mListenerLock = new Object();
    private ImageListener mListener;
    private ListenerHandler mListenerHandler;
    private long mNativeContext;

    // Field below is used by native code, do not access or modify.
    private int mWriterFormat;

    private final int mMaxImages;
    // Keep track of the currently attached Image; or an attached Image that is
    // released will be removed from this list.
    private List<Image> mAttachedImages = new ArrayList<Image>();
    private List<Image> mDequeuedImages = new ArrayList<Image>();

    /**
     * <p>
     * Create a new ImageWriter.
     * </p>
     * <p>
     * The {@code maxImages} parameter determines the maximum number of
     * {@link Image} objects that can be be dequeued from the
     * {@code ImageWriter} simultaneously. Requesting more buffers will use up
     * more memory, so it is important to use only the minimum number necessary.
     * </p>
     * <p>
     * The input Image size and format depend on the Surface that is provided by
     * the downstream consumer end-point.
     * </p>
     *
     * @param surface The destination Surface this writer produces Image data
     *            into.
     * @param maxImages The maximum number of Images the user will want to
     *            access simultaneously for producing Image data. This should be
     *            as small as possible to limit memory use. Once maxImages
     *            Images are dequeued by the user, one of them has to be queued
     *            back before a new Image can be dequeued for access via
     *            {@link #dequeueInputImage()}.
     * @return a new ImageWriter instance.
     */
    public static ImageWriter newInstance(Surface surface, int maxImages) {
        return new ImageWriter(surface, maxImages);
    }

    /**
     * @hide
     */
    protected ImageWriter(Surface surface, int maxImages) {
        if (surface == null || maxImages < 1) {
            throw new IllegalArgumentException("Illegal input argument: surface " + surface
                    + ", maxImages: " + maxImages);
        }

        mMaxImages = maxImages;
        // Note that the underlying BufferQueue is working in synchronous mode
        // to avoid dropping any buffers.
        mNativeContext = nativeInit(new WeakReference<ImageWriter>(this), surface, maxImages);
    }

    /**
     * <p>
     * Maximum number of Images that can be dequeued from the ImageWriter
     * simultaneously (for example, with {@link #dequeueInputImage()}).
     * </p>
     * <p>
     * An Image is considered dequeued after it's returned by
     * {@link #dequeueInputImage()} from ImageWriter, and until the Image is
     * sent back to ImageWriter via {@link #queueInputImage}, or
     * {@link Image#close()}.
     * </p>
     * <p>
     * Attempting to dequeue more than {@code maxImages} concurrently will
     * result in the {@link #dequeueInputImage()} function throwing an
     * {@link IllegalStateException}.
     * </p>
     *
     * @return Maximum number of Images that can be dequeued from this
     *         ImageWriter.
     * @see #dequeueInputImage
     * @see #queueInputImage
     * @see Image#close
     */
    public int getMaxImages() {
        return mMaxImages;
    }

    /**
     * <p>
     * Dequeue the next available input Image for the application to produce
     * data into.
     * </p>
     * <p>
     * This method requests a new input Image from ImageWriter. The application
     * owns this Image after this call. Once the application fills the Image
     * data, it is expected to return this Image back to ImageWriter for
     * downstream consumer components (e.g.
     * {@link android.hardware.camera2.CameraDevice}) to consume. The Image can
     * be returned to ImageWriter via {@link #queueInputImage} or
     * {@link Image#close()}.
     * </p>
     * <p>
     * This call will block if all available input images have been filled by
     * the application and the downstream consumer has not yet consumed any.
     * When an Image is consumed by the downstream consumer, an
     * {@link ImageListener#onInputImageReleased} callback will be fired, which
     * indicates that there is one input Image available. It is recommended to
     * dequeue next Image only after this callback is fired, in the steady state.
     * </p>
     *
     * @return The next available input Image from this ImageWriter.
     * @throws IllegalStateException if {@code maxImages} Images are currently
     *             dequeued.
     * @see #queueInputImage
     * @see Image#close
     */
    public Image dequeueInputImage() {
        if (mDequeuedImages.size() >= mMaxImages) {
            throw new IllegalStateException("Already dequeued max number of Images " + mMaxImages);
        }
        WriterSurfaceImage newImage = new WriterSurfaceImage(this);
        nativeDequeueInputImage(mNativeContext, newImage);
        mDequeuedImages.add(newImage);
        newImage.setImageValid(true);
        return newImage;
    }

    /**
     * <p>
     * Queue an input {@link Image} back to ImageWriter for the downstream
     * consumer to access.
     * </p>
     * <p>
     * The input {@link Image} could be from ImageReader (acquired via
     * {@link ImageReader#acquireNextImage} or
     * {@link ImageReader#acquireLatestImage}), or from this ImageWriter
     * (acquired via {@link #dequeueInputImage}). In the former case, the Image
     * data will be moved to this ImageWriter. Note that the Image properties
     * (size, format, strides, etc.) must be the same as the properties of the
     * images dequeued from this ImageWriter, or this method will throw an
     * {@link IllegalArgumentException}. In the latter case, the application has
     * filled the input image with data. This method then passes the filled
     * buffer to the downstream consumer. In both cases, it's up to the caller
     * to ensure that the Image timestamp (in nanoseconds) is correctly set, as
     * the downstream component may want to use it to indicate the Image data
     * capture time.
     * </p>
     * <p>
     * Passing in a non-opaque Image may result in a memory copy, which also
     * requires a free input Image from this ImageWriter as the destination. In
     * this case, this call will block, as {@link #dequeueInputImage} does, if
     * there are no free Images available. To be safe, the application should ensure
     * that there is at least one free Image available in this ImageWriter before calling
     * this method.
     * </p>
     * <p>
     * After this call, the input Image is no longer valid for further access,
     * as if the Image is {@link Image#close closed}. Attempting to access the
     * {@link ByteBuffer ByteBuffers} returned by an earlier
     * {@link Image.Plane#getBuffer Plane#getBuffer} call will result in an
     * {@link IllegalStateException}.
     * </p>
     *
     * @param image The Image to be queued back to ImageWriter for future
     *            consumption.
     * @see #dequeueInputImage()
     */
    public void queueInputImage(Image image) {
        if (image == null) {
            throw new IllegalArgumentException("image shouldn't be null");
        }
        boolean ownedByMe = isImageOwnedByMe(image);
        if (ownedByMe && !(((WriterSurfaceImage) image).isImageValid())) {
            throw new IllegalStateException("Image from ImageWriter is invalid");
        }

        // For images from other components, need to detach first, then attach.
        if (!ownedByMe) {
            if (!(image.getOwner() instanceof ImageReader)) {
                throw new IllegalArgumentException("Only images from ImageReader can be queued to"
                        + " ImageWriter, other image source is not supported yet!");
            }

            ImageReader prevOwner = (ImageReader) image.getOwner();
            // Only do the image attach for opaque images for now. Do the image
            // copy for other formats. TODO: use attach for other formats to
            // improve the performance, and fall back to copy when attach/detach fails.
            if (image.isOpaque()) {
                prevOwner.detachImage(image);
                attachInputImage(image);
            } else {
                Image inputImage = dequeueInputImage();
                inputImage.setTimestamp(image.getTimestamp());
                inputImage.setCropRect(image.getCropRect());
                ImageUtils.imageCopy(image, inputImage);
                image.close();
                image = inputImage;
                ownedByMe = true;
            }
        }

        Rect crop = image.getCropRect();
        nativeQueueInputImage(mNativeContext, image, image.getTimestamp(), crop.left, crop.top,
                crop.right, crop.bottom);

        /**
         * Only remove and cleanup the Images that are owned by this
         * ImageWriter. Images detached from other owners are only
         * temporarily owned by this ImageWriter and will be detached immediately
         * after they are released by downstream consumers, so there is no need to
         * keep track of them in mDequeuedImages.
         */
        if (ownedByMe) {
            mDequeuedImages.remove(image);
            WriterSurfaceImage wi = (WriterSurfaceImage) image;
            wi.clearSurfacePlanes();
            wi.setImageValid(false);
        } else {
            // This clears the native reference held by the original owner. When
            // this Image is detached later by this ImageWriter, the native
            // memory won't be leaked.
            image.close();
        }
    }

    /**
     * ImageWriter callback interface, used to to asynchronously notify the
     * application of various ImageWriter events.
     */
    public interface ImageListener {
        /**
         * <p>
         * Callback that is called when an input Image is released back to
         * ImageWriter after the data consumption.
         * </p>
         * <p>
         * The client can use this callback to indicate either an input Image is
         * available to fill data into, or the input Image is returned and freed
         * if it was attached from other components (e.g. an
         * {@link ImageReader}). For the latter case, the ownership of the Image
         * will be automatically removed by ImageWriter right before this
         * callback is fired.
         * </p>
         *
         * @param writer the ImageWriter the callback is associated with.
         * @see ImageWriter
         * @see Image
         */
        // TODO: the semantics is confusion, does't tell which buffer is
        // released if an application is doing queueInputImage with a mix of
        // buffers from dequeueInputImage and from an ImageReader. see b/19872821
        void onInputImageReleased(ImageWriter writer);
    }

    /**
     * Register a listener to be invoked when an input Image is returned to
     * the ImageWriter.
     *
     * @param listener The listener that will be run.
     * @param handler The handler on which the listener should be invoked, or
     *            null if the listener should be invoked on the calling thread's
     *            looper.
     * @throws IllegalArgumentException If no handler specified and the calling
     *             thread has no looper.
     */
    public void setImageListener(ImageListener listener, Handler handler) {
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
     * Free up all the resources associated with this ImageWriter.
     * <p>
     * After calling this method, this ImageWriter cannot be used. Calling any
     * methods on this ImageWriter and Images previously provided by
     * {@link #dequeueInputImage()} will result in an
     * {@link IllegalStateException}, and attempting to write into
     * {@link ByteBuffer ByteBuffers} returned by an earlier
     * {@link Image.Plane#getBuffer Plane#getBuffer} call will have undefined
     * behavior.
     * </p>
     */
    @Override
    public void close() {
        setImageListener(null, null);
        for (Image image : mDequeuedImages) {
            image.close();
        }
        mDequeuedImages.clear();
        nativeClose(mNativeContext);
        mNativeContext = 0;
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
     * Get the ImageWriter format.
     * <p>
     * This format may be different than the Image format returned by
     * {@link Image#getFormat()}
     * </p>
     *
     * @return The ImageWriter format.
     */
    int getFormat() {
        return mWriterFormat;
    }


    /**
     * <p>
     * Attach input Image to this ImageWriter.
     * </p>
     * <p>
     * When an Image is from an opaque source (e.g. an opaque ImageReader created
     * by {@link ImageReader#newOpaqueInstance}), or the source Image is so large
     * that copying its data is too expensive, this method can be used to
     * migrate the source Image into ImageWriter without a data copy. The source
     * Image must be detached from its previous owner already, or this call will
     * throw an {@link IllegalStateException}.
     * </p>
     * <p>
     * After this call, the ImageWriter takes ownership of this Image.
     * This ownership will be automatically removed from this writer after the
     * consumer releases this Image, that is, after
     * {@link ImageListener#onInputImageReleased}. The caller is
     * responsible for closing this Image through {@link Image#close()} to free up
     * the resources held by this Image.
     * </p>
     *
     * @param image The source Image to be attached and queued into this
     *            ImageWriter for downstream consumer to use.
     * @throws IllegalStateException if the Image is not detached from its
     *             previous owner, or the Image is already attached to this
     *             ImageWriter, or the source Image is invalid.
     */
    private void attachInputImage(Image image) {
        if (image == null) {
            throw new IllegalArgumentException("image shouldn't be null");
        }
        if (isImageOwnedByMe(image)) {
            throw new IllegalArgumentException(
                    "Can not attach an image that is owned ImageWriter already");
        }
        /**
         * Throw ISE if the image is not attachable, which means that it is
         * either owned by other entity now, or completely non-attachable (some
         * stand-alone images are not backed by native gralloc buffer, thus not
         * attachable).
         */
        if (!image.isAttachable()) {
            throw new IllegalStateException("Image was not detached from last owner, or image "
                    + " is not detachable");
        }
        if (mAttachedImages.contains(image)) {
            throw new IllegalStateException("Image was already attached to ImageWritter");
        }

        // TODO: what if attach failed, throw RTE or detach a slot then attach?
        // need do some cleanup to make sure no orphaned
        // buffer caused leak.
        nativeAttachImage(mNativeContext, image);
        mAttachedImages.add(image);
    }

    /**
     * This custom handler runs asynchronously so callbacks don't get queued
     * behind UI messages.
     */
    private final class ListenerHandler extends Handler {
        public ListenerHandler(Looper looper) {
            super(looper, null, true /* async */);
        }

        @Override
        public void handleMessage(Message msg) {
            ImageListener listener;
            synchronized (mListenerLock) {
                listener = mListener;
            }
            // TODO: detach Image from ImageWriter and remove the Image from
            // mAttachedImage list.
            if (listener != null) {
                listener.onInputImageReleased(ImageWriter.this);
            }
        }
    }

    /**
     * Called from Native code when an Event happens. This may be called from an
     * arbitrary Binder thread, so access to the ImageWriter must be
     * synchronized appropriately.
     */
    private static void postEventFromNative(Object selfRef) {
        @SuppressWarnings("unchecked")
        WeakReference<ImageWriter> weakSelf = (WeakReference<ImageWriter>) selfRef;
        final ImageWriter iw = weakSelf.get();
        if (iw == null) {
            return;
        }

        final Handler handler;
        synchronized (iw.mListenerLock) {
            handler = iw.mListenerHandler;
        }
        if (handler != null) {
            handler.sendEmptyMessage(0);
        }
    }

    /**
     * <p>
     * Abort the Images that were dequeued from this ImageWriter, and return
     * them to this writer for reuse.
     * </p>
     * <p>
     * This method is used for the cases where the application dequeued the
     * Image, may have filled the data, but does not want the downstream
     * component to consume it. The Image will be returned to this ImageWriter
     * for reuse after this call, and the ImageWriter will immediately have an
     * Image available to be dequeued. This aborted Image will be invisible to
     * the downstream consumer, as if nothing happened.
     * </p>
     *
     * @param image The Image to be aborted.
     * @see #dequeueInputImage()
     * @see Image#close()
     */
    private void abortImage(Image image) {
        if (image == null) {
            throw new IllegalArgumentException("image shouldn't be null");
        }

        if (!mDequeuedImages.contains(image)) {
            throw new IllegalStateException("It is illegal to abort some image that is not"
                    + " dequeued yet");
        }

        WriterSurfaceImage wi = (WriterSurfaceImage) image;

        if (!wi.isImageValid()) {
            throw new IllegalStateException("Image is invalid");
        }

        /**
         * We only need abort Images that are owned and dequeued by ImageWriter.
         * For attached Images, no need to abort, as there are only two cases:
         * attached + queued successfully, and attach failed. Neither of the
         * cases need abort.
         */
        cancelImage(mNativeContext,image);
        mDequeuedImages.remove(image);
        wi.clearSurfacePlanes();
        wi.setImageValid(false);
    }

    private boolean isImageOwnedByMe(Image image) {
        if (!(image instanceof WriterSurfaceImage)) {
            return false;
        }
        WriterSurfaceImage wi = (WriterSurfaceImage) image;
        if (wi.getOwner() != this) {
            return false;
        }

        return true;
    }

    private static class WriterSurfaceImage extends android.media.Image {
        private ImageWriter mOwner;
        private AtomicBoolean mIsImageValid = new AtomicBoolean(false);
        // This field is used by native code, do not access or modify.
        private long mNativeBuffer;
        private int mNativeFenceFd = -1;
        private SurfacePlane[] mPlanes;
        private int mHeight = -1;
        private int mWidth = -1;
        private int mFormat = -1;
        // When this default timestamp is used, timestamp for the input Image
        // will be generated automatically when queueInputBuffer is called.
        private final long DEFAULT_TIMESTAMP = Long.MIN_VALUE;
        private long mTimestamp = DEFAULT_TIMESTAMP;

        public WriterSurfaceImage(ImageWriter writer) {
            mOwner = writer;
        }

        @Override
        public int getFormat() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }
            if (mFormat == -1) {
                mFormat = nativeGetFormat();
            }
            return mFormat;
        }

        @Override
        public int getWidth() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            if (mWidth == -1) {
                mWidth = nativeGetWidth();
            }

            return mWidth;
        }

        @Override
        public int getHeight() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            if (mHeight == -1) {
                mHeight = nativeGetHeight();
            }

            return mHeight;
        }

        @Override
        public long getTimestamp() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            return mTimestamp;
        }

        @Override
        public void setTimestamp(long timestamp) {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            mTimestamp = timestamp;
        }

        @Override
        public boolean isOpaque() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            return getFormat() == PixelFormat.OPAQUE;
        }

        @Override
        public Plane[] getPlanes() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }

            if (mPlanes == null) {
                int numPlanes = ImageUtils.getNumPlanesForFormat(getFormat());
                mPlanes = nativeCreatePlanes(numPlanes, getOwner().getFormat());
            }

            return mPlanes.clone();
        }

        @Override
        boolean isAttachable() {
            if (!mIsImageValid.get()) {
                throw new IllegalStateException("Image is already released");
            }
            // Don't allow Image to be detached from ImageWriter for now, as no
            // detach API is exposed.
            return false;
        }

        @Override
        ImageWriter getOwner() {
            return mOwner;
        }

        @Override
        public void close() {
            if (mIsImageValid.get()) {
                getOwner().abortImage(this);
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

        private boolean isImageValid() {
            return mIsImageValid.get();
        }

        private void setImageValid(boolean isValid) {
            mIsImageValid.getAndSet(isValid);
        }

        private void clearSurfacePlanes() {
            if (mIsImageValid.get()) {
                for (int i = 0; i < mPlanes.length; i++) {
                    if (mPlanes[i] != null) {
                        mPlanes[i].clearBuffer();
                        mPlanes[i] = null;
                    }
                }
            }
        }

        private class SurfacePlane extends android.media.Image.Plane {
            private ByteBuffer mBuffer;
            final private int mPixelStride;
            final private int mRowStride;

            // SurfacePlane instance is created by native code when a new
            // SurfaceImage is created
            private SurfacePlane(int rowStride, int pixelStride, ByteBuffer buffer) {
                mRowStride = rowStride;
                mPixelStride = pixelStride;
                mBuffer = buffer;
                /**
                 * Set the byteBuffer order according to host endianness (native
                 * order), otherwise, the byteBuffer order defaults to
                 * ByteOrder.BIG_ENDIAN.
                 */
                mBuffer.order(ByteOrder.nativeOrder());
            }

            @Override
            public int getRowStride() {
                if (WriterSurfaceImage.this.isImageValid() == false) {
                    throw new IllegalStateException("Image is already released");
                }
                return mRowStride;
            }

            @Override
            public int getPixelStride() {
                if (WriterSurfaceImage.this.isImageValid() == false) {
                    throw new IllegalStateException("Image is already released");
                }
                return mPixelStride;
            }

            @Override
            public ByteBuffer getBuffer() {
                if (WriterSurfaceImage.this.isImageValid() == false) {
                    throw new IllegalStateException("Image is already released");
                }

                return mBuffer;
            }

            private void clearBuffer() {
                // Need null check first, as the getBuffer() may not be called
                // before an Image is closed.
                if (mBuffer == null) {
                    return;
                }

                if (mBuffer.isDirect()) {
                    NioUtils.freeDirectBuffer(mBuffer);
                }
                mBuffer = null;
            }

        }

        // this will create the SurfacePlane object and fill the information
        private synchronized native SurfacePlane[] nativeCreatePlanes(int numPlanes, int writerFmt);

        private synchronized native int nativeGetWidth();

        private synchronized native int nativeGetHeight();

        private synchronized native int nativeGetFormat();
    }

    // Native implemented ImageWriter methods.
    private synchronized native long nativeInit(Object weakSelf, Surface surface, int maxImgs);

    private synchronized native void nativeClose(long nativeCtx);

    private synchronized native void nativeAttachImage(long nativeCtx, Image image);

    private synchronized native void nativeDequeueInputImage(long nativeCtx, Image wi);

    private synchronized native void nativeQueueInputImage(long nativeCtx, Image image,
            long timestampNs, int left, int top, int right, int bottom);

    private synchronized native void cancelImage(long nativeCtx, Image image);

    /**
     * We use a class initializer to allow the native code to cache some field
     * offsets.
     */
    private static native void nativeClassInit();

    static {
        System.loadLibrary("media_jni");
        nativeClassInit();
    }
}
