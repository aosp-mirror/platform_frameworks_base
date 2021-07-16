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
package android.hardware.camera2;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.ICameraExtensionsProxyService;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.LatencyRange;
import android.hardware.camera2.extension.SizeList;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import java.util.HashSet;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Objects;

/**
 * <p>Allows clients to query availability and supported resolutions of camera extensions.</p>
 *
 * <p>Camera extensions give camera clients access to device-specific algorithms and sequences that
 * can improve the overall image quality of snapshots in various cases such as low light, selfies,
 * portraits, and scenes that can benefit from enhanced dynamic range. Often such sophisticated
 * processing sequences will rely on multiple camera frames as input and will produce a single
 * output.</p>
 *
 * <p>Camera extensions are not guaranteed to be present on all devices so camera clients must
 * query for their availability via {@link CameraExtensionCharacteristics#getSupportedExtensions()}.
 * </p>
 *
 * <p>In order to use any available camera extension, camera clients must create a corresponding
 * {@link CameraExtensionSession} via
 * {@link CameraDevice#createExtensionSession(ExtensionSessionConfiguration)}</p>
 *
 * <p>Camera clients must be aware that device-specific camera extensions may support only a
 * subset of the available camera resolutions and must first query
 * {@link CameraExtensionCharacteristics#getExtensionSupportedSizes(int, int)} for supported
 * single high-quality request output sizes and
 * {@link CameraExtensionCharacteristics#getExtensionSupportedSizes(int, Class)} for supported
 * repeating request output sizes.</p>
 *
 * <p>The extension characteristics for a given device are expected to remain static under
 * normal operating conditions.</p>
 *
 * @see CameraManager#getCameraExtensionCharacteristics(String)
 */
public final class CameraExtensionCharacteristics {
    private static final String TAG = "CameraExtensionCharacteristics";

    /**
     * Device-specific extension implementation for automatic selection of particular extension
     * such as HDR or NIGHT depending on the current lighting and environment conditions.
     */
    public static final int EXTENSION_AUTOMATIC = 0;

    /**
     * Device-specific extension implementation which tends to smooth the skin and apply other
     * cosmetic effects to people's faces.
     */
    public static final int EXTENSION_BEAUTY = 1;

    /**
     * Device-specific extension implementation which can blur certain regions of the final image
     * thereby "enhancing" focus for all remaining non-blurred parts.
     */
    public static final int EXTENSION_BOKEH = 2;

    /**
     * Device-specific extension implementation for enhancing the dynamic range of the
     * final image.
     */
    public static final int EXTENSION_HDR = 3;

