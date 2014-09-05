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

import android.content.Context;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceListener;
import android.hardware.CameraInfo;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.legacy.CameraDeviceUserShim;
import android.hardware.camera2.legacy.LegacyMetadataMapper;
import android.hardware.camera2.utils.CameraServiceBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.hardware.camera2.utils.BinderHolder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.ArrayMap;

import java.util.ArrayList;

/**
 * <p>A system service manager for detecting, characterizing, and connecting to
 * {@link CameraDevice CameraDevices}.</p>
 *
 * <p>You can get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String) Context.getSystemService()}.</p>
 *
 * <pre>CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);</pre>
 *
 * <p>For more details about communicating with camera devices, read the Camera
 * developer guide or the {@link android.hardware.camera2 camera2}
 * package documentation.</p>
 */
public final class CameraManager {

    private static final String TAG = "CameraManager";
    private final boolean DEBUG;

    /**
     * This should match the ICameraService definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";
    private static final int USE_CALLING_UID = -1;

    @SuppressWarnings("unused")
    private static final int API_VERSION_1 = 1;
    private static final int API_VERSION_2 = 2;

    // Access only through getCameraServiceLocked to deal with binder death
    private ICameraService mCameraService;

    private ArrayList<String> mDeviceIdList;

    private final ArrayMap<AvailabilityCallback, Handler> mCallbackMap =
            new ArrayMap<AvailabilityCallback, Handler>();

    private final Context mContext;
    private final Object mLock = new Object();

    private final CameraServiceListener mServiceListener = new CameraServiceListener();

    /**
     * @hide
     */
    public CameraManager(Context context) {
        DEBUG = Log.isLoggable(TAG, Log.DEBUG);
        synchronized(mLock) {
            mContext = context;

            connectCameraServiceLocked();
        }
    }

    /**
     * Return the list of currently connected camera devices by
     * identifier.
     *
     * <p>Non-removable cameras use integers starting at 0 for their
     * identifiers, while removable cameras have a unique identifier for each
     * individual device, even if they are the same model.</p>
     *
     * @return The list of currently connected camera devices.
     */
    public String[] getCameraIdList() throws CameraAccessException {
        synchronized (mLock) {
            // ID list creation handles various known failures in device enumeration, so only
            // exceptions it'll throw are unexpected, and should be propagated upward.
            return getOrCreateDeviceIdListLocked().toArray(new String[0]);
        }
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
     * @param callback the new callback to send camera availability notices to
     * @param handler The handler on which the callback should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper looper}.
     */
    public void registerAvailabilityCallback(AvailabilityCallback callback, Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                        "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }

        synchronized (mLock) {
            Handler oldHandler = mCallbackMap.put(callback, handler);
            // For new callbacks, provide initial availability information
            if (oldHandler == null) {
                mServiceListener.updateCallbackLocked(callback, handler);
            }
        }
    }

    /**
     * Remove a previously-added callback; the callback will no longer receive connection and
     * disconnection callbacks.
     *
     * <p>Removing a callback that isn't registered has no effect.</p>
     *
     * @param callback The callback to remove from the notification list
     */
    public void unregisterAvailabilityCallback(AvailabilityCallback callback) {
        synchronized (mLock) {
            mCallbackMap.remove(callback);
        }
    }

    /**
     * <p>Query the capabilities of a camera device. These capabilities are
     * immutable for a given camera.</p>
     *
     * @param cameraId The id of the camera device to query
     * @return The properties of the given camera
     *
     * @throws IllegalArgumentException if the cameraId does not match any
     *         known camera device.
     * @throws CameraAccessException if the camera is disabled by device policy, or
     *         the camera device has been disconnected.
     * @throws SecurityException if the application does not have permission to
     *         access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public CameraCharacteristics getCameraCharacteristics(String cameraId)
            throws CameraAccessException {
        CameraCharacteristics characteristics = null;

        synchronized (mLock) {
            if (!getOrCreateDeviceIdListLocked().contains(cameraId)) {
                throw new IllegalArgumentException(String.format("Camera id %s does not match any" +
                        " currently connected camera device", cameraId));
            }

            int id = Integer.valueOf(cameraId);

            /*
             * Get the camera characteristics from the camera service directly if it supports it,
             * otherwise get them from the legacy shim instead.
             */

