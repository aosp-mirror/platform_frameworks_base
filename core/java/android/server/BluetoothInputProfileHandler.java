/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDeviceProfileState;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfileState;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This handles all the operations on the HID profile.
 * All functions are called by BluetoothService, as Bluetooth Service
 * is the Service handler for the HID profile.
 */
final class BluetoothInputProfileHandler {
    private static final String TAG = "BluetoothInputProfileHandler";
    private static final boolean DBG = true;

    public static BluetoothInputProfileHandler sInstance;
    private Context mContext;
    private BluetoothService mBluetoothService;
    private final HashMap<BluetoothDevice, Integer> mInputDevices;
    private final BluetoothProfileState mHidProfileState;

    private BluetoothInputProfileHandler(Context context, BluetoothService service) {
        mContext = context;
        mBluetoothService = service;
        mInputDevices = new HashMap<BluetoothDevice, Integer>();
        mHidProfileState = new BluetoothProfileState(mContext, BluetoothProfileState.HID);
        mHidProfileState.start();
    }

    static synchronized BluetoothInputProfileHandler getInstance(Context context,
            BluetoothService service) {
        if (sInstance == null) sInstance = new BluetoothInputProfileHandler(context, service);
        return sInstance;
    }

    boolean connectInputDevice(BluetoothDevice device,
                                            BluetoothDeviceProfileState state) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (objectPath == null ||
            getInputDeviceConnectionState(device) != BluetoothInputDevice.STATE_DISCONNECTED ||
            getInputDevicePriority(device) == BluetoothInputDevice.PRIORITY_OFF) {
            return false;
        }
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.CONNECT_HID_OUTGOING;
            msg.obj = state;
            mHidProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    boolean connectInputDeviceInternal(BluetoothDevice device) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        handleInputDeviceStateChange(device, BluetoothInputDevice.STATE_CONNECTING);
        if (!mBluetoothService.connectInputDeviceNative(objectPath)) {
            handleInputDeviceStateChange(device, BluetoothInputDevice.STATE_DISCONNECTED);
            return false;
        }
        return true;
    }

    boolean disconnectInputDevice(BluetoothDevice device,
                                               BluetoothDeviceProfileState state) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (objectPath == null ||
                getInputDeviceConnectionState(device) == BluetoothInputDevice.STATE_DISCONNECTED) {
            return false;
        }
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.DISCONNECT_HID_OUTGOING;
            msg.obj = state;
            mHidProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    boolean disconnectInputDeviceInternal(BluetoothDevice device) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        handleInputDeviceStateChange(device, BluetoothInputDevice.STATE_DISCONNECTING);
        if (!mBluetoothService.disconnectInputDeviceNative(objectPath)) {
            handleInputDeviceStateChange(device, BluetoothInputDevice.STATE_CONNECTED);
            return false;
        }
        return true;
    }

    int getInputDeviceConnectionState(BluetoothDevice device) {
        if (mInputDevices.get(device) == null) {
            return BluetoothInputDevice.STATE_DISCONNECTED;
        }
        return mInputDevices.get(device);
    }

    List<BluetoothDevice> getConnectedInputDevices() {
        List<BluetoothDevice> devices = lookupInputDevicesMatchingStates(
            new int[] {BluetoothInputDevice.STATE_CONNECTED});
        return devices;
    }

    List<BluetoothDevice> getInputDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = lookupInputDevicesMatchingStates(states);
        return devices;
    }

    int getInputDevicePriority(BluetoothDevice device) {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothInputDevicePriorityKey(device.getAddress()),
                BluetoothInputDevice.PRIORITY_UNDEFINED);
    }

    boolean setInputDevicePriority(BluetoothDevice device, int priority) {
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            return false;
        }
        return Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothInputDevicePriorityKey(device.getAddress()),
                priority);
    }

    List<BluetoothDevice> lookupInputDevicesMatchingStates(int[] states) {
        List<BluetoothDevice> inputDevices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mInputDevices.keySet()) {
            int inputDeviceState = getInputDeviceConnectionState(device);
            for (int state : states) {
                if (state == inputDeviceState) {
                    inputDevices.add(device);
                    break;
                }
            }
        }
        return inputDevices;
    }

    private void handleInputDeviceStateChange(BluetoothDevice device, int state) {
        int prevState;
        if (mInputDevices.get(device) == null) {
            prevState = BluetoothInputDevice.STATE_DISCONNECTED;
        } else {
            prevState = mInputDevices.get(device);
        }
        if (prevState == state) return;

        mInputDevices.put(device, state);

        if (getInputDevicePriority(device) >
              BluetoothInputDevice.PRIORITY_OFF &&
            state == BluetoothInputDevice.STATE_CONNECTING ||
            state == BluetoothInputDevice.STATE_CONNECTED) {
            // We have connected or attempting to connect.
            // Bump priority
            setInputDevicePriority(device, BluetoothInputDevice.PRIORITY_AUTO_CONNECT);
        }

        Intent intent = new Intent(BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothInputDevice.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothInputDevice.EXTRA_STATE, state);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent, BluetoothService.BLUETOOTH_PERM);

        debugLog("InputDevice state : device: " + device + " State:" + prevState + "->" + state);
        mBluetoothService.sendConnectionStateChange(device, BluetoothProfile.INPUT_DEVICE, state,
                                                    prevState);
    }

    void handleInputDevicePropertyChange(String address, boolean connected) {
        int state = connected ? BluetoothInputDevice.STATE_CONNECTED :
            BluetoothInputDevice.STATE_DISCONNECTED;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(address);
        handleInputDeviceStateChange(device, state);
    }

    void setInitialInputDevicePriority(BluetoothDevice device, int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED:
                if (getInputDevicePriority(device) == BluetoothInputDevice.PRIORITY_UNDEFINED) {
                    setInputDevicePriority(device, BluetoothInputDevice.PRIORITY_ON);
                }
                break;
            case BluetoothDevice.BOND_NONE:
                setInputDevicePriority(device, BluetoothInputDevice.PRIORITY_UNDEFINED);
                break;
        }
    }

    private static void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
