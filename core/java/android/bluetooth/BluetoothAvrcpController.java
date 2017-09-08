/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * This class provides the public APIs to control the Bluetooth AVRCP Controller. It currently
 * supports player information, playback support and track metadata.
 *
 *<p>BluetoothAvrcpController is a proxy object for controlling the Bluetooth AVRCP
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothAvrcpController proxy object.
 *
 * {@hide}
 */
public final class BluetoothAvrcpController implements BluetoothProfile {
    private static final String TAG = "BluetoothAvrcpController";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the AVRCP Controller
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     *   <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     *   <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     *   <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    public static final String ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in player application setting state on AVRCP AG.
     *
     * <p>This intent will have the following extras:
     * <ul>
     *    <li> {@link #EXTRA_PLAYER_SETTING} - {@link BluetoothAvrcpPlayerSettings} containing the
     *    most recent player setting. </li>
     * </ul>
     */
    public static final String ACTION_PLAYER_SETTING =
        "android.bluetooth.avrcp-controller.profile.action.PLAYER_SETTING";

    public static final String EXTRA_PLAYER_SETTING =
            "android.bluetooth.avrcp-controller.profile.extra.PLAYER_SETTING";

    private Context mContext;
    private ServiceListener mServiceListener;
    private volatile IBluetoothAvrcpController mService;
    private BluetoothAdapter mAdapter;

    final private IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
        new IBluetoothStateChangeCallback.Stub() {
            public void onBluetoothStateChange(boolean up) {
                if (DBG) Log.d(TAG, "onBluetoothStateChange: up=" + up);
                if (!up) {
                    if (VDBG) Log.d(TAG,"Unbinding service...");
                    synchronized (mConnection) {
                        try {
                            mService = null;
                            mContext.unbindService(mConnection);
                        } catch (Exception re) {
                            Log.e(TAG,"",re);
                        }
                    }
                } else {
                    synchronized (mConnection) {
                        try {
                            if (mService == null) {
                                if (VDBG) Log.d(TAG,"Binding service...");
                                doBind();
                            }
                        } catch (Exception re) {
                            Log.e(TAG,"",re);
                        }
                    }
                }
            }
      };

    /**
     * Create a BluetoothAvrcpController proxy object for interacting with the local
     * Bluetooth AVRCP service.
     *
     */
    /*package*/ BluetoothAvrcpController(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                Log.e(TAG,"",e);
            }
        }

        doBind();
    }

    boolean doBind() {
        Intent intent = new Intent(IBluetoothAvrcpController.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                android.os.Process.myUserHandle())) {
            Log.e(TAG, "Could not bind to Bluetooth AVRCP Controller Service with " + intent);
            return false;
        }
        return true;
    }

    /*package*/ void close() {
        mServiceListener = null;
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (Exception e) {
                Log.e(TAG,"",e);
            }
        }

        synchronized (mConnection) {
            if (mService != null) {
                try {
                    mService = null;
                    mContext.unbindService(mConnection);
                } catch (Exception re) {
                    Log.e(TAG,"",re);
                }
            }
        }
    }

    public void finalize() {
        close();
    }

    /**
     * {@inheritDoc}
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothAvrcpController service = mService;
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
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothAvrcpController service = mService;
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
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothAvrcpController service = mService;
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

    /**
     * Gets the player application settings.
     *
     * @return the {@link BluetoothAvrcpPlayerSettings} or {@link null} if there is an error.
     */
    public BluetoothAvrcpPlayerSettings getPlayerSettings(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getPlayerSettings");
        BluetoothAvrcpPlayerSettings settings = null;
        final IBluetoothAvrcpController service = mService;
        if (service != null && isEnabled()) {
            try {
                settings = service.getPlayerSettings(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Error talking to BT service in getMetadata() " + e);
                return null;
            }
        }
        return settings;
    }

    /**
     * Sets the player app setting for current player.
     * returns true in case setting is supported by remote, false otherwise
     */
    public boolean setPlayerApplicationSetting(BluetoothAvrcpPlayerSettings plAppSetting) {
        if (DBG) Log.d(TAG, "setPlayerApplicationSetting");
        final IBluetoothAvrcpController service = mService;
        if (service != null && isEnabled()) {
            try {
                return service.setPlayerApplicationSetting(plAppSetting);
            } catch (RemoteException e) {
                Log.e(TAG, "Error talking to BT service in setPlayerApplicationSetting() " + e);
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /*
     * Send Group Navigation Command to Remote.
     * possible keycode values: next_grp, previous_grp defined above
     */
    public void sendGroupNavigationCmd(BluetoothDevice device, int keyCode, int keyState) {
        Log.d(TAG, "sendGroupNavigationCmd dev = " + device + " key " + keyCode + " State = "
                + keyState);
        final IBluetoothAvrcpController service = mService;
        if (service != null && isEnabled()) {
            try {
                service.sendGroupNavigationCmd(device, keyCode, keyState);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Error talking to BT service in sendGroupNavigationCmd()", e);
                return;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            mService = IBluetoothAvrcpController.Stub.asInterface(Binder.allowBlocking(service));
            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(BluetoothProfile.AVRCP_CONTROLLER,
                        BluetoothAvrcpController.this);
            }
        }
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            mService = null;
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(BluetoothProfile.AVRCP_CONTROLLER);
            }
        }
    };

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    private static void log(String msg) {
      Log.d(TAG, msg);
    }
}
