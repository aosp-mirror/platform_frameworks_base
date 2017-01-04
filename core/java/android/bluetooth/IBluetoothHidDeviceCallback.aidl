/*
 * Copyright (C) 2016, The Android Open Source Project
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
import android.bluetooth.BluetoothHidDeviceAppConfiguration;

/** @hide */
interface IBluetoothHidDeviceCallback {
   void onAppStatusChanged(in BluetoothDevice device, in BluetoothHidDeviceAppConfiguration config, boolean registered);
   void onConnectionStateChanged(in BluetoothDevice device, in int state);
   void onGetReport(in BluetoothDevice device, in byte type, in byte id, in int bufferSize);
   void onSetReport(in BluetoothDevice device, in byte type, in byte id, in byte[] data);
   void onSetProtocol(in BluetoothDevice device, in byte protocol);
   void onIntrData(in BluetoothDevice device, in byte reportId, in byte[] data);
   void onVirtualCableUnplug(in BluetoothDevice device);
}
