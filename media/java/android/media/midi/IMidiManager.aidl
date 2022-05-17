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

package android.media.midi;

import android.bluetooth.BluetoothDevice;
import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceOpenCallback;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.os.Bundle;
import android.os.IBinder;

/** @hide */
interface IMidiManager
{
    MidiDeviceInfo[] getDevices();

    MidiDeviceInfo[] getDevicesForTransport(int transport);

    // for device creation & removal notifications
    void registerListener(IBinder clientToken, in IMidiDeviceListener listener);
    void unregisterListener(IBinder clientToken, in IMidiDeviceListener listener);

    void openDevice(IBinder clientToken, in MidiDeviceInfo device, in IMidiDeviceOpenCallback callback);
    void openBluetoothDevice(IBinder clientToken, in BluetoothDevice bluetoothDevice,
            in IMidiDeviceOpenCallback callback);
    void closeDevice(IBinder clientToken, IBinder deviceToken);

    // for registering built-in MIDI devices
    MidiDeviceInfo registerDeviceServer(in IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, in String[] inputPortNames, in String[] outputPortNames,
            in Bundle properties, int type, int defaultProtocol);

    // for unregistering built-in MIDI devices
    void unregisterDeviceServer(in IMidiDeviceServer server);

    // used by MidiDeviceService to access the MidiDeviceInfo that was created based on its
    // manifest's meta-data
    MidiDeviceInfo getServiceDeviceInfo(String packageName, String className);

    // used for client's to retrieve a device's MidiDeviceStatus
    MidiDeviceStatus getDeviceStatus(in MidiDeviceInfo deviceInfo);

    // used by MIDI devices to report their status
    // the token is used by MidiService for death notification
    void setDeviceStatus(in IMidiDeviceServer server, in MidiDeviceStatus status);
}
