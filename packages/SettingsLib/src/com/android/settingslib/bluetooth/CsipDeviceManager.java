/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.ChecksSdkIntAtLeast;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CsipDeviceManager manages the set of remote CSIP Bluetooth devices.
 */
public class CsipDeviceManager {
    private static final String TAG = "CsipDeviceManager";
    private static final boolean DEBUG = BluetoothUtils.D;

    private final LocalBluetoothManager mBtManager;
    private final List<CachedBluetoothDevice> mCachedDevices;

    CsipDeviceManager(LocalBluetoothManager localBtManager,
            List<CachedBluetoothDevice> cachedDevices) {
        mBtManager = localBtManager;
        mCachedDevices = cachedDevices;
    }

    void initCsipDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        // Current it only supports the base uuid for CSIP and group this set in UI.
        final int groupId = getBaseGroupId(newDevice.getDevice());
        if (isValidGroupId(groupId)) {
            log("initCsipDeviceIfNeeded: " + newDevice + " (group: " + groupId + ")");
            // Once groupId is valid, assign groupId
            newDevice.setGroupId(groupId);
        }
    }

    private int getBaseGroupId(BluetoothDevice device) {
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final CsipSetCoordinatorProfile profileProxy = profileManager
                .getCsipSetCoordinatorProfile();
        if (profileProxy != null) {
            final Map<Integer, ParcelUuid> groupIdMap = profileProxy
                    .getGroupUuidMapByDevice(device);
            if (groupIdMap == null) {
                return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
            }

            for (Map.Entry<Integer, ParcelUuid> entry : groupIdMap.entrySet()) {
                if (entry.getValue().equals(BluetoothUuid.CAP)) {
                    return entry.getKey();
                }
            }
        }
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    boolean setMemberDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        final int groupId = newDevice.getGroupId();
        if (isValidGroupId(groupId)) {
            final CachedBluetoothDevice mainDevice = getCachedDevice(groupId);
            log("setMemberDeviceIfNeeded, main: " + mainDevice + ", member: " + newDevice);
            // Just add one of the coordinated set from a pair in the list that is shown in the UI.
            // Once there is other devices with the same groupId, to add new device as member
            // devices.
            if (mainDevice != null) {
                mainDevice.addMemberDevice(newDevice);
                newDevice.setName(mainDevice.getName());
                return true;
            }
        }
        return false;
    }

    private boolean isValidGroupId(int groupId) {
        return groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    /**
     * To find the device with {@code groupId}.
     *
     * @param groupId The group id
     * @return if we could find a device with this {@code groupId} return this device. Otherwise,
     * return null.
     */
    public CachedBluetoothDevice getCachedDevice(int groupId) {
        log("getCachedDevice: groupId: " + groupId);
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            if (cachedDevice.getGroupId() == groupId) {
                log("getCachedDevice: found cachedDevice with the groupId: "
                        + cachedDevice.getDevice().getAnonymizedAddress());
                return cachedDevice;
            }
        }
        return null;
    }

    // To collect all set member devices and call #onGroupIdChanged to group device by GroupId
    void updateCsipDevices() {
        final Set<Integer> newGroupIdSet = new HashSet<Integer>();
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            // Do nothing if GroupId has been assigned
            if (!isValidGroupId(cachedDevice.getGroupId())) {
                final int newGroupId = getBaseGroupId(cachedDevice.getDevice());
                // Do nothing if there is no GroupId on Bluetooth device
                if (isValidGroupId(newGroupId)) {
                    cachedDevice.setGroupId(newGroupId);
                    newGroupIdSet.add(newGroupId);
                }
            }
        }
        for (int groupId : newGroupIdSet) {
            onGroupIdChanged(groupId);
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private static boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    // Group devices by groupId
    @VisibleForTesting
    void onGroupIdChanged(int groupId) {
        if (!isValidGroupId(groupId)) {
            log("onGroupIdChanged: groupId is invalid");
            return;
        }
        updateRelationshipOfGroupDevices(groupId);
    }

    // @return {@code true}, the event is processed inside the method. It is for updating
    // le audio device on group relationship when receiving connected or disconnected.
    // @return {@code false}, it is not le audio device or to process it same as other profiles
    boolean onProfileConnectionStateChangedIfProcessed(CachedBluetoothDevice cachedDevice,
            int state) {
        log("onProfileConnectionStateChangedIfProcessed: " + cachedDevice + ", state: " + state);

        if (state != BluetoothProfile.STATE_CONNECTED
                && state != BluetoothProfile.STATE_DISCONNECTED) {
            return false;
        }
        return updateRelationshipOfGroupDevices(cachedDevice.getGroupId());
    }

    @VisibleForTesting
    boolean updateRelationshipOfGroupDevices(int groupId) {
        if (!isValidGroupId(groupId)) {
            log("The device is not group.");
            return false;
        }
        log("updateRelationshipOfGroupDevices: mCachedDevices list =" + mCachedDevices.toString());

        // Get the preferred main device by getPreferredMainDeviceWithoutConectionState
        List<CachedBluetoothDevice> groupDevicesList = getGroupDevicesFromAllOfDevicesList(groupId);
        CachedBluetoothDevice preferredMainDevice =
                getPreferredMainDevice(groupId, groupDevicesList);
        log("The preferredMainDevice= " + preferredMainDevice
                + " and the groupDevicesList of groupId= " + groupId
                + " =" + groupDevicesList);
        return addMemberDevicesIntoMainDevice(groupId, preferredMainDevice);
    }

    CachedBluetoothDevice findMainDevice(CachedBluetoothDevice device) {
        if (device == null || mCachedDevices == null) {
            return null;
        }

        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (isValidGroupId(cachedDevice.getGroupId())) {
                Set<CachedBluetoothDevice> memberSet = cachedDevice.getMemberDevice();
                if (memberSet.isEmpty()) {
                    continue;
                }

                for (CachedBluetoothDevice memberDevice : memberSet) {
                    if (memberDevice != null && memberDevice.equals(device)) {
                        return cachedDevice;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if the {@code groupId} is existed.
     *
     * @param groupId The group id
     * @return {@code true}, if we could find a device with this {@code groupId}; Otherwise,
     * return {@code false}.
     */
    public boolean isExistedGroupId(int groupId) {
        return getCachedDevice(groupId) != null;
    }

    @VisibleForTesting
    List<CachedBluetoothDevice> getGroupDevicesFromAllOfDevicesList(int groupId) {
        List<CachedBluetoothDevice> groupDevicesList = new ArrayList<>();
        if (!isValidGroupId(groupId)) {
            return groupDevicesList;
        }
        for (CachedBluetoothDevice item : mCachedDevices) {
            if (groupId != item.getGroupId()) {
                continue;
            }
            groupDevicesList.add(item);
            groupDevicesList.addAll(item.getMemberDevice());
        }
        return groupDevicesList;
    }

    public CachedBluetoothDevice getFirstMemberDevice(int groupId) {
        List<CachedBluetoothDevice> members = getGroupDevicesFromAllOfDevicesList(groupId);
        if (members.isEmpty())
            return null;

        CachedBluetoothDevice firstMember = members.get(0);
        log("getFirstMemberDevice: groupId=" + groupId
                + " address=" + firstMember.getDevice().getAnonymizedAddress());
        return firstMember;
    }

    @VisibleForTesting
    CachedBluetoothDevice getPreferredMainDevice(int groupId,
            List<CachedBluetoothDevice> groupDevicesList) {
        // How to select the preferred main device?
        // 1. The DUAL mode connected device which has A2DP/HFP and LE audio.
        // 2. One of connected LE device in the list. Default is the lead device from LE profile.
        // 3. If there is no connected device, then reset the relationship. Set the DUAL mode
        // deviced as the main device. Otherwise, set any one of the device.
        if (groupDevicesList == null || groupDevicesList.isEmpty()) {
            return null;
        }

        CachedBluetoothDevice dualModeDevice = groupDevicesList.stream()
                .filter(cachedDevice -> cachedDevice.getConnectableProfiles().stream()
                        .anyMatch(profile -> profile instanceof LeAudioProfile))
                .filter(cachedDevice -> cachedDevice.getConnectableProfiles().stream()
                        .anyMatch(profile -> profile instanceof A2dpProfile
                                || profile instanceof HeadsetProfile))
                .findFirst().orElse(null);
        if (isDeviceConnected(dualModeDevice)) {
            log("getPreferredMainDevice: The connected DUAL mode device");
            return dualModeDevice;
        }

        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final CachedBluetoothDeviceManager deviceManager = mBtManager.getCachedDeviceManager();
        final LeAudioProfile leAudioProfile = profileManager.getLeAudioProfile();
        final BluetoothDevice leAudioLeadDevice = (leAudioProfile != null && isAtLeastT())
                ? leAudioProfile.getConnectedGroupLeadDevice(groupId) : null;

        if (leAudioLeadDevice != null) {
            log("getPreferredMainDevice: The LeadDevice from LE profile is "
                    + leAudioLeadDevice.getAnonymizedAddress());
        }
        CachedBluetoothDevice leAudioLeadCachedDevice =
                leAudioLeadDevice != null ? deviceManager.findDevice(leAudioLeadDevice) : null;
        if (leAudioLeadCachedDevice == null) {
            log("getPreferredMainDevice: The LeadDevice is not in the all of devices list");
        } else if (isDeviceConnected(leAudioLeadCachedDevice)) {
            log("getPreferredMainDevice: The connected LeadDevice from LE profile");
            return leAudioLeadCachedDevice;
        }
        CachedBluetoothDevice oneOfConnectedDevices =
                groupDevicesList.stream()
                        .filter(cachedDevice -> isDeviceConnected(cachedDevice))
                        .findFirst()
                        .orElse(null);
        if (oneOfConnectedDevices != null) {
            log("getPreferredMainDevice: One of the connected devices.");
            return oneOfConnectedDevices;
        }

        if (dualModeDevice != null) {
            log("getPreferredMainDevice: The DUAL mode device.");
            return dualModeDevice;
        }
        // last
        if (!groupDevicesList.isEmpty()) {
            log("getPreferredMainDevice: One of the group devices.");
            return groupDevicesList.get(0);
        }
        return null;
    }

    @VisibleForTesting
    boolean addMemberDevicesIntoMainDevice(int groupId, CachedBluetoothDevice preferredMainDevice) {
        boolean hasChanged = false;
        if (preferredMainDevice == null) {
            log("addMemberDevicesIntoMainDevice: No main device. Do nothing.");
            return hasChanged;
        }

        // If the current main device is not preferred main device, then set it as new main device.
        // Otherwise, do nothing.
        BluetoothDevice bluetoothDeviceOfPreferredMainDevice = preferredMainDevice.getDevice();
        CachedBluetoothDevice mainDeviceOfPreferredMainDevice = findMainDevice(preferredMainDevice);
        boolean hasPreferredMainDeviceAlreadyBeenMainDevice =
                mainDeviceOfPreferredMainDevice == null;

        if (!hasPreferredMainDeviceAlreadyBeenMainDevice) {
            // preferredMainDevice has not been the main device.
            // switch relationship between the mainDeviceOfPreferredMainDevice and
            // PreferredMainDevice

            log("addMemberDevicesIntoMainDevice: The PreferredMainDevice have the mainDevice. "
                    + "Do switch relationship between the mainDeviceOfPreferredMainDevice and "
                    + "PreferredMainDevice");
            // To switch content and dispatch to notify UI change
            mBtManager.getEventManager().dispatchDeviceRemoved(mainDeviceOfPreferredMainDevice);
            mainDeviceOfPreferredMainDevice.switchMemberDeviceContent(preferredMainDevice);
            mainDeviceOfPreferredMainDevice.refresh();
            // It is necessary to do remove and add for updating the mapping on
            // preference and device
            mBtManager.getEventManager().dispatchDeviceAdded(mainDeviceOfPreferredMainDevice);
            hasChanged = true;
        }

        // If the mCachedDevices List at CachedBluetoothDeviceManager has multiple items which are
        // the same groupId, then combine them and also keep the preferred main device as main
        // device.
        List<CachedBluetoothDevice> topLevelOfGroupDevicesList = mCachedDevices.stream()
                .filter(device -> device.getGroupId() == groupId)
                .collect(Collectors.toList());
        boolean haveMultiMainDevicesInAllOfDevicesList = topLevelOfGroupDevicesList.size() > 1;
        // Update the new main of CachedBluetoothDevice, since it may be changed in above step.
        final CachedBluetoothDeviceManager deviceManager = mBtManager.getCachedDeviceManager();
        preferredMainDevice = deviceManager.findDevice(bluetoothDeviceOfPreferredMainDevice);
        if (haveMultiMainDevicesInAllOfDevicesList) {
            // put another devices into main device.
            for (CachedBluetoothDevice deviceItem : topLevelOfGroupDevicesList) {
                if (deviceItem.getDevice() == null || deviceItem.getDevice().equals(
                        bluetoothDeviceOfPreferredMainDevice)) {
                    continue;
                }

                Set<CachedBluetoothDevice> memberSet = deviceItem.getMemberDevice();
                for (CachedBluetoothDevice memberSetItem : memberSet) {
                    if (!memberSetItem.equals(preferredMainDevice)) {
                        preferredMainDevice.addMemberDevice(memberSetItem);
                    }
                }
                memberSet.clear();
                preferredMainDevice.addMemberDevice(deviceItem);
                mCachedDevices.remove(deviceItem);
                mBtManager.getEventManager().dispatchDeviceRemoved(deviceItem);
                hasChanged = true;
            }
        }
        if (hasChanged) {
            log("addMemberDevicesIntoMainDevice: After changed, CachedBluetoothDevice list: "
                    + mCachedDevices);
        }
        return hasChanged;
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private boolean isDeviceConnected(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice == null) {
            return false;
        }
        final BluetoothDevice device = cachedDevice.getDevice();
        return cachedDevice.isConnected()
                && device.getBondState() == BluetoothDevice.BOND_BONDED
                && device.isConnected();
    }
}
