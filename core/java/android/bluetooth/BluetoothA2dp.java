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
import android.server.BluetoothA2dpService;
import android.content.Context;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

/**
 * Public API for controlling the Bluetooth A2DP Profile Service.
 *
 * BluetoothA2dp is a proxy object for controlling the Bluetooth A2DP
 * Service via IPC.
 *
 * Creating a BluetoothA2dp object will initiate a binding with the
 * BluetoothHeadset service. Users of this object should call close() when they
 * are finished, so that this proxy object can unbind from the service.
 *
 * Currently the BluetoothA2dp service runs in the system server and this
 * proxy object will be immediately bound to the service on construction.
 *
 * Currently this class provides methods to connect to A2DP audio sinks.
 *
 * @hide
 */
public final class BluetoothA2dp {
    private static final String TAG = "BluetoothA2dp";
    private static final boolean DBG = false;

    /** int extra for ACTION_SINK_STATE_CHANGED */
    public static final String EXTRA_SINK_STATE =
        "android.bluetooth.a2dp.extra.SINK_STATE";
    /** int extra for ACTION_SINK_STATE_CHANGED */
    public static final String EXTRA_PREVIOUS_SINK_STATE =
        "android.bluetooth.a2dp.extra.PREVIOUS_SINK_STATE";

    /** Indicates the state of an A2DP audio sink has changed.
     * This intent will always contain EXTRA_SINK_STATE,
     * EXTRA_PREVIOUS_SINK_STATE and BluetoothDevice.EXTRA_DEVICE
     * extras.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SINK_STATE_CHANGED =
        "android.bluetooth.a2dp.action.SINK_STATE_CHANGED";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING   = 1;
    public static final int STATE_CONNECTED    = 2;
    public static final int STATE_DISCONNECTING = 3;
    /** Playing implies connected */
    public static final int STATE_PLAYING    = 4;

    /** Default priority for a2dp devices that we try to auto-connect
     * and allow incoming connections */
    public static final int PRIORITY_AUTO_CONNECT = 1000;
    /** Default priority for a2dp devices that should allow incoming
     * connections */
    public static final int PRIORITY_ON = 100;
    /** Default priority for a2dp devices that should not allow incoming
     * connections */
    public static final int PRIORITY_OFF = 0;
    /** Default priority when not set or when the device is unpaired */
    public static final int PRIORITY_UNDEFINED = -1;

    private final IBluetoothA2dp mService;
    private final Context mContext;

    /**
     * Create a BluetoothA2dp proxy object for interacting with the local
     * Bluetooth A2DP service.
     * @param c Context
     */
    public BluetoothA2dp(Context c) {
        mContext = c;

        IBinder b = ServiceManager.getService(BluetoothA2dpService.BLUETOOTH_A2DP_SERVICE);
        if (b != null) {
            mService = IBluetoothA2dp.Stub.asInterface(b);
        } else {
            Log.w(TAG, "Bluetooth A2DP service not available!");

            // Instead of throwing an exception which prevents people from going
            // into Wireless settings in the emulator. Let it crash later when it is actually used.
            mService = null;
        }
    }

    /** Initiate a connection to an A2DP sink.
     *  Listen for SINK_STATE_CHANGED_ACTION to find out when the
     *  connection is completed.
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean connectSink(BluetoothDevice device) {
        if (DBG) log("connectSink(" + device + ")");
        try {
            return mService.connectSink(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Initiate disconnect from an A2DP sink.
     *  Listen for SINK_STATE_CHANGED_ACTION to find out when
     *  disconnect is completed.
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean disconnectSink(BluetoothDevice device) {
        if (DBG) log("disconnectSink(" + device + ")");
        try {
            return mService.disconnectSink(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Initiate suspend from an A2DP sink.
     *  Listen for SINK_STATE_CHANGED_ACTION to find out when
     *  suspend is completed.
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean suspendSink(BluetoothDevice device) {
        try {
            return mService.suspendSink(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Initiate resume from an suspended A2DP sink.
     *  Listen for SINK_STATE_CHANGED_ACTION to find out when
     *  resume is completed.
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean resumeSink(BluetoothDevice device) {
        try {
            return mService.resumeSink(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Check if a specified A2DP sink is connected.
     *  @param device Remote BT device.
     *  @return True if connected (or playing), false otherwise and on error.
     *  @hide
     */
    public boolean isSinkConnected(BluetoothDevice device) {
        if (DBG) log("isSinkConnected(" + device + ")");
        int state = getSinkState(device);
        return state == STATE_CONNECTED || state == STATE_PLAYING;
    }

    /** Check if any A2DP sink is connected.
     * @return a unmodifiable set of connected A2DP sinks, or null on error.
     * @hide
     */
    public Set<BluetoothDevice> getConnectedSinks() {
        if (DBG) log("getConnectedSinks()");
        try {
            return Collections.unmodifiableSet(
                    new HashSet<BluetoothDevice>(Arrays.asList(mService.getConnectedSinks())));
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    /** Check if any A2DP sink is in Non Disconnected state
     * i.e playing, connected, connecting, disconnecting.
     * @return a unmodifiable set of connected A2DP sinks, or null on error.
     * @hide
     */
    public Set<BluetoothDevice> getNonDisconnectedSinks() {
        if (DBG) log("getNonDisconnectedSinks()");
        try {
            return Collections.unmodifiableSet(
                    new HashSet<BluetoothDevice>(Arrays.asList(mService.getNonDisconnectedSinks())));
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    /** Get the state of an A2DP sink
     *  @param device Remote BT device.
     *  @return State code, one of STATE_
     *  @hide
     */
    public int getSinkState(BluetoothDevice device) {
        if (DBG) log("getSinkState(" + device + ")");
        try {
            return mService.getSinkState(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return BluetoothA2dp.STATE_DISCONNECTED;
        }
    }

    /**
     * Set priority of a2dp sink.
     * Priority is a non-negative integer. By default paired sinks will have
     * a priority of PRIORITY_AUTO, and unpaired headset PRIORITY_NONE (0).
     * Sinks with priority greater than zero will accept incoming connections
     * (if no sink is currently connected).
     * Priority for unpaired sink must be PRIORITY_NONE.
     * @param device Paired sink
     * @param priority Integer priority, for example PRIORITY_AUTO or
     *                 PRIORITY_NONE
     * @return true if priority is set, false on error
     */
    public boolean setSinkPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setSinkPriority(" + device + ", " + priority + ")");
        try {
            return mService.setSinkPriority(device, priority);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Get priority of a2dp sink.
     * @param device Sink
     * @return non-negative priority, or negative error code on error.
     */
    public int getSinkPriority(BluetoothDevice device) {
        if (DBG) log("getSinkPriority(" + device + ")");
        try {
            return mService.getSinkPriority(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return PRIORITY_OFF;
        }
    }

    /** Helper for converting a state to a string.
     * For debug use only - strings are not internationalized.
     * @hide
     */
    public static String stateToString(int state) {
        switch (state) {
        case STATE_DISCONNECTED:
            return "disconnected";
        case STATE_CONNECTING:
            return "connecting";
        case STATE_CONNECTED:
            return "connected";
        case STATE_DISCONNECTING:
            return "disconnecting";
        case STATE_PLAYING:
            return "playing";
        default:
            return "<unknown state " + state + ">";
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
