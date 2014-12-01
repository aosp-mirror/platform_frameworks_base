/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.midi;

import android.hardware.usb.UsbDevice;
import android.midi.IMidiListener;
import android.midi.MidiDevice;
import android.midi.MidiDeviceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IMidiManager
{
    MidiDeviceInfo[] getDeviceList();

    // for device creation & removal notifications
    void registerListener(IBinder token, in IMidiListener listener);
    void unregisterListener(IBinder token, in IMidiListener listener);

    // for communicating with MIDI devices
    ParcelFileDescriptor openDevice(IBinder token, in MidiDeviceInfo device);

    // for implementing virtual MIDI devices
    MidiDevice registerVirtualDevice(IBinder token, in Bundle properties);
    void unregisterVirtualDevice(IBinder token, in MidiDeviceInfo device);

    // for use by UsbAudioManager
    void alsaDeviceAdded(int card, int device, in UsbDevice usbDevice);
    void alsaDeviceRemoved(in UsbDevice usbDevice);
}
