/*
 * Copyright (C) 2012 Google Inc.
 */
package android.bluetooth;

import android.bluetooth.BluetoothDevice;

/**
 * API for Bluetooth Pan service
 *
 * {@hide}
 */
interface IBluetoothPan {
    // Public API
    boolean isTetheringOn();
    void setBluetoothTethering(boolean value);
    boolean connect(in BluetoothDevice device);
    boolean disconnect(in BluetoothDevice device);
    List<BluetoothDevice> getConnectedDevices();
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states);
    int getConnectionState(in BluetoothDevice device);
}
