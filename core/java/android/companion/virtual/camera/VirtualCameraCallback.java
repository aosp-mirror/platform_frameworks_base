/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.hardware.camera2.params.SessionConfiguration;

import java.util.concurrent.Executor;

/**
 * Interface to be provided when creating a new {@link VirtualCamera} in order to receive callbacks
 * from the framework and the camera system.
 *
 * @see VirtualCameraConfig.Builder#setCallback(Executor, VirtualCameraCallback)
 * @hide
 */
public interface VirtualCameraCallback {

    /**
     * Called when a client opens a new camera session for the associated {@link VirtualCamera}
     *
     * @see android.hardware.camera2.CameraDevice#createCaptureSession(SessionConfiguration)
     */
    VirtualCameraSession onOpenSession();
}
