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

import android.bluetooth.IBluetoothDeviceCallback;

/**
 * System private API for talking with the Bluetooth service.
 *
 * {@hide}
 */
interface IBluetoothDevice
{
    boolean isEnabled();
    boolean enable(in IBluetoothDeviceCallback callback);  // async
    boolean disable();

    String getAddress();
    String getName();
    boolean setName(in String name);
    String getMajorClass();
    String getMinorClass();
    String getVersion();
    String getRevision();
    String getManufacturer();
    String getCompany();

    int getMode();
    boolean setMode(int mode);

    int getDiscoverableTimeout();
    boolean setDiscoverableTimeout(int timeout);

    boolean startDiscovery(boolean resolveNames);
    boolean cancelDiscovery();
    boolean isDiscovering();
    boolean startPeriodicDiscovery();
    boolean stopPeriodicDiscovery();
    boolean isPeriodicDiscovery();
    String[] listRemoteDevices();

    String[] listAclConnections();
    boolean isAclConnected(in String address);
    boolean disconnectRemoteDeviceAcl(in String address);

    boolean createBond(in String address);
    boolean cancelBondProcess(in String address);
    boolean removeBond(in String address);
    String[] listBonds();
    int getBondState(in String address);

    String getRemoteName(in String address);
    String getRemoteAlias(in String address);
    boolean setRemoteAlias(in String address, in String alias);
    boolean clearRemoteAlias(in String address);
    String getRemoteVersion(in String address);
    String getRemoteRevision(in String address);
    int getRemoteClass(in String address);
    String getRemoteManufacturer(in String address);
    String getRemoteCompany(in String address);
    String getRemoteMajorClass(in String address);
    String getRemoteMinorClass(in String address);
    String[] getRemoteServiceClasses(in String address);
    boolean getRemoteServiceChannel(in String address, int uuid16, in IBluetoothDeviceCallback callback);
    byte[] getRemoteFeatures(in String adddress);
    String lastSeen(in String address);
    String lastUsed(in String address);

    boolean setPin(in String address, in byte[] pin);
    boolean cancelPin(in String address);
}
