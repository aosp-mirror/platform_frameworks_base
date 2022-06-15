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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.Attributable;
import android.content.AttributionSource;
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
    private static final boolean DBG = false;

    private final AttributionSource mAttributionSource;
    private final BluetoothAdapter mAdapter;

    /**
     * @hide
     */
    public BluetoothManager(Context context) {
        mAttributionSource = resolveAttributionSource(context);
        mAdapter = BluetoothAdapter.createAdapter(mAttributionSource);
    }

    /** {@hide} */
    public static @NonNull AttributionSource resolveAttributionSource(@Nullable Context context) {
        AttributionSource res = null;
        if (context != null) {
            res = context.getAttributionSource();
        }
        if (res == null) {
            res = ActivityThread.currentAttributionSource();
        }
        if (res == null) {
            int uid = android.os.Process.myUid();
            if (uid == android.os.Process.ROOT_UID) {
                uid = android.os.Process.SYSTEM_UID;
            }
            try {
                res = new AttributionSource(uid,
                        AppGlobals.getPackageManager().getPackagesForUid(uid)[0], null);
            } catch (RemoteException ignored) {
            }
        }
        if (res == null) {
            throw new IllegalStateException("Failed to resolve AttributionSource");
        }
        return res;
    }

    /**
     * Get the BLUETOOTH Adapter for this device.
     *
     * @return the BLUETOOTH Adapter
     */
    @RequiresNoPermission
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
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
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
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getConnectedDevices(int profile) {
        if (DBG) Log.d(TAG, "getConnectedDevices");
        return getDevicesMatchingConnectionStates(profile, new int[] {
                BluetoothProfile.STATE_CONNECTED
        });
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
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
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
            devices = Attributable.setAttributionSource(
                    iGatt.getDevicesMatchingConnectionStates(states, mAttributionSource),
                    mAttributionSource);
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
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
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
     * @param eatt_support idicates if server should use eatt channel for notifications.
     * @return BluetoothGattServer instance
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback, boolean eatt_support) {
        return (openGattServer(context, callback, BluetoothDevice.TRANSPORT_AUTO, eatt_support));
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
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback, int transport) {
        return (openGattServer(context, callback, transport, false));
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
     * @param eatt_support idicates if server should use eatt channel for notifications.
     * @return BluetoothGattServer instance
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback, int transport, boolean eatt_support) {
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
            BluetoothGattServer mGattServer =
                    new BluetoothGattServer(iGatt, transport, mAdapter);
            Boolean regStatus = mGattServer.registerCallback(callback, eatt_support);
            return regStatus ? mGattServer : null;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }
}
