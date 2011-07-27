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
    int getBluetoothState();
    boolean enable();
    boolean disable(boolean persistSetting);

    String getAddress();
    String getName();
    boolean setName(in String name);
    ParcelUuid[] getUuids();

    int getScanMode();
    boolean setScanMode(int mode, int duration);

    int getDiscoverableTimeout();
    boolean setDiscoverableTimeout(int timeout);

    boolean startDiscovery();
    boolean cancelDiscovery();
    boolean isDiscovering();
    byte[] readOutOfBandData();

    int getAdapterConnectionState();

    boolean createBond(in String address);
    boolean createBondOutOfBand(in String address, in byte[] hash, in byte[] randomizer);
    boolean cancelBondProcess(in String address);
    boolean removeBond(in String address);
    String[] listBonds();
    int getBondState(in String address);
    boolean setDeviceOutOfBandData(in String address, in byte[] hash, in byte[] randomizer);

    String getRemoteName(in String address);
    String getRemoteAlias(in String address);
    boolean setRemoteAlias(in String address, in String name);
    int getRemoteClass(in String address);
    ParcelUuid[] getRemoteUuids(in String address);
    boolean fetchRemoteUuids(in String address, in ParcelUuid uuid, in IBluetoothCallback callback);
    int getRemoteServiceChannel(in String address, in ParcelUuid uuid);

    boolean setPin(in String address, in byte[] pin);
    boolean setPasskey(in String address, int passkey);
    boolean setPairingConfirmation(in String address, boolean confirm);
    boolean setRemoteOutOfBandData(in String addres);
    boolean cancelPairingUserInput(in String address);

    boolean setTrust(in String address, in boolean value);
    boolean getTrustState(in String address);
    boolean isBluetoothDock(in String address);

    int addRfcommServiceRecord(in String serviceName, in ParcelUuid uuid, int channel, IBinder b);
    void removeServiceRecord(int handle);

    boolean connectHeadset(String address);
    boolean disconnectHeadset(String address);
    boolean notifyIncomingConnection(String address);

    // HID profile APIs
    boolean connectInputDevice(in BluetoothDevice device);
    boolean disconnectInputDevice(in BluetoothDevice device);
    List<BluetoothDevice> getConnectedInputDevices();
    List<BluetoothDevice> getInputDevicesMatchingConnectionStates(in int[] states);
    int getInputDeviceConnectionState(in BluetoothDevice device);
    boolean setInputDevicePriority(in BluetoothDevice device, int priority);
    int getInputDevicePriority(in BluetoothDevice device);
    boolean allowIncomingHidConnect(in BluetoothDevice device, boolean value);

    boolean isTetheringOn();
    void setBluetoothTethering(boolean value);
    int getPanDeviceConnectionState(in BluetoothDevice device);
    List<BluetoothDevice> getConnectedPanDevices();
    List<BluetoothDevice> getPanDevicesMatchingConnectionStates(in int[] states);
    boolean connectPanDevice(in BluetoothDevice device);
    boolean disconnectPanDevice(in BluetoothDevice device);

    // HDP profile APIs
    boolean registerAppConfiguration(in BluetoothHealthAppConfiguration config,
        in IBluetoothHealthCallback callback);
    boolean unregisterAppConfiguration(in BluetoothHealthAppConfiguration config);
    boolean connectChannelToSource(in BluetoothDevice device, in BluetoothHealthAppConfiguration config);
    boolean connectChannelToSink(in BluetoothDevice device, in BluetoothHealthAppConfiguration config,
        int channelType);
    boolean disconnectChannel(in BluetoothDevice device, in BluetoothHealthAppConfiguration config, in ParcelFileDescriptor fd);
    ParcelFileDescriptor getMainChannelFd(in BluetoothDevice device, in BluetoothHealthAppConfiguration config);
    List<BluetoothDevice> getConnectedHealthDevices();
    List<BluetoothDevice> getHealthDevicesMatchingConnectionStates(in int[] states);
    int getHealthDeviceConnectionState(in BluetoothDevice device);

    void sendConnectionStateChange(in BluetoothDevice device, int state, int prevState);
}
