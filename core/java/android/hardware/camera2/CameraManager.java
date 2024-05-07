/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.CameraExtensionSessionStats;
import android.hardware.CameraIdRemapping;
import android.hardware.CameraStatus;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceListener;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.impl.CameraDeviceSetupImpl;
import android.hardware.camera2.impl.CameraInjectionSessionImpl;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.utils.CameraIdAndSessionConfiguration;
import android.hardware.camera2.utils.ConcurrentCameraIdCombination;
import android.hardware.camera2.utils.ExceptionUtils;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Size;
import android.view.Display;

import com.android.internal.camera.flags.Flags;
import com.android.internal.util.ArrayUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>A system service manager for detecting, characterizing, and connecting to
 * {@link CameraDevice CameraDevices}.</p>
 *
 * <p>For more details about communicating with camera devices, read the Camera
 * developer guide or the {@link android.hardware.camera2 camera2}
 * package documentation.</p>
 */
@SystemService(Context.CAMERA_SERVICE)
public final class CameraManager {

    private static final String TAG = "CameraManager";
    private final boolean DEBUG = false;

    private static final int USE_CALLING_UID = -1;

    @SuppressWarnings("unused")
    private static final int API_VERSION_1 = 1;
    private static final int API_VERSION_2 = 2;

    private static final int CAMERA_TYPE_BACKWARD_COMPATIBLE = 0;
    private static final int CAMERA_TYPE_ALL = 1;

    private ArrayList<String> mDeviceIdList;

    private final Context mContext;
    private final Object mLock = new Object();

    private static final String CAMERA_OPEN_CLOSE_LISTENER_PERMISSION =
            "android.permission.CAMERA_OPEN_CLOSE_LISTENER";
    private final boolean mHasOpenCloseListenerPermission;

