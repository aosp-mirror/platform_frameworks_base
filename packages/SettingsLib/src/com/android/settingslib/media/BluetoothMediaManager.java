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
import android.bluetooth.BluetoothProfile;
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
public class BluetoothMediaManager extends MediaManager implements BluetoothCallback,
        LocalBluetoothProfileManager.ServiceListener {

    private static final String TAG = "BluetoothMediaManager";

    private final DeviceAttributeChangeCallback mDeviceAttributeChangeCallback =
            new DeviceAttributeChangeCallback();

    private LocalBluetoothManager mLocalBluetoothManager;
    private LocalBluetoothProfileManager mProfileManager;
    private CachedBluetoothDeviceManager mCachedBluetoothDeviceManager;

    private MediaDevice mLastAddedDevice;
    private MediaDevice mLastRemovedDevice;

    private boolean mIsA2dpProfileReady = false;
    private boolean mIsHearingAidProfileReady = false;

    BluetoothMediaManager(Context context, LocalBluetoothManager localBluetoothManager,
            Notification notification) {
        super(context, notification);

        mLocalBluetoothManager = localBluetoothManager;
        mProfileManager = mLocalBluetoothManager.getProfileManager();
        mCachedBluetoothDeviceManager = mLocalBluetoothManager.getCachedDeviceManager();
    }

    @Override
    public void startScan() {
        mLocalBluetoothManager.getEventManager().registerCallback(this);
        buildBluetoothDeviceList();
        dispatchDeviceListAdded();
        addServiceListenerIfNecessary();
    }

    private void addServiceListenerIfNecessary() {
        // The profile may not ready when calling startScan().
        // Device status are all disconnected since profiles are not ready to connected.
        // In this case, we observe onServiceConnected() in LocalBluetoothProfileManager.
        // When A2dpProfile or HearingAidProfile is connected will call buildBluetoothDeviceList()
        // again to find the connected devices.
        if (!mIsA2dpProfileReady || !mIsHearingAidProfileReady) {
            mProfileManager.addServiceListener(this);
        }
    }

    private void buildBluetoothDeviceList() {
        mMediaDevices.clear();
        addConnectableA2dpDevices();
        addConnectableHearingAidDevices();
    }

    private void addConnectableA2dpDevices() {
        final A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        if (a2dpProfile == null) {
            Log.w(TAG, "addConnectableA2dpDevices() a2dp profile is null!");
            return;
        }

        final List<BluetoothDevice> devices = a2dpProfile.getConnectableDevices();

        for (BluetoothDevice device : devices) {
            final CachedBluetoothDevice cachedDevice =
                    mCachedBluetoothDeviceManager.findDevice(device);

            if (cachedDevice == null) {
                Log.w(TAG, "Can't found CachedBluetoothDevice : " + device.getName());
                continue;
            }

            Log.d(TAG, "addConnectableA2dpDevices() device : " + cachedDevice.getName()
                    + ", is connected : " + cachedDevice.isConnected()
                    + ", is preferred : " + a2dpProfile.isPreferred(device));

            if (a2dpProfile.isPreferred(device)
                    && BluetoothDevice.BOND_BONDED == cachedDevice.getBondState()) {
                addMediaDevice(cachedDevice);
            }
        }

        mIsA2dpProfileReady = a2dpProfile.isProfileReady();
    }

    private void addConnectableHearingAidDevices() {
        final HearingAidProfile hapProfile = mProfileManager.getHearingAidProfile();
        if (hapProfile == null) {
            Log.w(TAG, "addConnectableHearingAidDevices() hap profile is null!");
            return;
        }

        final List<Long> devicesHiSyncIds = new ArrayList<>();
        final List<BluetoothDevice> devices = hapProfile.getConnectableDevices();

        for (BluetoothDevice device : devices) {
            final CachedBluetoothDevice cachedDevice =
                    mCachedBluetoothDeviceManager.findDevice(device);

            if (cachedDevice == null) {
                Log.w(TAG, "Can't found CachedBluetoothDevice : " + device.getName());
                continue;
            }

            Log.d(TAG, "addConnectableHearingAidDevices() device : " + cachedDevice.getName()
                    + ", is connected : " + cachedDevice.isConnected()
                    + ", is preferred : " + hapProfile.isPreferred(device));

            final long hiSyncId = hapProfile.getHiSyncId(device);

            // device with same hiSyncId should not be shown in the UI.
            // So do not add it into connectedDevices.
            if (!devicesHiSyncIds.contains(hiSyncId) && hapProfile.isPreferred(device)
                    && BluetoothDevice.BOND_BONDED == cachedDevice.getBondState()) {
                devicesHiSyncIds.add(hiSyncId);
                addMediaDevice(cachedDevice);
            }
        }

        mIsHearingAidProfileReady = hapProfile.isProfileReady();
    }

    private void addMediaDevice(CachedBluetoothDevice cachedDevice) {
        MediaDevice mediaDevice = findMediaDevice(MediaDeviceUtils.getId(cachedDevice));
        if (mediaDevice == null) {
            mediaDevice = new BluetoothMediaDevice(mContext, cachedDevice);
            cachedDevice.registerCallback(mDeviceAttributeChangeCallback);
            mLastAddedDevice = mediaDevice;
            mMediaDevices.add(mediaDevice);
        }
    }

    @Override
    public void stopScan() {
        mLocalBluetoothManager.getEventManager().unregisterCallback(this);
        unregisterDeviceAttributeChangeCallback();
    }

    private void unregisterDeviceAttributeChangeCallback() {
        for (MediaDevice device : mMediaDevices) {
            ((BluetoothMediaDevice) device).getCachedDevice()
                    .unregisterCallback(mDeviceAttributeChangeCallback);
        }
    }

    @Override
    public void onBluetoothStateChanged(int bluetoothState) {
        if (BluetoothAdapter.STATE_ON == bluetoothState) {
            buildBluetoothDeviceList();
            dispatchDeviceListAdded();
            addServiceListenerIfNecessary();
        } else if (BluetoothAdapter.STATE_OFF == bluetoothState) {
            final List<MediaDevice> removeDevicesList = new ArrayList<>();
            for (MediaDevice device : mMediaDevices) {
                ((BluetoothMediaDevice) device).getCachedDevice()
                        .unregisterCallback(mDeviceAttributeChangeCallback);
                removeDevicesList.add(device);
            }
            mMediaDevices.removeAll(removeDevicesList);
            dispatchDeviceListRemoved(removeDevicesList);
        }
    }

    @Override
    public void onAudioModeChanged() {
        dispatchDataChanged();
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
        Log.d(TAG, "isCachedDeviceConnected() cachedDevice : " + cachedDevice
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
            cachedDevice.unregisterCallback(mDeviceAttributeChangeCallback);
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
        Log.d(TAG, "onProfileConnectionStateChanged() device: " + cachedDevice
                + ", state: " + state + ", bluetoothProfile: " + bluetoothProfile);

        updateMediaDeviceListIfNecessary(cachedDevice);
    }

    private void updateMediaDeviceListIfNecessary(CachedBluetoothDevice cachedDevice) {
        if (BluetoothDevice.BOND_NONE == cachedDevice.getBondState()) {
            removeMediaDevice(cachedDevice);
            dispatchDeviceRemoved(cachedDevice);
        } else {
            if (findMediaDevice(MediaDeviceUtils.getId(cachedDevice)) != null) {
                dispatchDataChanged();
            }
        }
    }

    @Override
    public void onAclConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        Log.d(TAG, "onAclConnectionStateChanged() device: " + cachedDevice + ", state: " + state);

        updateMediaDeviceListIfNecessary(cachedDevice);
    }

    @Override
    public void onActiveDeviceChanged(CachedBluetoothDevice activeDevice, int bluetoothProfile) {
        Log.d(TAG, "onActiveDeviceChanged : device : "
                + activeDevice + ", profile : " + bluetoothProfile);

        if (BluetoothProfile.HEARING_AID == bluetoothProfile) {
            if (activeDevice != null) {
                dispatchConnectedDeviceChanged(MediaDeviceUtils.getId(activeDevice));
            }
        } else if (BluetoothProfile.A2DP == bluetoothProfile) {
            // When active device change to Hearing Aid,
            // BluetoothEventManager also send onActiveDeviceChanged() to notify that active device
            // of A2DP profile is null. To handle this case, check hearing aid device
            // is active device or not
            final MediaDevice activeHearingAidDevice = findActiveHearingAidDevice();
            final String id = activeDevice == null
                    ? activeHearingAidDevice == null
                    ? PhoneMediaDevice.ID : activeHearingAidDevice.getId()
                    : MediaDeviceUtils.getId(activeDevice);
            dispatchConnectedDeviceChanged(id);
        }
    }

    private MediaDevice findActiveHearingAidDevice() {
        final HearingAidProfile hearingAidProfile = mProfileManager.getHearingAidProfile();

        if (hearingAidProfile != null) {
            final List<BluetoothDevice> activeDevices = hearingAidProfile.getActiveDevices();
            for (BluetoothDevice btDevice : activeDevices) {
                if (btDevice != null) {
                    return findMediaDevice(MediaDeviceUtils.getId(btDevice));
                }
            }
        }
        return null;
    }

    @Override
    public void onServiceConnected() {
        if (!mIsA2dpProfileReady || !mIsHearingAidProfileReady) {
            buildBluetoothDeviceList();
            dispatchDeviceListAdded();
        }

        //Remove the listener once a2dpProfile and hearingAidProfile are ready.
        if (mIsA2dpProfileReady && mIsHearingAidProfileReady) {
            mProfileManager.removeServiceListener(this);
        }
    }

    @Override
    public void onServiceDisconnected() {

    }

    /**
     * This callback is for update {@link BluetoothMediaDevice} summary when
     * {@link CachedBluetoothDevice} connection state is changed.
     */
    private class DeviceAttributeChangeCallback implements CachedBluetoothDevice.Callback {

        @Override
        public void onDeviceAttributesChanged() {
            dispatchDataChanged();
        }
    }
}
