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

package com.android.server.companion.virtual.camera;

import android.companion.virtual.camera.IVirtualCamera;
import android.companion.virtual.camera.VirtualCameraHalConfig;

/**
 * AIDL Interface to communicate with the VirtualCamera HAL
 * @hide
 */
interface IVirtualCameraService {

    /**
     * Registers a new camera with the virtual camera hal.
     * @return true if the camera was successfully registered
     */
    boolean registerCamera(in IVirtualCamera camera);

    /**
     * Unregisters the camera from the virtual camera hal. After this call the virtual camera won't
     * be visible to the camera framework anymore.
     */
    void unregisterCamera(in IVirtualCamera camera);
}
