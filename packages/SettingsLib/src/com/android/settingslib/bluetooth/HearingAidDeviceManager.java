/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.audiopolicy.AudioProductStrategy;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HearingAidDeviceManager manages the set of remote HearingAid(ASHA) Bluetooth devices.
 */
public class HearingAidDeviceManager {
    private static final String TAG = "HearingAidDeviceManager";
    private static final boolean DEBUG = BluetoothUtils.D;

    private final ContentResolver mContentResolver;
    private final LocalBluetoothManager mBtManager;
    private final List<CachedBluetoothDevice> mCachedDevices;
    private final HearingAidAudioRoutingHelper mRoutingHelper;
    HearingAidDeviceManager(Context context, LocalBluetoothManager localBtManager,
            List<CachedBluetoothDevice> CachedDevices) {
        mContentResolver = context.getContentResolver();
        mBtManager = localBtManager;
        mCachedDevices = CachedDevices;
        mRoutingHelper = new HearingAidAudioRoutingHelper(context);
    }

    @VisibleForTesting
    HearingAidDeviceManager(Context context, LocalBluetoothManager localBtManager,
            List<CachedBluetoothDevice> cachedDevices, HearingAidAudioRoutingHelper routingHelper) {
        mContentResolver = context.getContentResolver();
        mBtManager = localBtManager;
        mCachedDevices = cachedDevices;
        mRoutingHelper = routingHelper;
    }

