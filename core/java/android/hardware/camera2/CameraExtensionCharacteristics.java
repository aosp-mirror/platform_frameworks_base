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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.ImageFormat;
import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.ICameraExtensionsProxyService;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.LatencyRange;
import android.hardware.camera2.extension.SizeList;
import android.hardware.camera2.impl.CameraExtensionUtils;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import com.android.internal.camera.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    public static final int EXTENSION_FACE_RETOUCH = 1;

    /**
     * Device-specific extension implementation which tends to smooth the skin and apply other
     * cosmetic effects to people's faces.
     *
     * @deprecated Use {@link #EXTENSION_FACE_RETOUCH} instead.
     */
    public @Deprecated static final int EXTENSION_BEAUTY = EXTENSION_FACE_RETOUCH;

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
     * An extension that aims to lock and stabilize a given region or object of interest.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final int EXTENSION_EYES_FREE_VIDEOGRAPHY = 5;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {EXTENSION_AUTOMATIC,
                EXTENSION_FACE_RETOUCH,
                EXTENSION_BOKEH,
                EXTENSION_HDR,
                EXTENSION_NIGHT,
                EXTENSION_EYES_FREE_VIDEOGRAPHY})
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
            EXTENSION_FACE_RETOUCH,
            EXTENSION_BOKEH,
            EXTENSION_HDR,
            EXTENSION_NIGHT};

    private final Context mContext;
    private final String mCameraId;
    private final Map<String, CameraCharacteristics> mCharacteristicsMap;
    private final Map<String, CameraMetadataNative> mCharacteristicsMapNative;

    /**
     * @hide
     */
    public CameraExtensionCharacteristics(Context context, String cameraId,
            Map<String, CameraCharacteristics> characteristicsMap) {
        mContext = context;
        mCameraId = cameraId;
        mCharacteristicsMap = characteristicsMap;
        mCharacteristicsMapNative =
                CameraExtensionUtils.getCharacteristicsMapNative(characteristicsMap);
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

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        private static final int FALLBACK_PACKAGE_NAME =
                com.android.internal.R.string.config_extensionFallbackPackageName;
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        private static final int FALLBACK_SERVICE_NAME =
                com.android.internal.R.string.config_extensionFallbackServiceName;

        // Singleton instance
        private static final CameraExtensionManagerGlobal GLOBAL_CAMERA_MANAGER =
                new CameraExtensionManagerGlobal();
        private final Object mLock = new Object();
        private final int PROXY_SERVICE_DELAY_MS = 2000;
        private ExtensionConnectionManager mConnectionManager = new ExtensionConnectionManager();

        // Singleton, don't allow construction
        private CameraExtensionManagerGlobal() {}

        public static CameraExtensionManagerGlobal get() {
            return GLOBAL_CAMERA_MANAGER;
        }

        private void releaseProxyConnectionLocked(Context ctx, int extension) {
            if (mConnectionManager.getConnection(extension) != null) {
                ctx.unbindService(mConnectionManager.getConnection(extension));
                mConnectionManager.setConnection(extension, null);
                mConnectionManager.setProxy(extension, null);
                mConnectionManager.resetConnectionCount(extension);
            }
        }

        private void connectToProxyLocked(Context ctx, int extension, boolean useFallback) {
            if (mConnectionManager.getConnection(extension) == null) {
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

                if (Flags.concertMode() && useFallback) {
                    String packageName = ctx.getResources().getString(FALLBACK_PACKAGE_NAME);
                    String serviceName = ctx.getResources().getString(FALLBACK_SERVICE_NAME);

                    if (!packageName.isEmpty() && !serviceName.isEmpty()) {
                        Log.v(TAG,
                                "Choosing the fallback software implementation package: "
                                + packageName);
                        Log.v(TAG,
                                "Choosing the fallback software implementation service: "
                                + serviceName);
                        intent.setClassName(packageName, serviceName);
                    }
                }

                InitializerFuture initFuture = new InitializerFuture();
                ServiceConnection connection = new ServiceConnection() {
                    @Override
                    public void onServiceDisconnected(ComponentName component) {
                        mConnectionManager.setConnection(extension, null);
                        mConnectionManager.setProxy(extension, null);
                    }

                    @Override
                    public void onServiceConnected(ComponentName component, IBinder binder) {
                        ICameraExtensionsProxyService proxy =
                                ICameraExtensionsProxyService.Stub.asInterface(binder);
                        mConnectionManager.setProxy(extension, proxy);
                        if (mConnectionManager.getProxy(extension) == null) {
                            throw new IllegalStateException("Camera Proxy service is null");
                        }
                        try {
                            mConnectionManager.setAdvancedExtensionsSupported(extension,
                                    mConnectionManager.getProxy(extension)
                                    .advancedExtensionsSupported());
                        } catch (RemoteException e) {
                            Log.e(TAG, "Remote IPC failed!");
                        }
                        initFuture.setStatus(true);
                    }
                };
                ctx.bindService(intent, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT |
                        Context.BIND_ABOVE_CLIENT | Context.BIND_NOT_VISIBLE,
                        android.os.AsyncTask.THREAD_POOL_EXECUTOR, connection);
                mConnectionManager.setConnection(extension, connection);

                try {
                    initFuture.get(PROXY_SERVICE_DELAY_MS, TimeUnit.MILLISECONDS);
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

        public boolean registerClientHelper(Context ctx, IBinder token, int extension,
                boolean useFallback) {
            synchronized (mLock) {
                boolean ret = false;
                connectToProxyLocked(ctx, extension, useFallback);
                if (mConnectionManager.getProxy(extension) == null) {
                    return false;
                }
                mConnectionManager.incrementConnectionCount(extension);

                try {
                    ret = mConnectionManager.getProxy(extension).registerClient(token);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to initialize extension! Extension service does "
                            + " not respond!");
                }
                if (!ret) {
                    mConnectionManager.decrementConnectionCount(extension);
                }

                if (mConnectionManager.getConnectionCount(extension) <= 0) {
                    releaseProxyConnectionLocked(ctx, extension);
                }

                return ret;
            }
        }

        @SuppressLint("NonUserGetterCalled")
        public boolean registerClient(Context ctx, IBinder token, int extension,
                String cameraId, Map<String, CameraMetadataNative> characteristicsMapNative) {
            boolean ret = registerClientHelper(ctx, token, extension, false /*useFallback*/);

            if (Flags.concertMode()) {
                // Check if user enabled fallback impl
                ContentResolver resolver = ctx.getContentResolver();
                int userEnabled = Settings.Secure.getInt(resolver,
                        Settings.Secure.CAMERA_EXTENSIONS_FALLBACK, 1);

                boolean vendorImpl = true;
                if (ret && (mConnectionManager.getProxy(extension) != null) && (userEnabled == 1)) {
                    // At this point, we are connected to either CameraExtensionsProxyService or
                    // the vendor extension proxy service. If the vendor does not support the
                    // extension, unregisterClient and re-register client with the proxy service
                    // containing the fallback impl
                    vendorImpl = isExtensionSupported(cameraId, extension,
                            characteristicsMapNative);
                }

                if (!vendorImpl) {
                    unregisterClient(ctx, token, extension);
                    ret = registerClientHelper(ctx, token, extension, true /*useFallback*/);

                }
            }

            return ret;
        }

        public void unregisterClient(Context ctx, IBinder token, int extension) {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null) {
                    try {
                        mConnectionManager.getProxy(extension).unregisterClient(token);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to de-initialize extension! Extension service does"
                                + " not respond!");
                    } finally {
                        mConnectionManager.decrementConnectionCount(extension);
                        if (mConnectionManager.getConnectionCount(extension) <= 0) {
                            releaseProxyConnectionLocked(ctx, extension);
                        }
                    }
                }
            }
        }

        public void initializeSession(IInitializeSessionCallback cb, int extension)
                throws RemoteException {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null
                        && !mConnectionManager.isSessionInitialized()) {
                    mConnectionManager.getProxy(extension).initializeSession(cb);
                    mConnectionManager.setSessionInitialized(true);
                } else {
                    cb.onFailure();
                }
            }
        }

        public void releaseSession(int extension) {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null) {
                    try {
                        mConnectionManager.getProxy(extension).releaseSession();
                        mConnectionManager.setSessionInitialized(false);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to release session! Extension service does"
                                + " not respond!");
                    }
                }
            }
        }

        public boolean areAdvancedExtensionsSupported(int extension) {
            return mConnectionManager.areAdvancedExtensionsSupported(extension);
        }

        public IPreviewExtenderImpl initializePreviewExtension(int extension)
                throws RemoteException {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null) {
                    return mConnectionManager.getProxy(extension)
                            .initializePreviewExtension(extension);
                } else {
                    return null;
                }
            }
        }

        public IImageCaptureExtenderImpl initializeImageExtension(int extension)
                throws RemoteException {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null) {
                    return mConnectionManager.getProxy(extension)
                            .initializeImageExtension(extension);
                } else {
                    return null;
                }
            }
        }

        public IAdvancedExtenderImpl initializeAdvancedExtension(int extension)
                throws RemoteException {
            synchronized (mLock) {
                if (mConnectionManager.getProxy(extension) != null) {
                    return mConnectionManager.getProxy(extension)
                            .initializeAdvancedExtension(extension);
                } else {
                    return null;
                }
            }
        }

        private class ExtensionConnectionManager {
            // Maps extension to ExtensionConnection
            private Map<Integer, ExtensionConnection> mConnections = new HashMap<>();
            private boolean mSessionInitialized = false;

            public ExtensionConnectionManager() {
                IntArray extensionList = new IntArray(EXTENSION_LIST.length);
                extensionList.addAll(EXTENSION_LIST);
                if (Flags.concertMode()) {
                    extensionList.add(EXTENSION_EYES_FREE_VIDEOGRAPHY);
                }

                for (int extensionType : extensionList.toArray()) {
                    mConnections.put(extensionType, new ExtensionConnection());
                }
            }

            public ICameraExtensionsProxyService getProxy(@Extension int extension) {
                return mConnections.get(extension).mProxy;
            }

            public ServiceConnection getConnection(@Extension int extension) {
                return mConnections.get(extension).mConnection;
            }

            public int getConnectionCount(@Extension int extension) {
                return mConnections.get(extension).mConnectionCount;
            }

            public boolean areAdvancedExtensionsSupported(@Extension int extension) {
                return mConnections.get(extension).mSupportsAdvancedExtensions;
            }

            public boolean isSessionInitialized() {
                return mSessionInitialized;
            }

            public void setProxy(@Extension int extension, ICameraExtensionsProxyService proxy) {
                mConnections.get(extension).mProxy = proxy;
            }

            public void setConnection(@Extension int extension, ServiceConnection connection) {
                mConnections.get(extension).mConnection = connection;
            }

            public void incrementConnectionCount(@Extension int extension) {
                mConnections.get(extension).mConnectionCount++;
            }

            public void decrementConnectionCount(@Extension int extension) {
                mConnections.get(extension).mConnectionCount--;
            }

            public void resetConnectionCount(@Extension int extension) {
                mConnections.get(extension).mConnectionCount = 0;
            }

            public void setAdvancedExtensionsSupported(@Extension int extension,
                    boolean advancedExtSupported) {
                mConnections.get(extension).mSupportsAdvancedExtensions = advancedExtSupported;
            }

            public void setSessionInitialized(boolean initialized) {
                mSessionInitialized = initialized;
            }

            private class ExtensionConnection {
                public ICameraExtensionsProxyService mProxy = null;
                public ServiceConnection mConnection = null;
                public int mConnectionCount = 0;
                public boolean mSupportsAdvancedExtensions = false;
            }
        }
    }

    /**
     * @hide
     */
    public static boolean registerClient(Context ctx, IBinder token, int extension,
            String cameraId, Map<String, CameraMetadataNative> characteristicsMapNative) {
        return CameraExtensionManagerGlobal.get().registerClient(ctx, token, extension, cameraId,
                characteristicsMapNative);
    }

    /**
     * @hide
     */
    public static void unregisterClient(Context ctx, IBinder token, int extension) {
        CameraExtensionManagerGlobal.get().unregisterClient(ctx, token, extension);
    }

    /**
     * @hide
     */
    public static void initializeSession(IInitializeSessionCallback cb, int extension)
            throws RemoteException {
        CameraExtensionManagerGlobal.get().initializeSession(cb, extension);
    }

    /**
     * @hide
     */
    public static void releaseSession(int extension) {
        CameraExtensionManagerGlobal.get().releaseSession(extension);
    }

    /**
     * @hide
     */
    public static boolean areAdvancedExtensionsSupported(int extension) {
        return CameraExtensionManagerGlobal.get().areAdvancedExtensionsSupported(extension);
    }

    /**
     * @hide
     */
    public static boolean isExtensionSupported(String cameraId, int extensionType,
            Map<String, CameraMetadataNative> characteristicsMap) {
        if (areAdvancedExtensionsSupported(extensionType)) {
            try {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extensionType);
                return extender.isExtensionAvailable(cameraId, characteristicsMap);
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
                return extenders.first.isExtensionAvailable(cameraId,
                        characteristicsMap.get(cameraId))
                        && extenders.second.isExtensionAvailable(cameraId,
                        characteristicsMap.get(cameraId));
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

        if ((klass == android.graphics.SurfaceTexture.class) ||
                (klass == android.view.SurfaceView.class)) {
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
        final IBinder token = new Binder(TAG + "#getSupportedExtensions:" + mCameraId);

        IntArray extensionList = new IntArray(EXTENSION_LIST.length);
        extensionList.addAll(EXTENSION_LIST);
        if (Flags.concertMode()) {
            extensionList.add(EXTENSION_EYES_FREE_VIDEOGRAPHY);
        }

        for (int extensionType : extensionList.toArray()) {
            try {
                boolean success = registerClient(mContext, token, extensionType, mCameraId,
                        mCharacteristicsMapNative);
                if (success && isExtensionSupported(mCameraId, extensionType,
                        mCharacteristicsMapNative)) {
                    ret.add(extensionType);
                }
            } finally {
                unregisterClient(mContext, token, extensionType);
            }
        }

        return Collections.unmodifiableList(ret);
    }

    /**
     * Gets an extension specific camera characteristics field value.
     *
     * <p>An extension can have a reduced set of camera capabilities (such as limited zoom ratio
     * range, available video stabilization modes, etc). This API enables applications to query for
     * an extension’s specific camera characteristics. Applications are recommended to prioritize
     * obtaining camera characteristics using this API when using an extension. A {@code null}
     * result indicates that the extension specific characteristic is not defined or available.
     *
     * @param extension The extension type.
     * @param key The characteristics field to read.
     * @return The value of that key, or {@code null} if the field is not set.
     *
     * @throws IllegalArgumentException if the key is not valid or extension type is not a supported
     * device-specific extension.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_EXTENSIONS_CHARACTERISTICS_GET)
    public <T> @Nullable T get(@Extension int extension,
            @NonNull CameraCharacteristics.Key<T> key) {
        final IBinder token = new Binder(TAG + "#get:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            if (areAdvancedExtensionsSupported(extension) && getKeys(extension).contains(key)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                CameraMetadataNative metadata =
                        extender.getAvailableCharacteristicsKeyValues(mCameraId);
                CameraCharacteristics fallbackCharacteristics = mCharacteristicsMap.get(mCameraId);
                if (metadata == null) {
                    return fallbackCharacteristics.get(key);
                }
                CameraCharacteristics characteristics = new CameraCharacteristics(metadata);
                T value = characteristics.get(key);
                return value == null ? fallbackCharacteristics.get(key) : value;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension for the specified key! Extension "
                    + "service does not respond!");
        } finally {
            unregisterClient(mContext, token, extension);
        }
        return null;
    }

    /**
     * Returns the {@link CameraCharacteristics} keys that have extension-specific values.
     *
     * <p>An application can query the value from the key using
     * {@link #get(int, CameraCharacteristics.Key)} API.
     *
     * @param extension The extension type.
     * @return An unmodifiable set of keys that are extension specific.
     *
     * @throws IllegalArgumentException in case the extension type is not a
     * supported device-specific extension
     */
    @FlaggedApi(Flags.FLAG_CAMERA_EXTENSIONS_CHARACTERISTICS_GET)
    public @NonNull Set<CameraCharacteristics.Key> getKeys(@Extension int extension) {
        final IBinder token =
                new Binder(TAG + "#getKeys:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        HashSet<CameraCharacteristics.Key> ret = new HashSet<>();

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                CameraMetadataNative metadata =
                        extender.getAvailableCharacteristicsKeyValues(mCameraId);
                if (metadata == null) {
                    return Collections.emptySet();
                }

                int[] keys = metadata.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CHARACTERISTICS_KEYS);
                if (keys == null) {
                    throw new AssertionError(
                            "android.request.availableCharacteristicsKeys must be non-null"
                                    + " in the characteristics");
                }
                CameraCharacteristics chars = new CameraCharacteristics(metadata);

                Object key = CameraCharacteristics.Key.class;
                Class<CameraCharacteristics.Key<?>> keyTyped =
                        (Class<CameraCharacteristics.Key<?>>) key;

                ret.addAll(chars.getAvailableKeyList(CameraCharacteristics.class, keyTyped, keys,
                        /*includeSynthetic*/ true));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension for all available keys! Extension "
                    + "service does not respond!");
        } finally {
            unregisterClient(mContext, token, extension);
        }
        return Collections.unmodifiableSet(ret);
    }

    /**
     * Checks for postview support of still capture.
     *
     * <p>A postview is a preview version of the still capture that is available before the final
     * image. For example, it can be used as a temporary placeholder for the requested capture
     * while the final image is being processed. The supported sizes for a still capture's postview
     * can be retrieved using
     * {@link CameraExtensionCharacteristics#getPostviewSupportedSizes(int, Size, int)}.
     * The formats of the still capture and postview should be equivalent upon capture request.</p>
     *
     * @param extension the extension type
     * @return {@code true} in case postview is supported, {@code false} otherwise
     *
     * @throws IllegalArgumentException in case the extension type is not a
     * supported device-specific extension
     */
    public boolean isPostviewAvailable(@Extension int extension) {
        final IBinder token = new Binder(TAG + "#isPostviewAvailable:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                return extender.isPostviewAvailable();
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                return extenders.second.isPostviewAvailable();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension for postview availability! Extension "
                    + "service does not respond!");
        } finally {
            unregisterClient(mContext, token, extension);
        }

        return false;
    }

    /**
     * Get a list of the postview sizes supported for a still capture, using its
     * capture size {@code captureSize}, to use as an output for the postview request.
     *
     * <p>Available postview sizes will always be either equal to or less than the still
     * capture size. When choosing the most applicable postview size for a usecase, it should
     * be noted that lower resolution postviews will generally be available more quickly
     * than larger resolution postviews. For example, when choosing a size for an optimized
     * postview that will be displayed as a placeholder while the final image is processed,
     * the resolution closest to the preview size may be most suitable.</p>
     *
     * <p>Note that device-specific extensions are allowed to support only a subset
     * of the camera resolutions advertised by
     * {@link StreamConfigurationMap#getOutputSizes}.</p>
     *
     * @param extension the extension type
     * @param captureSize size of the still capture for which the postview is requested
     * @param format device-specific extension output format of the still capture and
     * postview
     * @return non-modifiable list of available sizes or an empty list if the format and
     * size is not supported.
     * @throws IllegalArgumentException in case of unsupported extension or if postview
     * feature is not supported by extension.
     */
    @NonNull
    public List<Size> getPostviewSupportedSizes(@Extension int extension,
            @NonNull Size captureSize, int format) {
        final IBinder token = new Binder(TAG + "#getPostviewSupportedSizes:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = captureSize.getWidth();
            sz.height = captureSize.getHeight();

            StreamConfigurationMap streamMap = mCharacteristicsMap.get(mCameraId).get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (areAdvancedExtensionsSupported(extension)) {
                switch(format) {
                    case ImageFormat.YUV_420_888:
                    case ImageFormat.JPEG:
                    case ImageFormat.JPEG_R:
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported format: " + format);
                }
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                return generateSupportedSizes(extender.getSupportedPostviewResolutions(
                    sz), format, streamMap);
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                if ((extenders.second.getCaptureProcessor() == null) ||
                        !isPostviewAvailable(extension)) {
                    // Extensions that don't implement any capture processor
                    // and have processing occur in the HAL don't currently support the
                    // postview feature
                    throw new IllegalArgumentException("Extension does not support "
                            + "postview feature");
                }

                if (format == ImageFormat.YUV_420_888) {
                    return generateSupportedSizes(
                            extenders.second.getSupportedPostviewResolutions(sz),
                            format, streamMap);
                } else if (format == ImageFormat.JPEG) {
                    // The framework will perform the additional encoding pass on the
                    // processed YUV_420 buffers.
                    return generateJpegSupportedSizes(
                            extenders.second.getSupportedPostviewResolutions(sz),
                                    streamMap);
                }  else if (format == ImageFormat.JPEG_R) {
                    // Jpeg_R/UltraHDR is currently not supported in the basic extension case
                    return new ArrayList<>();
                } else {
                    throw new IllegalArgumentException("Unsupported format: " + format);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension postview supported sizes! Extension "
                    + "service does not respond!");
            return Collections.emptyList();
        } finally {
            unregisterClient(mContext, token, extension);
        }
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
     * <p>Starting with Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}
     * {@link android.view.SurfaceView} classes are also guaranteed to be supported and include
     * the same resolutions as {@link android.graphics.SurfaceTexture}.
     * Clients must set the desired SurfaceView resolution by calling
     * {@link android.view.SurfaceHolder#setFixedSize}.</p>
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

        final IBinder token = new Binder(TAG + "#getExtensionSupportedSizes:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            StreamConfigurationMap streamMap = mCharacteristicsMap.get(mCameraId).get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                return generateSupportedSizes(
                        extender.getSupportedPreviewOutputResolutions(mCameraId),
                        ImageFormat.PRIVATE, streamMap);
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.first.init(mCameraId,
                        mCharacteristicsMapNative.get(mCameraId));
                return generateSupportedSizes(extenders.first.getSupportedResolutions(),
                        ImageFormat.PRIVATE, streamMap);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension supported sizes! Extension service does"
                    + " not respond!");
            return new ArrayList<>();
        } finally {
            unregisterClient(mContext, token, extension);
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
     * <p>Device-specific extensions currently support at most three
     * multi-frame capture surface formats. ImageFormat.JPEG will be supported by all
     * extensions while ImageFormat.YUV_420_888 and ImageFormat.JPEG_R may or may not be
     * supported.</p>
     *
     * @param extension the extension type
     * @param format    device-specific extension output format
     * @return non-modifiable list of available sizes or an empty list if the format is not
     * supported.
     * @throws IllegalArgumentException in case of format different from ImageFormat.JPEG,
     *                                  ImageFormat.YUV_420_888, ImageFormat.JPEG_R; or
     *                                  unsupported extension.
     */
    public @NonNull
    List<Size> getExtensionSupportedSizes(@Extension int extension, int format) {
        try {
            final IBinder token = new Binder(TAG + "#getExtensionSupportedSizes:" + mCameraId);
            boolean success = registerClient(mContext, token, extension, mCameraId,
                    mCharacteristicsMapNative);
            if (!success) {
                throw new IllegalArgumentException("Unsupported extensions");
            }

            try {
                if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                    throw new IllegalArgumentException("Unsupported extension");
                }

                StreamConfigurationMap streamMap = mCharacteristicsMap.get(mCameraId).get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (areAdvancedExtensionsSupported(extension)) {
                    switch(format) {
                        case ImageFormat.YUV_420_888:
                        case ImageFormat.JPEG:
                        case ImageFormat.JPEG_R:
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported format: " + format);
                    }
                    IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                    extender.init(mCameraId, mCharacteristicsMapNative);
                    return generateSupportedSizes(extender.getSupportedCaptureOutputResolutions(
                            mCameraId), format, streamMap);
                } else {
                    if (format == ImageFormat.YUV_420_888) {
                        Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                                initializeExtension(extension);
                        extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
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
                        extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                        if (extenders.second.getCaptureProcessor() != null) {
                            // The framework will perform the additional encoding pass on the
                            // processed YUV_420 buffers.
                            return generateJpegSupportedSizes(
                                    extenders.second.getSupportedResolutions(), streamMap);
                        } else {
                            return generateSupportedSizes(null, format, streamMap);
                        }
                    } else if (format == ImageFormat.JPEG_R) {
                        // Jpeg_R/UltraHDR is currently not supported in the basic extension case
                        return new ArrayList<>();
                    } else {
                        throw new IllegalArgumentException("Unsupported format: " + format);
                    }
                }
            } finally {
                unregisterClient(mContext, token, extension);
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
     * @throws IllegalArgumentException in case of format different from {@link ImageFormat#JPEG},
     *                                  {@link ImageFormat#YUV_420_888}, {@link ImageFormat#JPEG_R};
     *                                  or unsupported extension.
     */
    public @Nullable Range<Long> getEstimatedCaptureLatencyRangeMillis(@Extension int extension,
            @NonNull Size captureOutputSize, @ImageFormat.Format int format) {
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.JPEG:
            case ImageFormat.JPEG_R:
                //No op
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }

        final IBinder token = new Binder(TAG + "#getEstimatedCaptureLatencyRangeMillis:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = captureOutputSize.getWidth();
            sz.height = captureOutputSize.getHeight();
            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                LatencyRange latencyRange = extender.getEstimatedCaptureLatencyRange(mCameraId,
                        sz, format);
                if (latencyRange != null) {
                    return new Range(latencyRange.min, latencyRange.max);
                }
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
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
                if (format == ImageFormat.JPEG_R) {
                    // JpegR/UltraHDR is not supported for basic extensions
                    return null;
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
            unregisterClient(mContext, token, extension);
        }

        return null;
    }

    /**
     * Retrieve support for capture progress callbacks via
     *  {@link CameraExtensionSession.ExtensionCaptureCallback#onCaptureProcessProgressed}.
     *
     * @param extension         the extension type
     * @return {@code true} in case progress callbacks are supported, {@code false} otherwise
     *
     * @throws IllegalArgumentException in case of an unsupported extension.
     */
    public boolean isCaptureProcessProgressAvailable(@Extension int extension) {
        final IBinder token = new Binder(TAG + "#isCaptureProcessProgressAvailable:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                return extender.isCaptureProcessProgressAvailable();
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                return extenders.second.isCaptureProcessProgressAvailable();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query the extension progress callbacks! Extension service does"
                    + " not respond!");
        } finally {
            unregisterClient(mContext, token, extension);
        }

        return false;
    }

    /**
     * Returns the set of keys supported by a {@link CaptureRequest} submitted in a
     * {@link CameraExtensionSession} with a given extension type.
     *
     * <p>The set returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Devices launching on Android {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}
     * or newer versions are required to support {@link CaptureRequest#CONTROL_AF_MODE},
     * {@link CaptureRequest#CONTROL_AF_REGIONS}, {@link CaptureRequest#CONTROL_AF_TRIGGER},
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO} for
     * {@link CameraExtensionCharacteristics#EXTENSION_NIGHT}.</p>
     *
     * @param extension the extension type
     *
     * @return non-modifiable set of capture keys supported by camera extension session initialized
     *         with the given extension type.
     * @throws IllegalArgumentException in case of unsupported extension.
     */
    @NonNull
    public Set<CaptureRequest.Key> getAvailableCaptureRequestKeys(@Extension int extension) {
        final IBinder token = new Binder(TAG + "#getAvailableCaptureRequestKeys:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        HashSet<CaptureRequest.Key> ret = new HashSet<>();

        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            CameraMetadataNative captureRequestMeta = null;
            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                captureRequestMeta = extender.getAvailableCaptureRequestKeys(mCameraId);
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.onInit(token, mCameraId,
                        mCharacteristicsMapNative.get(mCameraId));
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                captureRequestMeta = extenders.second.getAvailableCaptureRequestKeys();
                extenders.second.onDeInit(token);
            }

            if (captureRequestMeta != null) {
                int[] requestKeys = captureRequestMeta.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_REQUEST_KEYS);
                if (requestKeys == null) {
                    throw new AssertionError(
                            "android.request.availableRequestKeys must be non-null"
                                    + " in the characteristics");
                }
                CameraCharacteristics requestChars = new CameraCharacteristics(
                        captureRequestMeta);

                Object crKey = CaptureRequest.Key.class;
                Class<CaptureRequest.Key<?>> crKeyTyped = (Class<CaptureRequest.Key<?>>) crKey;

                ret.addAll(requestChars.getAvailableKeyList(CaptureRequest.class, crKeyTyped,
                        requestKeys, /*includeSynthetic*/ true));
            }

            // Jpeg quality and orientation must always be supported
            if (!ret.contains(CaptureRequest.JPEG_QUALITY)) {
                ret.add(CaptureRequest.JPEG_QUALITY);
            }
            if (!ret.contains(CaptureRequest.JPEG_ORIENTATION)) {
                ret.add(CaptureRequest.JPEG_ORIENTATION);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to query the available capture request keys!");
        } finally {
            unregisterClient(mContext, token, extension);
        }

        return Collections.unmodifiableSet(ret);
    }

    /**
     * Returns the set of keys supported by a {@link CaptureResult} passed as an argument to
     * {@link CameraExtensionSession.ExtensionCaptureCallback#onCaptureResultAvailable}.
     *
     * <p>The set returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>In case the set is empty, then the extension is not able to support any capture results
     * and the {@link CameraExtensionSession.ExtensionCaptureCallback#onCaptureResultAvailable}
     * callback will not be fired.</p>
     *
     * <p>Devices launching on Android {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}
     * or newer versions are required to support {@link CaptureResult#CONTROL_AF_MODE},
     * {@link CaptureResult#CONTROL_AF_REGIONS}, {@link CaptureResult#CONTROL_AF_TRIGGER},
     * {@link CaptureResult#CONTROL_AF_STATE}, {@link CaptureResult#CONTROL_ZOOM_RATIO} for
     * {@link CameraExtensionCharacteristics#EXTENSION_NIGHT}.</p>
     *
     * @param extension the extension type
     *
     * @return non-modifiable set of capture result keys supported by camera extension session
     *         initialized with the given extension type.
     * @throws IllegalArgumentException in case of unsupported extension.
     */
    @NonNull
    public Set<CaptureResult.Key> getAvailableCaptureResultKeys(@Extension int extension) {
        final IBinder token = new Binder(TAG + "#getAvailableCaptureResultKeys:" + mCameraId);
        boolean success = registerClient(mContext, token, extension, mCameraId,
                mCharacteristicsMapNative);
        if (!success) {
            throw new IllegalArgumentException("Unsupported extensions");
        }

        HashSet<CaptureResult.Key> ret = new HashSet<>();
        try {
            if (!isExtensionSupported(mCameraId, extension, mCharacteristicsMapNative)) {
                throw new IllegalArgumentException("Unsupported extension");
            }

            CameraMetadataNative captureResultMeta = null;
            if (areAdvancedExtensionsSupported(extension)) {
                IAdvancedExtenderImpl extender = initializeAdvancedExtension(extension);
                extender.init(mCameraId, mCharacteristicsMapNative);
                captureResultMeta = extender.getAvailableCaptureResultKeys(mCameraId);
            } else {
                Pair<IPreviewExtenderImpl, IImageCaptureExtenderImpl> extenders =
                        initializeExtension(extension);
                extenders.second.onInit(token, mCameraId,
                        mCharacteristicsMapNative.get(mCameraId));
                extenders.second.init(mCameraId, mCharacteristicsMapNative.get(mCameraId));
                captureResultMeta = extenders.second.getAvailableCaptureResultKeys();
                extenders.second.onDeInit(token);
            }

            if (captureResultMeta != null) {
                int[] resultKeys = captureResultMeta.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_RESULT_KEYS);
                if (resultKeys == null) {
                    throw new AssertionError("android.request.availableResultKeys must be non-null "
                            + "in the characteristics");
                }
                CameraCharacteristics resultChars = new CameraCharacteristics(captureResultMeta);
                Object crKey = CaptureResult.Key.class;
                Class<CaptureResult.Key<?>> crKeyTyped = (Class<CaptureResult.Key<?>>) crKey;

                ret.addAll(resultChars.getAvailableKeyList(CaptureResult.class, crKeyTyped,
                        resultKeys, /*includeSynthetic*/ true));

                // Jpeg quality, orientation and sensor timestamp must always be supported
                if (!ret.contains(CaptureResult.JPEG_QUALITY)) {
                    ret.add(CaptureResult.JPEG_QUALITY);
                }
                if (!ret.contains(CaptureResult.JPEG_ORIENTATION)) {
                    ret.add(CaptureResult.JPEG_ORIENTATION);
                }
                if (!ret.contains(CaptureResult.SENSOR_TIMESTAMP)) {
                    ret.add(CaptureResult.SENSOR_TIMESTAMP);
                }
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to query the available capture result keys!");
        } finally {
            unregisterClient(mContext, token, extension);
        }

        return Collections.unmodifiableSet(ret);
    }
}
