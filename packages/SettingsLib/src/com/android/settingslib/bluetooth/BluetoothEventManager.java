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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * BluetoothEventManager receives broadcasts and callbacks from the Bluetooth
 * API and dispatches the event on the UI thread to the right class in the
 * Settings.
 */
public class BluetoothEventManager {
    private static final String TAG = "BluetoothEventManager";

    private final LocalBluetoothAdapter mLocalAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private LocalBluetoothProfileManager mProfileManager;
    private final IntentFilter mAdapterIntentFilter, mProfileIntentFilter;
    private final Map<String, Handler> mHandlerMap;
    private Context mContext;

    private final Collection<BluetoothCallback> mCallbacks =
            new ArrayList<BluetoothCallback>();

    private android.os.Handler mReceiverHandler;

    interface Handler {
        void onReceive(Context context, Intent intent, BluetoothDevice device);
    }

    private void addHandler(String action, Handler handler) {
        mHandlerMap.put(action, handler);
        mAdapterIntentFilter.addAction(action);
    }

    void addProfileHandler(String action, Handler handler) {
        mHandlerMap.put(action, handler);
        mProfileIntentFilter.addAction(action);
    }

    // Set profile manager after construction due to circular dependency
    void setProfileManager(LocalBluetoothProfileManager manager) {
        mProfileManager = manager;
    }

    BluetoothEventManager(LocalBluetoothAdapter adapter,
            CachedBluetoothDeviceManager deviceManager, Context context) {
        mLocalAdapter = adapter;
        mDeviceManager = deviceManager;
        mAdapterIntentFilter = new IntentFilter();
        mProfileIntentFilter = new IntentFilter();
        mHandlerMap = new HashMap<String, Handler>();
        mContext = context;

        // Bluetooth on/off broadcasts
        addHandler(BluetoothAdapter.ACTION_STATE_CHANGED, new AdapterStateChangedHandler());
        // Generic connected/not broadcast
        addHandler(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                new ConnectionStateChangedHandler());

        // Discovery broadcasts
        addHandler(BluetoothAdapter.ACTION_DISCOVERY_STARTED, new ScanningStateChangedHandler(true));
        addHandler(BluetoothAdapter.ACTION_DISCOVERY_FINISHED, new ScanningStateChangedHandler(false));
        addHandler(BluetoothDevice.ACTION_FOUND, new DeviceFoundHandler());
        addHandler(BluetoothDevice.ACTION_DISAPPEARED, new DeviceDisappearedHandler());
        addHandler(BluetoothDevice.ACTION_NAME_CHANGED, new NameChangedHandler());
        addHandler(BluetoothDevice.ACTION_ALIAS_CHANGED, new NameChangedHandler());

        // Pairing broadcasts
        addHandler(BluetoothDevice.ACTION_BOND_STATE_CHANGED, new BondStateChangedHandler());

        // Fine-grained state broadcasts
        addHandler(BluetoothDevice.ACTION_CLASS_CHANGED, new ClassChangedHandler());
        addHandler(BluetoothDevice.ACTION_UUID, new UuidChangedHandler());
        addHandler(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED, new BatteryLevelChangedHandler());

        // Dock event broadcasts
        addHandler(Intent.ACTION_DOCK_EVENT, new DockEventHandler());

        mContext.registerReceiver(mBroadcastReceiver, mAdapterIntentFilter, null, mReceiverHandler);
    }

    void registerProfileIntentReceiver() {
        mContext.registerReceiver(mBroadcastReceiver, mProfileIntentFilter, null, mReceiverHandler);
    }

    public void setReceiverHandler(android.os.Handler handler) {
        mContext.unregisterReceiver(mBroadcastReceiver);
        mReceiverHandler = handler;
        mContext.registerReceiver(mBroadcastReceiver, mAdapterIntentFilter, null, mReceiverHandler);
        registerProfileIntentReceiver();
    }

    /** Register to start receiving callbacks for Bluetooth events. */
    public void registerCallback(BluetoothCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    /** Unregister to stop receiving callbacks for Bluetooth events. */
    public void unregisterCallback(BluetoothCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent
                    .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            Handler handler = mHandlerMap.get(action);
            if (handler != null) {
                handler.onReceive(context, intent, device);
            }
        }
    };

