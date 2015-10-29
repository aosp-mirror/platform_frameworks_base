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

package android.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.os.Bundle;

/**
 * API for Bluetooth Headset Client service (HFP HF Role)
 *
 * {@hide}
 */
interface IBluetoothHeadsetClient {
    boolean connect(in BluetoothDevice device);
    boolean disconnect(in BluetoothDevice device);

    boolean acceptIncomingConnect(in BluetoothDevice device);
    boolean rejectIncomingConnect(in BluetoothDevice device);

    List<BluetoothDevice> getConnectedDevices();
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states);
    int getConnectionState(in BluetoothDevice device);
    boolean setPriority(in BluetoothDevice device, int priority);
    int getPriority(in BluetoothDevice device);

    boolean startVoiceRecognition(in BluetoothDevice device);
    boolean stopVoiceRecognition(in BluetoothDevice device);

    List<BluetoothHeadsetClientCall> getCurrentCalls(in BluetoothDevice device);
    Bundle getCurrentAgEvents(in BluetoothDevice device);

    boolean acceptCall(in BluetoothDevice device, int flag);
    boolean holdCall(in BluetoothDevice device);
    boolean rejectCall(in BluetoothDevice device);
    boolean terminateCall(in BluetoothDevice device, int index);

    boolean enterPrivateMode(in BluetoothDevice device, int index);
    boolean explicitCallTransfer(in BluetoothDevice device);

    boolean redial(in BluetoothDevice device);
    boolean dial(in BluetoothDevice device, String number);
    boolean dialMemory(in BluetoothDevice device, int location);

    boolean sendDTMF(in BluetoothDevice device, byte code);
    boolean getLastVoiceTagNumber(in BluetoothDevice device);

    int getAudioState(in BluetoothDevice device);
    boolean connectAudio();
    boolean disconnectAudio();
    void setAudioRouteAllowed(boolean allowed);
    boolean getAudioRouteAllowed();

    Bundle getCurrentAgFeatures(in BluetoothDevice device);
}
