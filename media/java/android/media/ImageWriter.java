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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.utils.SurfaceUtils;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Size;
import android.view.Surface;

import dalvik.system.VMRuntime;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.NioUtils;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <p>
 * The ImageWriter class allows an application to produce Image data into a
 * {@link android.view.Surface}, and have it be consumed by another component
 * like {@link android.hardware.camera2.CameraDevice CameraDevice}.
 * </p>
 * <p>
 * Several Android API classes can provide input {@link android.view.Surface
 * Surface} objects for ImageWriter to produce data into, including
 * {@link MediaCodec MediaCodec} (encoder),
 * {@link android.hardware.camera2.CameraCaptureSession CameraCaptureSession}
 * (reprocessing input), {@link ImageReader}, etc.
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
 * application can directly queue this Image into the ImageWriter (via
 * {@link #queueInputImage}), potentially with zero buffer copies. This
 * even works if the image format of the ImageWriter is
 * {@link ImageFormat#PRIVATE PRIVATE}, and prior to Android P is the only
 * way to enqueue images into such an ImageWriter. Starting in Android P
 * private images may also be accessed through their hardware buffers
 * (when available) through the {@link Image#getHardwareBuffer()} method.
 * Attempting to access the planes of a private image, will return an
 * empty array.
 * </p>
 * <p>
 * Once new input Images are queued into an ImageWriter, it's up to the
 * downstream components (e.g. {@link ImageReader} or
 * {@link android.hardware.camera2.CameraDevice}) to consume the Images. If the
 * downstream components cannot consume the Images at least as fast as the
 * ImageWriter production rate, the {@link #dequeueInputImage} call will
 * eventually block and the application will have to drop input frames.
 * </p>
 * <p>
 * If the consumer component that provided the input {@link android.view.Surface Surface}
 * abandons the {@link android.view.Surface Surface}, {@link #queueInputImage queueing}
 * or {@link #dequeueInputImage dequeueing} an {@link Image} will throw an
 * {@link IllegalStateException}.
 * </p>
 */
public class ImageWriter implements AutoCloseable {
    private final Object mListenerLock = new Object();
    private OnImageReleasedListener mListener;
    private ListenerHandler mListenerHandler;
    private long mNativeContext;

    // Field below is used by native code, do not access or modify.
    private int mWriterFormat;

    private final int mMaxImages;
    // Keep track of the currently dequeued Image. This need to be thread safe as the images
    // could be closed by different threads (e.g., application thread and GC thread).
    private List<Image> mDequeuedImages = new CopyOnWriteArrayList<>();
    private int mEstimatedNativeAllocBytes;

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
    public static @NonNull ImageWriter newInstance(@NonNull Surface surface,
            @IntRange(from = 1) int maxImages) {
        return new ImageWriter(surface, maxImages, ImageFormat.UNKNOWN);
    }

    /**
     * <p>
     * Create a new ImageWriter with given number of max Images and format.
     * </p>
     * <p>
     * The {@code maxImages} parameter determines the maximum number of
     * {@link Image} objects that can be be dequeued from the
     * {@code ImageWriter} simultaneously. Requesting more buffers will use up
     * more memory, so it is important to use only the minimum number necessary.
     * </p>
     * <p>
     * The format specifies the image format of this ImageWriter. The format
     * from the {@code surface} will be overridden with this format. For example,
     * if the surface is obtained from a {@link android.graphics.SurfaceTexture}, the default
     * format may be {@link PixelFormat#RGBA_8888}. If the application creates an ImageWriter
     * with this surface and {@link ImageFormat#PRIVATE}, this ImageWriter will be able to operate
     * with {@link ImageFormat#PRIVATE} Images.
     * </p>
     * <p>
     * Note that the consumer end-point may or may not be able to support Images with different
     * format, for such case, the application should only use this method if the consumer is able
     * to consume such images.
     * </p>
     * <p>
     * The input Image size depends on the Surface that is provided by
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
     * @param format The format of this ImageWriter. It can be any valid format specified by
     *            {@link ImageFormat} or {@link PixelFormat}.
     *
     * @return a new ImageWriter instance.
     */
    public static @NonNull ImageWriter newInstance(@NonNull Surface surface,
            @IntRange(from = 1) int maxImages, @Format int format) {
        if (!ImageFormat.isPublicFormat(format) && !PixelFormat.isPublicFormat(format)) {
            throw new IllegalArgumentException("Invalid format is specified: " + format);
        }
        return new ImageWriter(surface, maxImages, format);
    }

    /**
     * @hide
     */
    protected ImageWriter(Surface surface, int maxImages, int format) {
        if (surface == null || maxImages < 1) {
            throw new IllegalArgumentException("Illegal input argument: surface " + surface
                    + ", maxImages: " + maxImages);
        }

        mMaxImages = maxImages;

        // Note that the underlying BufferQueue is working in synchronous mode
        // to avoid dropping any buffers.
        mNativeContext = nativeInit(new WeakReference<>(this), surface, maxImages, format);

        // nativeInit internally overrides UNKNOWN format. So does surface format query after
        // nativeInit and before getEstimatedNativeAllocBytes().
        if (format == ImageFormat.UNKNOWN) {
            format = SurfaceUtils.getSurfaceFormat(surface);
        }
        // Estimate the native buffer allocation size and register it so it gets accounted for
        // during GC. Note that this doesn't include the buffers required by the buffer queue
        // itself and the buffers requested by the producer.
        // Only include memory for 1 buffer, since actually accounting for the memory used is
        // complex, and 1 buffer is enough for the VM to treat the ImageWriter as being of some
        // size.
        Size surfSize = SurfaceUtils.getSurfaceSize(surface);
        mEstimatedNativeAllocBytes =
                ImageUtils.getEstimatedNativeAllocBytes(surfSize.getWidth(),surfSize.getHeight(),
                        format, /*buffer count*/ 1);
        VMRuntime.getRuntime().registerNativeAllocation(mEstimatedNativeAllocBytes);
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
     * This call will block if all available input images have been queued by
     * the application and the downstream consumer has not yet consumed any.
     * When an Image is consumed by the downstream consumer and released, an
     * {@link OnImageReleasedListener#onImageReleased} callback will be fired,
     * which indicates that there is one input Image available. For non-
     * {@link ImageFormat#PRIVATE PRIVATE} formats (
     * {@link ImageWriter#getFormat()} != {@link ImageFormat#PRIVATE}), it is
     * recommended to dequeue the next Image only after this callback is fired,
     * in the steady state.
     * </p>
     * <p>
     * If the format of ImageWriter is {@link ImageFormat#PRIVATE PRIVATE} (
     * {@link ImageWriter#getFormat()} == {@link ImageFormat#PRIVATE}), the
     * image buffer is accessible to the application only through the hardware
     * buffer obtained through {@link Image#getHardwareBuffer()}. (On Android
     * versions prior to P, dequeueing private buffers will cause an
     * {@link IllegalStateException} to be thrown). Alternatively,
     * the application can acquire images from some other component (e.g. an
     * {@link ImageReader}), and queue them directly to this ImageWriter via the
     * {@link ImageWriter#queueInputImage queueInputImage()} method.
     * </p>
     *
     * @return The next available input Image from this ImageWriter.
     * @throws IllegalStateException if {@code maxImages} Images are currently
     *             dequeued, or the input {@link android.view.Surface Surface}
     *             has been abandoned by the consumer component that provided
     *             the {@link android.view.Surface Surface}. Prior to Android
     *             P, throws if the ImageWriter format is
     *             {@link ImageFormat#PRIVATE PRIVATE}.
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
        newImage.mIsImageValid = true;
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
     * After this method is called and the downstream consumer consumes and
     * releases the Image, an {@link OnImageReleasedListener#onImageReleased}
     * callback will fire. The application can use this callback to avoid
     * sending Images faster than the downstream consumer processing rate in
     * steady state.
     * </p>
     * <p>
     * Passing in an Image from some other component (e.g. an
     * {@link ImageReader}) requires a free input Image from this ImageWriter as
     * the destination. In this case, this call will block, as
     * {@link #dequeueInputImage} does, if there are no free Images available.
     * To avoid blocking, the application should ensure that there is at least
     * one free Image available in this ImageWriter before calling this method.
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
     * @throws IllegalStateException if the image was already queued previously,
     *            or the image was aborted previously, or the input
     *            {@link android.view.Surface Surface} has been abandoned by the
     *            consumer component that provided the
     *            {@link android.view.Surface Surface}.
     * @see #dequeueInputImage()
     */
    public void queueInputImage(Image image) {
        if (image == null) {
            throw new IllegalArgumentException("image shouldn't be null");
        }
        boolean ownedByMe = isImageOwnedByMe(image);
        if (ownedByMe && !(((WriterSurfaceImage) image).mIsImageValid)) {
            throw new IllegalStateException("Image from ImageWriter is invalid");
        }

        // For images from other components, need to detach first, then attach.
        if (!ownedByMe) {
            if (!(image.getOwner() instanceof ImageReader)) {
                throw new IllegalArgumentException("Only images from ImageReader can be queued to"
                        + " ImageWriter, other image source is not supported yet!");
            }

            ImageReader prevOwner = (ImageReader) image.getOwner();

            prevOwner.detachImage(image);
            attachAndQueueInputImage(image);
            // This clears the native reference held by the original owner.
            // When this Image is detached later by this ImageWriter, the
            // native memory won't be leaked.
            image.close();
            return;
        }

        Rect crop = image.getCropRect();
        nativeQueueInputImage(mNativeContext, image, image.getTimestamp(), crop.left, crop.top,
                crop.right, crop.bottom, image.getTransform(), image.getScalingMode());

        /**
         * Only remove and cleanup the Images that are owned by this
         * ImageWriter. Images detached from other owners are only temporarily
         * owned by this ImageWriter and will be detached immediately after they
         * are released by downstream consumers, so there is no need to keep
         * track of them in mDequeuedImages.
         */
        if (ownedByMe) {
            mDequeuedImages.remove(image);
            // Do not call close here, as close is essentially cancel image.
            WriterSurfaceImage wi = (WriterSurfaceImage) image;
            wi.clearSurfacePlanes();
            wi.mIsImageValid = false;
        }
    }

    /**
     * Get the ImageWriter format.
     * <p>
     * This format may be different than the Image format returned by
     * {@link Image#getFormat()}. However, if the ImageWriter format is
     * {@link ImageFormat#PRIVATE PRIVATE}, calling {@link #dequeueInputImage()}
     * will result in an {@link IllegalStateException}.
     * </p>
     *
     * @return The ImageWriter format.
     */
    public int getFormat() {
        return mWriterFormat;
    }

    /**
     * ImageWriter callback interface, used to to asynchronously notify the
     * application of various ImageWriter events.
     */
    public interface OnImageReleasedListener {
        /**
         * <p>
         * Callback that is called when an input Image is released back to
         * ImageWriter after the data consumption.
         * </p>
         * <p>
         * The client can use this callback to be notified that an input Image
         * has been consumed and released by the downstream consumer. More
         * specifically, this callback will be fired for below cases:
         * <li>The application dequeues an input Image via the
         * {@link ImageWriter#dequeueInputImage dequeueInputImage()} method,
         * uses it, and then queues it back to this ImageWriter via the
         * {@link ImageWriter#queueInputImage queueInputImage()} method. After
         * the downstream consumer uses and releases this image to this
         * ImageWriter, this callback will be fired. This image will be
         * available to be dequeued after this callback.</li>
         * <li>The application obtains an Image from some other component (e.g.
         * an {@link ImageReader}), uses it, and then queues it to this
         * ImageWriter via {@link ImageWriter#queueInputImage queueInputImage()}.
         * After the downstream consumer uses and releases this image to this
         * ImageWriter, this callback will be fired.</li>
         * </p>
         *
         * @param writer the ImageWriter the callback is associated with.
         * @see ImageWriter
         * @see Image
         */
        void onImageReleased(ImageWriter writer);
    }

    /**
     * Register a listener to be invoked when an input Image is returned to the
     * ImageWriter.
     *
     * @param listener The listener that will be run.
     * @param handler The handler on which the listener should be invoked, or
     *            null if the listener should be invoked on the calling thread's
     *            looper.
     * @throws IllegalArgumentException If no handler specified and the calling
     *             thread has no looper.
     */
    public void setOnImageReleasedListener(OnImageReleasedListener listener, Handler handler) {
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
        setOnImageReleasedListener(null, null);
        for (Image image : mDequeuedImages) {
            image.close();
        }
        mDequeuedImages.clear();
        nativeClose(mNativeContext);
        mNativeContext = 0;

        if (mEstimatedNativeAllocBytes > 0) {
            VMRuntime.getRuntime().registerNativeFree(mEstimatedNativeAllocBytes);
            mEstimatedNativeAllocBytes = 0;
        }
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
     * <p>
     * Attach and queue input Image to this ImageWriter.
     * </p>
     * <p>
     * When the format of an Image is {@link ImageFormat#PRIVATE PRIVATE}, or
     * the source Image is so large that copying its data is too expensive, this
     * method can be used to migrate the source Image into ImageWriter without a
     * data copy, and then queue it to this ImageWriter. The source Image must
     * be detached from its previous owner already, or this call will throw an
     * {@link IllegalStateException}.
     * </p>
     * <p>
     * After this call, the ImageWriter takes ownership of this Image. This
     * ownership will automatically be removed from this writer after the
     * consumer releases this Image, that is, after
     * {@link OnImageReleasedListener#onImageReleased}. The caller is responsible for
     * closing this Image through {@link Image#close()} to free up the resources
     * held by this Image.
     * </p>
     *
     * @param image The source Image to be attached and queued into this
     *            ImageWriter for downstream consumer to use.
     * @throws IllegalStateException if the Image is not detached from its
     *             previous owner, or the Image is already attached to this
     *             ImageWriter, or the source Image is invalid.
     */
    private void attachAndQueueInputImage(Image image) {
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

        // TODO: what if attach failed, throw RTE or detach a slot then attach?
        // need do some cleanup to make sure no orphaned
        // buffer caused leak.
        Rect crop = image.getCropRect();
        nativeAttachAndQueueImage(mNativeContext, image.getNativeContext(), image.getFormat(),
                image.getTimestamp(), crop.left, crop.top, crop.right, crop.bottom,
                image.getTransform(), image.getScalingMode());
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
            OnImageReleasedListener listener;
            synchronized (mListenerLock) {
                listener = mListener;
            }
            if (listener != null) {
                listener.onImageReleased(ImageWriter.this);
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
        if (!wi.mIsImageValid) {
            return;
        }

        /**
         * We only need abort Images that are owned and dequeued by ImageWriter.
         * For attached Images, no need to abort, as there are only two cases:
         * attached + queued successfully, and attach failed. Neither of the
         * cases need abort.
         */
        cancelImage(mNativeContext, image);
        mDequeuedImages.remove(image);
        wi.clearSurfacePlanes();
        wi.mIsImageValid = false;
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

        private int mTransform = 0; //Default no transform
        private int mScalingMode = 0; //Default frozen scaling mode

        public WriterSurfaceImage(ImageWriter writer) {
            mOwner = writer;
        }

        @Override
        public int getFormat() {
            throwISEIfImageIsInvalid();

            if (mFormat == -1) {
                mFormat = nativeGetFormat();
            }
            return mFormat;
        }

        @Override
        public int getWidth() {
            throwISEIfImageIsInvalid();

            if (mWidth == -1) {
                mWidth = nativeGetWidth();
            }

            return mWidth;
        }

        @Override
        public int getHeight() {
            throwISEIfImageIsInvalid();

            if (mHeight == -1) {
                mHeight = nativeGetHeight();
            }

            return mHeight;
        }

        @Override
        public int getTransform() {
            throwISEIfImageIsInvalid();

            return mTransform;
        }

        @Override
        public int getScalingMode() {
            throwISEIfImageIsInvalid();

            return mScalingMode;
        }

        @Override
        public long getTimestamp() {
            throwISEIfImageIsInvalid();

            return mTimestamp;
        }

        @Override
        public void setTimestamp(long timestamp) {
            throwISEIfImageIsInvalid();

            mTimestamp = timestamp;
        }

        @Override
        public HardwareBuffer getHardwareBuffer() {
            throwISEIfImageIsInvalid();

            return nativeGetHardwareBuffer();
        }

        @Override
        public Plane[] getPlanes() {
            throwISEIfImageIsInvalid();

            if (mPlanes == null) {
                int numPlanes = ImageUtils.getNumPlanesForFormat(getFormat());
                mPlanes = nativeCreatePlanes(numPlanes, getOwner().getFormat());
            }

            return mPlanes.clone();
        }

        @Override
        boolean isAttachable() {
            throwISEIfImageIsInvalid();
            // Don't allow Image to be detached from ImageWriter for now, as no
            // detach API is exposed.
            return false;
        }

        @Override
        ImageWriter getOwner() {
            throwISEIfImageIsInvalid();

            return mOwner;
        }

        @Override
        long getNativeContext() {
            throwISEIfImageIsInvalid();

            return mNativeBuffer;
        }

        @Override
        public void close() {
            if (mIsImageValid) {
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

        private void clearSurfacePlanes() {
            if (mIsImageValid && mPlanes != null) {
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

            // SurfacePlane instance is created by native code when SurfaceImage#getPlanes() is
            // called
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
                throwISEIfImageIsInvalid();
                return mRowStride;
            }

            @Override
            public int getPixelStride() {
                throwISEIfImageIsInvalid();
                return mPixelStride;
            }

            @Override
            public ByteBuffer getBuffer() {
                throwISEIfImageIsInvalid();
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

        // Create the SurfacePlane object and fill the information
        private synchronized native SurfacePlane[] nativeCreatePlanes(int numPlanes, int writerFmt);

        private synchronized native int nativeGetWidth();

        private synchronized native int nativeGetHeight();

        private synchronized native int nativeGetFormat();

        private synchronized native HardwareBuffer nativeGetHardwareBuffer();
    }

    // Native implemented ImageWriter methods.
    private synchronized native long nativeInit(Object weakSelf, Surface surface, int maxImgs,
            int format);

    private synchronized native void nativeClose(long nativeCtx);

    private synchronized native void nativeDequeueInputImage(long nativeCtx, Image wi);

    private synchronized native void nativeQueueInputImage(long nativeCtx, Image image,
            long timestampNs, int left, int top, int right, int bottom, int transform,
            int scalingMode);

    private synchronized native int nativeAttachAndQueueImage(long nativeCtx,
            long imageNativeBuffer, int imageFormat, long timestampNs, int left,
            int top, int right, int bottom, int transform, int scalingMode);

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
