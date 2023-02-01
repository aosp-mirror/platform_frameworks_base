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
package com.android.cameraextensions;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.extension.CameraOutputConfig;
import android.hardware.camera2.extension.CameraSessionConfig;
import android.hardware.camera2.extension.CaptureBundle;
import android.hardware.camera2.extension.CaptureFailure;
import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.ICameraExtensionsProxyService;
import android.hardware.camera2.extension.ICaptureCallback;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IImageProcessorImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.IPreviewImageProcessorImpl;
import android.hardware.camera2.extension.IProcessResultImpl;
import android.hardware.camera2.extension.IRequestCallback;
import android.hardware.camera2.extension.IRequestProcessorImpl;
import android.hardware.camera2.extension.IRequestUpdateProcessorImpl;
import android.hardware.camera2.extension.ISessionProcessorImpl;
import android.hardware.camera2.extension.LatencyPair;
import android.hardware.camera2.extension.LatencyRange;
import android.hardware.camera2.extension.OutputConfigId;
import android.hardware.camera2.extension.OutputSurface;
import android.hardware.camera2.extension.ParcelCaptureResult;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.extension.ParcelTotalCaptureResult;
import android.hardware.camera2.extension.Request;
import android.hardware.camera2.extension.SizeList;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
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
import androidx.camera.extensions.impl.ProcessResultImpl;
import androidx.camera.extensions.impl.RequestUpdateProcessorImpl;
import androidx.camera.extensions.impl.advanced.AdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.AutoAdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.BeautyAdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.BokehAdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.Camera2OutputConfigImpl;
import androidx.camera.extensions.impl.advanced.Camera2SessionConfigImpl;
import androidx.camera.extensions.impl.advanced.HdrAdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.ImageProcessorImpl;
import androidx.camera.extensions.impl.advanced.ImageReaderOutputConfigImpl;
import androidx.camera.extensions.impl.advanced.MultiResolutionImageReaderOutputConfigImpl;
import androidx.camera.extensions.impl.advanced.NightAdvancedExtenderImpl;
import androidx.camera.extensions.impl.advanced.OutputSurfaceConfigurationImpl;
import androidx.camera.extensions.impl.advanced.OutputSurfaceImpl;
import androidx.camera.extensions.impl.advanced.RequestProcessorImpl;
import androidx.camera.extensions.impl.advanced.SessionProcessorImpl;
import androidx.camera.extensions.impl.advanced.SurfaceOutputConfigImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CameraExtensionsProxyService extends Service {
    private static final String TAG = "CameraExtensionsProxyService";

    private static final String CAMERA_EXTENSION_VERSION_NAME =
            "androidx.camera.extensions.impl.ExtensionVersionImpl";
    private static final String LATEST_VERSION = "1.4.0";
    // No support for the init sequence
    private static final String NON_INIT_VERSION_PREFIX = "1.0";
    // Support advanced API and latency queries
    private static final String ADVANCED_VERSION_PREFIX = "1.2";
    // Support for the capture request & result APIs
    private static final String RESULTS_VERSION_PREFIX = "1.3";
    // Support for various latency improvements
    private static final String LATENCY_VERSION_PREFIX = "1.4";
    private static final String[] ADVANCED_VERSION_PREFIXES = {LATENCY_VERSION_PREFIX,
            ADVANCED_VERSION_PREFIX, RESULTS_VERSION_PREFIX };
    private static final String[] SUPPORTED_VERSION_PREFIXES = {LATENCY_VERSION_PREFIX,
            RESULTS_VERSION_PREFIX, ADVANCED_VERSION_PREFIX, "1.1", NON_INIT_VERSION_PREFIX};
    private static final boolean EXTENSIONS_PRESENT = checkForExtensions();
    private static final String EXTENSIONS_VERSION = EXTENSIONS_PRESENT ?
            (new ExtensionVersionImpl()).checkApiVersion(LATEST_VERSION) : null;
    private static final boolean ESTIMATED_LATENCY_API_SUPPORTED = checkForLatencyAPI();
    private static final boolean LATENCY_IMPROVEMENTS_SUPPORTED = EXTENSIONS_PRESENT &&
            (EXTENSIONS_VERSION.startsWith(LATENCY_VERSION_PREFIX));
    private static final boolean ADVANCED_API_SUPPORTED = checkForAdvancedAPI();
    private static final boolean INIT_API_SUPPORTED = EXTENSIONS_PRESENT &&
            (!EXTENSIONS_VERSION.startsWith(NON_INIT_VERSION_PREFIX));
    private static final boolean RESULT_API_SUPPORTED = EXTENSIONS_PRESENT &&
            (EXTENSIONS_VERSION.startsWith(RESULTS_VERSION_PREFIX) ||
            EXTENSIONS_VERSION.startsWith(LATENCY_VERSION_PREFIX));

    private HashMap<String, CameraCharacteristics> mCharacteristicsHashMap = new HashMap<>();
    private HashMap<String, Long> mMetadataVendorIdMap = new HashMap<>();
    private CameraManager mCameraManager;

    private static boolean checkForLatencyAPI() {
        if (!EXTENSIONS_PRESENT) {
            return false;
        }

        for (String advancedVersions : ADVANCED_VERSION_PREFIXES) {
            if (EXTENSIONS_VERSION.startsWith(advancedVersions)) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkForAdvancedAPI() {
        if (!checkForLatencyAPI()) {
            return false;
        }

        try {
            return (new ExtensionVersionImpl()).isAdvancedExtenderImplemented();
        } catch (NoSuchMethodError e) {
            // This could happen in case device specific extension implementations are using
            // an older extension API but incorrectly set the extension version.
        }

        return false;
    }

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
        private IInitializeSessionCallback mInitializeCb = null;

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
                if (INIT_API_SUPPORTED) {
                    if (mActiveClients.isEmpty()) {
                        InitializerFuture status = new InitializerFuture();
                        InitializerImpl.init(LATEST_VERSION, ctx, new InitializeHandler(status),
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
                        INIT_API_SUPPORTED) {
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

        private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                synchronized (mLock) {
                    mInitializeCb = null;
                }
            }
        };

        public boolean initializeSession(IInitializeSessionCallback cb) {
            synchronized (mLock) {
                if (mInitializeCb == null) {
                    mInitializeCb = cb;
                    try {
                        mInitializeCb.asBinder().linkToDeath(mDeathRecipient, 0);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                } else {
                    return false;
                }
            }
            return true;
        }

        public void releaseSession() {
            synchronized (mLock) {
                if (mInitializeCb != null) {
                    mInitializeCb.asBinder().unlinkToDeath(mDeathRecipient, 0);
                    mInitializeCb = null;
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
    public static boolean initializeSession(IInitializeSessionCallback cb) {
        if (!EXTENSIONS_PRESENT) {
            return false;
        }
        return CameraExtensionManagerGlobal.get().initializeSession(cb);
    }

    /**
     * @hide
     */
    public static void releaseSession() {
        if (!EXTENSIONS_PRESENT) {
            return;
        }
        CameraExtensionManagerGlobal.get().releaseSession();
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
            case CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH:
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

    /**
     * @hide
     */
    public static AdvancedExtenderImpl initializeAdvancedExtensionImpl(int extensionType) {
        switch (extensionType) {
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC:
                return new AutoAdvancedExtenderImpl();
            case CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH:
                return new BeautyAdvancedExtenderImpl();
            case CameraExtensionCharacteristics.EXTENSION_BOKEH:
                return new BokehAdvancedExtenderImpl();
            case CameraExtensionCharacteristics.EXTENSION_HDR:
                return new HdrAdvancedExtenderImpl();
            case CameraExtensionCharacteristics.EXTENSION_NIGHT:
                return new NightAdvancedExtenderImpl();
            default:
                throw new IllegalArgumentException("Unknown extension: " + extensionType);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // This will setup the camera vendor tag descriptor in the service process
        // along with all camera characteristics.
        try {
            mCameraManager = getSystemService(CameraManager.class);

            String [] cameraIds = mCameraManager.getCameraIdListNoLazy();
            if (cameraIds != null) {
                for (String cameraId : cameraIds) {
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                    mCharacteristicsHashMap.put(cameraId, chars);
                    Object thisClass = CameraCharacteristics.Key.class;
                    Class<CameraCharacteristics.Key<?>> keyClass =
                            (Class<CameraCharacteristics.Key<?>>)thisClass;
                    ArrayList<CameraCharacteristics.Key<?>> vendorKeys =
                            chars.getNativeMetadata().getAllVendorKeys(keyClass);
                    if ((vendorKeys != null) && !vendorKeys.isEmpty()) {
                        mMetadataVendorIdMap.put(cameraId, vendorKeys.get(0).getVendorId());
                    }
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

            if (!sizeList.sizes.isEmpty()) {
                ret.add(sizeList);
            }
        }

        return ret;
    }

    private static List<SizeList> initializeParcelable(
            Map<Integer, List<android.util.Size>> sizes) {
        if (sizes == null) {
            return null;
        }
        ArrayList<SizeList> ret = new ArrayList<>(sizes.size());
        for (Map.Entry<Integer, List<android.util.Size>> entry : sizes.entrySet()) {
            SizeList sizeList = new SizeList();
            sizeList.format = entry.getKey();
            sizeList.sizes = new ArrayList<>();
            for (android.util.Size size : entry.getValue()) {
                android.hardware.camera2.extension.Size sz =
                        new android.hardware.camera2.extension.Size();
                sz.width = size.getWidth();
                sz.height = size.getHeight();
                sizeList.sizes.add(sz);
            }
            ret.add(sizeList);
        }

        return ret;
    }

    private CameraMetadataNative initializeParcelableMetadata(
            List<Pair<CaptureRequest.Key, Object>> paramList, String cameraId) {
        if (paramList == null) {
            return null;
        }

        CameraMetadataNative ret = new CameraMetadataNative();
        if (mMetadataVendorIdMap.containsKey(cameraId)) {
            ret.setVendorId(mMetadataVendorIdMap.get(cameraId));
        }
        for (Pair<CaptureRequest.Key, Object> param : paramList) {
            ret.set(param.first, param.second);
        }

        return ret;
    }

    private CameraMetadataNative initializeParcelableMetadata(
            Map<CaptureRequest.Key<?>, Object> paramMap, String cameraId) {
        if (paramMap == null) {
            return null;
        }

        CameraMetadataNative ret = new CameraMetadataNative();
        if (mMetadataVendorIdMap.containsKey(cameraId)) {
            ret.setVendorId(mMetadataVendorIdMap.get(cameraId));
        }
        for (Map.Entry<CaptureRequest.Key<?>, Object> param : paramMap.entrySet()) {
            ret.set(((CaptureRequest.Key) param.getKey()), param.getValue());
        }

        return ret;
    }

    private android.hardware.camera2.extension.CaptureStageImpl initializeParcelable(
            androidx.camera.extensions.impl.CaptureStageImpl captureStage, String cameraId) {
        if (captureStage == null) {
            return null;
        }

        android.hardware.camera2.extension.CaptureStageImpl ret =
                new android.hardware.camera2.extension.CaptureStageImpl();
        ret.id = captureStage.getId();
        ret.parameters = initializeParcelableMetadata(captureStage.getParameters(), cameraId);

        return ret;
    }

    private Request initializeParcelable(RequestProcessorImpl.Request request, int requestId,
            String cameraId) {
        Request ret = new Request();
        ret.targetOutputConfigIds = new ArrayList<>();
        for (int id : request.getTargetOutputConfigIds()) {
            OutputConfigId configId = new OutputConfigId();
            configId.id = id;
            ret.targetOutputConfigIds.add(configId);
        }
        ret.templateId = request.getTemplateId();
        ret.parameters = initializeParcelableMetadata(request.getParameters(), cameraId);
        ret.requestId = requestId;
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

        private boolean checkCameraPermission() {
            int allowed = CameraExtensionsProxyService.this.checkPermission(
                    android.Manifest.permission.CAMERA, Binder.getCallingPid(),
                    Binder.getCallingUid());
            return (PackageManager.PERMISSION_GRANTED == allowed);
        }

        @Override
        public void initializeSession(IInitializeSessionCallback cb) {
            try {
                if (!checkCameraPermission()) {
                    Log.i(TAG, "Camera permission required for initializing capture session");
                    cb.onFailure();
                    return;
                }

                if (CameraExtensionsProxyService.initializeSession(cb)) {
                    cb.onSuccess();
                } else {
                    cb.onFailure();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Client doesn't respond!");
            }
        }

        @Override
        public void releaseSession() {
            if (checkCameraPermission()) {
                CameraExtensionsProxyService.releaseSession();
            }
        }

        @Override
        public boolean advancedExtensionsSupported() {
            return ADVANCED_API_SUPPORTED;
        }

        @Override
        public IAdvancedExtenderImpl initializeAdvancedExtension(int extensionType) {
            AdvancedExtenderImpl extension;
            try {
                extension = initializeAdvancedExtensionImpl(extensionType);
            } catch (IllegalArgumentException e) {
                return null;
            }

            return new AdvancedExtenderImplStub(extension);
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

    private class AdvancedExtenderImplStub extends IAdvancedExtenderImpl.Stub {
        private final AdvancedExtenderImpl mAdvancedExtender;

        public AdvancedExtenderImplStub(AdvancedExtenderImpl advancedExtender) {
            mAdvancedExtender = advancedExtender;
        }

        @Override
        public boolean isExtensionAvailable(String cameraId) {
            return mAdvancedExtender.isExtensionAvailable(cameraId, mCharacteristicsHashMap);
        }

        @Override
        public void init(String cameraId) {
            mAdvancedExtender.init(cameraId, mCharacteristicsHashMap);
        }

        @Override
        public List<SizeList> getSupportedPostviewResolutions(
                android.hardware.camera2.extension.Size captureSize) {
            Size sz = new Size(captureSize.width, captureSize.height);
            Map<Integer, List<Size>> supportedSizesMap =
                    mAdvancedExtender.getSupportedPostviewResolutions(sz);
            if (supportedSizesMap != null) {
                return initializeParcelable(supportedSizesMap);
            }

            return null;
        }

        @Override
        public List<SizeList> getSupportedPreviewOutputResolutions(String cameraId) {
            Map<Integer, List<Size>> supportedSizesMap =
                    mAdvancedExtender.getSupportedPreviewOutputResolutions(cameraId);
            if (supportedSizesMap != null) {
                return initializeParcelable(supportedSizesMap);
            }

            return null;
        }

        @Override
        public List<SizeList> getSupportedCaptureOutputResolutions(String cameraId) {
            Map<Integer, List<Size>> supportedSizesMap =
                    mAdvancedExtender.getSupportedCaptureOutputResolutions(cameraId);
            if (supportedSizesMap != null) {
                return initializeParcelable(supportedSizesMap);
            }

            return null;
        }

        @Override
        public LatencyRange getEstimatedCaptureLatencyRange(String cameraId,
                android.hardware.camera2.extension.Size outputSize, int format) {
            Size sz = new Size(outputSize.width, outputSize.height);
            Range<Long> latencyRange = mAdvancedExtender.getEstimatedCaptureLatencyRange(cameraId,
                    sz, format);
            if (latencyRange != null) {
                LatencyRange ret = new LatencyRange();
                ret.min = latencyRange.getLower();
                ret.max = latencyRange.getUpper();
                return ret;
            }

            return null;
        }

        @Override
        public ISessionProcessorImpl getSessionProcessor() {
            return new SessionProcessorImplStub(mAdvancedExtender.createSessionProcessor());
        }

        @Override
        public CameraMetadataNative getAvailableCaptureRequestKeys(String cameraId) {
            if (RESULT_API_SUPPORTED) {
                List<CaptureRequest.Key> supportedCaptureKeys =
                        mAdvancedExtender.getAvailableCaptureRequestKeys();

                if ((supportedCaptureKeys != null) && !supportedCaptureKeys.isEmpty()) {
                    CameraMetadataNative ret = new CameraMetadataNative();
                    long vendorId = mMetadataVendorIdMap.containsKey(cameraId) ?
                            mMetadataVendorIdMap.get(cameraId) : Long.MAX_VALUE;
                    ret.setVendorId(vendorId);
                    int requestKeyTags [] = new int[supportedCaptureKeys.size()];
                    int i = 0;
                    for (CaptureRequest.Key key : supportedCaptureKeys) {
                        requestKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                    }
                    ret.set(CameraCharacteristics.REQUEST_AVAILABLE_REQUEST_KEYS, requestKeyTags);

                    return ret;
                }
            }

            return null;
        }

        @Override
        public CameraMetadataNative getAvailableCaptureResultKeys(String cameraId) {
            if (RESULT_API_SUPPORTED) {
                List<CaptureResult.Key> supportedResultKeys =
                        mAdvancedExtender.getAvailableCaptureResultKeys();

                if ((supportedResultKeys != null) && !supportedResultKeys.isEmpty()) {
                    CameraMetadataNative ret = new CameraMetadataNative();
                    long vendorId = mMetadataVendorIdMap.containsKey(cameraId) ?
                            mMetadataVendorIdMap.get(cameraId) : Long.MAX_VALUE;
                    ret.setVendorId(vendorId);
                    int resultKeyTags [] = new int[supportedResultKeys.size()];
                    int i = 0;
                    for (CaptureResult.Key key : supportedResultKeys) {
                        resultKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                    }
                    ret.set(CameraCharacteristics.REQUEST_AVAILABLE_RESULT_KEYS, resultKeyTags);

                    return ret;
                }
            }

            return null;
        }

        @Override
        public boolean isCaptureProcessProgressAvailable() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mAdvancedExtender.isCaptureProcessProgressAvailable();
            }

            return false;
        }

        @Override
        public boolean isPostviewAvailable() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mAdvancedExtender.isPostviewAvailable();
            }

            return false;
        }
    }

    private class CaptureCallbackStub implements SessionProcessorImpl.CaptureCallback {
        private final ICaptureCallback mCaptureCallback;
        private final String mCameraId;

        private CaptureCallbackStub(ICaptureCallback captureCallback, String cameraId) {
            mCaptureCallback = captureCallback;
            mCameraId = cameraId;
        }

        @Override
        public void onCaptureStarted(int captureSequenceId, long timestamp) {
            if (mCaptureCallback != null) {
                try {
                    mCaptureCallback.onCaptureStarted(captureSequenceId, timestamp);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to notify capture start due to remote " +
                            "exception!");
                }
            }
        }

        @Override
        public void onCaptureProcessStarted(int captureSequenceId) {
            if (mCaptureCallback != null) {
                try {
                    mCaptureCallback.onCaptureProcessStarted(captureSequenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to notify capture process start due to remote " +
                            "exception!");
                }
            }
        }

        @Override
        public void onCaptureFailed(int captureSequenceId) {
            if (mCaptureCallback != null) {
                try {
                    mCaptureCallback.onCaptureFailed(captureSequenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to notify capture failure due to remote " +
                            "exception!");
                }
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int captureSequenceId) {
            if (mCaptureCallback != null) {
                try {
                    mCaptureCallback.onCaptureSequenceCompleted(captureSequenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to notify capture sequence end due to remote " +
                            "exception!");
                }
            }
        }

        @Override
        public void onCaptureSequenceAborted(int captureSequenceId) {
            if (mCaptureCallback != null) {
                try {
                    mCaptureCallback.onCaptureSequenceAborted(captureSequenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to notify capture sequence abort due to remote " +
                            "exception!");
                }
            }
        }

        @Override
        public void onCaptureCompleted(long timestamp, int requestId,
                Map<CaptureResult.Key, Object> result) {

            if (result == null) {
                Log.e(TAG, "Invalid capture result received!");
            }

            CameraMetadataNative captureResults = new CameraMetadataNative();
            if (mMetadataVendorIdMap.containsKey(mCameraId)) {
                captureResults.setVendorId(mMetadataVendorIdMap.get(mCameraId));
            }
            for (Map.Entry<CaptureResult.Key, Object> entry : result.entrySet()) {
                captureResults.set(entry.getKey(), entry.getValue());
            }

            try {
                mCaptureCallback.onCaptureCompleted(timestamp, requestId, captureResults);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture complete due to remote exception!");
            }
        }

        @Override
        public void onCaptureProcessProgressed(int progress) {
            try {
                mCaptureCallback.onCaptureProcessProgressed(progress);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote client doesn't respond to capture progress callbacks!");
            }
        }
    }

    private class RequestCallbackStub extends IRequestCallback.Stub {
        private final List<RequestProcessorImpl.Request> mRequests;
        private final RequestProcessorImpl.Callback mCallback;

        public RequestCallbackStub(List<RequestProcessorImpl.Request> requests,
                RequestProcessorImpl.Callback callback) {
            mCallback = callback;
            if (mCallback != null) {
                mRequests = requests;
            } else {
                Log.w(TAG, "No valid request callbacks!");
                mRequests = new ArrayList<>();
            }
        }

        @Override
        public void onCaptureStarted(int requestId, long frameNumber, long timestamp) {
            if (mCallback != null) {
                if (mRequests.get(requestId) != null) {
                    mCallback.onCaptureStarted(mRequests.get(requestId), frameNumber, timestamp);
                } else {
                    Log.e(TAG,"Request id: " + requestId + " not found!");
                }
            }
        }

        @Override
        public void onCaptureProgressed(int requestId, ParcelCaptureResult partialResult) {
            if (mCallback != null) {
                if (mRequests.get(requestId) != null) {
                    CaptureResult result = new CaptureResult(partialResult.cameraId,
                            partialResult.results, partialResult.parent, partialResult.sequenceId,
                            partialResult.frameNumber);
                    mCallback.onCaptureProgressed(mRequests.get(requestId), result);
                } else {
                    Log.e(TAG,"Request id: " + requestId + " not found!");
                }
            }
        }

        @Override
        public void onCaptureCompleted(int requestId, ParcelTotalCaptureResult totalCaptureResult) {
            if (mCallback != null) {
                if (mRequests.get(requestId) != null) {
                    PhysicalCaptureResultInfo[] physicalResults = new PhysicalCaptureResultInfo[0];
                    if ((totalCaptureResult.physicalResult != null) &&
                            (!totalCaptureResult.physicalResult.isEmpty())) {
                        int count = totalCaptureResult.physicalResult.size();
                        physicalResults = new PhysicalCaptureResultInfo[count];
                        physicalResults = totalCaptureResult.physicalResult.toArray(
                                physicalResults);
                    }
                    ArrayList<CaptureResult> partials = new ArrayList<>(
                            totalCaptureResult.partials.size());
                    for (ParcelCaptureResult parcelResult : totalCaptureResult.partials) {
                        partials.add(new CaptureResult(parcelResult.cameraId, parcelResult.results,
                                parcelResult.parent, parcelResult.sequenceId,
                                parcelResult.frameNumber));
                    }
                    TotalCaptureResult result = new TotalCaptureResult(
                            totalCaptureResult.logicalCameraId, totalCaptureResult.results,
                            totalCaptureResult.parent, totalCaptureResult.sequenceId,
                            totalCaptureResult.frameNumber, partials, totalCaptureResult.sessionId,
                            physicalResults);
                    mCallback.onCaptureCompleted(mRequests.get(requestId), result);
                } else {
                    Log.e(TAG,"Request id: " + requestId + " not found!");
                }
            }
        }

        @Override
        public void onCaptureFailed(int requestId, CaptureFailure captureFailure) {
            if (mCallback != null) {
                if (mRequests.get(requestId) != null) {
                    android.hardware.camera2.CaptureFailure failure =
                            new android.hardware.camera2.CaptureFailure(captureFailure.request,
                                    captureFailure.reason, captureFailure.dropped,
                                    captureFailure.sequenceId, captureFailure.frameNumber,
                                    captureFailure.errorPhysicalCameraId);
                    mCallback.onCaptureFailed(mRequests.get(requestId), failure);
                } else {
                    Log.e(TAG,"Request id: " + requestId + " not found!");
                }
            }
        }

        @Override
        public void onCaptureBufferLost(int requestId, long frameNumber, int outputStreamId) {
            if (mCallback != null) {
                if (mRequests.get(requestId) != null) {
                    mCallback.onCaptureBufferLost(mRequests.get(requestId), frameNumber,
                            outputStreamId);
                } else {
                    Log.e(TAG,"Request id: " + requestId + " not found!");
                }
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int sequenceId, long frameNumber) {
            if (mCallback != null) {
                mCallback.onCaptureSequenceCompleted(sequenceId, frameNumber);
            }
        }

        @Override
        public void onCaptureSequenceAborted(int sequenceId) {
            if (mCallback != null) {
                mCallback.onCaptureSequenceAborted(sequenceId);
            }
        }
    }

    private class ImageProcessorImplStub extends IImageProcessorImpl.Stub {
        private final ImageProcessorImpl mImageProcessor;

        public ImageProcessorImplStub(ImageProcessorImpl imageProcessor) {
            mImageProcessor = imageProcessor;
        }

        @Override
        public void onNextImageAvailable(OutputConfigId outputConfigId, ParcelImage img,
                String physicalCameraId) {
            if (mImageProcessor != null) {
                mImageProcessor.onNextImageAvailable(outputConfigId.id, img.timestamp,
                        new ImageReferenceImpl(img), physicalCameraId);
            }
        }
    }

    private class RequestProcessorStub implements RequestProcessorImpl {
        private final IRequestProcessorImpl mRequestProcessor;
        private final String mCameraId;

        public RequestProcessorStub(IRequestProcessorImpl requestProcessor, String cameraId) {
            mRequestProcessor = requestProcessor;
            mCameraId = cameraId;
        }

        @Override
        public void setImageProcessor(int outputConfigId,
                ImageProcessorImpl imageProcessor) {
            OutputConfigId  configId = new OutputConfigId();
            configId.id = outputConfigId;
            try {
                mRequestProcessor.setImageProcessor(configId,
                        new ImageProcessorImplStub(imageProcessor));
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set image processor due to remote exception!");
            }
        }

        @Override
        public int submit(Request request, Callback callback) {
            ArrayList<Request> requests = new ArrayList<>();
            requests.add(request);
            return submit(requests, callback);
        }

        @Override
        public int submit(List<Request> requests, Callback callback) {
            ArrayList<android.hardware.camera2.extension.Request> captureRequests =
                    new ArrayList<>();
            int requestId = 0;
            for (Request request : requests) {
                captureRequests.add(initializeParcelable(request, requestId, mCameraId));
                requestId++;
            }

            try {
                return mRequestProcessor.submitBurst(captureRequests,
                        new RequestCallbackStub(requests, callback));
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to submit request due to remote exception!");
            }
            return -1;
        }

        @Override
        public int setRepeating(Request request, Callback callback) {
            try {
                ArrayList<Request> requests = new ArrayList<>();
                requests.add(request);
                return mRequestProcessor.setRepeating(
                        initializeParcelable(request, 0, mCameraId),
                        new RequestCallbackStub(requests, callback));
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to submit repeating request due to remote exception!");
            }

            return -1;
        }

        @Override
        public void abortCaptures() {
            try {
                mRequestProcessor.abortCaptures();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to abort requests due to remote exception!");
            }
        }

        @Override
        public void stopRepeating() {
            try {
                mRequestProcessor.stopRepeating();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to stop repeating request due to remote exception!");
            }
        }
    }

    private class SessionProcessorImplStub extends ISessionProcessorImpl.Stub {
        private final SessionProcessorImpl mSessionProcessor;
        private String mCameraId = null;

        public SessionProcessorImplStub(SessionProcessorImpl sessionProcessor) {
            mSessionProcessor = sessionProcessor;
        }

        @Override
        public CameraSessionConfig initSession(String cameraId, OutputSurface previewSurface,
                OutputSurface imageCaptureSurface, OutputSurface postviewSurface) {
            OutputSurfaceImplStub outputPreviewSurfaceImpl =
                    new OutputSurfaceImplStub(previewSurface);
            OutputSurfaceImplStub outputImageCaptureSurfaceImpl =
                    new OutputSurfaceImplStub(imageCaptureSurface);
            OutputSurfaceImplStub outputPostviewSurfaceImpl =
                    new OutputSurfaceImplStub(postviewSurface);

            Camera2SessionConfigImpl sessionConfig;

            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                OutputSurfaceConfigurationImplStub outputSurfaceConfigs =
                        new OutputSurfaceConfigurationImplStub(outputPreviewSurfaceImpl,
                        // Image Analysis Output is currently only supported in CameraX
                        outputImageCaptureSurfaceImpl, null /*imageAnalysisSurfaceConfig*/,
                        outputPostviewSurfaceImpl);

                sessionConfig = mSessionProcessor.initSession(cameraId,
                        mCharacteristicsHashMap, getApplicationContext(), outputSurfaceConfigs);
            } else {
                sessionConfig = mSessionProcessor.initSession(cameraId,
                        mCharacteristicsHashMap, getApplicationContext(), outputPreviewSurfaceImpl,
                        outputImageCaptureSurfaceImpl, null /*imageAnalysisSurfaceConfig*/);
            }

            List<Camera2OutputConfigImpl> outputConfigs = sessionConfig.getOutputConfigs();
            CameraSessionConfig ret = new CameraSessionConfig();
            ret.outputConfigs = new ArrayList<>();
            for (Camera2OutputConfigImpl output : outputConfigs) {
                CameraOutputConfig entry = getCameraOutputConfig(output);
                List<Camera2OutputConfigImpl> sharedOutputs =
                        output.getSurfaceSharingOutputConfigs();
                if ((sharedOutputs != null) && (!sharedOutputs.isEmpty())) {
                    entry.sharedSurfaceConfigs = new ArrayList<>();
                    for (Camera2OutputConfigImpl sharedOutput : sharedOutputs) {
                        entry.sharedSurfaceConfigs.add(getCameraOutputConfig(sharedOutput));
                    }
                }
                ret.outputConfigs.add(entry);
            }
            ret.sessionTemplateId = sessionConfig.getSessionTemplateId();
            ret.sessionType = -1;
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                ret.sessionType = sessionConfig.getSessionType();
            }
            ret.sessionParameter = initializeParcelableMetadata(
                    sessionConfig.getSessionParameters(), cameraId);
            mCameraId = cameraId;

            return ret;
        }

        @Override
        public void deInitSession() {
            mSessionProcessor.deInitSession();
        }

        @Override
        public void onCaptureSessionStart(IRequestProcessorImpl requestProcessor) {
            mSessionProcessor.onCaptureSessionStart(
                    new RequestProcessorStub(requestProcessor, mCameraId));
        }

        @Override
        public void onCaptureSessionEnd() {
            mSessionProcessor.onCaptureSessionEnd();
        }

        @Override
        public int startRepeating(ICaptureCallback callback) {
            return mSessionProcessor.startRepeating(new CaptureCallbackStub(callback, mCameraId));
        }

        @Override
        public void stopRepeating() {
            mSessionProcessor.stopRepeating();
        }

        @Override
        public void setParameters(CaptureRequest captureRequest) {
            HashMap<CaptureRequest.Key<?>, Object> paramMap = new HashMap<>();
            for (CaptureRequest.Key captureRequestKey : captureRequest.getKeys()) {
                paramMap.put(captureRequestKey, captureRequest.get(captureRequestKey));
            }

            mSessionProcessor.setParameters(paramMap);
        }

        @Override
        public int startTrigger(CaptureRequest captureRequest, ICaptureCallback callback) {
            HashMap<CaptureRequest.Key<?>, Object> triggerMap = new HashMap<>();
            for (CaptureRequest.Key captureRequestKey : captureRequest.getKeys()) {
                triggerMap.put(captureRequestKey, captureRequest.get(captureRequestKey));
            }

            return mSessionProcessor.startTrigger(triggerMap,
                    new CaptureCallbackStub(callback, mCameraId));
        }

        @Override
        public int startCapture(ICaptureCallback callback, boolean isPostviewRequested) {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return isPostviewRequested ? mSessionProcessor.startCaptureWithPostview(
                        new CaptureCallbackStub(callback, mCameraId)) :
                        mSessionProcessor.startCapture(new CaptureCallbackStub(callback,
                        mCameraId));
            }

            return mSessionProcessor.startCapture(new CaptureCallbackStub(callback, mCameraId));
        }

        @Override
        public LatencyPair getRealtimeCaptureLatency() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                Pair<Long, Long> latency = mSessionProcessor.getRealtimeCaptureLatency();
                if (latency != null) {
                    LatencyPair ret = new LatencyPair();
                    ret.first = latency.first;
                    ret.second = latency.second;
                    return ret;
                }
            }

            return null;
        }
    }

    private class OutputSurfaceConfigurationImplStub implements OutputSurfaceConfigurationImpl {
        private OutputSurfaceImpl mOutputPreviewSurfaceImpl;
        private OutputSurfaceImpl mOutputImageCaptureSurfaceImpl;
        private OutputSurfaceImpl mOutputImageAnalysisSurfaceImpl;
        private OutputSurfaceImpl mOutputPostviewSurfaceImpl;

        public OutputSurfaceConfigurationImplStub(OutputSurfaceImpl previewOutput,
                OutputSurfaceImpl imageCaptureOutput, OutputSurfaceImpl imageAnalysisOutput,
                OutputSurfaceImpl postviewOutput) {
            mOutputPreviewSurfaceImpl = previewOutput;
            mOutputImageCaptureSurfaceImpl = imageCaptureOutput;
            mOutputImageAnalysisSurfaceImpl = imageAnalysisOutput;
            mOutputPostviewSurfaceImpl = postviewOutput;
        }

        @Override
        public OutputSurfaceImpl getPreviewOutputSurface() {
            return mOutputPreviewSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getImageCaptureOutputSurface() {
            return mOutputImageCaptureSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getImageAnalysisOutputSurface() {
            return mOutputImageAnalysisSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getPostviewOutputSurface() {
            return mOutputPostviewSurfaceImpl;
        }
    }

    private class OutputSurfaceImplStub implements OutputSurfaceImpl {
        private final Surface mSurface;
        private final Size mSize;
        private final int mImageFormat;

        public OutputSurfaceImplStub(OutputSurface outputSurface) {
            mSurface = outputSurface.surface;
            mSize = new Size(outputSurface.size.width, outputSurface.size.height);
            mImageFormat = outputSurface.imageFormat;
        }

        @Override
        public Surface getSurface() {
            return mSurface;
        }

        @Override
        public Size getSize() {
            return mSize;
        }

        @Override
        public int getImageFormat() {
            return mImageFormat;
        }
    }

    private class PreviewExtenderImplStub extends IPreviewExtenderImpl.Stub {
        private final PreviewExtenderImpl mPreviewExtender;
        private String mCameraId = null;

        public PreviewExtenderImplStub(PreviewExtenderImpl previewExtender) {
            mPreviewExtender = previewExtender;
        }

        @Override
        public void onInit(String cameraId, CameraMetadataNative cameraCharacteristics) {
            mCameraId = cameraId;
            CameraCharacteristics chars = new CameraCharacteristics(cameraCharacteristics);
            mCameraManager.registerDeviceStateListener(chars);
            mPreviewExtender.onInit(cameraId, chars, CameraExtensionsProxyService.this);
        }

        @Override
        public void onDeInit() {
            mPreviewExtender.onDeInit();
        }

        @Override
        public CaptureStageImpl onPresetSession() {
            return initializeParcelable(mPreviewExtender.onPresetSession(), mCameraId);
        }

        @Override
        public CaptureStageImpl onEnableSession() {
            return initializeParcelable(mPreviewExtender.onEnableSession(), mCameraId);
        }

        @Override
        public CaptureStageImpl onDisableSession() {
            return initializeParcelable(mPreviewExtender.onDisableSession(), mCameraId);
        }

        @Override
        public void init(String cameraId, CameraMetadataNative chars) {
            CameraCharacteristics c = new CameraCharacteristics(chars);
            mCameraManager.registerDeviceStateListener(c);
            mPreviewExtender.init(cameraId, c);
        }

        @Override
        public boolean isExtensionAvailable(String cameraId, CameraMetadataNative chars) {
            CameraCharacteristics c = new CameraCharacteristics(chars);
            mCameraManager.registerDeviceStateListener(c);
            return mPreviewExtender.isExtensionAvailable(cameraId, c);
        }

        @Override
        public CaptureStageImpl getCaptureStage() {
            return initializeParcelable(mPreviewExtender.getCaptureStage(), mCameraId);
        }

        @Override
        public int getSessionType() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mPreviewExtender.onSessionType();
            }

            return -1;
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
                return new PreviewImageProcessorImplStub(processor, mCameraId);
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
                return new RequestUpdateProcessorImplStub(processor, mCameraId);
            }

            return null;
        }

        @Override
        public List<SizeList> getSupportedResolutions() {
            if (INIT_API_SUPPORTED) {
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
        private String mCameraId = null;

        public ImageCaptureExtenderImplStub(ImageCaptureExtenderImpl imageExtender) {
            mImageExtender = imageExtender;
        }

        @Override
        public void onInit(String cameraId, CameraMetadataNative cameraCharacteristics) {
            CameraCharacteristics chars = new CameraCharacteristics(cameraCharacteristics);
            mCameraManager.registerDeviceStateListener(chars);
            mImageExtender.onInit(cameraId, chars, CameraExtensionsProxyService.this);
            mCameraId = cameraId;
        }

        @Override
        public void onDeInit() {
            mImageExtender.onDeInit();
        }

        @Override
        public CaptureStageImpl onPresetSession() {
            return initializeParcelable(mImageExtender.onPresetSession(), mCameraId);
        }

        @Override
        public boolean isCaptureProcessProgressAvailable() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mImageExtender.isCaptureProcessProgressAvailable();
            }

            return false;
        }

        @Override
        public boolean isPostviewAvailable() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mImageExtender.isPostviewAvailable();
            }

            return false;
        }

        @Override
        public CaptureStageImpl onEnableSession() {
            return initializeParcelable(mImageExtender.onEnableSession(), mCameraId);
        }

        @Override
        public CaptureStageImpl onDisableSession() {
            return initializeParcelable(mImageExtender.onDisableSession(), mCameraId);
        }

        @Override
        public int getSessionType() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                return mImageExtender.onSessionType();
            }

            return -1;
        }

        @Override
        public void init(String cameraId, CameraMetadataNative chars) {
            CameraCharacteristics c = new CameraCharacteristics(chars);
            mCameraManager.registerDeviceStateListener(c);
            mImageExtender.init(cameraId, c);
        }

        @Override
        public boolean isExtensionAvailable(String cameraId, CameraMetadataNative chars) {
            CameraCharacteristics c = new CameraCharacteristics(chars);
            mCameraManager.registerDeviceStateListener(c);
            return mImageExtender.isExtensionAvailable(cameraId, c);
        }

        @Override
        public ICaptureProcessorImpl getCaptureProcessor() {
            CaptureProcessorImpl captureProcessor = mImageExtender.getCaptureProcessor();
            if (captureProcessor != null) {
                return new CaptureProcessorImplStub(captureProcessor, mCameraId);
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
                    ret.add(initializeParcelable(stage, mCameraId));
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
            if (INIT_API_SUPPORTED) {
                List<Pair<Integer, android.util.Size[]>> sizes =
                        mImageExtender.getSupportedResolutions();
                if ((sizes != null) && !sizes.isEmpty()) {
                    return initializeParcelable(sizes);
                }
            }

            return null;
        }

        @Override
        public List<SizeList> getSupportedPostviewResolutions(
                android.hardware.camera2.extension.Size captureSize) {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                Size sz = new Size(captureSize.width, captureSize.height);
                List<Pair<Integer, android.util.Size[]>> sizes =
                        mImageExtender.getSupportedPostviewResolutions(sz);
                if ((sizes != null) && !sizes.isEmpty()) {
                    return initializeParcelable(sizes);
                }
            }

            return null;
        }

        @Override
        public LatencyRange getEstimatedCaptureLatencyRange(
                android.hardware.camera2.extension.Size outputSize) {
            if (ESTIMATED_LATENCY_API_SUPPORTED) {
                Size sz = new Size(outputSize.width, outputSize.height);
                Range<Long> latencyRange = mImageExtender.getEstimatedCaptureLatencyRange(sz);
                if (latencyRange != null) {
                    LatencyRange ret = new LatencyRange();
                    ret.min = latencyRange.getLower();
                    ret.max = latencyRange.getUpper();
                    return ret;
                }
            }

            return null;
        }

        @Override
        public LatencyPair getRealtimeCaptureLatency() {
            if (LATENCY_IMPROVEMENTS_SUPPORTED) {
                Pair<Long, Long> latency = mImageExtender.getRealtimeCaptureLatency();
                if (latency != null) {
                    LatencyPair ret = new LatencyPair();
                    ret.first = latency.first;
                    ret.second = latency.second;
                    return ret;
                }
            }

            return null;
        }

        @Override
        public CameraMetadataNative getAvailableCaptureRequestKeys() {
            if (RESULT_API_SUPPORTED) {
                List<CaptureRequest.Key> supportedCaptureKeys =
                        mImageExtender.getAvailableCaptureRequestKeys();

                if ((supportedCaptureKeys != null) && !supportedCaptureKeys.isEmpty()) {
                    CameraMetadataNative ret = new CameraMetadataNative();
                    long vendorId = mMetadataVendorIdMap.containsKey(mCameraId) ?
                            mMetadataVendorIdMap.get(mCameraId) : Long.MAX_VALUE;
                    ret.setVendorId(vendorId);
                    int requestKeyTags [] = new int[supportedCaptureKeys.size()];
                    int i = 0;
                    for (CaptureRequest.Key key : supportedCaptureKeys) {
                        requestKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                    }
                    ret.set(CameraCharacteristics.REQUEST_AVAILABLE_REQUEST_KEYS, requestKeyTags);

                    return ret;
                }
            }

            return null;
        }

        @Override
        public CameraMetadataNative getAvailableCaptureResultKeys() {
            if (RESULT_API_SUPPORTED) {
                List<CaptureResult.Key> supportedResultKeys =
                        mImageExtender.getAvailableCaptureResultKeys();

                if ((supportedResultKeys != null) && !supportedResultKeys.isEmpty()) {
                    CameraMetadataNative ret = new CameraMetadataNative();
                    long vendorId = mMetadataVendorIdMap.containsKey(mCameraId) ?
                            mMetadataVendorIdMap.get(mCameraId) : Long.MAX_VALUE;
                    ret.setVendorId(vendorId);
                    int resultKeyTags [] = new int[supportedResultKeys.size()];
                    int i = 0;
                    for (CaptureResult.Key key : supportedResultKeys) {
                        resultKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                    }
                    ret.set(CameraCharacteristics.REQUEST_AVAILABLE_RESULT_KEYS, resultKeyTags);

                    return ret;
                }
            }

            return null;
        }
    }

    private class ProcessResultCallback implements ProcessResultImpl {
        private final IProcessResultImpl mProcessResult;
        private final String mCameraId;

        private ProcessResultCallback(IProcessResultImpl processResult, String cameraId) {
            mProcessResult = processResult;
            mCameraId = cameraId;
        }

        @Override
        public void onCaptureProcessProgressed(int progress) {
            try {
                mProcessResult.onCaptureProcessProgressed(progress);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote client doesn't respond to capture progress callbacks!");
            }
        }

        @Override
        public void onCaptureCompleted(long shutterTimestamp,
                List<Pair<CaptureResult.Key, Object>> result) {
            if (result == null) {
                Log.e(TAG, "Invalid capture result received!");
            }

            CameraMetadataNative captureResults = new CameraMetadataNative();
            if (mMetadataVendorIdMap.containsKey(mCameraId)) {
                captureResults.setVendorId(mMetadataVendorIdMap.get(mCameraId));
            }
            for (Pair<CaptureResult.Key, Object> pair : result) {
                captureResults.set(pair.first, pair.second);
            }

            try {
                mProcessResult.onCaptureCompleted(shutterTimestamp, captureResults);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote client doesn't respond to capture results!");
            }
        }
    }

    private class CaptureProcessorImplStub extends ICaptureProcessorImpl.Stub {
        private final CaptureProcessorImpl mCaptureProcessor;
        private final String mCameraId;

        public CaptureProcessorImplStub(CaptureProcessorImpl captureProcessor, String cameraId) {
            mCaptureProcessor = captureProcessor;
            mCameraId = cameraId;
        }

        @Override
        public void onOutputSurface(Surface surface, int imageFormat) {
            mCaptureProcessor.onOutputSurface(surface, imageFormat);
        }

        @Override
        public void onPostviewOutputSurface(Surface surface) {
            mCaptureProcessor.onPostviewOutputSurface(surface);
        }

        @Override
        public void onResolutionUpdate(android.hardware.camera2.extension.Size size,
                android.hardware.camera2.extension.Size postviewSize) {
            if (postviewSize != null) {
                mCaptureProcessor.onResolutionUpdate(
                        new android.util.Size(size.width, size.height),
                        new android.util.Size(postviewSize.width, postviewSize.height));
            } else {
                mCaptureProcessor.onResolutionUpdate(
                        new android.util.Size(size.width, size.height));
            }
        }

        @Override
        public void onImageFormatUpdate(int imageFormat) {
            mCaptureProcessor.onImageFormatUpdate(imageFormat);
        }

        @Override
        public void process(List<CaptureBundle> captureList, IProcessResultImpl resultCallback,
                boolean isPostviewRequested) {
            HashMap<Integer, Pair<Image, TotalCaptureResult>> captureMap = new HashMap<>();
            for (CaptureBundle captureBundle : captureList) {
                captureMap.put(captureBundle.stage, new Pair<> (
                        new ExtensionImage(captureBundle.captureImage),
                        new TotalCaptureResult(captureBundle.captureResult,
                                captureBundle.sequenceId)));
            }
            if (!captureMap.isEmpty()) {
                if ((LATENCY_IMPROVEMENTS_SUPPORTED) && (isPostviewRequested)) {
                    ProcessResultCallback processResultCallback = (resultCallback != null)
                            ? new ProcessResultCallback(resultCallback, mCameraId) : null;
                    mCaptureProcessor.processWithPostview(captureMap, processResultCallback,
                            null /*executor*/);
                } else if ((resultCallback != null) && (RESULT_API_SUPPORTED)) {
                    mCaptureProcessor.process(captureMap, new ProcessResultCallback(resultCallback,
                                    mCameraId), null /*executor*/);
                } else if (resultCallback == null) {
                    mCaptureProcessor.process(captureMap);
                } else {
                    Log.e(TAG, "Process requests with capture results are not supported!");
                }
            } else {
                Log.e(TAG, "Process request with absent capture stages!");
            }
        }
    }

    private class PreviewImageProcessorImplStub extends IPreviewImageProcessorImpl.Stub {
        private final PreviewImageProcessorImpl mProcessor;
        private final String mCameraId;

        public PreviewImageProcessorImplStub(PreviewImageProcessorImpl processor, String cameraId) {
            mProcessor = processor;
            mCameraId = cameraId;
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
                CameraMetadataNative result, int sequenceId, IProcessResultImpl resultCallback) {
            if ((resultCallback != null) && RESULT_API_SUPPORTED) {
                mProcessor.process(new ExtensionImage(image),
                        new TotalCaptureResult(result, sequenceId),
                        new ProcessResultCallback(resultCallback, mCameraId), null /*executor*/);
            } else if (resultCallback == null) {
                mProcessor.process(new ExtensionImage(image),
                        new TotalCaptureResult(result, sequenceId));
            } else {

            }
        }
    }

    private class RequestUpdateProcessorImplStub extends IRequestUpdateProcessorImpl.Stub {
        private final RequestUpdateProcessorImpl mProcessor;
        private final String mCameraId;

        public RequestUpdateProcessorImplStub(RequestUpdateProcessorImpl processor,
                String cameraId) {
            mProcessor = processor;
            mCameraId = cameraId;
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
                    mProcessor.process(new TotalCaptureResult(result, sequenceId)), mCameraId);
        }
    }

    private class ImageReferenceImpl extends ExtensionImage
            implements androidx.camera.extensions.impl.advanced.ImageReferenceImpl {

        private final Object mImageLock = new Object();
        private int mReferenceCount;

        private ImageReferenceImpl(ParcelImage parcelImage) {
            super(parcelImage);
            mReferenceCount = 1;
        }

        @Override
        public boolean increment() {
            synchronized (mImageLock) {
                if (mReferenceCount <= 0) {
                    return false;
                }
                mReferenceCount++;
            }

            return true;
        }

        @Override
        public boolean decrement() {
            synchronized (mImageLock) {
                if (mReferenceCount <= 0) {
                    return false;
                }
                mReferenceCount--;

                if (mReferenceCount <= 0) {
                    close();
                }
            }

            return true;
        }

        @Override
        public Image get() {
            return this;
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

    private static CameraOutputConfig getCameraOutputConfig(Camera2OutputConfigImpl output) {
        CameraOutputConfig ret = new CameraOutputConfig();
        ret.outputId = new OutputConfigId();
        ret.outputId.id = output.getId();
        ret.physicalCameraId = output.getPhysicalCameraId();
        ret.surfaceGroupId = output.getSurfaceGroupId();
        if (output instanceof SurfaceOutputConfigImpl) {
            SurfaceOutputConfigImpl surfaceConfig = (SurfaceOutputConfigImpl) output;
            ret.type = CameraOutputConfig.TYPE_SURFACE;
            ret.surface = surfaceConfig.getSurface();
        } else if (output instanceof ImageReaderOutputConfigImpl) {
            ImageReaderOutputConfigImpl imageReaderOutputConfig =
                    (ImageReaderOutputConfigImpl) output;
            ret.type = CameraOutputConfig.TYPE_IMAGEREADER;
            ret.size = new android.hardware.camera2.extension.Size();
            ret.size.width = imageReaderOutputConfig.getSize().getWidth();
            ret.size.height = imageReaderOutputConfig.getSize().getHeight();
            ret.imageFormat = imageReaderOutputConfig.getImageFormat();
            ret.capacity = imageReaderOutputConfig.getMaxImages();
        } else if (output instanceof MultiResolutionImageReaderOutputConfigImpl) {
            MultiResolutionImageReaderOutputConfigImpl multiResReaderConfig =
                    (MultiResolutionImageReaderOutputConfigImpl) output;
            ret.type = CameraOutputConfig.TYPE_MULTIRES_IMAGEREADER;
            ret.imageFormat = multiResReaderConfig.getImageFormat();
            ret.capacity = multiResReaderConfig.getMaxImages();
        } else {
            throw new IllegalStateException("Unknown output config type!");
        }

        return ret;
    }
}
