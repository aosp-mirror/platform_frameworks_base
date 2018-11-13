/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

import java.util.ArrayList;
import java.util.List;

/**
 * BluetoothMediaManager provide interface to get Bluetooth device list.
 */
public class BluetoothMediaManager extends MediaManager implements BluetoothCallback {

    private static final String TAG = "BluetoothMediaManager";

    private final DeviceAttributeChangeCallback mCachedDeviceCallback =
            new DeviceAttributeChangeCallback();

    private LocalBluetoothManager mLocalBluetoothManager;
    private LocalBluetoothProfileManager mProfileManager;

    private MediaDevice mLastAddedDevice;
    private MediaDevice mLastRemovedDevice;

    BluetoothMediaManager(Context context, LocalBluetoothManager localBluetoothManager,
            Notification notification) {
        super(context, notification);

        mLocalBluetoothManager = localBluetoothManager;
        mProfileManager = mLocalBluetoothManager.getProfileManager();
    }

    @Override
    public void startScan() {
        mMediaDevices.clear();
        mLocalBluetoothManager.getEventManager().registerCallback(this);
        buildBluetoothDeviceList();
        dispatchDeviceListAdded();
    }

    private void buildBluetoothDeviceList() {
        addConnectedA2dpDevices();
        addConnectedHearingAidDevices();
    }

    private void addConnectedA2dpDevices() {
        final A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        if (a2dpProfile == null) {
            Log.w(TAG, "addConnectedA2dpDevices() a2dp profile is null!");
            return;
        }
        final List<BluetoothDevice> devices = a2dpProfile.getConnectedDevices();
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mLocalBluetoothManager.getCachedDeviceManager();

        for (BluetoothDevice device : devices) {
            final CachedBluetoothDevice cachedDevice =
                    cachedBluetoothDeviceManager.findDevice(device);

            if (cachedDevice == null) {
                Log.w(TAG, "Can't found CachedBluetoothDevice : " + device.getName());
                continue;
            }

            Log.d(TAG, "addConnectedA2dpDevices() device : " + cachedDevice.getName()
                    + ", is connected : " + cachedDevice.isConnected());

            if (cachedDevice.isConnected()) {
                addMediaDevice(cachedDevice);
            }
        }
    }

    private void addConnectedHearingAidDevices() {
        final HearingAidProfile hapProfile = mProfileManager.getHearingAidProfile();
        if (hapProfile == null) {
            Log.w(TAG, "addConnectedA2dpDevices() hap profile is null!");
            return;
        }
        final List<Long> devicesHiSyncIds = new ArrayList<>();
        final List<BluetoothDevice> devices = hapProfile.getConnectedDevices();
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mLocalBluetoothManager.getCachedDeviceManager();

        for (BluetoothDevice device : devices) {
            final CachedBluetoothDevice cachedDevice =
                    cachedBluetoothDeviceManager.findDevice(device);

            if (cachedDevice == null) {
                Log.w(TAG, "Can't found CachedBluetoothDevice : " + device.getName());
                continue;
            }

            Log.d(TAG, "addConnectedHearingAidDevices() device : " + cachedDevice.getName()
                    + ", is connected : " + cachedDevice.isConnected());
            final long hiSyncId = hapProfile.getHiSyncId(device);

            // device with same hiSyncId should not be shown in the UI.
            // So do not add it into connectedDevices.
            if (!devicesHiSyncIds.contains(hiSyncId) && cachedDevice.isConnected()) {
                devicesHiSyncIds.add(hiSyncId);
                addMediaDevice(cachedDevice);
            }
        }
    }

    private void addMediaDevice(CachedBluetoothDevice cachedDevice) {
        MediaDevice mediaDevice = findMediaDevice(MediaDeviceUtils.getId(cachedDevice));
        if (mediaDevice == null) {
            mediaDevice = new BluetoothMediaDevice(mContext, cachedDevice);
            cachedDevice.registerCallback(mCachedDeviceCallback);
            mLastAddedDevice = mediaDevice;
            mMediaDevices.add(mediaDevice);
        }
    }

    @Override
    public void stopScan() {
        mLocalBluetoothManager.getEventManager().unregisterCallback(this);
        unregisterCachedDeviceCallback();
    }

