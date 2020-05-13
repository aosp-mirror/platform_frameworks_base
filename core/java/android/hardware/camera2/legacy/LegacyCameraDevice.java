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
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.SubmitInfo;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static android.hardware.camera2.legacy.LegacyExceptionUtils.*;
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
    private final String TAG;

    private static final boolean DEBUG = false;
    private final int mCameraId;
    private final CameraCharacteristics mStaticCharacteristics;
    private final ICameraDeviceCallbacks mDeviceCallbacks;
    private final CameraDeviceState mDeviceState = new CameraDeviceState();
    private SparseArray<Surface> mConfiguredSurfaces;
    private boolean mClosed = false;

    private final ConditionVariable mIdle = new ConditionVariable(/*open*/true);

    private final HandlerThread mResultThread = new HandlerThread("ResultThread");
    private final HandlerThread mCallbackHandlerThread = new HandlerThread("CallbackThread");
    private final Handler mCallbackHandler;
    private final Handler mResultHandler;
    private static final int ILLEGAL_VALUE = -1;

    // Keep up to date with values in hardware/libhardware/include/hardware/gralloc.h
    private static final int GRALLOC_USAGE_RENDERSCRIPT = 0x00100000;
    private static final int GRALLOC_USAGE_SW_READ_OFTEN = 0x00000003;
    private static final int GRALLOC_USAGE_HW_TEXTURE = 0x00000100;
    private static final int GRALLOC_USAGE_HW_COMPOSER = 0x00000800;
    private static final int GRALLOC_USAGE_HW_RENDER = 0x00000200;
    private static final int GRALLOC_USAGE_HW_VIDEO_ENCODER = 0x00010000;

    public static final int MAX_DIMEN_FOR_ROUNDING = 1920; // maximum allowed width for rounding

    // Keep up to date with values in system/core/include/system/window.h
    public static final int NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW = 1;

    private CaptureResultExtras getExtrasFromRequest(RequestHolder holder) {
        return getExtrasFromRequest(holder,
                /*errorCode*/CameraDeviceState.NO_CAPTURE_ERROR, /*errorArg*/null);
    }

    private CaptureResultExtras getExtrasFromRequest(RequestHolder holder,
            int errorCode, Object errorArg) {
        int errorStreamId = -1;
        if (errorCode == CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_BUFFER) {
            Surface errorTarget = (Surface) errorArg;
            int indexOfTarget = mConfiguredSurfaces.indexOfValue(errorTarget);
            if (indexOfTarget < 0) {
                Log.e(TAG, "Buffer drop error reported for unknown Surface");
            } else {
                errorStreamId = mConfiguredSurfaces.keyAt(indexOfTarget);
            }
        }
        if (holder == null) {
            return new CaptureResultExtras(ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE,
                    ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE, null,
                    ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE);
        }
        return new CaptureResultExtras(holder.getRequestId(), holder.getSubsequeceId(),
                /*afTriggerId*/0, /*precaptureTriggerId*/0, holder.getFrameNumber(),
                /*partialResultCount*/1, errorStreamId, null, holder.getFrameNumber(), -1, -1);
    }

    /**
     * Listener for the camera device state machine.  Calls the appropriate
     * {@link ICameraDeviceCallbacks} for each state transition.
     */
    private final CameraDeviceState.CameraDeviceStateListener mStateListener =
            new CameraDeviceState.CameraDeviceStateListener() {
        @Override
        public void onError(final int errorCode, final Object errorArg, final RequestHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onError called, errorCode = " + errorCode + ", errorArg = " + errorArg);
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

            final CaptureResultExtras extras = getExtrasFromRequest(holder, errorCode, errorArg);
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
        public void onRequestQueueEmpty() {
            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onRequestQueueEmpty callback");
                    }
                    try {
                        mDeviceCallbacks.onRequestQueueEmpty();
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onRequestQueueEmpty callback: ",
                                e);
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
                        mDeviceCallbacks.onResultReceived(result, extras,
                                new PhysicalCaptureResultInfo[0]);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onRepeatingRequestError(final long lastFrameNumber,
                final int repeatingRequestId) {
            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onRepeatingRequestError callback.");
                    }
                    try {
                        mDeviceCallbacks.onRepeatingRequestError(lastFrameNumber,
                                repeatingRequestId);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onRepeatingRequestError " +
                                "callback: ", e);
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
     * @param outputs a list of surfaces to set. LegacyCameraDevice will take ownership of this
     *          list; it must not be modified by the caller once it's passed in.
     * @return an error code for this binder operation, or {@link NO_ERROR}
     *          on success.
     */
    public int configureOutputs(SparseArray<Surface> outputs) {
        return configureOutputs(outputs, /*validateSurfacesOnly*/false);
    }

    /**
     * Configure the device with a set of output surfaces.
     *
     * <p>Using empty or {@code null} {@code outputs} is the same as unconfiguring.</p>
     *
     * <p>Every surface in {@code outputs} must be non-{@code null}.</p>
     *
     * @param outputs a list of surfaces to set. LegacyCameraDevice will take ownership of this
     *          list; it must not be modified by the caller once it's passed in.
     * @param validateSurfacesOnly If set it will only check whether the outputs are supported
     *                             and avoid any device configuration.
     * @return an error code for this binder operation, or {@link NO_ERROR}
     *          on success.
     * @hide
     */
    public int configureOutputs(SparseArray<Surface> outputs, boolean validateSurfacesOnly) {
        List<Pair<Surface, Size>> sizedSurfaces = new ArrayList<>();
        if (outputs != null) {
            int count = outputs.size();
            for (int i = 0; i < count; i++)  {
                Surface output = outputs.valueAt(i);
                if (output == null) {
                    Log.e(TAG, "configureOutputs - null outputs are not allowed");
                    return BAD_VALUE;
                }
                if (!output.isValid()) {
                    Log.e(TAG, "configureOutputs - invalid output surfaces are not allowed");
                    return BAD_VALUE;
                }
                StreamConfigurationMap streamConfigurations = mStaticCharacteristics.
                        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // Validate surface size and format.
                try {
                    Size s = getSurfaceSize(output);
                    int surfaceType = detectSurfaceType(output);

                    boolean flexibleConsumer = isFlexibleConsumer(output);

                    Size[] sizes = streamConfigurations.getOutputSizes(surfaceType);
                    if (sizes == null) {
                        if (surfaceType == ImageFormat.PRIVATE) {

                            // YUV_420_888 is always present in LEGACY for all
                            // IMPLEMENTATION_DEFINED output sizes, and is publicly visible in the
                            // API (i.e. {@code #getOutputSizes} works here).
                            sizes = streamConfigurations.getOutputSizes(ImageFormat.YUV_420_888);
                        } else if (surfaceType == LegacyMetadataMapper.HAL_PIXEL_FORMAT_BLOB) {
                            sizes = streamConfigurations.getOutputSizes(ImageFormat.JPEG);
                        }
                    }

                    if (!ArrayUtils.contains(sizes, s)) {
                        if (flexibleConsumer && (s = findClosestSize(s, sizes)) != null) {
                            sizedSurfaces.add(new Pair<>(output, s));
                        } else {
                            String reason = (sizes == null) ? "format is invalid." :
                                    ("size not in valid set: " + Arrays.toString(sizes));
                            Log.e(TAG, String.format("Surface with size (w=%d, h=%d) and format " +
                                    "0x%x is not valid, %s", s.getWidth(), s.getHeight(),
                                    surfaceType, reason));
                            return BAD_VALUE;
                        }
                    } else {
                        sizedSurfaces.add(new Pair<>(output, s));
                    }
                    // Lock down the size before configuration
                    if (!validateSurfacesOnly) {
                        setSurfaceDimens(output, s.getWidth(), s.getHeight());
                    }
                } catch (BufferQueueAbandonedException e) {
                    Log.e(TAG, "Surface bufferqueue is abandoned, cannot configure as output: ", e);
                    return BAD_VALUE;
                }

            }
        }

        if (validateSurfacesOnly) {
            return LegacyExceptionUtils.NO_ERROR;
        }

        boolean success = false;
        if (mDeviceState.setConfiguring()) {
            mRequestThreadManager.configure(sizedSurfaces);
            success = mDeviceState.setIdle();
        }

        if (success) {
            mConfiguredSurfaces = outputs;
        } else {
            return LegacyExceptionUtils.INVALID_OPERATION;
        }
        return LegacyExceptionUtils.NO_ERROR;
    }

    /**
     * Submit a burst of capture requests.
     *
     * @param requestList a list of capture requests to execute.
     * @param repeating {@code true} if this burst is repeating.
     * @return the submission info, including the new request id, and the last frame number, which
     *   contains either the frame number of the last frame that will be returned for this request,
     *   or the frame number of the last frame that will be returned for the current repeating
     *   request if this burst is set to be repeating.
     */
    public SubmitInfo submitRequestList(CaptureRequest[] requestList, boolean repeating) {
        if (requestList == null || requestList.length == 0) {
            Log.e(TAG, "submitRequestList - Empty/null requests are not allowed");
            throw new ServiceSpecificException(BAD_VALUE,
                    "submitRequestList - Empty/null requests are not allowed");
        }

        List<Long> surfaceIds;

        try {
            surfaceIds = (mConfiguredSurfaces == null) ? new ArrayList<Long>() :
                    getSurfaceIds(mConfiguredSurfaces);
        } catch (BufferQueueAbandonedException e) {
            throw new ServiceSpecificException(BAD_VALUE,
                    "submitRequestList - configured surface is abandoned.");
        }

        // Make sure that there all requests have at least 1 surface; all surfaces are non-null
        for (CaptureRequest request : requestList) {
            if (request.getTargets().isEmpty()) {
                Log.e(TAG, "submitRequestList - "
                        + "Each request must have at least one Surface target");
                throw new ServiceSpecificException(BAD_VALUE,
                        "submitRequestList - "
                        + "Each request must have at least one Surface target");
            }

            for (Surface surface : request.getTargets()) {
                if (surface == null) {
                    Log.e(TAG, "submitRequestList - Null Surface targets are not allowed");
                    throw new ServiceSpecificException(BAD_VALUE,
                            "submitRequestList - Null Surface targets are not allowed");
                } else if (mConfiguredSurfaces == null) {
                    Log.e(TAG, "submitRequestList - must configure " +
                            " device with valid surfaces before submitting requests");
                    throw new ServiceSpecificException(INVALID_OPERATION,
                            "submitRequestList - must configure " +
                            " device with valid surfaces before submitting requests");
                } else if (!containsSurfaceId(surface, surfaceIds)) {
                    Log.e(TAG, "submitRequestList - cannot use a surface that wasn't configured");
                    throw new ServiceSpecificException(BAD_VALUE,
                            "submitRequestList - cannot use a surface that wasn't configured");
                }
            }
        }

        // TODO: further validation of request here
        mIdle.close();
        return mRequestThreadManager.submitCaptureRequests(requestList, repeating);
    }

    /**
     * Submit a single capture request.
     *
     * @param request the capture request to execute.
     * @param repeating {@code true} if this request is repeating.
     * @return the submission info, including the new request id, and the last frame number, which
     *   contains either the frame number of the last frame that will be returned for this request,
     *   or the frame number of the last frame that will be returned for the current repeating
     *   request if this burst is set to be repeating.
     */
    public SubmitInfo submitRequest(CaptureRequest request, boolean repeating) {
        CaptureRequest[] requestList = { request };
        return submitRequestList(requestList, repeating);
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

    public void setAudioRestriction(int mode) {
        mRequestThreadManager.setAudioRestriction(mode);
    }

    public int getAudioRestriction() {
        return mRequestThreadManager.getAudioRestriction();
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
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "Got error while trying to finalize, ignoring: " + e.getMessage());
        } finally {
            super.finalize();
        }
    }

    static long findEuclidDistSquare(Size a, Size b) {
        long d0 = a.getWidth() - b.getWidth();
        long d1 = a.getHeight() - b.getHeight();
        return d0 * d0 + d1 * d1;
    }

    // Keep up to date with rounding behavior in
    // frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp
    static Size findClosestSize(Size size, Size[] supportedSizes) {
        if (size == null || supportedSizes == null) {
            return null;
        }
        Size bestSize = null;
        for (Size s : supportedSizes) {
            if (s.equals(size)) {
                return size;
            } else if (s.getWidth() <= MAX_DIMEN_FOR_ROUNDING && (bestSize == null ||
                    LegacyCameraDevice.findEuclidDistSquare(size, s) <
                    LegacyCameraDevice.findEuclidDistSquare(bestSize, s))) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    /**
     * Query the surface for its currently configured default buffer size.
     * @param surface a non-{@code null} {@code Surface}
     * @return the width and height of the surface
     *
     * @throws NullPointerException if the {@code surface} was {@code null}
     * @throws BufferQueueAbandonedException if the {@code surface} was invalid
     */
    public static Size getSurfaceSize(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);

        int[] dimens = new int[2];
        LegacyExceptionUtils.throwOnError(nativeDetectSurfaceDimens(surface, /*out*/dimens));

        return new Size(dimens[0], dimens[1]);
    }

    public static boolean isFlexibleConsumer(Surface output) {
        int usageFlags = detectSurfaceUsageFlags(output);

        // Keep up to date with allowed consumer types in
        // frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp
        int disallowedFlags = GRALLOC_USAGE_HW_VIDEO_ENCODER | GRALLOC_USAGE_RENDERSCRIPT;
        int allowedFlags = GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_OFTEN |
            GRALLOC_USAGE_HW_COMPOSER;
        boolean flexibleConsumer = ((usageFlags & disallowedFlags) == 0 &&
                (usageFlags & allowedFlags) != 0);
        return flexibleConsumer;
    }

    public static boolean isPreviewConsumer(Surface output) {
        int usageFlags = detectSurfaceUsageFlags(output);
        int disallowedFlags = GRALLOC_USAGE_HW_VIDEO_ENCODER | GRALLOC_USAGE_RENDERSCRIPT |
                GRALLOC_USAGE_SW_READ_OFTEN;
        int allowedFlags = GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_COMPOSER |
                GRALLOC_USAGE_HW_RENDER;
        boolean previewConsumer = ((usageFlags & disallowedFlags) == 0 &&
                (usageFlags & allowedFlags) != 0);
        int surfaceFormat = ImageFormat.UNKNOWN;
        try {
            surfaceFormat = detectSurfaceType(output);
        } catch(BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("Surface was abandoned", e);
        }

        return previewConsumer;
    }

    public static boolean isVideoEncoderConsumer(Surface output) {
        int usageFlags = detectSurfaceUsageFlags(output);
        int disallowedFlags = GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_COMPOSER |
                GRALLOC_USAGE_RENDERSCRIPT | GRALLOC_USAGE_SW_READ_OFTEN;
        int allowedFlags = GRALLOC_USAGE_HW_VIDEO_ENCODER;
        boolean videoEncoderConsumer = ((usageFlags & disallowedFlags) == 0 &&
                (usageFlags & allowedFlags) != 0);

        int surfaceFormat = ImageFormat.UNKNOWN;
        try {
            surfaceFormat = detectSurfaceType(output);
        } catch(BufferQueueAbandonedException e) {
            throw new IllegalArgumentException("Surface was abandoned", e);
        }

        return videoEncoderConsumer;
    }

    /**
     * Query the surface for its currently configured usage flags
     */
    static int detectSurfaceUsageFlags(Surface surface) {
        checkNotNull(surface);
        return nativeDetectSurfaceUsageFlags(surface);
    }

    /**
     * Query the surface for its currently configured format
     */
    public static int detectSurfaceType(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);
        int surfaceType = nativeDetectSurfaceType(surface);

        // TODO: remove this override since the default format should be
        // ImageFormat.PRIVATE. b/9487482
        if ((surfaceType >= LegacyMetadataMapper.HAL_PIXEL_FORMAT_RGBA_8888 &&
                surfaceType <= LegacyMetadataMapper.HAL_PIXEL_FORMAT_BGRA_8888)) {
            surfaceType = ImageFormat.PRIVATE;
        }

        return LegacyExceptionUtils.throwOnError(surfaceType);
    }

    /**
     * Query the surface for its currently configured dataspace
     */
    public static int detectSurfaceDataspace(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);
        return LegacyExceptionUtils.throwOnError(nativeDetectSurfaceDataspace(surface));
    }

    static void connectSurface(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);

        LegacyExceptionUtils.throwOnError(nativeConnectSurface(surface));
    }

    static void disconnectSurface(Surface surface) throws BufferQueueAbandonedException {
        if (surface == null) return;

        LegacyExceptionUtils.throwOnError(nativeDisconnectSurface(surface));
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

    public static long getSurfaceId(Surface surface) throws BufferQueueAbandonedException {
        checkNotNull(surface);
        try {
            return nativeGetSurfaceId(surface);
        } catch (IllegalArgumentException e) {
            throw new BufferQueueAbandonedException();
        }
    }

    static List<Long> getSurfaceIds(SparseArray<Surface> surfaces)
            throws BufferQueueAbandonedException {
        if (surfaces == null) {
            throw new NullPointerException("Null argument surfaces");
        }
        List<Long> surfaceIds = new ArrayList<>();
        int count = surfaces.size();
        for (int i = 0; i < count; i++) {
            long id = getSurfaceId(surfaces.valueAt(i));
            if (id == 0) {
                throw new IllegalStateException(
                        "Configured surface had null native GraphicBufferProducer pointer!");
            }
            surfaceIds.add(id);
        }
        return surfaceIds;
    }

    static List<Long> getSurfaceIds(Collection<Surface> surfaces)
            throws BufferQueueAbandonedException {
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
        long id = 0;
        try {
            id = getSurfaceId(s);
        } catch (BufferQueueAbandonedException e) {
            // If surface is abandoned, return false.
            return false;
        }
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

    static void setScalingMode(Surface surface, int mode)
            throws BufferQueueAbandonedException {
        checkNotNull(surface);
        LegacyExceptionUtils.throwOnError(nativeSetScalingMode(surface, mode));
    }


    private static native int nativeDetectSurfaceType(Surface surface);

    private static native int nativeDetectSurfaceDataspace(Surface surface);

    private static native int nativeDetectSurfaceDimens(Surface surface,
            /*out*/int[/*2*/] dimens);

    private static native int nativeConnectSurface(Surface surface);

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

    private static native int nativeDetectSurfaceUsageFlags(Surface surface);

    private static native int nativeSetScalingMode(Surface surface, int scalingMode);

    private static native int nativeDisconnectSurface(Surface surface);

    static native int nativeGetJpegFooterSize();
}
