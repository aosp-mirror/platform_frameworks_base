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

import android.companion.virtual.IVirtualDevice;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Virtual camera that is used to send image data into system.
 *
 * @hide
 */
public final class VirtualCamera extends IVirtualCamera.Stub {

    private final VirtualCameraConfig mConfig;

    /**
     * VirtualCamera device constructor.
     *
     * @param virtualDevice The Binder object representing this camera in the server.
     * @param config Configuration for the new virtual camera
     */
    public VirtualCamera(
            @NonNull IVirtualDevice virtualDevice, @NonNull VirtualCameraConfig config) {
        mConfig = Objects.requireNonNull(config);
        Objects.requireNonNull(virtualDevice);
        try {
            virtualDevice.registerVirtualCamera(this);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Get the camera session associated with this device */
    @Override
    public IVirtualCameraSession open() {
        // TODO: b/302255544 - Make this async.
        VirtualCameraSession session = mConfig.getCallback().onOpenSession();
        return new VirtualCameraSessionInternal(session);
    }

    /** Returns the configuration of this virtual camera instance. */
    @NonNull
    public VirtualCameraConfig getConfig() {
        return mConfig;
    }

    /**
     * Returns the configuration to be used by the virtual camera HAL.
     *
     * @hide
     */
    @Override
    @NonNull
    public VirtualCameraHalConfig getHalConfig() {
        return mConfig.getHalConfig();
    }
}
