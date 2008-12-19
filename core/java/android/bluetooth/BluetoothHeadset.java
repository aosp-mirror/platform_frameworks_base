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
 * Public API for controlling the Bluetooth Headset Service. This includes both
 * Bluetooth Headset and Handsfree (v1.5) profiles. The Headset service will
 * attempt a handsfree connection first, and fall back to headset.
 *
 * BluetoothHeadset is a proxy object for controlling the Bluetooth Headset
 * Service via IPC.
 *
 * Creating a BluetoothHeadset object will create a binding with the
 * BluetoothHeadset service. Users of this object should call close() when they
 * are finished with the BluetoothHeadset, so that this proxy object can unbind
 * from the service.
 *
 * This BluetoothHeadset object is not immediately bound to the
 * BluetoothHeadset service. Use the ServiceListener interface to obtain a
 * notification when it is bound, this is especially important if you wish to
 * immediately call methods on BluetootHeadset after construction.
 *
 * Android only supports one connected Bluetooth Headset at a time.
 *
 * @hide
 */
public class BluetoothHeadset {

    private static final String TAG = "BluetoothHeadset";
    private static final boolean DBG = false;

    private IBluetoothHeadset mService;
    private final Context mContext;
    private final ServiceListener mServiceListener;
    private ConnectHeadsetCallback mConnectHeadsetCallback;

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

    /**
     * An interface for notifying BluetoothHeadset IPC clients when they have
     * been connected to the BluetoothHeadset service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when this proxy object has been
         * connected to the BluetoothHeadset service. Clients must wait for
         * this callback before making IPC calls on the BluetoothHeadset
         * service.
         */
        public void onServiceConnected();

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the BluetoothHeadset service. Clients must not
         * make IPC calls on the BluetoothHeadset service after this callback.
         * This callback will currently only occur if the application hosting
         * the BluetoothHeadset service, but may be called more often in future.
         */
        public void onServiceDisconnected();
    }

    /**
     * Interface for connectHeadset() callback.
     * This callback can occur in the Binder thread.
     */
    public interface ConnectHeadsetCallback {
        public void onConnectHeadsetResult(String address, int resultCode);
    }

    /**
     * Create a BluetoothHeadset proxy object.
     */
    public BluetoothHeadset(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        if (!context.bindService(new Intent(IBluetoothHeadset.class.getName()), mConnection, 0)) {
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
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
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
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return null;
    }

    /**
     * Request to initiate a connection to a headset.
     * This call does not block. Fails if a headset is already connecting
     * or connected.
     * Will connect to the last connected headset if address is null.
     * onConnectHeadsetResult() of your ConnectHeadsetCallback will be called
     * on completition.
     * @param address The Bluetooth Address to connect to, or null to connect
     *                to the last connected headset.
     * @param callback Callback on result. Not called if false is returned. Can
     *                be null.
     *                to the last connected headset.
     * @return        False if there was a problem initiating the connection
     *                procedure, and your callback will not be used. True if
     *                the connection procedure was initiated, in which case
     *                your callback is guarenteed to be called.
     */
    public boolean connectHeadset(String address, ConnectHeadsetCallback callback) {
        if (mService != null) {
            try {
                if (mService.connectHeadset(address, mHeadsetCallback)) {
                    mConnectHeadsetCallback = callback;
                    return true;
                }
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
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
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
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
                return true;
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Start BT Voice Recognition mode, and set up Bluetooth audio path.
     * Returns false if there is no headset connected, or if the
     * connected headset does not support voice recognition, or on
     * error.
     */
    public boolean startVoiceRecognition() {
        if (mService != null) {
            try {
                return mService.startVoiceRecognition();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Stop BT Voice Recognition mode, and shut down Bluetooth audio path.
     * Returns false if there is no headset connected, or the connected
     * headset is not in voice recognition mode, or on error.
     */
    public boolean stopVoiceRecognition() {
        if (mService != null) {
            try {
                return mService.stopVoiceRecognition();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            mService = IBluetoothHeadset.Stub.asInterface(service);
            if (mServiceListener != null) {
                mServiceListener.onServiceConnected();
            }
        }
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            mService = null;
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected();
            }
        }
    };

    private IBluetoothHeadsetCallback mHeadsetCallback = new IBluetoothHeadsetCallback.Stub() {
        public void onConnectHeadsetResult(String address, int resultCode) {
            if (mConnectHeadsetCallback != null) {
                mConnectHeadsetCallback.onConnectHeadsetResult(address, resultCode);
            }
        }
    };
}
