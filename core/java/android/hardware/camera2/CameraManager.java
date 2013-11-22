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
import android.hardware.IProCameraUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.CameraBinderDecorator;
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
 * <p>An interface for iterating, listing, and connecting to
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

    /**
     * This should match the ICameraService definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";
    private static final int USE_CALLING_UID = -1;

    private final ICameraService mCameraService;
    private ArrayList<String> mDeviceIdList;

    private final ArrayMap<AvailabilityListener, Handler> mListenerMap =
            new ArrayMap<AvailabilityListener, Handler>();

    private final Context mContext;
    private final Object mLock = new Object();

    /**
     * @hide
     */
    public CameraManager(Context context) {
        mContext = context;

        IBinder cameraServiceBinder = ServiceManager.getService(CAMERA_SERVICE_BINDER_NAME);
        ICameraService cameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);

        /**
         * Wrap the camera service in a decorator which automatically translates return codes
         * into exceptions, and RemoteExceptions into other exceptions.
         */
        mCameraService = CameraBinderDecorator.newInstance(cameraServiceRaw);

        try {
            mCameraService.addListener(new CameraServiceListener());
        } catch(CameraRuntimeException e) {
            throw new IllegalStateException("Failed to register a camera service listener",
                    e.asChecked());
        } catch (RemoteException e) {
            // impossible
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
            try {
                return getOrCreateDeviceIdListLocked().toArray(new String[0]);
            } catch(CameraAccessException e) {
                // this should almost never happen, except if mediaserver crashes
                throw new IllegalStateException(
                        "Failed to query camera service for device ID list", e);
            }
        }
    }

    /**
     * Register a listener to be notified about camera device availability.
     *
     * <p>Registering the same listener again will replace the handler with the
     * new one provided.</p>
     *
     * @param listener The new listener to send camera availability notices to
     * @param handler The handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper looper}.
     */
    public void addAvailabilityListener(AvailabilityListener listener, Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                        "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }

        synchronized (mLock) {
            mListenerMap.put(listener, handler);
        }
    }

    /**
     * Remove a previously-added listener; the listener will no longer receive
     * connection and disconnection callbacks.
     *
     * <p>Removing a listener that isn't registered has no effect.</p>
     *
     * @param listener The listener to remove from the notification list
     */
    public void removeAvailabilityListener(AvailabilityListener listener) {
        synchronized (mLock) {
            mListenerMap.remove(listener);
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
     * currently connected camera device.
     * @throws CameraAccessException if the camera is disabled by device policy.
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public CameraCharacteristics getCameraCharacteristics(String cameraId)
            throws CameraAccessException {

        synchronized (mLock) {
            if (!getOrCreateDeviceIdListLocked().contains(cameraId)) {
                throw new IllegalArgumentException(String.format("Camera id %s does not match any" +
                        " currently connected camera device", cameraId));
            }
        }

        CameraMetadataNative info = new CameraMetadataNative();
        try {
            mCameraService.getCameraCharacteristics(Integer.valueOf(cameraId), info);
        } catch(CameraRuntimeException e) {
            throw e.asChecked();
        } catch(RemoteException e) {
            // impossible
            return null;
        }

        return new CameraCharacteristics(info);
    }

    /**
     * Open a connection to a camera with the given ID. Use
     * {@link #getCameraIdList} to get the list of available camera
     * devices. Note that even if an id is listed, open may fail if the device
     * is disconnected between the calls to {@link #getCameraIdList} and
     * {@link #openCamera}.
     *
     * @param cameraId The unique identifier of the camera device to open
     * @param listener The listener for the camera. Must not be null.
     * @param handler  The handler to call the listener on. Must not be null.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * or too many camera devices are already open, or the cameraId does not match
     * any currently available camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     * @throws IllegalArgumentException if listener or handler is null.
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    private void openCameraDeviceUserAsync(String cameraId,
            CameraDevice.StateListener listener, Handler handler)
            throws CameraAccessException {
        try {

            synchronized (mLock) {

                ICameraDeviceUser cameraUser;

                android.hardware.camera2.impl.CameraDevice device =
                        new android.hardware.camera2.impl.CameraDevice(
                                cameraId,
                                listener,
                                handler);

                BinderHolder holder = new BinderHolder();
                mCameraService.connectDevice(device.getCallbacks(),
                        Integer.parseInt(cameraId),
                        mContext.getPackageName(), USE_CALLING_UID, holder);
                cameraUser = ICameraDeviceUser.Stub.asInterface(holder.getBinder());

                // TODO: factor out listener to be non-nested, then move setter to constructor
                // For now, calling setRemoteDevice will fire initial
                // onOpened/onUnconfigured callbacks.
                device.setRemoteDevice(cameraUser);
            }

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected cameraId to be numeric, but it was: "
                    + cameraId);
        } catch (CameraRuntimeException e) {
            throw e.asChecked();
        } catch (RemoteException e) {
            // impossible
        }
    }

    /**
     * Open a connection to a camera with the given ID.
     *
     * <p>Use {@link #getCameraIdList} to get the list of available camera
     * devices. Note that even if an id is listed, open may fail if the device
     * is disconnected between the calls to {@link #getCameraIdList} and
     * {@link #openCamera}.</p>
     *
     * <p>If the camera successfully opens after this function call returns,
     * {@link CameraDevice.StateListener#onOpened} will be invoked with the
     * newly opened {@link CameraDevice} in the unconfigured state.</p>
     *
     * <p>If the camera becomes disconnected during initialization
     * after this function call returns,
     * {@link CameraDevice.StateListener#onDisconnected} with a
     * {@link CameraDevice} in the disconnected state (and
     * {@link CameraDevice.StateListener#onOpened} will be skipped).</p>
     *
     * <p>If the camera fails to initialize after this function call returns,
     * {@link CameraDevice.StateListener#onError} will be invoked with a
     * {@link CameraDevice} in the error state (and
     * {@link CameraDevice.StateListener#onOpened} will be skipped).</p>
     *
     * @param cameraId
     *             The unique identifier of the camera device to open
     * @param listener
     *             The listener which is invoked once the camera is opened
     * @param handler
     *             The handler on which the listener should be invoked, or
     *             {@code null} to use the current thread's {@link android.os.Looper looper}.
     *
     * @throws CameraAccessException if the camera is disabled by device policy,
     * or the camera has become or was disconnected.
     *
     * @throws IllegalArgumentException if cameraId or the listener was null,
     * or the cameraId does not match any currently or previously available
     * camera device.
     *
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getCameraIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public void openCamera(String cameraId, final CameraDevice.StateListener listener,
            Handler handler)
            throws CameraAccessException {

        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener was null");
        } else if (handler == null) {
            if (Looper.myLooper() != null) {
                handler = new Handler();
            } else {
                throw new IllegalArgumentException(
                        "Looper doesn't exist in the calling thread");
            }
        }

        openCameraDeviceUserAsync(cameraId, listener, handler);
    }

    /**
     * Interface for listening to camera devices becoming available or
     * unavailable.
     *
     * <p>Cameras become available when they are no longer in use, or when a new
     * removable camera is connected. They become unavailable when some
     * application or service starts using a camera, or when a removable camera
     * is disconnected.</p>
     *
     * @see addAvailabilityListener
     */
    public static abstract class AvailabilityListener {

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
         * {@link CameraDevice.StateListener#onDisconnected disconnection error}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param cameraId The unique identifier of the disconnected camera.
         */
        public void onCameraUnavailable(String cameraId) {
            // default empty implementation
        }
    }

    private ArrayList<String> getOrCreateDeviceIdListLocked() throws CameraAccessException {
        if (mDeviceIdList == null) {
            int numCameras = 0;

            try {
                numCameras = mCameraService.getNumberOfCameras();
            } catch(CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            mDeviceIdList = new ArrayList<String>();
            CameraMetadataNative info = new CameraMetadataNative();
            for (int i = 0; i < numCameras; ++i) {
                // Non-removable cameras use integers starting at 0 for their
                // identifiers
                boolean isDeviceSupported = false;
                try {
                    mCameraService.getCameraCharacteristics(i, info);
                    if (!info.isEmpty()) {
                        isDeviceSupported = true;
                    } else {
                        throw new AssertionError("Expected to get non-empty characteristics");
                    }
                } catch(IllegalArgumentException  e) {
                    // Got a BAD_VALUE from service, meaning that this
                    // device is not supported.
                } catch(CameraRuntimeException e) {
                    throw e.asChecked();
                } catch(RemoteException e) {
                    // impossible
                }

                if (isDeviceSupported) {
                    mDeviceIdList.add(String.valueOf(i));
                }
            }

        }
        return mDeviceIdList;
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

        @Override
        public void onStatusChanged(int status, int cameraId) throws RemoteException {
            synchronized(CameraManager.this.mLock) {

                Log.v(TAG,
                        String.format("Camera id %d has status changed to 0x%x", cameraId, status));

                final String id = String.valueOf(cameraId);

                if (!validStatus(status)) {
                    Log.e(TAG, String.format("Ignoring invalid device %d status 0x%x", cameraId,
                            status));
                    return;
                }

                Integer oldStatus = mDeviceStatus.put(id, status);

                if (oldStatus != null && oldStatus == status) {
                    Log.v(TAG, String.format(
                            "Device status changed to 0x%x, which is what it already was",
                            status));
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

                    Log.v(TAG,
                            String.format(
                                    "Device status was previously available (%d), " +
                                            " and is now again available (%d)" +
                                            "so no new client visible update will be sent",
                                    isAvailable(status), isAvailable(status)));
                    return;
                }

                final int listenerCount = mListenerMap.size();
                for (int i = 0; i < listenerCount; i++) {
                    Handler handler = mListenerMap.valueAt(i);
                    final AvailabilityListener listener = mListenerMap.keyAt(i);
                    if (isAvailable(status)) {
                        handler.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    listener.onCameraAvailable(id);
                                }
                            });
                    } else {
                        handler.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    listener.onCameraUnavailable(id);
                                }
                            });
                    }
                } // for
            } // synchronized
        } // onStatusChanged
    } // CameraServiceListener
} // CameraManager
