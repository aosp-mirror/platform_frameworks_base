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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;

/**
 * A virtual touchscreen representing a touch-based display input mechanism on a remote device.
 *
 * This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.
 *
 * @hide
 */
@SystemApi
public class VirtualTouchscreen implements Closeable {

    private final IVirtualDevice mVirtualDevice;
    private final IBinder mToken;

    /** @hide */
    public VirtualTouchscreen(IVirtualDevice virtualDevice, IBinder token) {
        mVirtualDevice = virtualDevice;
        mToken = token;
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void close() {
        try {
            mVirtualDevice.unregisterInputDevice(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a touch event to the system.
     *
     * @param event the event to send
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendTouchEvent(@NonNull VirtualTouchEvent event) {
        try {
            mVirtualDevice.sendTouchEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
