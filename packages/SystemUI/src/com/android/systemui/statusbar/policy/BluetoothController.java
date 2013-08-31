/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class BluetoothController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.BluetoothController";

    private boolean mEnabled = false;

    private Set<BluetoothDevice> mBondedDevices = new HashSet<BluetoothDevice>();

    private ArrayList<BluetoothStateChangeCallback> mChangeCallbacks =
            new ArrayList<BluetoothStateChangeCallback>();

    public BluetoothController(Context context) {

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        context.registerReceiver(this, filter);

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            handleAdapterStateChange(adapter.getState());
        }
        fireCallbacks();
        updateBondedBluetoothDevices();
    }

    public void addStateChangedCallback(BluetoothStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    public Set<BluetoothDevice> getBondedBluetoothDevices() {
        return mBondedDevices;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            handleAdapterStateChange(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
        }
        fireCallbacks();
        updateBondedBluetoothDevices();
    }

    private void updateBondedBluetoothDevices() {
        mBondedDevices.clear();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices != null) {
                for (BluetoothDevice device : devices) {
                    if (device.getBondState() != BluetoothDevice.BOND_NONE) {
                        mBondedDevices.add(device);
                    }
                }
            }
        }
    }

    private void handleAdapterStateChange(int adapterState) {
        mEnabled = (adapterState == BluetoothAdapter.STATE_ON);
    }

    private void fireCallbacks() {
        for (BluetoothStateChangeCallback cb : mChangeCallbacks) {
            cb.onBluetoothStateChange(mEnabled);
        }
    }
}
