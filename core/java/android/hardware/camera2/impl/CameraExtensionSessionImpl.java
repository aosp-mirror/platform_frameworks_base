/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.camera2.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.extension.CaptureBundle;
import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.IProcessResultImpl;
import android.hardware.camera2.extension.IRequestUpdateProcessorImpl;
import android.hardware.camera2.extension.LatencyPair;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.ExtensionSessionStatsAggregator;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public final class CameraExtensionSessionImpl extends CameraExtensionSession {
    private static final int PREVIEW_QUEUE_SIZE = 10;
    private static final String TAG = "CameraExtensionSessionImpl";

    private final Executor mExecutor;
    private final CameraDevice mCameraDevice;
    private final IImageCaptureExtenderImpl mImageExtender;
    private final IPreviewExtenderImpl mPreviewExtender;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final StateCallback mCallbacks;
    private final List<Size> mSupportedPreviewSizes;
    private final InitializeSessionHandler mInitializeHandler;
    private final int mSessionId;
    private final Set<CaptureRequest.Key> mSupportedRequestKeys;
    private final Set<CaptureResult.Key> mSupportedResultKeys;
    private final ExtensionSessionStatsAggregator mStatsAggregator;
    private IBinder mToken = null;
    private boolean mCaptureResultsSupported;

    private CameraCaptureSession mCaptureSession = null;
    private Surface mCameraRepeatingSurface, mClientRepeatingRequestSurface;
    private Surface mCameraBurstSurface, mClientCaptureSurface;
    private Surface mClientPostviewSurface;
    private ImageReader mRepeatingRequestImageReader = null;
    private ImageReader mBurstCaptureImageReader = null;
    private ImageReader mStubCaptureImageReader = null;
    private ImageWriter mRepeatingRequestImageWriter = null;
    private CameraOutputImageCallback mRepeatingRequestImageCallback = null;
    private CameraOutputImageCallback mBurstCaptureImageCallback = null;

    private CameraExtensionJpegProcessor mImageJpegProcessor = null;
    private ICaptureProcessorImpl mImageProcessor = null;
    private CameraExtensionForwardProcessor mPreviewImageProcessor = null;
    private IRequestUpdateProcessorImpl mPreviewRequestUpdateProcessor = null;
    private int mPreviewProcessorType = IPreviewExtenderImpl.PROCESSOR_TYPE_NONE;

    private boolean mInitialized;
    private boolean mSessionClosed;
    // Enable/Disable internal preview/(repeating request). Extensions expect
    // that preview/(repeating request) is enabled and active at any point in time.
    // In case the client doesn't explicitly enable repeating requests, the framework
    // will do so internally.
    private boolean mInternalRepeatingRequestEnabled = true;
    private int mExtensionType;

    private final Context mContext;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock;

    private static int nativeGetSurfaceFormat(Surface surface) {
        return SurfaceUtils.getSurfaceFormat(surface);
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public static CameraExtensionSessionImpl createCameraExtensionSession(
            @NonNull android.hardware.camera2.impl.CameraDeviceImpl cameraDevice,
            @NonNull Map<String, CameraCharacteristics> characteristicsMap,
            @NonNull Context ctx,
            @NonNull ExtensionSessionConfiguration config,
            int sessionId,
            @NonNull IBinder token)
            throws CameraAccessException, RemoteException {
        String cameraId = cameraDevice.getId();
        CameraExtensionCharacteristics extensionChars = new CameraExtensionCharacteristics(ctx,
                cameraId, characteristicsMap);

        if (!CameraExtensionCharacteristics.isExtensionSupported(cameraDevice.getId(),
                config.getExtension(),
                CameraExtensionUtils.getCharacteristicsMapNative(characteristicsMap))) {
            throw new UnsupportedOperationException("Unsupported extension type: " +
                    config.getExtension());
        }

        if (config.getOutputConfigurations().isEmpty() ||
                config.getOutputConfigurations().size() > 2) {
            throw new IllegalArgumentException("Unexpected amount of output surfaces, received: " +
                    config.getOutputConfigurations().size() + " expected <= 2");
        }

        for (OutputConfiguration c : config.getOutputConfigurations()) {
            if (c.getDynamicRangeProfile() != DynamicRangeProfiles.STANDARD) {
                throw new IllegalArgumentException("Unsupported dynamic range profile: " +
                        c.getDynamicRangeProfile());
            }
            if (c.getStreamUseCase() !=
                    CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT) {
                throw new IllegalArgumentException("Unsupported stream use case: " +
                        c.getStreamUseCase());
            }
        }

        Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                CameraExtensionCharacteristics.initializeExtension(config.getExtension());

        int suitableSurfaceCount = 0;
        List<Size> supportedPreviewSizes = extensionChars.getExtensionSupportedSizes(
                config.getExtension(), SurfaceTexture.class);
        Surface repeatingRequestSurface = CameraExtensionUtils.getRepeatingRequestSurface(
                config.getOutputConfigurations(), supportedPreviewSizes);
        if (repeatingRequestSurface != null) {
            suitableSurfaceCount++;
        }

        HashMap<Integer, List<Size>> supportedCaptureSizes = new HashMap<>();
        for (int format : CameraExtensionUtils.SUPPORTED_CAPTURE_OUTPUT_FORMATS) {
            List<Size> supportedSizes = extensionChars.getExtensionSupportedSizes(
                    config.getExtension(), format);
            if (supportedSizes != null) {
                supportedCaptureSizes.put(format, supportedSizes);
            }
        }
        Surface burstCaptureSurface = CameraExtensionUtils.getBurstCaptureSurface(
                config.getOutputConfigurations(), supportedCaptureSizes);
        if (burstCaptureSurface != null) {
            suitableSurfaceCount++;
        }

        if (suitableSurfaceCount != config.getOutputConfigurations().size()) {
            throw new IllegalArgumentException("One or more unsupported output surfaces found!");
        }

        Surface postviewSurface = null;
        if (burstCaptureSurface != null && config.getPostviewOutputConfiguration() != null) {
            CameraExtensionUtils.SurfaceInfo burstCaptureSurfaceInfo =
                    CameraExtensionUtils.querySurface(burstCaptureSurface);
            Size burstCaptureSurfaceSize =
                    new Size(burstCaptureSurfaceInfo.mWidth, burstCaptureSurfaceInfo.mHeight);
            HashMap<Integer, List<Size>> supportedPostviewSizes = new HashMap<>();
            for (int format : CameraExtensionUtils.SUPPORTED_CAPTURE_OUTPUT_FORMATS) {
                List<Size> supportedSizesPostview = extensionChars.getPostviewSupportedSizes(
                        config.getExtension(), burstCaptureSurfaceSize, format);
                if (supportedSizesPostview != null) {
                    supportedPostviewSizes.put(format, supportedSizesPostview);
                }
            }

            postviewSurface = CameraExtensionUtils.getPostviewSurface(
                        config.getPostviewOutputConfiguration(), supportedPostviewSizes,
                        burstCaptureSurfaceInfo.mFormat);

            if (postviewSurface == null) {
                throw new IllegalArgumentException("Unsupported output surface for postview!");
            }
        }

        extenders.first.init(cameraId, characteristicsMap.get(cameraId).getNativeMetadata());
        extenders.first.onInit(token, cameraId,
                characteristicsMap.get(cameraId).getNativeMetadata());
        extenders.second.init(cameraId, characteristicsMap.get(cameraId).getNativeMetadata());
        extenders.second.onInit(token, cameraId,
                characteristicsMap.get(cameraId).getNativeMetadata());

        CameraExtensionSessionImpl session = new CameraExtensionSessionImpl(
                ctx,
                extenders.second,
                extenders.first,
                supportedPreviewSizes,
                cameraDevice,
                repeatingRequestSurface,
                burstCaptureSurface,
                postviewSurface,
                config.getStateCallback(),
                config.getExecutor(),
                sessionId,
                token,
                extensionChars.getAvailableCaptureRequestKeys(config.getExtension()),
                extensionChars.getAvailableCaptureResultKeys(config.getExtension()),
                config.getExtension());

        session.mStatsAggregator.setClientName(ctx.getOpPackageName());
        session.mStatsAggregator.setExtensionType(config.getExtension());

        session.initialize();

        return session;
    }

    public CameraExtensionSessionImpl(Context ctx, @NonNull IImageCaptureExtenderImpl imageExtender,
            @NonNull IPreviewExtenderImpl previewExtender,
            @NonNull List<Size> previewSizes,
            @NonNull android.hardware.camera2.impl.CameraDeviceImpl cameraDevice,
            @Nullable Surface repeatingRequestSurface,
            @Nullable Surface burstCaptureSurface,
            @Nullable Surface postviewSurface,
            @NonNull StateCallback callback,
            @NonNull Executor executor,
            int sessionId,
            @NonNull IBinder token,
            @NonNull Set<CaptureRequest.Key> requestKeys,
            @Nullable Set<CaptureResult.Key> resultKeys,
            int extension) {
        mContext = ctx;
        mImageExtender = imageExtender;
        mPreviewExtender = previewExtender;
        mCameraDevice = cameraDevice;
        mCallbacks = callback;
        mExecutor = executor;
        mClientRepeatingRequestSurface = repeatingRequestSurface;
        mClientCaptureSurface = burstCaptureSurface;
        mClientPostviewSurface = postviewSurface;
        mSupportedPreviewSizes = previewSizes;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mInitialized = false;
        mSessionClosed = false;
        mInitializeHandler = new InitializeSessionHandler();
        mSessionId = sessionId;
        mToken = token;
        mSupportedRequestKeys = requestKeys;
        mSupportedResultKeys = resultKeys;
        mCaptureResultsSupported = !resultKeys.isEmpty();
        mInterfaceLock = cameraDevice.mInterfaceLock;
        mExtensionType = extension;

        mStatsAggregator = new ExtensionSessionStatsAggregator(mCameraDevice.getId(),
                /*isAdvanced=*/false);
    }

    private void initializeRepeatingRequestPipeline() throws RemoteException {
        CameraExtensionUtils.SurfaceInfo repeatingSurfaceInfo =
                new CameraExtensionUtils.SurfaceInfo();
        mPreviewProcessorType = mPreviewExtender.getProcessorType();
        if (mClientRepeatingRequestSurface != null) {
            repeatingSurfaceInfo = CameraExtensionUtils.querySurface(
                    mClientRepeatingRequestSurface);
        } else {
            // Make the intermediate surface behave as any regular 'SurfaceTexture'
            CameraExtensionUtils.SurfaceInfo captureSurfaceInfo = CameraExtensionUtils.querySurface(
                    mClientCaptureSurface);
            Size captureSize = new Size(captureSurfaceInfo.mWidth, captureSurfaceInfo.mHeight);
            Size previewSize = findSmallestAspectMatchedSize(mSupportedPreviewSizes, captureSize);
            repeatingSurfaceInfo.mWidth = previewSize.getWidth();
            repeatingSurfaceInfo.mHeight = previewSize.getHeight();
            repeatingSurfaceInfo.mUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
        }

        if (mPreviewProcessorType == IPreviewExtenderImpl.PROCESSOR_TYPE_IMAGE_PROCESSOR) {
            try {
                mPreviewImageProcessor = new CameraExtensionForwardProcessor(
                        mPreviewExtender.getPreviewImageProcessor(), repeatingSurfaceInfo.mFormat,
                        repeatingSurfaceInfo.mUsage, mHandler);
            } catch (ClassCastException e) {
                throw new UnsupportedOperationException("Failed casting preview processor!");
            }
            mPreviewImageProcessor.onImageFormatUpdate(
                    CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT);
            mPreviewImageProcessor.onResolutionUpdate(new Size(repeatingSurfaceInfo.mWidth,
                    repeatingSurfaceInfo.mHeight));
            mPreviewImageProcessor.onOutputSurface(null, -1);
            mRepeatingRequestImageReader = ImageReader.newInstance(repeatingSurfaceInfo.mWidth,
                    repeatingSurfaceInfo.mHeight,
                    CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT, PREVIEW_QUEUE_SIZE,
                    repeatingSurfaceInfo.mUsage);
            mCameraRepeatingSurface = mRepeatingRequestImageReader.getSurface();
        } else if (mPreviewProcessorType ==
                IPreviewExtenderImpl.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY) {
            try {
                mPreviewRequestUpdateProcessor = mPreviewExtender.getRequestUpdateProcessor();
            } catch (ClassCastException e) {
                throw new UnsupportedOperationException("Failed casting preview processor!");
            }
            mRepeatingRequestImageReader = ImageReader.newInstance(repeatingSurfaceInfo.mWidth,
                    repeatingSurfaceInfo.mHeight,
                    CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT,
                    PREVIEW_QUEUE_SIZE, repeatingSurfaceInfo.mUsage);
            mCameraRepeatingSurface = mRepeatingRequestImageReader.getSurface();
            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = repeatingSurfaceInfo.mWidth;
            sz.height = repeatingSurfaceInfo.mHeight;
            mPreviewRequestUpdateProcessor.onResolutionUpdate(sz);
            mPreviewRequestUpdateProcessor.onImageFormatUpdate(
                    CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT);
        } else {
            mRepeatingRequestImageReader = ImageReader.newInstance(repeatingSurfaceInfo.mWidth,
                    repeatingSurfaceInfo.mHeight,
                    CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT,
                    PREVIEW_QUEUE_SIZE, repeatingSurfaceInfo.mUsage);
            mCameraRepeatingSurface = mRepeatingRequestImageReader.getSurface();
        }
        mRepeatingRequestImageCallback = new CameraOutputImageCallback(
                mRepeatingRequestImageReader, true /*pruneOlderBuffers*/);
        mRepeatingRequestImageReader
                .setOnImageAvailableListener(mRepeatingRequestImageCallback, mHandler);
    }

    private void initializeBurstCapturePipeline() throws RemoteException {
        mImageProcessor = mImageExtender.getCaptureProcessor();
        if ((mImageProcessor == null) && (mImageExtender.getMaxCaptureStage() != 1)) {
            throw new UnsupportedOperationException("Multiple stages expected without" +
                    " a valid capture processor!");
        }

        if (mImageProcessor != null) {
            if (mClientCaptureSurface != null) {
                CameraExtensionUtils.SurfaceInfo surfaceInfo = CameraExtensionUtils.querySurface(
                        mClientCaptureSurface);
                if (surfaceInfo.mFormat == ImageFormat.JPEG) {
                    mImageJpegProcessor = new CameraExtensionJpegProcessor(mImageProcessor);
                    mImageProcessor = mImageJpegProcessor;
                }
                mBurstCaptureImageReader = ImageReader.newInstance(surfaceInfo.mWidth,
                        surfaceInfo.mHeight, CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT,
                        mImageExtender.getMaxCaptureStage());
            } else {
                // The client doesn't intend to trigger multi-frame capture, however the
                // image extender still needs to get initialized and the camera still capture
                // stream configured for the repeating request processing to work.
                mBurstCaptureImageReader = ImageReader.newInstance(
                        mRepeatingRequestImageReader.getWidth(),
                        mRepeatingRequestImageReader.getHeight(),
                        CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT, 1);
                // The still capture output is not going to be used but we still need a valid
                // surface to register.
                mStubCaptureImageReader = ImageReader.newInstance(
                        mRepeatingRequestImageReader.getWidth(),
                        mRepeatingRequestImageReader.getHeight(),
                        CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT, 1);
                mImageProcessor.onOutputSurface(mStubCaptureImageReader.getSurface(),
                        CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT);
            }

            mBurstCaptureImageCallback = new CameraOutputImageCallback(mBurstCaptureImageReader,
                    false /*pruneOlderBuffers*/);
            mBurstCaptureImageReader.setOnImageAvailableListener(mBurstCaptureImageCallback,
                    mHandler);
            mCameraBurstSurface = mBurstCaptureImageReader.getSurface();
            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = mBurstCaptureImageReader.getWidth();
            sz.height = mBurstCaptureImageReader.getHeight();

            if (mClientPostviewSurface != null) {
                CameraExtensionUtils.SurfaceInfo postviewSurfaceInfo =
                        CameraExtensionUtils.querySurface(mClientPostviewSurface);
                android.hardware.camera2.extension.Size postviewSize =
                        new android.hardware.camera2.extension.Size();
                postviewSize.width = postviewSurfaceInfo.mWidth;
                postviewSize.height = postviewSurfaceInfo.mHeight;
                mImageProcessor.onResolutionUpdate(sz, postviewSize);
            } else {
                mImageProcessor.onResolutionUpdate(sz, null);
            }

            mImageProcessor.onImageFormatUpdate(mBurstCaptureImageReader.getImageFormat());
        } else {
            if (mClientCaptureSurface != null) {
                // Redirect camera output directly in to client output surface
                mCameraBurstSurface = mClientCaptureSurface;
            } else {
                // The client doesn't intend to trigger multi-frame capture, however the
                // image extender still needs to get initialized and the camera still capture
                // stream configured for the repeating request processing to work.
                mBurstCaptureImageReader = ImageReader.newInstance(
                        mRepeatingRequestImageReader.getWidth(),
                        mRepeatingRequestImageReader.getHeight(),
                        // Camera devices accept only Jpeg output if the image processor is null
                        ImageFormat.JPEG, 1);
                mCameraBurstSurface = mBurstCaptureImageReader.getSurface();
            }
        }
    }

    private void finishPipelineInitialization() throws RemoteException {
        if (mClientRepeatingRequestSurface != null) {
            if (mPreviewProcessorType == IPreviewExtenderImpl.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY) {
                mPreviewRequestUpdateProcessor.onOutputSurface(mClientRepeatingRequestSurface,
                        nativeGetSurfaceFormat(mClientRepeatingRequestSurface));
                mRepeatingRequestImageWriter = ImageWriter.newInstance(
                        mClientRepeatingRequestSurface,
                        PREVIEW_QUEUE_SIZE,
                        CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT);
            } else if (mPreviewProcessorType == IPreviewExtenderImpl.PROCESSOR_TYPE_NONE) {
                mRepeatingRequestImageWriter = ImageWriter.newInstance(
                        mClientRepeatingRequestSurface,
                        PREVIEW_QUEUE_SIZE,
                        CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT);
            }
        }
        if ((mImageProcessor != null) && (mClientCaptureSurface != null)) {
            if (mClientPostviewSurface != null) {
                mImageProcessor.onPostviewOutputSurface(mClientPostviewSurface);
            }

            CameraExtensionUtils.SurfaceInfo surfaceInfo = CameraExtensionUtils.querySurface(
                    mClientCaptureSurface);
            mImageProcessor.onOutputSurface(mClientCaptureSurface, surfaceInfo.mFormat);
        }
    }

    /**
     * @hide
     */
    public synchronized void initialize() throws CameraAccessException, RemoteException {
        if (mInitialized) {
            Log.d(TAG,
                    "Session already initialized");
            return;
        }
        int previewSessionType = mPreviewExtender.getSessionType();
        int imageSessionType = mImageExtender.getSessionType();
        if (previewSessionType != imageSessionType) {
            throw new IllegalStateException("Preview extender session type: " + previewSessionType +
                "and image extender session type: " + imageSessionType + " mismatch!");
        }
        int sessionType = SessionConfiguration.SESSION_REGULAR;
        if ((previewSessionType != -1) &&
                (previewSessionType != SessionConfiguration.SESSION_HIGH_SPEED)) {
            sessionType = previewSessionType;
            Log.v(TAG, "Using session type: " + sessionType);
        }

        ArrayList<CaptureStageImpl> sessionParamsList = new ArrayList<>();
        ArrayList<OutputConfiguration> outputList = new ArrayList<>();
        initializeRepeatingRequestPipeline();
        OutputConfiguration previewOutput = new OutputConfiguration(mCameraRepeatingSurface);
        // The extension processing logic needs to be able to match images to capture results via
        // image and result timestamps.
        previewOutput.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR);
        previewOutput.setReadoutTimestampEnabled(false);
        outputList.add(previewOutput);
        CaptureStageImpl previewSessionParams = mPreviewExtender.onPresetSession();
        if (previewSessionParams != null) {
            sessionParamsList.add(previewSessionParams);
        }
        initializeBurstCapturePipeline();
        OutputConfiguration captureOutput = new OutputConfiguration(mCameraBurstSurface);
        captureOutput.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR);
        captureOutput.setReadoutTimestampEnabled(false);
        outputList.add(captureOutput);
        CaptureStageImpl stillCaptureSessionParams = mImageExtender.onPresetSession();
        if (stillCaptureSessionParams != null) {
            sessionParamsList.add(stillCaptureSessionParams);
        }

        SessionConfiguration sessionConfig = new SessionConfiguration(
                sessionType,
                outputList,
                new CameraExtensionUtils.HandlerExecutor(mHandler),
                new SessionStateHandler());

        if (!sessionParamsList.isEmpty()) {
            CaptureRequest sessionParamRequest = createRequest(mCameraDevice, sessionParamsList,
                    null, CameraDevice.TEMPLATE_PREVIEW);
            sessionConfig.setSessionParameters(sessionParamRequest);
        }

        mCameraDevice.createCaptureSession(sessionConfig);
    }

    @Override
    public @NonNull CameraDevice getDevice() {
        synchronized (mInterfaceLock) {
            return mCameraDevice;
        }
    }

    @Override
    public StillCaptureLatency getRealtimeStillCaptureLatency() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            try {
                LatencyPair latency = mImageExtender.getRealtimeCaptureLatency();
                if (latency != null) {
                    return new StillCaptureLatency(latency.first, latency.second);
                }

                return null;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to query realtime latency! Extension service does not "
                        + "respond");
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }
        }
    }

    @Override
    public int setRepeatingRequest(@NonNull CaptureRequest request,
                                   @NonNull Executor executor,
                                   @NonNull ExtensionCaptureCallback listener)
            throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            if (mClientRepeatingRequestSurface == null) {
                throw new IllegalArgumentException("No registered preview surface");
            }

            if (!request.containsTarget(mClientRepeatingRequestSurface) ||
                    (request.getTargets().size() != 1)) {
                throw new IllegalArgumentException("Invalid repeating request output target!");
            }

            mInternalRepeatingRequestEnabled = false;
            try {
                return setRepeatingRequest(mPreviewExtender.getCaptureStage(),
                        new PreviewRequestHandler(request, executor, listener,
                                mRepeatingRequestImageCallback), request);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set repeating request! Extension service does not "
                        + "respond");
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }
        }
    }

    private ArrayList<CaptureStageImpl> compileInitialRequestList() {
        ArrayList<CaptureStageImpl> captureStageList = new ArrayList<>();
        try {
            CaptureStageImpl initialPreviewParams = mPreviewExtender.onEnableSession();
            if (initialPreviewParams != null) {
                captureStageList.add(initialPreviewParams);
            }

            CaptureStageImpl initialStillCaptureParams = mImageExtender.onEnableSession();
            if (initialStillCaptureParams != null) {
                captureStageList.add(initialStillCaptureParams);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to initialize session parameters! Extension service does not"
                    + " respond!");
        }

        return captureStageList;
    }

    private List<CaptureRequest> createBurstRequest(CameraDevice cameraDevice,
            List<CaptureStageImpl> captureStageList, CaptureRequest clientRequest,
            Surface target, int captureTemplate, Map<CaptureRequest, Integer> captureMap) {
        CaptureRequest.Builder requestBuilder;
        ArrayList<CaptureRequest> ret = new ArrayList<>();
        for (CaptureStageImpl captureStage : captureStageList) {
            try {
                requestBuilder = cameraDevice.createCaptureRequest(captureTemplate);
            } catch (CameraAccessException e) {
                return null;
            }

            // This will guarantee that client configured
            // parameters always have the highest priority.
            for (CaptureRequest.Key requestKey : mSupportedRequestKeys){
                Object value = clientRequest.get(requestKey);
                if (value != null) {
                    captureStage.parameters.set(requestKey, value);
                }
            }

            requestBuilder.addTarget(target);
            CaptureRequest request = requestBuilder.build();
            CameraMetadataNative.update(request.getNativeMetadata(), captureStage.parameters);
            ret.add(request);
            if (captureMap != null) {
                captureMap.put(request, captureStage.id);
            }
        }

        return ret;
    }

    private CaptureRequest createRequest(CameraDevice cameraDevice,
            List<CaptureStageImpl> captureStageList, Surface target, int captureTemplate,
            CaptureRequest clientRequest) throws CameraAccessException {
        CaptureRequest.Builder requestBuilder;
        requestBuilder = cameraDevice.createCaptureRequest(captureTemplate);
        if (target != null) {
            requestBuilder.addTarget(target);
        }

        CaptureRequest ret = requestBuilder.build();
        CameraMetadataNative nativeMeta = ret.getNativeMetadata();
        for (CaptureStageImpl captureStage : captureStageList) {
            if (captureStage != null) {
                CameraMetadataNative.update(nativeMeta, captureStage.parameters);
            }
        }

        if (clientRequest != null) {
            // This will guarantee that client configured
            // parameters always have the highest priority.
            for (CaptureRequest.Key requestKey : mSupportedRequestKeys) {
                Object value = clientRequest.get(requestKey);
                if (value != null) {
                    nativeMeta.set(requestKey, value);
                }
            }
        }

        return ret;
    }

    private CaptureRequest createRequest(CameraDevice cameraDevice,
            List<CaptureStageImpl> captureStageList,
            Surface target,
            int captureTemplate) throws CameraAccessException {
        return createRequest(cameraDevice, captureStageList, target, captureTemplate,
                /*clientRequest*/ null);
    }

    @Override
    public int capture(@NonNull CaptureRequest request,
                       @NonNull Executor executor,
                       @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        if (!mInitialized) {
            throw new IllegalStateException("Uninitialized component");
        }

        validateCaptureRequestTargets(request);

        int seqId = -1;
        if ((mClientCaptureSurface != null) && request.containsTarget(mClientCaptureSurface)) {
            HashMap<CaptureRequest, Integer> requestMap = new HashMap<>();
            List<CaptureRequest> burstRequest;
            try {
                burstRequest = createBurstRequest(mCameraDevice,
                        mImageExtender.getCaptureStages(), request, mCameraBurstSurface,
                        CameraDevice.TEMPLATE_STILL_CAPTURE, requestMap);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to initialize internal burst request! Extension service does"
                        + " not respond!");
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }
            if (burstRequest == null) {
                throw new UnsupportedOperationException(
                        "Failed to create still capture burst request");
            }

            seqId =  mCaptureSession.captureBurstRequests(burstRequest,
                    new CameraExtensionUtils.HandlerExecutor(mHandler),
                    new BurstRequestHandler(request, executor, listener, requestMap,
                            mBurstCaptureImageCallback));
        } else if ((mClientRepeatingRequestSurface != null) &&
                request.containsTarget(mClientRepeatingRequestSurface)) {

            CaptureRequest captureRequest = null;
            try {
                ArrayList<CaptureStageImpl> captureStageList = new ArrayList<>();
                captureStageList.add(mPreviewExtender.getCaptureStage());

                captureRequest = createRequest(mCameraDevice, captureStageList,
                        mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW, request);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to initialize capture request! Extension service does"
                        + " not respond!");
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }

            seqId = mCaptureSession.capture(captureRequest, new PreviewRequestHandler(request,
                    executor, listener, mRepeatingRequestImageCallback, true /*singleCapture*/),
                    mHandler);
        } else {
            throw new IllegalArgumentException("Capture request to unknown output surface!");
        }

        return seqId;
    }

    private void validateCaptureRequestTargets(@NonNull CaptureRequest request) {
        if (request.getTargets().size() == 1) {
            boolean containsCaptureTarget =
                    mClientCaptureSurface != null && request.containsTarget(mClientCaptureSurface);
            boolean containsRepeatingTarget =
                    mClientRepeatingRequestSurface != null &&
                    request.containsTarget(mClientRepeatingRequestSurface);

            if (!containsCaptureTarget && !containsRepeatingTarget) {
                throw new IllegalArgumentException("Target output combination requested is " +
                        "not supported!");
            }
        }

        if ((request.getTargets().size() == 2) &&
                (!request.getTargets().containsAll(Arrays.asList(mClientCaptureSurface,
                mClientPostviewSurface)))) {
            throw new IllegalArgumentException("Target output combination requested is " +
                    "not supported!");
        }

        if (request.getTargets().size() > 2) {
            throw new IllegalArgumentException("Target output combination requested is " +
                    "not supported!");
        }
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            mInternalRepeatingRequestEnabled = true;
            mCaptureSession.stopRepeating();
        }
    }

    @Override
    public void close() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                mInternalRepeatingRequestEnabled = false;
                try {
                    mCaptureSession.stopRepeating();
                } catch (IllegalStateException e) {
                    // OK: already be closed, nothing else to do
                    mSessionClosed = true;
                }

                ArrayList<CaptureStageImpl> captureStageList = new ArrayList<>();
                try {
                    CaptureStageImpl disableParams = mPreviewExtender.onDisableSession();
                    if (disableParams != null) {
                        captureStageList.add(disableParams);
                    }

                    CaptureStageImpl disableStillCaptureParams =
                            mImageExtender.onDisableSession();
                    if (disableStillCaptureParams != null) {
                        captureStageList.add(disableStillCaptureParams);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to disable extension! Extension service does not "
                            + "respond!");
                }
                if (!captureStageList.isEmpty() && !mSessionClosed) {
                    CaptureRequest disableRequest = createRequest(mCameraDevice, captureStageList,
                            mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW);
                    mCaptureSession.capture(disableRequest,
                            new CloseRequestHandler(mRepeatingRequestImageCallback), mHandler);
                }

                mSessionClosed = true;
                mStatsAggregator.commit(/*isFinal*/true); // Commit stats before closing session
                mCaptureSession.close();
            }
        }
    }

    /**
     * Called by {@link CameraDeviceImpl} right before the capture session is closed, and before it
     * calls {@link #release}
     *
     * @hide
     */
    public void commitStats() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                // Only commit stats if a capture session was initialized
                mStatsAggregator.commit(/*isFinal*/true);
            }
        }
    }

    private void setInitialCaptureRequest(List<CaptureStageImpl> captureStageList,
                                          InitialRequestHandler requestHandler)
            throws CameraAccessException {
        CaptureRequest initialRequest = createRequest(mCameraDevice,
                captureStageList, mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW);
        mCaptureSession.capture(initialRequest, requestHandler, mHandler);
    }

    private int setRepeatingRequest(CaptureStageImpl captureStage,
            CameraCaptureSession.CaptureCallback requestHandler) throws CameraAccessException {
        return setRepeatingRequest(captureStage, requestHandler, /*clientRequest*/ null);
    }

    private int setRepeatingRequest(CaptureStageImpl captureStage,
            CameraCaptureSession.CaptureCallback requestHandler, CaptureRequest clientRequest)
            throws CameraAccessException {
        ArrayList<CaptureStageImpl> captureStageList = new ArrayList<>();
        captureStageList.add(captureStage);
        CaptureRequest repeatingRequest = createRequest(mCameraDevice, captureStageList,
                mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW, clientRequest);
        return mCaptureSession.setSingleRepeatingRequest(repeatingRequest,
                new CameraExtensionUtils.HandlerExecutor(mHandler), requestHandler);
    }

    /** @hide */
    public void release(boolean skipCloseNotification) {
        boolean notifyClose = false;

        synchronized (mInterfaceLock) {
            mInternalRepeatingRequestEnabled = false;
            mHandlerThread.quit();

            try {
                if (!mSessionClosed) {
                    // return value is omitted. nothing can do after session is closed.
                    mPreviewExtender.onDisableSession();
                    mImageExtender.onDisableSession();
                }
                mPreviewExtender.onDeInit(mToken);
                mImageExtender.onDeInit(mToken);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to release extensions! Extension service does not"
                        + " respond!");
            }

            if (mToken != null) {
                if (mInitialized || (mCaptureSession != null)) {
                    notifyClose = true;
                    CameraExtensionCharacteristics.releaseSession(mExtensionType);
                }
                CameraExtensionCharacteristics.unregisterClient(mContext, mToken, mExtensionType);
            }
            mInitialized = false;
            mToken = null;

            if (mRepeatingRequestImageCallback != null) {
                mRepeatingRequestImageCallback.close();
                mRepeatingRequestImageCallback = null;
            }

            if (mRepeatingRequestImageReader != null) {
                mRepeatingRequestImageReader.close();
                mRepeatingRequestImageReader = null;
            }

            if (mBurstCaptureImageCallback != null) {
                mBurstCaptureImageCallback.close();
                mBurstCaptureImageCallback = null;
            }

            if (mBurstCaptureImageReader != null) {
                mBurstCaptureImageReader.close();
                mBurstCaptureImageReader = null;
            }

            if (mStubCaptureImageReader != null) {
                mStubCaptureImageReader.close();
                mStubCaptureImageReader = null;
            }

            if (mRepeatingRequestImageWriter != null) {
                mRepeatingRequestImageWriter.close();
                mRepeatingRequestImageWriter = null;
            }

            if (mPreviewImageProcessor != null) {
                mPreviewImageProcessor.close();
                mPreviewImageProcessor = null;
            }

            if (mImageJpegProcessor != null) {
                mImageJpegProcessor.close();
                mImageJpegProcessor = null;
            }

            mCaptureSession = null;
            mImageProcessor = null;
            mCameraRepeatingSurface = mClientRepeatingRequestSurface = null;
            mCameraBurstSurface = mClientCaptureSurface = null;
            mClientPostviewSurface = null;
        }

        if (notifyClose && !skipCloseNotification) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallbacks.onClosed(CameraExtensionSessionImpl.this));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void notifyConfigurationFailure() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                return;
            }
        }

        release(true /*skipCloseNotification*/);

        final long ident = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(
                    () -> mCallbacks.onConfigureFailed(CameraExtensionSessionImpl.this));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void notifyConfigurationSuccess() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                return;
            } else {
                mInitialized = true;
            }
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(() -> mCallbacks.onConfigured(CameraExtensionSessionImpl.this));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private class SessionStateHandler extends
            android.hardware.camera2.CameraCaptureSession.StateCallback {
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            release(false /*skipCloseNotification*/);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            notifyConfigurationFailure();
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            synchronized (mInterfaceLock) {
                mCaptureSession = session;
                // Commit basic stats as soon as the capture session is created
                mStatsAggregator.commit(/*isFinal*/false);
                try {
                    finishPipelineInitialization();
                    CameraExtensionCharacteristics.initializeSession(
                            mInitializeHandler, mExtensionType);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to initialize session! Extension service does"
                            + " not respond!");
                    notifyConfigurationFailure();
                }
            }
        }
    }

    private class InitializeSessionHandler extends IInitializeSessionCallback.Stub {
        @Override
        public void onSuccess() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean status = true;
                    ArrayList<CaptureStageImpl> initialRequestList =
                            compileInitialRequestList();
                    if (!initialRequestList.isEmpty()) {
                        try {
                            setInitialCaptureRequest(initialRequestList,
                                    new InitialRequestHandler(
                                            mRepeatingRequestImageCallback));
                        } catch (CameraAccessException e) {
                            Log.e(TAG,
                                    "Failed to initialize the initial capture "
                                            + "request!");
                            status = false;
                        }
                    } else {
                        try {
                            setRepeatingRequest(mPreviewExtender.getCaptureStage(),
                                    new PreviewRequestHandler(null, null, null,
                                            mRepeatingRequestImageCallback));
                        } catch (CameraAccessException | RemoteException e) {
                            Log.e(TAG,
                                    "Failed to initialize internal repeating "
                                            + "request!");
                            status = false;
                        }

                    }

                    if (!status) {
                        notifyConfigurationFailure();
                    }
                }
            });
        }

        @Override
        public void onFailure() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCaptureSession.close();
                    Log.e(TAG, "Failed to initialize proxy service session!"
                            + " This can happen when trying to configure multiple "
                            + "concurrent extension sessions!");
                    notifyConfigurationFailure();
                }
            });
        }
    }

    private class BurstRequestHandler extends CameraCaptureSession.CaptureCallback {
        private final Executor mExecutor;
        private final ExtensionCaptureCallback mCallbacks;
        private final CaptureRequest mClientRequest;
        private final HashMap<CaptureRequest, Integer> mCaptureRequestMap;
        private final CameraOutputImageCallback mBurstImageCallback;

        private HashMap<Integer, Pair<Image, TotalCaptureResult>> mCaptureStageMap =
                new HashMap<>();
        private LongSparseArray<Pair<Image, Integer>> mCapturePendingMap =
                new LongSparseArray<>();

        private ImageCallback mImageCallback = null;
        private boolean mCaptureFailed = false;
        private CaptureResultHandler mCaptureResultHandler = null;

        public BurstRequestHandler(@NonNull CaptureRequest request, @NonNull Executor executor,
                @NonNull ExtensionCaptureCallback callbacks,
                @NonNull HashMap<CaptureRequest, Integer> requestMap,
                @Nullable CameraOutputImageCallback imageCallback) {
            mClientRequest = request;
            mExecutor = executor;
            mCallbacks = callbacks;
            mCaptureRequestMap = requestMap;
            mBurstImageCallback = imageCallback;
        }

        private void notifyCaptureFailed() {
            if (!mCaptureFailed) {
                mCaptureFailed = true;

                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks.onCaptureFailed(CameraExtensionSessionImpl.this,
                                    mClientRequest));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }

                for (Pair<Image, TotalCaptureResult> captureStage : mCaptureStageMap.values()) {
                    if (captureStage.first != null) {
                        captureStage.first.close();
                    }
                }
                mCaptureStageMap.clear();
            }
        }

        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     long timestamp,
                                     long frameNumber) {
            // Trigger the client callback only once in case of burst request
            boolean initialCallback = false;
            synchronized (mInterfaceLock) {
                if ((mImageProcessor != null) && (mImageCallback == null)) {
                    mImageCallback = new ImageCallback();
                    initialCallback = true;
                } else if (mImageProcessor == null) {
                    // No burst expected in this case
                    initialCallback = true;
                }
            }

            if (initialCallback) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks.onCaptureStarted(CameraExtensionSessionImpl.this,
                                    mClientRequest, timestamp));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            if ((mBurstImageCallback != null) && (mImageCallback != null)) {
                mBurstImageCallback.registerListener(timestamp, mImageCallback);
            }
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull Surface target, long frameNumber) {
            notifyCaptureFailed();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {
            notifyCaptureFailed();
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session,
                                             int sequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallbacks.onCaptureSequenceAborted(CameraExtensionSessionImpl.this,
                                sequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                               int sequenceId,
                                               long frameNumber) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallbacks
                                .onCaptureSequenceCompleted(CameraExtensionSessionImpl.this,
                                        sequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            if (!mCaptureRequestMap.containsKey(request)) {
                Log.e(TAG,
                        "Unexpected still capture request received!");
                return;
            }
            Integer stageId = mCaptureRequestMap.get(request);

            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp != null) {
                if (mCaptureResultsSupported && (mCaptureResultHandler == null)) {
                    mCaptureResultHandler = new CaptureResultHandler(mClientRequest, mExecutor,
                            mCallbacks, result.getSequenceId());
                }
                if (mImageProcessor != null) {
                    if (mCapturePendingMap.indexOfKey(timestamp) >= 0) {
                        Image img = mCapturePendingMap.get(timestamp).first;
                        mCapturePendingMap.remove(timestamp);
                        mCaptureStageMap.put(stageId, new Pair<>(img, result));
                        checkAndFireBurstProcessing();
                    } else {
                        mCapturePendingMap.put(timestamp, new Pair<>(null, stageId));
                        mCaptureStageMap.put(stageId, new Pair<>(null, result));
                    }
                } else {
                    mCaptureRequestMap.clear();
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(
                                () -> mCallbacks
                                        .onCaptureProcessStarted(CameraExtensionSessionImpl.this,
                                                mClientRequest));

                        if (mCaptureResultHandler != null) {
                            mCaptureResultHandler.onCaptureCompleted(timestamp,
                                    initializeFilteredResults(result));
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            } else {
                Log.e(TAG,
                        "Capture result without valid sensor timestamp!");
            }
        }

        private void checkAndFireBurstProcessing() {
            if (mCaptureRequestMap.size() == mCaptureStageMap.size()) {
                for (Pair<Image, TotalCaptureResult> captureStage : mCaptureStageMap
                        .values()) {
                    if ((captureStage.first == null) || (captureStage.second == null)) {
                        return;
                    }
                }

                mCaptureRequestMap.clear();
                mCapturePendingMap.clear();
                boolean processStatus = true;
                Byte jpegQuality = mClientRequest.get(CaptureRequest.JPEG_QUALITY);
                Integer jpegOrientation = mClientRequest.get(CaptureRequest.JPEG_ORIENTATION);
                List<CaptureBundle> captureList = initializeParcelable(mCaptureStageMap,
                        jpegOrientation, jpegQuality);
                try {
                    boolean isPostviewRequested =
                            mClientRequest.containsTarget(mClientPostviewSurface);
                    mImageProcessor.process(captureList, mCaptureResultHandler,
                            isPostviewRequested);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to process multi-frame request! Extension service "
                            + "does not respond!");
                    processStatus = false;
                }

                for (CaptureBundle bundle : captureList) {
                    bundle.captureImage.buffer.close();
                }
                captureList.clear();
                for (Pair<Image, TotalCaptureResult> captureStage : mCaptureStageMap.values()) {
                    captureStage.first.close();
                }
                mCaptureStageMap.clear();

                final long ident = Binder.clearCallingIdentity();
                try {
                    if (processStatus) {
                        mExecutor.execute(() -> mCallbacks.onCaptureProcessStarted(
                                CameraExtensionSessionImpl.this, mClientRequest));
                    } else {
                        mExecutor.execute(() -> mCallbacks.onCaptureFailed(
                                CameraExtensionSessionImpl.this, mClientRequest));
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        private class ImageCallback implements OnImageAvailableListener {
            @Override
            public void onImageDropped(long timestamp) {
                notifyCaptureFailed();
            }

            @Override
            public void onImageAvailable(ImageReader reader, Image img) {
                if (mCaptureFailed) {
                    img.close();
                }

                long timestamp = img.getTimestamp();
                reader.detachImage(img);
                if (mCapturePendingMap.indexOfKey(timestamp) >= 0) {
                    Integer stageId = mCapturePendingMap.get(timestamp).second;
                    mCapturePendingMap.remove(timestamp);
                    Pair<Image, TotalCaptureResult> captureStage =
                            mCaptureStageMap.get(stageId);
                    if (captureStage != null) {
                        mCaptureStageMap.put(stageId,
                                new Pair<>(img,
                                        captureStage.second));
                        checkAndFireBurstProcessing();
                    } else {
                        Log.e(TAG,
                                "Capture stage: " +
                                        mCapturePendingMap.get(timestamp).second +
                                        " is absent!");
                    }
                } else {
                    mCapturePendingMap.put(timestamp,
                            new Pair<>(img,
                                    -1));
                }
            }
        }
    }

    private class ImageLoopbackCallback implements OnImageAvailableListener {
        @Override
        public void onImageDropped(long timestamp) { }

        @Override
        public void onImageAvailable(ImageReader reader, Image img) {
            img.close();
        }
    }

    private class InitialRequestHandler extends CameraCaptureSession.CaptureCallback {
        private final CameraOutputImageCallback mImageCallback;

        public InitialRequestHandler(CameraOutputImageCallback imageCallback) {
            mImageCallback = imageCallback;
        }

        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            mImageCallback.registerListener(timestamp, new ImageLoopbackCallback());
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session,
                int sequenceId) {
            Log.e(TAG, "Initial capture request aborted!");
            notifyConfigurationFailure();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {
            Log.e(TAG, "Initial capture request failed!");
            notifyConfigurationFailure();
        }

        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                               int sequenceId,
                                               long frameNumber) {
            boolean status = true;
            synchronized (mInterfaceLock) {
                /**
                 * Initialize and set the initial repeating request which will execute in the
                 * absence of client repeating requests.
                 */
                try {
                    setRepeatingRequest(mPreviewExtender.getCaptureStage(),
                            new PreviewRequestHandler(null, null, null,
                                    mImageCallback));
                } catch (CameraAccessException | RemoteException e) {
                    Log.e(TAG, "Failed to start the internal repeating request!");
                    status = false;
                }

            }

            if (!status) {
                notifyConfigurationFailure();
            }
        }
    }

    private interface OnImageAvailableListener {
        void onImageDropped(long timestamp);
        void onImageAvailable (ImageReader reader, Image img);
    }

    private class CameraOutputImageCallback implements ImageReader.OnImageAvailableListener,
            Closeable {
        private final ImageReader mImageReader;
        // Map timestamp to specific images and listeners
        private HashMap<Long, Pair<Image, OnImageAvailableListener>> mImageListenerMap =
                new HashMap<>();
        private boolean mOutOfBuffers = false;
        private final boolean mPruneOlderBuffers;

        CameraOutputImageCallback(ImageReader imageReader, boolean pruneOlderBuffers) {
            mImageReader = imageReader;
            mPruneOlderBuffers = pruneOlderBuffers;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img;
            synchronized (mInterfaceLock) {
                try {
                    img = reader.acquireNextImage();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to acquire image, too many images pending!");
                    mOutOfBuffers = true;
                    return;
                }
                if (img == null) {
                    Log.e(TAG, "Invalid image!");
                    return;
                }

                Long timestamp = img.getTimestamp();
                if (mImageListenerMap.containsKey(timestamp)) {
                    Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.remove(
                            timestamp);
                    if (entry.second != null) {
                        entry.second.onImageAvailable(reader, img);
                    } else {
                        Log.w(TAG, "Invalid image listener, dropping frame!");
                        img.close();
                    }
                } else {
                    mImageListenerMap.put(timestamp, new Pair<>(img, null));
                }

                notifyDroppedImages(timestamp);
            }
        }

        private void notifyDroppedImages(long timestamp) {
            synchronized (mInterfaceLock) {
                Set<Long> timestamps = mImageListenerMap.keySet();
                ArrayList<Long> removedTs = new ArrayList<>();
                for (long ts : timestamps) {
                    if (ts < timestamp) {
                        if (!mPruneOlderBuffers) {
                            Log.w(TAG, "Unexpected older image with ts: " + ts);
                            continue;
                        }
                        Log.e(TAG, "Dropped image with ts: " + ts);
                        Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.get(ts);
                        if (entry.second != null) {
                            entry.second.onImageDropped(ts);
                        }
                        if (entry.first != null) {
                            entry.first.close();
                        }
                        removedTs.add(ts);
                    }
                }
                for (long ts : removedTs) {
                    mImageListenerMap.remove(ts);
                }
            }
        }

        public void registerListener(Long timestamp, OnImageAvailableListener listener) {
            synchronized (mInterfaceLock) {
                if (mImageListenerMap.containsKey(timestamp)) {
                    Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.remove(
                            timestamp);
                    if (entry.first != null) {
                        listener.onImageAvailable(mImageReader, entry.first);
                        if (mOutOfBuffers) {
                            mOutOfBuffers = false;
                            Log.w(TAG,"Out of buffers, retry!");
                            onImageAvailable(mImageReader);
                        }
                    } else {
                        Log.w(TAG, "No valid image for listener with ts: " +
                                timestamp.longValue());
                    }
                } else {
                    mImageListenerMap.put(timestamp, new Pair<>(null, listener));
                }
            }
        }

        @Override
        public void close() {
            synchronized (mInterfaceLock) {
                for (Pair<Image, OnImageAvailableListener> entry : mImageListenerMap.values()) {
                    if (entry.first != null) {
                        entry.first.close();
                    }
                }
                for (long timestamp : mImageListenerMap.keySet()) {
                    Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.get(timestamp);
                    if (entry.second != null) {
                        entry.second.onImageDropped(timestamp);
                    }
                }
                mImageListenerMap.clear();
            }
        }
    }

    private class CloseRequestHandler extends CameraCaptureSession.CaptureCallback {
        private final CameraOutputImageCallback mImageCallback;

        public CloseRequestHandler(CameraOutputImageCallback imageCallback) {
            mImageCallback = imageCallback;
        }

        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            mImageCallback.registerListener(timestamp, new ImageLoopbackCallback());
        }
    }

    private class CaptureResultHandler extends IProcessResultImpl.Stub {
        private final Executor mExecutor;
        private final ExtensionCaptureCallback mCallbacks;
        private final CaptureRequest mClientRequest;
        private final int mRequestId;

        public CaptureResultHandler(@NonNull CaptureRequest clientRequest,
                @NonNull Executor executor, @NonNull ExtensionCaptureCallback listener,
                int requestId) {
            mClientRequest = clientRequest;
            mExecutor = executor;
            mCallbacks = listener;
            mRequestId = requestId;
        }

        @Override
        public void onCaptureCompleted(long shutterTimestamp, CameraMetadataNative result) {
            if (result == null) {
                Log.e(TAG,"Invalid capture result!");
                return;
            }

            result.set(CaptureResult.SENSOR_TIMESTAMP, shutterTimestamp);
            TotalCaptureResult totalResult = new TotalCaptureResult(mCameraDevice.getId(), result,
                    mClientRequest, mRequestId, shutterTimestamp, new ArrayList<CaptureResult>(),
                    mSessionId, new PhysicalCaptureResultInfo[0]);
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallbacks.onCaptureResultAvailable(CameraExtensionSessionImpl.this,
                                mClientRequest, totalResult));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureProcessProgressed(int progress) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallbacks.onCaptureProcessProgressed(CameraExtensionSessionImpl.this,
                                mClientRequest, progress));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    // This handler can operate in three modes:
    // 1) Using valid client callbacks, which means camera buffers will be propagated the
    //    registered output surfaces and clients will be notified accordingly.
    // 2) Without any client callbacks where an internal repeating request is kept active
    //    to satisfy the extensions continuous preview/(repeating request) requirement.
    // 3) Single capture mode, where internal repeating requests are ignored and the preview
    //    logic is only triggered for the image processor case.
    private class PreviewRequestHandler extends CameraCaptureSession.CaptureCallback {
        private final Executor mExecutor;
        private final ExtensionCaptureCallback mCallbacks;
        private final CaptureRequest mClientRequest;
        private final boolean mClientNotificationsEnabled;
        private final CameraOutputImageCallback mRepeatingImageCallback;
        private final boolean mSingleCapture;
        private OnImageAvailableListener mImageCallback = null;
        private LongSparseArray<Pair<Image, TotalCaptureResult>> mPendingResultMap =
                new LongSparseArray<>();
        private CaptureResultHandler mCaptureResultHandler = null;

        private boolean mRequestUpdatedNeeded = false;

        public PreviewRequestHandler(@Nullable CaptureRequest clientRequest,
                @Nullable Executor executor, @Nullable ExtensionCaptureCallback listener,
                @NonNull CameraOutputImageCallback imageCallback) {
            this(clientRequest, executor, listener, imageCallback, false /*singleCapture*/);
        }

        public PreviewRequestHandler(@Nullable CaptureRequest clientRequest,
                @Nullable Executor executor, @Nullable ExtensionCaptureCallback listener,
                @NonNull CameraOutputImageCallback imageCallback, boolean singleCapture) {
            mClientRequest = clientRequest;
            mExecutor = executor;
            mCallbacks = listener;
            mClientNotificationsEnabled =
                    (mClientRequest != null) && (mExecutor != null) && (mCallbacks != null);
            mRepeatingImageCallback = imageCallback;
            mSingleCapture = singleCapture;
        }

        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     long timestamp,
                                     long frameNumber) {
            synchronized (mInterfaceLock) {
                // Setup the image callback handler for this repeating request just once
                // after streaming resumes.
                if (mImageCallback == null) {
                    if (mPreviewProcessorType ==
                            IPreviewExtenderImpl.PROCESSOR_TYPE_IMAGE_PROCESSOR) {
                        if (mClientNotificationsEnabled) {
                            mPreviewImageProcessor.onOutputSurface(mClientRepeatingRequestSurface,
                                    nativeGetSurfaceFormat(mClientRepeatingRequestSurface));
                        } else {
                            mPreviewImageProcessor.onOutputSurface(null, -1);
                        }
                        mImageCallback = new ImageProcessCallback();
                    } else {
                        mImageCallback = mClientNotificationsEnabled ?
                                new ImageForwardCallback(mRepeatingRequestImageWriter) :
                                new ImageLoopbackCallback();
                    }
                }
            }

            if (mClientNotificationsEnabled) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks.onCaptureStarted(CameraExtensionSessionImpl.this,
                                    mClientRequest, timestamp));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            mRepeatingImageCallback.registerListener(timestamp, mImageCallback);
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session,
                                             int sequenceId) {
            synchronized (mInterfaceLock) {
                if (mInternalRepeatingRequestEnabled && !mSingleCapture) {
                    resumeInternalRepeatingRequest(true);
                }
            }

            if (mClientNotificationsEnabled) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks
                                    .onCaptureSequenceAborted(CameraExtensionSessionImpl.this,
                                            sequenceId));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                notifyConfigurationFailure();
            }
        }

        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                               int sequenceId,
                                               long frameNumber) {

            synchronized (mInterfaceLock) {
                if (mRequestUpdatedNeeded && !mSingleCapture) {
                    mRequestUpdatedNeeded = false;
                    resumeInternalRepeatingRequest(false);
                } else if (mInternalRepeatingRequestEnabled && !mSingleCapture) {
                    resumeInternalRepeatingRequest(true);
                }
            }

            if (mClientNotificationsEnabled) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks
                                    .onCaptureSequenceCompleted(CameraExtensionSessionImpl.this,
                                            sequenceId));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {

            if (mClientNotificationsEnabled) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks.onCaptureFailed(CameraExtensionSessionImpl.this,
                                    mClientRequest));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            boolean notifyClient = mClientNotificationsEnabled;
            boolean processStatus = true;

            synchronized (mInterfaceLock) {
                final Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (timestamp != null) {
                    if (mCaptureResultsSupported && mClientNotificationsEnabled &&
                            (mCaptureResultHandler == null)) {
                        mCaptureResultHandler = new CaptureResultHandler(mClientRequest, mExecutor,
                                mCallbacks, result.getSequenceId());
                    }
                    if ((!mSingleCapture) && (mPreviewProcessorType ==
                            IPreviewExtenderImpl.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY)) {
                        CaptureStageImpl captureStage = null;
                        try {
                            captureStage = mPreviewRequestUpdateProcessor.process(
                                    result.getNativeMetadata(), result.getSequenceId());
                        } catch (RemoteException e) {
                            Log.e(TAG, "Extension service does not respond during " +
                                    "processing!");
                        }
                        if (captureStage != null) {
                            try {
                                setRepeatingRequest(captureStage, this, request);
                                mRequestUpdatedNeeded = true;
                            } catch (IllegalStateException e) {
                                // This is possible in case the camera device closes and the
                                // and the callback here is executed before the onClosed
                                // notification.
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to update repeating request settings!");
                            }
                        } else {
                            mRequestUpdatedNeeded = false;
                        }
                    } else if (mPreviewProcessorType ==
                            IPreviewExtenderImpl.PROCESSOR_TYPE_IMAGE_PROCESSOR) {
                        int idx = mPendingResultMap.indexOfKey(timestamp);

                        if ((idx >= 0) && (mPendingResultMap.get(timestamp).first == null)) {
                            // Image was dropped before we can receive the capture results
                            if ((mCaptureResultHandler != null)) {
                                mCaptureResultHandler.onCaptureCompleted(timestamp,
                                        initializeFilteredResults(result));
                            }
                            discardPendingRepeatingResults(idx, mPendingResultMap, false);
                        } else  if (idx >= 0) {
                            // Image came before the capture results
                            ParcelImage parcelImage = initializeParcelImage(
                                    mPendingResultMap.get(timestamp).first);
                            try {
                                mPreviewImageProcessor.process(parcelImage, result,
                                        mCaptureResultHandler);
                            } catch (RemoteException e) {
                                processStatus = false;
                                Log.e(TAG, "Extension service does not respond during " +
                                        "processing, dropping frame!");
                            } catch (RuntimeException e) {
                                // Runtime exceptions can happen in a few obscure cases where the
                                // client tries to initialize a new capture session while this
                                // session is still ongoing. In such scenario, the camera will
                                // disconnect from the intermediate output surface, which will
                                // invalidate the images that we acquired previously. This can
                                // happen before we get notified via "onClosed" so there aren't
                                // many options to avoid the exception.
                                processStatus = false;
                                Log.e(TAG, "Runtime exception encountered during buffer " +
                                        "processing, dropping frame!");
                            } finally {
                                parcelImage.buffer.close();
                                mPendingResultMap.get(timestamp).first.close();
                            }
                            discardPendingRepeatingResults(idx, mPendingResultMap, false);
                        } else {
                            // Image not yet available
                            notifyClient = false;
                            mPendingResultMap.put(timestamp,
                                    new Pair<>(null,
                                            result));
                        }
                    } else {
                        // No special handling for PROCESSOR_TYPE_NONE
                    }
                    if (notifyClient) {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            if (processStatus) {
                                mExecutor.execute(() -> mCallbacks
                                        .onCaptureProcessStarted(
                                                CameraExtensionSessionImpl.this,
                                                mClientRequest));
                                if ((mCaptureResultHandler != null) && (mPreviewProcessorType !=
                                        IPreviewExtenderImpl.PROCESSOR_TYPE_IMAGE_PROCESSOR)) {
                                    mCaptureResultHandler.onCaptureCompleted(timestamp,
                                            initializeFilteredResults(result));
                                }
                            } else {
                                mExecutor.execute(
                                        () -> mCallbacks
                                                .onCaptureFailed(
                                                        CameraExtensionSessionImpl.this,
                                                        mClientRequest));
                            }
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                } else {
                    Log.e(TAG,
                            "Result without valid sensor timestamp!");
                }
            }

            if (!notifyClient) {
                notifyConfigurationSuccess();
            }
        }

        private void resumeInternalRepeatingRequest(boolean internal) {
            try {
                if (internal) {
                    setRepeatingRequest(mPreviewExtender.getCaptureStage(),
                            new PreviewRequestHandler(null, null, null,
                                    mRepeatingImageCallback));
                } else {
                    setRepeatingRequest(mPreviewExtender.getCaptureStage(), this, mClientRequest);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to resume internal repeating request, extension service"
                        + " fails to respond!");
            } catch (IllegalStateException e) {
                // This is possible in case we try to resume before the state "onClosed"
                // notification is able to reach us.
                Log.w(TAG, "Failed to resume internal repeating request!");
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to resume internal repeating request!");
            }
        }

        // Find the timestamp of the oldest pending buffer
        private Long calculatePruneThreshold(
                LongSparseArray<Pair<Image, TotalCaptureResult>> previewMap) {
            long oldestTimestamp = Long.MAX_VALUE;
            for (int idx = 0; idx < previewMap.size(); idx++) {
                Pair<Image, TotalCaptureResult> entry = previewMap.valueAt(idx);
                long timestamp = previewMap.keyAt(idx);
                if ((entry.first != null) && (timestamp < oldestTimestamp)) {
                    oldestTimestamp = timestamp;
                }
            }
            return (oldestTimestamp == Long.MAX_VALUE) ? 0 : oldestTimestamp;
        }

        private void discardPendingRepeatingResults(int idx, LongSparseArray<Pair<Image,
                TotalCaptureResult>> previewMap, boolean notifyCurrentIndex) {
            if (idx < 0) {
                return;
            }
            for (int i = idx; i >= 0; i--) {
                if (previewMap.valueAt(i).first != null) {
                    previewMap.valueAt(i).first.close();
                } else if (mClientNotificationsEnabled && (previewMap.valueAt(i).second != null) &&
                        ((i != idx) || notifyCurrentIndex)) {
                    TotalCaptureResult result = previewMap.valueAt(i).second;
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (mCaptureResultHandler != null) {
                        mCaptureResultHandler.onCaptureCompleted(timestamp,
                                initializeFilteredResults(result));
                    }

                    Log.w(TAG, "Preview frame drop with timestamp: " + previewMap.keyAt(i));
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(
                                () -> mCallbacks
                                        .onCaptureFailed(CameraExtensionSessionImpl.this,
                                                mClientRequest));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }

                }
                previewMap.removeAt(i);
            }
        }

        private class ImageForwardCallback implements OnImageAvailableListener {
            private final ImageWriter mOutputWriter;

            public ImageForwardCallback(@NonNull ImageWriter imageWriter) {
                mOutputWriter = imageWriter;
            }

            @Override
            public void onImageDropped(long timestamp) {
                discardPendingRepeatingResults(mPendingResultMap.indexOfKey(timestamp),
                        mPendingResultMap, true);
            }

            @Override
            public void onImageAvailable(ImageReader reader, Image img) {
                if (img == null) {
                    Log.e(TAG, "Invalid image!");
                    return;
                }

                try {
                    mOutputWriter.queueInputImage(img);
                } catch (IllegalStateException e) {
                    // This is possible in case the client disconnects from the output surface
                    // abruptly.
                    Log.w(TAG, "Output surface likely abandoned, dropping buffer!");
                    img.close();
                } catch (RuntimeException e) {
                    // NOTE: This is intended to catch RuntimeException from ImageReader.detachImage
                    // ImageReader.detachImage is not supposed to throw RuntimeExceptions but the
                    // bug went unchecked for a few years and now its behavior cannot be changed
                    // without breaking backwards compatibility.

                    if (!e.getClass().equals(RuntimeException.class)) {
                        // re-throw any exceptions that aren't base RuntimeException since they are
                        // coming from elsewhere, and we shouldn't silently drop those.
                        throw e;
                    }

                    Log.w(TAG, "Output surface likely abandoned, dropping buffer!");
                    img.close();
                }
            }
        }

        private class ImageProcessCallback implements OnImageAvailableListener {

            @Override
            public void onImageDropped(long timestamp) {
                discardPendingRepeatingResults(mPendingResultMap.indexOfKey(timestamp),
                        mPendingResultMap, true);
                // Add an empty frame&results entry to flag that we dropped a frame
                // and valid capture results can immediately return to client.
                mPendingResultMap.put(timestamp, new Pair<>(null, null));
            }

            @Override
            public void onImageAvailable(ImageReader reader, Image img) {
                if (mPendingResultMap.size() + 1 >= PREVIEW_QUEUE_SIZE) {
                    // We reached the maximum acquired images limit. This is possible in case we
                    // have capture failures that result in absent or missing capture results. In
                    // such scenario we can prune the oldest pending buffer.
                    discardPendingRepeatingResults(
                            mPendingResultMap
                                    .indexOfKey(calculatePruneThreshold(mPendingResultMap)),
                            mPendingResultMap, true);
                }

                if (img == null) {
                    Log.e(TAG,
                            "Invalid preview buffer!");
                    return;
                }
                try {
                    reader.detachImage(img);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to detach image!");
                    img.close();
                    return;
                } catch (RuntimeException e) {
                    // NOTE: This is intended to catch RuntimeException from ImageReader.detachImage
                    // ImageReader.detachImage is not supposed to throw RuntimeExceptions but the
                    // bug went unchecked for a few years and now its behavior cannot be changed
                    // without breaking backwards compatibility.

                    if (!e.getClass().equals(RuntimeException.class)) {
                        // re-throw any exceptions that aren't base RuntimeException since they are
                        // coming from elsewhere, and we shouldn't silently drop those.
                        throw e;
                    }

                    Log.e(TAG, "Failed to detach image!");
                    img.close();
                    return;
                }

                long timestamp = img.getTimestamp();
                int idx = mPendingResultMap.indexOfKey(timestamp);
                if (idx >= 0) {
                    boolean processStatus = true;
                    ParcelImage parcelImage = initializeParcelImage(img);
                    try {
                        mPreviewImageProcessor.process(parcelImage,
                                mPendingResultMap.get(timestamp).second, mCaptureResultHandler);
                    } catch (RemoteException e) {
                        processStatus = false;
                        Log.e(TAG, "Extension service does not respond during " +
                                "processing, dropping frame!");
                    } finally {
                        parcelImage.buffer.close();
                        img.close();
                    }
                    discardPendingRepeatingResults(idx, mPendingResultMap, false);
                    if (mClientNotificationsEnabled) {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            if (processStatus) {
                                mExecutor.execute(() -> mCallbacks.onCaptureProcessStarted(
                                        CameraExtensionSessionImpl.this,
                                        mClientRequest));
                            } else {
                                mExecutor.execute(() -> mCallbacks.onCaptureFailed(
                                        CameraExtensionSessionImpl.this,
                                        mClientRequest));
                            }
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                } else {
                    mPendingResultMap.put(timestamp, new Pair<>(img, null));
                }
            }
        }
    }

    private CameraMetadataNative initializeFilteredResults(TotalCaptureResult result) {
        CameraMetadataNative captureResults = new CameraMetadataNative();
        for (CaptureResult.Key key : mSupportedResultKeys) {
            Object value = result.get(key);
            if (value != null) {
                captureResults.set(key, value);
            }
        }
        return captureResults;
    }

    private static Size findSmallestAspectMatchedSize(@NonNull List<Size> sizes,
            @NonNull Size arSize) {
        final float TOLL = .01f;

        if (arSize.getHeight() == 0) {
            throw new IllegalArgumentException("Invalid input aspect ratio");
        }

        float targetAR = ((float) arSize.getWidth()) / arSize.getHeight();
        Size ret = null;
        Size fallbackSize = null;
        for (Size sz : sizes) {
            if (fallbackSize == null) {
                fallbackSize = sz;
            }
            if ((sz.getHeight() > 0) &&
                    ((ret == null) ||
                            (ret.getWidth() * ret.getHeight()) <
                                    (sz.getWidth() * sz.getHeight()))) {
                float currentAR = ((float) sz.getWidth()) / sz.getHeight();
                if (Math.abs(currentAR - targetAR) <= TOLL) {
                    ret = sz;
                }
            }
        }
        if (ret == null) {
            Log.e(TAG, "AR matched size not found returning first size in list");
            ret = fallbackSize;
        }

        return ret;
    }

    private static ParcelImage initializeParcelImage(Image img) {
        ParcelImage parcelImage = new ParcelImage();
        parcelImage.buffer = img.getHardwareBuffer();
        try {
            SyncFence fd = img.getFence();
            if (fd.isValid()) {
                parcelImage.fence = fd.getFdDup();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to parcel buffer fence!");
        }
        parcelImage.width = img.getWidth();
        parcelImage.height = img.getHeight();
        parcelImage.format = img.getFormat();
        parcelImage.timestamp = img.getTimestamp();
        parcelImage.transform = img.getTransform();
        parcelImage.scalingMode = img.getScalingMode();
        parcelImage.planeCount = img.getPlaneCount();
        parcelImage.crop = img.getCropRect();

        return parcelImage;
    }

    private static List<CaptureBundle> initializeParcelable(
            HashMap<Integer, Pair<Image, TotalCaptureResult>> captureMap, Integer jpegOrientation,
            Byte jpegQuality) {
        ArrayList<CaptureBundle> ret = new ArrayList<>();
        for (Integer stageId : captureMap.keySet()) {
            Pair<Image, TotalCaptureResult> entry = captureMap.get(stageId);
            CaptureBundle bundle = new CaptureBundle();
            bundle.stage = stageId;
            bundle.captureImage = initializeParcelImage(entry.first);
            bundle.sequenceId = entry.second.getSequenceId();
            bundle.captureResult = entry.second.getNativeMetadata();
            if (jpegOrientation != null) {
                bundle.captureResult.set(CaptureResult.JPEG_ORIENTATION, jpegOrientation);
            }
            if (jpegQuality != null) {
                bundle.captureResult.set(CaptureResult.JPEG_QUALITY, jpegQuality);
            }
            ret.add(bundle);
        }

        return ret;
    }
}
