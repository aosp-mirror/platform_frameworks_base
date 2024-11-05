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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.companion.virtual.IVirtualDevice;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

/**
 * A virtual keyboard representing a key input mechanism on a remote device, such as a built-in
 * keyboard on a laptop, a software keyboard on a tablet, or a keypad on a TV remote control.
 *
 * <p>This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualKeyboard extends VirtualInputDevice {

    private final int mUnsupportedKeyCode = KeyEvent.KEYCODE_DPAD_CENTER;

    /** @hide */
    public VirtualKeyboard(VirtualKeyboardConfig config,
            IVirtualDevice virtualDevice, IBinder token) {
        super(config, virtualDevice, token);
    }

    /**
     * Sends a key event to the system.
     *
     * @param event the event to send
     */
    public void sendKeyEvent(@NonNull VirtualKeyEvent event) {
        try {
            if (mUnsupportedKeyCode == event.getKeyCode()) {
                throw new IllegalArgumentException(
                    "Unsupported key code " + event.getKeyCode()
                        + " sent to a VirtualKeyboard input device.");
            }
            if (!mVirtualDevice.sendKeyEvent(mToken, event)) {
                Log.w(TAG, "Failed to send key event to virtual keyboard "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return The id of the {@link android.view.InputDevice} corresponding to this keyboard.
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    @Override
    public int getInputDeviceId() {
        return super.getInputDeviceId();
    }
}
