/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.RequiresPermission;
import android.companion.virtual.IVirtualDevice;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.Closeable;

/**
 * The base class for all virtual input devices such as VirtualKeyboard, VirtualMouse.
 * This implements the shared functionality such as closing the device and keeping track of
 * identifiers.
 *
 * @hide
 */
abstract class VirtualInputDevice implements Closeable {

    protected static final String TAG = "VirtualInputDevice";

    /**
     * The virtual device to which this VirtualInputDevice belongs to.
     */
    protected final IVirtualDevice mVirtualDevice;

    /**
     * The token used to uniquely identify the virtual input device.
     */
    protected final IBinder mToken;

    protected final VirtualInputDeviceConfig mConfig;

    /** @hide */
    VirtualInputDevice(VirtualInputDeviceConfig config,
            IVirtualDevice virtualDevice, IBinder token) {
        mConfig = config;
        mVirtualDevice = virtualDevice;
        mToken = token;
    }

    /**
     * @return The device id of this device.
     * @hide
     */
    public int getInputDeviceId() {
        try {
            return mVirtualDevice.getInputDeviceId(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void close() {
        Log.d(TAG, "Closing virtual input device " + mConfig.getInputDeviceName());
        try {
            mVirtualDevice.unregisterInputDevice(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return mConfig.toString();
    }
}
