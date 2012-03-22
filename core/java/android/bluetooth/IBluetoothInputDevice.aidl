/*
 * Copyright (C) 2012 Google Inc.
 */
package android.bluetooth;

import android.bluetooth.BluetoothDevice;

/**
 * API for Bluetooth HID service
 *
 * {@hide}
 */
interface IBluetoothInputDevice {
    // Public API
    boolean connect(in BluetoothDevice device);
    boolean disconnect(in BluetoothDevice device);
    List<BluetoothDevice> getConnectedDevices();
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states);
    int getConnectionState(in BluetoothDevice device);
    boolean setPriority(in BluetoothDevice device, int priority);
    int getPriority(in BluetoothDevice device);
}
