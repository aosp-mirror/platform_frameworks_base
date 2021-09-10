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

import static android.companion.BluetoothDeviceFilterUtils.getDeviceDisplayNameInternal;
import static android.companion.BluetoothDeviceFilterUtils.getDeviceMacAddress;

import static com.android.internal.util.ArrayUtils.isEmpty;
import static com.android.internal.util.CollectionUtils.emptyIfNull;
import static com.android.internal.util.CollectionUtils.size;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
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
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.IFindDeviceCallback;
import android.companion.WifiDeviceFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CompanionDeviceDiscoveryService extends Service {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = CompanionDeviceDiscoveryService.class.getSimpleName();

    private static final long SCAN_TIMEOUT = 20000;

    static CompanionDeviceDiscoveryService sInstance;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private WifiManager mWifiManager;
    @Nullable private BluetoothLeScanner mBLEScanner;
    private ScanSettings mDefaultScanSettings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

    private List<DeviceFilter<?>> mFilters;
    private List<BluetoothLeDeviceFilter> mBLEFilters;
    private List<BluetoothDeviceFilter> mBluetoothFilters;
    private List<WifiDeviceFilter> mWifiFilters;
    private List<ScanFilter> mBLEScanFilters;

    AssociationRequest mRequest;
    List<DeviceFilterPair> mDevicesFound;
    DeviceFilterPair mSelectedDevice;
    IFindDeviceCallback mFindCallback;

    AndroidFuture<String> mServiceCallback;
    boolean mIsScanning = false;
    @Nullable
    CompanionDeviceActivity mActivity = null;

    private final ICompanionDeviceDiscoveryService mBinder =
            new ICompanionDeviceDiscoveryService.Stub() {
        @Override
        public void startDiscovery(AssociationRequest request,
                String callingPackage,
                IFindDeviceCallback findCallback,
                AndroidFuture<String> serviceCallback) {
            Log.i(LOG_TAG,
                    "startDiscovery() called with: filter = [" + request
                            + "], findCallback = [" + findCallback + "]"
                            + "], serviceCallback = [" + serviceCallback + "]");
            mFindCallback = findCallback;
            mServiceCallback = serviceCallback;
            Handler.getMain().sendMessage(obtainMessage(
                    CompanionDeviceDiscoveryService::startDiscovery,
                    CompanionDeviceDiscoveryService.this, request));
        }
    };

    private ScanCallback mBLEScanCallback;
    private BluetoothBroadcastReceiver mBluetoothBroadcastReceiver;
    private WifiBroadcastReceiver mWifiBroadcastReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(LOG_TAG, "onBind(" + intent + ")");
        return mBinder.asBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(LOG_TAG, "onCreate()");

        mBluetoothManager = getSystemService(BluetoothManager.class);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mBLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        mWifiManager = getSystemService(WifiManager.class);

        mDevicesFound = new ArrayList<>();

        sInstance = this;
    }

    @MainThread
    private void startDiscovery(AssociationRequest request) {
        if (!request.equals(mRequest)) {
            mRequest = request;

            mFilters = request.getDeviceFilters();
            mWifiFilters = CollectionUtils.filter(mFilters, WifiDeviceFilter.class);
            mBluetoothFilters = CollectionUtils.filter(mFilters, BluetoothDeviceFilter.class);
            mBLEFilters = CollectionUtils.filter(mFilters, BluetoothLeDeviceFilter.class);
            mBLEScanFilters
                    = CollectionUtils.map(mBLEFilters, BluetoothLeDeviceFilter::getScanFilter);

            reset();
        } else {
            Log.i(LOG_TAG, "startDiscovery: duplicate request: " + request);
        }

        if (!ArrayUtils.isEmpty(mDevicesFound)) {
            onReadyToShowUI();
        }

        // If filtering to get single device by mac address, also search in the set of already
        // bonded devices to allow linking those directly
        String singleMacAddressFilter = null;
        if (mRequest.isSingleDevice()) {
            int numFilters = size(mBluetoothFilters);
            for (int i = 0; i < numFilters; i++) {
                BluetoothDeviceFilter filter = mBluetoothFilters.get(i);
                if (!TextUtils.isEmpty(filter.getAddress())) {
                    singleMacAddressFilter = filter.getAddress();
                    break;
                }
            }
        }
        if (singleMacAddressFilter != null) {
            for (BluetoothDevice dev : emptyIfNull(mBluetoothAdapter.getBondedDevices())) {
                onDeviceFound(DeviceFilterPair.findMatch(dev, mBluetoothFilters));
            }
            for (BluetoothDevice dev : emptyIfNull(
                    mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT))) {
                onDeviceFound(DeviceFilterPair.findMatch(dev, mBluetoothFilters));
            }
            for (BluetoothDevice dev : emptyIfNull(
                    mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER))) {
                onDeviceFound(DeviceFilterPair.findMatch(dev, mBluetoothFilters));
            }
        }

        if (shouldScan(mBluetoothFilters)) {
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothDevice.ACTION_FOUND);

            Log.i(LOG_TAG, "registerReceiver(BluetoothDevice.ACTION_FOUND)");
            mBluetoothBroadcastReceiver = new BluetoothBroadcastReceiver();
            registerReceiver(mBluetoothBroadcastReceiver, intentFilter);
            mBluetoothAdapter.startDiscovery();
        }

        if (shouldScan(mBLEFilters) && mBLEScanner != null) {
            Log.i(LOG_TAG, "BLEScanner.startScan");
            mBLEScanCallback = new BLEScanCallback();
            mBLEScanner.startScan(mBLEScanFilters, mDefaultScanSettings, mBLEScanCallback);
        }

        if (shouldScan(mWifiFilters)) {
            Log.i(LOG_TAG, "registerReceiver(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)");
            mWifiBroadcastReceiver = new WifiBroadcastReceiver();
            registerReceiver(mWifiBroadcastReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            mWifiManager.startScan();
        }
        mIsScanning = true;
        Handler.getMain().sendMessageDelayed(
                obtainMessage(CompanionDeviceDiscoveryService::stopScan, this),
                SCAN_TIMEOUT);
    }

    private boolean shouldScan(List<? extends DeviceFilter> mediumSpecificFilters) {
        return !isEmpty(mediumSpecificFilters) || isEmpty(mFilters);
    }

    @MainThread
    private void reset() {
        Log.i(LOG_TAG, "reset()");
        stopScan();
        mDevicesFound.clear();
        mSelectedDevice = null;
        CompanionDeviceActivity.notifyDevicesChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(LOG_TAG, "onUnbind(intent = " + intent + ")");
        stopScan();
        return super.onUnbind(intent);
    }

    private void stopScan() {
        Log.i(LOG_TAG, "stopScan()");

        if (!mIsScanning) return;
        mIsScanning = false;

        CompanionDeviceActivity activity = mActivity;
        if (activity != null) {
            if (activity.mDeviceListView != null) {
                activity.mDeviceListView.removeFooterView(activity.mLoadingIndicator);
            }
            mActivity = null;
        }

        mBluetoothAdapter.cancelDiscovery();
        if (mBluetoothBroadcastReceiver != null) {
            unregisterReceiver(mBluetoothBroadcastReceiver);
            mBluetoothBroadcastReceiver = null;
        }
        if (mBLEScanner != null) mBLEScanner.stopScan(mBLEScanCallback);
        if (mWifiBroadcastReceiver != null) {
            unregisterReceiver(mWifiBroadcastReceiver);
            mWifiBroadcastReceiver = null;
        }
    }

    private void onDeviceFound(@Nullable DeviceFilterPair device) {
        if (device == null) return;

        Handler.getMain().sendMessage(obtainMessage(
                CompanionDeviceDiscoveryService::onDeviceFoundMainThread, this, device));
    }

    @MainThread
    void onDeviceFoundMainThread(@NonNull DeviceFilterPair device) {
        if (mDevicesFound.contains(device)) {
            Log.i(LOG_TAG, "Skipping device " + device + " - already among found devices");
            return;
        }

        Log.i(LOG_TAG, "Found device " + device);

        if (mDevicesFound.isEmpty()) {
            onReadyToShowUI();
        }
        mDevicesFound.add(device);
        CompanionDeviceActivity.notifyDevicesChanged();
    }

    //TODO also, on timeout -> call onFailure
    private void onReadyToShowUI() {
        try {
            mFindCallback.onSuccess(PendingIntent.getActivity(
                    this, 0,
                    new Intent(this, CompanionDeviceActivity.class),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void onDeviceLost(@Nullable DeviceFilterPair device) {
        Log.i(LOG_TAG, "Lost device " + device.getDisplayName());
        Handler.getMain().sendMessage(obtainMessage(
                CompanionDeviceDiscoveryService::onDeviceLostMainThread, this, device));
    }

    @MainThread
    void onDeviceLostMainThread(@Nullable DeviceFilterPair device) {
        mDevicesFound.remove(device);
        CompanionDeviceActivity.notifyDevicesChanged();
    }

    void onDeviceSelected(String callingPackage, String deviceAddress) {
        if (callingPackage == null || deviceAddress == null) {
            return;
        }
        mServiceCallback.complete(deviceAddress);
    }

    void onCancel() {
        if (DEBUG) Log.i(LOG_TAG, "onCancel()");
        mServiceCallback.cancel(true);
    }

    /**
     * A pair of device and a filter that matched this device if any.
     *
     * @param <T> device type
     */
    static class DeviceFilterPair<T extends Parcelable> {
        public final T device;
        @Nullable
        public final DeviceFilter<T> filter;

        private DeviceFilterPair(T device, @Nullable DeviceFilter<T> filter) {
            this.device = device;
            this.filter = filter;
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
            final DeviceFilter<T> matchingFilter
                    = CollectionUtils.find(filters, f -> f.matches(dev));

            DeviceFilterPair<T> result = matchingFilter != null
                    ? new DeviceFilterPair<>(dev, matchingFilter)
                    : null;
            if (DEBUG) Log.i(LOG_TAG, "findMatch(dev = " + dev + ", filters = " + filters +
                    ") -> " + result);
            return result;
        }

        public String getDisplayName() {
            if (filter == null) {
                Preconditions.checkNotNull(device);
                if (device instanceof BluetoothDevice) {
                    return getDeviceDisplayNameInternal((BluetoothDevice) device);
                } else if (device instanceof android.net.wifi.ScanResult) {
                    return getDeviceDisplayNameInternal((android.net.wifi.ScanResult) device);
                } else if (device instanceof ScanResult) {
                    return getDeviceDisplayNameInternal(((ScanResult) device).getDevice());
                } else {
                    throw new IllegalArgumentException("Unknown device type: " + device.getClass());
                }
            }
            return filter.getDeviceDisplayName(device);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DeviceFilterPair<?> that = (DeviceFilterPair<?>) o;
            return Objects.equals(getDeviceMacAddress(device), getDeviceMacAddress(that.device));
        }

        @Override
        public int hashCode() {
            return Objects.hash(getDeviceMacAddress(device));
        }

        @Override
        public String toString() {
            return "DeviceFilterPair{"
                    + "device=" + device + " " + getDisplayName()
                    + ", filter=" + filter
                    + '}';
        }
    }

    private class BLEScanCallback extends ScanCallback {

        public BLEScanCallback() {
            if (DEBUG) Log.i(LOG_TAG, "new BLEScanCallback() -> " + this);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (DEBUG) {
                Log.i(LOG_TAG,
                        "BLE.onScanResult(callbackType = " + callbackType + ", result = " + result
                                + ")");
            }
            final DeviceFilterPair<ScanResult> deviceFilterPair
                    = DeviceFilterPair.findMatch(result, mBLEFilters);
            if (deviceFilterPair == null) return;
            if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                onDeviceLost(deviceFilterPair);
            } else {
                onDeviceFound(deviceFilterPair);
            }
        }
    }

    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.i(LOG_TAG,
                        "BL.onReceive(context = " + context + ", intent = " + intent + ")");
            }
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            final DeviceFilterPair<BluetoothDevice> deviceFilterPair
                    = DeviceFilterPair.findMatch(device, mBluetoothFilters);
            if (deviceFilterPair == null) return;
            if (intent.getAction().equals(BluetoothDevice.ACTION_FOUND)) {
                onDeviceFound(deviceFilterPair);
            } else {
                onDeviceLost(deviceFilterPair);
            }
        }
    }

    private class WifiBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                List<android.net.wifi.ScanResult> scanResults = mWifiManager.getScanResults();

                if (DEBUG) {
                    Log.i(LOG_TAG, "Wifi scan results: " + TextUtils.join("\n", scanResults));
                }

                for (int i = 0; i < scanResults.size(); i++) {
                    onDeviceFound(DeviceFilterPair.findMatch(scanResults.get(i), mWifiFilters));
                }
            }
        }
    }
}
