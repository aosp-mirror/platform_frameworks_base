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


/**
 * Callback definitions for interacting with BLE / GATT
 * @hide
 */
interface IBluetoothGattCallback {
    void onClientRegistered(in int status, in int clientIf);
    void onClientConnectionState(in int status, in int clientIf,
                                 in boolean connected, in String address);
    void onScanResult(in String address, in int rssi, in byte[] advData);
    void onGetService(in String address, in int srvcType, in int srvcInstId,
                      in ParcelUuid srvcUuid);
    void onGetIncludedService(in String address, in int srvcType, in int srvcInstId,
                              in ParcelUuid srvcUuid, in int inclSrvcType,
                              in int inclSrvcInstId, in ParcelUuid inclSrvcUuid);
    void onGetCharacteristic(in String address, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in int charProps);
    void onGetDescriptor(in String address, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in int descrInstId, in ParcelUuid descrUuid);
    void onSearchComplete(in String address, in int status);
    void onCharacteristicRead(in String address, in int status, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in byte[] value);
    void onCharacteristicWrite(in String address, in int status, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid);
    void onExecuteWrite(in String address, in int status);
    void onDescriptorRead(in String address, in int status, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in int descrInstId, in ParcelUuid descrUuid,
                             in byte[] value);
    void onDescriptorWrite(in String address, in int status, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in int descrInstId, in ParcelUuid descrUuid);
    void onNotify(in String address, in int srvcType,
                             in int srvcInstId, in ParcelUuid srvcUuid,
                             in int charInstId, in ParcelUuid charUuid,
                             in byte[] value);
    void onReadRemoteRssi(in String address, in int rssi, in int status);
    void onListen(in int status);
}
