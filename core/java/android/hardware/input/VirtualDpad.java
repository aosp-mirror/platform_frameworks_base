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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.KeyEvent;

import java.io.Closeable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A virtual dpad representing a key input mechanism on a remote device.
 *
 * This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.
 *
 * @hide
 */
@SystemApi
public class VirtualDpad implements Closeable {

    private final Set<Integer> mSupportedKeyCodes =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    KeyEvent.KEYCODE_DPAD_UP,
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    KeyEvent.KEYCODE_DPAD_LEFT,
                                    KeyEvent.KEYCODE_DPAD_RIGHT,
                                    KeyEvent.KEYCODE_DPAD_CENTER)));
    private final IVirtualDevice mVirtualDevice;
    private final IBinder mToken;

    /** @hide */
    public VirtualDpad(IVirtualDevice virtualDevice, IBinder token) {
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
     * Sends a key event to the system.
     *
     * Supported key codes are KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT,
     * KEYCODE_DPAD_RIGHT and KEYCODE_DPAD_CENTER,
     *
     * @param event the event to send
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendKeyEvent(@NonNull VirtualKeyEvent event) {
        try {
            if (!mSupportedKeyCodes.contains(event.getKeyCode())) {
                throw new IllegalArgumentException(
                        "Unsupported key code "
                                + event.getKeyCode()
                                + " sent to a VirtualDpad input device.");
            }
            mVirtualDevice.sendDpadKeyEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
