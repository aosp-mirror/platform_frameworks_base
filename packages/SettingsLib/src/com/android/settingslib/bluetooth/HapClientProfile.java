/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * HapClientProfile handles the Bluetooth HAP service client role.
 */
public class HapClientProfile implements LocalBluetoothProfile {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            HearingAidType.TYPE_INVALID,
            HearingAidType.TYPE_BINAURAL,
            HearingAidType.TYPE_MONAURAL,
            HearingAidType.TYPE_BANDED,
            HearingAidType.TYPE_RFU
    })

    /** Hearing aid type definition for HAP Client. */
    public @interface HearingAidType {
        int TYPE_INVALID = -1;
        int TYPE_BINAURAL = BluetoothHapClient.TYPE_BINAURAL;
        int TYPE_MONAURAL = BluetoothHapClient.TYPE_MONAURAL;
        int TYPE_BANDED = BluetoothHapClient.TYPE_BANDED;
        int TYPE_RFU = BluetoothHapClient.TYPE_RFU;
    }

    static final String NAME = "HapClient";
    private static final String TAG = "HapClientProfile";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private final BluetoothAdapter mBluetoothAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;
    private BluetoothHapClient mService;
    private boolean mIsProfileReady;

    // These callbacks run on the main thread.
    private final class HapClientServiceListener implements BluetoothProfile.ServiceListener {

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHapClient) proxy;
            // We just bound to the service, so refresh the UI for any connected HapClient devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // Adds a new device into mDeviceManager if it does not exist
                if (device == null) {
                    Log.w(TAG, "HapClient profile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(
                        HapClientProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mIsProfileReady = true;
            mProfileManager.callServiceConnectedListeners();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mIsProfileReady = false;
            mProfileManager.callServiceDisconnectedListeners();
        }
    }

    HapClientProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();
            mBluetoothAdapter.getProfileProxy(context, new HapClientServiceListener(),
                    BluetoothProfile.HAP_CLIENT);
        } else {
            mBluetoothAdapter = null;
        }
    }

    /**
     * Registers a {@link BluetoothHapClient.Callback} that will be invoked during the
     * operation of this profile.
     *
     * Repeated registration of the same <var>callback</var> object after the first call to this
     * method will result with IllegalArgumentException being thrown, even when the
     * <var>executor</var> is different. API caller would have to call
     * {@link #unregisterCallback(BluetoothHapClient.Callback)} with the same callback object
     * before registering it again.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link BluetoothHapClient.Callback}
     * @throws NullPointerException if a null executor, or callback is given, or
     *  IllegalArgumentException if the same <var>callback</var> is already registered.
     * @hide
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothHapClient.Callback callback) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot register callback.");
            return;
        }
        mService.registerCallback(executor, callback);
    }

    /**
     * Unregisters the specified {@link BluetoothHapClient.Callback}.
     * <p>The same {@link BluetoothHapClient.Callback} object used when calling
     * {@link #registerCallback(Executor, BluetoothHapClient.Callback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link BluetoothHapClient.Callback}
     * @throws NullPointerException when callback is null or IllegalArgumentException when no
     *  callback is registered
     * @hide
     */
    public void unregisterCallback(@NonNull BluetoothHapClient.Callback callback) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot unregister callback.");
            return;
        }
        mService.unregisterCallback(callback);
    }

    /**
     * Gets hearing aid devices matching connection states{
     * {@code BluetoothProfile.STATE_CONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTING},
     * {@code BluetoothProfile.STATE_DISCONNECTING}}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    /**
     * Gets hearing aid devices matching connection states{
     * {@code BluetoothProfile.STATE_DISCONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTING},
     * {@code BluetoothProfile.STATE_DISCONNECTING}}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectableDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    private List<BluetoothDevice> getDevicesByStates(int[] states) {
        if (mService == null) {
            return new ArrayList<>(0);
        }
        return mService.getDevicesMatchingConnectionStates(states);
    }

    /**
     * Gets the hearing aid type of the device.
     *
     * @param device is the device for which we want to get the hearing aid type
     * @return hearing aid type
     */
    @HearingAidType
    public int getHearingAidType(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return HearingAidType.TYPE_INVALID;
        }
        return mService.getHearingAidType(device);
    }

    /**
     * Gets if this device supports synchronized presets or not
     *
     * @param device is the device for which we want to know if supports synchronized presets
     * @return {@code true} if the device supports synchronized presets
     */
    public boolean supportsSynchronizedPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsSynchronizedPresets(device);
    }

    /**
     * Gets if this device supports independent presets or not
     *
     * @param device is the device for which we want to know if supports independent presets
     * @return {@code true} if the device supports independent presets
     */
    public boolean supportsIndependentPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsIndependentPresets(device);
    }

    /**
     * Gets if this device supports dynamic presets or not
     *
     * @param device is the device for which we want to know if supports dynamic presets
     * @return {@code true} if the device supports dynamic presets
     */
    public boolean supportsDynamicPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsDynamicPresets(device);
    }

    /**
     * Gets if this device supports writable presets or not
     *
     * @param device is the device for which we want to know if supports writable presets
     * @return {@code true} if the device supports writable presets
     */
    public boolean supportsWritablePresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsWritablePresets(device);
    }


    /**
     * Gets the group identifier, which can be used in the group related part of the API.
     *
     * <p>Users are expected to get group identifier for each of the connected device to discover
     * the device grouping. This allows them to make an informed decision which devices can be
     * controlled by single group API call and which require individual device calls.
     *
     * <p>Note that some binaural HA devices may not support group operations, therefore are not
     * considered a valid HAP group. In such case -1 is returned even if such device is a valid Le
     * Audio Coordinated Set member.
     *
     * @param device is the device for which we want to get the hap group identifier
     * @return valid group identifier or -1
     * @hide
     */
    public int getHapGroup(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get hap group.");
            return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }
        return mService.getHapGroup(device);
    }

    /**
     * Gets the currently active preset for a HA device.
     *
     * @param device is the device for which we want to set the active preset
     * @return active preset index or {@link BluetoothHapClient#PRESET_INDEX_UNAVAILABLE} if the
     *         device is not connected.
     * @hide
     */
    public int getActivePresetIndex(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get active preset index.");
            return BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        }
        return mService.getActivePresetIndex(device);
    }

    /**
     * Gets the currently active preset info for a remote device.
     *
     * @param device is the device for which we want to get the preset name
     * @return currently active preset info if selected, null if preset info is not available for
     *     the remote device
     * @hide
     */
    @Nullable
    public BluetoothHapPresetInfo getActivePresetInfo(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get active preset info.");
            return null;
        }
        return mService.getActivePresetInfo(device);
    }

    /**
     * Selects the currently active preset for a HA device
     *
     * <p>On success,
     * {@link BluetoothHapClient.Callback#onPresetSelected(BluetoothDevice, int, int)} will be
     * called with reason code {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} On failure,
     * {@link BluetoothHapClient.Callback#onPresetSelectionFailed(BluetoothDevice, int)} will be
     * called.
     *
     * @param device is the device for which we want to set the active preset
     * @param presetIndex is an index of one of the available presets
     * @hide
     */
    public void selectPreset(@NonNull BluetoothDevice device, int presetIndex) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot select preset.");
            return;
        }
        mService.selectPreset(device, presetIndex);
    }


    /**
     * Selects the currently active preset for a Hearing Aid device group.
     *
     * <p>This group call may replace multiple device calls if those are part of the valid HAS
     * group. Note that binaural HA devices may or may not support group.
     *
     * <p>On success,
     * {@link BluetoothHapClient.Callback#onPresetSelected(BluetoothDevice, int, int)} will be
     * called for each device within the group with reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} On failure,
     * {@link BluetoothHapClient.Callback#onPresetSelectionForGroupFailed(int, int)} will be
     * called for the group.
     *
     * @param groupId is the device group identifier for which want to set the active preset
     * @param presetIndex is an index of one of the available presets
     * @hide
     */
    public void selectPresetForGroup(int groupId, int presetIndex) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot select preset for group.");
            return;
        }
        mService.selectPresetForGroup(groupId, presetIndex);
    }

    /**
     * Sets the next preset as a currently active preset for a HA device
     *
     * <p>Note that the meaning of 'next' is HA device implementation specific and does not
     * necessarily mean a higher preset index.
     *
     * @param device is the device for which we want to set the active preset
     * @hide
     */
    public void switchToNextPreset(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot switch to next preset.");
            return;
        }
        mService.switchToNextPreset(device);
    }


    /**
     * Sets the next preset as a currently active preset for a HA device group
     *
     * <p>Note that the meaning of 'next' is HA device implementation specific and does not
     * necessarily mean a higher preset index.
     *
     * <p>This group call may replace multiple device calls if those are part of the valid HAS
     * group. Note that binaural HA devices may or may not support group.
     *
     * @param groupId is the device group identifier for which want to set the active preset
     * @hide
     */
    public void switchToNextPresetForGroup(int groupId) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot switch to next preset for group.");
            return;
        }
        mService.switchToNextPresetForGroup(groupId);
    }

    /**
     * Sets the previous preset as a currently active preset for a HA device.
     *
     * <p>Note that the meaning of 'previous' is HA device implementation specific and does not
     * necessarily mean a lower preset index.
     *
     * @param device is the device for which we want to set the active preset
     * @hide
     */
    public void switchToPreviousPreset(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot switch to previous preset.");
            return;
        }
        mService.switchToPreviousPreset(device);
    }


    /**
     * Sets the next preset as a currently active preset for a HA device group
     *
     * <p>Note that the meaning of 'next' is HA device implementation specific and does not
     * necessarily mean a higher preset index.
     *
     * <p>This group call may replace multiple device calls if those are part of the valid HAS
     * group. Note that binaural HA devices may or may not support group.
     *
     * @param groupId is the device group identifier for which want to set the active preset
     * @hide
     */
    public void switchToPreviousPresetForGroup(int groupId) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot switch to previous preset for "
                    + "group.");
            return;
        }
        mService.switchToPreviousPresetForGroup(groupId);
    }

    /**
     * Requests the preset info
     *
     * @param device is the device for which we want to get the preset name
     * @param presetIndex is an index of one of the available presets
     * @return preset info
     * @hide
     */
    public BluetoothHapPresetInfo getPresetInfo(@NonNull BluetoothDevice device, int presetIndex) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get preset info.");
            return null;
        }
        return mService.getPresetInfo(device, presetIndex);
    }

    /**
     * Gets all preset info for a particular device
     *
     * @param device is the device for which we want to get all presets info
     * @return a list of all known preset info
     * @hide
     */
    @NonNull
    public List<BluetoothHapPresetInfo> getAllPresetInfo(@NonNull BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get all preset info.");
            return new ArrayList<>();
        }
        return mService.getAllPresetInfo(device);
    }

    /**
     * Sets the preset name for a particular device
     *
     * <p>Note that the name length is restricted to 40 characters.
     *
     * <p>On success,
     * {@link BluetoothHapClient.Callback#onPresetInfoChanged(BluetoothDevice, List, int)} with a
     * new name will be called and reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} On failure,
     * {@link BluetoothHapClient.Callback#onSetPresetNameFailed(BluetoothDevice, int)} will be
     * called.
     *
     * @param device is the device for which we want to get the preset name
     * @param presetIndex is an index of one of the available presets
     * @param name is a new name for a preset, maximum length is 40 characters
     * @hide
     */
    public void setPresetName(@NonNull BluetoothDevice device, int presetIndex,
            @NonNull String name) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot set preset name.");
            return;
        }
        mService.setPresetName(device, presetIndex, name);
    }

    /**
     * Sets the name for a hearing aid preset.
     *
     * <p>Note that the name length is restricted to 40 characters.
     *
     * <p>On success,
     * {@link BluetoothHapClient.Callback#onPresetInfoChanged(BluetoothDevice, List, int)} with a
     * new name will be called for each device within the group with reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} On failure,
     * {@link BluetoothHapClient.Callback#onSetPresetNameForGroupFailed(int, int)} will be invoked
     *
     * @param groupId is the device group identifier
     * @param presetIndex is an index of one of the available presets
     * @param name is a new name for a preset, maximum length is 40 characters
     * @hide
     */
    public void setPresetNameForGroup(int groupId, int presetIndex, @NonNull String name) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot set preset name for group.");
            return;
        }
        mService.setPresetNameForGroup(groupId, presetIndex, name);
    }


    @Override
    public boolean accessProfileEnabled() {
        return false;
    }

    @Override
    public boolean isAutoConnectable() {
        return true;
    }

    @Override
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null || device == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null || device == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null || device == null) {
            return false;
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.HAP_CLIENT;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_hearing_aid;
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_hearing_aid_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_hearing_aid_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_hearing_aid;
    }

    /**
     * Gets the name of this class
     *
     * @return the name of this class
     */
    public String toString() {
        return NAME;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HAP_CLIENT, mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HAP Client proxy", t);
            }
        }
    }
}
