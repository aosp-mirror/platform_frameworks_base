/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Public API for controlling the Bluetooth HID (Input Device) Profile
 *
 * BluetoothInputDevice is a proxy object used to make calls to Bluetooth Service
 * which handles the HID profile.
 *
 * Creating a BluetoothInputDevice object will initiate a binding with the
 * Bluetooth service. Users of this object should call close() when they
 * are finished, so that this proxy object can unbind from the service.
 *
 * Currently the Bluetooth service runs in the system server and this
 * proxy object will be immediately bound to the service on construction.
 *
 *  @hide
 */
public final class BluetoothInputDevice {
    private static final String TAG = "BluetoothInputDevice";
    private static final boolean DBG = false;

    /** int extra for ACTION_INPUT_DEVICE_STATE_CHANGED */
    public static final String EXTRA_INPUT_DEVICE_STATE =
        "android.bluetooth.inputdevice.extra.INPUT_DEVICE_STATE";
    /** int extra for ACTION_INPUT_DEVICE_STATE_CHANGED */
    public static final String EXTRA_PREVIOUS_INPUT_DEVICE_STATE =
        "android.bluetooth.inputdevice.extra.PREVIOUS_INPUT_DEVICE_STATE";

    /** Indicates the state of an input device has changed.
     * This intent will always contain EXTRA_INPUT_DEVICE_STATE,
     * EXTRA_PREVIOUS_INPUT_DEVICE_STATE and BluetoothDevice.EXTRA_DEVICE
     * extras.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INPUT_DEVICE_STATE_CHANGED =
        "android.bluetooth.inputdevice.action.INPUT_DEVICE_STATE_CHANGED";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING   = 1;
    public static final int STATE_CONNECTED    = 2;
    public static final int STATE_DISCONNECTING = 3;

    /**
     * Auto connection, incoming and outgoing connection are allowed at this
     * priority level.
     */
    public static final int PRIORITY_AUTO_CONNECT = 1000;
    /**
     * Incoming and outgoing connection are allowed at this priority level
     */
    public static final int PRIORITY_ON = 100;
    /**
     * Connections to the device are not allowed at this priority level.
     */
    public static final int PRIORITY_OFF = 0;
    /**
     * Default priority level when the device is unpaired.
     */
    public static final int PRIORITY_UNDEFINED = -1;

    private final IBluetooth mService;
    private final Context mContext;

    /**
     * Create a BluetoothInputDevice proxy object for interacting with the local
     * Bluetooth Service which handle the HID profile.
     * @param c Context
     */
    public BluetoothInputDevice(Context c) {
        mContext = c;

        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_SERVICE);
        if (b != null) {
            mService = IBluetooth.Stub.asInterface(b);
        } else {
            Log.w(TAG, "Bluetooth Service not available!");

            // Instead of throwing an exception which prevents people from going
            // into Wireless settings in the emulator. Let it crash later when it is actually used.
            mService = null;
        }
    }

    /** Initiate a connection to an Input device.
     *
     *  This function returns false on error and true if the connection
     *  attempt is being made.
     *
     *  Listen for INPUT_DEVICE_STATE_CHANGED_ACTION to find out when the
     *  connection is completed.
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean connectInputDevice(BluetoothDevice device) {
        if (DBG) log("connectInputDevice(" + device + ")");
        try {
            return mService.connectInputDevice(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Initiate disconnect from an Input Device.
     *  This function return false on error and true if the disconnection
     *  attempt is being made.
     *
     *  Listen for INPUT_DEVICE_STATE_CHANGED_ACTION to find out when
     *  disconnect is completed.
     *
     *  @param device Remote BT device.
     *  @return false on immediate error, true otherwise
     *  @hide
     */
    public boolean disconnectInputDevice(BluetoothDevice device) {
        if (DBG) log("disconnectInputDevice(" + device + ")");
        try {
            return mService.disconnectInputDevice(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /** Check if a specified InputDevice is connected.
     *
     *  @param device Remote BT device.
     *  @return True if connected , false otherwise and on error.
     *  @hide
     */
    public boolean isInputDeviceConnected(BluetoothDevice device) {
        if (DBG) log("isInputDeviceConnected(" + device + ")");
        int state = getInputDeviceState(device);
        if (state == STATE_CONNECTED) return true;
        return false;
    }

    /** Check if any Input Device is connected.
     *
     * @return a unmodifiable set of connected Input Devices, or null on error.
     * @hide
     */
    public Set<BluetoothDevice> getConnectedInputDevices() {
        if (DBG) log("getConnectedInputDevices()");
        try {
            return Collections.unmodifiableSet(
                    new HashSet<BluetoothDevice>(
                        Arrays.asList(mService.getConnectedInputDevices())));
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    /** Get the state of an Input Device.
     *
     *  @param device Remote BT device.
     *  @return The current state of the Input Device
     *  @hide
     */
    public int getInputDeviceState(BluetoothDevice device) {
        if (DBG) log("getInputDeviceState(" + device + ")");
        try {
            return mService.getInputDeviceState(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return STATE_DISCONNECTED;
        }
    }

    /**
     * Set priority of an input device.
     *
     * Priority is a non-negative integer. Priority can take the following
     * values:
     * {@link PRIORITY_ON}, {@link PRIORITY_OFF}, {@link PRIORITY_AUTO_CONNECT}
     *
     * @param device Paired device.
     * @param priority Integer priority
     * @return true if priority is set, false on error
     */
    public boolean setInputDevicePriority(BluetoothDevice device, int priority) {
        if (DBG) log("setInputDevicePriority(" + device + ", " + priority + ")");
        try {
            return mService.setInputDevicePriority(device, priority);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Get the priority associated with an Input Device.
     *
     * @param device Input Device
     * @return non-negative priority, or negative error code on error.
     */
    public int getInputDevicePriority(BluetoothDevice device) {
        if (DBG) log("getInputDevicePriority(" + device + ")");
        try {
            return mService.getInputDevicePriority(device);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return PRIORITY_OFF;
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
