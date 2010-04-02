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
public final class BluetoothHeadset {

    private static final String TAG = "BluetoothHeadset";
    private static final boolean DBG = false;

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_STATE_CHANGED =
            "android.bluetooth.headset.action.STATE_CHANGED";
    /**
     * TODO(API release): Consider incorporating as new state in
     * HEADSET_STATE_CHANGED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headset.action.AUDIO_STATE_CHANGED";
    public static final String EXTRA_STATE =
            "android.bluetooth.headset.extra.STATE";
    public static final String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.headset.extra.PREVIOUS_STATE";
    public static final String EXTRA_AUDIO_STATE =
            "android.bluetooth.headset.extra.AUDIO_STATE";

    /** Extra to be used with the Headset State change intent.
     * This will be used only when Headset state changes to
     * {@link #STATE_DISCONNECTED} from any previous state.
     * This extra field is optional and will be used when
     * we have deterministic information regarding whether
     * the disconnect was initiated by the remote device or
     * by the local adapter.
     */
    public static final String EXTRA_DISCONNECT_INITIATOR =
            "android.bluetooth.headset.extra.DISCONNECT_INITIATOR";

    /**
     * TODO(API release): Consider incorporating as new state in
     * HEADSET_STATE_CHANGED
     */
    private IBluetoothHeadset mService;
    private final Context mContext;
    private final ServiceListener mServiceListener;

    /** There was an error trying to obtain the state */
    public static final int STATE_ERROR        = -1;
    /** No headset currently connected */
    public static final int STATE_DISCONNECTED = 0;
    /** Connection attempt in progress */
    public static final int STATE_CONNECTING   = 1;
    /** A headset is currently connected */
    public static final int STATE_CONNECTED    = 2;

    /** A SCO audio channel is not established */
    public static final int AUDIO_STATE_DISCONNECTED = 0;
    /** A SCO audio channel is established */
    public static final int AUDIO_STATE_CONNECTED = 1;

    public static final int RESULT_FAILURE = 0;
    public static final int RESULT_SUCCESS = 1;
    /** Connection canceled before completetion. */
    public static final int RESULT_CANCELED = 2;

    /** Values for {@link #EXTRA_DISCONNECT_INITIATOR} */
    public static final int REMOTE_DISCONNECT = 0;
    public static final int LOCAL_DISCONNECT = 1;


    /** Default priority for headsets that  for which we will accept
     * inconing connections and auto-connect */
    public static final int PRIORITY_AUTO_CONNECT = 1000;
    /** Default priority for headsets that  for which we will accept
     * inconing connections but not auto-connect */
    public static final int PRIORITY_ON = 100;
    /** Default priority for headsets that should not be auto-connected
     * and not allow incoming connections. */
    public static final int PRIORITY_OFF = 0;
    /** Default priority when not set or when the device is unpaired */
    public static final int PRIORITY_UNDEFINED = -1;

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
        if (DBG) log("close()");
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
        if (DBG) log("getState()");
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
     * Get the BluetoothDevice for the current headset.
     * @return current headset, or null if not in connected or connecting
     *         state, or if this proxy object is not connected to the Headset
     *         service.
     */
    public BluetoothDevice getCurrentHeadset() {
        if (DBG) log("getCurrentHeadset()");
        if (mService != null) {
            try {
                return mService.getCurrentHeadset();
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
     * Initiates auto-connection if device is null. Tries to connect to all
     * devices with priority greater than PRIORITY_AUTO in descending order.
     * @param device device to connect to, or null to auto-connect last connected
     *               headset
     * @return       false if there was a problem initiating the connection
     *               procedure, and no further HEADSET_STATE_CHANGED intents
     *               will be expected.
     */
    public boolean connectHeadset(BluetoothDevice device) {
        if (DBG) log("connectHeadset(" + device + ")");
        if (mService != null) {
            try {
                if (mService.connectHeadset(device)) {
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
    public boolean isConnected(BluetoothDevice device) {
        if (DBG) log("isConnected(" + device + ")");
        if (mService != null) {
            try {
                return mService.isConnected(device);
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
        if (DBG) log("disconnectHeadset()");
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
        if (DBG) log("startVoiceRecognition()");
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
        if (DBG) log("stopVoiceRecognition()");
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

    /**
     * Set priority of headset.
     * Priority is a non-negative integer. By default paired headsets will have
     * a priority of PRIORITY_AUTO, and unpaired headset PRIORITY_NONE (0).
     * Headsets with priority greater than zero will be auto-connected, and
     * incoming connections will be accepted (if no other headset is
     * connected).
     * Auto-connection occurs at the following events: boot, incoming phone
     * call, outgoing phone call.
     * Headsets with priority equal to zero, or that are unpaired, are not
     * auto-connected.
     * Incoming connections are ignored regardless of priority if there is
     * already a headset connected.
     * @param device paired headset
     * @param priority Integer priority, for example PRIORITY_AUTO or
     *                 PRIORITY_NONE
     * @return true if successful, false if there was some error
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        if (mService != null) {
            try {
                return mService.setPriority(device, priority);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Get priority of headset.
     * @param device headset
     * @return non-negative priority, or negative error code on error
     */
    public int getPriority(BluetoothDevice device) {
        if (DBG) log("getPriority(" + device + ")");
        if (mService != null) {
            try {
                return mService.getPriority(device);
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return -1;
    }

    /**
     * Get battery usage hint for Bluetooth Headset service.
     * This is a monotonically increasing integer. Wraps to 0 at
     * Integer.MAX_INT, and at boot.
     * Current implementation returns the number of AT commands handled since
     * boot. This is a good indicator for spammy headset/handsfree units that
     * can keep the device awake by polling for cellular status updates. As a
     * rule of thumb, each AT command prevents the CPU from sleeping for 500 ms
     * @return monotonically increasing battery usage hint, or a negative error
     *         code on error
     * @hide
     */
    public int getBatteryUsageHint() {
        if (DBG) log("getBatteryUsageHint()");
        if (mService != null) {
            try {
                return mService.getBatteryUsageHint();
            } catch (RemoteException e) {Log.e(TAG, e.toString());}
        } else {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) Log.d(TAG, Log.getStackTraceString(new Throwable()));
        }
        return -1;
    }

    /**
     * Indicates if current platform supports voice dialing over bluetooth SCO.
     * @return true if voice dialing over bluetooth is supported, false otherwise.
     * @hide
     */
    public static boolean isBluetoothVoiceDialingEnabled(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_bluetooth_sco_off_call);
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

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
