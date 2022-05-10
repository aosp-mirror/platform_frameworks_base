/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.presence;

import static com.android.server.companion.presence.CompanionDevicePresenceMonitor.DEBUG;
import static com.android.server.companion.presence.Utils.btDeviceToString;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.net.MacAddress;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.util.Log;

import com.android.server.companion.AssociationStore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("LongLogTag")
class BluetoothCompanionDeviceConnectionListener
        extends BluetoothAdapter.BluetoothConnectionCallback
        implements AssociationStore.OnChangeListener {
    private static final String TAG = "CompanionDevice_PresenceMonitor_BT";

    interface Callback {
        void onBluetoothCompanionDeviceConnected(int associationId);

        void onBluetoothCompanionDeviceDisconnected(int associationId);
    }

    private final @NonNull AssociationStore mAssociationStore;
    private final @NonNull Callback mCallback;
    /** A set of ALL connected BT device (not only companion.) */
    private final @NonNull Map<MacAddress, BluetoothDevice> mAllConnectedDevices = new HashMap<>();

    BluetoothCompanionDeviceConnectionListener(@NonNull AssociationStore associationStore,
            @NonNull Callback callback) {
        mAssociationStore = associationStore;
        mCallback = callback;
    }

    public void init(@NonNull BluetoothAdapter btAdapter) {
        if (DEBUG) Log.i(TAG, "init()");

        btAdapter.registerBluetoothConnectionCallback(
                new HandlerExecutor(Handler.getMain()), /* callback */this);
        mAssociationStore.registerListener(this);
    }

    /**
     * Overrides
     * {@link BluetoothAdapter.BluetoothConnectionCallback#onDeviceConnected(BluetoothDevice)}.
     */
    @Override
    public void onDeviceConnected(@NonNull BluetoothDevice device) {
        if (DEBUG) Log.i(TAG, "onDevice_Connected() " + btDeviceToString(device));

        final MacAddress macAddress = MacAddress.fromString(device.getAddress());
        if (mAllConnectedDevices.put(macAddress, device) != null) {
            if (DEBUG) Log.w(TAG, "Device " + btDeviceToString(device) + " is already connected.");
            return;
        }

        onDeviceConnectivityChanged(device, true);
    }

    /**
     * Overrides
     * {@link BluetoothAdapter.BluetoothConnectionCallback#onDeviceConnected(BluetoothDevice)}.
     * Also invoked when user turns BT off while the device is connected.
     */
    @Override
    public void onDeviceDisconnected(@NonNull BluetoothDevice device,
            int reason) {
        if (DEBUG) {
            Log.i(TAG, "onDevice_Disconnected() " + btDeviceToString(device));
            Log.d(TAG, "  reason=" + disconnectReasonToString(reason));
        }

        final MacAddress macAddress = MacAddress.fromString(device.getAddress());
        if (mAllConnectedDevices.remove(macAddress) == null) {
            if (DEBUG) {
                Log.w(TAG, "The device wasn't tracked as connected " + btDeviceToString(device));
            }
            return;
        }

        onDeviceConnectivityChanged(device, false);
    }

    private void onDeviceConnectivityChanged(@NonNull BluetoothDevice device, boolean connected) {
        final List<AssociationInfo> associations =
                mAssociationStore.getAssociationsByAddress(device.getAddress());

        if (DEBUG) {
            Log.d(TAG, "onDevice_ConnectivityChanged() " + btDeviceToString(device)
                    + " connected=" + connected);
            if (associations.isEmpty()) {
                Log.d(TAG, "  > No CDM associations");
            } else {
                Log.d(TAG, "  > associations=" + Arrays.toString(associations.toArray()));
            }
        }

        for (AssociationInfo association : associations) {
            final int id = association.getId();
            if (connected) {
                mCallback.onBluetoothCompanionDeviceConnected(id);
            } else {
                mCallback.onBluetoothCompanionDeviceDisconnected(id);
            }
        }
    }

    @Override
    public void onAssociationAdded(AssociationInfo association) {
        if (DEBUG) Log.d(TAG, "onAssociation_Added() " + association);

        if (mAllConnectedDevices.containsKey(association.getDeviceMacAddress())) {
            mCallback.onBluetoothCompanionDeviceConnected(association.getId());
        }
    }

    @Override
    public void onAssociationRemoved(AssociationInfo association) {
        // Intentionally do nothing: CompanionDevicePresenceMonitor will do all the bookkeeping
        // required.
    }

    @Override
    public void onAssociationUpdated(AssociationInfo association, boolean addressChanged) {
        if (DEBUG) {
            Log.d(TAG, "onAssociation_Updated() addrChange=" + addressChanged
                    + " " + association);
        }

        if (!addressChanged) {
            // Don't need to do anything.
            return;
        }

        // At the moment CDM does allow changing association addresses, so we will never come here.
        // This will be implemented when CDM support updating addresses.
        throw new IllegalArgumentException("Address changes are not supported.");
    }
}
