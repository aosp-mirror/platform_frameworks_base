/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.camera2;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.hardware.HardwareBuffer;
import android.hardware.HardwareBuffer.Usage;
import android.media.Image;
import android.media.ImageReader;
import android.hardware.camera2.params.MultiResolutionStreamInfo;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;


import java.nio.NioUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * <p>The MultiResolutionImageReader class wraps a group of {@link ImageReader ImageReaders} with
 * the same format and different sizes, source camera Id, or camera sensor modes.</p>
 *
 * <p>The main use case of this class is for a
 * {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA logical
 * multi-camera} or an ultra high resolution sensor camera to output variable-size images. For a
 * logical multi-camera which implements optical zoom, different physical cameras may have different
 * maximum resolutions. As a result, when the camera device switches between physical cameras
 * depending on zoom ratio, the maximum resolution for a particular format may change. For an
 * ultra high resolution sensor camera, the camera device may deem it better or worse to run in
 * maximum resolution mode / default mode depending on lighting conditions. So the application may
 * choose to let the camera device decide on its behalf.</p>
 *
 * <p>MultiResolutionImageReader should be used for a camera device only if the camera device
 * supports multi-resolution output stream by advertising the specified output format in {@link
 * CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP}.</p>
 *
 * <p>To acquire images from the MultiResolutionImageReader, the application must use the
 * {@link ImageReader} object passed by
 * {@link ImageReader.OnImageAvailableListener#onImageAvailable} callback to call
 * {@link ImageReader#acquireNextImage} or {@link ImageReader#acquireLatestImage}. The application
 * must not use the {@link ImageReader} passed by an {@link
 * ImageReader.OnImageAvailableListener#onImageAvailable} callback to acquire future images
 * because future images may originate from a different {@link ImageReader} contained within the
 * {@code MultiResolutionImageReader}.</p>
 *
 *
 * @see ImageReader
 * @see android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
 */
public class MultiResolutionImageReader implements AutoCloseable {

    private static final String TAG = "MultiResolutionImageReader";

    /**
     * <p>
     * Create a new multi-resolution reader based on a group of camera stream properties returned
     * by a camera device.
     * </p>
     * <p>
     * The valid size and formats depend on the camera characteristics.
     * {@code MultiResolutionImageReader} for an image format is supported by the camera device if
     * the format is in the supported multi-resolution output stream formats returned by
     * {@link android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputFormats}.
     * If the image format is supported, the {@code MultiResolutionImageReader} object can be
     * created with the {@code streams} objects returned by
     * {@link android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputInfo}.
     * </p>
     * <p>
     * The {@code maxImages} parameter determines the maximum number of
     * {@link Image} objects that can be acquired from each of the {@code ImageReader}
     * within the {@code MultiResolutionImageReader}. However, requesting more buffers will
     * use up more memory, so it is important to use only the minimum number necessary. The
     * application is strongly recommended to acquire no more than {@code maxImages} images
     * from all of the internal ImageReader objects combined. By keeping track of the number of
     * acquired images for the MultiResolutionImageReader, the application doesn't need to do the
     * bookkeeping for each internal ImageReader returned from {@link
     * ImageReader.OnImageAvailableListener#onImageAvailable onImageAvailable} callback.
     * </p>
     * <p>
     * Unlike the normal ImageReader, the MultiResolutionImageReader has a more complex
     * configuration sequence. Instead of passing the same surface to OutputConfiguration and
     * CaptureRequest, the
     * {@link android.hardware.camera2.params.OutputConfiguration#createInstancesForMultiResolutionOutput}
     * call needs to be used to create the OutputConfigurations for session creation, and then
     * {@link #getSurface} is used to get {@link CaptureRequest.Builder#addTarget the target for
     * CaptureRequest}.
     * </p>
     * @param streams The group of multi-resolution stream info, which is used to create
     *            a multi-resolution reader containing a number of ImageReader objects. Each
     *            ImageReader object represents a multi-resolution stream in the group.
     * @param format The format of the Image that this multi-resolution reader will produce.
     *            This must be one of the {@link android.graphics.ImageFormat} or
     *            {@link android.graphics.PixelFormat} constants. Note that not all formats are
     *            supported, like ImageFormat.NV21. The supported multi-resolution
     *            reader format can be queried by {@link
     *            android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputFormats}.
     * @param maxImages The maximum number of images the user will want to
     *            access simultaneously. This should be as small as possible to
     *            limit memory use. Once maxImages images are obtained by the
     *            user from any given internal ImageReader, one of them has to be released before
     *            a new Image will become available for access through the ImageReader's
     *            {@link ImageReader#acquireLatestImage()} or
     *            {@link ImageReader#acquireNextImage()}. Must be greater than 0.
     * @see Image
     * @see
     * android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
     * @see
     * android.hardware.camera2.params.MultiResolutionStreamConfigurationMap
     */
    public MultiResolutionImageReader(
            @NonNull Collection<MultiResolutionStreamInfo> streams,
            @Format             int format,
            @IntRange(from = 1) int maxImages) {
        mFormat = format;
        mMaxImages = maxImages;

        if (streams == null || streams.size() <= 1) {
            throw new IllegalArgumentException(
                "The streams info collection must contain at least 2 entries");
        }
        if (mMaxImages < 1) {
            throw new IllegalArgumentException(
                "Maximum outstanding image count must be at least 1");
        }

        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        int numImageReaders = streams.size();
        mReaders = new ImageReader[numImageReaders];
        mStreamInfo = new MultiResolutionStreamInfo[numImageReaders];
        int index = 0;
        for (MultiResolutionStreamInfo streamInfo : streams) {
            mReaders[index] = ImageReader.newInstance(streamInfo.getWidth(),
                    streamInfo.getHeight(), format, maxImages);
            mStreamInfo[index] = streamInfo;
            index++;
        }
    }

    /**
     * Set onImageAvailableListener callback.
     *
     * <p>This function sets the onImageAvailableListener for all the internal
     * {@link ImageReader} objects.</p>
     *
     * <p>For a multi-resolution ImageReader, the timestamps of images acquired in
     * onImageAvailable callback from different internal ImageReaders may become
     * out-of-order due to the asynchronous callbacks between the different resolution
     * image queues.</p>
     *
     * @param listener
     *            The listener that will be run.
     * @param executor
     *            The executor which will be used when invoking the callback.
     */
    @SuppressLint({"ExecutorRegistration", "SamShouldBeLast"})
    public void setOnImageAvailableListener(
            @Nullable ImageReader.OnImageAvailableListener listener,
            @Nullable @CallbackExecutor Executor executor) {
        for (int i = 0; i < mReaders.length; i++) {
            mReaders[i].setOnImageAvailableListenerWithExecutor(listener, executor);
        }
    }

    @Override
    public void close() {
        flush();

        for (int i = 0; i < mReaders.length; i++) {
            mReaders[i].close();
        }
    }

    @Override
    protected void finalize() {
        close();
    }

    /**
     * Flush pending images from all internal ImageReaders
     *
     * <p>Acquire and close pending images from all internal ImageReaders. This has the same
     * effect as calling acquireLatestImage() on all internal ImageReaders, and closing all
     * latest images.</p>
     */
    public void flush() {
        flushOther(null);
    }

    /**
     * Flush pending images from other internal ImageReaders
     *
     * <p>Acquire and close pending images from all internal ImageReaders except for the
     * one specified.</p>
     *
     * @param reader The ImageReader object that won't be flushed.
     *
     * @hide
     */
    public void flushOther(ImageReader reader) {
        for (int i = 0; i < mReaders.length; i++) {
            if (reader != null && reader == mReaders[i]) {
                continue;
            }

            while (true) {
                Image image = mReaders[i].acquireNextImageNoThrowISE();
                if (image == null) {
                    break;
                } else {
                    image.close();
                }
            }
        }
    }

    /**
     * Get the internal ImageReader objects
     *
     * @hide
     */
    public @NonNull ImageReader[] getReaders() {
        return mReaders;
    }

    /**
     * Get the surface that is used as a target for {@link CaptureRequest}
     *
     * <p>The application must use the surface returned by this function as a target for
     * {@link CaptureRequest}. The camera device makes the decision on which internal
     * {@code ImageReader} will receive the output image.</p>
     *
     * <p>Please note that holding on to the Surface objects returned by this method is not enough
     * to keep their parent MultiResolutionImageReaders from being reclaimed. In that sense, a
     * Surface acts like a {@link java.lang.ref.WeakReference weak reference} to the
     * MultiResolutionImageReader that provides it.</p>
     *
     * @return a {@link Surface} to use as the target for a capture request.
     */
    public @NonNull Surface getSurface() {
        // Pick the surface of smallest size. This is necessary for an ultra high resolution
        // camera not to default to maximum resolution pixel mode.
        int minReaderSize = mReaders[0].getWidth() * mReaders[0].getHeight();
        Surface candidateSurface = mReaders[0].getSurface();
        for (int i = 1; i < mReaders.length; i++) {
            int readerSize =  mReaders[i].getWidth() * mReaders[i].getHeight();
            if (readerSize < minReaderSize) {
                minReaderSize = readerSize;
                candidateSurface = mReaders[i].getSurface();
            }
        }
        return candidateSurface;
    }

    /**
     * Get the MultiResolutionStreamInfo describing the ImageReader an image originates from
     *
     *<p>An image from a {@code MultiResolutionImageReader} is produced from one of the underlying
     *{@code ImageReader}s. This function returns the {@link MultiResolutionStreamInfo} to describe
     *the property for that {@code ImageReader}, such as width, height, and physical camera Id.</p>
     *
     * @param reader An internal ImageReader within {@code MultiResolutionImageReader}.
     *
     * @return The stream info describing the internal {@code ImageReader}.
     */
    public @NonNull MultiResolutionStreamInfo getStreamInfoForImageReader(
            @NonNull ImageReader reader) {
        for (int i = 0; i < mReaders.length; i++) {
            if (reader == mReaders[i]) {
                return mStreamInfo[i];
            }
        }

        throw new IllegalArgumentException("ImageReader doesn't belong to this multi-resolution "
                + "imagereader");
    }

    // mReaders and mStreamInfo has the same length, and their entries are 1:1 mapped.
    private final ImageReader[] mReaders;
    private final MultiResolutionStreamInfo[] mStreamInfo;

    private final int mFormat;
    private final int mMaxImages;
}
