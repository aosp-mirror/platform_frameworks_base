/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.camera;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.extension.CaptureBundle;
import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.ICameraExtensionsProxyService;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.IPreviewImageProcessorImpl;
import android.hardware.camera2.extension.IRequestUpdateProcessorImpl;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.SizeList;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import androidx.camera.extensions.impl.AutoImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.AutoPreviewExtenderImpl;
import androidx.camera.extensions.impl.BeautyImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.BeautyPreviewExtenderImpl;
import androidx.camera.extensions.impl.BokehImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.BokehPreviewExtenderImpl;
import androidx.camera.extensions.impl.CaptureProcessorImpl;
import androidx.camera.extensions.impl.ExtensionVersionImpl;
import androidx.camera.extensions.impl.HdrImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.HdrPreviewExtenderImpl;
import androidx.camera.extensions.impl.ImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.InitializerImpl;
import androidx.camera.extensions.impl.NightImageCaptureExtenderImpl;
import androidx.camera.extensions.impl.NightPreviewExtenderImpl;
import androidx.camera.extensions.impl.PreviewExtenderImpl;
import androidx.camera.extensions.impl.PreviewExtenderImpl.ProcessorType;
import androidx.camera.extensions.impl.PreviewImageProcessorImpl;
import androidx.camera.extensions.impl.RequestUpdateProcessorImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

public class CameraExtensionsProxyService extends Service {
    private static final String TAG = "CameraExtensionsProxyService";

    private static final String CAMERA_EXTENSION_VERSION_NAME =
            "androidx.camera.extensions.impl.ExtensionVersionImpl";
    private static final String LATEST_VERSION = "1.1.0";
    private static final String[] SUPPORTED_VERSION_PREFIXES = {"1.1.", "1.0."};
    private static final boolean EXTENSIONS_PRESENT = checkForExtensions();
    private static final String EXTENSIONS_VERSION = EXTENSIONS_PRESENT ?
            (new ExtensionVersionImpl()).checkApiVersion(LATEST_VERSION) : null;
    private static final boolean LATEST_VERSION_SUPPORTED =
            EXTENSIONS_PRESENT && EXTENSIONS_VERSION.startsWith(SUPPORTED_VERSION_PREFIXES[0]);

    private static boolean checkForExtensions() {
        try {
            Class.forName(CAMERA_EXTENSION_VERSION_NAME);
        } catch (ClassNotFoundException e) {
            return false;
        }

        String extensionVersion = (new ExtensionVersionImpl()).checkApiVersion(LATEST_VERSION);
        for (String supportedVersion : SUPPORTED_VERSION_PREFIXES) {
            if (extensionVersion.startsWith(supportedVersion)) {
                return true;
            }
        }

        return false;
    }

    /**
     * A per-process global camera extension manager instance, to track and
     * initialize/release extensions depending on client activity.
     */
    private static final class CameraExtensionManagerGlobal {
        private static final String TAG = "CameraExtensionManagerGlobal";
        private final int EXTENSION_DELAY_MS = 1000;

        private final Handler mHandler;
        private final HandlerThread mHandlerThread;
        private final Object mLock = new Object();

        private long mCurrentClientCount = 0;
        private ArraySet<Long> mActiveClients = new ArraySet<>();

        // Singleton instance
        private static final CameraExtensionManagerGlobal GLOBAL_CAMERA_MANAGER =
                new CameraExtensionManagerGlobal();

        // Singleton, don't allow construction
        private CameraExtensionManagerGlobal() {
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }

        private final static class InitializeHandler
                implements InitializerImpl.OnExtensionsInitializedCallback {
            private final InitializerFuture mStatusFuture;

            public InitializeHandler(InitializerFuture statusFuture) {
                mStatusFuture = statusFuture;
            }

            @Override
            public void onSuccess() {
                mStatusFuture.setStatus(true);
            }

            @Override
            public void onFailure(int error) {
                mStatusFuture.setStatus(false);
            }
        }

