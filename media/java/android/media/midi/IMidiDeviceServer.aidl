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

import android.media.midi.MidiDeviceInfo;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IMidiDeviceServer
{
    ParcelFileDescriptor openInputPort(IBinder token, int portNumber);
    ParcelFileDescriptor openOutputPort(IBinder token, int portNumber);
    void closePort(IBinder token);
    void closeDevice();

    // connects the input port pfd to the specified output port
    // Returns the PID of the called process.
    int connectPorts(IBinder token, in ParcelFileDescriptor pfd, int outputPortNumber);

    MidiDeviceInfo getDeviceInfo();
    void setDeviceInfo(in MidiDeviceInfo deviceInfo);
}