    void initHearingAidDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        long hiSyncId = getHiSyncId(newDevice.getDevice());
        if (isValidHiSyncId(hiSyncId)) {
            // Once hiSyncId is valid, assign hearing aid info
            final HearingAidInfo.Builder infoBuilder = new HearingAidInfo.Builder()
                    .setAshaDeviceSide(getDeviceSide(newDevice.getDevice()))
                    .setAshaDeviceMode(getDeviceMode(newDevice.getDevice()))
                    .setHiSyncId(hiSyncId);
            newDevice.setHearingAidInfo(infoBuilder.build());
        }
    }

    private long getHiSyncId(BluetoothDevice device) {
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final HearingAidProfile profileProxy = profileManager.getHearingAidProfile();
        if (profileProxy == null) {
            return BluetoothHearingAid.HI_SYNC_ID_INVALID;
        }

        return profileProxy.getHiSyncId(device);
    }

    private int getDeviceSide(BluetoothDevice device) {
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final HearingAidProfile profileProxy = profileManager.getHearingAidProfile();
        if (profileProxy == null) {
            Log.w(TAG, "HearingAidProfile is not supported and not ready to fetch device side");
            return HearingAidProfile.DeviceSide.SIDE_INVALID;
        }

        return profileProxy.getDeviceSide(device);
    }

    private int getDeviceMode(BluetoothDevice device) {
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final HearingAidProfile profileProxy = profileManager.getHearingAidProfile();
        if (profileProxy == null) {
            Log.w(TAG, "HearingAidProfile is not supported and not ready to fetch device mode");
            return HearingAidProfile.DeviceMode.MODE_INVALID;
        }

        return profileProxy.getDeviceMode(device);
    }

    boolean setSubDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        final long hiSyncId = newDevice.getHiSyncId();
        if (isValidHiSyncId(hiSyncId)) {
            final CachedBluetoothDevice hearingAidDevice = getCachedDevice(hiSyncId);
            // Just add one of the hearing aids from a pair in the list that is shown in the UI.
            // Once there is another device with the same hiSyncId, to add new device as sub
            // device.
            if (hearingAidDevice != null) {
                hearingAidDevice.setSubDevice(newDevice);
                return true;
            }
        }
        return false;
    }

    private boolean isValidHiSyncId(long hiSyncId) {
        return hiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    private CachedBluetoothDevice getCachedDevice(long hiSyncId) {
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            if (cachedDevice.getHiSyncId() == hiSyncId) {
                return cachedDevice;
            }
        }
        return null;
    }

    // To collect all HearingAid devices and call #onHiSyncIdChanged to group device by HiSyncId
    void updateHearingAidsDevices() {
        final Set<Long> newSyncIdSet = new HashSet<Long>();
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            // Do nothing if HiSyncId has been assigned
            if (!isValidHiSyncId(cachedDevice.getHiSyncId())) {
                final long newHiSyncId = getHiSyncId(cachedDevice.getDevice());
                // Do nothing if there is no HiSyncId on Bluetooth device
                if (isValidHiSyncId(newHiSyncId)) {
                    // Once hiSyncId is valid, assign hearing aid info
                    final HearingAidInfo.Builder infoBuilder = new HearingAidInfo.Builder()
                            .setAshaDeviceSide(getDeviceSide(cachedDevice.getDevice()))
                            .setAshaDeviceMode(getDeviceMode(cachedDevice.getDevice()))
                            .setHiSyncId(newHiSyncId);
                    cachedDevice.setHearingAidInfo(infoBuilder.build());

                    newSyncIdSet.add(newHiSyncId);
                }
            }
        }
        for (Long syncId : newSyncIdSet) {
            onHiSyncIdChanged(syncId);
        }
    }

    // Group devices by hiSyncId
    @VisibleForTesting
    void onHiSyncIdChanged(long hiSyncId) {
        int firstMatchedIndex = -1;

        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            if (cachedDevice.getHiSyncId() != hiSyncId) {
                continue;
            }
            if (firstMatchedIndex == -1) {
                // Found the first one
                firstMatchedIndex = i;
                continue;
            }
            // Found the second one
            int indexToRemoveFromUi;
            CachedBluetoothDevice subDevice;
            CachedBluetoothDevice mainDevice;
            // Since the hiSyncIds have been updated for a connected pair of hearing aids,
            // we remove the entry of one the hearing aids from the UI. Unless the
            // hiSyncId get updated, the system does not know it is a hearing aid, so we add
            // both the hearing aids as separate entries in the UI first, then remove one
            // of them after the hiSyncId is populated. We will choose the device that
            // is not connected to be removed.
            if (cachedDevice.isConnected()) {
                mainDevice = cachedDevice;
                indexToRemoveFromUi = firstMatchedIndex;
                subDevice = mCachedDevices.get(firstMatchedIndex);
            } else {
                mainDevice = mCachedDevices.get(firstMatchedIndex);
                indexToRemoveFromUi = i;
                subDevice = cachedDevice;
            }

            mainDevice.setSubDevice(subDevice);
            mCachedDevices.remove(indexToRemoveFromUi);
            log("onHiSyncIdChanged: removed from UI device =" + subDevice
                    + ", with hiSyncId=" + hiSyncId);
            mBtManager.getEventManager().dispatchDeviceRemoved(subDevice);
            break;
        }
    }

    // @return {@code true}, the event is processed inside the method. It is for updating
    // hearing aid device on main-sub relationship when receiving connected or disconnected.
    // @return {@code false}, it is not hearing aid device or to process it same as other profiles
    boolean onProfileConnectionStateChangedIfProcessed(CachedBluetoothDevice cachedDevice,
            int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                onHiSyncIdChanged(cachedDevice.getHiSyncId());
                CachedBluetoothDevice mainDevice = findMainDevice(cachedDevice);
                if (mainDevice != null) {
                    if (mainDevice.isConnected()) {
                        // When main device exists and in connected state, receiving sub device
                        // connection. To refresh main device UI
                        mainDevice.refresh();
                    } else {
                        // When both Hearing Aid devices are disconnected, receiving sub device
                        // connection. To switch content and dispatch to notify UI change
                        mBtManager.getEventManager().dispatchDeviceRemoved(mainDevice);
                        mainDevice.switchSubDeviceContent();
                        mainDevice.refresh();
                        // It is necessary to do remove and add for updating the mapping on
                        // preference and device
                        mBtManager.getEventManager().dispatchDeviceAdded(mainDevice);
                        // Only need to set first device of a set. AudioDeviceInfo for
                        // GET_DEVICES_OUTPUTS will not change device.
                        setAudioRoutingConfig(cachedDevice);
                    }
                    return true;
                }
                // Only need to set first device of a set. AudioDeviceInfo for GET_DEVICES_OUTPUTS
                // will not change device.
                setAudioRoutingConfig(cachedDevice);
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                mainDevice = findMainDevice(cachedDevice);
                if (cachedDevice.getUnpairing()) {
                    return true;
                }
                if (mainDevice != null) {
                    // When main device exists, receiving sub device disconnection
                    // To update main device UI
                    mainDevice.refresh();
                    return true;
                }
                CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
                if (subDevice != null && subDevice.isConnected()) {
                    // Main device is disconnected and sub device is connected
                    // To copy data from sub device to main device
                    mBtManager.getEventManager().dispatchDeviceRemoved(cachedDevice);
                    cachedDevice.switchSubDeviceContent();
                    cachedDevice.refresh();
                    // It is necessary to do remove and add for updating the mapping on
                    // preference and device
                    mBtManager.getEventManager().dispatchDeviceAdded(cachedDevice);

                    return true;
                }
                // Only need to clear when last device of a set get disconnected
                clearAudioRoutingConfig();
                break;
        }
        return false;
    }

    private void setAudioRoutingConfig(CachedBluetoothDevice device) {
        AudioDeviceAttributes hearingDeviceAttributes =
                mRoutingHelper.getMatchedHearingDeviceAttributes(device);
        if (hearingDeviceAttributes == null) {
            Log.w(TAG, "Can not find expected AudioDeviceAttributes for hearing device: "
                    + device.getDevice().getAnonymizedAddress());
            return;
        }

        final int callRoutingValue = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.HEARING_AID_CALL_ROUTING,
                HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        final int mediaRoutingValue = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.HEARING_AID_MEDIA_ROUTING,
                HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        final int ringtoneRoutingValue = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.HEARING_AID_RINGTONE_ROUTING,
                HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        final int systemSoundsRoutingValue = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.HEARING_AID_SYSTEM_SOUNDS_ROUTING,
                HearingAidAudioRoutingConstants.RoutingValue.AUTO);

        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.CALL_ROUTING_ATTRIBUTES,
                hearingDeviceAttributes, callRoutingValue);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.MEDIA_ROUTING_ATTRIBUTES,
                hearingDeviceAttributes, mediaRoutingValue);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.RINGTONE_ROUTING_ATTRIBUTE,
                hearingDeviceAttributes, ringtoneRoutingValue);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.SYSTEM_SOUNDS_ROUTING_ATTRIBUTES,
                hearingDeviceAttributes, systemSoundsRoutingValue);
    }

    private void clearAudioRoutingConfig() {
        // Don't need to pass hearingDevice when we want to reset it (set to AUTO).
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.CALL_ROUTING_ATTRIBUTES,
                /* hearingDevice = */ null, HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.MEDIA_ROUTING_ATTRIBUTES,
                /* hearingDevice = */ null, HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.RINGTONE_ROUTING_ATTRIBUTE,
                /* hearingDevice = */ null, HearingAidAudioRoutingConstants.RoutingValue.AUTO);
        setPreferredDeviceRoutingStrategies(
                HearingAidAudioRoutingConstants.SYSTEM_SOUNDS_ROUTING_ATTRIBUTES,
                /* hearingDevice = */ null, HearingAidAudioRoutingConstants.RoutingValue.AUTO);
    }

    private void setPreferredDeviceRoutingStrategies(int[] attributeSdkUsageList,
            AudioDeviceAttributes hearingDevice,
            @HearingAidAudioRoutingConstants.RoutingValue int routingValue) {
        final List<AudioProductStrategy> supportedStrategies =
                mRoutingHelper.getSupportedStrategies(attributeSdkUsageList);

        final boolean status = mRoutingHelper.setPreferredDeviceRoutingStrategies(
                supportedStrategies, hearingDevice, routingValue);

        if (!status) {
            Log.w(TAG, "routingStrategies: " + supportedStrategies.toString() + "routingValue: "
                    + routingValue + " fail to configure AudioProductStrategy");
        }
    }

    CachedBluetoothDevice findMainDevice(CachedBluetoothDevice device) {
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (isValidHiSyncId(cachedDevice.getHiSyncId())) {
                CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
                if (subDevice != null && subDevice.equals(device)) {
                    return cachedDevice;
                }
            }
        }
        return null;
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}