    private class AdapterStateChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.ERROR);
            // update local profiles and get paired devices
            mLocalAdapter.setBluetoothStateInt(state);
            // send callback to update UI and possibly start scanning
            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onBluetoothStateChanged(state);
                }
            }
            // Inform CachedDeviceManager that the adapter state has changed
            mDeviceManager.onBluetoothStateChanged(state);
        }
    }

    private class ScanningStateChangedHandler implements Handler {
        private final boolean mStarted;

        ScanningStateChangedHandler(boolean started) {
            mStarted = started;
        }
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onScanningStateChanged(mStarted);
                }
            }
            mDeviceManager.onScanningStateChanged(mStarted);
        }
    }

    private class DeviceFoundHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            BluetoothClass btClass = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
            String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
            // TODO Pick up UUID. They should be available for 2.1 devices.
            // Skip for now, there's a bluez problem and we are not getting uuids even for 2.1.
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                cachedDevice = mDeviceManager.addDevice(mLocalAdapter, mProfileManager, device);
                Log.d(TAG, "DeviceFoundHandler created new CachedBluetoothDevice: "
                        + cachedDevice);
            }
            cachedDevice.setRssi(rssi);
            cachedDevice.setBtClass(btClass);
            cachedDevice.setNewName(name);
            cachedDevice.setJustDiscovered(true);
        }
    }

    private class ConnectionStateChangedHandler implements Handler {
        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.ERROR);
            dispatchConnectionStateChanged(cachedDevice, state);
        }
    }

    private void dispatchConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onConnectionStateChanged(cachedDevice, state);
            }
        }
    }

    void dispatchDeviceAdded(CachedBluetoothDevice cachedDevice) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onDeviceAdded(cachedDevice);
            }
        }
    }

    private class DeviceDisappearedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "received ACTION_DISAPPEARED for an unknown device: " + device);
                return;
            }
            if (CachedBluetoothDeviceManager.onDeviceDisappeared(cachedDevice)) {
                synchronized (mCallbacks) {
                    for (BluetoothCallback callback : mCallbacks) {
                        callback.onDeviceDeleted(cachedDevice);
                    }
                }
            }
        }
    }

    private class NameChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onDeviceNameUpdated(device);
        }
    }

    private class BondStateChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            if (device == null) {
                Log.e(TAG, "ACTION_BOND_STATE_CHANGED with no EXTRA_DEVICE");
                return;
            }
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                                               BluetoothDevice.ERROR);
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "CachedBluetoothDevice for device " + device +
                        " not found, calling readPairedDevices().");
                if (readPairedDevices()) {
                    cachedDevice = mDeviceManager.findDevice(device);
                }

                if (cachedDevice == null) {
                    Log.w(TAG, "Got bonding state changed for " + device +
                            ", but we have no record of that device.");

                    cachedDevice = mDeviceManager.addDevice(mLocalAdapter, mProfileManager, device);
                    dispatchDeviceAdded(cachedDevice);
                }
            }

            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onDeviceBondStateChanged(cachedDevice, bondState);
                }
            }
            cachedDevice.onBondingStateChanged(bondState);

            if (bondState == BluetoothDevice.BOND_NONE) {
                int reason = intent.getIntExtra(BluetoothDevice.EXTRA_REASON,
                        BluetoothDevice.ERROR);

                showUnbondMessage(context, cachedDevice.getName(), reason);
            }
        }

        /**
         * Called when we have reached the unbonded state.
         *
         * @param reason one of the error reasons from
         *            BluetoothDevice.UNBOND_REASON_*
         */
        private void showUnbondMessage(Context context, String name, int reason) {
            int errorMsg;

            switch(reason) {
            case BluetoothDevice.UNBOND_REASON_AUTH_FAILED:
                errorMsg = R.string.bluetooth_pairing_pin_error_message;
                break;
            case BluetoothDevice.UNBOND_REASON_AUTH_REJECTED:
                errorMsg = R.string.bluetooth_pairing_rejected_error_message;
                break;
            case BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN:
                errorMsg = R.string.bluetooth_pairing_device_down_error_message;
                break;
            case BluetoothDevice.UNBOND_REASON_DISCOVERY_IN_PROGRESS:
            case BluetoothDevice.UNBOND_REASON_AUTH_TIMEOUT:
            case BluetoothDevice.UNBOND_REASON_REPEATED_ATTEMPTS:
            case BluetoothDevice.UNBOND_REASON_REMOTE_AUTH_CANCELED:
                errorMsg = R.string.bluetooth_pairing_error_message;
                break;
            default:
                Log.w(TAG, "showUnbondMessage: Not displaying any message for reason: " + reason);
                return;
            }
            Utils.showError(context, name, errorMsg);
        }
    }

    private class ClassChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onBtClassChanged(device);
        }
    }

    private class UuidChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onUuidChanged(device);
        }
    }

    private class DockEventHandler implements Handler {
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            // Remove if unpair device upon undocking
            int anythingButUnDocked = Intent.EXTRA_DOCK_STATE_UNDOCKED + 1;
            int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, anythingButUnDocked);
            if (state == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                if (device != null && device.getBondState() == BluetoothDevice.BOND_NONE) {
                    CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
                    if (cachedDevice != null) {
                        cachedDevice.setJustDiscovered(false);
                    }
                }
            }
        }
    }

    private class BatteryLevelChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice != null) {
                cachedDevice.refresh();
            }
        }
    }

    boolean readPairedDevices() {
        Set<BluetoothDevice> bondedDevices = mLocalAdapter.getBondedDevices();
        if (bondedDevices == null) {
            return false;
        }

        boolean deviceAdded = false;
        for (BluetoothDevice device : bondedDevices) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                cachedDevice = mDeviceManager.addDevice(mLocalAdapter, mProfileManager, device);
                dispatchDeviceAdded(cachedDevice);
                deviceAdded = true;
            }
        }

        return deviceAdded;
    }
}
