/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.ParcelUuid;
import android.bluetooth.BluetoothGattService;

/**
 * Callback definitions for interacting with BLE / GATT
 * @hide
 */
oneway interface IBluetoothGattCallbackExt {
    void onClientRegistered(in int status, in int clientIf);
    void onClientConnectionState(in int status, in int clientIf,
                                 in boolean connected, in String address);
    void onPhyUpdate(in String address, in int txPhy, in int rxPhy, in int status);
    void onPhyRead(in String address, in int txPhy, in int rxPhy, in int status);
    void onSearchComplete(in String address, in List<BluetoothGattService> services, in int status);
    void onCharacteristicRead(in String address, in int status, in int handle, in byte[] value);
    void onCharacteristicWrite(in String address, in int status, in int handle);
    void onExecuteWrite(in String address, in int status);
    void onDescriptorRead(in String address, in int status, in int handle, in byte[] value);
    void onDescriptorWrite(in String address, in int status, in int handle);
    void onNotify(in String address, in int handle, in byte[] value);
    void onReadRemoteRssi(in String address, in int rssi, in int status);
    void onConfigureMTU(in String address, in int mtu, in int status);
}