        private final static class ReleaseHandler
                implements InitializerImpl.OnExtensionsDeinitializedCallback {
            private final InitializerFuture mStatusFuture;

            public ReleaseHandler(InitializerFuture statusFuture) {
                mStatusFuture = statusFuture;
            }

            @Override public void onSuccess() {
                mStatusFuture.setStatus(true);
            }

            @Override
            public void onFailure(int i) {
                mStatusFuture.setStatus(false);
            }
        }

        private static class InitializerFuture implements Future<Boolean> {
            private volatile Boolean mStatus;
            ConditionVariable mCondVar = new ConditionVariable(/*opened*/false);

            public void setStatus(boolean status) {
                mStatus = status;
                mCondVar.open();
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false; // don't allow canceling this task
            }

            @Override
            public boolean isCancelled() {
                return false; // can never cancel this task
            }

            @Override
            public boolean isDone() {
                return mStatus != null;
            }

            @Override
            public Boolean get() {
                mCondVar.block();
                return mStatus;
            }

            @Override
            public Boolean get(long timeout, TimeUnit unit) throws TimeoutException {
                long timeoutMs = unit.convert(timeout, TimeUnit.MILLISECONDS);
                if (!mCondVar.block(timeoutMs)) {
                    throw new TimeoutException(
                            "Failed to receive status after " + timeout + " " + unit);
                }

                if (mStatus == null) {
                    throw new AssertionError();
                }
                return mStatus;
            }

        }

        public static CameraExtensionManagerGlobal get() {
            return GLOBAL_CAMERA_MANAGER;
        }

