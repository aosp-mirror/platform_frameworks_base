/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.companiondevicemanager;

import static com.android.companiondevicemanager.Utils.runOnMainThread;
import static com.android.internal.util.ArrayUtils.isEmpty;
import static com.android.internal.util.CollectionUtils.filter;
import static com.android.internal.util.CollectionUtils.find;
import static com.android.internal.util.CollectionUtils.map;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.DeviceFilter;
import android.companion.WifiDeviceFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 *  A CompanionDevice service response for scanning nearby devices
 */
@SuppressLint("LongLogTag")
public class CompanionDeviceDiscoveryService extends Service {
    private static final String TAG = "CDM_CompanionDeviceDiscoveryService";

    private static final String SYS_PROP_DEBUG_TIMEOUT = "debug.cdm.discovery_timeout";
    private static final long TIMEOUT_DEFAULT = 20_000L; // 20 seconds
    private static final long TIMEOUT_MIN = 1_000L; // 1 sec
    private static final long TIMEOUT_MAX = 60_000L; // 1 min

    private static final String ACTION_START_DISCOVERY =
            "com.android.companiondevicemanager.action.START_DISCOVERY";
    private static final String ACTION_STOP_DISCOVERY =
            "com.android.companiondevicemanager.action.ACTION_STOP_DISCOVERY";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";

    private static MutableLiveData<List<DeviceFilterPair<?>>> sScanResultsLiveData =
            new MutableLiveData<>(Collections.emptyList());
    private static MutableLiveData<DiscoveryState> sStateLiveData =
            new MutableLiveData<>(DiscoveryState.NOT_STARTED);
    private static final Object LOCK = new Object();
    @GuardedBy("LOCK")
    private static boolean sDiscoveryStarted = false;

    private BluetoothManager mBtManager;
    private BluetoothAdapter mBtAdapter;
    private WifiManager mWifiManager;
    private BluetoothLeScanner mBleScanner;

    private ScanCallback mBleScanCallback;
    private BluetoothBroadcastReceiver mBtReceiver;
    private WifiBroadcastReceiver mWifiReceiver;

    private final List<DeviceFilterPair<?>> mDevicesFound = new ArrayList<>();

    private final Runnable mTimeoutRunnable = this::timeout;

    private boolean mStopAfterFirstMatch;

    /**
     * A state enum for devices' discovery.
     */
    enum DiscoveryState {
        NOT_STARTED,
        IN_PROGRESS,
        FINISHED_STOPPED,
        FINISHED_TIMEOUT
    }

    static boolean startForRequest(
            @NonNull Context context, @NonNull AssociationRequest associationRequest) {
        synchronized (LOCK) {
            if (sDiscoveryStarted) {
                Slog.e(TAG, "Discovery is already started. Ignoring this request...");
                return false;
            }
        }
        sScanResultsLiveData.setValue(Collections.emptyList());
        requireNonNull(associationRequest);
        final Intent intent = new Intent(context, CompanionDeviceDiscoveryService.class);
        intent.setAction(ACTION_START_DISCOVERY);
        intent.putExtra(EXTRA_ASSOCIATION_REQUEST, associationRequest);

        context.startService(intent);

        return true;
    }

    static void stop(@NonNull Context context) {
        final Intent intent = new Intent(context, CompanionDeviceDiscoveryService.class);
        intent.setAction(ACTION_STOP_DISCOVERY);
        context.startService(intent);
    }

    static LiveData<List<DeviceFilterPair<?>>> getScanResult() {
        return sScanResultsLiveData;
    }

