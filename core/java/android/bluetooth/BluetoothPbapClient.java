/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth PBAP Client Profile.
 *
 * @hide
 */
public final class BluetoothPbapClient implements BluetoothProfile {

    private static final String TAG = "BluetoothPbapClient";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED";

    private volatile IBluetoothPbapClient mService;
    private final Context mContext;
    private ServiceListener mServiceListener;
    private BluetoothAdapter mAdapter;

    /** There was an error trying to obtain the state */
    public static final int STATE_ERROR = -1;

    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    /** Connection canceled before completion. */
    public static final int RESULT_CANCELED = 2;

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
            new IBluetoothStateChangeCallback.Stub() {
                public void onBluetoothStateChange(boolean up) {
                    if (DBG) {
                        Log.d(TAG, "onBluetoothStateChange: PBAP CLIENT up=" + up);
                    }
                    if (!up) {
                        if (VDBG) {
                            Log.d(TAG, "Unbinding service...");
                        }
                        synchronized (mConnection) {
                            try {
                                mService = null;
                                mContext.unbindService(mConnection);
                            } catch (Exception re) {
                                Log.e(TAG, "", re);
                            }
                        }
                    } else {
                        synchronized (mConnection) {
                            try {
                                if (mService == null) {
                                    if (VDBG) {
                                        Log.d(TAG, "Binding service...");
                                    }
                                    doBind();
                                }
                            } catch (Exception re) {
                                Log.e(TAG, "", re);
                            }
                        }
                    }
                }
            };

    /**
     * Create a BluetoothPbapClient proxy object.
     */
    BluetoothPbapClient(Context context, ServiceListener l) {
        if (DBG) {
            Log.d(TAG, "Create BluetoothPbapClient proxy object");
        }
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        doBind();
    }

    private boolean doBind() {
        Intent intent = new Intent(IBluetoothPbapClient.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                mContext.getUser())) {
            Log.e(TAG, "Could not bind to Bluetooth PBAP Client Service with " + intent);
            return false;
        }
        return true;
    }

    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Close the connection to the backing service.
     * Other public functions of BluetoothPbapClient will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    public synchronized void close() {
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (Exception e) {
                Log.e(TAG, "", e);
            }
        }

        synchronized (mConnection) {
            if (mService != null) {
                try {
                    mService = null;
                    mContext.unbindService(mConnection);
                } catch (Exception re) {
                    Log.e(TAG, "", re);
                }
            }
        }
        mServiceListener = null;
    }

    /**
     * Initiate connection.
     * Upon successful connection to remote PBAP server the Client will
     * attempt to automatically download the users phonebook and call log.
     *
     * @param device a remote device we want connect to
     * @return <code>true</code> if command has been issued successfully; <code>false</code>
     * otherwise;
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) {
            log("connect(" + device + ") for PBAP Client.");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return false;
    }

    /**
     * Initiate disconnect.
     *
     * @param device Remote Bluetooth Device
     * @return false on error, true otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) {
            log("disconnect(" + device + ")" + new Exception());
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                service.disconnect(device);
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return false;
    }

    /**
     * Get the list of connected devices.
     * Currently at most one.
     *
     * @return list of connected devices
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (DBG) {
            log("getConnectedDevices()");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * Get the list of devices matching specified states. Currently at most one.
     *
     * @return list of matching devices
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) {
            log("getDevicesMatchingStates()");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * Get connection state of device
     *
     * @return device connection state
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) {
            log("getConnectionState(" + device + ")");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) {
                log("Proxy object connected");
            }
            mService = IBluetoothPbapClient.Stub.asInterface(Binder.allowBlocking(service));
            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.PBAP_CLIENT,
                        BluetoothPbapClient.this);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            if (DBG) {
                log("Proxy object disconnected");
            }
            mService = null;
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.PBAP_CLIENT);
            }
        }
    };

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private boolean isEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_ON) {
            return true;
        }
        log("Bluetooth is Not enabled");
        return false;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} or
     * {@link #PRIORITY_OFF},
     *
     * @param device Paired bluetooth device
     * @param priority Priority of this profile
     * @return true if priority is set, false on error
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) {
            log("setPriority(" + device + ", " + priority + ")");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            if (priority != BluetoothProfile.PRIORITY_OFF
                    && priority != BluetoothProfile.PRIORITY_ON) {
                return false;
            }
            try {
                return service.setPriority(device, priority);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return false;
    }

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_AUTO_CONNECT}, {@link #PRIORITY_OFF},
     * {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     */
    public int getPriority(BluetoothDevice device) {
        if (VDBG) {
            log("getPriority(" + device + ")");
        }
        final IBluetoothPbapClient service = mService;
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getPriority(device);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                return PRIORITY_OFF;
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
        return PRIORITY_OFF;
    }
}
