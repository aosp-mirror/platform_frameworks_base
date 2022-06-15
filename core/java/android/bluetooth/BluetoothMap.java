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

package android.bluetooth;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SdkConstant.SdkConstantType;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Attributable;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.CloseGuard;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth MAP
 * Profile.
 *
 * @hide
 */
@SystemApi
public final class BluetoothMap implements BluetoothProfile, AutoCloseable {

    private static final String TAG = "BluetoothMap";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private CloseGuard mCloseGuard;

    /** @hide */
    @SuppressLint("ActionValue")
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.map.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * There was an error trying to obtain the state
     *
     * @hide
     */
    public static final int STATE_ERROR = -1;

    /** @hide */
    public static final int RESULT_FAILURE = 0;
    /** @hide */
    public static final int RESULT_SUCCESS = 1;
    /**
     * Connection canceled before completion.
     *
     * @hide
     */
    public static final int RESULT_CANCELED = 2;

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
    private final BluetoothProfileConnector<IBluetoothMap> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.MAP,
                    "BluetoothMap", IBluetoothMap.class.getName()) {
                @Override
                public IBluetoothMap getServiceInterface(IBinder service) {
                    return IBluetoothMap.Stub.asInterface(Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothMap proxy object.
     */
    /* package */ BluetoothMap(Context context, ServiceListener listener,
            BluetoothAdapter adapter) {
        if (DBG) Log.d(TAG, "Create BluetoothMap proxy object");
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mProfileConnector.connect(context, listener);
        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");
    }

    protected void finalize() {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothMap will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     *
     * @hide
     */
    @SystemApi
    public void close() {
        if (VDBG) log("close()");
        mProfileConnector.disconnect();
    }

    private IBluetoothMap getService() {
        return mProfileConnector.getService();
    }

    /**
     * Get the current state of the BluetoothMap service.
     *
     * @return One of the STATE_ return codes, or STATE_ERROR if this proxy object is currently not
     * connected to the Map service.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getState() {
        if (VDBG) log("getState()");
        final IBluetoothMap service = getService();
        if (service != null) {
            try {
                return service.getState(mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        }
        return BluetoothMap.STATE_ERROR;
    }

    /**
     * Get the currently connected remote Bluetooth device (PCE).
     *
     * @return The remote Bluetooth device, or null if not in connected or connecting state, or if
     * this proxy object is not connected to the Map service.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public BluetoothDevice getClient() {
        if (VDBG) log("getClient()");
        final IBluetoothMap service = getService();
        if (service != null) {
            try {
                return Attributable.setAttributionSource(
                        service.getClient(mAttributionSource), mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        }
        return null;
    }

    /**
     * Returns true if the specified Bluetooth device is connected.
     * Returns false if not connected, or if this proxy object is not
     * currently connected to the Map service.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isConnected(BluetoothDevice device) {
        if (VDBG) log("isConnected(" + device + ")");
        final IBluetoothMap service = getService();
        if (service != null) {
            try {
                return service.isConnected(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Initiate connection. Initiation of outgoing connections is not
     * supported for MAP server.
     *
     * @hide
     */
    @RequiresNoPermission
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")" + "not supported for MAPS");
        return false;
    }

    /**
     * Initiate disconnect.
     *
     * @param device Remote Bluetooth Device
     * @return false on error, true otherwise
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Check class bits for possible Map support.
     * This is a simple heuristic that tries to guess if a device with the
     * given class bits might support Map. It is not accurate for all
     * devices. It tries to err on the side of false positives.
     *
     * @return True if this device might support Map.
     *
     * @hide
     */
    public static boolean doesClassMatchSink(BluetoothClass btClass) {
        // TODO optimize the rule
        switch (btClass.getDeviceClass()) {
            case BluetoothClass.Device.COMPUTER_DESKTOP:
            case BluetoothClass.Device.COMPUTER_LAPTOP:
            case BluetoothClass.Device.COMPUTER_SERVER:
            case BluetoothClass.Device.COMPUTER_UNCATEGORIZED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get the list of connected devices. Currently at most one.
     *
     * @return list of connected devices
     *
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (DBG) log("getConnectedDevices()");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getConnectedDevices(mAttributionSource), mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * Get the list of devices matching specified states. Currently at most one.
     *
     * @return list of matching devices
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) log("getDevicesMatchingStates()");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getDevicesMatchingConnectionStates(states, mAttributionSource),
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * Get connection state of device
     *
     * @return device connection state
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) log("getConnectionState(" + device + ")");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} or {@link #PRIORITY_OFF},
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        return setConnectionPolicy(device, BluetoothAdapter.priorityToConnectionPolicy(priority));
    }

    /**
     * Set connection policy of the profile
     *
     * <p> The device should already be paired.
     * Connection policy can be one of {@link #CONNECTION_POLICY_ALLOWED},
     * {@link #CONNECTION_POLICY_FORBIDDEN}, {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setConnectionPolicy(@NonNull BluetoothDevice device,
            @ConnectionPolicy int connectionPolicy) {
        if (DBG) log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                    && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                return false;
            }
            try {
                return service.setConnectionPolicy(device, connectionPolicy, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_OFF}, {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        return BluetoothAdapter.connectionPolicyToPriority(getConnectionPolicy(device));
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link #CONNECTION_POLICY_ALLOWED}, {@link #CONNECTION_POLICY_FORBIDDEN},
     * {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        final IBluetoothMap service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionPolicy(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private boolean isEnabled() {
        return mAdapter.isEnabled();
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }
}
