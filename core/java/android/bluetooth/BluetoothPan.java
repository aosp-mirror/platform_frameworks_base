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
import android.content.Context;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public final class BluetoothPan {
    private static final String TAG = "BluetoothPan";
    private static final boolean DBG = false;

    /** int extra for ACTION_PAN_STATE_CHANGED */
    public static final String EXTRA_PAN_STATE =
        "android.bluetooth.pan.extra.STATE";
    /** int extra for ACTION_PAN_STATE_CHANGED */
    public static final String EXTRA_PREVIOUS_PAN_STATE =
        "android.bluetooth.pan.extra.PREVIOUS_STATE";

    /** Indicates the state of an PAN device has changed.
     * This intent will always contain EXTRA_DEVICE_STATE,
     * EXTRA_PREVIOUS_DEVICE_STATE and BluetoothDevice.EXTRA_DEVICE
     * extras.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PAN_STATE_CHANGED =
        "android.bluetooth.pan.action.STATE_CHANGED";

    public static final String NAP_ROLE = "nap";
    public static final String NAP_BRIDGE = "pan1";

    public static final int MAX_CONNECTIONS = 7;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING   = 1;
    public static final int STATE_CONNECTED    = 2;
    public static final int STATE_DISCONNECTING = 3;

    private final IBluetooth mService;
    private final Context mContext;

    /**
     * Create a BluetoothPan proxy object for interacting with the local
     * Bluetooth Pan service.
     * @param c Context
     */
    public BluetoothPan(Context c) {
        mContext = c;

        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_SERVICE);
        if (b != null) {
            mService = IBluetooth.Stub.asInterface(b);
        } else {
            Log.w(TAG, "Bluetooth Service not available!");

            // Instead of throwing an exception which prevents people from going
            // into Wireless settings in the emulator. Let it crash later
            // when it is actually used.
            mService = null;
        }
    }

    /**
     * Initiate a PAN connection.
     *
     * This function returns false on error and true if the connection
     * attempt is being made.
     *
     * Listen for {@link #ACTION_PAN_STATE_CHANGED} to find out when the
     * connection is completed.
     *
     * @param device Remote BT device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        try {
            return mService.connectPanDevice(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Initiate disconnect from PAN.
     *
     * This function return false on error and true if the disconnection
     * attempt is being made.
     *
     * Listen for {@link #ACTION_PAN_STATE_CHANGED} to find out when
     * disconnect is completed.
     *
     * @param device Remote BT device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        try {
            return mService.disconnectPanDevice(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Get the state of a PAN Device.
    *
    * This function returns an int representing the state of the PAN connection
    *
    *  @param device Remote BT device.
    *  @return The current state of the PAN Device
    *  @hide
    */
   public int getPanDeviceState(BluetoothDevice device) {
       if (DBG) log("getPanDeviceState(" + device + ")");
       try {
           return mService.getPanDeviceState(device);
       } catch (RemoteException e) {
           Log.e(TAG, "", e);
           return STATE_DISCONNECTED;
       }
   }

   /** Returns a set of all the connected PAN Devices
   *
   * Does not include devices that are currently connecting or disconnecting
   *
   * @return a unmodifiable set of connected PAN Devices, or null on error.
   * @hide
   */
   public Set<BluetoothDevice> getConnectedDevices() {
      if (DBG) log("getConnectedDevices");
      try {
          return Collections.unmodifiableSet(
                  new HashSet<BluetoothDevice>(
                      Arrays.asList(mService.getConnectedPanDevices())));
      } catch (RemoteException e) {
          Log.e(TAG, "", e);
          return null;
      }
   }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    public void setBluetoothTethering(boolean value, String uuid, String bridge) {
        try {
            mService.setBluetoothTethering(value, uuid, bridge);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    public boolean isTetheringOn() {
        try {
            return mService.isTetheringOn();
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }
}
