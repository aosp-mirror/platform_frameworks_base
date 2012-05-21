/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.hardware.input.KeyboardLayout;
import android.hardware.input.IInputDevicesChangedListener;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.InputEvent;

/** @hide */
interface IInputManager {
    // Gets input device information.
    InputDevice getInputDevice(int deviceId);
    int[] getInputDeviceIds();

    // Reports whether the hardware supports the given keys; returns true if successful
    boolean hasKeys(int deviceId, int sourceMask, in int[] keyCodes, out boolean[] keyExists);

    // Temporarily changes the pointer speed.
    void tryPointerSpeed(int speed);

    // Injects an input event into the system.  To inject into windows owned by other
    // applications, the caller must have the INJECT_EVENTS permission.
    boolean injectInputEvent(in InputEvent ev, int mode);

    // Keyboard layouts configuration.
    KeyboardLayout[] getKeyboardLayouts();
    KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor);
    String getCurrentKeyboardLayoutForInputDevice(String inputDeviceDescriptor);
    void setCurrentKeyboardLayoutForInputDevice(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor);
    String[] getKeyboardLayoutsForInputDevice(String inputDeviceDescriptor);
    void addKeyboardLayoutForInputDevice(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor);
    void removeKeyboardLayoutForInputDevice(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor);

    // Registers an input devices changed listener.
    void registerInputDevicesChangedListener(IInputDevicesChangedListener listener);

    // Input device vibrator control.
    void vibrate(int deviceId, in long[] pattern, int repeat, IBinder token);
    void cancelVibrate(int deviceId, IBinder token);
}
