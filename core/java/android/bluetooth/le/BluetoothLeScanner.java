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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Extra containing a list of ScanResults. It can have one or more results if there was no
     * error. In case of error, {@link #EXTRA_ERROR_CODE} will contain the error code and this
     * extra will not be available.
     */
    public static final String EXTRA_LIST_SCAN_RESULT =
            "android.bluetooth.le.extra.LIST_SCAN_RESULT";

    /**
     * Optional extra indicating the error code, if any. The error code will be one of the
     * SCAN_FAILED_* codes in {@link ScanCallback}.
     */
    public static final String EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE";

    /**
     * Optional extra indicating the callback type, which will be one of
     * CALLBACK_TYPE_* constants in {@link ScanSettings}.
     *
     * @see ScanCallback#onScanResult(int, ScanResult)
     */
    public static final String EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE";

    private final IBluetoothManager mBluetoothManager;
    private final Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private final Map<ScanCallback, BleScanCallbackWrapper> mLeScanClients;
    private final AttributionSource mAttributionSource;

    /**
     * Use {@link BluetoothAdapter#getBluetoothLeScanner()} instead.
     *
     * @param bluetoothManager BluetoothManager that conducts overall Bluetooth Management.
     * @param opPackageName The opPackageName of the context this object was created from
     * @param featureId  The featureId of the context this object was created from
     * @hide
     */
    public BluetoothLeScanner(IBluetoothManager bluetoothManager,
            @NonNull AttributionSource attributionSource) {
        mBluetoothManager = bluetoothManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = new Handler(Looper.getMainLooper());
        mLeScanClients = new HashMap<ScanCallback, BleScanCallbackWrapper>();
        mAttributionSource = attributionSource;
    }

    /**
     * Start Bluetooth LE scan with default parameters and no filters. The scan results will be
     * delivered through {@code callback}. For unfiltered scans, scanning is stopped on screen
     * off to save power. Scanning is resumed when screen is turned on again. To avoid this, use
     * {@link #startScan(List, ScanSettings, ScanCallback)} with desired {@link ScanFilter}.
     * <p>
     * An app must have
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} permission
     * in order to get results. An App targeting Android Q or later must have
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
     * For unfiltered scans, scanning is stopped on screen off to save power. Scanning is
     * resumed when screen is turned on again. To avoid this, do filetered scanning by
     * using proper {@link ScanFilter}.
     * <p>
     * An app must have
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} permission
     * in order to get results. An App targeting Android Q or later must have
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
        startScan(filters, settings, null, callback, /*callbackIntent=*/ null, null);
    }

    /**
     * Start Bluetooth LE scan using a {@link PendingIntent}. The scan results will be delivered via
     * the PendingIntent. Use this method of scanning if your process is not always running and it
     * should be started when scan results are available.
     * <p>
     * An app must have
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} permission
     * in order to get results. An App targeting Android Q or later must have
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission
     * in order to get results.
     * <p>
     * When the PendingIntent is delivered, the Intent passed to the receiver or activity
     * will contain one or more of the extras {@link #EXTRA_CALLBACK_TYPE},
     * {@link #EXTRA_ERROR_CODE} and {@link #EXTRA_LIST_SCAN_RESULT} to indicate the result of
     * the scan.
     *
     * @param filters Optional list of ScanFilters for finding exact BLE devices.
     * @param settings Optional settings for the scan.
     * @param callbackIntent The PendingIntent to deliver the result to.
     * @return Returns 0 for success or an error code from {@link ScanCallback} if the scan request
     * could not be sent.
     * @see #stopScan(PendingIntent)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public int startScan(@Nullable List<ScanFilter> filters, @Nullable ScanSettings settings,
            @NonNull PendingIntent callbackIntent) {
        return startScan(filters,
                settings != null ? settings : new ScanSettings.Builder().build(),
                null, null, callbackIntent, null);
    }

    /**
     * Start Bluetooth LE scan. Same as {@link #startScan(ScanCallback)} but allows the caller to
     * specify on behalf of which application(s) the work is being done.
     *
     * @param workSource {@link WorkSource} identifying the application(s) for which to blame for
     * the scan.
     * @param callback Callback used to deliver scan results.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.UPDATE_DEVICE_STATS})
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
     * the scan.
     * @param callback Callback used to deliver scan results.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.UPDATE_DEVICE_STATS})
    public void startScanFromSource(List<ScanFilter> filters, ScanSettings settings,
            final WorkSource workSource, final ScanCallback callback) {
        startScan(filters, settings, workSource, callback, null, null);
    }

    private int startScan(List<ScanFilter> filters, ScanSettings settings,
            final WorkSource workSource, final ScanCallback callback,
            final PendingIntent callbackIntent,
            List<List<ResultStorageDescriptor>> resultStorages) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        if (callback == null && callbackIntent == null) {
            throw new IllegalArgumentException("callback is null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings is null");
        }
        synchronized (mLeScanClients) {
            if (callback != null && mLeScanClients.containsKey(callback)) {
                return postCallbackErrorOrReturn(callback,
                            ScanCallback.SCAN_FAILED_ALREADY_STARTED);
            }
            IBluetoothGatt gatt;
            try {
                gatt = mBluetoothManager.getBluetoothGatt();
            } catch (RemoteException e) {
                gatt = null;
            }
            if (gatt == null) {
                return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            }
            if (!isSettingsConfigAllowedForScan(settings)) {
                return postCallbackErrorOrReturn(callback,
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            if (!isHardwareResourcesAvailableForScan(settings)) {
                return postCallbackErrorOrReturn(callback,
                        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
            }
            if (!isSettingsAndFilterComboAllowed(settings, filters)) {
                return postCallbackErrorOrReturn(callback,
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            if (callback != null) {
                BleScanCallbackWrapper wrapper = new BleScanCallbackWrapper(gatt, filters,
                        settings, workSource, callback, resultStorages);
                wrapper.startRegistration();
            } else {
                try {
                    gatt.startScanForIntent(callbackIntent, settings, filters, mAttributionSource);
                } catch (RemoteException e) {
                    return ScanCallback.SCAN_FAILED_INTERNAL_ERROR;
                }
            }
        }
        return ScanCallback.NO_ERROR;
    }

    /**
     * Stops an ongoing Bluetooth LE scan.
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
     * Stops an ongoing Bluetooth LE scan started using a PendingIntent. When creating the
     * PendingIntent parameter, please do not use the FLAG_CANCEL_CURRENT flag. Otherwise, the stop
     * scan may have no effect.
     *
     * @param callbackIntent The PendingIntent that was used to start the scan.
     * @see #startScan(List, ScanSettings, PendingIntent)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public void stopScan(PendingIntent callbackIntent) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
        IBluetoothGatt gatt;
        try {
            gatt = mBluetoothManager.getBluetoothGatt();
            gatt.stopScanForIntent(callbackIntent);
        } catch (RemoteException e) {
        }
    }

    /**
     * Flush pending batch scan results stored in Bluetooth controller. This will return Bluetooth
     * LE scan results batched on bluetooth controller. Returns immediately, batch scan results data
     * will be delivered through the {@code callback}.
     *
     * @param callback Callback of the Bluetooth LE Scan, it has to be the same instance as the one
     * used to start scan.
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
        startScan(scanFilters, settings, null, callback, null, scanStorages);
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
    private class BleScanCallbackWrapper extends IScannerCallback.Stub {
        private static final int REGISTRATION_CALLBACK_TIMEOUT_MILLIS = 2000;

        private final ScanCallback mScanCallback;
        private final List<ScanFilter> mFilters;
        private final WorkSource mWorkSource;
        private ScanSettings mSettings;
        private IBluetoothGatt mBluetoothGatt;
        private List<List<ResultStorageDescriptor>> mResultStorages;

        // mLeHandle 0: not registered
        // -2: registration failed because app is scanning to frequently
        // -1: scan stopped or registration failed
        // > 0: registered and scan started
        private int mScannerId;

        public BleScanCallbackWrapper(IBluetoothGatt bluetoothGatt,
                List<ScanFilter> filters, ScanSettings settings,
                WorkSource workSource, ScanCallback scanCallback,
                List<List<ResultStorageDescriptor>> resultStorages) {
            mBluetoothGatt = bluetoothGatt;
            mFilters = filters;
            mSettings = settings;
            mWorkSource = workSource;
            mScanCallback = scanCallback;
            mScannerId = 0;
            mResultStorages = resultStorages;
        }

        public void startRegistration() {
            synchronized (this) {
                // Scan stopped.
                if (mScannerId == -1 || mScannerId == -2) return;
                try {
                    mBluetoothGatt.registerScanner(this, mWorkSource);
                    wait(REGISTRATION_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException | RemoteException e) {
                    Log.e(TAG, "application registeration exception", e);
                    postCallbackError(mScanCallback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                }
                if (mScannerId > 0) {
                    mLeScanClients.put(mScanCallback, this);
                } else {
                    // Registration timed out or got exception, reset RscannerId to -1 so no
                    // subsequent operations can proceed.
                    if (mScannerId == 0) mScannerId = -1;

                    // If scanning too frequently, don't report anything to the app.
                    if (mScannerId == -2) return;

                    postCallbackError(mScanCallback,
                            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
                }
            }
        }

        public void stopLeScan() {
            synchronized (this) {
                if (mScannerId <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mScannerId);
                    return;
                }
                try {
                    mBluetoothGatt.stopScan(mScannerId);
                    mBluetoothGatt.unregisterScanner(mScannerId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop scan and unregister", e);
                }
                mScannerId = -1;
            }
        }

        void flushPendingBatchResults() {
            synchronized (this) {
                if (mScannerId <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mScannerId);
                    return;
                }
                try {
                    mBluetoothGatt.flushPendingBatchResults(mScannerId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to get pending scan results", e);
                }
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        @Override
        public void onScannerRegistered(int status, int scannerId) {
            Log.d(TAG, "onScannerRegistered() - status=" + status
                    + " scannerId=" + scannerId + " mScannerId=" + mScannerId);
            synchronized (this) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        if (mScannerId == -1) {
                            // Registration succeeds after timeout, unregister scanner.
                            mBluetoothGatt.unregisterScanner(scannerId);
                        } else {
                            mScannerId = scannerId;
                            mBluetoothGatt.startScan(mScannerId, mSettings, mFilters,
                                    mResultStorages, mAttributionSource);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le scan: " + e);
                        mScannerId = -1;
                    }
                } else if (status == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                    // applicaiton was scanning too frequently
                    mScannerId = -2;
                } else {
                    // registration failed
                    mScannerId = -1;
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
                if (mScannerId <= 0) return;
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
                Log.d(TAG, "onFoundOrLost() - onFound = " + onFound + " " + scanResult.toString());
            }

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mScannerId <= 0) {
                    return;
                }
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
                if (mScannerId <= 0) {
                    return;
                }
            }
            postCallbackError(mScanCallback, errorCode);
        }
    }

    private int postCallbackErrorOrReturn(final ScanCallback callback, final int errorCode) {
        if (callback == null) {
            return errorCode;
        } else {
            postCallbackError(callback, errorCode);
            return ScanCallback.NO_ERROR;
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
            List<ScanFilter> filterList) {
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
            return (mBluetoothAdapter.isOffloadedFilteringSupported()
                    && mBluetoothAdapter.isHardwareTrackingFiltersAvailable());
        }
        return true;
    }
}
