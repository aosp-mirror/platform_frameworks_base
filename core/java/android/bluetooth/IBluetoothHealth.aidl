/*
 * Copyright (C) 2012 Google Inc.
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