    /**
     * Force camera output to be rotated to portrait orientation on landscape cameras.
     * Many apps do not handle this situation and display stretched images otherwise.
     * @hide
     */
    @ChangeId
    @Overridable
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.BASE)
    @TestApi
    public static final long OVERRIDE_CAMERA_LANDSCAPE_TO_PORTRAIT = 250678880L;

    /**
     * System property for allowing the above
     * @hide
     */
    @TestApi
    public static final String LANDSCAPE_TO_PORTRAIT_PROP =
            "camera.enable_landscape_to_portrait";

    /**
     * Enable physical camera availability callbacks when the logical camera is unavailable
     *
     * <p>Previously once a logical camera becomes unavailable, no
     * {@link AvailabilityCallback#onPhysicalCameraAvailable} or
     * {@link AvailabilityCallback#onPhysicalCameraUnavailable} will
     * be called until the logical camera becomes available again. The
     * results in the app opening the logical camera not able to
     * receive physical camera availability change.</p>
     *
     * <p>With this change, the {@link
     * AvailabilityCallback#onPhysicalCameraAvailable} and {@link
     * AvailabilityCallback#onPhysicalCameraUnavailable} can still be
     * called while the logical camera is unavailable.  </p>
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final long ENABLE_PHYSICAL_CAMERA_CALLBACK_FOR_UNAVAILABLE_LOGICAL_CAMERA =
            244358506L;

    /**
     * @hide
     */
    public CameraManager(Context context) {
        synchronized(mLock) {
            mContext = context;
            mHasOpenCloseListenerPermission =
                    mContext.checkSelfPermission(CAMERA_OPEN_CLOSE_LISTENER_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * @hide
     */
    public interface DeviceStateListener {
        void onDeviceStateChanged(boolean folded);
    }

    private static final class FoldStateListener implements DeviceStateManager.DeviceStateCallback {
        private final int[] mFoldedDeviceStates;

        private ArrayList<WeakReference<DeviceStateListener>> mDeviceStateListeners =
                new ArrayList<>();
        private boolean mFoldedDeviceState;

        public FoldStateListener(Context context) {
            mFoldedDeviceStates = context.getResources().getIntArray(
                    com.android.internal.R.array.config_foldedDeviceStates);
        }

        private synchronized void handleStateChange(int state) {
            boolean folded = ArrayUtils.contains(mFoldedDeviceStates, state);

            mFoldedDeviceState = folded;
            Iterator<WeakReference<DeviceStateListener>> it = mDeviceStateListeners.iterator();
            while(it.hasNext()) {
                DeviceStateListener callback = it.next().get();
                if (callback != null) {
                    callback.onDeviceStateChanged(folded);
                } else {
                    it.remove();
                }
            }
        }

        public synchronized void addDeviceStateListener(DeviceStateListener listener) {
            listener.onDeviceStateChanged(mFoldedDeviceState);
            mDeviceStateListeners.removeIf(l -> l.get() == null);
            mDeviceStateListeners.add(new WeakReference<>(listener));
        }

        @Override
        public final void onBaseStateChanged(int state) {
            handleStateChange(state);
        }

        @Override
        public final void onStateChanged(int state) {
            handleStateChange(state);
        }
    }

    /**
     * Register a {@link CameraCharacteristics} device state listener
     *
     * @param chars Camera characteristics that need to receive device state updates
     *
     * @hide
     */
    public void registerDeviceStateListener(@NonNull CameraCharacteristics chars) {
        CameraManagerGlobal.get().registerDeviceStateListener(chars, mContext);
    }

    /**
     * Return the list of currently connected camera devices by identifier, including
     * cameras that may be in use by other camera API clients.
     *
     * <p>Non-removable cameras use integers starting at 0 for their
     * identifiers, while removable cameras have a unique identifier for each
     * individual device, even if they are the same model.</p>
     *
     * <p>This list doesn't contain physical cameras that can only be used as part of a logical
     * multi-camera device.</p>
     *
     * @return The list of currently connected camera devices.
     */
    @NonNull
    public String[] getCameraIdList() throws CameraAccessException {
        return CameraManagerGlobal.get().getCameraIdList();
    }

    /**
     * Similar to getCameraIdList(). However, getCamerIdListNoLazy() necessarily communicates with
     * cameraserver in order to get the list of camera ids. This is to facilitate testing since some
     * camera ids may go 'offline' without callbacks from cameraserver because of changes in
     * SYSTEM_CAMERA permissions (though this is not a changeable permission, tests may call
     * adopt(drop)ShellPermissionIdentity() and effectively change their permissions). This call
     * affects the camera ids returned by getCameraIdList() as well. Tests which do adopt shell
     * permission identity should not mix getCameraIdList() and getCameraListNoLazyCalls().
     */
    /** @hide */
    @TestApi
    public String[] getCameraIdListNoLazy() throws CameraAccessException {
        return CameraManagerGlobal.get().getCameraIdListNoLazy();
    }

    /**
     * Return the set of combinations of currently connected camera device identifiers, which
     * support configuring camera device sessions concurrently.
     *
     * <p>The devices in these combinations can be concurrently configured by the same
     * client camera application. Using these camera devices concurrently by two different
     * applications is not guaranteed to be supported, however.</p>
     *
     * <p>For concurrent operation, in chronological order :
     * <ul>
     * <li> Applications must first close any open cameras that have sessions configured, using
     *   {@link CameraDevice#close}. </li>
     * <li> All camera devices intended to be operated concurrently, must be opened using
     *   {@link #openCamera}, before configuring sessions on any of the camera devices.</li>
     *</ul>
     *</p>
     * <p>Each device in a combination, is guaranteed to support stream combinations which may be
     * obtained by querying {@link #getCameraCharacteristics} for the key
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS}.</p>
     *
     * <p>For concurrent operation, if a camera device has a non null zoom ratio range as specified
     * by
     * {@link android.hardware.camera2.CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE},
     * its complete zoom ratio range may not apply. Applications can use
     * {@link android.hardware.camera2.CaptureRequest#CONTROL_ZOOM_RATIO} >=1 and  <=
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM}
     * during concurrent operation.
     * <p>
     *
     * <p>The set of combinations may include camera devices that may be in use by other camera API
     * clients.</p>
     *
     * <p>Concurrent camera extension sessions {@link CameraExtensionSession} are not currently
     * supported.</p>
     *
     * <p>The set of combinations doesn't contain physical cameras that can only be used as
     * part of a logical multi-camera device.</p>
     *
     * <p> If a new camera id becomes available through
     * {@link AvailabilityCallback#onCameraUnavailable(String)}, clients can call
     * this method to check if new combinations of camera ids which can stream concurrently are
     * available.
     *
     * @return The set of combinations of currently connected camera devices, that may have
     *         sessions configured concurrently. The set of combinations will be empty if no such
     *         combinations are supported by the camera subsystem.
     *
     * @throws CameraAccessException if the camera device has been disconnected.
     */
    @NonNull
    public Set<Set<String>> getConcurrentCameraIds() throws CameraAccessException {
        return CameraManagerGlobal.get().getConcurrentCameraIds();
    }

    /**
     * Checks whether the provided set of camera devices and their corresponding
     * {@link SessionConfiguration} can be configured concurrently.
     *
     * <p>This method performs a runtime check of the given {@link SessionConfiguration} and camera
     * id combinations. The result confirms whether or not the passed session configurations can be
     * successfully used to create camera capture sessions concurrently, on the given camera
     * devices using {@link CameraDevice#createCaptureSession(SessionConfiguration)}.
     * </p>
     *
     * <p>The method can be called at any point before, during and after active capture sessions.
     * It will not impact normal camera behavior in any way and must complete significantly
     * faster than creating a regular or constrained capture session.</p>
     *
     * <p>Although this method is faster than creating a new capture session, it is not intended
     * to be used for exploring the entire space of supported concurrent stream combinations. The
     * available mandatory concurrent stream combinations may be obtained by querying
     * {@link #getCameraCharacteristics} for the key
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS}. </p>
     *
     * <p>Note that session parameters will be ignored and calls to
     * {@link SessionConfiguration#setSessionParameters} are not required.</p>
     *
     * @return {@code true} if the given combination of session configurations and corresponding
     *                      camera ids are concurrently supported by the camera sub-system,
     *         {@code false} otherwise OR if the set of camera devices provided is not a subset of
     *                       those returned by {@link #getConcurrentCameraIds}.
     *
     * @throws CameraAccessException if one of the camera devices queried is no longer connected.
     *
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public boolean isConcurrentSessionConfigurationSupported(
            @NonNull Map<String, SessionConfiguration> cameraIdAndSessionConfig)
            throws CameraAccessException {
        return CameraManagerGlobal.get().isConcurrentSessionConfigurationSupported(
                cameraIdAndSessionConfig, mContext.getApplicationInfo().targetSdkVersion);
    }

    /**
     * Register a callback to be notified about camera device availability.
     *
     * <p>Registering the same callback again will replace the handler with the
     * new one provided.</p>
     *
     * <p>The first time a callback is registered, it is immediately called
     * with the availability status of all currently known camera devices.</p>
     *
     * <p>{@link AvailabilityCallback#onCameraUnavailable(String)} will be called whenever a camera
     * device is opened by any camera API client. As of API level 23, other camera API clients may
     * still be able to open such a camera device, evicting the existing client if they have higher
     * priority than the existing client of a camera device. See open() for more details.</p>
     *
     * <p>Since this callback will be registered with the camera service, remember to unregister it
     * once it is no longer needed; otherwise the callback will continue to receive events
     * indefinitely and it may prevent other resources from being released. Specifically, the
     * callbacks will be invoked independently of the general activity lifecycle and independently
     * of the state of individual CameraManager instances.</p>
     *
     * @param callback the new callback to send camera availability notices to
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *             the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the handler is {@code null} but the current thread has
     *             no looper.
     */
    public void registerAvailabilityCallback(@NonNull AvailabilityCallback callback,
            @Nullable Handler handler) {
        CameraManagerGlobal.get().registerAvailabilityCallback(callback,
                CameraDeviceImpl.checkAndWrapHandler(handler), mHasOpenCloseListenerPermission);
    }

    /**
     * Register a callback to be notified about camera device availability.
     *
     * <p>The behavior of this method matches that of
     * {@link #registerAvailabilityCallback(AvailabilityCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * <p>Note: If the order between some availability callbacks matters, the implementation of the
     * executor should handle those callbacks in the same thread to maintain the callbacks' order.
     * Some examples are:</p>
     *
     * <ul>
     *
     * <li>{@link AvailabilityCallback#onCameraAvailable} and
     * {@link AvailabilityCallback#onCameraUnavailable} of the same camera ID.</li>
     *
     * <li>{@link AvailabilityCallback#onCameraAvailable} or
     * {@link AvailabilityCallback#onCameraUnavailable} of a logical multi-camera, and {@link
     * AvailabilityCallback#onPhysicalCameraUnavailable} or
     * {@link AvailabilityCallback#onPhysicalCameraAvailable} of its physical
     * cameras.</li>
     *
     * </ul>
     *
     * @param executor The executor which will be used to invoke the callback.
     * @param callback the new callback to send camera availability notices to
     *
     * @throws IllegalArgumentException if the executor is {@code null}.
     */
    public void registerAvailabilityCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AvailabilityCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        CameraManagerGlobal.get().registerAvailabilityCallback(callback, executor,
                mHasOpenCloseListenerPermission);
    }

    /**
     * Remove a previously-added callback; the callback will no longer receive connection and
     * disconnection callbacks.
     *
     * <p>Removing a callback that isn't registered has no effect.</p>
     *
     * @param callback The callback to remove from the notification list
     */
    public void unregisterAvailabilityCallback(@NonNull AvailabilityCallback callback) {
        CameraManagerGlobal.get().unregisterAvailabilityCallback(callback);
    }

    /**
     * Register a callback to be notified about torch mode status.
     *
     * <p>Registering the same callback again will replace the handler with the
     * new one provided.</p>
     *
     * <p>The first time a callback is registered, it is immediately called
     * with the torch mode status of all currently known camera devices with a flash unit.</p>
     *
     * <p>Since this callback will be registered with the camera service, remember to unregister it
     * once it is no longer needed; otherwise the callback will continue to receive events
     * indefinitely and it may prevent other resources from being released. Specifically, the
     * callbacks will be invoked independently of the general activity lifecycle and independently
     * of the state of individual CameraManager instances.</p>
     *
     * @param callback The new callback to send torch mode status to
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *             the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the handler is {@code null} but the current thread has
     *             no looper.
     */
    public void registerTorchCallback(@NonNull TorchCallback callback, @Nullable Handler handler) {
        CameraManagerGlobal.get().registerTorchCallback(callback,
                CameraDeviceImpl.checkAndWrapHandler(handler));
    }

    /**
     * Register a callback to be notified about torch mode status.
     *
     * <p>The behavior of this method matches that of
     * {@link #registerTorchCallback(TorchCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * @param executor The executor which will be used to invoke the callback
     * @param callback The new callback to send torch mode status to
     *
     * @throws IllegalArgumentException if the executor is {@code null}.
     */
    public void registerTorchCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull TorchCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        CameraManagerGlobal.get().registerTorchCallback(callback, executor);
    }

    /**
     * Remove a previously-added callback; the callback will no longer receive torch mode status
     * callbacks.
     *
     * <p>Removing a callback that isn't registered has no effect.</p>
     *
     * @param callback The callback to remove from the notification list
     */
    public void unregisterTorchCallback(@NonNull TorchCallback callback) {
        CameraManagerGlobal.get().unregisterTorchCallback(callback);
    }

    // TODO(b/147726300): Investigate how to support foldables/multi-display devices.
    private Size getDisplaySize() {
        Size ret = new Size(0, 0);

        try {
            DisplayManager displayManager =
                    (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Point sz = new Point();
                display.getRealSize(sz);
                int width = sz.x;
                int height = sz.y;

                if (height > width) {
                    height = width;
                    width = sz.y;
                }

                ret = new Size(width, height);
            } else {
                Log.e(TAG, "Invalid default display!");
            }
        } catch (Exception e) {
            Log.e(TAG, "getDisplaySize Failed. " + e);
        }

        return ret;
    }

    /**
     * Get all physical cameras' multi-resolution stream configuration map
     *
     * <p>For a logical multi-camera, query the map between physical camera id and
     * the physical camera's multi-resolution stream configuration. This map is in turn
     * combined to form the logical camera's multi-resolution stream configuration map.</p>
     *
     * <p>For an ultra high resolution camera, directly use
     * android.scaler.physicalCameraMultiResolutionStreamConfigurations as the camera device's
     * multi-resolution stream configuration map.</p>
     */
    private Map<String, StreamConfiguration[]> getPhysicalCameraMultiResolutionConfigs(
            String cameraId, CameraMetadataNative info, ICameraService cameraService)
            throws CameraAccessException {
        HashMap<String, StreamConfiguration[]> multiResolutionStreamConfigurations =
                new HashMap<String, StreamConfiguration[]>();

        Boolean multiResolutionStreamSupported = info.get(
                CameraCharacteristics.SCALER_MULTI_RESOLUTION_STREAM_SUPPORTED);
        if (multiResolutionStreamSupported == null || !multiResolutionStreamSupported) {
            return multiResolutionStreamConfigurations;
        }

        // Query the characteristics of all physical sub-cameras, and combine the multi-resolution
        // stream configurations. Alternatively, for ultra-high resolution camera, directly use
        // its multi-resolution stream configurations. Note that framework derived formats such as
        // HEIC and DEPTH_JPEG aren't supported as multi-resolution input or output formats.
        Set<String> physicalCameraIds = info.getPhysicalCameraIds();
        if (physicalCameraIds.size() == 0 && info.isUltraHighResolutionSensor()) {
            StreamConfiguration[] configs = info.get(CameraCharacteristics.
                    SCALER_PHYSICAL_CAMERA_MULTI_RESOLUTION_STREAM_CONFIGURATIONS);
            if (configs != null) {
                multiResolutionStreamConfigurations.put(cameraId, configs);
            }
            return multiResolutionStreamConfigurations;
        }
        try {
            for (String physicalCameraId : physicalCameraIds) {
                CameraMetadataNative physicalCameraInfo =
                        cameraService.getCameraCharacteristics(physicalCameraId,
                                mContext.getApplicationInfo().targetSdkVersion,
                                /*overrideToPortrait*/false);
                StreamConfiguration[] configs = physicalCameraInfo.get(
                        CameraCharacteristics.
                                SCALER_PHYSICAL_CAMERA_MULTI_RESOLUTION_STREAM_CONFIGURATIONS);
                if (configs != null) {
                    multiResolutionStreamConfigurations.put(physicalCameraId, configs);
                }
            }
        } catch (RemoteException e) {
            ServiceSpecificException sse = new ServiceSpecificException(
                    ICameraService.ERROR_DISCONNECTED,
                    "Camera service is currently unavailable");
            throw ExceptionUtils.throwAsPublicException(sse);
        }

        return multiResolutionStreamConfigurations;
    }

    /**
     * <p>Query the capabilities of a camera device. These capabilities are
     * immutable for a given camera.</p>
     *
     * <p>From API level 29, this function can also be used to query the capabilities of physical
     * cameras that can only be used as part of logical multi-camera. These cameras cannot be
     * opened directly via {@link #openCamera}</p>
     *
     * <p>Also starting with API level 29, while most basic camera information is still available
     * even without the CAMERA permission, some values are not available to apps that do not hold
     * that permission. The keys not available are listed by
     * {@link CameraCharacteristics#getKeysNeedingPermission}.</p>
     *
     * @param cameraId The id of the camera device to query. This could be either a standalone
     * camera ID which can be directly opened by {@link #openCamera}, or a physical camera ID that
     * can only used as part of a logical multi-camera.
     * @return The properties of the given camera
     *
     * @throws IllegalArgumentException if the cameraId does not match any
     *         known camera device.
     * @throws CameraAccessException if the camera device has been disconnected.
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    @NonNull
    public CameraCharacteristics getCameraCharacteristics(@NonNull String cameraId)
            throws CameraAccessException {
        return getCameraCharacteristics(cameraId, shouldOverrideToPortrait(mContext));
    }

    /**
     * <p>Query the capabilities of a camera device. These capabilities are
     * immutable for a given camera.</p>
     *
     * <p>The value of {@link CameraCharacteristics.SENSOR_ORIENTATION} will change for landscape
     * cameras depending on whether overrideToPortrait is enabled. If enabled, these cameras will
     * appear to be portrait orientation instead, provided that the override is supported by the
     * camera device. Only devices that can be opened by {@link #openCamera} will report a changed
     * {@link CameraCharacteristics.SENSOR_ORIENTATION}.</p>
     *
     * @param cameraId The id of the camera device to query. This could be either a standalone
     * camera ID which can be directly opened by {@link #openCamera}, or a physical camera ID that
     * can only used as part of a logical multi-camera.
     * @param overrideToPortrait Whether to apply the landscape to portrait override.
     * @return The properties of the given camera
     *
     * @hide
     */
    @TestApi
    @NonNull
    public CameraCharacteristics getCameraCharacteristics(@NonNull String cameraId,
            boolean overrideToPortrait) throws CameraAccessException {
        CameraCharacteristics characteristics = null;
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No cameras available on device");
        }
        synchronized (mLock) {
            ICameraService cameraService = CameraManagerGlobal.get().getCameraService();
            if (cameraService == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable");
            }
            try {
                Size displaySize = getDisplaySize();

                CameraMetadataNative info = cameraService.getCameraCharacteristics(cameraId,
                        mContext.getApplicationInfo().targetSdkVersion, overrideToPortrait);
                try {
                    info.setCameraId(Integer.parseInt(cameraId));
                } catch (NumberFormatException e) {
                    Log.v(TAG, "Failed to parse camera Id " + cameraId + " to integer");
                }

                boolean hasConcurrentStreams =
                        CameraManagerGlobal.get().cameraIdHasConcurrentStreamsLocked(cameraId);
                info.setHasMandatoryConcurrentStreams(hasConcurrentStreams);
                info.setDisplaySize(displaySize);

                Map<String, StreamConfiguration[]> multiResolutionSizeMap =
                        getPhysicalCameraMultiResolutionConfigs(cameraId, info, cameraService);
                if (multiResolutionSizeMap.size() > 0) {
                    info.setMultiResolutionStreamConfigurationMap(multiResolutionSizeMap);
                }

                characteristics = new CameraCharacteristics(info);
            } catch (ServiceSpecificException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            } catch (RemoteException e) {
                // Camera service died - act as if the camera was disconnected
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable", e);
            }
        }
        registerDeviceStateListener(characteristics);
        return characteristics;
    }

    /**
     * <p>Query the camera extension capabilities of a camera device.</p>
     *
     * @param cameraId The id of the camera device to query. This must be a standalone
     * camera ID which can be directly opened by {@link #openCamera}.
     * @return The properties of the given camera
     *
     * @throws IllegalArgumentException if the cameraId does not match any
     *         known camera device.
     * @throws CameraAccessException if the camera device has been disconnected.
     *
     * @see CameraExtensionCharacteristics
     * @see CameraDevice#createExtensionSession(ExtensionSessionConfiguration)
     * @see CameraExtensionSession
     */
    @NonNull
    public CameraExtensionCharacteristics getCameraExtensionCharacteristics(
            @NonNull String cameraId) throws CameraAccessException {
        CameraCharacteristics chars = getCameraCharacteristics(cameraId);
        Map<String, CameraCharacteristics> characteristicsMap = getPhysicalIdToCharsMap(chars);
        characteristicsMap.put(cameraId, chars);

        return new CameraExtensionCharacteristics(mContext, cameraId, characteristicsMap);
    }

    /**
     * @hide
     */
    public Map<String, CameraCharacteristics> getPhysicalIdToCharsMap(
            CameraCharacteristics chars) throws CameraAccessException {
        HashMap<String, CameraCharacteristics> physicalIdsToChars =
                new HashMap<String, CameraCharacteristics>();
        Set<String> physicalCameraIds = chars.getPhysicalCameraIds();
        for (String physicalCameraId : physicalCameraIds) {
            CameraCharacteristics physicalChars = getCameraCharacteristics(physicalCameraId);
            physicalIdsToChars.put(physicalCameraId, physicalChars);
        }
        return physicalIdsToChars;
    }

    /**
     * Returns a {@link CameraDevice.CameraDeviceSetup} object for the given {@code cameraId},
     * which provides limited access to CameraDevice setup and query functionality without
     * requiring an {@link #openCamera} call. The {@link CameraDevice} can later be obtained either
     * by calling {@link #openCamera}, or {@link CameraDevice.CameraDeviceSetup#openCamera}.
     *
     * <p>Support for {@link CameraDevice.CameraDeviceSetup} for a given {@code cameraId} must be
     * checked with {@link #isCameraDeviceSetupSupported}. If {@code isCameraDeviceSetupSupported}
     * returns {@code false} for a {@code cameraId}, this method will throw an
     * {@link UnsupportedOperationException}</p>
     *
     * @param cameraId The unique identifier of the camera device for which
     *                 {@link CameraDevice.CameraDeviceSetup} object must be constructed. This
     *                 identifier must be present in {@link #getCameraIdList()}
     *
     * @return {@link CameraDevice.CameraDeviceSetup} object corresponding to the provided
     * {@code cameraId}
     *
     * @throws IllegalArgumentException If {@code cameraId} is null, or if {@code cameraId} does not
     * match any device in {@link #getCameraIdList()}.
     * @throws CameraAccessException if the camera device is not accessible
     * @throws UnsupportedOperationException if {@link CameraDevice.CameraDeviceSetup} instance
     * cannot be constructed for the given {@code cameraId}, i.e.
     * {@link #isCameraDeviceSetupSupported} returns false.
     *
     * @see CameraDevice.CameraDeviceSetup
     * @see #getCameraIdList()
     * @see #openCamera
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public CameraDevice.CameraDeviceSetup getCameraDeviceSetup(@NonNull String cameraId)
            throws CameraAccessException {
        // isCameraDeviceSetup does all the error checking we need.
        if (!isCameraDeviceSetupSupported(cameraId)) {
            throw new UnsupportedOperationException(
                    "CameraDeviceSetup is not supported for Camera ID: " + cameraId);
        }

        return getCameraDeviceSetupUnsafe(cameraId);

    }

    /**
     * Creates and returns a {@link CameraDeviceSetup} instance without any error checking. To
     * be used (carefully) by callers who are sure that CameraDeviceSetup instance can be legally
     * created and don't want to pay the latency cost of calling {@link #getCameraDeviceSetup}.
     */
    private CameraDevice.CameraDeviceSetup getCameraDeviceSetupUnsafe(@NonNull String cameraId) {
        return new CameraDeviceSetupImpl(cameraId, /*cameraManager=*/ this, mContext);
    }

    /**
     * Checks a Camera Device's characteristics to ensure that a
     * {@link CameraDevice.CameraDeviceSetup} instance can be constructed for a given
     * {@code cameraId}. If this method returns false for a {@code cameraId}, calling
     * {@link #getCameraDeviceSetup} for that {@code cameraId} will throw an
     * {@link UnsupportedOperationException}.
     *
     * <p>{@link CameraDevice.CameraDeviceSetup} is supported for all devices that report
     * {@link CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION} >
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}</p>
     *
     * @param cameraId The unique identifier of the camera device for which
     *                 {@link CameraDevice.CameraDeviceSetup} support is being queried. This
     *                 identifier must be present in {@link #getCameraIdList()}.
     *
     * @return {@code true} if {@link CameraDevice.CameraDeviceSetup} object can be constructed
     * for the provided {@code cameraId}; {@code false} otherwise.
     *
     * @throws IllegalArgumentException If {@code cameraId} is null, or if {@code cameraId} does not
     *                                  match any device in {@link #getCameraIdList()}.
     * @throws CameraAccessException    if the camera device is not accessible
     *
     * @see CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION
     * @see CameraDevice.CameraDeviceSetup
     * @see #getCameraDeviceSetup(String)
     * @see #getCameraIdList()
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public boolean isCameraDeviceSetupSupported(@NonNull String cameraId)
            throws CameraAccessException {
        if (cameraId == null) {
            throw new IllegalArgumentException("Camera ID was null");
        }

        if (CameraManagerGlobal.sCameraServiceDisabled
                || !Arrays.asList(CameraManagerGlobal.get().getCameraIdList()).contains(cameraId)) {
            throw new IllegalArgumentException(
                    "Camera ID '" + cameraId + "' not available on device.");
        }

        CameraCharacteristics chars = getCameraCharacteristics(cameraId);
        return CameraDeviceSetupImpl.isCameraDeviceSetupSupported(chars);
    }

    /**
     * Helper for opening a connection to a camera with the given ID.
     *
     * @param cameraId The unique identifier of the camera device to open
     * @param callback The callback for the camera. Must not be null.
     * @param executor The executor to invoke the callback with. Must not be null.
     * @param uid      The UID of the application actually opening the camera.
     *                 Must be USE_CALLING_UID unless the caller is a service
     *                 that is trusted to open the device on behalf of an
     *                 application and to forward the real UID.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * too many camera devices are already open, or the cameraId does not match
     * any currently available camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     * @throws IllegalArgumentException if callback or handler is null.
     * @return A handle to the newly-created camera device.
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    private CameraDevice openCameraDeviceUserAsync(String cameraId,
            CameraDevice.StateCallback callback, Executor executor, final int uid,
            final int oomScoreOffset, boolean overrideToPortrait) throws CameraAccessException {
        CameraCharacteristics characteristics = getCameraCharacteristics(cameraId);
        CameraDevice device = null;
        synchronized (mLock) {

            ICameraDeviceUser cameraUser = null;
            CameraDevice.CameraDeviceSetup cameraDeviceSetup = null;
            if (Flags.cameraDeviceSetup()
                    && CameraDeviceSetupImpl.isCameraDeviceSetupSupported(characteristics)) {
                cameraDeviceSetup = getCameraDeviceSetupUnsafe(cameraId);
            }

            android.hardware.camera2.impl.CameraDeviceImpl deviceImpl =
                    new android.hardware.camera2.impl.CameraDeviceImpl(
                        cameraId,
                        callback,
                        executor,
                        characteristics,
                        this,
                        mContext.getApplicationInfo().targetSdkVersion,
                        mContext, cameraDeviceSetup);
            ICameraDeviceCallbacks callbacks = deviceImpl.getCallbacks();

            try {
                ICameraService cameraService = CameraManagerGlobal.get().getCameraService();
                if (cameraService == null) {
                    throw new ServiceSpecificException(
                        ICameraService.ERROR_DISCONNECTED,
                        "Camera service is currently unavailable");
                }

                cameraUser = cameraService.connectDevice(callbacks, cameraId,
                    mContext.getOpPackageName(), mContext.getAttributionTag(), uid,
                    oomScoreOffset, mContext.getApplicationInfo().targetSdkVersion,
                    overrideToPortrait);
            } catch (ServiceSpecificException e) {
                if (e.errorCode == ICameraService.ERROR_DEPRECATED_HAL) {
                    throw new AssertionError("Should've gone down the shim path");
                } else if (e.errorCode == ICameraService.ERROR_CAMERA_IN_USE ||
                        e.errorCode == ICameraService.ERROR_MAX_CAMERAS_IN_USE ||
                        e.errorCode == ICameraService.ERROR_DISABLED ||
                        e.errorCode == ICameraService.ERROR_DISCONNECTED ||
                        e.errorCode == ICameraService.ERROR_INVALID_OPERATION) {
                    // Received one of the known connection errors
                    // The remote camera device cannot be connected to, so
                    // set the local camera to the startup error state
                    deviceImpl.setRemoteFailure(e);

                    if (e.errorCode == ICameraService.ERROR_DISABLED ||
                            e.errorCode == ICameraService.ERROR_DISCONNECTED ||
                            e.errorCode == ICameraService.ERROR_CAMERA_IN_USE) {
                        // Per API docs, these failures call onError and throw
                        throw ExceptionUtils.throwAsPublicException(e);
                    }
                } else {
                    // Unexpected failure - rethrow
                    throw ExceptionUtils.throwAsPublicException(e);
                }
            } catch (RemoteException e) {
                // Camera service died - act as if it's a CAMERA_DISCONNECTED case
                ServiceSpecificException sse = new ServiceSpecificException(
                    ICameraService.ERROR_DISCONNECTED,
                    "Camera service is currently unavailable");
                deviceImpl.setRemoteFailure(sse);
                throw ExceptionUtils.throwAsPublicException(sse);
            }

            // TODO: factor out callback to be non-nested, then move setter to constructor
            // For now, calling setRemoteDevice will fire initial
            // onOpened/onUnconfigured callbacks.
            // This function call may post onDisconnected and throw CAMERA_DISCONNECTED if
            // cameraUser dies during setup.
            deviceImpl.setRemoteDevice(cameraUser);
            device = deviceImpl;
        }

        return device;
    }

    /**
     * Open a connection to a camera with the given ID.
     *
     * <p>Use {@link #getCameraIdList} to get the list of available camera
     * devices. Note that even if an id is listed, open may fail if the device
     * is disconnected between the calls to {@link #getCameraIdList} and
     * {@link #openCamera}, or if a higher-priority camera API client begins using the
     * camera device.</p>
     *
     * <p>As of API level 23, devices for which the
     * {@link AvailabilityCallback#onCameraUnavailable(String)} callback has been called due to the
     * device being in use by a lower-priority, background camera API client can still potentially
     * be opened by calling this method when the calling camera API client has a higher priority
     * than the current camera API client using this device.  In general, if the top, foreground
     * activity is running within your application process, your process will be given the highest
     * priority when accessing the camera, and this method will succeed even if the camera device is
     * in use by another camera API client. Any lower-priority application that loses control of the
     * camera in this way will receive an
     * {@link android.hardware.camera2.CameraDevice.StateCallback#onDisconnected} callback.
     * Opening the same camera ID twice in the same application will similarly cause the
     * {@link android.hardware.camera2.CameraDevice.StateCallback#onDisconnected} callback
     * being fired for the {@link CameraDevice} from the first open call and all ongoing tasks
     * being dropped.</p>
     *
     * <p>Once the camera is successfully opened, {@link CameraDevice.StateCallback#onOpened} will
     * be invoked with the newly opened {@link CameraDevice}. The camera device can then be set up
     * for operation by calling {@link CameraDevice#createCaptureSession} and
     * {@link CameraDevice#createCaptureRequest}</p>
     *
     * <p>Before API level 30, when the application tries to open multiple {@link CameraDevice} of
     * different IDs and the device does not support opening such combination, either the
     * {@link #openCamera} will fail and throw a {@link CameraAccessException} or one or more of
     * already opened {@link CameraDevice} will be disconnected and receive
     * {@link android.hardware.camera2.CameraDevice.StateCallback#onDisconnected} callback. Which
     * behavior will happen depends on the device implementation and can vary on different devices.
     * Starting in API level 30, if the device does not support the combination of cameras being
     * opened, it is guaranteed the {@link #openCamera} call will fail and none of existing
     * {@link CameraDevice} will be disconnected.</p>
     *
     * <!--
     * <p>Since the camera device will be opened asynchronously, any asynchronous operations done
     * on the returned CameraDevice instance will be queued up until the device startup has
     * completed and the callback's {@link CameraDevice.StateCallback#onOpened onOpened} method is
     * called. The pending operations are then processed in order.</p>
     * -->
     * <p>If the camera becomes disconnected during initialization
     * after this function call returns,
     * {@link CameraDevice.StateCallback#onDisconnected} with a
     * {@link CameraDevice} in the disconnected state (and
     * {@link CameraDevice.StateCallback#onOpened} will be skipped).</p>
     *
     * <p>If opening the camera device fails, then the device callback's
     * {@link CameraDevice.StateCallback#onError onError} method will be called, and subsequent
     * calls on the camera device will throw a {@link CameraAccessException}.</p>
     *
     * @param cameraId
     *             The unique identifier of the camera device to open
     * @param callback
     *             The callback which is invoked once the camera is opened
     * @param handler
     *             The handler on which the callback should be invoked, or
     *             {@code null} to use the current thread's {@link android.os.Looper looper}.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * has been disconnected, is being used by a higher-priority camera API client, or the device
     * has reached its maximal resource and cannot open this camera device.
     *
     * @throws IllegalArgumentException if cameraId or the callback was null,
     * or the cameraId does not match any currently or previously available
     * camera device returned by {@link #getCameraIdList}.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public void openCamera(@NonNull String cameraId,
            @NonNull final CameraDevice.StateCallback callback, @Nullable Handler handler)
            throws CameraAccessException {

        openCameraForUid(cameraId, callback, CameraDeviceImpl.checkAndWrapHandler(handler),
                USE_CALLING_UID);
    }

    /**
     * Open a connection to a camera with the given ID. Also specify overrideToPortrait for testing.
     *
     * @param cameraId
     *             The unique identifier of the camera device to open
     * @param handler
     *             The handler on which the callback should be invoked, or
     *             {@code null} to use the current thread's {@link android.os.Looper looper}.
     * @param callback
     *             The callback which is invoked once the camera is opened
     * @param overrideToPortrait
     *             Whether to apply the landscape to portrait override, using rotate and crop.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * has been disconnected, or is being used by a higher-priority camera API client.
     *
     * @throws IllegalArgumentException if cameraId, the callback or the executor was null,
     * or the cameraId does not match any currently or previously available
     * camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public void openCamera(@NonNull String cameraId, boolean overrideToPortrait,
            @Nullable Handler handler,
            @NonNull final CameraDevice.StateCallback callback) throws CameraAccessException {
        openCameraForUid(cameraId, callback, CameraDeviceImpl.checkAndWrapHandler(handler),
                         USE_CALLING_UID, /*oomScoreOffset*/0, overrideToPortrait);
    }

    /**
     * Open a connection to a camera with the given ID.
     *
     * <p>The behavior of this method matches that of
     * {@link #openCamera(String, StateCallback, Handler)}, except that it uses
     * {@link java.util.concurrent.Executor} as an argument instead of
     * {@link android.os.Handler}.</p>
     *
     * @param cameraId
     *             The unique identifier of the camera device to open
     * @param executor
     *             The executor which will be used when invoking the callback.
     * @param callback
     *             The callback which is invoked once the camera is opened
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * has been disconnected, or is being used by a higher-priority camera API client.
     *
     * @throws IllegalArgumentException if cameraId, the callback or the executor was null,
     * or the cameraId does not match any currently or previously available
     * camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public void openCamera(@NonNull String cameraId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull final CameraDevice.StateCallback callback)
            throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        openCameraForUid(cameraId, callback, executor, USE_CALLING_UID);
    }

    /**
     * Open a connection to a camera with the given ID. Also specify what oom score must be offset
     * by cameraserver for this client. This api can be useful for system
     * components which want to assume a lower priority (for camera arbitration) than other clients
     * which it might contend for camera devices with. Increasing the oom score of a client reduces
     * its priority when the camera framework manages camera arbitration.
     * Considering typical use cases:
     *
     * 1) oom score(apps hosting activities visible to the user) - oom score(of a foreground app)
     *    is approximately 100.
     *
     * 2) The oom score (process which hosts components which that are perceptible to the user /
     *    native vendor camera clients) - oom (foreground app) is approximately 200.
     *
     * 3) The oom score (process which is cached hosting activities not visible) - oom (foreground
     *    app) is approximately 999.
     *
     * <p>The behavior of this method matches that of
     * {@link #openCamera(String, StateCallback, Handler)}, except that it uses
     * {@link java.util.concurrent.Executor} as an argument instead of
     * {@link android.os.Handler}.</p>
     *
     * @param cameraId
     *             The unique identifier of the camera device to open
     * @param executor
     *             The executor which will be used when invoking the callback.
     * @param callback
     *             The callback which is invoked once the camera is opened
     * @param oomScoreOffset
     *             The value by which the oom score of this client must be offset by the camera
     *             framework in order to assist it with camera arbitration. This value must be > 0.
     *             A positive value lowers the priority of this camera client compared to what the
     *             camera framework would have originally seen.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * has been disconnected, or is being used by a higher-priority camera API client.
     *
     * @throws IllegalArgumentException if cameraId, the callback or the executor was null,
     * or the cameraId does not match any currently or previously available
     * camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.SYSTEM_CAMERA,
            android.Manifest.permission.CAMERA,
    })
    public void openCamera(@NonNull String cameraId, int oomScoreOffset,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull final CameraDevice.StateCallback callback) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        if (oomScoreOffset < 0) {
            throw new IllegalArgumentException(
                    "oomScoreOffset < 0, cannot increase priority of camera client");
        }
        openCameraForUid(cameraId, callback, executor, USE_CALLING_UID, oomScoreOffset,
                shouldOverrideToPortrait(mContext));
    }

    /**
     * Open a connection to a camera with the given ID, on behalf of another application
     * specified by clientUid. Also specify the minimum oom score and process state the application
     * should have, as seen by the cameraserver.
     *
     * <p>The behavior of this method matches that of {@link #openCamera}, except that it allows
     * the caller to specify the UID to use for permission/etc verification. This can only be
     * done by services trusted by the camera subsystem to act on behalf of applications and
     * to forward the real UID.</p>
     *
     * @param clientUid
     *             The UID of the application on whose behalf the camera is being opened.
     *             Must be USE_CALLING_UID unless the caller is a trusted service.
     * @param oomScoreOffset
     *             The minimum oom score that cameraservice must see for this client.
     * @hide
     */
    public void openCameraForUid(@NonNull String cameraId,
            @NonNull final CameraDevice.StateCallback callback, @NonNull Executor executor,
            int clientUid, int oomScoreOffset, boolean overrideToPortrait)
            throws CameraAccessException {

        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback was null");
        }
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No cameras available on device");
        }

        openCameraDeviceUserAsync(cameraId, callback, executor, clientUid, oomScoreOffset,
                overrideToPortrait);
    }

    /**
     * Open a connection to a camera with the given ID, on behalf of another application
     * specified by clientUid.
     *
     * <p>The behavior of this method matches that of {@link #openCamera}, except that it allows
     * the caller to specify the UID to use for permission/etc verification. This can only be
     * done by services trusted by the camera subsystem to act on behalf of applications and
     * to forward the real UID.</p>
     *
     * @param clientUid
     *             The UID of the application on whose behalf the camera is being opened.
     *             Must be USE_CALLING_UID unless the caller is a trusted service.
     *
     * @hide
     */
    public void openCameraForUid(@NonNull String cameraId,
            @NonNull final CameraDevice.StateCallback callback, @NonNull Executor executor,
            int clientUid) throws CameraAccessException {
        openCameraForUid(cameraId, callback, executor, clientUid, /*oomScoreOffset*/0,
                shouldOverrideToPortrait(mContext));
    }

    /**
     * Set the flash unit's torch mode of the camera of the given ID without opening the camera
     * device.
     *
     * <p>Use {@link #getCameraIdList} to get the list of available camera devices and use
     * {@link #getCameraCharacteristics} to check whether the camera device has a flash unit.
     * Note that even if a camera device has a flash unit, turning on the torch mode may fail
     * if the camera device or other camera resources needed to turn on the torch mode are in use.
     * </p>
     *
     * <p> If {@link #setTorchMode} is called to turn on or off the torch mode successfully,
     * {@link CameraManager.TorchCallback#onTorchModeChanged} will be invoked.
     * However, even if turning on the torch mode is successful, the application does not have the
     * exclusive ownership of the flash unit or the camera device. The torch mode will be turned
     * off and becomes unavailable when the camera device that the flash unit belongs to becomes
     * unavailable or when other camera resources to keep the torch on become unavailable (
     * {@link CameraManager.TorchCallback#onTorchModeUnavailable} will be invoked). Also,
     * other applications are free to call {@link #setTorchMode} to turn off the torch mode (
     * {@link CameraManager.TorchCallback#onTorchModeChanged} will be invoked). If the latest
     * application that turned on the torch mode exits, the torch mode will be turned off.
     *
     * @param cameraId
     *             The unique identifier of the camera device that the flash unit belongs to.
     * @param enabled
     *             The desired state of the torch mode for the target camera device. Set to
     *             {@code true} to turn on the torch mode. Set to {@code false} to turn off the
     *             torch mode.
     *
     * @throws CameraAccessException if it failed to access the flash unit.
     *             {@link CameraAccessException#CAMERA_IN_USE} will be thrown if the camera device
     *             is in use. {@link CameraAccessException#MAX_CAMERAS_IN_USE} will be thrown if
     *             other camera resources needed to turn on the torch mode are in use.
     *             {@link CameraAccessException#CAMERA_DISCONNECTED} will be thrown if camera
     *             service is not available.
     *
     * @throws IllegalArgumentException if cameraId was null, cameraId doesn't match any currently
     *             or previously available camera device, or the camera device doesn't have a
     *             flash unit.
     */
    public void setTorchMode(@NonNull String cameraId, boolean enabled)
            throws CameraAccessException {
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No cameras available on device");
        }
        CameraManagerGlobal.get().setTorchMode(cameraId, enabled);
    }

    /**
     * Set the brightness level of the flashlight associated with the given cameraId in torch
     * mode. If the torch is OFF and torchStrength is >= 1, torch will turn ON with the
     * strength level specified in torchStrength.
     *
     * <p>Use
     * {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_MAXIMUM_LEVEL}
     * to check whether the camera device supports flash unit strength control or not. If this value
     * is greater than 1, applications can call this API to control the flashlight brightness level.
     * </p>
     *
     * <p>If {@link #turnOnTorchWithStrengthLevel} is called to change the brightness level of the
     * flash unit {@link CameraManager.TorchCallback#onTorchStrengthLevelChanged} will be invoked.
     * If the new desired strength level is same as previously set level, then this callback will
     * not be invoked.
     * If the torch is OFF and {@link #turnOnTorchWithStrengthLevel} is called with level >= 1,
     * the torch will be turned ON with that brightness level. In this case
     * {@link CameraManager.TorchCallback#onTorchModeChanged} will also be invoked.
     * </p>
     *
     * <p>When the torch is turned OFF via {@link #setTorchMode}, the flashlight brightness level
     * will reset to default value
     * {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_DEFAULT_LEVEL}
     * In this case the {@link CameraManager.TorchCallback#onTorchStrengthLevelChanged} will not be
     * invoked.
     * </p>
     *
     * <p>If torch is enabled via {@link #setTorchMode} after calling
     * {@link #turnOnTorchWithStrengthLevel} with level N then the flash unit will have the
     * brightness level N.
     * Since multiple applications are free to call {@link #setTorchMode}, when the latest
     * application that turned ON the torch mode exits, the torch mode will be turned OFF
     * and in this case the brightness level will reset to default level.
     * </p>
     *
     * @param cameraId
     *             The unique identifier of the camera device that the flash unit belongs to.
     * @param torchStrength
     *             The desired brightness level to be set for the flash unit in the range 1 to
     *             {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_MAXIMUM_LEVEL}.
     *
     * @throws CameraAccessException if it failed to access the flash unit.
     *             {@link CameraAccessException#CAMERA_IN_USE} will be thrown if the camera device
     *             is in use. {@link CameraAccessException#MAX_CAMERAS_IN_USE} will be thrown if
     *             other camera resources needed to turn on the torch mode are in use.
     *             {@link CameraAccessException#CAMERA_DISCONNECTED} will be thrown if camera
     *             service is not available.
     * @throws IllegalArgumentException if cameraId was null, cameraId doesn't match any currently
     *              or previously available camera device, the camera device doesn't have a
     *              flash unit or if torchStrength is not within the range i.e. is greater than
     *              the maximum level
     *              {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_MAXIMUM_LEVEL}
     *              or <= 0.
     *
     */
    public void turnOnTorchWithStrengthLevel(@NonNull String cameraId, int torchStrength)
            throws CameraAccessException {
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No camera available on device");
        }
        CameraManagerGlobal.get().turnOnTorchWithStrengthLevel(cameraId, torchStrength);
    }

    /**
     * Returns the brightness level of the flash unit associated with the cameraId.
     *
     * @param cameraId
     *              The unique identifier of the camera device that the flash unit belongs to.
     * @return The brightness level of the flash unit associated with cameraId.
     *         When the torch is turned OFF, the strength level will reset to a default level
     *         {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_DEFAULT_LEVEL}.
     *         In this case the return value will be
     *         {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_DEFAULT_LEVEL}
     *         rather than 0.
     *
     * @throws CameraAccessException if it failed to access the flash unit.
     * @throws IllegalArgumentException if cameraId was null, cameraId doesn't match any currently
     *              or previously available camera device, or the camera device doesn't have a
     *              flash unit.
     *
     */
    public int getTorchStrengthLevel(@NonNull String cameraId)
            throws CameraAccessException {
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No camera available on device.");
        }
        return CameraManagerGlobal.get().getTorchStrengthLevel(cameraId);
    }

    /**
     * @hide
     */
    public static boolean shouldOverrideToPortrait(@Nullable Context context) {
        PackageManager packageManager = null;
        String packageName = null;

        if (context != null) {
            packageManager = context.getPackageManager();
            packageName = context.getOpPackageName();
        }

        return shouldOverrideToPortrait(packageManager, packageName);
    }

    /**
     * @hide
     */
    @TestApi
    public static boolean shouldOverrideToPortrait(@Nullable PackageManager packageManager,
                                                   @Nullable String packageName) {
        if (!CameraManagerGlobal.sLandscapeToPortrait) {
            return false;
        }

        if (packageManager != null && packageName != null) {
            try {
                return packageManager.getProperty(
                        PackageManager.PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT,
                        packageName).getBoolean();
            } catch (PackageManager.NameNotFoundException e) {
                // No such property
            }
        }

        return CompatChanges.isChangeEnabled(OVERRIDE_CAMERA_LANDSCAPE_TO_PORTRAIT);
    }

    /**
     * @hide
     */
    public static boolean physicalCallbacksAreEnabledForUnavailableCamera() {
        return CompatChanges.isChangeEnabled(
                ENABLE_PHYSICAL_CAMERA_CALLBACK_FOR_UNAVAILABLE_LOGICAL_CAMERA);
    }

    /**
     * A callback for camera devices becoming available or unavailable to open.
     *
     * <p>Cameras become available when they are no longer in use, or when a new
     * removable camera is connected. They become unavailable when some
     * application or service starts using a camera, or when a removable camera
     * is disconnected.</p>
     *
     * <p>Extend this callback and pass an instance of the subclass to
     * {@link CameraManager#registerAvailabilityCallback} to be notified of such availability
     * changes.</p>
     *
     * @see #registerAvailabilityCallback
     */
    public static abstract class AvailabilityCallback {

        /**
         * A new camera has become available to use.
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the new camera.
         */
        public void onCameraAvailable(@NonNull String cameraId) {
            // default empty implementation
        }

        /**
         * A previously-available camera has become unavailable for use.
         *
         * <p>If an application had an active CameraDevice instance for the
         * now-disconnected camera, that application will receive a
         * {@link CameraDevice.StateCallback#onDisconnected disconnection error}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the disconnected camera.
         */
        public void onCameraUnavailable(@NonNull String cameraId) {
            // default empty implementation
        }

        /**
         * Called whenever camera access priorities change.
         *
         * <p>Notification that camera access priorities have changed and the camera may
         * now be openable. An application that was previously denied camera access due to
         * a higher-priority user already using the camera, or that was disconnected from an
         * active camera session due to a higher-priority user trying to open the camera,
         * should try to open the camera again if it still wants to use it.  Note that
         * multiple applications may receive this callback at the same time, and only one of
         * them will succeed in opening the camera in practice, depending on exact access
         * priority levels and timing. This method is useful in cases where multiple
         * applications may be in the resumed state at the same time, and the user switches
         * focus between them, or if the current camera-using application moves between
         * full-screen and Picture-in-Picture (PiP) states. In such cases, the camera
         * available/unavailable callbacks will not be invoked, but another application may
         * now have higher priority for camera access than the current camera-using
         * application.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         */
        public void onCameraAccessPrioritiesChanged() {
            // default empty implementation
        }

        /**
         * A physical camera has become available for use again.
         *
         * <p>By default, all of the physical cameras of a logical multi-camera are
         * available, so {@link #onPhysicalCameraAvailable} is not called for any of the physical
         * cameras of a logical multi-camera, when {@link #onCameraAvailable} for the logical
         * multi-camera is invoked. However, if some specific physical cameras are unavailable
         * to begin with, {@link #onPhysicalCameraUnavailable} may be invoked after
         * {@link #onCameraAvailable}.</p>
         *
         * <p>If {@link android.content.pm.ApplicationInfo#targetSdkVersion targetSdkVersion}
         * &lt; {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, opening a logical camera
         * disables the {@link #onPhysicalCameraAvailable} and {@link #onPhysicalCameraUnavailable}
         * callbacks for its physical cameras. For example, if app A opens the camera device:</p>
         *
         * <ul>
         *
         * <li>All apps subscribing to ActivityCallback get {@link #onCameraUnavailable}.</li>
         *
         * <li>No app (including app A) subscribing to ActivityCallback gets
         * {@link #onPhysicalCameraAvailable} or {@link #onPhysicalCameraUnavailable}, because
         * the logical camera is unavailable (some app is using it).</li>
         *
         * </ul>
         *
         * <p>If {@link android.content.pm.ApplicationInfo#targetSdkVersion targetSdkVersion}
         * &ge; {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}:</p>
         *
         * <ul>
         *
         * <li>A physical camera status change will trigger {@link #onPhysicalCameraAvailable}
         * or {@link #onPhysicalCameraUnavailable} even after the logical camera becomes
         * unavailable. A {@link #onCameraUnavailable} call for a logical camera doesn't reset the
         * physical cameras' availability status. This makes it possible for an application opening
         * the logical camera device to know which physical camera becomes unavailable or available
         * to use.</li>
         *
         * <li>Similar to {@link android.os.Build.VERSION_CODES#TIRAMISU Android 13} and earlier,
         * the logical camera's {@link #onCameraAvailable} callback implies all of its physical
         * cameras' status become available. {@link #onPhysicalCameraUnavailable} will be called
         * for any unavailable physical cameras upon the logical camera becoming available.</li>
         *
         * </ul>
         *
         * <p>Given the pipeline nature of the camera capture through {@link
         * android.hardware.camera2.CaptureRequest}, there may be frame drops if the application
         * requests images from a physical camera of a logical multi-camera and that physical camera
         * becomes unavailable. The application should stop requesting directly from an unavailable
         * physical camera as soon as {@link #onPhysicalCameraUnavailable} is received, and also be
         * ready to robustly handle frame drop errors for requests targeting physical cameras,
         * since those errors may arrive before the unavailability callback.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the logical multi-camera.
         * @param physicalCameraId The unique identifier of the physical camera.
         *
         * @see #onCameraAvailable
         * @see #onPhysicalCameraUnavailable
         */
        public void onPhysicalCameraAvailable(@NonNull String cameraId,
                @NonNull String physicalCameraId) {
            // default empty implementation
        }

        /**
         * A previously-available physical camera has become unavailable for use.
         *
         * <p>By default, all of the physical cameras of a logical multi-camera are
         * unavailable if the logical camera itself is unavailable.
         * No availability callbacks will be called for any of the physical
         * cameras of its parent logical multi-camera, when {@link #onCameraUnavailable} for
         * the logical multi-camera is invoked.</p>
         *
         * <p>If {@link android.content.pm.ApplicationInfo#targetSdkVersion targetSdkVersion}
         * &lt; {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, opening a logical camera
         * disables the {@link #onPhysicalCameraAvailable} and {@link #onPhysicalCameraUnavailable}
         * callbacks for its physical cameras. For example, if app A opens the camera device:</p>
         *
         * <ul>
         *
         * <li>All apps subscribing to ActivityCallback get {@link #onCameraUnavailable}.</li>
         *
         * <li>No app (including app A) subscribing to ActivityCallback gets
         * {@link #onPhysicalCameraAvailable} or {@link #onPhysicalCameraUnavailable}, because
         * the logical camera is unavailable (some app is using it).</li>
         *
         * </ul>
         *
         * <p>If {@link android.content.pm.ApplicationInfo#targetSdkVersion targetSdkVersion}
         * &ge; {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}:</p>
         *
         * <ul>
         *
         * <li>A physical camera status change will trigger {@link #onPhysicalCameraAvailable}
         * or {@link #onPhysicalCameraUnavailable} even after the logical camera becomes
         * unavailable. A {@link #onCameraUnavailable} call for a logical camera doesn't reset the
         * physical cameras' availability status. This makes it possible for an application opening
         * the logical camera device to know which physical camera becomes unavailable or available
         * to use.</li>
         *
         * <li>Similar to {@link android.os.Build.VERSION_CODES#TIRAMISU Android 13} and earlier,
         * the logical camera's {@link #onCameraAvailable} callback implies all of its physical
         * cameras' status become available. {@link #onPhysicalCameraUnavailable} will be called
         * for any unavailable physical cameras upon the logical camera becoming available.</li>
         *
         * </ul>
         *
         * <p>Given the pipeline nature of the camera capture through {@link
         * android.hardware.camera2.CaptureRequest}, there may be frame drops if the application
         * requests images from a physical camera of a logical multi-camera and that physical camera
         * becomes unavailable. The application should stop requesting directly from an unavailable
         * physical camera as soon as {@link #onPhysicalCameraUnavailable} is received, and also be
         * ready to robustly handle frame drop errors for requests targeting physical cameras,
         * since those errors may arrive before the unavailability callback.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the logical multi-camera.
         * @param physicalCameraId The unique identifier of the physical camera.
         *
         * @see #onCameraAvailable
         * @see #onPhysicalCameraAvailable
         */
        public void onPhysicalCameraUnavailable(@NonNull String cameraId,
                @NonNull String physicalCameraId) {
            // default empty implementation
        }

        /**
         * A camera device has been opened by an application.
         *
         * <p>The default implementation of this method does nothing.</p>
         *    android.Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER is required to receive this
         *    callback
         * @param cameraId The unique identifier of the camera opened.
         * @param packageId The package Id of the application opening the camera.
         *
         * @see #onCameraClosed
         * @hide
         */
        @SystemApi
        @TestApi
        @RequiresPermission(android.Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER)
        public void onCameraOpened(@NonNull String cameraId, @NonNull String packageId) {
            // default empty implementation
        }

        /**
         * A previously-opened camera has been closed.
         *
         * <p>The default implementation of this method does nothing.</p>
         *    android.Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER is required to receive this
         *    callback.
         * @param cameraId The unique identifier of the closed camera.
         * @hide
         */
        @SystemApi
        @TestApi
        @RequiresPermission(android.Manifest.permission.CAMERA_OPEN_CLOSE_LISTENER)
        public void onCameraClosed(@NonNull String cameraId) {
            // default empty implementation
        }
    }

    /**
     * A callback for camera flash torch modes becoming unavailable, disabled, or enabled.
     *
     * <p>The torch mode becomes unavailable when the camera device it belongs to becomes
     * unavailable or other camera resources it needs become busy due to other higher priority
     * camera activities. The torch mode becomes disabled when it was turned off or when the camera
     * device it belongs to is no longer in use and other camera resources it needs are no longer
     * busy. A camera's torch mode is turned off when an application calls {@link #setTorchMode} to
     * turn off the camera's torch mode, or when an application turns on another camera's torch mode
     * if keeping multiple torch modes on simultaneously is not supported. The torch mode becomes
     * enabled when it is turned on via {@link #setTorchMode}.</p>
     *
     * <p>The torch mode is available to set via {@link #setTorchMode} only when it's in a disabled
     * or enabled state.</p>
     *
     * <p>Extend this callback and pass an instance of the subclass to
     * {@link CameraManager#registerTorchCallback} to be notified of such status changes.
     * </p>
     *
     * @see #registerTorchCallback
     */
    public static abstract class TorchCallback {
        /**
         * A camera's torch mode has become unavailable to set via {@link #setTorchMode}.
         *
         * <p>If torch mode was previously turned on by calling {@link #setTorchMode}, it will be
         * turned off before {@link CameraManager.TorchCallback#onTorchModeUnavailable} is
         * invoked. {@link #setTorchMode} will fail until the torch mode has entered a disabled or
         * enabled state again.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the camera whose torch mode has become
         *                 unavailable.
         */
        public void onTorchModeUnavailable(@NonNull String cameraId) {
            // default empty implementation
        }

        /**
         * A camera's torch mode has become enabled or disabled and can be changed via
         * {@link #setTorchMode}.
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the camera whose torch mode has been changed.
         *
         * @param enabled The state that the torch mode of the camera has been changed to.
         *                {@code true} when the torch mode has become on and available to be turned
         *                off. {@code false} when the torch mode has becomes off and available to
         *                be turned on.
         */
        public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
            // default empty implementation
        }

        /**
         * A camera's flash unit brightness level has been changed in torch mode via
         * {@link #turnOnTorchWithStrengthLevel}. When the torch is turned OFF, this
         * callback will not be triggered even though the torch strength level resets to
         * default value
         * {@link android.hardware.camera2.CameraCharacteristics#FLASH_INFO_STRENGTH_DEFAULT_LEVEL}
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the camera whose flash unit brightness level has
         * been changed.
         *
         * @param newStrengthLevel The brightness level of the flash unit that has been changed to.
         */
        public void onTorchStrengthLevelChanged(@NonNull String cameraId, int newStrengthLevel) {
            // default empty implementation
        }
    }

    /**
     * Queries the camera service if a cameraId is a hidden physical camera that belongs to a
     * logical camera device.
     *
     * A hidden physical camera is a camera that cannot be opened by the application. But it
     * can be used as part of a logical camera.
     *
     * @param cameraId a non-{@code null} camera identifier
     * @return {@code true} if cameraId is a hidden physical camera device
     *
     * @hide
     */
    public static boolean isHiddenPhysicalCamera(String cameraId) {
        try {
            ICameraService cameraService = CameraManagerGlobal.get().getCameraService();
            // If no camera service, no support
            if (cameraService == null) return false;

            return cameraService.isHiddenPhysicalCamera(cameraId);
        } catch (RemoteException e) {
            // Camera service is now down, no support for any API level
        }
        return false;
    }

    /**
     * Inject the external camera to replace the internal camera session.
     *
     * <p>If injecting the external camera device fails, then the injection callback's
     * {@link CameraInjectionSession.InjectionStatusCallback#onInjectionError
     * onInjectionError} method will be called.</p>
     *
     * @param packageName   It scopes the injection to a particular app.
     * @param internalCamId The id of one of the physical or logical cameras on the phone.
     * @param externalCamId The id of one of the remote cameras that are provided by the dynamic
     *                      camera HAL.
     * @param executor      The executor which will be used when invoking the callback.
     * @param callback      The callback which is invoked once the external camera is injected.
     *
     * @throws CameraAccessException    If the camera device has been disconnected.
     *                                  {@link CameraAccessException#CAMERA_DISCONNECTED} will be
     *                                  thrown if camera service is not available.
     * @throws SecurityException        If the specific application that can cast to external
     *                                  devices does not have permission to inject the external
     *                                  camera.
     * @throws IllegalArgumentException If cameraId doesn't match any currently or previously
     *                                  available camera device or some camera functions might not
     *                                  work properly or the injection camera runs into a fatal
     *                                  error.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void injectCamera(@NonNull String packageName, @NonNull String internalCamId,
            @NonNull String externalCamId, @NonNull @CallbackExecutor Executor executor,
            @NonNull CameraInjectionSession.InjectionStatusCallback callback)
            throws CameraAccessException, SecurityException,
            IllegalArgumentException {
        if (CameraManagerGlobal.sCameraServiceDisabled) {
            throw new IllegalArgumentException("No cameras available on device");
        }
        ICameraService cameraService = CameraManagerGlobal.get().getCameraService();
        if (cameraService == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                    "Camera service is currently unavailable");
        }
        synchronized (mLock) {
            try {
                CameraInjectionSessionImpl injectionSessionImpl =
                        new CameraInjectionSessionImpl(callback, executor);
                ICameraInjectionCallback cameraInjectionCallback =
                        injectionSessionImpl.getCallback();
                ICameraInjectionSession injectionSession = cameraService.injectCamera(packageName,
                        internalCamId, externalCamId, cameraInjectionCallback);
                injectionSessionImpl.setRemoteInjectionSession(injectionSession);
            } catch (ServiceSpecificException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            } catch (RemoteException e) {
                // Camera service died - act as if it's a CAMERA_DISCONNECTED case
                ServiceSpecificException sse = new ServiceSpecificException(
                        ICameraService.ERROR_DISCONNECTED,
                        "Camera service is currently unavailable");
                throw ExceptionUtils.throwAsPublicException(sse);
            }
        }
    }

    /**
     * Remaps Camera Ids in the CameraService.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void remapCameraIds(@NonNull CameraIdRemapping cameraIdRemapping)
            throws CameraAccessException, SecurityException, IllegalArgumentException {
        CameraManagerGlobal.get().remapCameraIds(cameraIdRemapping);
    }

    /**
     * Injects session params into existing clients in the CameraService.
     *
     * @param cameraId       The camera id of client to inject session params into.
     *                       If no such client exists for cameraId, no injection will
     *                       take place.
     * @param sessionParams  A {@link CaptureRequest} object containing the
     *                       the sessionParams to inject into the existing client.
     *
     * @throws CameraAccessException    {@link CameraAccessException#CAMERA_DISCONNECTED} will be
     *                                  thrown if camera service is not available. Further, if
     *                                  if no such client exists for cameraId,
     *                                  {@link CameraAccessException#CAMERA_ERROR} will be thrown.
     * @throws SecurityException        If the caller does not have permission to inject session
     *                                  params
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void injectSessionParams(@NonNull String cameraId, @NonNull CaptureRequest sessionParams)
            throws CameraAccessException, SecurityException {
        CameraManagerGlobal.get().injectSessionParams(cameraId, sessionParams);
    }

    /**
     * Returns the current CameraService instance connected to Global
     * @hide
     */
    public ICameraService getCameraService() {
        return CameraManagerGlobal.get().getCameraService();
    }

    /**
     * Returns true if cameraservice is currently disabled. If true, {@link #getCameraService()}
     * will definitely return null.
     * @hide
     */
    public boolean isCameraServiceDisabled() {
        return CameraManagerGlobal.sCameraServiceDisabled;
    }

    /**
     * Reports {@link CameraExtensionSessionStats} to the {@link ICameraService} to be logged for
     * currently active session. Validation is done downstream.
     *
     * @param extStats Extension Session stats to be logged by cameraservice
     *
     * @return the key to be used with the next call.
     *         See {@link ICameraService#reportExtensionSessionStats}.
     * @hide
     */
    public static String reportExtensionSessionStats(CameraExtensionSessionStats extStats) {
        ICameraService cameraService = CameraManagerGlobal.get().getCameraService();
        if (cameraService == null) {
            Log.e(TAG, "CameraService not available. Not reporting extension stats.");
            return "";
        }
        try {
            return cameraService.reportExtensionSessionStats(extStats);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to report extension session stats to cameraservice.", e);
        }
        return "";
    }

    /**
     * A per-process global camera manager instance, to retain a connection to the camera service,
     * and to distribute camera availability notices to API-registered callbacks
     */
    private static final class CameraManagerGlobal extends ICameraServiceListener.Stub
            implements IBinder.DeathRecipient {

        private static final String TAG = "CameraManagerGlobal";
        private final boolean DEBUG = false;

        private final int CAMERA_SERVICE_RECONNECT_DELAY_MS = 1000;

        // Singleton instance
        private static final CameraManagerGlobal gCameraManager =
            new CameraManagerGlobal();

        /**
         * This must match the ICameraService definition
         */
        private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

        private final ScheduledExecutorService mScheduler = Executors.newScheduledThreadPool(1);
        // Camera ID -> Status map
        private final ArrayMap<String, Integer> mDeviceStatus = new ArrayMap<String, Integer>();
        // Camera ID -> (physical camera ID -> Status map)
        private final ArrayMap<String, ArrayList<String>> mUnavailablePhysicalDevices =
                new ArrayMap<String, ArrayList<String>>();
        // Opened Camera ID -> apk name map
        private final ArrayMap<String, String> mOpenedDevices = new ArrayMap<String, String>();

        private final Set<Set<String>> mConcurrentCameraIdCombinations =
                new ArraySet<Set<String>>();

        // Registered availability callbacks and their executors
        private final ArrayMap<AvailabilityCallback, Executor> mCallbackMap =
            new ArrayMap<AvailabilityCallback, Executor>();

        // torch client binder to set the torch mode with.
        private Binder mTorchClientBinder = new Binder();

        // Camera ID -> Torch status map
        private final ArrayMap<String, Integer> mTorchStatus = new ArrayMap<String, Integer>();

        // Registered torch callbacks and their executors
        private final ArrayMap<TorchCallback, Executor> mTorchCallbackMap =
                new ArrayMap<TorchCallback, Executor>();

        private final Object mLock = new Object();

        /**
         * The active CameraIdRemapping. This will be used to refresh the cameraIdRemapping state
         * in the CameraService every time we connect to it, including when the CameraService
         * Binder dies and we reconnect to it.
         */
        @Nullable private CameraIdRemapping mActiveCameraIdRemapping;

        // Access only through getCameraService to deal with binder death
        private ICameraService mCameraService;
        private boolean mHasOpenCloseListenerPermission = false;

        private HandlerThread mDeviceStateHandlerThread;
        private Handler mDeviceStateHandler;
        private FoldStateListener mFoldStateListener;

        // Singleton, don't allow construction
        private CameraManagerGlobal() { }

        public static final boolean sCameraServiceDisabled =
                SystemProperties.getBoolean("config.disable_cameraservice", false);

        public static final boolean sLandscapeToPortrait =
                SystemProperties.getBoolean(LANDSCAPE_TO_PORTRAIT_PROP, false);

        public static CameraManagerGlobal get() {
            return gCameraManager;
        }

        public void registerDeviceStateListener(@NonNull CameraCharacteristics chars,
                @NonNull Context ctx) {
            synchronized(mLock) {
                if (mDeviceStateHandlerThread == null) {
                    mDeviceStateHandlerThread = new HandlerThread(TAG);
                    mDeviceStateHandlerThread.start();
                    mDeviceStateHandler = new Handler(mDeviceStateHandlerThread.getLooper());
                }

                if (mFoldStateListener == null) {
                    mFoldStateListener = new FoldStateListener(ctx);
                    try {
                        ctx.getSystemService(DeviceStateManager.class).registerCallback(
                                new HandlerExecutor(mDeviceStateHandler), mFoldStateListener);
                    } catch (IllegalStateException e) {
                        mFoldStateListener = null;
                        Log.v(TAG, "Failed to register device state listener!");
                        Log.v(TAG, "Device state dependent characteristics updates will not be" +
                                "functional!");
                        return;
                    }
                }

                mFoldStateListener.addDeviceStateListener(chars.getDeviceStateListener());
            }
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        /**
         * Return a best-effort ICameraService.
         *
         * <p>This will be null if the camera service is not currently available. If the camera
         * service has died since the last use of the camera service, will try to reconnect to the
         * service.</p>
         */
        public ICameraService getCameraService() {
            synchronized(mLock) {
                connectCameraServiceLocked();
                if (mCameraService == null && !sCameraServiceDisabled) {
                    Log.e(TAG, "Camera service is unavailable");
                }
                return mCameraService;
            }
        }

        /**
         * Connect to the camera service if it's available, and set up listeners.
         * If the service is already connected, do nothing.
         *
         * <p>Sets mCameraService to a valid pointer or null if the connection does not succeed.</p>
         */
        private void connectCameraServiceLocked() {
            // Only reconnect if necessary
            if (mCameraService != null || sCameraServiceDisabled) return;

            Log.i(TAG, "Connecting to camera service");

            IBinder cameraServiceBinder = ServiceManager.getService(CAMERA_SERVICE_BINDER_NAME);
            if (cameraServiceBinder == null) {
                // Camera service is now down, leave mCameraService as null
                return;
            }
            try {
                cameraServiceBinder.linkToDeath(this, /*flags*/ 0);
            } catch (RemoteException e) {
                // Camera service is now down, leave mCameraService as null
                return;
            }

            ICameraService cameraService = ICameraService.Stub.asInterface(cameraServiceBinder);

            try {
                CameraMetadataNative.setupGlobalVendorTagDescriptor();
            } catch (ServiceSpecificException e) {
                handleRecoverableSetupErrors(e);
            }

            try {
                CameraStatus[] cameraStatuses = cameraService.addListener(this);
                for (CameraStatus c : cameraStatuses) {
                    onStatusChangedLocked(c.status, c.cameraId);

                    if (c.unavailablePhysicalCameras != null) {
                        for (String unavailPhysicalCamera : c.unavailablePhysicalCameras) {
                            onPhysicalCameraStatusChangedLocked(
                                    ICameraServiceListener.STATUS_NOT_PRESENT,
                                    c.cameraId, unavailPhysicalCamera);
                        }
                    }

                    if (mHasOpenCloseListenerPermission &&
                            c.status == ICameraServiceListener.STATUS_NOT_AVAILABLE &&
                            !c.clientPackage.isEmpty()) {
                        onCameraOpenedLocked(c.cameraId, c.clientPackage);
                    }
                }
                mCameraService = cameraService;
            } catch(ServiceSpecificException e) {
                // Unexpected failure
                throw new IllegalStateException("Failed to register a camera service listener", e);
            } catch (RemoteException e) {
                // Camera service is now down, leave mCameraService as null
            }

            try {
                ConcurrentCameraIdCombination[] cameraIdCombinations =
                        cameraService.getConcurrentCameraIds();
                for (ConcurrentCameraIdCombination comb : cameraIdCombinations) {
                    mConcurrentCameraIdCombinations.add(comb.getConcurrentCameraIdCombination());
                }
            } catch (ServiceSpecificException e) {
                // Unexpected failure
                throw new IllegalStateException("Failed to get concurrent camera id combinations",
                        e);
            } catch (RemoteException e) {
                // Camera service died in all probability
            }

            if (mActiveCameraIdRemapping != null) {
                try {
                    cameraService.remapCameraIds(mActiveCameraIdRemapping);
                } catch (ServiceSpecificException e) {
                    // Unexpected failure, ignore and continue.
                    Log.e(TAG, "Unable to remap camera Ids in the camera service");
                } catch (RemoteException e) {
                    // Camera service died in all probability
                }
            }
        }

        /** Updates the cameraIdRemapping state in the CameraService. */
        public void remapCameraIds(@NonNull CameraIdRemapping cameraIdRemapping)
                throws CameraAccessException, SecurityException {
            synchronized (mLock) {
                ICameraService cameraService = getCameraService();
                if (cameraService == null) {
                    throw new CameraAccessException(
                            CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }

                try {
                    cameraService.remapCameraIds(cameraIdRemapping);
                    mActiveCameraIdRemapping = cameraIdRemapping;
                } catch (ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                    throw new CameraAccessException(
                            CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }
            }
        }

        /** Injects session params into an existing client for cameraid. */
        public void injectSessionParams(@NonNull String cameraId,
                @NonNull CaptureRequest sessionParams)
                throws CameraAccessException, SecurityException {
            synchronized (mLock) {
                ICameraService cameraService = getCameraService();
                if (cameraService == null) {
                    throw new CameraAccessException(
                            CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }

                try {
                    cameraService.injectSessionParams(cameraId, sessionParams.getNativeMetadata());
                } catch (ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                    throw new CameraAccessException(
                            CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }
            }
        }

        private String[] extractCameraIdListLocked() {
            String[] cameraIds = null;
            int idCount = 0;
            for (int i = 0; i < mDeviceStatus.size(); i++) {
                int status = mDeviceStatus.valueAt(i);
                if (status == ICameraServiceListener.STATUS_NOT_PRESENT
                        || status == ICameraServiceListener.STATUS_ENUMERATING) continue;
                idCount++;
            }
            cameraIds = new String[idCount];
            idCount = 0;
            for (int i = 0; i < mDeviceStatus.size(); i++) {
                int status = mDeviceStatus.valueAt(i);
                if (status == ICameraServiceListener.STATUS_NOT_PRESENT
                        || status == ICameraServiceListener.STATUS_ENUMERATING) continue;
                cameraIds[idCount] = mDeviceStatus.keyAt(i);
                idCount++;
            }
            return cameraIds;
        }

        private Set<Set<String>> extractConcurrentCameraIdListLocked() {
            Set<Set<String>> concurrentCameraIds = new ArraySet<Set<String>>();
            for (Set<String> cameraIds : mConcurrentCameraIdCombinations) {
                Set<String> extractedCameraIds = new ArraySet<String>();
                for (String cameraId : cameraIds) {
                    // if the camera id status is NOT_PRESENT or ENUMERATING; skip the device.
                    // TODO: Would a device status NOT_PRESENT ever be in the map ? it gets removed
                    // in the callback anyway.
                    Integer status = mDeviceStatus.get(cameraId);
                    if (status == null) {
                        // camera id not present
                        continue;
                    }
                    if (status == ICameraServiceListener.STATUS_ENUMERATING
                            || status == ICameraServiceListener.STATUS_NOT_PRESENT) {
                        continue;
                    }
                    extractedCameraIds.add(cameraId);
                }
                concurrentCameraIds.add(extractedCameraIds);
            }
            return concurrentCameraIds;
        }

        private static void sortCameraIds(String[] cameraIds) {
            // The sort logic must match the logic in
            // libcameraservice/common/CameraProviderManager.cpp::getAPI1CompatibleCameraDeviceIds
            Arrays.sort(cameraIds, new Comparator<String>() {
                    @Override
                    public int compare(String s1, String s2) {
                        int s1Int = 0, s2Int = 0;
                        try {
                            s1Int = Integer.parseInt(s1);
                        } catch (NumberFormatException e) {
                            s1Int = -1;
                        }

                        try {
                            s2Int = Integer.parseInt(s2);
                        } catch (NumberFormatException e) {
                            s2Int = -1;
                        }

                        // Uint device IDs first
                        if (s1Int >= 0 && s2Int >= 0) {
                            return s1Int - s2Int;
                        } else if (s1Int >= 0) {
                            return -1;
                        } else if (s2Int >= 0) {
                            return 1;
                        } else {
                            // Simple string compare if both id are not uint
                            return s1.compareTo(s2);
                        }
                    }});

        }

        public static boolean cameraStatusesContains(CameraStatus[] cameraStatuses, String id) {
            for (CameraStatus c : cameraStatuses) {
                if (c.cameraId.equals(id)) {
                    return true;
                }
            }
            return false;
        }

        public String[] getCameraIdListNoLazy() {
            if (sCameraServiceDisabled) {
                return new String[] {};
            }

            CameraStatus[] cameraStatuses;
            ICameraServiceListener.Stub testListener = new ICameraServiceListener.Stub() {
                @Override
                public void onStatusChanged(int status, String id) throws RemoteException {
                }
                @Override
                public void onPhysicalCameraStatusChanged(int status,
                        String id, String physicalId) throws RemoteException {
                }
                @Override
                public void onTorchStatusChanged(int status, String id) throws RemoteException {
                }
                @Override
                public void onTorchStrengthLevelChanged(String id, int newStrengthLevel)
                        throws RemoteException {
                }
                @Override
                public void onCameraAccessPrioritiesChanged() {
                }
                @Override
                public void onCameraOpened(String id, String clientPackageId) {
                }
                @Override
                public void onCameraClosed(String id) {
                }};

            String[] cameraIds = null;
            synchronized (mLock) {
                connectCameraServiceLocked();
                try {
                    // The purpose of the addListener, removeListener pair here is to get a fresh
                    // list of camera ids from cameraserver. We do this since for in test processes,
                    // changes can happen w.r.t non-changeable permissions (eg: SYSTEM_CAMERA
                    // permissions can be effectively changed by calling
                    // adopt(drop)ShellPermissionIdentity()).
                    // Camera devices, which have their discovery affected by these permission
                    // changes, will not have clients get callbacks informing them about these
                    // devices going offline (in real world scenarios, these permissions aren't
                    // changeable). Future calls to getCameraIdList() will reflect the changes in
                    // the camera id list after getCameraIdListNoLazy() is called.
                    // We need to remove the torch ids which may have been associated with the
                    // devices removed as well. This is the same situation.
                    cameraStatuses = mCameraService.addListener(testListener);
                    mCameraService.removeListener(testListener);
                    for (CameraStatus c : cameraStatuses) {
                        onStatusChangedLocked(c.status, c.cameraId);
                    }
                    Set<String> deviceCameraIds = mDeviceStatus.keySet();
                    ArrayList<String> deviceIdsToRemove = new ArrayList<String>();
                    for (String deviceCameraId : deviceCameraIds) {
                        // Its possible that a device id was removed without a callback notifying
                        // us. This may happen in case a process 'drops' system camera permissions
                        // (even though the permission isn't a changeable one, tests may call
                        // adoptShellPermissionIdentity() and then dropShellPermissionIdentity().
                        if (!cameraStatusesContains(cameraStatuses, deviceCameraId)) {
                            deviceIdsToRemove.add(deviceCameraId);
                        }
                    }
                    for (String id : deviceIdsToRemove) {
                        onStatusChangedLocked(ICameraServiceListener.STATUS_NOT_PRESENT, id);
                        mTorchStatus.remove(id);
                    }
                } catch (ServiceSpecificException e) {
                    // Unexpected failure
                    throw new IllegalStateException("Failed to register a camera service listener",
                            e);
                } catch (RemoteException e) {
                    // Camera service is now down, leave mCameraService as null
                }
                cameraIds = extractCameraIdListLocked();
            }
            sortCameraIds(cameraIds);
            return cameraIds;
        }

        /**
         * Get a list of all camera IDs that are at least PRESENT; ignore devices that are
         * NOT_PRESENT or ENUMERATING, since they cannot be used by anyone.
         */
        public String[] getCameraIdList() {
            String[] cameraIds = null;
            synchronized (mLock) {
                // Try to make sure we have an up-to-date list of camera devices.
                connectCameraServiceLocked();
                cameraIds = extractCameraIdListLocked();
            }
            sortCameraIds(cameraIds);
            return cameraIds;
        }

        public @NonNull Set<Set<String>> getConcurrentCameraIds() {
            Set<Set<String>> concurrentStreamingCameraIds = null;
            synchronized (mLock) {
                // Try to make sure we have an up-to-date list of concurrent camera devices.
                connectCameraServiceLocked();
                concurrentStreamingCameraIds = extractConcurrentCameraIdListLocked();
            }
            // TODO: Some sort of sorting  ?
            return concurrentStreamingCameraIds;
        }

        public boolean isConcurrentSessionConfigurationSupported(
                @NonNull Map<String, SessionConfiguration> cameraIdsAndSessionConfigurations,
                int targetSdkVersion) throws CameraAccessException {

            if (cameraIdsAndSessionConfigurations == null) {
                throw new IllegalArgumentException("cameraIdsAndSessionConfigurations was null");
            }

            int size = cameraIdsAndSessionConfigurations.size();
            if (size == 0) {
                throw new IllegalArgumentException("camera id and session combination is empty");
            }

            synchronized (mLock) {
                // Go through all the elements and check if the camera ids are valid at least /
                // belong to one of the combinations returned by getConcurrentCameraIds()
                boolean subsetFound = false;
                for (Set<String> combination : mConcurrentCameraIdCombinations) {
                    if (combination.containsAll(cameraIdsAndSessionConfigurations.keySet())) {
                        subsetFound = true;
                    }
                }
                if (!subsetFound) {
                    Log.v(TAG, "isConcurrentSessionConfigurationSupported called with a subset of"
                            + "camera ids not returned by getConcurrentCameraIds");
                    return false;
                }
                CameraIdAndSessionConfiguration [] cameraIdsAndConfigs =
                        new CameraIdAndSessionConfiguration[size];
                int i = 0;
                for (Map.Entry<String, SessionConfiguration> pair :
                        cameraIdsAndSessionConfigurations.entrySet()) {
                    cameraIdsAndConfigs[i] =
                            new CameraIdAndSessionConfiguration(pair.getKey(), pair.getValue());
                    i++;
                }
                try {
                    return mCameraService.isConcurrentSessionConfigurationSupported(
                            cameraIdsAndConfigs, targetSdkVersion);
                } catch (ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                  // Camera service died - act as if the camera was disconnected
                  throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                          "Camera service is currently unavailable", e);
                }
            }
        }

      /**
        * Helper function to find out if a camera id is in the set of combinations returned by
        * getConcurrentCameraIds()
        * @param cameraId the unique identifier of the camera device to query
        * @return Whether the camera device was found in the set of combinations returned by
        *         getConcurrentCameraIds
        */
        public boolean cameraIdHasConcurrentStreamsLocked(String cameraId) {
            if (!mDeviceStatus.containsKey(cameraId)) {
                // physical camera ids aren't advertised in concurrent camera id combinations.
                if (DEBUG) {
                    Log.v(TAG, " physical camera id " + cameraId + " is hidden." +
                            " Available logical camera ids : " + mDeviceStatus.toString());
                }
                return false;
            }
            for (Set<String> comb : mConcurrentCameraIdCombinations) {
                if (comb.contains(cameraId)) {
                    return true;
                }
            }
            return false;
        }

        public void setTorchMode(String cameraId, boolean enabled) throws CameraAccessException {
            synchronized(mLock) {

                if (cameraId == null) {
                    throw new IllegalArgumentException("cameraId was null");
                }

                ICameraService cameraService = getCameraService();
                if (cameraService == null) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable");
                }

                try {
                    cameraService.setTorchMode(cameraId, enabled, mTorchClientBinder);
                } catch(ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable");
                }
            }
        }

        public void turnOnTorchWithStrengthLevel(String cameraId, int torchStrength) throws
                CameraAccessException {
            synchronized(mLock) {

                if (cameraId == null) {
                    throw new IllegalArgumentException("cameraId was null");
                }

                ICameraService cameraService = getCameraService();
                if (cameraService == null) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable.");
                }

                try {
                    cameraService.turnOnTorchWithStrengthLevel(cameraId, torchStrength,
                            mTorchClientBinder);
                } catch(ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }
            }
        }

        public int getTorchStrengthLevel(String cameraId) throws CameraAccessException {
            int torchStrength = 0;
            synchronized(mLock) {
                if (cameraId == null) {
                    throw new IllegalArgumentException("cameraId was null");
                }

                ICameraService cameraService = getCameraService();
                if (cameraService == null) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable.");
                }

                try {
                    torchStrength = cameraService.getTorchStrengthLevel(cameraId);
                } catch(ServiceSpecificException e) {
                    throw ExceptionUtils.throwAsPublicException(e);
                } catch (RemoteException e) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera service is currently unavailable.");
                }
            }
            return torchStrength;
        }

        private void handleRecoverableSetupErrors(ServiceSpecificException e) {
            switch (e.errorCode) {
                case ICameraService.ERROR_DISCONNECTED:
                    Log.w(TAG, e.getMessage());
                    break;
                default:
                    throw new IllegalStateException(e);
            }
        }

        private boolean isAvailable(int status) {
            switch (status) {
                case ICameraServiceListener.STATUS_PRESENT:
                    return true;
                default:
                    return false;
            }
        }

        private boolean validStatus(int status) {
            switch (status) {
                case ICameraServiceListener.STATUS_NOT_PRESENT:
                case ICameraServiceListener.STATUS_PRESENT:
                case ICameraServiceListener.STATUS_ENUMERATING:
                case ICameraServiceListener.STATUS_NOT_AVAILABLE:
                    return true;
                default:
                    return false;
            }
        }

        private boolean validTorchStatus(int status) {
            switch (status) {
                case ICameraServiceListener.TORCH_STATUS_NOT_AVAILABLE:
                case ICameraServiceListener.TORCH_STATUS_AVAILABLE_ON:
                case ICameraServiceListener.TORCH_STATUS_AVAILABLE_OFF:
                    return true;
                default:
                    return false;
            }
        }

        private void postSingleAccessPriorityChangeUpdate(final AvailabilityCallback callback,
                final Executor executor) {
            final long ident = Binder.clearCallingIdentity();
            try {
                executor.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            callback.onCameraAccessPrioritiesChanged();
                        }
                    });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void postSingleCameraOpenedUpdate(final AvailabilityCallback callback,
                final Executor executor, final String id, final String packageId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                executor.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            callback.onCameraOpened(id, packageId);
                        }
                    });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void postSingleCameraClosedUpdate(final AvailabilityCallback callback,
                final Executor executor, final String id) {
            final long ident = Binder.clearCallingIdentity();
            try {
                executor.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            callback.onCameraClosed(id);
                        }
                    });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void postSingleUpdate(final AvailabilityCallback callback, final Executor executor,
                final String id, final String physicalId, final int status) {
            if (isAvailable(status)) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    executor.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (physicalId == null) {
                                    callback.onCameraAvailable(id);
                                } else {
                                    callback.onPhysicalCameraAvailable(id, physicalId);
                                }
                            }
                        });
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                final long ident = Binder.clearCallingIdentity();
                try {
                    executor.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (physicalId == null) {
                                    callback.onCameraUnavailable(id);
                                } else {
                                    callback.onPhysicalCameraUnavailable(id, physicalId);
                                }
                            }
                        });
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        private void postSingleTorchUpdate(final TorchCallback callback, final Executor executor,
                final String id, final int status) {
            switch(status) {
                case ICameraServiceListener.TORCH_STATUS_AVAILABLE_ON:
                case ICameraServiceListener.TORCH_STATUS_AVAILABLE_OFF: {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            executor.execute(() -> {
                                callback.onTorchModeChanged(id, status ==
                                        ICameraServiceListener.TORCH_STATUS_AVAILABLE_ON);
                            });
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                    break;
                default: {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            executor.execute(() -> {
                                callback.onTorchModeUnavailable(id);
                            });
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                    break;
            }
        }

        private void postSingleTorchStrengthLevelUpdate(final TorchCallback callback,
                 final Executor executor, final String id, final int newStrengthLevel) {
            final long ident = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> {
                    callback.onTorchStrengthLevelChanged(id, newStrengthLevel);
                });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Send the state of all known cameras to the provided listener, to initialize
         * the listener's knowledge of camera state.
         */
        private void updateCallbackLocked(AvailabilityCallback callback, Executor executor) {
            for (int i = 0; i < mDeviceStatus.size(); i++) {
                String id = mDeviceStatus.keyAt(i);
                Integer status = mDeviceStatus.valueAt(i);
                postSingleUpdate(callback, executor, id, null /*physicalId*/, status);

                // Send the NOT_PRESENT state for unavailable physical cameras
                if ((isAvailable(status) || physicalCallbacksAreEnabledForUnavailableCamera())
                        && mUnavailablePhysicalDevices.containsKey(id)) {
                    ArrayList<String> unavailableIds = mUnavailablePhysicalDevices.get(id);
                    for (String unavailableId : unavailableIds) {
                        postSingleUpdate(callback, executor, id, unavailableId,
                                ICameraServiceListener.STATUS_NOT_PRESENT);
                    }
                }

            }
            for (int i = 0; i < mOpenedDevices.size(); i++) {
                String id = mOpenedDevices.keyAt(i);
                String clientPackageId = mOpenedDevices.valueAt(i);
                postSingleCameraOpenedUpdate(callback, executor, id, clientPackageId);
            }
        }

        private void onStatusChangedLocked(int status, String id) {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("Camera id %s has status changed to 0x%x", id, status));
            }

            if (!validStatus(status)) {
                Log.e(TAG, String.format("Ignoring invalid device %s status 0x%x", id,
                                status));
                return;
            }

            Integer oldStatus;
            if (status == ICameraServiceListener.STATUS_NOT_PRESENT) {
                oldStatus = mDeviceStatus.remove(id);
                mUnavailablePhysicalDevices.remove(id);
            } else {
                oldStatus = mDeviceStatus.put(id, status);
                if (oldStatus == null) {
                    mUnavailablePhysicalDevices.put(id, new ArrayList<String>());
                }
            }

            if (oldStatus != null && oldStatus == status) {
                if (DEBUG) {
                    Log.v(TAG, String.format(
                        "Device status changed to 0x%x, which is what it already was",
                        status));
                }
                return;
            }

            // TODO: consider abstracting out this state minimization + transition
            // into a separate
            // more easily testable class
            // i.e. (new State()).addState(STATE_AVAILABLE)
            //                   .addState(STATE_NOT_AVAILABLE)
            //                   .addTransition(STATUS_PRESENT, STATE_AVAILABLE),
            //                   .addTransition(STATUS_NOT_PRESENT, STATE_NOT_AVAILABLE)
            //                   .addTransition(STATUS_ENUMERATING, STATE_NOT_AVAILABLE);
            //                   .addTransition(STATUS_NOT_AVAILABLE, STATE_NOT_AVAILABLE);

            // Translate all the statuses to either 'available' or 'not available'
            //  available -> available         => no new update
            //  not available -> not available => no new update
            if (oldStatus != null && isAvailable(status) == isAvailable(oldStatus)) {
                if (DEBUG) {
                    Log.v(TAG,
                            String.format(
                                "Device status was previously available (%b), " +
                                " and is now again available (%b)" +
                                "so no new client visible update will be sent",
                                isAvailable(oldStatus), isAvailable(status)));
                }
                return;
            }

            final int callbackCount = mCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                Executor executor = mCallbackMap.valueAt(i);
                final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                postSingleUpdate(callback, executor, id, null /*physicalId*/, status);

                // Send the NOT_PRESENT state for unavailable physical cameras
                if (isAvailable(status) && mUnavailablePhysicalDevices.containsKey(id)) {
                    ArrayList<String> unavailableIds = mUnavailablePhysicalDevices.get(id);
                    for (String unavailableId : unavailableIds) {
                        postSingleUpdate(callback, executor, id, unavailableId,
                                ICameraServiceListener.STATUS_NOT_PRESENT);
                    }
                }
            }
        } // onStatusChangedLocked

        private void onPhysicalCameraStatusChangedLocked(int status,
                String id, String physicalId) {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("Camera id %s physical camera id %s has status "
                        + "changed to 0x%x", id, physicalId, status));
            }

            if (!validStatus(status)) {
                Log.e(TAG, String.format(
                        "Ignoring invalid device %s physical device %s status 0x%x", id,
                        physicalId, status));
                return;
            }

            //TODO: Do we need to treat this as error?
            if (!mDeviceStatus.containsKey(id) || !mUnavailablePhysicalDevices.containsKey(id)) {
                Log.e(TAG, String.format("Camera %s is not present. Ignore physical camera "
                        + "status change", id));
                return;
            }

            ArrayList<String> unavailablePhysicalDevices = mUnavailablePhysicalDevices.get(id);
            if (!isAvailable(status)
                    && !unavailablePhysicalDevices.contains(physicalId)) {
                unavailablePhysicalDevices.add(physicalId);
            } else if (isAvailable(status)
                    && unavailablePhysicalDevices.contains(physicalId)) {
                unavailablePhysicalDevices.remove(physicalId);
            } else {
                if (DEBUG) {
                    Log.v(TAG,
                            String.format(
                                "Physical camera device status was previously available (%b), "
                                + " and is now again available (%b)"
                                + "so no new client visible update will be sent",
                                !unavailablePhysicalDevices.contains(physicalId),
                                isAvailable(status)));
                }
                return;
            }

            if (!physicalCallbacksAreEnabledForUnavailableCamera()
                    && !isAvailable(mDeviceStatus.get(id))) {
                Log.i(TAG, String.format("Camera %s is not available. Ignore physical camera "
                        + "status change callback(s)", id));
                return;
            }

            final int callbackCount = mCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                Executor executor = mCallbackMap.valueAt(i);
                final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                postSingleUpdate(callback, executor, id, physicalId, status);
            }
        } // onPhysicalCameraStatusChangedLocked

        private void updateTorchCallbackLocked(TorchCallback callback, Executor executor) {
            for (int i = 0; i < mTorchStatus.size(); i++) {
                String id = mTorchStatus.keyAt(i);
                Integer status = mTorchStatus.valueAt(i);
                postSingleTorchUpdate(callback, executor, id, status);
            }
        }

        private void onTorchStatusChangedLocked(int status, String id) {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("Camera id %s has torch status changed to 0x%x", id, status));
            }

            if (!validTorchStatus(status)) {
                Log.e(TAG, String.format("Ignoring invalid device %s torch status 0x%x", id,
                                status));
                return;
            }

            Integer oldStatus = mTorchStatus.put(id, status);
            if (oldStatus != null && oldStatus == status) {
                if (DEBUG) {
                    Log.v(TAG, String.format(
                        "Torch status changed to 0x%x, which is what it already was",
                        status));
                }
                return;
            }

            final int callbackCount = mTorchCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                final Executor executor = mTorchCallbackMap.valueAt(i);
                final TorchCallback callback = mTorchCallbackMap.keyAt(i);
                postSingleTorchUpdate(callback, executor, id, status);
            }
        } // onTorchStatusChangedLocked

        private void onTorchStrengthLevelChangedLocked(String cameraId, int newStrengthLevel) {
            if (DEBUG) {

                Log.v(TAG,
                        String.format("Camera id %s has torch strength level changed to %d",
                            cameraId, newStrengthLevel));
            }

            final int callbackCount = mTorchCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                final Executor executor = mTorchCallbackMap.valueAt(i);
                final TorchCallback callback = mTorchCallbackMap.keyAt(i);
                postSingleTorchStrengthLevelUpdate(callback, executor, cameraId, newStrengthLevel);
            }
        } // onTorchStrengthLevelChanged

        /**
         * Register a callback to be notified about camera device availability with the
         * global listener singleton.
         *
         * @param callback the new callback to send camera availability notices to
         * @param executor The executor which should invoke the callback. May not be null.
         * @param hasOpenCloseListenerPermission whether the client has permission for
         *                                       onCameraOpened/onCameraClosed callback
         */
        public void registerAvailabilityCallback(AvailabilityCallback callback, Executor executor,
                boolean hasOpenCloseListenerPermission) {
            synchronized (mLock) {
                // In practice, this permission doesn't change. So we don't need one flag for each
                // callback object.
                mHasOpenCloseListenerPermission = hasOpenCloseListenerPermission;
                connectCameraServiceLocked();

                Executor oldExecutor = mCallbackMap.put(callback, executor);
                // For new callbacks, provide initial availability information
                if (oldExecutor == null) {
                    updateCallbackLocked(callback, executor);
                }

                // If not connected to camera service, schedule a reconnect to camera service.
                if (mCameraService == null) {
                    scheduleCameraServiceReconnectionLocked();
                }
            }
        }

        /**
         * Remove a previously-added callback; the callback will no longer receive connection and
         * disconnection callbacks, and is no longer referenced by the global listener singleton.
         *
         * @param callback The callback to remove from the notification list
         */
        public void unregisterAvailabilityCallback(AvailabilityCallback callback) {
            synchronized (mLock) {
                mCallbackMap.remove(callback);
            }
        }

        public void registerTorchCallback(TorchCallback callback, Executor executor) {
            synchronized(mLock) {
                connectCameraServiceLocked();

                Executor oldExecutor = mTorchCallbackMap.put(callback, executor);
                // For new callbacks, provide initial torch information
                if (oldExecutor == null) {
                    updateTorchCallbackLocked(callback, executor);
                }

                // If not connected to camera service, schedule a reconnect to camera service.
                if (mCameraService == null) {
                    scheduleCameraServiceReconnectionLocked();
                }
            }
        }

        public void unregisterTorchCallback(TorchCallback callback) {
            synchronized(mLock) {
                mTorchCallbackMap.remove(callback);
            }
        }

        /**
         * Callback from camera service notifying the process about camera availability changes
         */
        @Override
        public void onStatusChanged(int status, String cameraId) throws RemoteException {
            synchronized(mLock) {
                onStatusChangedLocked(status, cameraId);
            }
        }

        @Override
        public void onPhysicalCameraStatusChanged(int status, String cameraId,
                String physicalCameraId) throws RemoteException {
            synchronized (mLock) {
                onPhysicalCameraStatusChangedLocked(status, cameraId, physicalCameraId);
            }
        }

        @Override
        public void onTorchStatusChanged(int status, String cameraId) throws RemoteException {
            synchronized (mLock) {
                onTorchStatusChangedLocked(status, cameraId);
            }
        }

        @Override
        public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel)
                throws RemoteException {
            synchronized (mLock) {
                onTorchStrengthLevelChangedLocked(cameraId, newStrengthLevel);
            }
        }

        @Override
        public void onCameraAccessPrioritiesChanged() {
            synchronized (mLock) {
                final int callbackCount = mCallbackMap.size();
                for (int i = 0; i < callbackCount; i++) {
                    Executor executor = mCallbackMap.valueAt(i);
                    final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                    postSingleAccessPriorityChangeUpdate(callback, executor);
                }
            }
        }

        @Override
        public void onCameraOpened(String cameraId, String clientPackageId) {
            synchronized (mLock) {
                onCameraOpenedLocked(cameraId, clientPackageId);
            }
        }

        private void onCameraOpenedLocked(String cameraId, String clientPackageId) {
            String oldApk = mOpenedDevices.put(cameraId, clientPackageId);

            if (oldApk != null) {
                if (oldApk.equals(clientPackageId)) {
                    Log.w(TAG,
                            "onCameraOpened was previously called for " + oldApk
                            + " and is now again called for the same package name, "
                            + "so no new client visible update will be sent");
                    return;
                } else {
                    Log.w(TAG,
                            "onCameraOpened was previously called for " + oldApk
                            + " and is now called for " + clientPackageId
                            + " without onCameraClosed being called first");
                }
            }

            final int callbackCount = mCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                Executor executor = mCallbackMap.valueAt(i);
                final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                postSingleCameraOpenedUpdate(callback, executor, cameraId, clientPackageId);
            }
        }

        @Override
        public void onCameraClosed(String cameraId) {
            synchronized (mLock) {
                onCameraClosedLocked(cameraId);
            }
        }

        private void onCameraClosedLocked(String cameraId) {
            mOpenedDevices.remove(cameraId);

            final int callbackCount = mCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                Executor executor = mCallbackMap.valueAt(i);
                final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                postSingleCameraClosedUpdate(callback, executor, cameraId);
            }
        }

        /**
         * Try to connect to camera service after some delay if any client registered camera
         * availability callback or torch status callback.
         */
        private void scheduleCameraServiceReconnectionLocked() {
            if (mCallbackMap.isEmpty() && mTorchCallbackMap.isEmpty()) {
                // Not necessary to reconnect camera service if no client registers a callback.
                return;
            }

            if (DEBUG) {
                Log.v(TAG, "Reconnecting Camera Service in " + CAMERA_SERVICE_RECONNECT_DELAY_MS +
                        " ms");
            }

            try {
                mScheduler.schedule(() -> {
                    ICameraService cameraService = getCameraService();
                    if (cameraService == null) {
                        synchronized(mLock) {
                            if (DEBUG) {
                                Log.v(TAG, "Reconnecting Camera Service failed.");
                            }
                            scheduleCameraServiceReconnectionLocked();
                        }
                    }
                }, CAMERA_SERVICE_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Failed to schedule camera service re-connect: " + e);
            }
        }

        /**
         * Listener for camera service death.
         *
         * <p>The camera service isn't supposed to die under any normal circumstances, but can be
         * turned off during debug, or crash due to bugs.  So detect that and null out the interface
         * object, so that the next calls to the manager can try to reconnect.</p>
         */
        public void binderDied() {
            synchronized(mLock) {
                // Only do this once per service death
                if (mCameraService == null) return;

                mCameraService = null;

                // Tell listeners that the cameras and torch modes are unavailable and schedule a
                // reconnection to camera service. When camera service is reconnected, the camera
                // and torch statuses will be updated.
                // Iterate from the end to the beginning because onStatusChangedLocked removes
                // entries from the ArrayMap.
                for (int i = mDeviceStatus.size() - 1; i >= 0; i--) {
                    String cameraId = mDeviceStatus.keyAt(i);
                    onStatusChangedLocked(ICameraServiceListener.STATUS_NOT_PRESENT, cameraId);

                    if (mHasOpenCloseListenerPermission) {
                        onCameraClosedLocked(cameraId);
                    }
                }
                for (int i = 0; i < mTorchStatus.size(); i++) {
                    String cameraId = mTorchStatus.keyAt(i);
                    onTorchStatusChangedLocked(ICameraServiceListener.TORCH_STATUS_NOT_AVAILABLE,
                            cameraId);
                }

                mConcurrentCameraIdCombinations.clear();

                scheduleCameraServiceReconnectionLocked();
            }
        }

    } // CameraManagerGlobal

} // CameraManager
