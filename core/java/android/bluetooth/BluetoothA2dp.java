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

import java.util.List;

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
 * However this may change in future releases, and error codes such as
 * BluetoothError.ERROR_IPC_NOT_READY will be returned from this API when the
 * proxy object is not yet attached.
 * 
 * Currently this class provides methods to connect to A2DP audio sinks.
 *
 * @hide
 */
public class BluetoothA2dp {
    private static final String TAG = "BluetoothA2dp";

    /** int extra for SINK_STATE_CHANGED_ACTION */
    public static final String SINK_STATE =
        "android.bluetooth.a2dp.intent.SINK_STATE";
    /** int extra for SINK_STATE_CHANGED_ACTION */
    public static final String SINK_PREVIOUS_STATE =
        "android.bluetooth.a2dp.intent.SINK_PREVIOUS_STATE";

    /** Indicates the state of an A2DP audio sink has changed.
     *  This intent will always contain SINK_STATE, SINK_PREVIOUS_STATE and
     *  BluetoothIntent.ADDRESS extras.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SINK_STATE_CHANGED_ACTION =
        "android.bluetooth.a2dp.intent.action.SINK_STATE_CHANGED";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING   = 1;
    public static final int STATE_CONNECTED    = 2;
    public static final int STATE_DISCONNECTING = 3;
    /** Playing implies connected */
    public static final int STATE_PLAYING    = 4;

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
        if (b == null) {
            throw new RuntimeException("Bluetooth A2DP service not available!");
        }
        mService = IBluetoothA2dp.Stub.asInterface(b);
    }

    /** Initiate a connection to an A2DP sink.
     *  Listen for A2DP_SINK_STATE_CHANGED_ACTION to find out when the
     *  connection is completed.
     *  @param address Remote BT address.
     *  @return Result code, negative indicates an immediate error.
     *  @hide
     */
    public int connectSink(String address) {
        try {
            return mService.connectSink(address);
        } catch (RemoteException e) {
            Log.w(TAG, "", e);
            return BluetoothError.ERROR_IPC;
        }
    }

    /** Initiate disconnect from an A2DP sink.
     *  Listen for A2DP_SINK_STATE_CHANGED_ACTION to find out when
     *  disconnect is completed.
     *  @param address Remote BT address.
     *  @return Result code, negative indicates an immediate error.
     *  @hide
     */
    public int disconnectSink(String address) {
        try {
            return mService.disconnectSink(address);
        } catch (RemoteException e) {
            Log.w(TAG, "", e);
            return BluetoothError.ERROR_IPC;
        }
    }

    /** Check if a specified A2DP sink is connected.
     *  @param address Remote BT address.
     *  @return True if connected (or playing), false otherwise and on error.
     *  @hide
     */
    public boolean isSinkConnected(String address) {
        int state = getSinkState(address);
        return state == STATE_CONNECTED || state == STATE_PLAYING;
    }

    /** Check if any A2DP sink is connected.
     * @return a List of connected A2DP sinks, or null on error.
     * @hide
     */
    public List<String> listConnectedSinks() {
        try {
            return mService.listConnectedSinks();
        } catch (RemoteException e) {
            Log.w(TAG, "", e);
            return null;
        }
    }

    /** Get the state of an A2DP sink
     *  @param address Remote BT address.
     *  @return State code, or negative on error
     *  @hide
     */
    public int getSinkState(String address) {
        try {
            return mService.getSinkState(address);
        } catch (RemoteException e) {
            Log.w(TAG, "", e);
            return BluetoothError.ERROR_IPC;
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
}
