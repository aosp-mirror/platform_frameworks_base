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

import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.os.Bundle;
import android.os.IBinder;

/** @hide */
interface IMidiManager
{
    MidiDeviceInfo[] getDevices();

    // for device creation & removal notifications
    void registerListener(IBinder token, in IMidiDeviceListener listener);
    void unregisterListener(IBinder token, in IMidiDeviceListener listener);

    // for opening built-in MIDI devices
    IMidiDeviceServer openDevice(IBinder token, in MidiDeviceInfo device);

    // for registering built-in MIDI devices
    MidiDeviceInfo registerDeviceServer(in IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, in String[] inputPortNames, in String[] outputPortNames,
            in Bundle properties, int type);

    // for unregistering built-in MIDI devices
    void unregisterDeviceServer(in IMidiDeviceServer server);

    // used by MidiDeviceService to access the MidiDeviceInfo that was created based on its
    // manifest's meta-data
    MidiDeviceInfo getServiceDeviceInfo(String packageName, String className);

    // used for client's to retrieve a device's MidiDeviceStatus
    MidiDeviceStatus getDeviceStatus(in MidiDeviceInfo deviceInfo);

    // used by MIDI devices to report their status
    // the token is used by MidiService for death notification
    void setDeviceStatus(IBinder token, in MidiDeviceStatus status);
}