        public long registerClient(Context ctx) {
            synchronized (mLock) {
                if (LATEST_VERSION_SUPPORTED) {
                    if (mActiveClients.isEmpty()) {
                        InitializerFuture status = new InitializerFuture();
                        InitializerImpl.init(EXTENSIONS_VERSION, ctx, new InitializeHandler(status),
                                new HandlerExecutor(mHandler));
                        boolean initSuccess;
                        try {
                            initSuccess = status.get(EXTENSION_DELAY_MS,
                                    TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            Log.e(TAG, "Timed out while initializing camera extensions!");
                            return -1;
                        }
                        if (!initSuccess) {
                            Log.e(TAG, "Failed while initializing camera extensions!");
                            return -1;
                        }
                    }
                }

                long ret = mCurrentClientCount;
                mCurrentClientCount++;
                if (mCurrentClientCount < 0) {
                    mCurrentClientCount = 0;
                }
                mActiveClients.add(ret);

                return ret;
            }
        }

        public void unregisterClient(long clientId) {
            synchronized (mLock) {
                if (mActiveClients.remove(clientId) && mActiveClients.isEmpty() &&
                        LATEST_VERSION_SUPPORTED) {
                    InitializerFuture status = new InitializerFuture();
                    InitializerImpl.deinit(new ReleaseHandler(status),
                            new HandlerExecutor(mHandler));
                    boolean releaseSuccess;
                    try {
                        releaseSuccess = status.get(EXTENSION_DELAY_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        Log.e(TAG, "Timed out while releasing camera extensions!");
                        return;
                    }
                    if (!releaseSuccess) {
                        Log.e(TAG, "Failed while releasing camera extensions!");
                    }
                }
            }
        }
    }

    /**
     * @hide
     */
    private static long registerClient(Context ctx) {
        if (!EXTENSIONS_PRESENT) {
            return -1;
        }
        return CameraExtensionManagerGlobal.get().registerClient(ctx);
    }

    /**
     * @hide
     */
    public static void unregisterClient(long clientId) {
        if (!EXTENSIONS_PRESENT) {
            return;
        }
        CameraExtensionManagerGlobal.get().unregisterClient(clientId);
    }

    /**
     * @hide
     */
    public static Pair<PreviewExtenderImpl, ImageCaptureExtenderImpl> initializeExtension(
            int extensionType) {
        switch (extensionType) {
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC:
                return new Pair<>(new AutoPreviewExtenderImpl(),
                        new AutoImageCaptureExtenderImpl());
            case CameraExtensionCharacteristics.EXTENSION_BEAUTY:
                return new Pair<>(new BeautyPreviewExtenderImpl(),
                        new BeautyImageCaptureExtenderImpl());
            case CameraExtensionCharacteristics.EXTENSION_BOKEH:
                return new Pair<>(new BokehPreviewExtenderImpl(),
                        new BokehImageCaptureExtenderImpl());
            case CameraExtensionCharacteristics.EXTENSION_HDR:
                return new Pair<>(new HdrPreviewExtenderImpl(), new HdrImageCaptureExtenderImpl());
            case CameraExtensionCharacteristics.EXTENSION_NIGHT:
                return new Pair<>(new NightPreviewExtenderImpl(),
                        new NightImageCaptureExtenderImpl());
            default:
                throw new IllegalArgumentException("Unknown extension: " + extensionType);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // This will setup the camera vendor tag descriptor in the service process
        try {
            CameraManager manager = getSystemService(CameraManager.class);

            String [] cameraIds = manager.getCameraIdListNoLazy();
            if (cameraIds != null) {
                for (String cameraId : cameraIds) {
                    CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera characteristics!");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


    @Override
    public IBinder onBind(Intent intent) {
        return new CameraExtensionsProxyServiceStub();
    }

    private static List<SizeList> initializeParcelable(
            List<Pair<Integer, android.util.Size[]>> sizes) {
        if (sizes == null) {
            return null;
        }
        ArrayList<SizeList> ret = new ArrayList<>(sizes.size());
        for (Pair<Integer, Size[]> entry : sizes) {
            SizeList sizeList = new SizeList();
            sizeList.format = entry.first;
            sizeList.sizes = new ArrayList<>();
            for (android.util.Size size : entry.second) {
                android.hardware.camera2.extension.Size sz =
                        new android.hardware.camera2.extension.Size();
                sz.width = size.getWidth();
                sz.height = size.getHeight();
                sizeList.sizes.add(sz);
            }
        }

        return ret;
    }

    private static CameraMetadataNative initializeParcelableMetadata(
            List<Pair<CaptureRequest.Key, Object>> paramList) {
        if (paramList == null) {
            return null;
        }

        CameraMetadataNative ret = new CameraMetadataNative();
        for (Pair<CaptureRequest.Key, Object> param : paramList) {
            ret.set(param.first, param.second);
        }

        return ret;
    }

    private static android.hardware.camera2.extension.CaptureStageImpl initializeParcelable(
            androidx.camera.extensions.impl.CaptureStageImpl captureStage) {
        if (captureStage == null) {
            return null;
        }

        android.hardware.camera2.extension.CaptureStageImpl ret =
                new android.hardware.camera2.extension.CaptureStageImpl();
        ret.id = captureStage.getId();
        ret.parameters = initializeParcelableMetadata(captureStage.getParameters());

        return ret;
    }

    private class CameraExtensionsProxyServiceStub extends ICameraExtensionsProxyService.Stub {
        @Override
        public long registerClient() {
            return CameraExtensionsProxyService.registerClient(CameraExtensionsProxyService.this);
        }

        @Override
        public void unregisterClient(long clientId) {
            CameraExtensionsProxyService.unregisterClient(clientId);
        }

        @Override
        public IPreviewExtenderImpl initializePreviewExtension(int extensionType) {
            Pair<PreviewExtenderImpl, ImageCaptureExtenderImpl> extension;
            try {
                extension = initializeExtension(extensionType);
            } catch (IllegalArgumentException e) {
                return null;
            }

            return new PreviewExtenderImplStub(extension.first);
        }

        @Override
        public IImageCaptureExtenderImpl initializeImageExtension(int extensionType) {
            Pair<PreviewExtenderImpl, ImageCaptureExtenderImpl> extension;
            try {
                extension = initializeExtension(extensionType);
            } catch (IllegalArgumentException e) {
                return null;
            }

            return new ImageCaptureExtenderImplStub(extension.second);
        }
    }

    private class PreviewExtenderImplStub extends IPreviewExtenderImpl.Stub {
        private final PreviewExtenderImpl mPreviewExtender;

        public PreviewExtenderImplStub(PreviewExtenderImpl previewExtender) {
            mPreviewExtender = previewExtender;
        }

        @Override
        public void onInit(String cameraId, CameraMetadataNative cameraCharacteristics) {
            mPreviewExtender.onInit(cameraId, new CameraCharacteristics(cameraCharacteristics),
                    CameraExtensionsProxyService.this);
        }

        @Override
        public void onDeInit() {
            mPreviewExtender.onDeInit();
        }

        @Override
        public CaptureStageImpl onPresetSession() {
            return initializeParcelable(mPreviewExtender.onPresetSession());
        }

        @Override
        public CaptureStageImpl onEnableSession() {
            return initializeParcelable(mPreviewExtender.onEnableSession());
        }

        @Override
        public CaptureStageImpl onDisableSession() {
            return initializeParcelable(mPreviewExtender.onDisableSession());
        }

        @Override
        public void init(String cameraId, CameraMetadataNative chars) {
            if (LATEST_VERSION_SUPPORTED) {
                mPreviewExtender.init(cameraId, new CameraCharacteristics(chars));
            }
        }

        @Override
        public boolean isExtensionAvailable(String cameraId, CameraMetadataNative chars) {
            return mPreviewExtender.isExtensionAvailable(cameraId,
                    new CameraCharacteristics(chars));
        }

        @Override
        public CaptureStageImpl getCaptureStage() {
            return initializeParcelable(mPreviewExtender.getCaptureStage());
        }

        @Override
        public int getProcessorType() {
            ProcessorType processorType = mPreviewExtender.getProcessorType();
            if (processorType == ProcessorType.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY) {
                return IPreviewExtenderImpl.PROCESSOR_TYPE_REQUEST_UPDATE_ONLY;
            } else if (processorType == ProcessorType.PROCESSOR_TYPE_IMAGE_PROCESSOR) {
                return IPreviewExtenderImpl.PROCESSOR_TYPE_IMAGE_PROCESSOR;
            } else {
                return IPreviewExtenderImpl.PROCESSOR_TYPE_NONE;
            }
        }

        @Override
        public IPreviewImageProcessorImpl getPreviewImageProcessor() {
            PreviewImageProcessorImpl processor;
            try {
                processor = (PreviewImageProcessorImpl) mPreviewExtender.getProcessor();
            } catch (ClassCastException e) {
                Log.e(TAG, "Failed casting preview processor!");
                return null;
            }

            if (processor != null) {
                return new PreviewImageProcessorImplStub(processor);
            }

            return null;
        }

        @Override
        public IRequestUpdateProcessorImpl getRequestUpdateProcessor() {
            RequestUpdateProcessorImpl processor;
            try {
                processor = (RequestUpdateProcessorImpl) mPreviewExtender.getProcessor();
            } catch (ClassCastException e) {
                Log.e(TAG, "Failed casting preview processor!");
                return null;
            }

            if (processor != null) {
                return new RequestUpdateProcessorImplStub(processor);
            }

            return null;
        }

        @Override
        public List<SizeList> getSupportedResolutions() {
            if (LATEST_VERSION_SUPPORTED) {
                List<Pair<Integer, android.util.Size[]>> sizes =
                        mPreviewExtender.getSupportedResolutions();
                if ((sizes != null) && !sizes.isEmpty()) {
                    return initializeParcelable(sizes);
                }
            }
            return null;
        }
    }

    private class ImageCaptureExtenderImplStub extends IImageCaptureExtenderImpl.Stub {
        private final ImageCaptureExtenderImpl mImageExtender;

        public ImageCaptureExtenderImplStub(ImageCaptureExtenderImpl imageExtender) {
            mImageExtender = imageExtender;
        }

        @Override
        public void onInit(String cameraId, CameraMetadataNative cameraCharacteristics) {
            mImageExtender.onInit(cameraId, new CameraCharacteristics(cameraCharacteristics),
                    CameraExtensionsProxyService.this);
        }

        @Override
        public void onDeInit() {
            mImageExtender.onDeInit();
        }

        @Override
        public CaptureStageImpl onPresetSession() {
            return initializeParcelable(mImageExtender.onPresetSession());
        }

        @Override
        public CaptureStageImpl onEnableSession() {
            return initializeParcelable(mImageExtender.onEnableSession());
        }

        @Override
        public CaptureStageImpl onDisableSession() {
            return initializeParcelable(mImageExtender.onDisableSession());
        }

        @Override
        public void init(String cameraId, CameraMetadataNative chars) {
            if (LATEST_VERSION_SUPPORTED) {
                mImageExtender.init(cameraId, new CameraCharacteristics(chars));
            }
        }

        @Override
        public boolean isExtensionAvailable(String cameraId, CameraMetadataNative chars) {
            return mImageExtender.isExtensionAvailable(cameraId,
                    new CameraCharacteristics(chars));
        }

        @Override
        public ICaptureProcessorImpl getCaptureProcessor() {
            CaptureProcessorImpl captureProcessor = mImageExtender.getCaptureProcessor();
            if (captureProcessor != null) {
                return new CaptureProcessorImplStub(captureProcessor);
            }

            return null;
        }


        @Override
        public List<CaptureStageImpl> getCaptureStages() {
            List<androidx.camera.extensions.impl.CaptureStageImpl> captureStages =
                mImageExtender.getCaptureStages();
            if (captureStages != null) {
                ArrayList<android.hardware.camera2.extension.CaptureStageImpl> ret =
                        new ArrayList<>();
                for (androidx.camera.extensions.impl.CaptureStageImpl stage : captureStages) {
                    ret.add(initializeParcelable(stage));
                }

                return ret;
            }

            return null;
        }

        @Override
        public int getMaxCaptureStage() {
            return mImageExtender.getMaxCaptureStage();
        }

        @Override
        public List<SizeList> getSupportedResolutions() {
            if (LATEST_VERSION_SUPPORTED) {
                List<Pair<Integer, android.util.Size[]>> sizes =
                        mImageExtender.getSupportedResolutions();
                if ((sizes != null) && !sizes.isEmpty()) {
                    return initializeParcelable(sizes);
                }
            }

            return null;
        }
    }

    private class CaptureProcessorImplStub extends ICaptureProcessorImpl.Stub {
        private final CaptureProcessorImpl mCaptureProcessor;

        public CaptureProcessorImplStub(CaptureProcessorImpl captureProcessor) {
            mCaptureProcessor = captureProcessor;
        }

        @Override
        public void onOutputSurface(Surface surface, int imageFormat) {
            mCaptureProcessor.onOutputSurface(surface, imageFormat);
        }

        @Override
        public void onResolutionUpdate(android.hardware.camera2.extension.Size size) {
            mCaptureProcessor.onResolutionUpdate(new android.util.Size(size.width, size.height));
        }

        @Override
        public void onImageFormatUpdate(int imageFormat) {
            mCaptureProcessor.onImageFormatUpdate(imageFormat);
        }

        @Override
        public void process(List<CaptureBundle> captureList) {
            HashMap<Integer, Pair<Image, TotalCaptureResult>> captureMap = new HashMap<>();
            for (CaptureBundle captureBundle : captureList) {
                captureMap.put(captureBundle.stage, new Pair<> (
                        new ExtensionImage(captureBundle.captureImage),
                        new TotalCaptureResult(captureBundle.captureResult,
                                captureBundle.sequenceId)));
            }
            if (!captureMap.isEmpty()) {
                mCaptureProcessor.process(captureMap);
            } else {
                Log.e(TAG, "Process request with absent capture stages!");
            }
        }
    }

    private class PreviewImageProcessorImplStub extends IPreviewImageProcessorImpl.Stub {
        private final PreviewImageProcessorImpl mProcessor;

        public PreviewImageProcessorImplStub(PreviewImageProcessorImpl processor) {
            mProcessor = processor;
        }

        @Override
        public void onOutputSurface(Surface surface, int imageFormat) {
            mProcessor.onOutputSurface(surface, imageFormat);
        }

        @Override
        public void onResolutionUpdate(android.hardware.camera2.extension.Size size) {
            mProcessor.onResolutionUpdate(new android.util.Size(size.width, size.height));
        }

        @Override
        public void onImageFormatUpdate(int imageFormat) {
            mProcessor.onImageFormatUpdate(imageFormat);
        }

        @Override
        public void process(android.hardware.camera2.extension.ParcelImage image,
                CameraMetadataNative result, int sequenceId) {
            mProcessor.process(new ExtensionImage(image),
                    new TotalCaptureResult(result, sequenceId));
        }
    }

    private class RequestUpdateProcessorImplStub extends IRequestUpdateProcessorImpl.Stub {
        private final RequestUpdateProcessorImpl mProcessor;

        public RequestUpdateProcessorImplStub(RequestUpdateProcessorImpl processor) {
            mProcessor = processor;
        }

        @Override
        public void onOutputSurface(Surface surface, int imageFormat) {
            mProcessor.onOutputSurface(surface, imageFormat);
        }

        @Override
        public void onResolutionUpdate(android.hardware.camera2.extension.Size size) {
            mProcessor.onResolutionUpdate(new android.util.Size(size.width, size.height));
        }

        @Override
        public void onImageFormatUpdate(int imageFormat) {
            mProcessor.onImageFormatUpdate(imageFormat);
        }

        @Override
        public CaptureStageImpl process(CameraMetadataNative result, int sequenceId) {
            return initializeParcelable(
                    mProcessor.process(new TotalCaptureResult(result, sequenceId)));
        }
    }

    private class ExtensionImage extends android.media.Image {
        private final android.hardware.camera2.extension.ParcelImage mParcelImage;
        private GraphicBuffer mGraphicBuffer;
        private ImageReader.ImagePlane[] mPlanes;

        private ExtensionImage(android.hardware.camera2.extension.ParcelImage parcelImage) {
            mParcelImage = parcelImage;
            mIsImageValid = true;
        }

        @Override
        public int getFormat() {
            throwISEIfImageIsInvalid();
            return mParcelImage.format;
        }

        @Override
        public int getWidth() {
            throwISEIfImageIsInvalid();
            return mParcelImage.width;
        }

        @Override
        public HardwareBuffer getHardwareBuffer() {
            throwISEIfImageIsInvalid();
            return mParcelImage.buffer;
        }

        @Override
        public int getHeight() {
            throwISEIfImageIsInvalid();
            return mParcelImage.height;
        }

        @Override
        public long getTimestamp() {
            throwISEIfImageIsInvalid();
            return mParcelImage.timestamp;
        }

        @Override
        public int getTransform() {
            throwISEIfImageIsInvalid();
            return mParcelImage.transform;
        }

        @Override
        public int getScalingMode() {
            throwISEIfImageIsInvalid();
            return mParcelImage.scalingMode;
        }

        @Override
        public Plane[] getPlanes() {
            throwISEIfImageIsInvalid();
            if (mPlanes == null) {
                int fenceFd = mParcelImage.fence != null ? mParcelImage.fence.getFd() : -1;
                mGraphicBuffer = GraphicBuffer.createFromHardwareBuffer(mParcelImage.buffer);
                mPlanes = ImageReader.initializeImagePlanes(mParcelImage.planeCount, mGraphicBuffer,
                        fenceFd, mParcelImage.format, mParcelImage.timestamp,
                        mParcelImage.transform, mParcelImage.scalingMode, mParcelImage.crop);
            }
            // Shallow copy is fine.
            return mPlanes.clone();
        }

        @Override
        protected final void finalize() throws Throwable {
            try {
                close();
            } finally {
                super.finalize();
            }
        }

        @Override
        public boolean isAttachable() {
            throwISEIfImageIsInvalid();
            // Clients must always detach parcelable images
            return true;
        }

        @Override
        public Rect getCropRect() {
            throwISEIfImageIsInvalid();
            return mParcelImage.crop;
        }

        @Override
        public void close() {
            mIsImageValid = false;

            if (mGraphicBuffer != null) {
                ImageReader.unlockGraphicBuffer(mGraphicBuffer);
                mGraphicBuffer.destroy();
                mGraphicBuffer = null;
            }

            if (mPlanes != null) {
                mPlanes = null;
            }

            if (mParcelImage.buffer != null) {
                mParcelImage.buffer.close();
                mParcelImage.buffer = null;
            }

            if (mParcelImage.fence != null) {
                try {
                    mParcelImage.fence.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mParcelImage.fence = null;
            }
        }
    }
}
