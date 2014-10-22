/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static android.hardware.camera2.legacy.LegacyExceptionUtils.*;
import static android.hardware.camera2.utils.CameraBinderDecorator.*;
import static com.android.internal.util.Preconditions.*;

/**
 * This class emulates the functionality of a Camera2 device using a the old Camera class.
 *
 * <p>
 * There are two main components that are used to implement this:
 * - A state machine containing valid Camera2 device states ({@link CameraDeviceState}).
 * - A message-queue based pipeline that manages an old Camera class, and executes capture and
 *   configuration requests.
 * </p>
 */
public class LegacyCameraDevice implements AutoCloseable {
    public static final String DEBUG_PROP = "HAL1ShimLogging";
    private final String TAG;

    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);
    private final int mCameraId;
    private final CameraCharacteristics mStaticCharacteristics;
    private final ICameraDeviceCallbacks mDeviceCallbacks;
    private final CameraDeviceState mDeviceState = new CameraDeviceState();
    private List<Surface> mConfiguredSurfaces;
    private boolean mClosed = false;

    private final ConditionVariable mIdle = new ConditionVariable(/*open*/true);

    private final HandlerThread mResultThread = new HandlerThread("ResultThread");
    private final HandlerThread mCallbackHandlerThread = new HandlerThread("CallbackThread");
    private final Handler mCallbackHandler;
    private final Handler mResultHandler;
    private static final int ILLEGAL_VALUE = -1;

    private CaptureResultExtras getExtrasFromRequest(RequestHolder holder) {
        if (holder == null) {
            return new CaptureResultExtras(ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE,
                    ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE);
        }
        return new CaptureResultExtras(holder.getRequestId(), holder.getSubsequeceId(),
                /*afTriggerId*/0, /*precaptureTriggerId*/0, holder.getFrameNumber(),
                /*partialResultCount*/1);
    }

    /**
     * Listener for the camera device state machine.  Calls the appropriate
     * {@link ICameraDeviceCallbacks} for each state transition.
     */
    private final CameraDeviceState.CameraDeviceStateListener mStateListener =
            new CameraDeviceState.CameraDeviceStateListener() {
        @Override
        public void onError(final int errorCode, final RequestHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onError called, errorCode = " + errorCode);
            }
            switch (errorCode) {
                /*
                 * Only be considered idle if we hit a fatal error
                 * and no further requests can be processed.
                 */
                case CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DISCONNECTED:
                case CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_SERVICE:
                case CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE: {
                    mIdle.open();

                    if (DEBUG) {
                        Log.d(TAG, "onError - opening idle");
                    }
                }
            }

            final CaptureResultExtras extras = getExtrasFromRequest(holder);
            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onError callback for request " + holder.getRequestId() +
                                ", with error code " + errorCode);
                    }
                    try {
                        mDeviceCallbacks.onDeviceError(errorCode, extras);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onConfiguring() {
            // Do nothing
            if (DEBUG) {
                Log.d(TAG, "doing onConfiguring callback.");
            }
        }

        @Override
        public void onIdle() {
            if (DEBUG) {
                Log.d(TAG, "onIdle called");
            }

            mIdle.open();

            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onIdle callback.");
                    }
                    try {
                        mDeviceCallbacks.onDeviceIdle();
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraIdle callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onBusy() {
            mIdle.close();

            if (DEBUG) {
                Log.d(TAG, "onBusy called");
            }
        }

        @Override
        public void onCaptureStarted(final RequestHolder holder, final long timestamp) {
            final CaptureResultExtras extras = getExtrasFromRequest(holder);

            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onCaptureStarted callback for request " +
                                holder.getRequestId());
                    }
                    try {
                        mDeviceCallbacks.onCaptureStarted(extras, timestamp);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onCaptureResult(final CameraMetadataNative result, final RequestHolder holder) {
            final CaptureResultExtras extras = getExtrasFromRequest(holder);

            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onCaptureResult callback for request " +
                                holder.getRequestId());
                    }
                    try {
                        mDeviceCallbacks.onResultReceived(result, extras);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }
    };

    private final RequestThreadManager mRequestThreadManager;

    /**
     * Check if a given surface uses {@link ImageFormat#YUV_420_888} or format that can be readily
     * converted to this; YV12 and NV21 are the two currently supported formats.
     *
     * @param s the surface to check.
     * @return {@code true} if the surfaces uses {@link ImageFormat#YUV_420_888} or a compatible
     *          format.
     */
    static boolean needsConversion(Surface s) throws BufferQueueAbandonedException {
        int nativeType = detectSurfaceType(s);
        return nativeType == ImageFormat.YUV_420_888 || nativeType == ImageFormat.YV12 ||
                nativeType == ImageFormat.NV21;
    }

    /**
     * Create a new emulated camera device from a given Camera 1 API camera.
     *
     * <p>
     * The {@link Camera} provided to this constructor must already have been successfully opened,
     * and ownership of the provided camera is passed to this object.  No further calls to the
     * camera methods should be made following this constructor.
     * </p>
     *
     * @param cameraId the id of the camera.
     * @param camera an open {@link Camera} device.
     * @param characteristics the static camera characteristics for this camera device
     * @param callbacks {@link ICameraDeviceCallbacks} callbacks to call for Camera2 API operations.
     */
    public LegacyCameraDevice(int cameraId, Camera camera, CameraCharacteristics characteristics,
            ICameraDeviceCallbacks callbacks) {
        mCameraId = cameraId;
        mDeviceCallbacks = callbacks;
        TAG = String.format("CameraDevice-%d-LE", mCameraId);

        mResultThread.start();
        mResultHandler = new Handler(mResultThread.getLooper());
        mCallbackHandlerThread.start();
        mCallbackHandler = new Handler(mCallbackHandlerThread.getLooper());
        mDeviceState.setCameraDeviceCallbacks(mCallbackHandler, mStateListener);
        mStaticCharacteristics = characteristics;
        mRequestThreadManager =
                new RequestThreadManager(cameraId, camera, characteristics, mDeviceState);
        mRequestThreadManager.start();
    }

    /**
     * Configure the device with a set of output surfaces.
     *
     * <p>Using empty or {@code null} {@code outputs} is the same as unconfiguring.</p>
     *
     * <p>Every surface in {@code outputs} must be non-{@code null}.</p>
     *
     * @param outputs a list of surfaces to set.
     * @return an error code for this binder operation, or {@link NO_ERROR}
     *          on success.
     */
    public int configureOutputs(List<Surface> outputs) {
        if (outputs != null) {
            for (Surface output : outputs) {
                if (output == null) {
                    Log.e(TAG, "configureOutputs - null outputs are not allowed");
                    return BAD_VALUE;
                }
                StreamConfigurationMap streamConfigurations = mStaticCharacteristics.
                        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // Validate surface size and format.
                try {
                    Size s = getSurfaceSize(output);
                    int surfaceType = detectSurfaceType(output);
                    Size[] sizes = streamConfigurations.getOutputSizes(surfaceType);

                    if (sizes == null) {
                        // WAR: Override default format to IMPLEMENTATION_DEFINED for b/9487482
                        if ((surfaceType >= LegacyMetadataMapper.HAL_PIXEL_FORMAT_RGBA_8888 &&
                            surfaceType <= LegacyMetadataMapper.HAL_PIXEL_FORMAT_BGRA_8888)) {

                            // YUV_420_888 is always present in LEGACY for all IMPLEMENTATION_DEFINED
                            // output sizes, and is publicly visible in the API (i.e.
                            // {@code #getOutputSizes} works here).
                            sizes = streamConfigurations.getOutputSizes(ImageFormat.YUV_420_888);
                        } else if (surfaceType == LegacyMetadataMapper.HAL_PIXEL_FORMAT_BLOB) {
                            sizes = streamConfigurations.getOutputSizes(ImageFormat.JPEG);
                        }
                    }

                    if (!ArrayUtils.contains(sizes, s)) {
                        String reason = (sizes == null) ? "format is invalid." :
                                ("size not in valid set: " + Arrays.toString(sizes));
                        Log.e(TAG, String.format("Surface with size (w=%d, h=%d) and format 0x%x is"
                                + " not valid, %s", s.getWidth(), s.getHeight(), surfaceType,
                                reason));
                        return BAD_VALUE;
                    }
                } catch (BufferQueueAbandonedException e) {
                    Log.e(TAG, "Surface bufferqueue is abandoned, cannot configure as output: ", e);
                    return BAD_VALUE;
                }

            }
        }

        boolean success = false;
        if (mDeviceState.setConfiguring()) {
            mRequestThreadManager.configure(outputs);
            success = mDeviceState.setIdle();
        }

        if (success) {
            mConfiguredSurfaces = outputs != null ? new ArrayList<>(outputs) : null;
        } else {
            return CameraBinderDecorator.INVALID_OPERATION;
        }
        return CameraBinderDecorator.NO_ERROR;
    }

    /**
     * Submit a burst of capture requests.
     *
     * @param requestList a list of capture requests to execute.
     * @param repeating {@code true} if this burst is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    burst is set to be repeating.
     * @return the request id.
     */
    public int submitRequestList(List<CaptureRequest> requestList, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        if (requestList == null || requestList.isEmpty()) {
            Log.e(TAG, "submitRequestList - Empty/null requests are not allowed");
            return BAD_VALUE;
        }

        List<Long> surfaceIds = (mConfiguredSurfaces == null) ? new ArrayList<Long>() :
                getSurfaceIds(mConfiguredSurfaces);

        // Make sure that there all requests have at least 1 surface; all surfaces are non-null
        for (CaptureRequest request : requestList) {
            if (request.getTargets().isEmpty()) {
                Log.e(TAG, "submitRequestList - "
                        + "Each request must have at least one Surface target");
                return BAD_VALUE;
            }

            for (Surface surface : request.getTargets()) {
                if (surface == null) {
                    Log.e(TAG, "submitRequestList - Null Surface targets are not allowed");
                    return BAD_VALUE;
                } else if (mConfiguredSurfaces == null) {
                    Log.e(TAG, "submitRequestList - must configure " +
                            " device with valid surfaces before submitting requests");
                    return INVALID_OPERATION;
                } else if (!containsSurfaceId(surface, surfaceIds)) {
                    Log.e(TAG, "submitRequestList - cannot use a surface that wasn't configured");
                    return BAD_VALUE;
                }
            }
        }

        // TODO: further validation of request here
        mIdle.close();
        return mRequestThreadManager.submitCaptureRequests(requestList, repeating,
                frameNumber);
    }

    /**
     * Submit a single capture request.
     *
     * @param request the capture request to execute.
     * @param repeating {@code true} if this request is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    request is set to be repeating.
     * @return the request id.
     */
    public int submitRequest(CaptureRequest request, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        ArrayList<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitRequestList(requestList, repeating, frameNumber);
    }

    /**
     * Cancel the repeating request with the given request id.
     *
     * @param requestId the request id of the request to cancel.
     * @return the last frame number to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public long cancelRequest(int requestId) {
        return mRequestThreadManager.cancelRepeating(requestId);
    }

    /**
     * Block until the {@link ICameraDeviceCallbacks#onCameraIdle()} callback is received.
     */
    public void waitUntilIdle()  {
        mIdle.block();
    }

    /**
     * Flush any pending requests.
     *
     * @return the last frame number.
     */
    public long flush() {
        long lastFrame = mRequestThreadManager.flush();
        waitUntilIdle();
        return lastFrame;
    }

    /**
     * Return {@code true} if the device has been closed.
     */
    public boolean isClosed() {
        return mClosed;
    }

    @Override
    public void close() {
        mRequestThreadManager.quit();
        mCallbackHandlerThread.quitSafely();
        mResultThread.quitSafely();

        try {
            mCallbackHandlerThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mCallbackHandlerThread.getName(), mCallbackHandlerThread.getId()));
        }

        try {
            mResultThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mResultThread.getName(), mResultThread.getId()));
        }

        mClosed = true;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } catch (CameraRuntimeException e) {
            Log.e(TAG, "Got error while trying to finalize, ignoring: " + e.getMessage());
        } finally {
            super.finalize();
        }
    }

    /**
     * Query the surface for its currently configured default buffer size.
     * @param surface a non-{@code null} {@code Surface}
     * @return the width and height of the surface
     *
     * @throws NullPointerException if the {@code surface} was {@code null}
     * @throws IllegalStateException if the {@code surface} was invalid
     */
    static Size getSurfaceSize(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);

        int[] dimens = new int[2];
        LegacyExceptionUtils.throwOnError(nativeDetectSurfaceDimens(surface, /*out*/dimens));

        return new Size(dimens[0], dimens[1]);
    }

    static int detectSurfaceType(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);
        return LegacyExceptionUtils.throwOnError(nativeDetectSurfaceType(surface));
    }

    static void configureSurface(Surface surface, int width, int height,
                                 int pixelFormat) throws BufferQueueAbandonedException {
        checkNotNull(surface);
        checkArgumentPositive(width, "width must be positive.");
        checkArgumentPositive(height, "height must be positive.");

        LegacyExceptionUtils.throwOnError(nativeConfigureSurface(surface, width, height,
                pixelFormat));
    }

    static void produceFrame(Surface surface, byte[] pixelBuffer, int width,
                             int height, int pixelFormat)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);
        checkNotNull(pixelBuffer);
        checkArgumentPositive(width, "width must be positive.");
        checkArgumentPositive(height, "height must be positive.");

        LegacyExceptionUtils.throwOnError(nativeProduceFrame(surface, pixelBuffer, width, height,
                pixelFormat));
    }

    static void setSurfaceFormat(Surface surface, int pixelFormat)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);

        LegacyExceptionUtils.throwOnError(nativeSetSurfaceFormat(surface, pixelFormat));
    }

    static void setSurfaceDimens(Surface surface, int width, int height)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);
        checkArgumentPositive(width, "width must be positive.");
        checkArgumentPositive(height, "height must be positive.");

        LegacyExceptionUtils.throwOnError(nativeSetSurfaceDimens(surface, width, height));
    }

    static long getSurfaceId(Surface surface) {
        checkNotNull(surface);
        return nativeGetSurfaceId(surface);
    }

    static List<Long> getSurfaceIds(Collection<Surface> surfaces) {
        if (surfaces == null) {
            throw new NullPointerException("Null argument surfaces");
        }
        List<Long> surfaceIds = new ArrayList<>();
        for (Surface s : surfaces) {
            long id = getSurfaceId(s);
            if (id == 0) {
                throw new IllegalStateException(
                        "Configured surface had null native GraphicBufferProducer pointer!");
            }
            surfaceIds.add(id);
        }
        return surfaceIds;
    }

    static boolean containsSurfaceId(Surface s, Collection<Long> ids) {
        long id = getSurfaceId(s);
        return ids.contains(id);
    }

    static void setSurfaceOrientation(Surface surface, int facing, int sensorOrientation)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);
        LegacyExceptionUtils.throwOnError(nativeSetSurfaceOrientation(surface, facing,
                sensorOrientation));
    }

    static Size getTextureSize(SurfaceTexture surfaceTexture)
            throws BufferQueueAbandonedException {
        checkNotNull(surfaceTexture);

        int[] dimens = new int[2];
        LegacyExceptionUtils.throwOnError(nativeDetectTextureDimens(surfaceTexture,
                /*out*/dimens));

        return new Size(dimens[0], dimens[1]);
    }

    static void setNextTimestamp(Surface surface, long timestamp)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);
        LegacyExceptionUtils.throwOnError(nativeSetNextTimestamp(surface, timestamp));
    }

    private static native int nativeDetectSurfaceType(Surface surface);

    private static native int nativeDetectSurfaceDimens(Surface surface,
            /*out*/int[/*2*/] dimens);

    private static native int nativeConfigureSurface(Surface surface, int width, int height,
                                                        int pixelFormat);

    private static native int nativeProduceFrame(Surface surface, byte[] pixelBuffer, int width,
                                                    int height, int pixelFormat);

    private static native int nativeSetSurfaceFormat(Surface surface, int pixelFormat);

    private static native int nativeSetSurfaceDimens(Surface surface, int width, int height);

    private static native long nativeGetSurfaceId(Surface surface);

    private static native int nativeSetSurfaceOrientation(Surface surface, int facing,
                                                             int sensorOrientation);

    private static native int nativeDetectTextureDimens(SurfaceTexture surfaceTexture,
            /*out*/int[/*2*/] dimens);

    private static native int nativeSetNextTimestamp(Surface surface, long timestamp);

    static native int nativeGetJpegFooterSize();
}
