/*
 * Copyright (C) 2022 The Android Open Source Project
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


package com.android.server.companion.presence;

import static android.bluetooth.BluetoothAdapter.ACTION_BLE_STATE_CHANGED;
import static android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED;
import static android.bluetooth.BluetoothAdapter.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothAdapter.EXTRA_STATE;
import static android.bluetooth.BluetoothAdapter.nameForState;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_ALREADY_STARTED;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES;
import static android.bluetooth.le.ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER;

import static com.android.server.companion.presence.CompanionDevicePresenceMonitor.DEBUG;
import static com.android.server.companion.utils.Utils.btDeviceToString;

import static java.util.Objects.requireNonNull;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Slog;

import com.android.server.companion.AssociationStore;
import com.android.server.companion.AssociationStore.ChangeType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("LongLogTag")
class BleCompanionDeviceScanner implements AssociationStore.OnChangeListener {
    private static final String TAG = "CDM_BleCompanionDeviceScanner";

    interface Callback {
        void onBleCompanionDeviceFound(int associationId);

        void onBleCompanionDeviceLost(int associationId);
    }

    private final @NonNull AssociationStore mAssociationStore;
    private final @NonNull Callback mCallback;

    // Non-null after init().
    private @Nullable BluetoothAdapter mBtAdapter;
    // Non-null after init() and when BLE is available. Otherwise - null.
    private @Nullable BluetoothLeScanner mBleScanner;
    // Only accessed from the Main thread.
    private boolean mScanning = false;

    BleCompanionDeviceScanner(
            @NonNull AssociationStore associationStore, @NonNull Callback callback) {
        mAssociationStore = associationStore;
        mCallback = callback;
    }

    @MainThread
    void init(@NonNull Context context, @NonNull BluetoothAdapter btAdapter) {
        if (DEBUG) Log.i(TAG, "init()");

        if (mBtAdapter != null) {
            throw new IllegalStateException(getClass().getSimpleName() + " is already initialized");
        }
        mBtAdapter = requireNonNull(btAdapter);

        checkBleState();
        registerBluetoothStateBroadcastReceiver(context);

        mAssociationStore.registerListener(this);
    }

    @MainThread
    final void restartScan() {
        enforceInitialized();

        if (DEBUG) Log.i(TAG , "restartScan()");
        if (mBleScanner == null) {
            if (DEBUG) Log.d(TAG, "  > BLE is not available");
            return;
        }

        stopScanIfNeeded();
        startScan();
    }

    @Override
    public void onAssociationChanged(@ChangeType int changeType, AssociationInfo association) {
        // Simply restart scanning.
        if (Looper.getMainLooper().isCurrentThread()) {
            restartScan();
        } else {
            new Handler(Looper.getMainLooper()).post(this::restartScan);
        }
    }

    @MainThread
    private void checkBleState() {
        enforceInitialized();

        final boolean bleAvailable = mBtAdapter.isLeEnabled();
        if (DEBUG) {
            Log.i(TAG, "checkBleState() bleAvailable=" + bleAvailable);
        }
        if ((bleAvailable && mBleScanner != null) || (!bleAvailable && mBleScanner == null)) {
            // Nothing changed.
            if (DEBUG) Log.i(TAG, "  > BLE status did not change");
            return;
        }

        if (bleAvailable) {
            mBleScanner = mBtAdapter.getBluetoothLeScanner();
            if (mBleScanner == null) {
                // Oops, that's a race condition. Can return.
                return;
            }
            if (DEBUG) Log.i(TAG, "  > BLE is now available");

            startScan();
        } else {
            if (DEBUG) Log.i(TAG, "  > BLE is now unavailable");

            stopScanIfNeeded();
            mBleScanner = null;
        }
    }

    @MainThread
    void startScan() {
        enforceInitialized();

        Slog.i(TAG, "startBleScan()");
        // This method should not be called if scan is already in progress.
        if (mScanning) {
            Slog.w(TAG, "Scan is already in progress.");
            return;
        }

        // Neither should this method be called if the adapter is not available.
        if (mBleScanner == null) {
            Slog.w(TAG, "BLE is not available.");
            return;
        }

        // Collect MAC addresses from all associations.
        final Set<String> macAddresses = new HashSet<>();
        for (AssociationInfo association : mAssociationStore.getAssociations()) {
            if (!association.isNotifyOnDeviceNearby()) continue;

            // Beware that BT stack does not consider low-case MAC addresses valid, while
            // MacAddress.toString() return a low-case String.
            final String macAddress = association.getDeviceMacAddressAsString();
            if (macAddress != null) {
                macAddresses.add(macAddress);
            }
        }
        if (macAddresses.isEmpty()) {
            if (DEBUG) Log.i(TAG, "  > there are no (associated) devices to Scan for.");
            return;
        } else {
            if (DEBUG) {
                Log.d(TAG, "  > addresses=(n=" + macAddresses.size() + ")"
                        + "[" + String.join(", ", macAddresses) + "]");
            }
        }

        final List<ScanFilter> filters = new ArrayList<>(macAddresses.size());
        for (String macAddress : macAddresses) {
            final ScanFilter filter = new ScanFilter.Builder()
                    .setDeviceAddress(macAddress)
                    .build();
            filters.add(filter);
        }

        // BluetoothLeScanner will throw an IllegalStateException if startScan() is called while LE
        // is not enabled.
        if (mBtAdapter.isLeEnabled()) {
            try {
                mBleScanner.startScan(filters, SCAN_SETTINGS, mScanCallback);
                mScanning = true;
            } catch (IllegalStateException e) {
                Slog.w(TAG, "Exception while starting BLE scanning", e);
            }
        } else {
            Slog.w(TAG, "BLE scanning is not turned on");
        }
    }

    void stopScanIfNeeded() {
        enforceInitialized();

        Slog.i(TAG, "stopBleScan()");
        if (!mScanning) {
            if (DEBUG) Log.d(TAG, "  > not scanning.");
            return;
        }
        // mScanCallback is non-null here - it cannot be null when mScanning is true.

        // BluetoothLeScanner will throw an IllegalStateException if stopScan() is called while LE
        // is not enabled.
        if (mBtAdapter.isLeEnabled()) {
            try {
                mBleScanner.stopScan(mScanCallback);
            } catch (IllegalStateException e) {
                Slog.w(TAG, "Exception while stopping BLE scanning", e);
            }
        } else {
            Slog.w(TAG, "BLE scanning is not turned on");
        }

        mScanning = false;
    }

    @MainThread
    private void notifyDeviceFound(@NonNull BluetoothDevice device) {
        if (DEBUG) Log.i(TAG, "notifyDevice_Found()" + btDeviceToString(device));

        final List<AssociationInfo> associations =
                mAssociationStore.getAssociationsByAddress(device.getAddress());
        if (DEBUG) Log.d(TAG, "  > associations=" + Arrays.toString(associations.toArray()));

        for (AssociationInfo association : associations) {
            mCallback.onBleCompanionDeviceFound(association.getId());
        }
    }

    @MainThread
    private void notifyDeviceLost(@NonNull BluetoothDevice device) {
        if (DEBUG) Log.i(TAG, "notifyDevice_Lost()" + btDeviceToString(device));

        final List<AssociationInfo> associations =
                mAssociationStore.getAssociationsByAddress(device.getAddress());
        if (DEBUG) Log.d(TAG, "  > associations=" + Arrays.toString(associations.toArray()));

        for (AssociationInfo association : associations) {
            mCallback.onBleCompanionDeviceLost(association.getId());
        }
    }

    private void registerBluetoothStateBroadcastReceiver(Context context) {
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int prevState = intent.getIntExtra(EXTRA_PREVIOUS_STATE, -1);
                final int state = intent.getIntExtra(EXTRA_STATE, -1);

                if (DEBUG) {
                    // The action is either STATE_CHANGED or BLE_STATE_CHANGED.
                    final String action =
                            intent.getAction().replace("android.bluetooth.adapter.", "bt.");
                    Log.d(TAG, "on(Broadcast)Receive() " + action + ": "
                            + nameForBtState(prevState) + "->" + nameForBtState(state));
                }

                checkBleState();
            }
        };

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STATE_CHANGED);
        filter.addAction(ACTION_BLE_STATE_CHANGED);

        context.registerReceiver(receiver, filter);
    }

    private void enforceInitialized() {
        if (mBtAdapter != null) return;
        throw new IllegalStateException(getClass().getSimpleName() + " is not initialized");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @MainThread
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();

            if (DEBUG) {
                Log.d(TAG, "onScanResult() " + nameForBleScanCallbackType(callbackType)
                        + " device=" + btDeviceToString(device));
                Log.v(TAG, "  > scanResult=" + result);

                final List<AssociationInfo> associations =
                        mAssociationStore.getAssociationsByAddress(device.getAddress());
                Log.v(TAG, "  > associations=" + Arrays.toString(associations.toArray()));
            }

            switch (callbackType) {
                case CALLBACK_TYPE_FIRST_MATCH:
                    notifyDeviceFound(device);
                    break;

                case CALLBACK_TYPE_MATCH_LOST:
                    notifyDeviceLost(device);
                    break;

                default:
                    Slog.wtf(TAG, "Unexpected callback "
                            + nameForBleScanCallbackType(callbackType));
                    break;
            }
        }

        @MainThread
        @Override
        public void onScanFailed(int errorCode) {
            if (DEBUG) Log.w(TAG, "onScanFailed() " + nameForBleScanErrorCode(errorCode));
            mScanning = false;
        }
    };

    private static String nameForBtState(int state) {
        return nameForState(state) + "(" + state + ")";
    }

    private static String nameForBleScanCallbackType(int callbackType) {
        final String name;
        switch (callbackType) {
            case CALLBACK_TYPE_ALL_MATCHES:
                name = "ALL_MATCHES";
                break;
            case CALLBACK_TYPE_FIRST_MATCH:
                name = "FIRST_MATCH";
                break;
            case CALLBACK_TYPE_MATCH_LOST:
                name = "MATCH_LOST";
                break;
            default:
                name = "Unknown";
        }
        return name + "(" + callbackType + ")";
    }

    private static String nameForBleScanErrorCode(int errorCode) {
        final String name;
        switch (errorCode) {
            case SCAN_FAILED_ALREADY_STARTED:
                name = "ALREADY_STARTED";
                break;
            case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                name = "APPLICATION_REGISTRATION_FAILED";
                break;
            case SCAN_FAILED_INTERNAL_ERROR:
                name = "INTERNAL_ERROR";
                break;
            case SCAN_FAILED_FEATURE_UNSUPPORTED:
                name = "FEATURE_UNSUPPORTED";
                break;
            case SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                name = "OUT_OF_HARDWARE_RESOURCES";
                break;
            case SCAN_FAILED_SCANNING_TOO_FREQUENTLY:
                name = "SCANNING_TOO_FREQUENTLY";
                break;
            default:
                name = "Unknown";
        }
        return name + "(" + errorCode + ")";
    }

    private static final ScanSettings SCAN_SETTINGS = new ScanSettings.Builder()
            .setCallbackType(CALLBACK_TYPE_FIRST_MATCH | CALLBACK_TYPE_MATCH_LOST)
            .setScanMode(SCAN_MODE_LOW_POWER)
            .build();
}
