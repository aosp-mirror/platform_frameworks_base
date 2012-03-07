/*
 * Copyright (C) 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.bluetooth.IBluetoothHealthCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.os.ParcelUuid;
import android.os.ParcelFileDescriptor;

/**
 * System private API for talking with the Bluetooth service.
 *
 * {@hide}
 */
interface IBluetooth
{
    boolean isEnabled();
    int getState();
    boolean enable();
    boolean disable(boolean persist);

    String getAddress();
    ParcelUuid[] getUuids();
    boolean setName(in String name);
    String getName();

    int getScanMode();
    boolean setScanMode(int mode, int duration);

    int getDiscoverableTimeout();
    boolean setDiscoverableTimeout(int timeout);

    boolean startDiscovery();
    boolean cancelDiscovery();
    boolean isDiscovering();

    int getAdapterConnectionState();
    int getProfileConnectionState(int profile);

    BluetoothDevice[] getBondedDevices();
    boolean createBond(in BluetoothDevice device);
    boolean cancelBondProcess(in BluetoothDevice device);
    boolean removeBond(in BluetoothDevice device);
    int getBondState(in BluetoothDevice device);

    String getRemoteName(in BluetoothDevice device);
    String getRemoteAlias(in BluetoothDevice device);
    boolean setRemoteAlias(in BluetoothDevice device, in String name);
    int getRemoteClass(in BluetoothDevice device);
    ParcelUuid[] getRemoteUuids(in BluetoothDevice device);
    //TODO(BT)
    //boolean fetchRemoteUuids(in BluetoothDevice device, in ParcelUuid uuid,
    //    in IBluetoothCallback callback);

    boolean setPin(in BluetoothDevice device, boolean accept, int len, in byte[] pinCode);
    boolean setPasskey(in BluetoothDevice device, boolean accept, int len, in byte[]
    passkey);
    boolean setPairingConfirmation(in BluetoothDevice device, boolean accept);

    void sendConnectionStateChange(in BluetoothDevice device, int profile, int state, int prevState);
}