            ICameraService cameraService = getCameraServiceLocked();
            if (cameraService == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable");
            }
            try {
                if (!supportsCamera2ApiLocked(cameraId)) {
                    // Legacy backwards compatibility path; build static info from the camera
                    // parameters
                    String[] outParameters = new String[1];

                    cameraService.getLegacyParameters(id, /*out*/outParameters);
                    String parameters = outParameters[0];

                    CameraInfo info = new CameraInfo();
                    cameraService.getCameraInfo(id, /*out*/info);

                    characteristics = LegacyMetadataMapper.createCharacteristics(parameters, info);
                } else {
                    // Normal path: Get the camera characteristics directly from the camera service
                    CameraMetadataNative info = new CameraMetadataNative();

                    cameraService.getCameraCharacteristics(id, info);

                    characteristics = new CameraCharacteristics(info);
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // Camera service died - act as if the camera was disconnected
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable", e);
            }
        }
        return characteristics;
    }

    /**
     * Helper for openning a connection to a camera with the given ID.
     *
     * @param cameraId The unique identifier of the camera device to open
     * @param callback The callback for the camera. Must not be null.
     * @param handler  The handler to invoke the callback on. Must not be null.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * or too many camera devices are already open, or the cameraId does not match
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
            CameraDevice.StateCallback callback, Handler handler)
            throws CameraAccessException {
        CameraCharacteristics characteristics = getCameraCharacteristics(cameraId);
        CameraDevice device = null;
        try {

            synchronized (mLock) {

                ICameraDeviceUser cameraUser = null;

                android.hardware.camera2.impl.CameraDeviceImpl deviceImpl =
                        new android.hardware.camera2.impl.CameraDeviceImpl(
                                cameraId,
                                callback,
                                handler,
                                characteristics);

                BinderHolder holder = new BinderHolder();

                ICameraDeviceCallbacks callbacks = deviceImpl.getCallbacks();
                int id = Integer.parseInt(cameraId);
                try {
                    if (supportsCamera2ApiLocked(cameraId)) {
                        // Use cameraservice's cameradeviceclient implementation for HAL3.2+ devices
                        ICameraService cameraService = getCameraServiceLocked();
                        if (cameraService == null) {
                            throw new CameraRuntimeException(
                                CameraAccessException.CAMERA_DISCONNECTED,
                                "Camera service is currently unavailable");
                        }
                        cameraService.connectDevice(callbacks, id,
                                mContext.getPackageName(), USE_CALLING_UID, holder);
                        cameraUser = ICameraDeviceUser.Stub.asInterface(holder.getBinder());
                    } else {
                        // Use legacy camera implementation for HAL1 devices
                        Log.i(TAG, "Using legacy camera HAL.");
                        cameraUser = CameraDeviceUserShim.connectBinderShim(callbacks, id);
                    }
                } catch (CameraRuntimeException e) {
                    if (e.getReason() == CameraAccessException.CAMERA_DEPRECATED_HAL) {
                        throw new AssertionError("Should've gone down the shim path");
                    } else if (e.getReason() == CameraAccessException.CAMERA_IN_USE ||
                            e.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE ||
                            e.getReason() == CameraAccessException.CAMERA_DISABLED ||
                            e.getReason() == CameraAccessException.CAMERA_DISCONNECTED ||
                            e.getReason() == CameraAccessException.CAMERA_ERROR) {
                        // Received one of the known connection errors
                        // The remote camera device cannot be connected to, so
                        // set the local camera to the startup error state
                        deviceImpl.setRemoteFailure(e);

                        if (e.getReason() == CameraAccessException.CAMERA_DISABLED ||
                                e.getReason() == CameraAccessException.CAMERA_DISCONNECTED) {
                            // Per API docs, these failures call onError and throw
                            throw e.asChecked();
                        }
                    } else {
                        // Unexpected failure - rethrow
                        throw e;
                    }
                } catch (RemoteException e) {
                    // Camera service died - act as if it's a CAMERA_DISCONNECTED case
                    CameraRuntimeException ce = new CameraRuntimeException(
                        CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable", e);
                    deviceImpl.setRemoteFailure(ce);
                    throw ce.asChecked();
                }

                // TODO: factor out callback to be non-nested, then move setter to constructor
                // For now, calling setRemoteDevice will fire initial
                // onOpened/onUnconfigured callbacks.
                deviceImpl.setRemoteDevice(cameraUser);
                device = deviceImpl;
            }

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected cameraId to be numeric, but it was: "
                    + cameraId);
        } catch (CameraRuntimeException e) {
            throw e.asChecked();
        }
        return device;
    }

    /**
     * Open a connection to a camera with the given ID.
     *
     * <p>Use {@link #getCameraIdList} to get the list of available camera
     * devices. Note that even if an id is listed, open may fail if the device
     * is disconnected between the calls to {@link #getCameraIdList} and
     * {@link #openCamera}.</p>
     *
     * <p>Once the camera is successfully opened, {@link CameraDevice.StateCallback#onOpened} will
     * be invoked with the newly opened {@link CameraDevice}. The camera device can then be set up
     * for operation by calling {@link CameraDevice#createCaptureSession} and
     * {@link CameraDevice#createCaptureRequest}</p>
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
     * or the camera has become or was disconnected.
     *
     * @throws IllegalArgumentException if cameraId or the callback was null,
     * or the cameraId does not match any currently or previously available
     * camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public void openCamera(String cameraId, final CameraDevice.StateCallback callback,
            Handler handler)
            throws CameraAccessException {

        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback was null");
        } else if (handler == null) {
            if (Looper.myLooper() != null) {
                handler = new Handler();
            } else {
                throw new IllegalArgumentException(
                        "Looper doesn't exist in the calling thread");
            }
        }

        openCameraDeviceUserAsync(cameraId, callback, handler);
    }

    /**
     * A callback for camera devices becoming available or
     * unavailable to open.
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
     * @see registerAvailabilityCallback
     */
    public static abstract class AvailabilityCallback {

        /**
         * A new camera has become available to use.
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the new camera.
         */
        public void onCameraAvailable(String cameraId) {
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
        public void onCameraUnavailable(String cameraId) {
            // default empty implementation
        }
    }

    /**
     * Temporary for migrating to Callback naming
     * @hide
     */
    public static abstract class AvailabilityListener extends AvailabilityCallback {
    }

    /**
     * Return or create the list of currently connected camera devices.
     *
     * <p>In case of errors connecting to the camera service, will return an empty list.</p>
     */
    private ArrayList<String> getOrCreateDeviceIdListLocked() throws CameraAccessException {
        if (mDeviceIdList == null) {
            int numCameras = 0;
            ICameraService cameraService = getCameraServiceLocked();
            ArrayList<String> deviceIdList = new ArrayList<>();

            // If no camera service, then no devices
            if (cameraService == null) {
                return deviceIdList;
            }

            try {
                numCameras = cameraService.getNumberOfCameras();
            } catch(CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // camera service just died - if no camera service, then no devices
                return deviceIdList;
            }

            CameraMetadataNative info = new CameraMetadataNative();
            for (int i = 0; i < numCameras; ++i) {
                // Non-removable cameras use integers starting at 0 for their
                // identifiers
                boolean isDeviceSupported = false;
                try {
                    cameraService.getCameraCharacteristics(i, info);
                    if (!info.isEmpty()) {
                        isDeviceSupported = true;
                    } else {
                        throw new AssertionError("Expected to get non-empty characteristics");
                    }
                } catch(IllegalArgumentException  e) {
                    // Got a BAD_VALUE from service, meaning that this
                    // device is not supported.
                } catch(CameraRuntimeException e) {
                    // DISCONNECTED means that the HAL reported an low-level error getting the
                    // device info; skip listing the device.  Other errors,
                    // propagate exception onward
                    if (e.getReason() != CameraAccessException.CAMERA_DISCONNECTED) {
                        throw e.asChecked();
                    }
                } catch(RemoteException e) {
                    // Camera service died - no devices to list
                    deviceIdList.clear();
                    return deviceIdList;
                }

                if (isDeviceSupported) {
                    deviceIdList.add(String.valueOf(i));
                } else {
                    Log.w(TAG, "Error querying camera device " + i + " for listing.");
                }

            }
            mDeviceIdList = deviceIdList;
        }
        return mDeviceIdList;
    }

    private void handleRecoverableSetupErrors(CameraRuntimeException e, String msg) {
        int problem = e.getReason();
        switch (problem) {
            case CameraAccessException.CAMERA_DISCONNECTED:
                String errorMsg = CameraAccessException.getDefaultMessage(problem);
                Log.w(TAG, msg + ": " + errorMsg);
                break;
            default:
                throw new IllegalStateException(msg, e.asChecked());
        }
    }

    /**
     * Queries the camera service if it supports the camera2 api directly, or needs a shim.
     *
     * @param cameraId a non-{@code null} camera identifier
     * @return {@code false} if the legacy shim needs to be used, {@code true} otherwise.
     */
    private boolean supportsCamera2ApiLocked(String cameraId) {
        return supportsCameraApiLocked(cameraId, API_VERSION_2);
    }

    /**
     * Queries the camera service if it supports a camera api directly, or needs a shim.
     *
     * @param cameraId a non-{@code null} camera identifier
     * @param apiVersion the version, i.e. {@code API_VERSION_1} or {@code API_VERSION_2}
     * @return {@code true} if connecting will work for that device version.
     */
    private boolean supportsCameraApiLocked(String cameraId, int apiVersion) {
        int id = Integer.parseInt(cameraId);

        /*
         * Possible return values:
         * - NO_ERROR => CameraX API is supported
         * - CAMERA_DEPRECATED_HAL => CameraX API is *not* supported (thrown as an exception)
         * - Remote exception => If the camera service died
         *
         * Anything else is an unexpected error we don't want to recover from.
         */
        try {
            ICameraService cameraService = getCameraServiceLocked();
            // If no camera service, no support
            if (cameraService == null) return false;

            int res = cameraService.supportsCameraApi(id, apiVersion);

            if (res != CameraServiceBinderDecorator.NO_ERROR) {
                throw new AssertionError("Unexpected value " + res);
            }
            return true;
        } catch (CameraRuntimeException e) {
            if (e.getReason() != CameraAccessException.CAMERA_DEPRECATED_HAL) {
                throw e;
            }
            // API level is not supported
        } catch (RemoteException e) {
            // Camera service is now down, no support for any API level
        }
        return false;
    }

    /**
     * Connect to the camera service if it's available, and set up listeners.
     *
     * <p>Sets mCameraService to a valid pointer or null if the connection does not succeed.</p>
     */
    private void connectCameraServiceLocked() {
        mCameraService = null;
        IBinder cameraServiceBinder = ServiceManager.getService(CAMERA_SERVICE_BINDER_NAME);
        if (cameraServiceBinder == null) {
            // Camera service is now down, leave mCameraService as null
            return;
        }
        try {
            cameraServiceBinder.linkToDeath(new CameraServiceDeathListener(), /*flags*/ 0);
        } catch (RemoteException e) {
            // Camera service is now down, leave mCameraService as null
            return;
        }

        ICameraService cameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);

        /**
         * Wrap the camera service in a decorator which automatically translates return codes
         * into exceptions.
         */
        ICameraService cameraService = CameraServiceBinderDecorator.newInstance(cameraServiceRaw);

        try {
            CameraServiceBinderDecorator.throwOnError(
                    CameraMetadataNative.nativeSetupGlobalVendorTagDescriptor());
        } catch (CameraRuntimeException e) {
            handleRecoverableSetupErrors(e, "Failed to set up vendor tags");
        }

        try {
            cameraService.addListener(mServiceListener);
            mCameraService = cameraService;
        } catch(CameraRuntimeException e) {
            // Unexpected failure
            throw new IllegalStateException("Failed to register a camera service listener",
                    e.asChecked());
        } catch (RemoteException e) {
            // Camera service is now down, leave mCameraService as null
        }
    }

    /**
     * Return a best-effort ICameraService.
     *
     * <p>This will be null if the camera service
     * is not currently available. If the camera service has died since the last
     * use of the camera service, will try to reconnect to the service.</p>
     */
    private ICameraService getCameraServiceLocked() {
        if (mCameraService == null) {
            Log.i(TAG, "getCameraServiceLocked: Reconnecting to camera service");
            connectCameraServiceLocked();
            if (mCameraService == null) {
                Log.e(TAG, "Camera service is unavailable");
            }
        }
        return mCameraService;
    }

    /**
     * Listener for camera service death.
     *
     * <p>The camera service isn't supposed to die under any normal circumstances, but can be turned
     * off during debug, or crash due to bugs.  So detect that and null out the interface object, so
     * that the next calls to the manager can try to reconnect.</p>
     */
    private class CameraServiceDeathListener implements IBinder.DeathRecipient {
        public void binderDied() {
            synchronized(mLock) {
                mCameraService = null;
                // Tell listeners that the cameras are _available_, because any existing clients
                // will have gotten disconnected. This is optimistic under the assumption that the
                // service will be back shortly.
                //
                // Without this, a camera service crash while a camera is open will never signal to
                // listeners that previously in-use cameras are now available.
                for (String cameraId : mDeviceIdList) {
                    mServiceListener.onStatusChangedLocked(CameraServiceListener.STATUS_PRESENT,
                            cameraId);
                }
            }
        }
    }

    // TODO: this class needs unit tests
    // TODO: extract class into top level
    private class CameraServiceListener extends ICameraServiceListener.Stub {

        // Keep up-to-date with ICameraServiceListener.h

        // Device physically unplugged
        public static final int STATUS_NOT_PRESENT = 0;
        // Device physically has been plugged in
        // and the camera can be used exclusively
        public static final int STATUS_PRESENT = 1;
        // Device physically has been plugged in
        // but it will not be connect-able until enumeration is complete
        public static final int STATUS_ENUMERATING = 2;
        // Camera is in use by another app and cannot be used exclusively
        public static final int STATUS_NOT_AVAILABLE = 0x80000000;

        // Camera ID -> Status map
        private final ArrayMap<String, Integer> mDeviceStatus = new ArrayMap<String, Integer>();

        private static final String TAG = "CameraServiceListener";

        @Override
        public IBinder asBinder() {
            return this;
        }

        private boolean isAvailable(int status) {
            switch (status) {
                case STATUS_PRESENT:
                    return true;
                default:
                    return false;
            }
        }

        private boolean validStatus(int status) {
            switch (status) {
                case STATUS_NOT_PRESENT:
                case STATUS_PRESENT:
                case STATUS_ENUMERATING:
                case STATUS_NOT_AVAILABLE:
                    return true;
                default:
                    return false;
            }
        }

        private void postSingleUpdate(final AvailabilityCallback callback, final Handler handler,
                final String id, final int status) {
            if (isAvailable(status)) {
                handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            callback.onCameraAvailable(id);
                        }
                    });
            } else {
                handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            callback.onCameraUnavailable(id);
                        }
                    });
            }
        }

        /**
         * Send the state of all known cameras to the provided listener, to initialize
         * the listener's knowledge of camera state.
         */
        public void updateCallbackLocked(AvailabilityCallback callback, Handler handler) {
            for (int i = 0; i < mDeviceStatus.size(); i++) {
                String id = mDeviceStatus.keyAt(i);
                Integer status = mDeviceStatus.valueAt(i);
                postSingleUpdate(callback, handler, id, status);
            }
        }

        @Override
        public void onStatusChanged(int status, int cameraId) throws RemoteException {
            synchronized(CameraManager.this.mLock) {
                onStatusChangedLocked(status, String.valueOf(cameraId));
            }
        }

        public void onStatusChangedLocked(int status, String id) {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("Camera id %s has status changed to 0x%x", id, status));
            }

            if (!validStatus(status)) {
                Log.e(TAG, String.format("Ignoring invalid device %s status 0x%x", id,
                                status));
                return;
            }

            Integer oldStatus = mDeviceStatus.put(id, status);

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
                                "Device status was previously available (%d), " +
                                " and is now again available (%d)" +
                                "so no new client visible update will be sent",
                                isAvailable(status), isAvailable(status)));
                }
                return;
            }

            final int callbackCount = mCallbackMap.size();
            for (int i = 0; i < callbackCount; i++) {
                Handler handler = mCallbackMap.valueAt(i);
                final AvailabilityCallback callback = mCallbackMap.keyAt(i);

                postSingleUpdate(callback, handler, id, status);
            }
        } // onStatusChangedLocked

    } // CameraServiceListener
} // CameraManager
