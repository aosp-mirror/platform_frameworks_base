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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 *
 * Public API for controlling the Bluetooth Headset Service.
 *
 * BluetoothHeadset is a proxy object for controlling the Bluetooth Headset
 * Service.
 *
 * Creating a BluetoothHeadset object will create a binding with the
 * BluetoothHeadset service. Users of this object should call close() when they
 * are finished with the BluetoothHeadset, so that this proxy object can unbind
 * from the service.
 *
 * BlueoothHeadset objects are not guarenteed to be connected to the
 * BluetoothHeadsetService at all times. Calls on this object while not
 * connected to the service will result in default error return values. Even
 * after object construction, there is a short delay (~10ms) before this proxy
 * object is actually connected to the Service.
 *
 * Android only supports one connected Bluetooth Headset at a time.
 *
 * Note that in this context, Headset includes both Bluetooth Headset's and
 * Handsfree devices.
 *
 * @hide
 */
public class BluetoothHeadset {

    private final static String TAG = "BluetoothHeadset";

    private final Context mContext;
    private IBluetoothHeadset mService;

    /** There was an error trying to obtain the state */
    public static final int STATE_ERROR        = -1;
    /** No headset currently connected */
    public static final int STATE_DISCONNECTED = 0;
    /** Connection attempt in progress */
    public static final int STATE_CONNECTING   = 1;
    /** A headset is currently connected */
    public static final int STATE_CONNECTED    = 2;

    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    /** Connection cancelled before completetion. */
    public static final int RESULT_CANCELLED = 2;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = IBluetoothHeadset.Stub.asInterface(service);
            Log.i(TAG, "Proxy object is now connected to Bluetooth Headset Service");
        }
        public void onServiceDisconnected(ComponentName className) {
            mService = null;
        }
    };

    /**
     * Create a BluetoothHeadset proxy object.
     * Remeber to call close() when you are done with this object, so that it
     * can unbind from the BluetoothHeadsetService.
     */
    public BluetoothHeadset(Context context) {
        mContext = context;
        if (!context.bindService(
                new Intent(IBluetoothHeadset.class.getName()), mConnection, 0)) {
            Log.e(TAG, "Could not bind to Bluetooth Headset Service");
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
     * Other public functions of BluetoothHeadset will return default error
     * results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    public synchronized void close() {
        if (mConnection != null) {
            mContext.unbindService(mConnection);
            mConnection = null;
        }
    }

    /**
     * Get the current state of the Bluetooth Headset service.
     * @return One of the STATE_ return codes, or STATE_ERROR if this proxy
     *         object is currently not connected to the Headset service.
     */
    public int getState() {
        if (mService != null) {
            try {
                return mService.getState();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        }
        return BluetoothHeadset.STATE_ERROR;
    }

    /**
     * Get the Bluetooth address of the current headset.
     * @return The Bluetooth address, or null if not in connected or connecting
     *         state, or if this proxy object is not connected to the Headset
     *         service.
     */
    public String getHeadsetAddress() {
        if (mService != null) {
            try {
                return mService.getHeadsetAddress();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        }
        return null;
    }

    /**
     * Request to initiate a connection to a headset.
     * This call does not block. Fails if a headset is already connecting
     * or connected.
     * Will connect to the last connected headset if address is null.
     * @param address The Bluetooth Address to connect to, or null to connect
     *                to the last connected headset.
     * @param callback A callback with onCreateBondingResult() defined, or
     *                 null.
     * @return        False if there was a problem initiating the connection
     *                procedure, and your callback will not be used. True if
     *                the connection procedure was initiated, in which case
     *                your callback is guarenteed to be called.
     */
    public boolean connectHeadset(String address, IBluetoothHeadsetCallback callback) {
        if (mService != null) {
            try {
                return mService.connectHeadset(address, callback);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        }
        return false;
    }

    /**
     * Returns true if the specified headset is connected (does not include
     * connecting). Returns false if not connected, or if this proxy object
     * if not currently connected to the headset service.
     */
    public boolean isConnected(String address) {
        if (mService != null) {
            try {
                return mService.isConnected(address);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        }
        return false;
    }

    /**
     * Disconnects the current headset. Currently this call blocks, it may soon
     * be made asynchornous. Returns false if this proxy object is
     * not currently connected to the Headset service.
     */
    public boolean disconnectHeadset() {
        if (mService != null) {
            try {
                mService.disconnectHeadset();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        }
        return false;
    }
}
