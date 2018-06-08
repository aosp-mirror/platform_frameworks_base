/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.bluetooth;

import android.Manifest;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * High level manager used to obtain an instance of an {@link BluetoothAdapter}
 * and to conduct overall Bluetooth Management.
 * <p>
 * Use {@link android.content.Context#getSystemService(java.lang.String)}
 * with {@link Context#BLUETOOTH_SERVICE} to create an {@link BluetoothManager},
 * then call {@link #getAdapter} to obtain the {@link BluetoothAdapter}.
 * </p>
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>
 * For more information about using BLUETOOTH, read the <a href=
 * "{@docRoot}guide/topics/connectivity/bluetooth.html">Bluetooth</a> developer
 * guide.
 * </p>
 * </div>
 *
 * @see Context#getSystemService
 * @see BluetoothAdapter#getDefaultAdapter()
 */
@SystemService(Context.BLUETOOTH_SERVICE)
@RequiresFeature(PackageManager.FEATURE_BLUETOOTH)
public final class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private final BluetoothAdapter mAdapter;

    /**
     * @hide
     */
    public BluetoothManager(Context context) {
        context = context.getApplicationContext();
        if (context == null) {
            throw new IllegalArgumentException(
                    "context not associated with any application (using a mock context?)");
        }
        // Legacy api - getDefaultAdapter does not take in the context
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Get the default BLUETOOTH Adapter for this device.
     *
     * @return the default BLUETOOTH Adapter
     */
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Get the current connection state of the profile to the remote device.
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of the local Bluetooth adapter for certain profile.
     * This can be used by applications like status bar which would just like
     * to know the state of Bluetooth.
     *
     * @param device Remote bluetooth device.
     * @param profile GATT or GATT_SERVER
     * @return State of the profile connection. One of {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_DISCONNECTED},
     * {@link BluetoothProfile#STATE_DISCONNECTING}
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getConnectionState(BluetoothDevice device, int profile) {
        if (DBG) Log.d(TAG, "getConnectionState()");

        List<BluetoothDevice> connectedDevices = getConnectedDevices(profile);
        for (BluetoothDevice connectedDevice : connectedDevices) {
            if (device.equals(connectedDevice)) {
                return BluetoothProfile.STATE_CONNECTED;
            }
        }

        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Get connected devices for the specified profile.
     *
     * <p> Return the set of devices which are in state {@link BluetoothProfile#STATE_CONNECTED}
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of Bluetooth for this profile.
     * This can be used by applications like status bar which would just like
     * to know the state of Bluetooth.
     *
     * @param profile GATT or GATT_SERVER
     * @return List of devices. The list will be empty on error.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getConnectedDevices(int profile) {
        if (DBG) Log.d(TAG, "getConnectedDevices");
        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER) {
            throw new IllegalArgumentException("Profile not supported: " + profile);
        }

        List<BluetoothDevice> connectedDevices = new ArrayList<BluetoothDevice>();

        try {
            IBluetoothManager managerService = mAdapter.getBluetoothManager();
            IBluetoothGatt iGatt = managerService.getBluetoothGatt();
            if (iGatt == null) return connectedDevices;

            connectedDevices = iGatt.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED});
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }

        return connectedDevices;
    }

    /**
     * Get a list of devices that match any of the given connection
     * states.
     *
     * <p> If none of the devices match any of the given states,
     * an empty list will be returned.
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of the local Bluetooth adapter for this profile.
     * This can be used by applications like status bar which would just like
     * to know the state of the local adapter.
     *
     * @param profile GATT or GATT_SERVER
     * @param states Array of states. States can be one of {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_DISCONNECTED},
     * {@link BluetoothProfile#STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int profile, int[] states) {
        if (DBG) Log.d(TAG, "getDevicesMatchingConnectionStates");

        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER) {
            throw new IllegalArgumentException("Profile not supported: " + profile);
        }

        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();

        try {
            IBluetoothManager managerService = mAdapter.getBluetoothManager();
            IBluetoothGatt iGatt = managerService.getBluetoothGatt();
            if (iGatt == null) return devices;
            devices = iGatt.getDevicesMatchingConnectionStates(states);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }

        return devices;
    }

    /**
     * Open a GATT Server
     * The callback is used to deliver results to Caller, such as connection status as well
     * as the results of any other GATT server operations.
     * The method returns a BluetoothGattServer instance. You can use BluetoothGattServer
     * to conduct GATT server operations.
     *
     * @param context App context
     * @param callback GATT server callback handler that will receive asynchronous callbacks.
     * @return BluetoothGattServer instance
     */
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback) {

        return (openGattServer(context, callback, BluetoothDevice.TRANSPORT_AUTO));
    }

    /**
     * Open a GATT Server
     * The callback is used to deliver results to Caller, such as connection status as well
     * as the results of any other GATT server operations.
     * The method returns a BluetoothGattServer instance. You can use BluetoothGattServer
     * to conduct GATT server operations.
     *
     * @param context App context
     * @param callback GATT server callback handler that will receive asynchronous callbacks.
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     * BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     * BluetoothDevice#TRANSPORT_LE}
     * @return BluetoothGattServer instance
     * @hide
     */
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback, int transport) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException("null parameter: " + context + " " + callback);
        }

        // TODO(Bluetooth) check whether platform support BLE
        //     Do the check here or in GattServer?

        try {
            IBluetoothManager managerService = mAdapter.getBluetoothManager();
            IBluetoothGatt iGatt = managerService.getBluetoothGatt();
            if (iGatt == null) {
                Log.e(TAG, "Fail to get GATT Server connection");
                return null;
            }
            BluetoothGattServer mGattServer = new BluetoothGattServer(iGatt, transport);
            Boolean regStatus = mGattServer.registerCallback(callback);
            return regStatus ? mGattServer : null;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }
}