    static LiveData<DiscoveryState> getDiscoveryState() {
        return sStateLiveData;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mBtManager = getSystemService(BluetoothManager.class);
        mBtAdapter = mBtManager.getAdapter();
        mBleScanner = mBtAdapter.getBluetoothLeScanner();
        mWifiManager = getSystemService(WifiManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent.getAction();

        switch (action) {
            case ACTION_START_DISCOVERY:
                final AssociationRequest request =
                        intent.getParcelableExtra(EXTRA_ASSOCIATION_REQUEST);
                startDiscovery(request);
                break;

            case ACTION_STOP_DISCOVERY:
                stopDiscoveryAndFinish(/* timeout */ false);
                break;
        }
        return START_NOT_STICKY;
    }

    @MainThread
    private void startDiscovery(@NonNull AssociationRequest request) {
        Slog.d(TAG, "startDiscovery() request=" + request);
        requireNonNull(request);

        synchronized (LOCK) {
            if (sDiscoveryStarted) {
                Slog.e(TAG, "Discovery is already started. Returning...");
                return;
            }
            sDiscoveryStarted = true;
        }
        mStopAfterFirstMatch = request.isSingleDevice();
        sStateLiveData.setValue(DiscoveryState.IN_PROGRESS);

        final List<DeviceFilter<?>> allFilters = request.getDeviceFilters();
        final List<BluetoothDeviceFilter> btFilters =
                filter(allFilters, BluetoothDeviceFilter.class);
        final List<BluetoothLeDeviceFilter> bleFilters =
                filter(allFilters, BluetoothLeDeviceFilter.class);
        final List<WifiDeviceFilter> wifiFilters = filter(allFilters, WifiDeviceFilter.class);

        // No need to startDiscovery if the device is already bound or connected for
        // singleDevice dialog.
        if (checkBoundDevicesIfNeeded(request, btFilters)) {
            stopSelf();
            return;
        }

        // If no filters are specified: look for everything.
        final boolean forceStartScanningAll = isEmpty(allFilters);
        // Start BT scanning (if needed)
        mBtReceiver = startBtScanningIfNeeded(btFilters, forceStartScanningAll);
        // Start Wi-Fi scanning (if needed)
        mWifiReceiver = startWifiScanningIfNeeded(wifiFilters, forceStartScanningAll);
        // Start BLE scanning (if needed)
        mBleScanCallback = startBleScanningIfNeeded(bleFilters, forceStartScanningAll);

        scheduleTimeout();
    }

    @MainThread
    private void stopDiscoveryAndFinish(boolean timeout) {
        Slog.d(TAG, "stopDiscoveryAndFinish(" + timeout + ")");

        synchronized (LOCK) {
            if (!sDiscoveryStarted) {
                stopSelf();
                return;
            }
        }

        // Stop BT discovery.
        if (mBtReceiver != null) {
            // Cancel discovery.
            mBtAdapter.cancelDiscovery();
            // Unregister receiver.
            unregisterReceiver(mBtReceiver);
            mBtReceiver = null;
        }

        // Stop Wi-Fi scanning.
        if (mWifiReceiver != null) {
            // TODO: need to stop scan?
            // Unregister receiver.
            unregisterReceiver(mWifiReceiver);
            mWifiReceiver = null;
        }

        // Stop BLE scanning.
        if (mBleScanCallback != null) {
            mBleScanner.stopScan(mBleScanCallback);
        }

        Handler.getMain().removeCallbacks(mTimeoutRunnable);

        if (timeout) {
            sStateLiveData.setValue(DiscoveryState.FINISHED_TIMEOUT);
        } else {
            sStateLiveData.setValue(DiscoveryState.FINISHED_STOPPED);
        }

        synchronized (LOCK) {
            sDiscoveryStarted = false;
        }

        // "Finish".
        stopSelf();
    }

    private boolean checkBoundDevicesIfNeeded(@NonNull AssociationRequest request,
            @NonNull List<BluetoothDeviceFilter> btFilters) {
        // If filtering to get single device by mac address, also search in the set of already
        // bonded devices to allow linking those directly
        if (btFilters.isEmpty() || !request.isSingleDevice()) return false;

        final BluetoothDeviceFilter singleMacAddressFilter =
                find(btFilters, filter -> !TextUtils.isEmpty(filter.getAddress()));

        if (singleMacAddressFilter == null) return false;

        return findAndReportMatches(mBtAdapter.getBondedDevices(), btFilters)
                || findAndReportMatches(mBtManager.getConnectedDevices(
                        BluetoothProfile.GATT), btFilters)
                || findAndReportMatches(mBtManager.getConnectedDevices(
                        BluetoothProfile.GATT_SERVER), btFilters);
    }

    private boolean findAndReportMatches(@Nullable Collection<BluetoothDevice> devices,
            @NonNull List<BluetoothDeviceFilter> filters) {
        if (devices == null) return false;

        for (BluetoothDevice device : devices) {
            final DeviceFilterPair<BluetoothDevice> match = findMatch(device, filters);
            if (match != null) {
                onDeviceFound(match);
                return true;
            }
        }

        return false;
    }

    private BluetoothBroadcastReceiver startBtScanningIfNeeded(
            List<BluetoothDeviceFilter> filters, boolean force) {
        if (isEmpty(filters) && !force) return null;
        Slog.d(TAG, "registerReceiver(BluetoothDevice.ACTION_FOUND)");

        final BluetoothBroadcastReceiver receiver = new BluetoothBroadcastReceiver(filters);
        final IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, intentFilter);

        mBtAdapter.startDiscovery();

        return receiver;
    }

    private WifiBroadcastReceiver startWifiScanningIfNeeded(
            List<WifiDeviceFilter> filters, boolean force) {
        if (isEmpty(filters) && !force) return null;
        Slog.d(TAG, "registerReceiver(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)");

        final WifiBroadcastReceiver receiver = new WifiBroadcastReceiver(filters);

        final IntentFilter intentFilter = new IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(receiver, intentFilter);

        mWifiManager.startScan();

        return receiver;
    }

