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

package android.hardware.photography;

import android.content.Context;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceListener;
import android.hardware.IProCameraUser;
import android.hardware.photography.utils.CameraBinderDecorator;
import android.hardware.photography.utils.CameraRuntimeException;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
 * developer guide or the {@link android.hardware.photography photography}
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
    private HashSet<CameraListener> mListenerSet;
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
     * <p>Return the list of currently connected camera devices by
     * identifier. Non-removable cameras use integers starting at 0 for their
     * identifiers, while removable cameras have a unique identifier for each
     * individual device, even if they are the same model.</p>
     *
     * @return The list of currently connected camera devices.
     */
    public String[] getDeviceIdList() throws CameraAccessException {
        synchronized (mLock) {
            return (String[]) getOrCreateDeviceIdListLocked().toArray();
        }
    }

    /**
     * Register a listener to be notified about camera device availability.
     *
     * Registering a listener more than once has no effect.
     *
     * @param listener the new listener to send camera availability notices to.
     */
    public void registerCameraListener(CameraListener listener) {
        synchronized (mLock) {
            mListenerSet.add(listener);
        }
    }

    /**
     * Remove a previously-added listener; the listener will no longer receive
     * connection and disconnection callbacks.
     *
     * Removing a listener that isn't registered has no effect.
     *
     * @param listener the listener to remove from the notification list
     */
    public void unregisterCameraListener(CameraListener listener) {
        synchronized (mLock) {
            mListenerSet.remove(listener);
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
     * @see #getDeviceIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public CameraProperties getCameraProperties(String cameraId)
            throws CameraAccessException {

        synchronized (mLock) {
            if (!getOrCreateDeviceIdListLocked().contains(cameraId)) {
                throw new IllegalArgumentException(String.format("Camera id %s does not match any" +
                        " currently connected camera device", cameraId));
            }
        }

        // TODO: implement and call a service function to get the capabilities on C++ side

        // TODO: get properties from service
        return new CameraProperties();
    }

    /**
     * Open a connection to a camera with the given ID. Use
     * {@link #getDeviceIdList} to get the list of available camera
     * devices. Note that even if an id is listed, open may fail if the device
     * is disconnected between the calls to {@link #getDeviceIdList} and
     * {@link #openCamera}.
     *
     * @param cameraId The unique identifier of the camera device to open
     *
     * @throws IllegalArgumentException if the cameraId does not match any
     * currently connected camera device.
     * @throws CameraAccessException if the camera is disabled by device policy,
     * or too many camera devices are already open.
     * @throws SecurityException if the application does not have permission to
     * access the camera
     *
     * @see #getDeviceIdList
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled
     */
    public CameraDevice openCamera(String cameraId) throws CameraAccessException {

        try {
            IProCameraUser cameraUser;

            synchronized (mLock) {
                // TODO: Use ICameraDevice or some such instead of this...
                cameraUser = mCameraService.connectPro(null,
                        Integer.parseInt(cameraId),
                        mContext.getPackageName(), USE_CALLING_UID);

            }

            return new CameraDevice(cameraUser.asBinder());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected cameraId to be numeric, but it was: "
                    + cameraId);
        } catch (CameraRuntimeException e) {
            if (e.getReason() == CameraAccessException.CAMERA_DISCONNECTED) {
                throw new IllegalArgumentException("Invalid camera ID specified -- " +
                        "perhaps the camera was physically disconnected", e);
            } else {
                throw e.asChecked();
            }
        } catch (RemoteException e) {
            // impossible
            return null;
        }
    }

    /**
     * Interface for listening to cameras becoming available or unavailable.
     * Cameras become available when they are no longer in use, or when a new
     * removable camera is connected. They become unavailable when some
     * application or service starts using a camera, or when a removable camera
     * is disconnected.
     */
    public interface CameraListener {
        /**
         * A new camera has become available to use.
         *
         * @param cameraId The unique identifier of the new camera.
         */
        public void onCameraAvailable(String cameraId);

        /**
         * A previously-available camera has become unavailable for use. If an
         * application had an active CameraDevice instance for the
         * now-disconnected camera, that application will receive a {@link
         * CameraDevice.ErrorListener#DEVICE_DISCONNECTED disconnection error}.
         *
         * @param cameraId The unique identifier of the disconnected camera.
         */
        public void onCameraUnavailable(String cameraId);
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
            for (int i = 0; i < numCameras; ++i) {
                // Non-removable cameras use integers starting at 0 for their
                // identifiers
                mDeviceIdList.add(String.valueOf(i));
            }

        }
        return mDeviceIdList;
    }

    // TODO: this class needs unit tests
    // TODO: extract class into top level
    private class CameraServiceListener extends Binder implements ICameraServiceListener  {

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
        private final HashMap<String, Integer> mDeviceStatus = new HashMap<String, Integer>();

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
            synchronized(CameraManager.this) {

                Log.v(TAG,
                        String.format("Camera id %d has status changed to 0x%x", cameraId, status));

                String id = String.valueOf(cameraId);

                if (!validStatus(status)) {
                    Log.e(TAG, String.format("Ignoring invalid device %d status 0x%x", cameraId,
                            status));
                    return;
                }

                Integer oldStatus = mDeviceStatus.put(id, status);

                if (oldStatus == status) {
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

                for (CameraListener listener : mListenerSet) {
                    if (isAvailable(status)) {
                        listener.onCameraAvailable(id);
                    } else {
                        listener.onCameraUnavailable(id);
                    }
                } // for
            } // synchronized
        } // onStatusChanged
    } // CameraServiceListener
} // CameraManager
