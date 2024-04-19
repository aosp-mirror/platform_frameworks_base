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

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HapClientProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.utils.ThreadUtils;

import java.util.List;

/**
 * The controller of the hearing devices presets of the bluetooth Hearing Access Profile.
 */
public class HearingDevicesPresetsController implements
        LocalBluetoothProfileManager.ServiceListener, BluetoothHapClient.Callback {

    private static final String TAG = "HearingDevicesPresetsController";
    private static final boolean DEBUG = true;

    private final LocalBluetoothProfileManager mProfileManager;
    private final HapClientProfile mHapClientProfile;
    private final PresetCallback mPresetCallback;

    private CachedBluetoothDevice mActiveHearingDevice;
    private int mSelectedPresetIndex;

    public HearingDevicesPresetsController(LocalBluetoothProfileManager profileManager,
            PresetCallback presetCallback) {
        mProfileManager = profileManager;
        mHapClientProfile = mProfileManager.getHapClientProfile();
        mPresetCallback = presetCallback;
    }

    @Override
    public void onServiceConnected() {
        if (mHapClientProfile != null && mHapClientProfile.isProfileReady()) {
            mProfileManager.removeServiceListener(this);
            registerHapCallback();
            mPresetCallback.onPresetInfoUpdated(getAllPresetInfo(), getActivePresetIndex());
        }
    }

    @Override
    public void onServiceDisconnected() {
        // Do nothing
    }

    @Override
    public void onPresetSelected(@NonNull BluetoothDevice device, int presetIndex, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (device.equals(mActiveHearingDevice.getDevice())) {
            if (DEBUG) {
                Log.d(TAG, "onPresetSelected, device: " + device.getAddress()
                        + ", presetIndex: " + presetIndex + ", reason: " + reason);
            }
            mPresetCallback.onPresetInfoUpdated(getAllPresetInfo(), getActivePresetIndex());
        }
    }

    @Override
    public void onPresetInfoChanged(@NonNull BluetoothDevice device,
            @NonNull List<BluetoothHapPresetInfo> presetInfoList, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (device.equals(mActiveHearingDevice.getDevice())) {
            if (DEBUG) {
                Log.d(TAG, "onPresetInfoChanged, device: " + device.getAddress()
                        + ", reason: " + reason + ", infoList: " + presetInfoList);
            }
            mPresetCallback.onPresetInfoUpdated(getAllPresetInfo(), getActivePresetIndex());
        }
    }

    @Override
    public void onPresetSelectionFailed(@NonNull BluetoothDevice device, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (device.equals(mActiveHearingDevice.getDevice())) {
            Log.w(TAG, "onPresetSelectionFailed, device: " + device.getAddress()
                    + ", reason: " + reason);
            mPresetCallback.onPresetCommandFailed(reason);
        }
    }

    @Override
    public void onPresetSelectionForGroupFailed(int hapGroupId, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (hapGroupId == mHapClientProfile.getHapGroup(mActiveHearingDevice.getDevice())) {
            Log.w(TAG, "onPresetSelectionForGroupFailed, group: " + hapGroupId
                    + ", reason: " + reason);
            selectPresetIndependently(mSelectedPresetIndex);
        }
    }

    @Override
    public void onSetPresetNameFailed(@NonNull BluetoothDevice device, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (device.equals(mActiveHearingDevice.getDevice())) {
            Log.w(TAG, "onSetPresetNameFailed, device: " + device.getAddress()
                    + ", reason: " + reason);
            mPresetCallback.onPresetCommandFailed(reason);
        }
    }

    @Override
    public void onSetPresetNameForGroupFailed(int hapGroupId, int reason) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (hapGroupId == mHapClientProfile.getHapGroup(mActiveHearingDevice.getDevice())) {
            Log.w(TAG, "onSetPresetNameForGroupFailed, group: " + hapGroupId
                    + ", reason: " + reason);
        }
        mPresetCallback.onPresetCommandFailed(reason);
    }

    /**
     * Registers a callback to be notified about operation changed for {@link HapClientProfile}.
     */
    public void registerHapCallback() {
        if (mHapClientProfile != null) {
            try {
                mHapClientProfile.registerCallback(ThreadUtils.getBackgroundExecutor(), this);
            } catch (IllegalArgumentException e) {
                // The callback was already registered
                Log.w(TAG, "Cannot register callback: " + e.getMessage());
            }

        }
    }

    /**
     * Removes a previously-added {@link HapClientProfile} callback.
     */
    public void unregisterHapCallback() {
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
     * Sets the hearing device for this controller to control the preset.
     *
     * @param activeHearingDevice the {@link CachedBluetoothDevice} need to be hearing aid device
     */
    public void setActiveHearingDevice(CachedBluetoothDevice activeHearingDevice) {
        mActiveHearingDevice = activeHearingDevice;
    }

    /**
     * Selects the currently active preset for {@code mActiveHearingDevice} individual device or
     * the device group accoridng to whether it supports synchronized presets or not.
     *
     * @param presetIndex an index of one of the available presets
     */
    public void selectPreset(int presetIndex) {
        if (mActiveHearingDevice == null) {
            return;
        }
        mSelectedPresetIndex = presetIndex;
        boolean supportSynchronizedPresets = mHapClientProfile.supportsSynchronizedPresets(
                mActiveHearingDevice.getDevice());
        int hapGroupId = mHapClientProfile.getHapGroup(mActiveHearingDevice.getDevice());
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

    /**
     * Gets all preset info for {@code mActiveHearingDevice} device.
     *
     * @return a list of all known preset info
     */
    public List<BluetoothHapPresetInfo> getAllPresetInfo() {
        if (mActiveHearingDevice == null) {
            return emptyList();
        }
        return mHapClientProfile.getAllPresetInfo(mActiveHearingDevice.getDevice());
    }

    /**
     * Gets the currently active preset for {@code mActiveHearingDevice} device.
     *
     * @return active preset index
     */
    public int getActivePresetIndex() {
        if (mActiveHearingDevice == null) {
            return BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        }
        return mHapClientProfile.getActivePresetIndex(mActiveHearingDevice.getDevice());
    }

    private void selectPresetSynchronously(int groupId, int presetIndex) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "selectPresetSynchronously"
                    + ", presetIndex: " + presetIndex
                    + ", groupId: " + groupId
                    + ", device: " + mActiveHearingDevice.getAddress());
        }
        mHapClientProfile.selectPresetForGroup(groupId, presetIndex);
    }

    private void selectPresetIndependently(int presetIndex) {
        if (mActiveHearingDevice == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "selectPresetIndependently"
                    + ", presetIndex: " + presetIndex
                    + ", device: " + mActiveHearingDevice.getAddress());
        }
        mHapClientProfile.selectPreset(mActiveHearingDevice.getDevice(), presetIndex);
        final CachedBluetoothDevice subDevice = mActiveHearingDevice.getSubDevice();
        if (subDevice != null) {
            if (DEBUG) {
                Log.d(TAG, "selectPreset for subDevice, device: " + subDevice);
            }
            mHapClientProfile.selectPreset(subDevice.getDevice(), presetIndex);
        }
        for (final CachedBluetoothDevice memberDevice :
                mActiveHearingDevice.getMemberDevice()) {
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
         * @param presetInfos all preset info for {@code mActiveHearingDevice} device
         * @param activePresetIndex currently active preset index for {@code mActiveHearingDevice}
         *                          device
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