    private ScanCallback startBleScanningIfNeeded(
            List<BluetoothLeDeviceFilter> filters, boolean force) {
        if (isEmpty(filters) && !force) return null;
        Slog.d(TAG, "BLEScanner.startScan");

        if (mBleScanner == null) {
            Slog.w(TAG, "BLE Scanner is not available.");
            return null;
        }

        final BLEScanCallback callback = new BLEScanCallback(filters);

        final List<ScanFilter> scanFilters = map(
                filters, BluetoothLeDeviceFilter::getScanFilter);
        final ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        mBleScanner.startScan(scanFilters, scanSettings, callback);

        return callback;
    }

    private void onDeviceFound(@NonNull DeviceFilterPair<?> device) {
        runOnMainThread(() -> {
            synchronized (LOCK) {
                if (!sDiscoveryStarted) return;
            }
            if (mDevicesFound.contains(device)) {
                // TODO: update the device instead of ignoring (new found device may contain
                //  additional/updated info, eg. name of the device).
                return;
            }
            Slog.i(TAG, "onDeviceFound() " + device.toShortString() + " - New device.");

            // First: make change.
            mDevicesFound.add(device);
            // Then: notify observers.
            sScanResultsLiveData.setValue(mDevicesFound);
            // Stop discovery when there's one device found for singleDevice.
            if (mStopAfterFirstMatch) {
                stopDiscoveryAndFinish(/* timeout */ false);
            }
        });
    }

    private void onDeviceLost(@NonNull DeviceFilterPair<?> device) {
        runOnMainThread(() -> {
            Slog.i(TAG, "onDeviceLost(), device=" + device.toShortString());

            // First: make change.
            mDevicesFound.remove(device);
            // Then: notify observers.
            sScanResultsLiveData.setValue(mDevicesFound);
        });
    }

    private void scheduleTimeout() {
        long timeout = SystemProperties.getLong(SYS_PROP_DEBUG_TIMEOUT, -1);
        if (timeout <= 0) {
            // 0 or negative values indicate that the sysprop was never set or should be ignored.
            timeout = TIMEOUT_DEFAULT;
        } else {
            timeout = min(timeout, TIMEOUT_MAX); // should be <= 1 min (TIMEOUT_MAX)
            timeout = max(timeout, TIMEOUT_MIN); // should be >= 1 sec (TIMEOUT_MIN)
        }

        Handler.getMain().postDelayed(mTimeoutRunnable, timeout);
    }

    private void timeout() {
        stopDiscoveryAndFinish(/* timeout */ true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class BLEScanCallback extends ScanCallback {
        final List<BluetoothLeDeviceFilter> mFilters;

        BLEScanCallback(List<BluetoothLeDeviceFilter> filters) {
            mFilters = filters;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final DeviceFilterPair<ScanResult> match = findMatch(result, mFilters);
            if (match == null) return;

            if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                onDeviceLost(match);
            } else {
                // TODO: check this logic.
                onDeviceFound(match);
            }
        }
    }

    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        final List<BluetoothDeviceFilter> mFilters;

        BluetoothBroadcastReceiver(List<BluetoothDeviceFilter> filters) {
            this.mFilters = filters;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (action == null) return;

            final DeviceFilterPair<BluetoothDevice> match = findMatch(device, mFilters);
            if (match == null) return;

            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                onDeviceFound(match);
            } else {
                // TODO: check this logic.
                onDeviceLost(match);
            }
        }
    }

    private class WifiBroadcastReceiver extends BroadcastReceiver {
        final List<WifiDeviceFilter> mFilters;

        private WifiBroadcastReceiver(List<WifiDeviceFilter> filters) {
            this.mFilters = filters;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Objects.equals(intent.getAction(), WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                return;
            }

            final List<android.net.wifi.ScanResult> scanResults = mWifiManager.getScanResults();

            for (int i = 0; i < scanResults.size(); i++) {
                final android.net.wifi.ScanResult scanResult = scanResults.get(i);
                final DeviceFilterPair<?> match = findMatch(scanResult, mFilters);
                if (match != null) {
                    onDeviceFound(match);
                }
            }
        }
    }

    /**
     * {@code (device, null)} if the filters list is empty or null
     * {@code null} if none of the provided filters match the device
     * {@code (device, filter)} where filter is among the list of filters and matches the device
     */
    @Nullable
    public static <T extends Parcelable> DeviceFilterPair<T> findMatch(
            T dev, @Nullable List<? extends DeviceFilter<T>> filters) {
        if (isEmpty(filters)) return new DeviceFilterPair<>(dev, null);
        final DeviceFilter<T> matchingFilter = find(filters, f -> f.matches(dev));

        DeviceFilterPair<T> result = matchingFilter != null
                ? new DeviceFilterPair<>(dev, matchingFilter) : null;

        return result;
    }
}
