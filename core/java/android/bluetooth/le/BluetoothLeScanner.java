/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.bluetooth.le;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides methods to perform scan related operations for Bluetooth LE devices. An
 * application can scan for a particular type of BLE devices using {@link ScanFilter}. It can also
 * request different types of callbacks for delivering the result.
 * <p>
 * Use {@link BluetoothAdapter#getBluetoothLeScanner()} to get an instance of
 * {@link BluetoothLeScanner}.
 * <p>
 * Note most of the scan methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see ScanFilter
 */
public final class BluetoothLeScanner {

    private static final String TAG = "BluetoothLeScanner";
    private static final boolean DBG = true;

    private final IBluetoothGatt mBluetoothGatt;
    private final Handler mHandler;
    private final Map<ScanCallback, BleScanCallbackWrapper> mLeScanClients;

    /**
     * @hide
     */
    public BluetoothLeScanner(IBluetoothGatt bluetoothGatt) {
        mBluetoothGatt = bluetoothGatt;
        mHandler = new Handler(Looper.getMainLooper());
        mLeScanClients = new HashMap<ScanCallback, BleScanCallbackWrapper>();
    }

    /**
     * Start Bluetooth LE scan. The scan results will be delivered through {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param filters {@link ScanFilter}s for finding exact BLE devices.
     * @param settings Settings for ble scan.
     * @param callback Callback when scan results are delivered.
     * @throws IllegalArgumentException If {@code settings} or {@code callback} is null.
     */
    public void startScan(List<ScanFilter> filters, ScanSettings settings,
            final ScanCallback callback) {
        if (settings == null || callback == null) {
            throw new IllegalArgumentException("settings or callback is null");
        }
        synchronized (mLeScanClients) {
            if (mLeScanClients.containsKey(callback)) {
                postCallbackError(callback, ScanCallback.SCAN_FAILED_ALREADY_STARTED);
                return;
            }
            BleScanCallbackWrapper wrapper = new BleScanCallbackWrapper(mBluetoothGatt, filters,
                    settings, callback);
            try {
                UUID uuid = UUID.randomUUID();
                mBluetoothGatt.registerClient(new ParcelUuid(uuid), wrapper);
                if (wrapper.scanStarted()) {
                    mLeScanClients.put(callback, wrapper);
                } else {
                    postCallbackError(callback,
                            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
                    return;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "GATT service exception when starting scan", e);
                postCallbackError(callback, ScanCallback.SCAN_FAILED_GATT_SERVICE_FAILURE);
            }
        }
    }

    /**
     * Stops an ongoing Bluetooth LE scan.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback
     */
    public void stopScan(ScanCallback callback) {
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.remove(callback);
            if (wrapper == null) {
                return;
            }
            wrapper.stopLeScan();
        }
    }

    /**
     * Returns available storage size for batch scan results. It's recommended not to use batch scan
     * if available storage size is small (less than 1k bytes, for instance).
     *
     * @hide TODO: unhide when batching is supported in stack.
     */
    public int getAvailableBatchStorageSizeBytes() {
        throw new UnsupportedOperationException("not impelemented");
    }

    /**
     * Poll scan results from bluetooth controller. This will return Bluetooth LE scan results
     * batched on bluetooth controller.
     *
     * @param callback Callback of the Bluetooth LE Scan, it has to be the same instance as the one
     *            used to start scan.
     * @param flush Whether to flush the batch scan buffer. Note the other batch scan clients will
     *            get batch scan callback if the batch scan buffer is flushed.
     * @return Batch Scan results.
     * @hide TODO: unhide when batching is supported in stack.
     */
    public List<ScanResult> getBatchScanResults(ScanCallback callback, boolean flush) {
        throw new UnsupportedOperationException("not impelemented");
    }

    /**
     * Bluetooth GATT interface callbacks
     */
    private static class BleScanCallbackWrapper extends IBluetoothGattCallback.Stub {
        private static final int REGISTRATION_CALLBACK_TIMEOUT_SECONDS = 5;

        private final ScanCallback mScanCallback;
        private final List<ScanFilter> mFilters;
        private ScanSettings mSettings;
        private IBluetoothGatt mBluetoothGatt;

        // mLeHandle 0: not registered
        // -1: scan stopped
        // > 0: registered and scan started
        private int mLeHandle;

        public BleScanCallbackWrapper(IBluetoothGatt bluetoothGatt,
                List<ScanFilter> filters, ScanSettings settings,
                ScanCallback scanCallback) {
            mBluetoothGatt = bluetoothGatt;
            mFilters = filters;
            mSettings = settings;
            mScanCallback = scanCallback;
            mLeHandle = 0;
        }

        public boolean scanStarted() {
            synchronized (this) {
                if (mLeHandle == -1) {
                    return false;
                }
                try {
                    wait(REGISTRATION_CALLBACK_TIMEOUT_SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Callback reg wait interrupted: " + e);
                }
            }
            return mLeHandle > 0;
        }

        public void stopLeScan() {
            synchronized (this) {
                if (mLeHandle <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mLeHandle);
                    return;
                }
                try {
                    mBluetoothGatt.stopScan(mLeHandle, false);
                    mBluetoothGatt.unregisterClient(mLeHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop scan and unregister" + e);
                }
                mLeHandle = -1;
                notifyAll();
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        @Override
        public void onClientRegistered(int status, int clientIf) {
            Log.d(TAG, "onClientRegistered() - status=" + status +
                    " clientIf=" + clientIf);

            synchronized (this) {
                if (mLeHandle == -1) {
                    if (DBG)
                        Log.d(TAG, "onClientRegistered LE scan canceled");
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mLeHandle = clientIf;
                    try {
                        mBluetoothGatt.startScanWithFilters(mLeHandle, false, mSettings, mFilters);
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le scan: " + e);
                        mLeHandle = -1;
                    }
                } else {
                    // registration failed
                    mLeHandle = -1;
                }
                notifyAll();
            }
        }

        @Override
        public void onClientConnectionState(int status, int clientIf,
                boolean connected, String address) {
            // no op
        }

        /**
         * Callback reporting an LE scan result.
         *
         * @hide
         */
        @Override
        public void onScanResult(String address, int rssi, byte[] advData) {
            if (DBG)
                Log.d(TAG, "onScanResult() - Device=" + address + " RSSI=" + rssi);

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mLeHandle <= 0)
                    return;
            }
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(
                    address);
            long scanNanos = SystemClock.elapsedRealtimeNanos();
            ScanResult result = new ScanResult(device, advData, rssi,
                    scanNanos);
            mScanCallback.onAdvertisementUpdate(result);
        }

        @Override
        public void onGetService(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid) {
            // no op
        }

        @Override
        public void onGetIncludedService(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int inclSrvcType, int inclSrvcInstId,
                ParcelUuid inclSrvcUuid) {
            // no op
        }

        @Override
        public void onGetCharacteristic(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int charProps) {
            // no op
        }

        @Override
        public void onGetDescriptor(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descUuid) {
            // no op
        }

        @Override
        public void onSearchComplete(String address, int status) {
            // no op
        }

        @Override
        public void onCharacteristicRead(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid, byte[] value) {
            // no op
        }

        @Override
        public void onCharacteristicWrite(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid) {
            // no op
        }

        @Override
        public void onNotify(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                byte[] value) {
            // no op
        }

        @Override
        public void onDescriptorRead(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descrUuid, byte[] value) {
            // no op
        }

        @Override
        public void onDescriptorWrite(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descrUuid) {
            // no op
        }

        @Override
        public void onExecuteWrite(String address, int status) {
            // no op
        }

        @Override
        public void onReadRemoteRssi(String address, int rssi, int status) {
            // no op
        }

        @Override
        public void onAdvertiseStateChange(int advertiseState, int status) {
            // no op
        }

        @Override
        public void onMultiAdvertiseCallback(int status) {
            // no op
        }

        @Override
        public void onConfigureMTU(String address, int mtu, int status) {
            // no op
        }
    }

    private void postCallbackError(final ScanCallback callback, final int errorCode) {
        mHandler.post(new Runnable() {
                @Override
            public void run() {
                callback.onScanFailed(errorCode);
            }
        });
    }
}
