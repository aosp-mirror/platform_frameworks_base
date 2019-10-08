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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth Pan
 * Profile.
 *
 * <p>BluetoothPan is a proxy object for controlling the Bluetooth
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothPan proxy object.
 *
 * <p>Each method is protected with its appropriate permission.
 *
 * @hide
 */
public final class BluetoothPan implements BluetoothProfile {
    private static final String TAG = "BluetoothPan";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Pan
     * profile.
     *
     * <p>This intent will have 4 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * <li> {@link #EXTRA_LOCAL_ROLE} - Which local role the remote device is
     * bound to. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p> {@link #EXTRA_LOCAL_ROLE} can be one of {@link #LOCAL_NAP_ROLE} or
     * {@link #LOCAL_PANU_ROLE}
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Extra for {@link #ACTION_CONNECTION_STATE_CHANGED} intent
     * The local role of the PAN profile that the remote device is bound to.
     * It can be one of {@link #LOCAL_NAP_ROLE} or {@link #LOCAL_PANU_ROLE}.
     */
    public static final String EXTRA_LOCAL_ROLE = "android.bluetooth.pan.extra.LOCAL_ROLE";

    public static final int PAN_ROLE_NONE = 0;
    /**
     * The local device is acting as a Network Access Point.
     */
    public static final int LOCAL_NAP_ROLE = 1;
    public static final int REMOTE_NAP_ROLE = 1;

    /**
     * The local device is acting as a PAN User.
     */
    public static final int LOCAL_PANU_ROLE = 2;
    public static final int REMOTE_PANU_ROLE = 2;

    /**
     * Return codes for the connect and disconnect Bluez / Dbus calls.
     *
     * @hide
     */
    public static final int PAN_DISCONNECT_FAILED_NOT_CONNECTED = 1000;

    /**
     * @hide
     */
    public static final int PAN_CONNECT_FAILED_ALREADY_CONNECTED = 1001;

    /**
     * @hide
     */
    public static final int PAN_CONNECT_FAILED_ATTEMPT_FAILED = 1002;

    /**
     * @hide
     */
    public static final int PAN_OPERATION_GENERIC_FAILURE = 1003;

    /**
     * @hide
     */
    public static final int PAN_OPERATION_SUCCESS = 1004;

    private final Context mContext;

    private BluetoothAdapter mAdapter;
    private final BluetoothProfileConnector<IBluetoothPan> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.PAN,
                    "BluetoothPan", IBluetoothPan.class.getName()) {
                @Override
                public IBluetoothPan getServiceInterface(IBinder service) {
                    return IBluetoothPan.Stub.asInterface(Binder.allowBlocking(service));
                }
    };


    /**
     * Create a BluetoothPan proxy object for interacting with the local
     * Bluetooth Service which handles the Pan profile
     */
    @UnsupportedAppUsage
    /*package*/ BluetoothPan(Context context, ServiceListener listener) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;
        mProfileConnector.connect(context, listener);
    }

    @UnsupportedAppUsage
    /*package*/ void close() {
        if (VDBG) log("close()");
        mProfileConnector.disconnect();
    }

    private IBluetoothPan getService() {
        return mProfileConnector.getService();
    }


    protected void finalize() {
        close();
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    @UnsupportedAppUsage
    public void setBluetoothTethering(boolean value) {
        String pkgName = mContext.getOpPackageName();
        if (DBG) log("setBluetoothTethering(" + value + "), calling package:" + pkgName);
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                service.setBluetoothTethering(value, pkgName);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    @UnsupportedAppUsage
    public boolean isTetheringOn() {
        if (VDBG) log("isTetheringOn()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.isTetheringOn();
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    @UnsupportedAppUsage
    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    @UnsupportedAppUsage
    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    @UnsupportedAppUsage
    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
