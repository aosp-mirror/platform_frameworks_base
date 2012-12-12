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
     * @hide
     */
    public CameraManager() {
    }

    /**
     * <p>Return the list of currently connected camera devices by
     * identifier. Non-removable cameras use integers starting at 0 for their
     * identifiers, while removable cameras have a unique identifier for each
     * individual device, even if they are the same model.</p>
     *
     * @return The list of currently connected camera devices.
     */
    public String[] getDeviceIdList() {
        return null;
    }

    /**
     * Register a listener to be notified about camera device availability.
     *
     * @param listener the new listener to send camera availablity notices to.
     */
    public void registerCameraListener(CameraListener listener) {
    }

    /**
     * Remove a previously-added listener; the listener will no longer receive
     * connection and disconnection callbacks.
     *
     * @param listener the listener to remove from the notification list
     */
    public void unregisterCameraListener(CameraListener listener) {
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
        throw new IllegalArgumentException();
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
        throw new IllegalArgumentException();
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
}
