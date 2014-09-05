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

import android.util.AndroidException;

/**
 * <p><code>CameraAccessException</code> is thrown if a camera device could not
 * be queried or opened by the {@link CameraManager}, or if the connection to an
 * opened {@link CameraDevice} is no longer valid.</p>
 *
 * @see CameraManager
 * @see CameraDevice
 */
public class CameraAccessException extends AndroidException {
    /**
     * The camera device is in use already
     * @hide
     */
    public static final int CAMERA_IN_USE = 4;

    /**
     * The system-wide limit for number of open cameras has been reached,
     * and more camera devices cannot be opened until previous instances are
     * closed.
     * @hide
     */
    public static final int MAX_CAMERAS_IN_USE = 5;

    /**
     * The camera is disabled due to a device policy, and cannot be opened.
     *
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled(android.content.ComponentName, boolean)
     */
    public static final int CAMERA_DISABLED = 1;

    /**
     * The camera device is removable and has been disconnected from the Android
     * device, or the camera id used with {@link android.hardware.camera2.CameraManager#openCamera}
     * is no longer valid, or the camera service has shut down the connection due to a
     * higher-priority access request for the camera device.
     */
    public static final int CAMERA_DISCONNECTED = 2;

    /**
     * The camera device is currently in the error state.
     *
     * <p>The camera has failed to open or has failed at a later time
     * as a result of some non-user interaction. Refer to
     * {@link CameraDevice.StateCallback#onError} for the exact
     * nature of the error.</p>
     *
     * <p>No further calls to the camera will succeed. Clean up
     * the camera with {@link CameraDevice#close} and try
     * handling the error in order to successfully re-open the camera.
     * </p>
     *
     */
    public static final int CAMERA_ERROR = 3;

    /**
     * A deprecated HAL version is in use.
     * @hide
     */
    public static final int CAMERA_DEPRECATED_HAL = 1000;

    // Make the eclipse warning about serializable exceptions go away
    private static final long serialVersionUID = 5630338637471475675L; // randomly generated

    private final int mReason;

    /**
     * The reason for the failure to access the camera.
     *
     * @see #CAMERA_DISABLED
     * @see #CAMERA_DISCONNECTED
     * @see #CAMERA_ERROR
     */
    public final int getReason() {
        return mReason;
    }

    public CameraAccessException(int problem) {
        super(getDefaultMessage(problem));
        mReason = problem;
    }

    public CameraAccessException(int problem, String message) {
        super(message);
        mReason = problem;
    }

    public CameraAccessException(int problem, String message, Throwable cause) {
        super(message, cause);
        mReason = problem;
    }

    public CameraAccessException(int problem, Throwable cause) {
        super(getDefaultMessage(problem), cause);
        mReason = problem;
    }

    /**
     * @hide
     */
    public static String getDefaultMessage(int problem) {
        switch (problem) {
            case CAMERA_IN_USE:
                return "The camera device is in use already";
            case MAX_CAMERAS_IN_USE:
                return "The system-wide limit for number of open cameras has been reached, " +
                       "and more camera devices cannot be opened until previous instances " +
                       "are closed.";
            case CAMERA_DISCONNECTED:
                return "The camera device is removable and has been disconnected from the " +
                        "Android device, or the camera service has shut down the connection due " +
                        "to a higher-priority access request for the camera device.";
            case CAMERA_DISABLED:
                return "The camera is disabled due to a device policy, and cannot be opened.";
            case CAMERA_ERROR:
                return "The camera device is currently in the error state; " +
                       "no further calls to it will succeed.";
        }
        return null;
    }
}
