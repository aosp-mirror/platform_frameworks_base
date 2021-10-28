/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

/**
 * System level service for managing virtual devices.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManager";

    private final IVirtualDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public VirtualDeviceManager(
            @Nullable IVirtualDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Creates a virtual device.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Nullable
    public VirtualDevice createVirtualDevice() {
        // TODO(b/194949534): Add CDM association ID here and unhide this API
        try {
            IVirtualDevice virtualDevice = mService.createVirtualDevice();
            return new VirtualDevice(mContext, virtualDevice);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A virtual device has its own virtual display, audio output, microphone, and camera etc. The
     * creator of a virtual device can take the output from the virtual display and stream it over
     * to another device, and inject input events that are received from the remote device.
     */
    public static class VirtualDevice implements AutoCloseable {

        private final Context mContext;
        private final IVirtualDevice mVirtualDevice;

        private VirtualDevice(Context context, IVirtualDevice virtualDevice) {
            mContext = context.getApplicationContext();
            mVirtualDevice = virtualDevice;
        }

        /**
         * Closes the virtual device, stopping and tearing down any virtual displays,
         * audio policies, and event injection that's currently in progress.
         */
        public void close() {
            try {
                mVirtualDevice.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
