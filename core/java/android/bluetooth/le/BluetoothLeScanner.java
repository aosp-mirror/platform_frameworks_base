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

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallbackWrapper;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides methods to perform scan related operations for Bluetooth LE devices. An
 * application can scan for a particular type of Bluetooth LE devices using {@link ScanFilter}. It
 * can also request different types of callbacks for delivering the result.
 * <p>
 * Use {@link BluetoothAdapter#getBluetoothLeScanner()} to get an instance of
 * {@link BluetoothLeScanner}.
 * <p>
 * <b>Note:</b> Most of the scan methods here require
 * {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
 *
 * @see ScanFilter
 */
public final class BluetoothLeScanner {

    private static final String TAG = "BluetoothLeScanner";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private final IBluetoothManager mBluetoothManager;
    private final Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private final Map<ScanCallback, BleScanCallbackWrapper> mLeScanClients;

    /**
     * Use {@link BluetoothAdapter#getBluetoothLeScanner()} instead.
     *
     * @param bluetoothManager BluetoothManager that conducts overall Bluetooth Management.
     * @hide
     */
    public BluetoothLeScanner(IBluetoothManager bluetoothManager) {
        mBluetoothManager = bluetoothManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = new Handler(Looper.getMainLooper());
        mLeScanClients = new HashMap<ScanCallback, BleScanCallbackWrapper>();
    }

    /**
     * Start Bluetooth LE scan with default parameters and no filters. The scan results will be
     * delivered through {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     * An app must hold
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission
     * in order to get results.
     *
     * @param callback Callback used to deliver scan results.
     * @throws IllegalArgumentException If {@code callback} is null.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public void startScan(final ScanCallback callback) {
        startScan(null, new ScanSettings.Builder().build(), callback);
    }

    /**
     * Start Bluetooth LE scan. The scan results will be delivered through {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     * An app must hold
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission
     * in order to get results.
     *
     * @param filters {@link ScanFilter}s for finding exact BLE devices.
     * @param settings Settings for the scan.
     * @param callback Callback used to deliver scan results.
     * @throws IllegalArgumentException If {@code settings} or {@code callback} is null.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public void startScan(List<ScanFilter> filters, ScanSettings settings,
            final ScanCallback callback) {
        startScan(filters, settings, null, callback, null);
    }

    /**
     * Start Bluetooth LE scan. Same as {@link #startScan(ScanCallback)} but allows the caller to
     * specify on behalf of which application(s) the work is being done.
     *
     * @param workSource {@link WorkSource} identifying the application(s) for which to blame for
     *                   the scan.
     * @param callback Callback used to deliver scan results.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.UPDATE_DEVICE_STATS })
    public void startScanFromSource(final WorkSource workSource, final ScanCallback callback) {
        startScanFromSource(null, new ScanSettings.Builder().build(), workSource, callback);
    }

    /**
     * Start Bluetooth LE scan. Same as {@link #startScan(List, ScanSettings, ScanCallback)} but
     * allows the caller to specify on behalf of which application(s) the work is being done.
     *
     * @param filters {@link ScanFilter}s for finding exact BLE devices.
     * @param settings Settings for the scan.
     * @param workSource {@link WorkSource} identifying the application(s) for which to blame for
     *                   the scan.
     * @param callback Callback used to deliver scan results.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.UPDATE_DEVICE_STATS })
    public void startScanFromSource(List<ScanFilter> filters, ScanSettings settings,
                                    final WorkSource workSource, final ScanCallback callback) {
        startScan(filters, settings, workSource, callback, null);
    }

    private void startScan(List<ScanFilter> filters, ScanSettings settings,
                           final WorkSource workSource, final ScanCallback callback,
                           List<List<ResultStorageDescriptor>> resultStorages) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        if (callback == null) {
            throw new IllegalArgumentException("callback is null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings is null");
        }
        synchronized (mLeScanClients) {
            if (mLeScanClients.containsKey(callback)) {
                postCallbackError(callback, ScanCallback.SCAN_FAILED_ALREADY_STARTED);
                return;
            }
            IBluetoothGatt gatt;
            try {
                gatt = mBluetoothManager.getBluetoothGatt();
            } catch (RemoteException e) {
                gatt = null;
            }
            if (gatt == null) {
                postCallbackError(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                return;
            }
            if (!isSettingsConfigAllowedForScan(settings)) {
                postCallbackError(callback,
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
                return;
            }
            if (!isHardwareResourcesAvailableForScan(settings)) {
                postCallbackError(callback,
                        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
                return;
            }
            if (!isSettingsAndFilterComboAllowed(settings, filters)) {
                postCallbackError(callback,
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
                return;
            }
            BleScanCallbackWrapper wrapper = new BleScanCallbackWrapper(gatt, filters,
                    settings, workSource, callback, resultStorages);
            wrapper.startRegisteration();
        }
    }

    /**
     * Stops an ongoing Bluetooth LE scan.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public void stopScan(ScanCallback callback) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.remove(callback);
            if (wrapper == null) {
                if (DBG) Log.d(TAG, "could not find callback wrapper");
                return;
            }
            wrapper.stopLeScan();
        }
    }

    /**
     * Flush pending batch scan results stored in Bluetooth controller. This will return Bluetooth
     * LE scan results batched on bluetooth controller. Returns immediately, batch scan results data
     * will be delivered through the {@code callback}.
     *
     * @param callback Callback of the Bluetooth LE Scan, it has to be the same instance as the one
     *            used to start scan.
     */
    public void flushPendingScanResults(ScanCallback callback) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null!");
        }
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.get(callback);
            if (wrapper == null) {
                return;
            }
            wrapper.flushPendingBatchResults();
        }
    }

    /**
     * Start truncated scan.
     *
     * @hide
     */
    @SystemApi
    public void startTruncatedScan(List<TruncatedFilter> truncatedFilters, ScanSettings settings,
            final ScanCallback callback) {
        int filterSize = truncatedFilters.size();
        List<ScanFilter> scanFilters = new ArrayList<ScanFilter>(filterSize);
        List<List<ResultStorageDescriptor>> scanStorages =
                new ArrayList<List<ResultStorageDescriptor>>(filterSize);
        for (TruncatedFilter filter : truncatedFilters) {
            scanFilters.add(filter.getFilter());
            scanStorages.add(filter.getStorageDescriptors());
        }
        startScan(scanFilters, settings, null, callback, scanStorages);
    }

    /**
     * Cleans up scan clients. Should be called when bluetooth is down.
     *
     * @hide
     */
    public void cleanup() {
        mLeScanClients.clear();
    }

    /**
     * Bluetooth GATT interface callbacks
     */
    private class BleScanCallbackWrapper extends BluetoothGattCallbackWrapper {
        private static final int REGISTRATION_CALLBACK_TIMEOUT_MILLIS = 2000;

        private final ScanCallback mScanCallback;
        private final List<ScanFilter> mFilters;
        private final WorkSource mWorkSource;
        private ScanSettings mSettings;
        private IBluetoothGatt mBluetoothGatt;
        private List<List<ResultStorageDescriptor>> mResultStorages;

        // mLeHandle 0: not registered
        // -1: scan stopped or registration failed
        // > 0: registered and scan started
        private int mClientIf;

        public BleScanCallbackWrapper(IBluetoothGatt bluetoothGatt,
                List<ScanFilter> filters, ScanSettings settings,
                WorkSource workSource, ScanCallback scanCallback,
                List<List<ResultStorageDescriptor>> resultStorages) {
            mBluetoothGatt = bluetoothGatt;
            mFilters = filters;
            mSettings = settings;
            mWorkSource = workSource;
            mScanCallback = scanCallback;
            mClientIf = 0;
            mResultStorages = resultStorages;
        }

        public void startRegisteration() {
            synchronized (this) {
                // Scan stopped.
                if (mClientIf == -1) return;
                try {
                    UUID uuid = UUID.randomUUID();
                    mBluetoothGatt.registerClient(new ParcelUuid(uuid), this);
                    wait(REGISTRATION_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException | RemoteException e) {
                    Log.e(TAG, "application registeration exception", e);
                    postCallbackError(mScanCallback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                }
                if (mClientIf > 0) {
                    mLeScanClients.put(mScanCallback, this);
                } else {
                    // Registration timed out or got exception, reset clientIf to -1 so no
                    // subsequent operations can proceed.
                    if (mClientIf == 0) mClientIf = -1;
                    postCallbackError(mScanCallback,
                            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
                }
            }
        }

        public void stopLeScan() {
            synchronized (this) {
                if (mClientIf <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mClientIf);
                    return;
                }
                try {
                    mBluetoothGatt.stopScan(mClientIf, false);
                    mBluetoothGatt.unregisterClient(mClientIf);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop scan and unregister", e);
                }
                mClientIf = -1;
            }
        }

        void flushPendingBatchResults() {
            synchronized (this) {
                if (mClientIf <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mClientIf);
                    return;
                }
                try {
                    mBluetoothGatt.flushPendingBatchResults(mClientIf, false);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to get pending scan results", e);
                }
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        @Override
        public void onClientRegistered(int status, int clientIf) {
            Log.d(TAG, "onClientRegistered() - status=" + status +
                    " clientIf=" + clientIf + " mClientIf=" + mClientIf);
            synchronized (this) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        if (mClientIf == -1) {
                            // Registration succeeds after timeout, unregister client.
                            mBluetoothGatt.unregisterClient(clientIf);
                        } else {
                            mClientIf = clientIf;
                            mBluetoothGatt.startScan(mClientIf, false, mSettings, mFilters,
                                    mWorkSource, mResultStorages,
                                    ActivityThread.currentOpPackageName());
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le scan: " + e);
                        mClientIf = -1;
                    }
                } else {
                    // registration failed
                    mClientIf = -1;
                }
                notifyAll();
            }
        }

        /**
         * Callback reporting an LE scan result.
         *
         * @hide
         */
        @Override
        public void onScanResult(final ScanResult scanResult) {
            if (VDBG) Log.d(TAG, "onScanResult() - " + scanResult.toString());

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mClientIf <= 0) return;
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    mScanCallback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
                }
            });

        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    mScanCallback.onBatchScanResults(results);
                }
            });
        }

        @Override
        public void onFoundOrLost(final boolean onFound, final ScanResult scanResult) {
            if (VDBG) {
                Log.d(TAG, "onFoundOrLost() - onFound = " + onFound +
                        " " + scanResult.toString());
            }

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mClientIf <= 0)
                    return;
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                    @Override
                public void run() {
                    if (onFound) {
                        mScanCallback.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH,
                                scanResult);
                    } else {
                        mScanCallback.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST,
                                scanResult);
                    }
                }
            });
        }

        @Override
        public void onScanManagerErrorCallback(final int errorCode) {
            if (VDBG) {
                Log.d(TAG, "onScanManagerErrorCallback() - errorCode = " + errorCode);
            }
            synchronized (this) {
                if (mClientIf <= 0)
                    return;
            }
            postCallbackError(mScanCallback, errorCode);
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

    private boolean isSettingsConfigAllowedForScan(ScanSettings settings) {
        if (mBluetoothAdapter.isOffloadedFilteringSupported()) {
            return true;
        }
        final int callbackType = settings.getCallbackType();
        // Only support regular scan if no offloaded filter support.
        if (callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                && settings.getReportDelayMillis() == 0) {
            return true;
        }
        return false;
    }

    private boolean isSettingsAndFilterComboAllowed(ScanSettings settings,
                        List <ScanFilter> filterList) {
        final int callbackType = settings.getCallbackType();
        // If onlost/onfound is requested, a non-empty filter is expected
        if ((callbackType & (ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST)) != 0) {
            if (filterList == null) {
                return false;
            }
            for (ScanFilter filter : filterList) {
                if (filter.isAllFieldsEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isHardwareResourcesAvailableForScan(ScanSettings settings) {
        final int callbackType = settings.getCallbackType();
        if ((callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                || (callbackType & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            // For onlost/onfound, we required hw support be available
            return (mBluetoothAdapter.isOffloadedFilteringSupported() &&
                    mBluetoothAdapter.isHardwareTrackingFiltersAvailable());
        }
        return true;
    }
}
