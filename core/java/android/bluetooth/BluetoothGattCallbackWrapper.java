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

import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanResult;
import android.bluetooth.BluetoothGattService;
import android.os.ParcelUuid;
import android.os.RemoteException;

import java.util.List;

/**
 * Wrapper class for default implementation of IBluetoothGattCallback.
 *
 * @hide
 */
public class BluetoothGattCallbackWrapper extends IBluetoothGattCallback.Stub {

    @Override
    public void onClientRegistered(int status, int clientIf) throws RemoteException {
    }

    @Override
    public void onClientConnectionState(int status, int clientIf, boolean connected, String address)
            throws RemoteException {
    }

    @Override
    public void onScanResult(ScanResult scanResult) throws RemoteException {
    }

    @Override
    public void onBatchScanResults(List<ScanResult> batchResults) throws RemoteException {
    }

    @Override
    public void onSearchComplete(String address, List<BluetoothGattService> services,
            int status) throws RemoteException {
    }

    @Override
    public void onCharacteristicRead(String address, int status, int handle, byte[] value)
            throws RemoteException {
    }

    @Override
    public void onCharacteristicWrite(String address, int status, int handle) throws RemoteException {
    }

    @Override
    public void onExecuteWrite(String address, int status) throws RemoteException {
    }

    @Override
    public void onDescriptorRead(String address, int status, int handle, byte[] value) throws RemoteException {
    }

    @Override
    public void onDescriptorWrite(String address, int status, int handle) throws RemoteException {
    }

    @Override
    public void onNotify(String address, int handle, byte[] value) throws RemoteException {
    }

    @Override
    public void onReadRemoteRssi(String address, int rssi, int status) throws RemoteException {
    }

    @Override
    public void onMultiAdvertiseCallback(int status, boolean isStart,
            AdvertiseSettings advertiseSettings) throws RemoteException {
    }

    @Override
    public void onConfigureMTU(String address, int mtu, int status) throws RemoteException {
    }

    @Override
    public void onFoundOrLost(boolean onFound, ScanResult scanResult) throws RemoteException {
    }

    @Override
    public void onScanManagerErrorCallback(int errorCode) throws RemoteException {
    }
}
