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
import android.graphics.PointF;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.MotionEvent;

/**
 * A virtual mouse representing a relative input mechanism on a remote device, such as a mouse or
 * trackpad.
 *
 * This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.
 *
 * @hide
 */
@SystemApi
public class VirtualMouse extends VirtualInputDevice {

    /** @hide */
    public VirtualMouse(VirtualMouseConfig config, IVirtualDevice virtualDevice, IBinder token) {
        super(config, virtualDevice, token);
    }

    /**
     * Send a mouse button event to the system.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendButtonEvent(@NonNull VirtualMouseButtonEvent event) {
        try {
            mVirtualDevice.sendButtonEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a scrolling event to the system. See {@link MotionEvent#AXIS_VSCROLL} and
     * {@link MotionEvent#AXIS_SCROLL}.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendScrollEvent(@NonNull VirtualMouseScrollEvent event) {
        try {
            mVirtualDevice.sendScrollEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a relative movement event to the system.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendRelativeEvent(@NonNull VirtualMouseRelativeEvent event) {
        try {
            mVirtualDevice.sendRelativeEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current cursor position.
     *
     * @return the position, expressed as x and y coordinates
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public @NonNull PointF getCursorPosition() {
        try {
            return mVirtualDevice.getCursorPosition(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
