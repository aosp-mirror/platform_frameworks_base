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

import static android.companion.BluetoothDeviceFilterUtils.getDeviceDisplayName;
import static android.companion.BluetoothLEDeviceFilter.nullsafe;

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
import android.companion.BluetoothLEDeviceFilter;
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.ICompanionDeviceDiscoveryServiceCallback;
import android.companion.IFindDeviceCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeviceDiscoveryService extends Service {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "DeviceDiscoveryService";

    static DeviceDiscoveryService sInstance;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLEDeviceFilter mFilter;
    private ScanFilter mScanFilter;
    private ScanSettings mDefaultScanSettings = new ScanSettings.Builder().build();
    AssociationRequest<?> mRequest;
    List<BluetoothDevice> mDevicesFound;
    BluetoothDevice mSelectedDevice;
    DevicesAdapter mDevicesAdapter;
    IFindDeviceCallback mFindCallback;
    ICompanionDeviceDiscoveryServiceCallback mServiceCallback;
    String mCallingPackage;

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
            mCallingPackage = callingPackage;
            DeviceDiscoveryService.this.startDiscovery(request);
        }
    };

    private final ScanCallback mBLEScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();
            if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                onDeviceLost(device);
            } else {
                onDeviceFound(device);
            }
        }
    };

    private BluetoothLeScanner mBLEScanner;

    private BroadcastReceiver mBluetoothDeviceFoundBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final BluetoothDevice device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE);
            if (!mFilter.matches(device)) return; // ignore device

            if (intent.getAction().equals(BluetoothDevice.ACTION_FOUND)) {
                onDeviceFound(device);
            } else {
                onDeviceLost(device);
            }
        }
    };

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

        mDevicesFound = new ArrayList<>();
        mDevicesAdapter = new DevicesAdapter();

        sInstance = this;
    }

    private void startDiscovery(AssociationRequest<?> request) {
        //TODO support other protocols as well
        mRequest = request;
        mFilter = nullsafe((BluetoothLEDeviceFilter) request.getDeviceFilter());
        mScanFilter = mFilter.getScanFilter();

        reset();

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_DISAPPEARED);

        registerReceiver(mBluetoothDeviceFoundBroadcastReceiver, intentFilter);
        mBluetoothAdapter.startDiscovery();

        mBLEScanner.startScan(
                Collections.singletonList(mScanFilter), mDefaultScanSettings, mBLEScanCallback);
    }

    private void reset() {
        mDevicesFound.clear();
        mSelectedDevice = null;
        mDevicesAdapter.notifyDataSetChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopScan();
        return super.onUnbind(intent);
    }

    public void onDeviceSelected() {
        try {
            mServiceCallback.onDeviceSelected(mCallingPackage, getUserId());
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting selected device");
        }
    }

    private void stopScan() {
        if (DEBUG) Log.i(LOG_TAG, "stopScan() called");
        mBluetoothAdapter.cancelDiscovery();
        mBLEScanner.stopScan(mBLEScanCallback);
        unregisterReceiver(mBluetoothDeviceFoundBroadcastReceiver);
        stopSelf();
    }

    private void onDeviceFound(BluetoothDevice device) {
        if (mDevicesFound.contains(device)) {
            return;
        }

        if (DEBUG) {
            Log.i(LOG_TAG, "Considering device " + getDeviceDisplayName(device));
        }

        if (!mFilter.matches(device)) {
            return;
        }

        if (DEBUG) {
            Log.i(LOG_TAG, "Found device " + getDeviceDisplayName(device));
        }
        if (mDevicesFound.isEmpty()) {
            onReadyToShowUI();
        }
        mDevicesFound.add(device);
        mDevicesAdapter.notifyDataSetChanged();
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

    private void onDeviceLost(BluetoothDevice device) {
        mDevicesFound.remove(device);
        mDevicesAdapter.notifyDataSetChanged();
        if (DEBUG) {
            Log.i(LOG_TAG, "Lost device " + getDeviceDisplayName(device));
        }
    }

    class DevicesAdapter extends ArrayAdapter<BluetoothDevice> {
        private Drawable BLUETOOTH_ICON = icon(android.R.drawable.stat_sys_data_bluetooth);

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

        private void bind(TextView textView, BluetoothDevice device) {
            textView.setText(getDeviceDisplayName(device));
            textView.setBackgroundColor(
                    device.equals(mSelectedDevice)
                            ? Color.GRAY
                            : Color.TRANSPARENT);
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
            textView.setCompoundDrawablesWithIntrinsicBounds(
                    BLUETOOTH_ICON, null, null, null);
            textView.setCompoundDrawablePadding(padding);
            return textView;
        }
    }
}