    /**
     * Device-specific extension implementation that aims to suppress noise and improve the
     * overall image quality under low light conditions.
     */
    public static final int EXTENSION_NIGHT = 4;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {EXTENSION_AUTOMATIC,
                EXTENSION_BEAUTY,
                EXTENSION_BOKEH,
                EXTENSION_HDR,
                EXTENSION_NIGHT})
    public @interface Extension {
    }

    /**
     * Default camera output in case additional processing from CameraX extensions is not needed
     *
     * @hide
     */
    public static final int NON_PROCESSING_INPUT_FORMAT = ImageFormat.PRIVATE;

    /**
     * CameraX extensions require YUV_420_888 as default input for processing at the moment
     *
     * @hide
     */
    public static final int PROCESSING_INPUT_FORMAT = ImageFormat.YUV_420_888;

    private static final @Extension
    int[] EXTENSION_LIST = new int[]{
            EXTENSION_AUTOMATIC,
            EXTENSION_BEAUTY,
            EXTENSION_BOKEH,
            EXTENSION_HDR,
            EXTENSION_NIGHT};

    private final Context mContext;
    private final String mCameraId;
    private final CameraCharacteristics mChars;

    /**
     * @hide
     */
    public CameraExtensionCharacteristics(Context context, String cameraId,
            CameraCharacteristics chars) {
        mContext = context;
        mCameraId = cameraId;
        mChars = chars;
    }

    private static ArrayList<Size> getSupportedSizes(List<SizeList> sizesList,
            Integer format) {
        ArrayList<Size> ret = new ArrayList<>();
        if ((sizesList != null) && (!sizesList.isEmpty())) {
            for (SizeList entry : sizesList) {
                if ((entry.format == format) && !entry.sizes.isEmpty()) {
                    for (android.hardware.camera2.extension.Size sz : entry.sizes) {
                        ret.add(new Size(sz.width, sz.height));
                    }
                    return ret;
                }
            }
        }

        return ret;
    }

    private static List<Size> generateSupportedSizes(List<SizeList> sizesList,
                                                     Integer format,
                                                     StreamConfigurationMap streamMap) {
        // Per API contract it is assumed that the extension is able to support all
        // camera advertised sizes for a given format in case it doesn't return
        // a valid non-empty size list.
        ArrayList<Size> ret = getSupportedSizes(sizesList, format);
        Size[] supportedSizes = streamMap.getOutputSizes(format);
        if ((ret.isEmpty()) && (supportedSizes != null)) {
            ret.addAll(Arrays.asList(supportedSizes));
        }
        return ret;
    }

    private static List<Size> generateJpegSupportedSizes(List<SizeList> sizesList,
            StreamConfigurationMap streamMap) {
        ArrayList<Size> extensionSizes = getSupportedSizes(sizesList, ImageFormat.YUV_420_888);
        HashSet<Size> supportedSizes = extensionSizes.isEmpty() ? new HashSet<>(Arrays.asList(
                streamMap.getOutputSizes(ImageFormat.YUV_420_888))) : new HashSet<>(extensionSizes);
        HashSet<Size> supportedJpegSizes = new HashSet<>(Arrays.asList(streamMap.getOutputSizes(
                ImageFormat.JPEG)));
        supportedSizes.retainAll(supportedJpegSizes);

        return new ArrayList<>(supportedSizes);
    }

    /**
     * A per-process global camera extension manager instance, to track and
     * initialize/release extensions depending on client activity.
     */
    private static final class CameraExtensionManagerGlobal {
        private static final String TAG = "CameraExtensionManagerGlobal";
        private static final String PROXY_PACKAGE_NAME = "com.android.cameraextensions";
        private static final String PROXY_SERVICE_NAME =
                "com.android.cameraextensions.CameraExtensionsProxyService";

        // Singleton instance
        private static final CameraExtensionManagerGlobal GLOBAL_CAMERA_MANAGER =
                new CameraExtensionManagerGlobal();
        private final Object mLock = new Object();
        private final int PROXY_SERVICE_DELAY_MS = 1000;
        private InitializerFuture mInitFuture = null;
        private ServiceConnection mConnection = null;
        private ICameraExtensionsProxyService mProxy = null;
        private boolean mSupportsAdvancedExtensions = false;

        // Singleton, don't allow construction
        private CameraExtensionManagerGlobal() {}

        public static CameraExtensionManagerGlobal get() {
            return GLOBAL_CAMERA_MANAGER;
        }

        private void connectToProxyLocked(Context ctx) {
            if (mConnection == null) {
                Intent intent = new Intent();
                intent.setClassName(PROXY_PACKAGE_NAME, PROXY_SERVICE_NAME);
                String vendorProxyPackage = SystemProperties.get(
                    "ro.vendor.camera.extensions.package");
                String vendorProxyService = SystemProperties.get(
                    "ro.vendor.camera.extensions.service");
                if (!vendorProxyPackage.isEmpty() && !vendorProxyService.isEmpty()) {
                  Log.v(TAG,
                      "Choosing the vendor camera extensions proxy package: "
                      + vendorProxyPackage);
                  Log.v(TAG,
                      "Choosing the vendor camera extensions proxy service: "
                      + vendorProxyService);
                  intent.setClassName(vendorProxyPackage, vendorProxyService);
                }
                mInitFuture = new InitializerFuture();
                mConnection = new ServiceConnection() {
                    @Override
                    public void onServiceDisconnected(ComponentName component) {
                        mInitFuture.setStatus(false);
                        mConnection = null;
                        mProxy = null;
                    }

                    @Override
                    public void onServiceConnected(ComponentName component, IBinder binder) {
                        mProxy = ICameraExtensionsProxyService.Stub.asInterface(binder);
                        mInitFuture.setStatus(true);
                        try {
                            mSupportsAdvancedExtensions = mProxy.advancedExtensionsSupported();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Remote IPC failed!");
                        }
                    }
                };
                ctx.bindService(intent, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT |
                        Context.BIND_ABOVE_CLIENT | Context.BIND_NOT_VISIBLE,
                        android.os.AsyncTask.THREAD_POOL_EXECUTOR, mConnection);

                try {
                    mInitFuture.get(PROXY_SERVICE_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    Log.e(TAG, "Timed out while initializing proxy service!");
                }
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

        public long registerClient(Context ctx) {
            synchronized (mLock) {
                connectToProxyLocked(ctx);
                if (mProxy != null) {
                    try {
                        return mProxy.registerClient();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to initialize extension! Extension service does "
                                + " not respond!");
                        return -1;
                    }
                } else {
                    return -1;
                }
            }
        }

        public void unregisterClient(long clientId) {
            synchronized (mLock) {
                if (mProxy != null) {
                    try {
                        mProxy.unregisterClient(clientId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to de-initialize extension! Extension service does"
                                + " not respond!");
                    }
                }
            }
        }

        public void initializeSession(IInitializeSessionCallback cb) throws RemoteException {
            synchronized (mLock) {
                if (mProxy != null) {
                    mProxy.initializeSession(cb);
                }
            }
        }

        public void releaseSession() {
            synchronized (mLock) {
                if (mProxy != null) {
                    try {
                        mProxy.releaseSession();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to release session! Extension service does"
                                + " not respond!");
                    }
                }
            }
        }

        public boolean areAdvancedExtensionsSupported() {
            return mSupportsAdvancedExtensions;
        }

        public IPreviewExtenderImpl initializePreviewExtension(int extensionType)
                throws RemoteException {
            synchronized (mLock) {
                if (mProxy != null) {
                    return mProxy.initializePreviewExtension(extensionType);
                } else {
                    return null;
                }
            }
        }

        public IImageCaptureExtenderImpl initializeImageExtension(int extensionType)
                throws RemoteException {
            synchronized (mLock) {
                if (mProxy != null) {
                    return mProxy.initializeImageExtension(extensionType);
                } else {
                    return null;
                }
            }
        }

        public IAdvancedExtenderImpl initializeAdvancedExtension(int extensionType)
                throws RemoteException {
            synchronized (mLock) {
                if (mProxy != null) {
                    return mProxy.initializeAdvancedExtension(extensionType);
                } else {
                    return null;
                }
            }
        }
    }

    /**
     * @hide
     */
    public static long registerClient(Context ctx) {
        return CameraExtensionManagerGlobal.get().registerClient(ctx);
    }

    /**
     * @hide
     */
    public static void unregisterClient(long clientId) {
        CameraExtensionManagerGlobal.get().unregisterClient(clientId);
    }

    /**
     * @hide
     */
    public static void initializeSession(IInitializeSessionCallback cb) throws RemoteException {
        CameraExtensionManagerGlobal.get().initializeSession(cb);
    }

    /**
     * @hide
     */
    public static void releaseSession() {
        CameraExtensionManagerGlobal.get().releaseSession();
    }

    /**
     * @hide
     */
    public static boolean areAdvancedExtensionsSupported() {
        return CameraExtensionManagerGlobal.get().areAdvancedExtensionsSupported();
    }

    /**
     * @hide
     */
    public static boolean isExtensionSupported(String cameraId, int extensionType,
            CameraCharacteristics chars) {
        if (areAdvancedExtensionsSupported()) {
            try {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extensionType);
                return extender.isExtensionAvailable(cameraId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to query extension availability! Extension service does not"
                        + " respond!");
                return false;
            }
        } else {
            Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders;
            try {
                extenders = initializeExtension(extensionType);
            } catch (IllegalArgumentException e) {
                return false;
            }

            try {
                return extenders.first.isExtensionAvailable(cameraId, chars.getNativeMetadata()) &&
                        extenders.second.isExtensionAvailable(cameraId, chars.getNativeMetadata());
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to query extension availability! Extension service does not"
                        + " respond!");
                return false;
            }
        }
    }

    /**
     * @hide
     */
    public static IAdvancedExtenderImpl initializeAdvancedExtension(@Extension int extensionType) {
        IAdvancedExtenderImpl extender;
        try {
            extender = CameraExtensionManagerGlobal.get().initializeAdvancedExtension(
                    extensionType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to initialize extension: " + extensionType);
        }

        if (extender == null) {
            throw new IllegalArgumentException("Unknown extension: " + extensionType);
        }

        return extender;
    }

    /**
     * @hide
     */
    public static Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> initializeExtension(
            @Extension int extensionType) {
        IPreviewExtenderImpl previewExtender;
        IImageCaptureExtenderImpl imageExtender;
        try {
            previewExtender =
                    CameraExtensionManagerGlobal.get().initializePreviewExtension(extensionType);
            imageExtender =
                    CameraExtensionManagerGlobal.get().initializeImageExtension(extensionType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to initialize extension: " + extensionType);
        }
        if ((imageExtender == null) || (previewExtender == null)) {
            throw new IllegalArgumentException("Unknown extension: " + extensionType);
        }

        return new Pair<>(previewExtender, imageExtender);
    }

    private static <T> boolean isOutputSupportedFor(Class<T> klass) {
        Objects.requireNonNull(klass, "klass must not be null");

        if (klass == android.graphics.SurfaceTexture.class) {
            return true;
        }

        return false;
    }

    /**
     * Return a list of supported device-specific extensions for a given camera device.
     *
     * @return non-modifiable list of available extensions
     */
    public @NonNull List<Integer> getSupportedExtensions() {
        ArrayList<Integer> ret = new ArrayList<>();
        long clientId = registerClient(mContext);
        if (clientId < 0) {
            return Collections.unmodifiableList(ret);
        }

        try {
            for (int extensionType : EXTENSION_LIST) {
                if (isExtensionSupported(mCameraId, extensionType, mChars)) {
                    ret.add(extensionType);
                }
            }
        } finally {
            unregisterClient(clientId);
        }

        return Collections.unmodifiableList(ret);
    }

    /**
     * Get a list of sizes compatible with {@code klass} to use as an output for the
     * repeating request
     * {@link CameraExtensionSession#setRepeatingRequest}.
     *
     * <p>Note that device-specific extensions are allowed to support only a subset
     * of the camera output surfaces and resolutions.
     * The {@link android.graphics.SurfaceTexture} class is guaranteed at least one size for
     * backward compatible cameras whereas other output classes are not guaranteed to be supported.
     * </p>
     *
     * @param extension the extension type
     * @param klass     a non-{@code null} {@link Class} object reference
     * @return non-modifiable list of available sizes or an empty list if the Surface output is not
     * supported
     * @throws NullPointerException     if {@code klass} was {@code null}
     * @throws IllegalArgumentException in case of  unsupported extension.
     */
    @NonNull
    public <T> List<Size> getExtensionSupportedSizes(@Extension int extension,
            @NonNull Class<T> klass) {
        if (!isOutputSupportedFor(klass)) {
            return new ArrayList<>();
        }
        // TODO: Revisit this code once the Extension preview processor output format
        //       ambiguity is resolved in b/169799538.

        long clientId = registerClient(mContext);
        if (clientId < 0) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mChars)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            StreamConfigurationMap streamMap = mChars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (areAdvancedExtensionsSupported()) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId);
                return generateSupportedSizes(
                        extender.getSupportedPreviewOutputResolutions(mCameraId),
                        ImageFormat.PRIVATE, streamMap);
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.first.init(mCameraId, mChars.getNativeMetadata());
                return generateSupportedSizes(extenders.first.getSupportedResolutions(),
                        ImageFormat.PRIVATE, streamMap);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension supported sizes! Extension service does"
                    + " not respond!");
            return new ArrayList<>();
        } finally {
            unregisterClient(clientId);
        }
    }

    /**
     * Check whether a given extension is available and return the
     * supported output surface resolutions that can be used for high-quality capture
     * requests via {@link CameraExtensionSession#capture}.
     *
     * <p>Note that device-specific extensions are allowed to support only a subset
     * of the camera resolutions advertised by
     * {@link StreamConfigurationMap#getOutputSizes}.</p>
     *
     * <p>Device-specific extensions currently support at most two
     * multi-frame capture surface formats. ImageFormat.JPEG will be supported by all
     * extensions and ImageFormat.YUV_420_888 may or may not be supported.</p>
     *
     * @param extension the extension type
     * @param format    device-specific extension output format
     * @return non-modifiable list of available sizes or an empty list if the format is not
     * supported.
     * @throws IllegalArgumentException in case of format different from ImageFormat.JPEG /
     *                                  ImageFormat.YUV_420_888; or unsupported extension.
     */
    public @NonNull
    List<Size> getExtensionSupportedSizes(@Extension int extension, int format) {
        try {
            long clientId = registerClient(mContext);
            if (clientId < 0) {
                throw new IllegalArgumentException("Unsupported extensions");
            }

            try {
                if (!isExtensionSupported(mCameraId, extension, mChars)) {
                    throw new IllegalArgumentException("Unsupported extension");
                }

                StreamConfigurationMap streamMap = mChars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (areAdvancedExtensionsSupported()) {
                    switch(format) {
                        case ImageFormat.YUV_420_888:
                        case ImageFormat.JPEG:
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported format: " + format);
                    }
                    IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                    extender.init(mCameraId);
                    return generateSupportedSizes(extender.getSupportedCaptureOutputResolutions(
                            mCameraId), format, streamMap);
                } else {
                    if (format == ImageFormat.YUV_420_888) {
                        Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                                initializeExtension(extension);
                        extenders.second.init(mCameraId, mChars.getNativeMetadata());
                        if (extenders.second.getCaptureProcessor() == null) {
                            // Extensions that don't implement any capture processor are limited to
                            // JPEG only!
                            return new ArrayList<>();
                        }
                        return generateSupportedSizes(extenders.second.getSupportedResolutions(),
                                format, streamMap);
                    } else if (format == ImageFormat.JPEG) {
                        Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                                initializeExtension(extension);
                        extenders.second.init(mCameraId, mChars.getNativeMetadata());
                        if (extenders.second.getCaptureProcessor() != null) {
                            // The framework will perform the additional encoding pass on the
                            // processed YUV_420 buffers.
                            return generateJpegSupportedSizes(
                                    extenders.second.getSupportedResolutions(), streamMap);
                        } else {
                            return generateSupportedSizes(null, format, streamMap);
                        }
                    } else {
                        throw new IllegalArgumentException("Unsupported format: " + format);
                    }
                }
            } finally {
                unregisterClient(clientId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension supported sizes! Extension service does"
                    + " not respond!");
            return new ArrayList<>();
        }
    }

    /**
     * Returns the estimated capture latency range in milliseconds for the
     * target capture resolution during the calls to {@link CameraExtensionSession#capture}. This
     * includes the time spent processing the multi-frame capture request along with any additional
     * time for encoding of the processed buffer if necessary.
     *
     * @param extension         the extension type
     * @param captureOutputSize size of the capture output surface. If it is not in the supported
     *                          output sizes, maximum capture output size is used for the estimation
     * @param format            device-specific extension output format
     * @return the range of estimated minimal and maximal capture latency in milliseconds
     * or null if no capture latency info can be provided
     *
     * @throws IllegalArgumentException in case of format different from {@link ImageFormat#JPEG} /
     *                                  {@link ImageFormat#YUV_420_888}; or unsupported extension.
     */
    public @Nullable Range<Long> getEstimatedCaptureLatencyRangeMillis(@Extension int extension,
            @NonNull Size captureOutputSize, @ImageFormat.Format int format) {
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.JPEG:
                //No op
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }

        long clientId = registerClient(mContext);
        if (clientId < 0) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mChars)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = captureOutputSize.getWidth();
            sz.height = captureOutputSize.getHeight();
            if (areAdvancedExtensionsSupported()) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId);
                LatencyRange latencyRange = extender.getEstimatedCaptureLatencyRange(mCameraId,
                        sz, format);
                if (latencyRange != null) {
                    return new Range(latencyRange.min, latencyRange.max);
                }
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.init(mCameraId, mChars.getNativeMetadata());
                if ((format == ImageFormat.YUV_420_888) &&
                        (extenders.second.getCaptureProcessor() == null) ){
                    // Extensions that don't implement any capture processor are limited to
                    // JPEG only!
                    return null;
                }
                if ((format == ImageFormat.JPEG) &&
                        (extenders.second.getCaptureProcessor() != null)) {
                    // The framework will perform the additional encoding pass on the
                    // processed YUV_420 buffers. Latency in this case is very device
                    // specific and cannot be estimated accurately enough.
                    return  null;
                }

                LatencyRange latencyRange = extenders.second.getEstimatedCaptureLatencyRange(sz);
                if (latencyRange != null) {
                    return new Range(latencyRange.min, latencyRange.max);
                }
        }
    } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension capture latency! Extension service does"
                    + " not respond!");
        } finally {
            unregisterClient(clientId);
        }

        return null;
    }
}
