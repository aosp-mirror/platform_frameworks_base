/*
 * Copyright (C) 2012 Google Inc.
 */

package android.bluetooth;

import android.bluetooth.IBluetooth;

/**
 * API for Communication between BluetoothAdapter and BluetoothManager
 *
 * {@hide}
 */
interface IBluetoothManagerCallback {
    void onBluetoothServiceUp(in IBluetooth bluetoothService);
    void onBluetoothServiceDown();
}