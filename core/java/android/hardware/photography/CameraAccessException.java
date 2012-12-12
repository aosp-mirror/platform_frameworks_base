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
 * <p><code>CameraAccessException</code> is thrown if a camera device could not
 * be queried or opened by the {@link CameraManager}, or if the connection to an
 * opened {@link CameraDevice} is no longer valid.</p>
 *
 * @see CameraManager
 * @see CameraDevice
 */
public class CameraAccessException extends Exception {
    /**
     * The camera device is in use already
     */
    public static final int CAMERA_IN_USE = 1;

    /**
     * The system-wide limit for number of open cameras has been reached,
     * and more camera devices cannot be opened until previous instances are
     * closed.
     */
    public static final int MAX_CAMERAS_IN_USE = 2;

    /**
     * The camera is disabled due to a device policy, and cannot be opened.
     *
     * @see android.app.admin.DevicePolicyManager#setCameraDisabled(android.content.ComponentName, boolean)
     */
    public static final int CAMERA_DISABLED = 3;

    /**
     * The camera device is removable and has been disconnected from the Android
     * device, or the camera service has shut down the connection due to a
     * higher-priority access request for the camera device.
     */
    public static final int CAMERA_DISCONNECTED = 4;

    private int mReason;

    /**
     * The reason for the failure to access the camera.
     *
     * @see #CAMERA_IN_USE
     * @see #MAX_CAMERAS_IN_USE
     * @see #CAMERA_DISABLED
     * @see #CAMERA_DISCONNECTED
     */
    public final int getReason() {
        return mReason;
    }

    public CameraAccessException(int problem) {
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
        super(cause);
        mReason = problem;
    }
}
