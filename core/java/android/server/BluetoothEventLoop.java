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

import android.bluetooth.BluetoothClass.Device;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetoothDeviceCallback;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

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
    private boolean mInterrupted;
    private HashMap<String, IBluetoothDeviceCallback> mCreateBondingCallbacks;
    private HashMap<String, Integer> mPasskeyAgentRequestData;
    private HashMap<String, IBluetoothDeviceCallback> mGetRemoteServiceChannelCallbacks;
    private HashMap<String, Boolean> mDefaultPinData;
    private BluetoothDeviceService mBluetoothService;    
    private Context mContext;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    static { classInitNative(); }
    private static native void classInitNative();

    /* pacakge */ BluetoothEventLoop(Context context, BluetoothDeviceService bluetoothService) {
        mBluetoothService = bluetoothService;
        mContext = context;
        mCreateBondingCallbacks = new HashMap();
        mPasskeyAgentRequestData = new HashMap();
        mGetRemoteServiceChannelCallbacks = new HashMap();
        mDefaultPinData = new HashMap();        
        initializeNativeDataNative();
    }
    private native void initializeNativeDataNative();

    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }
    private native void cleanupNativeDataNative();

    /* pacakge */ HashMap<String, IBluetoothDeviceCallback> getCreateBondingCallbacks() {
        return mCreateBondingCallbacks;
    }
    /* pacakge */ HashMap<String, IBluetoothDeviceCallback> getRemoteServiceChannelCallbacks() {
        return mGetRemoteServiceChannelCallbacks;
    }

    /* pacakge */ HashMap<String, Integer> getPasskeyAgentRequestData() {
        return mPasskeyAgentRequestData;
    }

    private synchronized boolean waitForAndDispatchEvent(int timeout_ms) {
        return waitForAndDispatchEventNative(timeout_ms);
    }
    private native boolean waitForAndDispatchEventNative(int timeout_ms);

    /* package */ synchronized void start() {

        if (mThread != null) {
            // Already running.
            return;
        }
        mThread = new Thread("Bluetooth Event Loop") {
                @Override
                public void run() {
                    try {
                        if (setUpEventLoopNative()) {
                            while (!mInterrupted) {
                                waitForAndDispatchEvent(0);
                                sleep(500);
                            }
                            tearDownEventLoopNative();
                        }
                    } catch (InterruptedException e) { }
                    if (DBG) log("Event Loop thread finished");
                }
            };
        if (DBG) log("Starting Event Loop thread");
        mInterrupted = false;
        mThread.start();
    }
    private native boolean setUpEventLoopNative();
    private native void tearDownEventLoopNative();

    public synchronized void stop() {
        if (mThread != null) {

            mInterrupted = true;

            try {
                mThread.join();
                mThread = null;
            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupted waiting for Event Loop thread to join");
            }
        }
    }

    public synchronized boolean isEventLoopRunning() {
        return mThread != null;
    }

    public void onModeChanged(String mode) {
        Intent intent = new Intent(BluetoothIntent.MODE_CHANGED_ACTION);
        int intMode = BluetoothDevice.MODE_UNKNOWN;
        if (mode.equalsIgnoreCase("off")) {
            intMode = BluetoothDevice.MODE_OFF;
        }
        else if (mode.equalsIgnoreCase("connectable")) {
            intMode = BluetoothDevice.MODE_CONNECTABLE;
        }
        else if (mode.equalsIgnoreCase("discoverable")) {
            intMode = BluetoothDevice.MODE_DISCOVERABLE;
        }
        intent.putExtra(BluetoothIntent.MODE, intMode);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    public void onDiscoveryStarted() {
        mBluetoothService.setIsDiscovering(true);
        Intent intent = new Intent(BluetoothIntent.DISCOVERY_STARTED_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onDiscoveryCompleted() {
        mBluetoothService.setIsDiscovering(false);
        Intent intent = new Intent(BluetoothIntent.DISCOVERY_COMPLETED_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    public void onPairingRequest() {
        Intent intent = new Intent(BluetoothIntent.PAIRING_REQUEST_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
    }
    
    public void onPairingCancel() {
        Intent intent = new Intent(BluetoothIntent.PAIRING_CANCEL_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
    }

    public void onRemoteDeviceFound(String address, int deviceClass, short rssi) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothIntent.CLASS, deviceClass);
        intent.putExtra(BluetoothIntent.RSSI, rssi);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteDeviceDisappeared(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_DISAPPEARED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteClassUpdated(String address, int deviceClass) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_CLASS_UPDATED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothIntent.CLASS, deviceClass);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteDeviceConnected(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteDeviceDisconnectRequested(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteDeviceDisconnected(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_DEVICE_DISCONNECTED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteNameUpdated(String address, String name) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_NAME_UPDATED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothIntent.NAME, name);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteNameFailed(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_NAME_FAILED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteNameChanged(String address, String name) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_NAME_UPDATED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothIntent.NAME, name);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteAliasChanged(String address, String alias) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_ALIAS_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothIntent.ALIAS, alias);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    public void onRemoteAliasCleared(String address) {
        Intent intent = new Intent(BluetoothIntent.REMOTE_ALIAS_CLEARED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private void onCreateBondingResult(String address, boolean result) {
        mBluetoothService.setOutgoingBondingDevAddress(null);
        IBluetoothDeviceCallback callback = mCreateBondingCallbacks.get(address);
        if (callback != null) {
            try {
                callback.onCreateBondingResult(address,
                        result ? BluetoothDevice.RESULT_SUCCESS :
                                 BluetoothDevice.RESULT_FAILURE);
            } catch (RemoteException e) {}
            mCreateBondingCallbacks.remove(address);
        }        
    }
    
    public void onBondingCreated(String address) {
        Intent intent = new Intent(BluetoothIntent.BONDING_CREATED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }
    
    public void onBondingRemoved(String address) {
        Intent intent = new Intent(BluetoothIntent.BONDING_REMOVED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        if (mDefaultPinData.containsKey(address)) {
            mDefaultPinData.remove(address);        
        }     
    }

    public void onNameChanged(String name) {
        Intent intent = new Intent(BluetoothIntent.NAME_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.NAME, name);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    public void onPasskeyAgentRequest(String address, int nativeData) {
        mPasskeyAgentRequestData.put(address, new Integer(nativeData));        
        
        if (address.equals(mBluetoothService.getOutgoingBondingDevAddress())) {
            int btClass = mBluetoothService.getRemoteClass(address);
            int remoteDeviceClass = Device.getDevice(btClass);
            if (remoteDeviceClass == Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                remoteDeviceClass == Device.AUDIO_VIDEO_HANDSFREE ||
                remoteDeviceClass == Device.AUDIO_VIDEO_HEADPHONES ||
                remoteDeviceClass == Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
                remoteDeviceClass == Device.AUDIO_VIDEO_CAR_AUDIO ||
                remoteDeviceClass == Device.AUDIO_VIDEO_HIFI_AUDIO) {
                if (!mDefaultPinData.containsKey(address)) {
                    mDefaultPinData.put(address, false);
                }
                if (!mDefaultPinData.get(address)) {
                    mDefaultPinData.remove(address);
                    mDefaultPinData.put(address, true);

                    mBluetoothService.setPin(address, BluetoothDevice.convertPinToBytes("0000"));
                    return;
                }
            }
        }
        Intent intent = new Intent(BluetoothIntent.PAIRING_REQUEST_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
    }
    
    public void onPasskeyAgentCancel(String address) {
        mPasskeyAgentRequestData.remove(address);
        if (mDefaultPinData.containsKey(address)) {
            mDefaultPinData.remove(address);
            mDefaultPinData.put(address, false);
        }
        Intent intent = new Intent(BluetoothIntent.PAIRING_CANCEL_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
    }
    
    private void onGetRemoteServiceChannelResult(String address, int channel) {
        IBluetoothDeviceCallback callback = mGetRemoteServiceChannelCallbacks.get(address);
        if (callback != null) {
            try {
                callback.onGetRemoteServiceChannelResult(address, channel);
            } catch (RemoteException e) {}
            mGetRemoteServiceChannelCallbacks.remove(address);
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
