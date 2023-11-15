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

import android.annotation.FlaggedApi;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.flags.Flags;
import android.hardware.camera2.CameraDevice;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A VirtualCamera is the representation of a remote or computer generated camera that will be
 * exposed to applications using the Android Camera APIs.
 *
 * <p>A VirtualCamera is created using {@link
 * VirtualDeviceManager.VirtualDevice#createVirtualCamera(VirtualCameraConfig)}.
 *
 * <p>Once a virtual camera is created, it will receive callbacks from the system when an
 * application attempts to use it via the {@link VirtualCameraCallback} class set using {@link
 * VirtualCameraConfig.Builder#setVirtualCameraCallback(Executor, VirtualCameraCallback)}
 *
 * @see VirtualDeviceManager.VirtualDevice#createVirtualDevice(int, VirtualDeviceParams)
 * @see VirtualCameraConfig.Builder#setVirtualCameraCallback(Executor, VirtualCameraCallback)
 * @see android.hardware.camera2.CameraManager#openCamera(String, CameraDevice.StateCallback,
 *     android.os.Handler)
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public final class VirtualCamera implements Closeable {

    private final IVirtualDevice mVirtualDevice;
    private final VirtualCameraConfig mConfig;

    /**
     * VirtualCamera device constructor.
     *
     * @param virtualDevice The Binder object representing this camera in the server.
     * @param config Configuration for the new virtual camera
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public VirtualCamera(
            @NonNull IVirtualDevice virtualDevice, @NonNull VirtualCameraConfig config) {
        mVirtualDevice = virtualDevice;
        mConfig = Objects.requireNonNull(config);
        Objects.requireNonNull(virtualDevice);
        // TODO(b/310857519): Avoid registration inside constructor.
        try {
            mVirtualDevice.registerVirtualCamera(config);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Returns the configuration of this virtual camera instance. */
    @NonNull
    public VirtualCameraConfig getConfig() {
        return mConfig;
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void close() {
        try {
            mVirtualDevice.unregisterVirtualCamera(mConfig);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}
