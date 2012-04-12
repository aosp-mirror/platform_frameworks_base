/*
 * Copyright (C) 2012 Google Inc.
 */

package android.bluetooth;

import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;

/**
 * System private API for talking with the Bluetooth service.
 *
 * {@hide}
 */
interface IBluetoothManager
{
    IBluetooth registerAdapter(in IBluetoothManagerCallback callback);
    void unregisterAdapter(in IBluetoothManagerCallback callback);
    void registerStateChangeCallback(in IBluetoothStateChangeCallback callback);
    void unregisterStateChangeCallback(in IBluetoothStateChangeCallback callback);
    boolean isEnabled();
    boolean enable();
    boolean disable(boolean persist);

    String getAddress();
    String getName();
}
