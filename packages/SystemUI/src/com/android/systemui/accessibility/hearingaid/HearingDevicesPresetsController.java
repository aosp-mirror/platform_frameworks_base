/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static java.util.Collections.emptyList;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HapClientProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The controller of handling hearing device preset with Bluetooth Hearing Access Profile(HAP).
 */
public class HearingDevicesPresetsController implements
        LocalBluetoothProfileManager.ServiceListener, BluetoothHapClient.Callback {

    private static final String TAG = "HearingDevicesPresetsController";
    private static final boolean DEBUG = true;

    private final LocalBluetoothProfileManager mProfileManager;
    private final HapClientProfile mHapClientProfile;
    private final PresetCallback mPresetCallback;

    private CachedBluetoothDevice mDevice;
    private List<BluetoothHapPresetInfo> mPresetInfos = new ArrayList<>();
    private int mActivePresetIndex = BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
    private int mSelectedPresetIndex;

    public HearingDevicesPresetsController(@NonNull LocalBluetoothProfileManager profileManager,
            @Nullable PresetCallback presetCallback) {
        mProfileManager = profileManager;
        mHapClientProfile = mProfileManager.getHapClientProfile();
        mPresetCallback = presetCallback;
    }

    @Override
    public void onServiceConnected() {
        if (mHapClientProfile != null && mHapClientProfile.isProfileReady()) {
            mProfileManager.removeServiceListener(this);
            registerHapCallback();
            refreshPresetInfo();
        }
    }

    @Override
    public void onServiceDisconnected() {
        // Do nothing
    }

    @Override
    public void onPresetSelected(@NonNull BluetoothDevice device, int presetIndex, int reason) {
        if (mDevice == null) {
            return;
        }
        if (device.equals(mDevice.getDevice())) {
            if (DEBUG) {
                Log.d(TAG, "onPresetSelected, device: " + device.getAddress()
                        + ", presetIndex: " + presetIndex + ", reason: " + reason);
            }
            refreshPresetInfo();
        }
    }

    @Override
    public void onPresetInfoChanged(@NonNull BluetoothDevice device,
            @NonNull List<BluetoothHapPresetInfo> presetInfoList, int reason) {
        if (mDevice == null) {
            return;
        }
        if (device.equals(mDevice.getDevice())) {
            if (DEBUG) {
                Log.d(TAG, "onPresetInfoChanged, device: " + device.getAddress()
                        + ", reason: " + reason + ", infoList: " + presetInfoList);
            }
            refreshPresetInfo();
        }
    }

    @Override
    public void onPresetSelectionFailed(@NonNull BluetoothDevice device, int reason) {
        if (mDevice == null) {
            return;
        }
        if (device.equals(mDevice.getDevice())) {
            Log.w(TAG, "onPresetSelectionFailed, device: " + device.getAddress()
                    + ", reason: " + reason);
            if (mPresetCallback != null) {
                mPresetCallback.onPresetCommandFailed(reason);
            }
        }
    }

    @Override
    public void onPresetSelectionForGroupFailed(int hapGroupId, int reason) {
        if (mDevice == null || mHapClientProfile == null) {
            return;
        }
        if (hapGroupId == mHapClientProfile.getHapGroup(mDevice.getDevice())) {
            Log.w(TAG, "onPresetSelectionForGroupFailed, group: " + hapGroupId
                    + ", reason: " + reason);
            selectPresetIndependently(mSelectedPresetIndex);
        }
    }

    @Override
    public void onSetPresetNameFailed(@NonNull BluetoothDevice device, int reason) {
        if (mDevice == null) {
            return;
        }
        if (device.equals(mDevice.getDevice())) {
            Log.w(TAG, "onSetPresetNameFailed, device: " + device.getAddress()
                    + ", reason: " + reason);
            if (mPresetCallback != null) {
                mPresetCallback.onPresetCommandFailed(reason);
            }
        }
    }

    @Override
    public void onSetPresetNameForGroupFailed(int hapGroupId, int reason) {
        if (mDevice == null || mHapClientProfile == null) {
            return;
        }
        if (hapGroupId == mHapClientProfile.getHapGroup(mDevice.getDevice())) {
            Log.w(TAG, "onSetPresetNameForGroupFailed, group: " + hapGroupId
                    + ", reason: " + reason);
        }
        if (mPresetCallback != null) {
            mPresetCallback.onPresetCommandFailed(reason);
        }
    }

    /**
     * Registers a callback to be notified about operation changed of {@link HapClientProfile}.
     */
    public void registerHapCallback() {
        if (mHapClientProfile != null) {
            if (!mHapClientProfile.isProfileReady()) {
                mProfileManager.addServiceListener(this);
                Log.w(TAG, "Profile is not ready yet, the callback will be registered once the "
                        + "profile is ready.");
                return;
            }
            try {
                mHapClientProfile.registerCallback(ThreadUtils.getBackgroundExecutor(), this);
            } catch (IllegalArgumentException e) {
                // The callback was already registered
                Log.w(TAG, "Cannot register callback: " + e.getMessage());
            }

        }
    }

    /**
     * Removes a previously-added {@link HapClientProfile} callback if exist.
     */
    public void unregisterHapCallback() {
        mProfileManager.removeServiceListener(this);
        if (mHapClientProfile != null) {
            try {
                mHapClientProfile.unregisterCallback(this);
            } catch (IllegalArgumentException e) {
                // The callback was never registered or was already unregistered
                Log.w(TAG, "Cannot unregister callback: " + e.getMessage());
            }
        }
    }

    /**
     * Sets the device for this controller to control the preset if it supports
     * {@link HapClientProfile}, otherwise the device of this controller will be {@code null}.
     *
     * @param device the {@link CachedBluetoothDevice} set to the controller
     */
    public void setDevice(@Nullable CachedBluetoothDevice device) {
        if (device != null && device.getProfiles().stream().anyMatch(
                profile -> profile instanceof HapClientProfile)) {
            mDevice = device;
        } else {
            mDevice = null;
        }
        refreshPresetInfo();
    }

    /**
     * Refreshes the preset info of {@code mDevice}. If the preset info list or the active preset
     * index is updated, the {@link PresetCallback#onPresetInfoUpdated(List, int)} will be called
     * to notify the change.
     *
     * <b>Note:</b> If {@code mDevice} is null, the cached preset info and active preset index will
     * be reset to empty list and {@code BluetoothHapClient.PRESET_INDEX_UNAVAILABLE} respectively.
     */
    public void refreshPresetInfo() {
        List<BluetoothHapPresetInfo> updatedInfos = new ArrayList<>();
        int updatedActiveIndex = BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        if (mHapClientProfile != null && mDevice != null) {
            updatedInfos = mHapClientProfile.getAllPresetInfo(mDevice.getDevice()).stream().filter(
                    BluetoothHapPresetInfo::isAvailable).toList();
            updatedActiveIndex = mHapClientProfile.getActivePresetIndex(mDevice.getDevice());
        }
        final boolean infoUpdated = !mPresetInfos.equals(updatedInfos);
        final boolean activeIndexUpdated = mActivePresetIndex != updatedActiveIndex;
        mPresetInfos = updatedInfos;
        mActivePresetIndex = updatedActiveIndex;
        if (infoUpdated || activeIndexUpdated) {
            if (mPresetCallback != null) {
                mPresetCallback.onPresetInfoUpdated(mPresetInfos, mActivePresetIndex);
            }
        }
    }

    /**
     * @return if the preset control is available. The preset control is available only
     * when the {@code mDevice} supports HAP and the retrieved preset info list is not empty.
     */
    public boolean isPresetControlAvailable() {
        boolean deviceValid = mDevice != null && mDevice.isConnectedHapClientDevice();
        boolean hasPreset = mPresetInfos != null && !mPresetInfos.isEmpty();
        return deviceValid && hasPreset;
    }

    /**
     * @return a list of {@link BluetoothHapPresetInfo} retrieved from {@code mDevice}
     */
    public List<BluetoothHapPresetInfo> getAllPresetInfo() {
        if (mDevice == null || mHapClientProfile == null) {
            return emptyList();
        }
        return mPresetInfos;
    }

    /**
     * Gets the currently active preset of {@code mDevice}.
     *
     * @return active preset index
     */
    public int getActivePresetIndex() {
        if (mDevice == null || mHapClientProfile == null) {
            return BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        }
        return mActivePresetIndex;
    }

    /**
     * Selects the preset for {@code mDevice}. Performs individual or group operation according
     * to whether the device supports synchronized presets feature or not.
     *
     * @param presetIndex an index of one of the available presets
     */
    public void selectPreset(int presetIndex) {
        if (mDevice == null || mHapClientProfile == null) {
            return;
        }
        mSelectedPresetIndex = presetIndex;
        boolean supportSynchronizedPresets = mHapClientProfile.supportsSynchronizedPresets(
                mDevice.getDevice());
        int hapGroupId = mHapClientProfile.getHapGroup(mDevice.getDevice());
        if (supportSynchronizedPresets) {
            if (hapGroupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                selectPresetSynchronously(hapGroupId, presetIndex);
            } else {
                Log.w(TAG, "supportSynchronizedPresets but hapGroupId is invalid.");
                selectPresetIndependently(presetIndex);
            }
        } else {
            selectPresetIndependently(presetIndex);
        }
    }

    private void selectPresetSynchronously(int groupId, int presetIndex) {
        if (mDevice == null || mHapClientProfile == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "selectPresetSynchronously"
                    + ", presetIndex: " + presetIndex
                    + ", groupId: " + groupId
                    + ", device: " + mDevice.getAddress());
        }
        mHapClientProfile.selectPresetForGroup(groupId, presetIndex);
    }

    private void selectPresetIndependently(int presetIndex) {
        if (mDevice == null || mHapClientProfile == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "selectPresetIndependently"
                    + ", presetIndex: " + presetIndex
                    + ", device: " + mDevice.getAddress());
        }
        mHapClientProfile.selectPreset(mDevice.getDevice(), presetIndex);
        final CachedBluetoothDevice subDevice = mDevice.getSubDevice();
        if (subDevice != null) {
            if (DEBUG) {
                Log.d(TAG, "selectPreset for subDevice, device: " + subDevice);
            }
            mHapClientProfile.selectPreset(subDevice.getDevice(), presetIndex);
        }
        for (final CachedBluetoothDevice memberDevice : mDevice.getMemberDevice()) {
            if (DEBUG) {
                Log.d(TAG, "selectPreset for memberDevice, device: " + memberDevice);
            }
            mHapClientProfile.selectPreset(memberDevice.getDevice(), presetIndex);
        }
    }

    /**
     * Interface to provide callbacks when preset command result from {@link HapClientProfile}
     * changed.
     */
    public interface PresetCallback {
        /**
         * Called when preset info from {@link HapClientProfile} operation get updated.
         *
         * @param presetInfos all preset info of {@code mDevice}
         * @param activePresetIndex currently active preset index of {@code mDevice}
         */
        void onPresetInfoUpdated(List<BluetoothHapPresetInfo> presetInfos, int activePresetIndex);

        /**
         * Called when preset operation from {@link HapClientProfile} failed to handle.
         *
         * @param reason failure reason
         */
        void onPresetCommandFailed(int reason);
    }
}