    private void unregisterCachedDeviceCallback() {
        for (MediaDevice device : mMediaDevices) {
            if (device instanceof BluetoothMediaDevice) {
                ((BluetoothMediaDevice) device).getCachedDevice()
                        .unregisterCallback(mCachedDeviceCallback);
            }
        }
    }

    @Override
    public void onBluetoothStateChanged(int bluetoothState) {
        if (BluetoothAdapter.STATE_ON == bluetoothState) {
            buildBluetoothDeviceList();
            dispatchDeviceListAdded();
        } else if (BluetoothAdapter.STATE_OFF == bluetoothState) {
            final List<MediaDevice> removeDevicesList = new ArrayList<>();
            for (MediaDevice device : mMediaDevices) {
                if (device instanceof BluetoothMediaDevice) {
                    ((BluetoothMediaDevice) device).getCachedDevice()
                            .unregisterCallback(mCachedDeviceCallback);
                    removeDevicesList.add(device);
                }
            }
            mMediaDevices.removeAll(removeDevicesList);
            dispatchDeviceListRemoved(removeDevicesList);
        }
    }

    @Override
    public void onDeviceAdded(CachedBluetoothDevice cachedDevice) {
        if (isCachedDeviceConnected(cachedDevice)) {
            addMediaDevice(cachedDevice);
            dispatchDeviceAdded(cachedDevice);
        }
    }

    private boolean isCachedDeviceConnected(CachedBluetoothDevice cachedDevice) {
        final boolean isConnectedHearingAidDevice = cachedDevice.isConnectedHearingAidDevice();
        final boolean isConnectedA2dpDevice = cachedDevice.isConnectedA2dpDevice();
        Log.d(TAG, "isCachedDeviceConnected() cachedDevice : " + cachedDevice.getName()
                + ", is hearing aid connected : " + isConnectedHearingAidDevice
                + ", is a2dp connected : " + isConnectedA2dpDevice);

        return isConnectedHearingAidDevice || isConnectedA2dpDevice;
    }

    private void dispatchDeviceAdded(CachedBluetoothDevice cachedDevice) {
        if (mLastAddedDevice != null
                && MediaDeviceUtils.getId(cachedDevice) == mLastAddedDevice.getId()) {
            dispatchDeviceAdded(mLastAddedDevice);
        }
    }

    @Override
    public void onDeviceDeleted(CachedBluetoothDevice cachedDevice) {
        if (!isCachedDeviceConnected(cachedDevice)) {
            removeMediaDevice(cachedDevice);
            dispatchDeviceRemoved(cachedDevice);
        }
    }

    private void removeMediaDevice(CachedBluetoothDevice cachedDevice) {
        final MediaDevice mediaDevice = findMediaDevice(MediaDeviceUtils.getId(cachedDevice));
        if (mediaDevice != null) {
            cachedDevice.unregisterCallback(mCachedDeviceCallback);
            mLastRemovedDevice = mediaDevice;
            mMediaDevices.remove(mediaDevice);
        }
    }

    void dispatchDeviceRemoved(CachedBluetoothDevice cachedDevice) {
        if (mLastRemovedDevice != null
                && MediaDeviceUtils.getId(cachedDevice) == mLastRemovedDevice.getId()) {
            dispatchDeviceRemoved(mLastRemovedDevice);
        }
    }

    @Override
    public void onProfileConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state,
            int bluetoothProfile) {
        Log.d(TAG, "onProfileConnectionStateChanged() device: " + cachedDevice.getName()
                + ", state: " + state + ", bluetoothProfile: " + bluetoothProfile);

        if (isCachedDeviceConnected(cachedDevice)) {
            addMediaDevice(cachedDevice);
            dispatchDeviceAdded(cachedDevice);
        } else {
            removeMediaDevice(cachedDevice);
            dispatchDeviceRemoved(cachedDevice);
        }
    }

    @Override
    public void onAclConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        Log.d(TAG, "onAclConnectionStateChanged() device: " + cachedDevice.getName()
                + ", state: " + state);

        if (isCachedDeviceConnected(cachedDevice)) {
            addMediaDevice(cachedDevice);
            dispatchDeviceAdded(cachedDevice);
        } else {
            removeMediaDevice(cachedDevice);
            dispatchDeviceRemoved(cachedDevice);
        }
    }

    class DeviceAttributeChangeCallback implements CachedBluetoothDevice.Callback {
        @Override
        public void onDeviceAttributesChanged() {
            dispatchDeviceAttributesChanged();
        }
    }
}
