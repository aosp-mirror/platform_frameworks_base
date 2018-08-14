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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
import android.companion.ICompanionDeviceDiscoveryServiceCallback;
import android.companion.IFindDeviceCallback;
import android.companion.WifiDeviceFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DeviceDiscoveryService extends Service {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "DeviceDiscoveryService";

    private static final long SCAN_TIMEOUT = 20000;

    static DeviceDiscoveryService sInstance;

    private BluetoothAdapter mBluetoothAdapter;
    private WifiManager mWifiManager;
    @Nullable private BluetoothLeScanner mBLEScanner;
    private ScanSettings mDefaultScanSettings = new ScanSettings.Builder().build();

    private List<DeviceFilter<?>> mFilters;
    private List<BluetoothLeDeviceFilter> mBLEFilters;
    private List<BluetoothDeviceFilter> mBluetoothFilters;
    private List<WifiDeviceFilter> mWifiFilters;
    private List<ScanFilter> mBLEScanFilters;

    AssociationRequest mRequest;
    List<DeviceFilterPair> mDevicesFound;
    DeviceFilterPair mSelectedDevice;
    DevicesAdapter mDevicesAdapter;
    IFindDeviceCallback mFindCallback;

    ICompanionDeviceDiscoveryServiceCallback mServiceCallback;
    boolean mIsScanning = false;
    @Nullable DeviceChooserActivity mActivity = null;

    private final ICompanionDeviceDiscoveryService mBinder =
            new ICompanionDeviceDiscoveryService.Stub() {
        @Override
        public void startDiscovery(AssociationRequest request,
                String callingPackage,
                IFindDeviceCallback findCallback,
                ICompanionDeviceDiscoveryServiceCallback serviceCallback) {
            if (DEBUG) {
                Log.i(LOG_TAG,
                        "startDiscovery() called with: filter = [" + request
                                + "], findCallback = [" + findCallback + "]"
                                + "], serviceCallback = [" + serviceCallback + "]");
            }
            mFindCallback = findCallback;
            mServiceCallback = serviceCallback;
            DeviceDiscoveryService.this.startDiscovery(request);
        }
    };

    private ScanCallback mBLEScanCallback;
    private BluetoothBroadcastReceiver mBluetoothBroadcastReceiver;
    private WifiBroadcastReceiver mWifiBroadcastReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.i(LOG_TAG, "onBind(" + intent + ")");
        return mBinder.asBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) Log.i(LOG_TAG, "onCreate()");

        mBluetoothAdapter = getSystemService(BluetoothManager.class).getAdapter();
        mBLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        mWifiManager = getSystemService(WifiManager.class);

        mDevicesFound = new ArrayList<>();
        mDevicesAdapter = new DevicesAdapter();

        sInstance = this;
    }

    private void startDiscovery(AssociationRequest request) {
        if (!request.equals(mRequest)) {
            mRequest = request;

            mFilters = request.getDeviceFilters();
            mWifiFilters = CollectionUtils.filter(mFilters, WifiDeviceFilter.class);
            mBluetoothFilters = CollectionUtils.filter(mFilters, BluetoothDeviceFilter.class);
            mBLEFilters = CollectionUtils.filter(mFilters, BluetoothLeDeviceFilter.class);
            mBLEScanFilters = CollectionUtils.map(mBLEFilters, BluetoothLeDeviceFilter::getScanFilter);

            reset();
        } else if (DEBUG) Log.i(LOG_TAG, "startDiscovery: duplicate request: " + request);

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
        }

        if (shouldScan(mBluetoothFilters)) {
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
            intentFilter.addAction(BluetoothDevice.ACTION_DISAPPEARED);

            mBluetoothBroadcastReceiver = new BluetoothBroadcastReceiver();
            registerReceiver(mBluetoothBroadcastReceiver, intentFilter);
            mBluetoothAdapter.startDiscovery();
        }

        if (shouldScan(mBLEFilters) && mBLEScanner != null) {
            mBLEScanCallback = new BLEScanCallback();
            mBLEScanner.startScan(mBLEScanFilters, mDefaultScanSettings, mBLEScanCallback);
        }

        if (shouldScan(mWifiFilters)) {
            mWifiBroadcastReceiver = new WifiBroadcastReceiver();
            registerReceiver(mWifiBroadcastReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            mWifiManager.startScan();
        }
        mIsScanning = true;
        Handler.getMain().sendMessageDelayed(
                obtainMessage(DeviceDiscoveryService::stopScan, this),
                SCAN_TIMEOUT);
    }

    private boolean shouldScan(List<? extends DeviceFilter> mediumSpecificFilters) {
        return !isEmpty(mediumSpecificFilters) || isEmpty(mFilters);
    }

    private void reset() {
        if (DEBUG) Log.i(LOG_TAG, "reset()");
        stopScan();
        mDevicesFound.clear();
        mSelectedDevice = null;
        notifyDataSetChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopScan();
        return super.onUnbind(intent);
    }

    private void stopScan() {
        if (DEBUG) Log.i(LOG_TAG, "stopScan()");

        if (!mIsScanning) return;
        mIsScanning = false;

        DeviceChooserActivity activity = mActivity;
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

        if (mDevicesFound.contains(device)) {
            return;
        }

        if (DEBUG) Log.i(LOG_TAG, "Found device " + device);

        if (mDevicesFound.isEmpty()) {
            onReadyToShowUI();
        }
        mDevicesFound.add(device);
        notifyDataSetChanged();
    }

    private void notifyDataSetChanged() {
        Handler.getMain().sendMessage(obtainMessage(
                DevicesAdapter::notifyDataSetChanged, mDevicesAdapter));
    }

    //TODO also, on timeout -> call onFailure
    private void onReadyToShowUI() {
        try {
            mFindCallback.onSuccess(PendingIntent.getActivity(
                    this, 0,
                    new Intent(this, DeviceChooserActivity.class),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void onDeviceLost(@Nullable DeviceFilterPair device) {
        mDevicesFound.remove(device);
        notifyDataSetChanged();
        if (DEBUG) Log.i(LOG_TAG, "Lost device " + device.getDisplayName());
    }

    void onDeviceSelected(String callingPackage, String deviceAddress) {
        try {
            mServiceCallback.onDeviceSelected(
                    //TODO is this the right userId?
                    callingPackage, getUserId(), deviceAddress);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed to record association: "
                    + callingPackage + " <-> " + deviceAddress);
        }
    }

    void onCancel() {
        if (DEBUG) Log.i(LOG_TAG, "onCancel()");
        try {
            mServiceCallback.onDeviceSelectionCancel();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    class DevicesAdapter extends ArrayAdapter<DeviceFilterPair> {
        private Drawable BLUETOOTH_ICON = icon(android.R.drawable.stat_sys_data_bluetooth);
        private Drawable WIFI_ICON = icon(com.android.internal.R.drawable.ic_wifi_signal_3);

        private Drawable icon(int drawableRes) {
            Drawable icon = getResources().getDrawable(drawableRes, null);
            icon.setTint(Color.DKGRAY);
            return icon;
        }

        public DevicesAdapter() {
            super(DeviceDiscoveryService.this, 0, mDevicesFound);
        }

        @Override
        public View getView(
                int position,
                @Nullable View convertView,
                @NonNull ViewGroup parent) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView
                    : newView();
            bind(view, getItem(position));
            return view;
        }

        private void bind(TextView textView, DeviceFilterPair device) {
            textView.setText(device.getDisplayName());
            textView.setBackgroundColor(
                    device.equals(mSelectedDevice)
                            ? Color.GRAY
                            : Color.TRANSPARENT);
            textView.setCompoundDrawablesWithIntrinsicBounds(
                    device.device instanceof android.net.wifi.ScanResult
                        ? WIFI_ICON
                        : BLUETOOTH_ICON,
                    null, null, null);
            textView.setOnClickListener((view) -> {
                mSelectedDevice = device;
                notifyDataSetChanged();
            });
        }

        //TODO move to a layout file
        private TextView newView() {
            final TextView textView = new TextView(DeviceDiscoveryService.this);
            textView.setTextColor(Color.BLACK);
            final int padding = DeviceChooserActivity.getPadding(getResources());
            textView.setPadding(padding, padding, padding, padding);
            textView.setCompoundDrawablePadding(padding);
            return textView;
        }
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
            return "DeviceFilterPair{" +
                    "device=" + device +
                    ", filter=" + filter +
                    '}';
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
