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
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 *
 * Public API for controlling the Bluetooth Pbap Service. This includes
 * Bluetooth Phone book Access profile.
 * BluetoothPbap is a proxy object for controlling the Bluetooth Pbap
 * Service via IPC.
 *
 * Creating a BluetoothPbap object will create a binding with the
 * BluetoothPbap service. Users of this object should call close() when they
 * are finished with the BluetoothPbap, so that this proxy object can unbind
 * from the service.
 *
 * This BluetoothPbap object is not immediately bound to the
 * BluetoothPbap service. Use the ServiceListener interface to obtain a
 * notification when it is bound, this is especially important if you wish to
 * immediately call methods on BluetoothPbap after construction.
 *
 * Android only supports one connected Bluetooth Pce at a time.
 *
 * @hide
 */
public class BluetoothPbap implements BluetoothProfile {

    private static final String TAG = "BluetoothPbap";
    private static final boolean DBG = false;

    /**
     * Intent used to broadcast the change in connection state of the PBAP
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link BluetoothProfile#EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link BluetoothProfile#EXTRA_PREVIOUS_STATE}- The previous state of the profile. </li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     * <p>{@link BluetoothProfile#EXTRA_STATE} or {@link BluetoothProfile#EXTRA_PREVIOUS_STATE}
     *  can be any of {@link BluetoothProfile#STATE_DISCONNECTED},
     *  {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_CONNECTED},
     *  {@link BluetoothProfile#STATE_DISCONNECTING}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pbap.profile.action.CONNECTION_STATE_CHANGED";

    private volatile IBluetoothPbap mService;
    private final Context mContext;
    private ServiceListener mServiceListener;
    private BluetoothAdapter mAdapter;

    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    /** Connection canceled before completion. */
    public static final int RESULT_CANCELED = 2;

    /**
     * An interface for notifying Bluetooth PCE IPC clients when they have
     * been connected to the BluetoothPbap service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when this proxy object has been
         * connected to the BluetoothPbap service. Clients must wait for
         * this callback before making IPC calls on the BluetoothPbap
         * service.
         */
        public void onServiceConnected(BluetoothPbap proxy);

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the BluetoothPbap service. Clients must not
         * make IPC calls on the BluetoothPbap service after this callback.
         * This callback will currently only occur if the application hosting
         * the BluetoothPbap service, but may be called more often in future.
         */
        public void onServiceDisconnected();
    }

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
            new IBluetoothStateChangeCallback.Stub() {
                public void onBluetoothStateChange(boolean up) {
                    log("onBluetoothStateChange: up=" + up);
                    if (!up) {
                        doUnbind();
                    } else {
                        doBind();
                    }
                }
            };

    /**
     * Create a BluetoothPbap proxy object.
     */
    public BluetoothPbap(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "", re);
            }
        }
        doBind();
    }

    boolean doBind() {
        synchronized (mConnection) {
            try {
                if (mService == null) {
                    log("Binding service...");
                    Intent intent = new Intent(IBluetoothPbap.class.getName());
                    ComponentName comp = intent.resolveSystemService(
                            mContext.getPackageManager(), 0);
                    intent.setComponent(comp);
                    if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                            UserHandle.CURRENT_OR_SELF)) {
                        Log.e(TAG, "Could not bind to Bluetooth Pbap Service with " + intent);
                        return false;
                    }
                }
            } catch (SecurityException se) {
                Log.e(TAG, "", se);
                return false;
            }
        }
        return true;
    }

    private void doUnbind() {
        synchronized (mConnection) {
            if (mService != null) {
                log("Unbinding service...");
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException ie) {
                    Log.e(TAG, "", ie);
                } finally {
                    mService = null;
                }
            }
        }
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
     * Other public functions of BluetoothPbap will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    public synchronized void close() {
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "", re);
            }
        }
        doUnbind();
        mServiceListener = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        log("getConnectedDevices()");
        final IBluetoothPbap service = mService;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        }
        try {
            return service.getConnectedDevices();
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        log("getConnectionState: device=" + device);
        final IBluetoothPbap service = mService;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        try {
            return service.getConnectionState(device);
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        log("getDevicesMatchingConnectionStates: states=" + Arrays.toString(states));
        final IBluetoothPbap service = mService;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        }
        try {
            return service.getDevicesMatchingConnectionStates(states);
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * Returns true if the specified Bluetooth device is connected (does not
     * include connecting). Returns false if not connected, or if this proxy
     * object is not currently connected to the Pbap service.
     */
    // TODO: This is currently being used by SettingsLib and internal app.
    public boolean isConnected(BluetoothDevice device) {
        return getConnectionState(device) == BluetoothAdapter.STATE_CONNECTED;
    }

    /**
     * Disconnects the current Pbap client (PCE). Currently this call blocks,
     * it may soon be made asynchronous. Returns false if this proxy object is
     * not currently connected to the Pbap service.
     */
    // TODO: This is currently being used by SettingsLib and will be used in the future.
    // TODO: Must specify target device. Implement this in the service.
    @UnsupportedAppUsage
    public boolean disconnect(BluetoothDevice device) {
        log("disconnect()");
        final IBluetoothPbap service = mService;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            return false;
        }
        try {
            service.disconnect(device);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
        return false;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            log("Proxy object connected");
            mService = IBluetoothPbap.Stub.asInterface(service);
            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothPbap.this);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            log("Proxy object disconnected");
            doUnbind();
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected();
            }
        }
    };

    private static void log(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}
