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

package android.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.bluetooth.IBluetoothHealthCallback;
import android.os.ParcelFileDescriptor;

/**
 * API for Bluetooth Health service
 *
 * {@hide}
 */
interface IBluetoothHealth
{
    boolean registerAppConfiguration(in BluetoothHealthAppConfiguration config,
        in IBluetoothHealthCallback callback);
    boolean unregisterAppConfiguration(in BluetoothHealthAppConfiguration config);
    boolean connectChannelToSource(in BluetoothDevice device, in BluetoothHealthAppConfiguration config);
    boolean connectChannelToSink(in BluetoothDevice device, in BluetoothHealthAppConfiguration config,
        int channelType);
    boolean disconnectChannel(in BluetoothDevice device, in BluetoothHealthAppConfiguration config, int id);
    ParcelFileDescriptor getMainChannelFd(in BluetoothDevice device, in BluetoothHealthAppConfiguration config);
    List<BluetoothDevice> getConnectedHealthDevices();
    List<BluetoothDevice> getHealthDevicesMatchingConnectionStates(in int[] states);
    int getHealthDeviceConnectionState(in BluetoothDevice device);
}
