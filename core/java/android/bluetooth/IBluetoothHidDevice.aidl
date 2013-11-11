/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 * Copyright (C) 2011 The Android Open Source Project
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
import android.bluetooth.IBluetoothHidDeviceCallback;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;

/** @hide */
interface IBluetoothHidDevice {
    boolean registerApp(in BluetoothHidDeviceAppConfiguration config,
            in BluetoothHidDeviceAppSdpSettings sdp, in BluetoothHidDeviceAppQosSettings inQos,
            in BluetoothHidDeviceAppQosSettings outQos, in IBluetoothHidDeviceCallback callback);
    boolean unregisterApp(in BluetoothHidDeviceAppConfiguration config);
    boolean sendReport(in int id, in byte[] data);
    boolean replyReport(in byte type, in byte id, in byte[] data);
    boolean reportError();
    boolean unplug();
    boolean connect();
    boolean disconnect();
}
