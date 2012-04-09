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
    /**
    * @hide
    */
    boolean getProtocolMode(in BluetoothDevice device);
    /**
    * @hide
    */
    boolean virtualUnplug(in BluetoothDevice device);
    /**
    * @hide
    */
    boolean setProtocolMode(in BluetoothDevice device, int protocolMode);
    /**
    * @hide
    */
    boolean getReport(in BluetoothDevice device, byte reportType, byte reportId, int bufferSize);
    /**
    * @hide
    */
    boolean setReport(in BluetoothDevice device, byte reportType, String report);
    /**
    * @hide
    */
    boolean sendData(in BluetoothDevice device, String report);
}
