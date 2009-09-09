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

package android.server;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.HashMap;
import java.util.UUID;

/**
 * TODO: Move this to
 * java/services/com/android/server/BluetoothEventLoop.java
 * and make the contructor package private again.
 *
 * @hide
 */
class BluetoothEventLoop {
    private static final String TAG = "BluetoothEventLoop";
    private static final boolean DBG = false;

    private int mNativeData;
    private Thread mThread;
    private boolean mStarted;
    private boolean mInterrupted;

    private final HashMap<String, Integer> mPasskeyAgentRequestData;
    private final BluetoothService mBluetoothService;
    private final BluetoothAdapter mAdapter;
    private final Context mContext;

    private static final int EVENT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 1;
    private static final int EVENT_RESTART_BLUETOOTH = 2;

    // The time (in millisecs) to delay the pairing attempt after the first
    // auto pairing attempt fails. We use an exponential delay with
    // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the initial value and
    // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the max value.
    private static final long INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 3000;
    private static final long MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 12000;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY:
                String address = (String)msg.obj;
                if (address != null) {
                    mBluetoothService.createBond(address);
                    return;
                }
                break;
            case EVENT_RESTART_BLUETOOTH:
                mBluetoothService.restart();
                break;
            }
        }
    };

    static { classInitNative(); }
    private static native void classInitNative();

    /* pacakge */ BluetoothEventLoop(Context context, BluetoothAdapter adapter,
            BluetoothService bluetoothService) {
        mBluetoothService = bluetoothService;
        mContext = context;
        mPasskeyAgentRequestData = new HashMap();
        mAdapter = adapter;
        initializeNativeDataNative();
    }

    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }

    /* package */ HashMap<String, Integer> getPasskeyAgentRequestData() {
        return mPasskeyAgentRequestData;
    }

    /* package */ void start() {

        if (!isEventLoopRunningNative()) {
            if (DBG) log("Starting Event Loop thread");
            startEventLoopNative();
        }
    }

    public void stop() {
        if (isEventLoopRunningNative()) {
            if (DBG) log("Stopping Event Loop thread");
            stopEventLoopNative();
        }
    }

    public boolean isEventLoopRunning() {
        return isEventLoopRunningNative();
    }

    private void addDevice(String address, String[] properties) {
        mBluetoothService.addRemoteDeviceProperties(address, properties);
        String rssi = mBluetoothService.getRemoteDeviceProperty(address, "RSSI");
        String classValue = mBluetoothService.getRemoteDeviceProperty(address, "Class");
        String name = mBluetoothService.getRemoteDeviceProperty(address, "Name");
        short rssiValue;
        // For incoming connections, we don't get the RSSI value. Use a default of MIN_VALUE.
        // If we accept the pairing, we will automatically show it at the top of the list.
        if (rssi != null) {
            rssiValue = (short)Integer.valueOf(rssi).intValue();
        } else {
            rssiValue = Short.MIN_VALUE;
        }
        if (classValue != null) {
            Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION);
            intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
            intent.putExtra(BluetoothIntent.CLASS, Integer.valueOf(classValue));
            intent.putExtra(BluetoothIntent.RSSI, rssiValue);
            intent.putExtra(BluetoothIntent.NAME, name);

            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        } else {
            log ("ClassValue: " + classValue + " for remote device: " + address + " is null");
        }
    }

    private void onDeviceFound(String address, String[] properties) {
        if (properties == null) {
            Log.e(TAG, "ERROR: Remote device properties are null");
            return;
        }
        addDevice(address, properties);
    }

    private void onDeviceDisappeared(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_DISAPPEARED_ACTION);
        intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private void onCreatePairedDeviceResult(String address, int result) {
        address = address.toUpperCase();
        if (result == BluetoothError.SUCCESS) {
            mBluetoothService.getBondState().setBondState(address, BluetoothDevice.BOND_BONDED);
            if (mBluetoothService.getBondState().isAutoPairingAttemptsInProgress(address)) {
                mBluetoothService.getBondState().clearPinAttempts(address);
            }
        } else if (result == BluetoothDevice.UNBOND_REASON_AUTH_FAILED &&
                mBluetoothService.getBondState().getAttempt(address) == 1) {
            mBluetoothService.getBondState().addAutoPairingFailure(address);
            pairingAttempt(address, result);
        } else if (result == BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN &&
                mBluetoothService.getBondState().isAutoPairingAttemptsInProgress(address)) {
            pairingAttempt(address, result);
        } else {
            mBluetoothService.getBondState().setBondState(address,
                                                          BluetoothDevice.BOND_NOT_BONDED, result);
            if (mBluetoothService.getBondState().isAutoPairingAttemptsInProgress(address)) {
                mBluetoothService.getBondState().clearPinAttempts(address);
            }
        }
    }

    private void pairingAttempt(String address, int result) {
        // This happens when our initial guess of "0000" as the pass key
        // fails. Try to create the bond again and display the pin dialog
        // to the user. Use back-off while posting the delayed
        // message. The initial value is
        // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY and the max value is
        // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY. If the max value is
        // reached, display an error to the user.
        int attempt = mBluetoothService.getBondState().getAttempt(address);
        if (attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY >
                    MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY) {
            mBluetoothService.getBondState().clearPinAttempts(address);
            mBluetoothService.getBondState().setBondState(address,
                    BluetoothDevice.BOND_NOT_BONDED, result);
            return;
        }

        Message message = mHandler.obtainMessage(EVENT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        message.obj = address;
        boolean postResult =  mHandler.sendMessageDelayed(message,
                                        attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        if (!postResult) {
            mBluetoothService.getBondState().clearPinAttempts(address);
            mBluetoothService.getBondState().setBondState(address,
                    BluetoothDevice.BOND_NOT_BONDED, result);
            return;
        }
        mBluetoothService.getBondState().attempt(address);
    }

    private void onDeviceCreated(String deviceObjectPath) {
        String address = mBluetoothService.getAddressFromObjectPath(deviceObjectPath);
        if (!mBluetoothService.isRemoteDeviceInCache(address)) {
            // Incoming connection, we haven't seen this device, add to cache.
            String[] properties = mBluetoothService.getRemoteDeviceProperties(address);
            if (properties != null) {
                addDevice(address, properties);
            }
        }
        return;
    }

    private void onDeviceRemoved(String deviceObjectPath) {
        String address = mBluetoothService.getAddressFromObjectPath(deviceObjectPath);
        if (address != null)
            mBluetoothService.getBondState().setBondState(address.toUpperCase(),
                    BluetoothDevice.BOND_NOT_BONDED, BluetoothDevice.UNBOND_REASON_REMOVED);
    }

    /*package*/ void onPropertyChanged(String[] propValues) {
        if (mBluetoothService.isAdapterPropertiesEmpty()) {
            // We have got a property change before
            // we filled up our cache.
            mBluetoothService.getAllProperties();
        }
        String name = propValues[0];
        if (name.equals("Name")) {
            Intent intent = new Intent(BluetoothIntent.NAME_CHANGED_ACTION);
            intent.putExtra(BluetoothIntent.NAME, propValues[1]);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            mBluetoothService.setProperty(name, propValues[1]);
        } else if (name.equals("Pairable") || name.equals("Discoverable")) {
            String pairable = name.equals("Pairable") ? propValues[1] :
                mBluetoothService.getProperty("Pairable");
            String discoverable = name.equals("Discoverable") ? propValues[1] :
                mBluetoothService.getProperty("Discoverable");

            // This shouldn't happen, unless Adapter Properties are null.
            if (pairable == null || discoverable == null)
                return;

            int mode = BluetoothService.bluezStringToScanMode(
                    pairable.equals("true"),
                    discoverable.equals("true"));
            if (mode >= 0) {
                Intent intent = new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
                intent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, mode);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            }
            mBluetoothService.setProperty(name, propValues[1]);
        } else if (name.equals("Discovering")) {
            Intent intent;
            if (propValues[1].equals("true")) {
                mBluetoothService.setIsDiscovering(true);
                intent = new Intent(BluetoothIntent.DISCOVERY_STARTED_ACTION);
            } else {
                // Stop the discovery.
                mBluetoothService.cancelDiscovery();
                mBluetoothService.setIsDiscovering(false);
                intent = new Intent(BluetoothIntent.DISCOVERY_COMPLETED_ACTION);
            }
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            mBluetoothService.setProperty(name, propValues[1]);
        } else if (name.equals("Devices")) {
            String value = null;
            int len = Integer.valueOf(propValues[1]);
            if (len > 0) {
                StringBuilder str = new StringBuilder();
                for (int i = 2; i < propValues.length; i++) {
                    str.append(propValues[i]);
                    str.append(",");
                }
                value = str.toString();
            }
            mBluetoothService.setProperty(name, value);
        } else if (name.equals("Powered")) {
            // bluetoothd has restarted, re-read all our properties.
            // Note: bluez only sends this property change when it restarts.
            if (propValues[1].equals("true"))
                onRestartRequired();
        }
    }

    private void onDevicePropertyChanged(String deviceObjectPath, String[] propValues) {
        String name = propValues[0];
        String address = mBluetoothService.getAddressFromObjectPath(deviceObjectPath);
        if (address == null) {
            Log.e(TAG, "onDevicePropertyChanged: Address of the remote device in null");
            return;
        }
        BluetoothDevice device = mAdapter.getRemoteDevice(address);
        if (name.equals("Name")) {
            Intent intent = new Intent(BluetoothIntent.REMOTE_NAME_UPDATED_ACTION);
            intent.putExtra(BluetoothIntent.DEVICE, device);
            intent.putExtra(BluetoothIntent.NAME, propValues[1]);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            mBluetoothService.setRemoteDeviceProperty(address, name, propValues[1]);
        } else if (name.equals("Class")) {
            Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_CLASS_UPDATED_ACTION);
            intent.putExtra(BluetoothIntent.DEVICE, device);
            intent.putExtra(BluetoothIntent.CLASS, propValues[1]);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            mBluetoothService.setRemoteDeviceProperty(address, name, propValues[1]);
        } else if (name.equals("Connected")) {
            Intent intent = null;
            if (propValues[1].equals("true")) {
                intent = new Intent(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION);
            } else {
                intent = new Intent(BluetoothIntent.REMOTE_DEVICE_DISCONNECTED_ACTION);
            }
            intent.putExtra(BluetoothIntent.DEVICE, device);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            mBluetoothService.setRemoteDeviceProperty(address, name, propValues[1]);
        } else if (name.equals("UUIDs")) {
            String uuid = null;
            int len = Integer.valueOf(propValues[1]);
            if (len > 0) {
                StringBuilder str = new StringBuilder();
                for (int i = 2; i < propValues.length; i++) {
                    str.append(propValues[i]);
                    str.append(",");
                }
                uuid = str.toString();
            }
            mBluetoothService.setRemoteDeviceProperty(address, name, uuid);
        } else if (name.equals("Paired")) {
            if (propValues[1].equals("true")) {
                mBluetoothService.getBondState().setBondState(address, BluetoothDevice.BOND_BONDED);
            } else {
                mBluetoothService.getBondState().setBondState(address,
                        BluetoothDevice.BOND_NOT_BONDED);
                mBluetoothService.setRemoteDeviceProperty(address, "Trusted", "false");
            }
        } else if (name.equals("Trusted")) {
            if (DBG)
                log("set trust state succeded, value is  " + propValues[1]);
            mBluetoothService.setRemoteDeviceProperty(address, name, propValues[1]);
        }
    }

    private String checkPairingRequestAndGetAddress(String objectPath, int nativeData) {
        String address = mBluetoothService.getAddressFromObjectPath(objectPath);
        if (address == null) {
            Log.e(TAG, "Unable to get device address in checkPairingRequestAndGetAddress, " +
                  "returning null");
            return null;
        }
        address = address.toUpperCase();
        mPasskeyAgentRequestData.put(address, new Integer(nativeData));

        if (mBluetoothService.getBluetoothState() == BluetoothAdapter.STATE_TURNING_OFF) {
            // shutdown path
            mBluetoothService.cancelPairingUserInput(address);
            return null;
        }
        return address;
    }

    private void onRequestConfirmation(String objectPath, int passkey, int nativeData) {
        String address = checkPairingRequestAndGetAddress(objectPath, nativeData);
        if (address == null) return;

        Intent intent = new Intent(BluetoothIntent.PAIRING_REQUEST_ACTION);
        intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
        intent.putExtra(BluetoothIntent.PASSKEY, passkey);
        intent.putExtra(BluetoothIntent.PAIRING_VARIANT,
                BluetoothDevice.PAIRING_VARIANT_CONFIRMATION);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        return;
    }

    private void onRequestPasskey(String objectPath, int nativeData) {
        String address = checkPairingRequestAndGetAddress(objectPath, nativeData);
        if (address == null) return;

        Intent intent = new Intent(BluetoothIntent.PAIRING_REQUEST_ACTION);
        intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
        intent.putExtra(BluetoothIntent.PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PASSKEY);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        return;
    }

    private void onRequestPinCode(String objectPath, int nativeData) {
        String address = checkPairingRequestAndGetAddress(objectPath, nativeData);
        if (address == null) return;

        if (mBluetoothService.getBondState().getBondState(address) ==
                BluetoothDevice.BOND_BONDING) {
            // we initiated the bonding
            int btClass = mBluetoothService.getRemoteClass(address);

            // try 0000 once if the device looks dumb
            switch (BluetoothClass.Device.getDevice(btClass)) {
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                if (!mBluetoothService.getBondState().hasAutoPairingFailed(address) &&
                    !mBluetoothService.getBondState().isAutoPairingBlacklisted(address)) {
                    mBluetoothService.getBondState().attempt(address);
                    mBluetoothService.setPin(address, BluetoothDevice.convertPinToBytes("0000"));
                    return;
                }
           }
        }
        Intent intent = new Intent(BluetoothIntent.PAIRING_REQUEST_ACTION);
        intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
        intent.putExtra(BluetoothIntent.PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PIN);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        return;
    }

    private boolean onAgentAuthorize(String objectPath, String deviceUuid) {
        String address = mBluetoothService.getAddressFromObjectPath(objectPath);
        if (address == null) {
            Log.e(TAG, "Unable to get device address in onAuthAgentAuthorize");
            return false;
        }

        boolean authorized = false;
        UUID uuid = UUID.fromString(deviceUuid);
        // Bluez sends the UUID of the local service being accessed, _not_ the
        // remote service
        if (mBluetoothService.isEnabled() &&
                (BluetoothUuid.isAudioSource(uuid) || BluetoothUuid.isAvrcpTarget(uuid)
                        || BluetoothUuid.isAdvAudioDist(uuid))) {
            BluetoothA2dp a2dp = new BluetoothA2dp(mContext);
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
            authorized = a2dp.getSinkPriority(device) > BluetoothA2dp.PRIORITY_OFF;
            if (authorized) {
                Log.i(TAG, "Allowing incoming A2DP / AVRCP connection from " + address);
            } else {
                Log.i(TAG, "Rejecting incoming A2DP / AVRCP connection from " + address);
            }
        } else {
            Log.i(TAG, "Rejecting incoming " + deviceUuid + " connection from " + address);
        }
        log("onAgentAuthorize(" + objectPath + ", " + deviceUuid + ") = " + authorized);
        return authorized;
    }

    private void onAgentCancel() {
        Intent intent = new Intent(BluetoothIntent.PAIRING_CANCEL_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        return;
    }

    private void onRestartRequired() {
        if (mBluetoothService.isEnabled()) {
            Log.e(TAG, "*** A serious error occured (did bluetoothd crash?) - " +
                       "restarting Bluetooth ***");
            mHandler.sendEmptyMessage(EVENT_RESTART_BLUETOOTH);
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native void initializeNativeDataNative();
    private native void startEventLoopNative();
    private native void stopEventLoopNative();
    private native boolean isEventLoopRunningNative();
    private native void cleanupNativeDataNative();
}
