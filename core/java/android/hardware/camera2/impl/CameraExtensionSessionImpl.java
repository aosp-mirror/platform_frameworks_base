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

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.extension.CaptureBundle;
import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.IRequestUpdateProcessorImpl;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public final class CameraExtensionSessionImpl extends CameraExtensionSession {
    private static final int PREVIEW_QUEUE_SIZE = 3;
    private static final String TAG = "CameraExtensionSessionImpl";

    private final Executor mExecutor;
    private final CameraDevice mCameraDevice;
    private final long mExtensionClientId;
    private final IImageCaptureExtenderImpl mImageExtender;
    private final IPreviewExtenderImpl mPreviewExtender;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final StateCallback mCallbacks;
    private final List<Size> mSupportedPreviewSizes;
    private final InitializeSessionHandler mInitializeHandler;

    private CameraCaptureSession mCaptureSession = null;
    private Surface mCameraRepeatingSurface, mClientRepeatingRequestSurface;
    private Surface mCameraBurstSurface, mClientCaptureSurface;
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
    // Enable/Disable internal preview/(repeating request). Extensions expect
    // that preview/(repeating request) is enabled and active at any point in time.
    // In case the client doesn't explicitly enable repeating requests, the framework
    // will do so internally.
    private boolean mInternalRepeatingRequestEnabled = true;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock = new Object(); // access from this class and Session only!

    private static int nativeGetSurfaceFormat(Surface surface) {
        return SurfaceUtils.getSurfaceFormat(surface);
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public static CameraExtensionSessionImpl createCameraExtensionSession(
            @NonNull CameraDevice cameraDevice,
            @NonNull Context ctx,
            @NonNull ExtensionSessionConfiguration config)
            throws CameraAccessException, RemoteException {
        long clientId = CameraExtensionCharacteristics.registerClient(ctx);
        if (clientId < 0) {
            throw new UnsupportedOperationException("Unsupported extension!");
        }

        String cameraId = cameraDevice.getId();
        CameraManager manager = ctx.getSystemService(CameraManager.class);
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        CameraExtensionCharacteristics extensionChars = new CameraExtensionCharacteristics(ctx,
                cameraId, chars);

        if (!CameraExtensionCharacteristics.isExtensionSupported(cameraDevice.getId(),
                config.getExtension(), chars)) {
            throw new UnsupportedOperationException("Unsupported extension type: " +
                    config.getExtension());
        }

        if (config.getOutputConfigurations().isEmpty() ||
                config.getOutputConfigurations().size() > 2) {
            throw new IllegalArgumentException("Unexpected amount of output surfaces, received: " +
                    config.getOutputConfigurations().size() + " expected <= 2");
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

        extenders.first.init(cameraId, chars.getNativeMetadata());
        extenders.first.onInit(cameraId, chars.getNativeMetadata());
        extenders.second.init(cameraId, chars.getNativeMetadata());
        extenders.second.onInit(cameraId, chars.getNativeMetadata());

        CameraExtensionSessionImpl session = new CameraExtensionSessionImpl(
                extenders.second,
                extenders.first,
                supportedPreviewSizes,
                clientId,
                cameraDevice,
                repeatingRequestSurface,
                burstCaptureSurface,
                config.getStateCallback(),
                config.getExecutor());

        session.initialize();

        return session;
    }

    public CameraExtensionSessionImpl(@NonNull IImageCaptureExtenderImpl imageExtender,
            @NonNull IPreviewExtenderImpl previewExtender,
            @NonNull List<Size> previewSizes,
            long extensionClientId,
            @NonNull CameraDevice cameraDevice,
            @Nullable Surface repeatingRequestSurface,
            @Nullable Surface burstCaptureSurface,
            @NonNull StateCallback callback,
            @NonNull Executor executor) {
        mExtensionClientId = extensionClientId;
        mImageExtender = imageExtender;
        mPreviewExtender = previewExtender;
        mCameraDevice = cameraDevice;
        mCallbacks = callback;
        mExecutor = executor;
        mClientRepeatingRequestSurface = repeatingRequestSurface;
        mClientCaptureSurface = burstCaptureSurface;
        mSupportedPreviewSizes = previewSizes;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mInitialized = false;
        mInitializeHandler = new InitializeSessionHandler();
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
            if (mClientRepeatingRequestSurface != null) {
                mPreviewRequestUpdateProcessor.onOutputSurface(mClientRepeatingRequestSurface,
                        nativeGetSurfaceFormat(mClientRepeatingRequestSurface));
                mRepeatingRequestImageWriter = ImageWriter.newInstance(
                        mClientRepeatingRequestSurface, PREVIEW_QUEUE_SIZE,
                        CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT);
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
            if (mClientRepeatingRequestSurface != null) {
                mRepeatingRequestImageWriter = ImageWriter.newInstance(
                        mClientRepeatingRequestSurface, PREVIEW_QUEUE_SIZE,
                        CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT);
            }
            mRepeatingRequestImageReader = ImageReader.newInstance(repeatingSurfaceInfo.mWidth,
                    repeatingSurfaceInfo.mHeight,
                    CameraExtensionCharacteristics.NON_PROCESSING_INPUT_FORMAT,
                    PREVIEW_QUEUE_SIZE, repeatingSurfaceInfo.mUsage);
            mCameraRepeatingSurface = mRepeatingRequestImageReader.getSurface();
        }
        mRepeatingRequestImageCallback = new CameraOutputImageCallback(
                mRepeatingRequestImageReader);
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
                mImageProcessor.onOutputSurface(mClientCaptureSurface, surfaceInfo.mFormat);
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

            mBurstCaptureImageCallback = new CameraOutputImageCallback(mBurstCaptureImageReader);
            mBurstCaptureImageReader.setOnImageAvailableListener(mBurstCaptureImageCallback,
                    mHandler);
            mCameraBurstSurface = mBurstCaptureImageReader.getSurface();
            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = mBurstCaptureImageReader.getWidth();
            sz.height = mBurstCaptureImageReader.getHeight();
            mImageProcessor.onResolutionUpdate(sz);
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

    /**
     * @hide
     */
    public synchronized void initialize() throws CameraAccessException, RemoteException {
        if (mInitialized) {
            Log.d(TAG,
                    "Session already initialized");
            return;
        }

        ArrayList<CaptureStageImpl> sessionParamsList = new ArrayList<>();
        ArrayList<OutputConfiguration> outputList = new ArrayList<>();
        initializeRepeatingRequestPipeline();
        outputList.add(new OutputConfiguration(mCameraRepeatingSurface));
        CaptureStageImpl previewSessionParams = mPreviewExtender.onPresetSession();
        if (previewSessionParams != null) {
            sessionParamsList.add(previewSessionParams);
        }
        initializeBurstCapturePipeline();
        outputList.add(new OutputConfiguration(mCameraBurstSurface));
        CaptureStageImpl stillCaptureSessionParams = mImageExtender.onPresetSession();
        if (stillCaptureSessionParams != null) {
            sessionParamsList.add(stillCaptureSessionParams);
        }

        SessionConfiguration sessionConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
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
                        new RepeatingRequestHandler(request, executor, listener,
                                mRepeatingRequestImageCallback));
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

    private static List<CaptureRequest> createBurstRequest(CameraDevice cameraDevice,
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

            // This will override the extension capture stage jpeg parameters with the user set
            // jpeg quality and rotation. This will guarantee that client configured jpeg
            // parameters always have highest priority.
            Integer jpegRotation = clientRequest.get(CaptureRequest.JPEG_ORIENTATION);
            if (jpegRotation != null) {
                captureStage.parameters.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation);
            }
            Byte jpegQuality = clientRequest.get(CaptureRequest.JPEG_QUALITY);
            if (jpegQuality != null) {
                captureStage.parameters.set(CaptureRequest.JPEG_QUALITY, jpegQuality);
            }

            requestBuilder.addTarget(target);
            CaptureRequest request = requestBuilder.build();
            CameraMetadataNative.update(request.getNativeMetadata(), captureStage.parameters);
            ret.add(request);
            captureMap.put(request, captureStage.id);
        }

        return ret;
    }

    private static CaptureRequest createRequest(CameraDevice cameraDevice,
                                                List<CaptureStageImpl> captureStageList,
                                                Surface target,
                                                int captureTemplate) throws CameraAccessException {
        CaptureRequest.Builder requestBuilder;
        requestBuilder = cameraDevice.createCaptureRequest(captureTemplate);
        if (target != null) {
            requestBuilder.addTarget(target);
        }

        CaptureRequest ret = requestBuilder.build();
        for (CaptureStageImpl captureStage : captureStageList) {
            if (captureStage != null) {
                CameraMetadataNative.update(ret.getNativeMetadata(), captureStage.parameters);
            }
        }
        return ret;
    }

    @Override
    public int capture(@NonNull CaptureRequest request,
                       @NonNull Executor executor,
                       @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        if (!mInitialized) {
            throw new IllegalStateException("Uninitialized component");
        }

        if (mClientCaptureSurface == null) {
            throw new IllegalArgumentException("No output surface registered for single requests!");
        }

        if (!request.containsTarget(mClientCaptureSurface) || (request.getTargets().size() != 1)) {
            throw new IllegalArgumentException("Invalid single capture output target!");
        }

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
            throw new UnsupportedOperationException("Failed to create still capture burst request");
        }

        return mCaptureSession.captureBurstRequests(burstRequest,
                new CameraExtensionUtils.HandlerExecutor(mHandler),
                new BurstRequestHandler(request, executor, listener, requestMap,
                        mBurstCaptureImageCallback));
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
                mCaptureSession.stopRepeating();

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
                if (!captureStageList.isEmpty()) {
                    CaptureRequest disableRequest = createRequest(mCameraDevice, captureStageList,
                            mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW);
                    mCaptureSession.capture(disableRequest,
                            new CloseRequestHandler(mRepeatingRequestImageCallback), mHandler);
                }

                mCaptureSession.close();
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
                                    CameraCaptureSession.CaptureCallback requestHandler)
            throws CameraAccessException {
        ArrayList<CaptureStageImpl> captureStageList = new ArrayList<>();
        captureStageList.add(captureStage);
        CaptureRequest repeatingRequest = createRequest(mCameraDevice,
                captureStageList, mCameraRepeatingSurface, CameraDevice.TEMPLATE_PREVIEW);
        return mCaptureSession.setSingleRepeatingRequest(repeatingRequest,
                new CameraExtensionUtils.HandlerExecutor(mHandler), requestHandler);
    }

    /** @hide */
    public void release() {
        synchronized (mInterfaceLock) {
            mInternalRepeatingRequestEnabled = false;
            mHandlerThread.quitSafely();

            try {
                mPreviewExtender.onDeInit();
                mImageExtender.onDeInit();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to release extensions! Extension service does not"
                        + " respond!");
            }

            if (mExtensionClientId >= 0) {
                CameraExtensionCharacteristics.unregisterClient(mExtensionClientId);
                if (mInitialized) {
                    CameraExtensionCharacteristics.releaseSession();
                }
            }
            mInitialized = false;

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
        }
    }

    private void notifyConfigurationFailure() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                return;
            }
        }

        release();

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
            release();

            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallbacks.onClosed(CameraExtensionSessionImpl.this));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            notifyConfigurationFailure();
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            synchronized (mInterfaceLock) {
                mCaptureSession = session;
                try {
                    CameraExtensionCharacteristics.initializeSession(mInitializeHandler);
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
                            new RepeatingRequestHandler(null, null, null,
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

        @Override
        public void onFailure() {
            mCaptureSession.close();
            Log.e(TAG, "Failed to initialize proxy service session!"
                    + " This can happen when trying to configure multiple "
                    + "concurrent extension sessions!");
            notifyConfigurationFailure();
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
                    captureStage.first.close();
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
                if (mImageProcessor != null) {
                    if (mCapturePendingMap.indexOfKey(timestamp) >= 0) {
                        Image img = mCapturePendingMap.get(timestamp).first;
                        mCaptureStageMap.put(stageId,
                                new Pair<>(img,
                                        result));
                        checkAndFireBurstProcessing();
                    } else {
                        mCapturePendingMap.put(timestamp,
                                new Pair<>(null,
                                        stageId));
                        mCaptureStageMap.put(stageId,
                                new Pair<>(null,
                                        result));
                    }
                } else {
                    mCaptureRequestMap.clear();
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(
                                () -> mCallbacks
                                        .onCaptureProcessStarted(CameraExtensionSessionImpl.this,
                                                mClientRequest));
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
                    mImageProcessor.process(captureList);
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
            public void onImageAvailable(ImageReader reader, Image img) {
                if (mCaptureFailed) {
                    img.close();
                }

                long timestamp = img.getTimestamp();
                reader.detachImage(img);
                if (mCapturePendingMap.indexOfKey(timestamp) >= 0) {
                    Integer stageId = mCapturePendingMap.get(timestamp).second;
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
                            new RepeatingRequestHandler(null, null, null,
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
        public void onImageAvailable (ImageReader reader, Image img);
    }

    private class CameraOutputImageCallback implements ImageReader.OnImageAvailableListener,
            Closeable {
        private final ImageReader mImageReader;
        // Map timestamp to specific images and listeners
        private HashMap<Long, Pair<Image, OnImageAvailableListener>> mImageListenerMap =
                new HashMap<>();
        private boolean mOutOfBuffers = false;

        CameraOutputImageCallback(ImageReader imageReader) {
            mImageReader = imageReader;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img;
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
                Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.remove(timestamp);
                if (entry.second != null) {
                    entry.second.onImageAvailable(reader, img);
                } else {
                    Log.w(TAG, "Invalid image listener, dropping frame!");
                    img.close();
                }
            } else {
                mImageListenerMap.put(img.getTimestamp(), new Pair<>(img, null));
            }
        }

        public void registerListener(Long timestamp, OnImageAvailableListener listener) {
            if (mImageListenerMap.containsKey(timestamp)) {
                Pair<Image, OnImageAvailableListener> entry = mImageListenerMap.remove(timestamp);
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

        @Override
        public void close() {
            for (Pair<Image, OnImageAvailableListener> entry : mImageListenerMap.values()) {
                if (entry.first != null) {
                    entry.first.close();
                }
            }
            mImageListenerMap.clear();
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

    // This handler can operate in two modes:
    // 1) Using valid client callbacks, which means camera buffers will be propagated the
    //    registered output surfaces and clients will be notified accordingly.
    // 2) Without any client callbacks where an internal repeating request is kept active
    //    to satisfy the extensions continuous preview/(repeating request) requirement.
    private class RepeatingRequestHandler extends CameraCaptureSession.CaptureCallback {
        private final Executor mExecutor;
        private final ExtensionCaptureCallback mCallbacks;
        private final CaptureRequest mClientRequest;
        private final boolean mClientNotificationsEnabled;
        private final CameraOutputImageCallback mRepeatingImageCallback;
        private OnImageAvailableListener mImageCallback = null;
        private LongSparseArray<Pair<Image, TotalCaptureResult>> mPendingResultMap =
                new LongSparseArray<>();

        private boolean mRequestUpdatedNeeded = false;

        public RepeatingRequestHandler(@Nullable CaptureRequest clientRequest,
                @Nullable Executor executor, @Nullable ExtensionCaptureCallback listener,
                @NonNull CameraOutputImageCallback imageCallback) {
            mClientRequest = clientRequest;
            mExecutor = executor;
            mCallbacks = listener;
            mClientNotificationsEnabled =
                    (mClientRequest != null) && (mExecutor != null) && (mCallbacks != null);
            mRepeatingImageCallback = imageCallback;
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
                if (mInternalRepeatingRequestEnabled) {
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
                if (mRequestUpdatedNeeded) {
                    mRequestUpdatedNeeded = false;
                    resumeInternalRepeatingRequest(false);
                } else if (mInternalRepeatingRequestEnabled) {
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
                    if (mPreviewProcessorType ==
                            IPreviewExtenderImpl.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY) {
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
                                setRepeatingRequest(captureStage, this);
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
                        if (idx >= 0) {
                            ParcelImage parcelImage = initializeParcelImage(
                                    mPendingResultMap.get(timestamp).first);
                            try {
                                mPreviewImageProcessor.process(parcelImage, result);
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
                            new RepeatingRequestHandler(null, null, null,
                                    mRepeatingImageCallback));
                } else {
                    setRepeatingRequest(mPreviewExtender.getCaptureStage(), this);
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
                } else {
                    if (mClientNotificationsEnabled && ((i != idx) || notifyCurrentIndex)) {
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
                }
            }
        }

        private class ImageProcessCallback implements OnImageAvailableListener {

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
                } catch (Exception e) {
                    Log.e(TAG,
                            "Failed to detach image!");
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
                                mPendingResultMap.get(timestamp).second);
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
        if (img.getFenceFd() >= 0) {
            try {
                parcelImage.fence = ParcelFileDescriptor.fromFd(img.getFenceFd());
            } catch (IOException e) {
                Log.e(TAG,"Failed to parcel buffer fence!");
            }
        }
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
        for (Integer stagetId : captureMap.keySet()) {
            Pair<Image, TotalCaptureResult> entry = captureMap.get(stagetId);
            CaptureBundle bundle = new CaptureBundle();
            bundle.stage = stagetId;
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
