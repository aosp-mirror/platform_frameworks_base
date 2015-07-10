/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.camera2.impl;

/**
 * Internal interface for CameraDeviceImpl to CameraCaptureSessionImpl(s) communication
 */
public interface CameraCaptureSessionCore {

    /**
     * Replace this session with another session.
     *
     * <p>This is an optimization to avoid unconfiguring and then immediately having to
     * reconfigure again.</p>
     *
     * <p>The semantics are identical to {@link #close}, except that unconfiguring will be skipped.
     * </p>
     *
     * <p>After this call completes, the session will not call any further methods on the camera
     * device.</p>
     *
     * @see CameraCaptureSession#close
     */
    void replaceSessionClose();

    /**
     *
     * Create an internal state callback, to be invoked on the mDeviceHandler
     *
     * <p>It has a few behaviors:
     * <ul>
     * <li>Convert device state changes into session state changes.
     * <li>Keep track of async tasks that the session began (idle, abort).
     * </ul>
     * </p>
     * */
    CameraDeviceImpl.StateCallbackKK getDeviceStateCallback();

    /**
     * Whether currently in mid-abort.
     *
     * <p>This is used by the implementation to set the capture failure
     * reason, in lieu of more accurate error codes from the camera service.
     * Unsynchronized to avoid deadlocks between simultaneous session->device,
     * device->session calls.</p>
     *
     */
    boolean isAborting();

}
