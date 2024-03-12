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

package android.hardware.camera2.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.hardware.camera2.extension.CameraOutputConfig;
import android.hardware.camera2.extension.CameraSessionConfig;
import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.ICaptureCallback;
import android.hardware.camera2.extension.IImageProcessorImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IRequestCallback;
import android.hardware.camera2.extension.IRequestProcessorImpl;
import android.hardware.camera2.extension.ISessionProcessorImpl;
import android.hardware.camera2.extension.LatencyPair;
import android.hardware.camera2.extension.OutputConfigId;
import android.hardware.camera2.extension.OutputSurface;
import android.hardware.camera2.extension.ParcelCaptureResult;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.extension.ParcelTotalCaptureResult;
import android.hardware.camera2.extension.Request;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.ExtensionSessionStatsAggregator;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.IntArray;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public final class CameraAdvancedExtensionSessionImpl extends CameraExtensionSession {
    private static final String TAG = "CameraAdvancedExtensionSessionImpl";

    private final Executor mExecutor;
    private CameraDevice mCameraDevice;
    private final Map<String, CameraMetadataNative> mCharacteristicsMap;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final CameraExtensionSession.StateCallback mCallbacks;
    private IAdvancedExtenderImpl mAdvancedExtender;
    // maps registered camera surfaces to extension output configs
    private final HashMap<Surface, CameraOutputConfig> mCameraConfigMap = new HashMap<>();
    // maps camera extension output ids to camera registered image readers
    private final HashMap<Integer, ImageReader> mReaderMap = new HashMap<>();
    private RequestProcessor mRequestProcessor = new RequestProcessor();
    private final int mSessionId;
    private IBinder mToken = null;

    private Surface mClientRepeatingRequestSurface;
    private Surface mClientCaptureSurface;
    private Surface mClientPostviewSurface;
    private OutputConfiguration mClientRepeatingRequestOutputConfig;
    private OutputConfiguration mClientCaptureOutputConfig;
    private OutputConfiguration mClientPostviewOutputConfig;
    private CameraCaptureSession mCaptureSession = null;
    private ISessionProcessorImpl mSessionProcessor = null;
    private final InitializeSessionHandler mInitializeHandler;
    private final ExtensionSessionStatsAggregator mStatsAggregator;

    private boolean mInitialized;
    private boolean mSessionClosed;
    private int mExtensionType;


    private final Context mContext;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock;

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public static CameraAdvancedExtensionSessionImpl createCameraAdvancedExtensionSession(
            @NonNull android.hardware.camera2.impl.CameraDeviceImpl cameraDevice,
            @NonNull Map<String, CameraCharacteristics> characteristicsMap,
            @NonNull Context ctx, @NonNull ExtensionSessionConfiguration config, int sessionId,
            @NonNull IBinder token)
            throws CameraAccessException, RemoteException {
        String cameraId = cameraDevice.getId();
        CameraExtensionCharacteristics extensionChars = new CameraExtensionCharacteristics(ctx,
                cameraId, characteristicsMap);

        Map<String, CameraMetadataNative> characteristicsMapNative =
                CameraExtensionUtils.getCharacteristicsMapNative(characteristicsMap);
        if (!CameraExtensionCharacteristics.isExtensionSupported(cameraDevice.getId(),
                config.getExtension(), characteristicsMapNative)) {
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
                if (Flags.extension10Bit() && Flags.cameraExtensionsCharacteristicsGet()) {
                    DynamicRangeProfiles dynamicProfiles = extensionChars.get(
                            config.getExtension(),
                            CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
                    if (dynamicProfiles == null || !dynamicProfiles.getSupportedProfiles()
                            .contains(c.getDynamicRangeProfile())) {
                        throw new IllegalArgumentException("Unsupported dynamic range profile: "
                                + c.getDynamicRangeProfile());
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported dynamic range profile: "
                            + c.getDynamicRangeProfile());
                }
            }
            if (c.getStreamUseCase() !=
                    CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT) {
                throw new IllegalArgumentException("Unsupported stream use case: " +
                        c.getStreamUseCase());
            }
        }

        int suitableSurfaceCount = 0;
        List<Size> supportedPreviewSizes = extensionChars.getExtensionSupportedSizes(
                config.getExtension(), SurfaceTexture.class);
        Surface repeatingRequestSurface = CameraExtensionUtils.getRepeatingRequestSurface(
                config.getOutputConfigurations(), supportedPreviewSizes);
        OutputConfiguration repeatingRequestOutputConfig = null;
        if (repeatingRequestSurface != null) {
            for (OutputConfiguration outputConfig : config.getOutputConfigurations()) {
                if (outputConfig.getSurface() == repeatingRequestSurface) {
                    repeatingRequestOutputConfig = outputConfig;
                }
            }
            suitableSurfaceCount++;
        }

        HashMap<Integer, List<Size>> supportedCaptureSizes = new HashMap<>();

        IntArray supportedCaptureOutputFormats =
                new IntArray(CameraExtensionUtils.SUPPORTED_CAPTURE_OUTPUT_FORMATS.length);
        supportedCaptureOutputFormats.addAll(
                CameraExtensionUtils.SUPPORTED_CAPTURE_OUTPUT_FORMATS);
        if (Flags.extension10Bit()) {
            supportedCaptureOutputFormats.add(ImageFormat.YCBCR_P010);
        }
        for (int format : supportedCaptureOutputFormats.toArray()) {
            List<Size> supportedSizes = extensionChars.getExtensionSupportedSizes(
                    config.getExtension(), format);
            if (supportedSizes != null) {
                supportedCaptureSizes.put(format, supportedSizes);
            }
        }
        Surface burstCaptureSurface = CameraExtensionUtils.getBurstCaptureSurface(
                config.getOutputConfigurations(), supportedCaptureSizes);
        OutputConfiguration burstCaptureOutputConfig = null;
        if (burstCaptureSurface != null) {
            for (OutputConfiguration outputConfig : config.getOutputConfigurations()) {
                if (outputConfig.getSurface() == burstCaptureSurface) {
                    burstCaptureOutputConfig = outputConfig;
                }
            }
            suitableSurfaceCount++;
        }

        if (suitableSurfaceCount != config.getOutputConfigurations().size()) {
            throw new IllegalArgumentException("One or more unsupported output surfaces found!");
        }

        Surface postviewSurface = null;
        OutputConfiguration postviewOutputConfig = config.getPostviewOutputConfiguration();
        if (burstCaptureSurface != null && config.getPostviewOutputConfiguration() != null) {
            CameraExtensionUtils.SurfaceInfo burstCaptureSurfaceInfo =
                    CameraExtensionUtils.querySurface(burstCaptureSurface);
            Size burstCaptureSurfaceSize =
                    new Size(burstCaptureSurfaceInfo.mWidth, burstCaptureSurfaceInfo.mHeight);
            HashMap<Integer, List<Size>> supportedPostviewSizes = new HashMap<>();
            for (int format : supportedCaptureOutputFormats.toArray()) {
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

        IAdvancedExtenderImpl extender = CameraExtensionCharacteristics.initializeAdvancedExtension(
                config.getExtension());
        extender.init(cameraId, characteristicsMapNative);

        CameraAdvancedExtensionSessionImpl ret = new CameraAdvancedExtensionSessionImpl(ctx,
                extender, cameraDevice, characteristicsMapNative, repeatingRequestOutputConfig,
                burstCaptureOutputConfig, postviewOutputConfig, config.getStateCallback(),
                config.getExecutor(), sessionId, token, config.getExtension());

        ret.mStatsAggregator.setClientName(ctx.getOpPackageName());
        ret.mStatsAggregator.setExtensionType(config.getExtension());

        ret.initialize();

        return ret;
    }

    private CameraAdvancedExtensionSessionImpl(Context ctx,
            @NonNull IAdvancedExtenderImpl extender,
            @NonNull CameraDeviceImpl cameraDevice,
            Map<String, CameraMetadataNative> characteristicsMap,
            @Nullable OutputConfiguration repeatingRequestOutputConfig,
            @Nullable OutputConfiguration burstCaptureOutputConfig,
            @Nullable OutputConfiguration postviewOutputConfig,
            @NonNull StateCallback callback, @NonNull Executor executor,
            int sessionId,
            @NonNull IBinder token,
            int extension) {
        mContext = ctx;
        mAdvancedExtender = extender;
        mCameraDevice = cameraDevice;
        mCharacteristicsMap = characteristicsMap;
        mCallbacks = callback;
        mExecutor = executor;
        mClientRepeatingRequestOutputConfig = repeatingRequestOutputConfig;
        mClientCaptureOutputConfig = burstCaptureOutputConfig;
        mClientPostviewOutputConfig = postviewOutputConfig;
        if (repeatingRequestOutputConfig != null) {
            mClientRepeatingRequestSurface = repeatingRequestOutputConfig.getSurface();
        }
        if (burstCaptureOutputConfig != null) {
            mClientCaptureSurface = burstCaptureOutputConfig.getSurface();
        }
        if (postviewOutputConfig != null) {
            mClientPostviewSurface = postviewOutputConfig.getSurface();
        }
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mInitialized = false;
        mSessionClosed = false;
        mInitializeHandler = new InitializeSessionHandler();
        mSessionId = sessionId;
        mToken = token;
        mInterfaceLock = cameraDevice.mInterfaceLock;
        mExtensionType = extension;

        mStatsAggregator = new ExtensionSessionStatsAggregator(mCameraDevice.getId(),
                /*isAdvanced=*/true);
    }

    /**
     * @hide
     */
    public synchronized void initialize() throws CameraAccessException, RemoteException {
        if (mInitialized) {
            Log.d(TAG, "Session already initialized");
            return;
        }

        OutputSurface previewSurface = initializeParcelable(mClientRepeatingRequestOutputConfig);
        OutputSurface captureSurface = initializeParcelable(mClientCaptureOutputConfig);
        OutputSurface postviewSurface = initializeParcelable(mClientPostviewOutputConfig);

        mSessionProcessor = mAdvancedExtender.getSessionProcessor();
        CameraSessionConfig sessionConfig = mSessionProcessor.initSession(mToken,
                mCameraDevice.getId(),
                mCharacteristicsMap, previewSurface, captureSurface, postviewSurface);
        List<CameraOutputConfig> outputConfigs = sessionConfig.outputConfigs;
        ArrayList<OutputConfiguration> outputList = new ArrayList<>();
        for (CameraOutputConfig output : outputConfigs) {
            Surface outputSurface = initializeSurface(output);
            if (outputSurface == null) {
                continue;
            }
            OutputConfiguration cameraOutput = new OutputConfiguration(output.surfaceGroupId,
                    outputSurface);

            if (output.isMultiResolutionOutput) {
                cameraOutput.setMultiResolutionOutput();
            }
            if ((output.sharedSurfaceConfigs != null) && !output.sharedSurfaceConfigs.isEmpty()) {
                cameraOutput.enableSurfaceSharing();
                for (CameraOutputConfig sharedOutputConfig : output.sharedSurfaceConfigs) {
                    Surface sharedSurface = initializeSurface(sharedOutputConfig);
                    if (sharedSurface == null) {
                        continue;
                    }
                    cameraOutput.addSurface(sharedSurface);
                    mCameraConfigMap.put(sharedSurface, sharedOutputConfig);
                }
            }

            // The extension processing logic needs to be able to match images to capture results via
            // image and result timestamps.
            cameraOutput.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR);
            cameraOutput.setReadoutTimestampEnabled(false);
            cameraOutput.setPhysicalCameraId(output.physicalCameraId);
            cameraOutput.setDynamicRangeProfile(output.dynamicRangeProfile);
            outputList.add(cameraOutput);
            mCameraConfigMap.put(cameraOutput.getSurface(), output);
        }

        int sessionType = SessionConfiguration.SESSION_REGULAR;
        if (sessionConfig.sessionType != -1 &&
                (sessionConfig.sessionType != SessionConfiguration.SESSION_HIGH_SPEED)) {
            sessionType = sessionConfig.sessionType;
            Log.v(TAG, "Using session type: " + sessionType);
        }

        SessionConfiguration sessionConfiguration = new SessionConfiguration(sessionType,
                outputList, new CameraExtensionUtils.HandlerExecutor(mHandler),
                new SessionStateHandler());
        if (sessionConfig.colorSpace != ColorSpaceProfiles.UNSPECIFIED) {
            sessionConfiguration.setColorSpace(
                    ColorSpace.Named.values()[sessionConfig.colorSpace]);
        }
        if ((sessionConfig.sessionParameter != null) &&
                (!sessionConfig.sessionParameter.isEmpty())) {
            CaptureRequest.Builder requestBuilder = mCameraDevice.createCaptureRequest(
                    sessionConfig.sessionTemplateId);
            CaptureRequest sessionRequest = requestBuilder.build();
            CameraMetadataNative.update(sessionRequest.getNativeMetadata(),
                    sessionConfig.sessionParameter);
            sessionConfiguration.setSessionParameters(sessionRequest);
        }

        mCameraDevice.createCaptureSession(sessionConfiguration);
    }

    private static ParcelCaptureResult initializeParcelable(CaptureResult result) {
        ParcelCaptureResult ret = new ParcelCaptureResult();
        ret.cameraId = result.getCameraId();
        ret.results = result.getNativeMetadata();
        ret.parent = result.getRequest();
        ret.sequenceId = result.getSequenceId();
        ret.frameNumber = result.getFrameNumber();

        return ret;
    }

    private static ParcelTotalCaptureResult initializeParcelable(TotalCaptureResult totalResult) {
        ParcelTotalCaptureResult ret = new ParcelTotalCaptureResult();
        ret.logicalCameraId = totalResult.getCameraId();
        ret.results = totalResult.getNativeMetadata();
        ret.parent = totalResult.getRequest();
        ret.sequenceId = totalResult.getSequenceId();
        ret.frameNumber = totalResult.getFrameNumber();
        ret.sessionId = totalResult.getSessionId();
        ret.partials = new ArrayList<>(totalResult.getPartialResults().size());
        for (CaptureResult partial : totalResult.getPartialResults()) {
            ret.partials.add(initializeParcelable(partial));
        }
        Map<String, TotalCaptureResult> physicalResults =
                totalResult.getPhysicalCameraTotalResults();
        ret.physicalResult = new ArrayList<>(physicalResults.size());
        for (TotalCaptureResult physicalResult : physicalResults.values()) {
            ret.physicalResult.add(new PhysicalCaptureResultInfo(physicalResult.getCameraId(),
                    physicalResult.getNativeMetadata()));
        }

        return ret;
    }

    private static OutputSurface initializeParcelable(OutputConfiguration o) {
        OutputSurface ret = new OutputSurface();

        if (o != null && o.getSurface() != null) {
            Surface s = o.getSurface();
            ret.surface = s;
            ret.size = new android.hardware.camera2.extension.Size();
            Size surfaceSize = SurfaceUtils.getSurfaceSize(s);
            ret.size.width = surfaceSize.getWidth();
            ret.size.height = surfaceSize.getHeight();
            ret.imageFormat = SurfaceUtils.getSurfaceFormat(s);

            if (Flags.extension10Bit()) {
                ret.dynamicRangeProfile = o.getDynamicRangeProfile();
                ColorSpace colorSpace = o.getColorSpace();
                if (colorSpace != null) {
                    ret.colorSpace = colorSpace.getId();
                } else {
                    ret.colorSpace = ColorSpaceProfiles.UNSPECIFIED;
                }
            } else {
                ret.dynamicRangeProfile = DynamicRangeProfiles.STANDARD;
                ret.colorSpace = ColorSpaceProfiles.UNSPECIFIED;
            }
        } else {
            ret.surface = null;
            ret.size = new android.hardware.camera2.extension.Size();
            ret.size.width = -1;
            ret.size.height = -1;
            ret.imageFormat = ImageFormat.UNKNOWN;
            ret.dynamicRangeProfile = DynamicRangeProfiles.STANDARD;
            ret.colorSpace = ColorSpaceProfiles.UNSPECIFIED;
        }

        return ret;
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
                LatencyPair latency = mSessionProcessor.getRealtimeCaptureLatency();
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
    public int setRepeatingRequest(@NonNull CaptureRequest request, @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        int seqId = -1;
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

            try {
                mSessionProcessor.setParameters(request);

                seqId = mSessionProcessor.startRepeating(new RequestCallbackHandler(request,
                        executor, listener, mCameraDevice.getId()));
            } catch (RemoteException e) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                        "Failed to enable repeating request, extension service failed to respond!");
            }
        }

        return seqId;
    }

    @Override
    public int capture(@NonNull CaptureRequest request,
            @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        int seqId = -1;
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            validateCaptureRequestTargets(request);

            if ((mClientCaptureSurface != null)  && request.containsTarget(mClientCaptureSurface)) {
                try {
                    boolean isPostviewRequested = request.containsTarget(mClientPostviewSurface);
                    mSessionProcessor.setParameters(request);

                    seqId = mSessionProcessor.startCapture(new RequestCallbackHandler(request,
                            executor, listener, mCameraDevice.getId()), isPostviewRequested);
                } catch (RemoteException e) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Failed " +
                            " to submit capture request, extension service failed to respond!");
                }
            } else if ((mClientRepeatingRequestSurface != null) &&
                    request.containsTarget(mClientRepeatingRequestSurface)) {
                try {
                    seqId = mSessionProcessor.startTrigger(request, new RequestCallbackHandler(
                            request, executor, listener, mCameraDevice.getId()));
                } catch (RemoteException e) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Failed " +
                            " to submit trigger request, extension service failed to respond!");
                }
            } else {
                throw new IllegalArgumentException("Invalid single capture output target!");
            }
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

            mCaptureSession.stopRepeating();

            try {
                mSessionProcessor.stopRepeating();
            } catch (RemoteException e) {
               throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                       "Failed to notify about the end of repeating request, extension service"
                               + " failed to respond!");
            }
        }
    }

    @Override
    public void close() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                try {
                    try {
                        mCaptureSession.stopRepeating();
                    } catch (IllegalStateException e) {
                        // OK: already be closed, nothing else to do
                    }
                    mSessionProcessor.stopRepeating();
                    mSessionProcessor.onCaptureSessionEnd();
                    mSessionClosed = true;
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop the repeating request or end the session,"
                            + " , extension service does not respond!") ;
                }
                // Commit stats before closing the capture session
                mStatsAggregator.commit(/*isFinal*/true);
                mCaptureSession.close();
            }
        }
    }

    /**
     * Called by {@link CameraDeviceImpl} right before the capture session is closed, and before it
     * calls {@link #release}
     */
    public void commitStats() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                // Only commit stats if a capture session was initialized
                mStatsAggregator.commit(/*isFinal*/true);
            }
        }
    }

    public void release(boolean skipCloseNotification) {
        boolean notifyClose = false;

        synchronized (mInterfaceLock) {
            mHandlerThread.quitSafely();

            if (mSessionProcessor != null) {
                try {
                    if (!mSessionClosed) {
                        mSessionProcessor.onCaptureSessionEnd();
                    }
                    mSessionProcessor.deInitSession(mToken);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to de-initialize session processor, extension service"
                            + " does not respond!") ;
                }
                mSessionProcessor = null;
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

            for (ImageReader reader : mReaderMap.values()) {
                reader.close();
            }
            mReaderMap.clear();

            mClientRepeatingRequestSurface = null;
            mClientCaptureSurface = null;
            mCaptureSession = null;
            mRequestProcessor = null;
            mCameraDevice = null;
            mAdvancedExtender = null;
        }

        if (notifyClose && !skipCloseNotification) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallbacks.onClosed(
                        CameraAdvancedExtensionSessionImpl.this));
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
                    () -> mCallbacks.onConfigureFailed(
                            CameraAdvancedExtensionSessionImpl.this));
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
            }

            try {
                CameraExtensionCharacteristics.initializeSession(
                        mInitializeHandler, mExtensionType);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to initialize session! Extension service does"
                        + " not respond!");
                notifyConfigurationFailure();
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
                    synchronized (mInterfaceLock) {
                        try {
                            if (mSessionProcessor != null) {
                                mInitialized = true;
                                mSessionProcessor.onCaptureSessionStart(mRequestProcessor,
                                        mStatsAggregator.getStatsKey());
                            } else {
                                Log.v(TAG, "Failed to start capture session, session " +
                                                " released before extension start!");
                                status = false;
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to start capture session,"
                                    + " extension service does not respond!");
                            status = false;
                            mInitialized = false;
                        }
                    }

                    if (status) {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            mExecutor.execute(() -> mCallbacks.onConfigured(
                                    CameraAdvancedExtensionSessionImpl.this));
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    } else {
                        onFailure();
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

    private final class RequestCallbackHandler extends ICaptureCallback.Stub {
        private final CaptureRequest mClientRequest;
        private final Executor mClientExecutor;
        private final ExtensionCaptureCallback mClientCallbacks;
        private final String mCameraId;

        private RequestCallbackHandler(@NonNull CaptureRequest clientRequest,
                @NonNull Executor clientExecutor,
                @NonNull ExtensionCaptureCallback clientCallbacks,
                @NonNull String cameraId) {
            mClientRequest = clientRequest;
            mClientExecutor = clientExecutor;
            mClientCallbacks = clientCallbacks;
            mCameraId = cameraId;
        }

        @Override
        public void onCaptureStarted(int captureSequenceId, long timestamp) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureStarted(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest,
                                timestamp));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureProcessStarted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureProcessStarted(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureFailed(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureFailed(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureProcessFailed(int captureSequenceId, int captureFailureReason) {
            if (Flags.concertMode()) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mClientExecutor.execute(
                            () -> mClientCallbacks.onCaptureFailed(
                                     CameraAdvancedExtensionSessionImpl.this, mClientRequest,
                                    captureFailureReason
                            ));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureSequenceCompleted(
                                CameraAdvancedExtensionSessionImpl.this, captureSequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureSequenceAborted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureSequenceAborted(
                                CameraAdvancedExtensionSessionImpl.this, captureSequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureCompleted(long timestamp, int requestId, CameraMetadataNative result) {
            if (result == null) {
                Log.e(TAG,"Invalid capture result!");
                return;
            }

            result.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
            TotalCaptureResult totalResult = new TotalCaptureResult(mCameraId, result,
                    mClientRequest, requestId, timestamp, new ArrayList<>(), mSessionId,
                    new PhysicalCaptureResultInfo[0]);
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mClientCallbacks.onCaptureResultAvailable(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest,
                                totalResult));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureProcessProgressed(int progress) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mClientCallbacks.onCaptureProcessProgressed(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest,
                                progress));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private final class CaptureCallbackHandler extends CameraCaptureSession.CaptureCallback {
        private final IRequestCallback mCallback;

        public CaptureCallbackHandler(IRequestCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request,
                Surface target, long frameNumber) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureBufferLost(requestId, frameNumber,
                            mCameraConfigMap.get(target).outputId.id);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify lost capture buffer, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                TotalCaptureResult result) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureCompleted(requestId, initializeParcelable(result));
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture result, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                CaptureFailure failure) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    android.hardware.camera2.extension.CaptureFailure captureFailure =
                            new android.hardware.camera2.extension.CaptureFailure();
                    captureFailure.request = request;
                    captureFailure.reason = failure.getReason();
                    captureFailure.errorPhysicalCameraId = failure.getPhysicalCameraId();
                    captureFailure.frameNumber = failure.getFrameNumber();
                    captureFailure.sequenceId = failure.getSequenceId();
                    captureFailure.dropped = !failure.wasImageCaptured();
                    mCallback.onCaptureFailed(requestId, captureFailure);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture failure, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                CaptureResult partialResult) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureProgressed(requestId, initializeParcelable(partialResult));
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture partial result, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
            try {
                mCallback.onCaptureSequenceAborted(sequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify aborted sequence, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId,
                long frameNumber) {
            try {
                mCallback.onCaptureSequenceCompleted(sequenceId, frameNumber);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify sequence complete, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                long timestamp, long frameNumber) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureStarted(requestId, frameNumber, timestamp);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture started, extension service doesn't"
                        + " respond!");
            }
        }
    }

    private static final class ImageReaderHandler implements ImageReader.OnImageAvailableListener {
        private final OutputConfigId mOutputConfigId;
        private final IImageProcessorImpl mIImageProcessor;
        private final String mPhysicalCameraId;

        private ImageReaderHandler(int outputConfigId,
                IImageProcessorImpl iImageProcessor, String physicalCameraId) {
            mOutputConfigId = new OutputConfigId();
            mOutputConfigId.id = outputConfigId;
            mIImageProcessor = iImageProcessor;
            mPhysicalCameraId = physicalCameraId;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (mIImageProcessor == null) {
                return;
            }

            Image img;
            try {
                img = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to acquire image, too many images pending!");
                return;
            }
            if (img == null) {
                Log.e(TAG, "Invalid image!");
                return;
            }

            try {
                reader.detachImage(img);
            } catch(Exception e) {
                Log.e(TAG, "Failed to detach image");
                img.close();
                return;
            }

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

            try {
                mIImageProcessor.onNextImageAvailable(mOutputConfigId, parcelImage,
                        mPhysicalCameraId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to propagate image buffer on output surface id: " +
                        mOutputConfigId + " extension service does not respond!");
            } finally {
                parcelImage.buffer.close();
                img.close();
            }
        }
    }

    private final class RequestProcessor extends IRequestProcessorImpl.Stub {
        @Override
        public void setImageProcessor(OutputConfigId outputConfigId,
                IImageProcessorImpl imageProcessor) {
            synchronized (mInterfaceLock) {
                if (mReaderMap.containsKey(outputConfigId.id)) {
                    ImageReader reader = mReaderMap.get(outputConfigId.id);
                    String physicalCameraId = null;
                    if (mCameraConfigMap.containsKey(reader.getSurface())) {
                        physicalCameraId =
                                mCameraConfigMap.get(reader.getSurface()).physicalCameraId;
                        reader.setOnImageAvailableListener(new ImageReaderHandler(outputConfigId.id,
                                    imageProcessor, physicalCameraId), mHandler);
                    } else {
                        Log.e(TAG, "Camera output configuration for ImageReader with " +
                                        " config Id " + outputConfigId.id + " not found!");
                    }
                } else {
                    Log.e(TAG, "ImageReader with output config id: " + outputConfigId.id +
                            " not found!");
                }
            }
        }

        @Override
        public int submit(Request request, IRequestCallback callback) {
            ArrayList<Request> captureList = new ArrayList<>();
            captureList.add(request);
            return submitBurst(captureList, callback);
        }

        @Override
        public int submitBurst(List<Request> requests, IRequestCallback callback) {
            int seqId = -1;
            try {
                synchronized (mInterfaceLock) {
                    if (!mInitialized) {
                        return seqId;
                    }

                    CaptureCallbackHandler captureCallback = new CaptureCallbackHandler(callback);
                    ArrayList<CaptureRequest> captureRequests = new ArrayList<>();
                    for (Request request : requests) {
                        captureRequests.add(initializeCaptureRequest(mCameraDevice, request,
                                mCameraConfigMap));
                    }
                    seqId = mCaptureSession.captureBurstRequests(captureRequests,
                            new CameraExtensionUtils.HandlerExecutor(mHandler), captureCallback);
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to submit capture requests!");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Capture session closed!");
            }

            return seqId;
        }

        @Override
        public int setRepeating(Request request, IRequestCallback callback) {
            int seqId = -1;
            try {
                synchronized (mInterfaceLock) {
                    if (!mInitialized) {
                        return seqId;
                    }

                    CaptureRequest repeatingRequest = initializeCaptureRequest(mCameraDevice,
                            request, mCameraConfigMap);
                    CaptureCallbackHandler captureCallback = new CaptureCallbackHandler(callback);
                    seqId = mCaptureSession.setSingleRepeatingRequest(repeatingRequest,
                            new CameraExtensionUtils.HandlerExecutor(mHandler), captureCallback);
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to enable repeating request!");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Capture session closed!");
            }

            return seqId;
        }

        @Override
        public void abortCaptures() {
            try {
                synchronized (mInterfaceLock) {
                    if (!mInitialized) {
                        return;
                    }

                    mCaptureSession.abortCaptures();
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed during capture abort!");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Capture session closed!");
            }
        }

        @Override
        public void stopRepeating() {
            try {
                synchronized (mInterfaceLock) {
                    if (!mInitialized) {
                        return;
                    }

                    mCaptureSession.stopRepeating();
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed during repeating capture stop!");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Capture session closed!");
            }
        }
    }

    private static CaptureRequest initializeCaptureRequest(CameraDevice cameraDevice,
            Request request, HashMap<Surface, CameraOutputConfig> surfaceIdMap)
            throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(request.templateId);
        for (OutputConfigId configId : request.targetOutputConfigIds) {
            boolean found = false;
            for (Map.Entry<Surface, CameraOutputConfig> entry : surfaceIdMap.entrySet()) {
                if (entry.getValue().outputId.id == configId.id) {
                    builder.addTarget(entry.getKey());
                    found = true;
                    break;
                }
            }

            if (!found) {
                Log.e(TAG, "Surface with output id: " + configId.id +
                        " not found among registered camera outputs!");
            }
        }

        builder.setTag(request.requestId);
        CaptureRequest ret = builder.build();
        CameraMetadataNative.update(ret.getNativeMetadata(), request.parameters);
        return ret;
    }

    private Surface initializeSurface(CameraOutputConfig output) {
        switch(output.type) {
            case CameraOutputConfig.TYPE_SURFACE:
                if (output.surface == null) {
                    Log.w(TAG, "Unsupported client output id: " + output.outputId.id +
                            ", skipping!");
                    return null;
                }
                return output.surface;
            case CameraOutputConfig.TYPE_IMAGEREADER:
                if ((output.imageFormat == ImageFormat.UNKNOWN) || (output.size.width <= 0) ||
                        (output.size.height <= 0)) {
                    Log.w(TAG, "Unsupported client output id: " + output.outputId.id +
                            ", skipping!");
                    return null;
                }
                ImageReader reader = ImageReader.newInstance(output.size.width,
                        output.size.height, output.imageFormat, output.capacity,
                        output.usage);
                mReaderMap.put(output.outputId.id, reader);
                return reader.getSurface();
            case CameraOutputConfig.TYPE_MULTIRES_IMAGEREADER:
                // Support for multi-resolution outputs to be added in future releases
            default:
                throw new IllegalArgumentException("Unsupported output config type: " +
                        output.type);
        }
    }
}
