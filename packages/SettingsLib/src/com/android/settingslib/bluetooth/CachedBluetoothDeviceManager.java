/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CachedBluetoothDeviceManager manages the set of remote Bluetooth devices.
 */
public class CachedBluetoothDeviceManager {
    private static final String TAG = "CachedBluetoothDeviceManager";
    private static final boolean DEBUG = BluetoothUtils.D;

    @VisibleForTesting static int sLateBondingTimeoutMillis = 10000; // 10s

    private Context mContext;
    private final LocalBluetoothManager mBtManager;

    @VisibleForTesting
    final List<CachedBluetoothDevice> mCachedDevices = new ArrayList<CachedBluetoothDevice>();
    @VisibleForTesting
    HearingAidDeviceManager mHearingAidDeviceManager;
    @VisibleForTesting
    CsipDeviceManager mCsipDeviceManager;
    BluetoothDevice mOngoingSetMemberPair;
    boolean mIsLateBonding;
    int mGroupIdOfLateBonding;

    public CachedBluetoothDeviceManager(Context context, LocalBluetoothManager localBtManager) {
        mContext = context;
        mBtManager = localBtManager;
        mHearingAidDeviceManager = new HearingAidDeviceManager(context, localBtManager,
                mCachedDevices);
        mCsipDeviceManager = new CsipDeviceManager(localBtManager, mCachedDevices);
    }

    public synchronized Collection<CachedBluetoothDevice> getCachedDevicesCopy() {
        return new ArrayList<>(mCachedDevices);
    }

    public static boolean onDeviceDisappeared(CachedBluetoothDevice cachedDevice) {
        cachedDevice.setJustDiscovered(false);
        return cachedDevice.getBondState() == BluetoothDevice.BOND_NONE;
    }

    public void onDeviceNameUpdated(BluetoothDevice device) {
        CachedBluetoothDevice cachedDevice = findDevice(device);
        if (cachedDevice != null) {
            cachedDevice.refreshName();
        }
    }

    /**
     * Search for existing {@link CachedBluetoothDevice} or return null
     * if this device isn't in the cache. Use {@link #addDevice}
     * to create and return a new {@link CachedBluetoothDevice} for
     * a newly discovered {@link BluetoothDevice}.
     *
     * @param device the address of the Bluetooth device
     * @return the cached device object for this device, or null if it has
     *   not been previously seen
     */
    public synchronized CachedBluetoothDevice findDevice(BluetoothDevice device) {
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (cachedDevice.getDevice().equals(device)) {
                return cachedDevice;
            }
            // Check the member devices for the coordinated set if it exists
            final Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
            if (!memberDevices.isEmpty()) {
                for (CachedBluetoothDevice memberDevice : memberDevices) {
                    if (memberDevice.getDevice().equals(device)) {
                        return memberDevice;
                    }
                }
            }
            // Check sub devices for hearing aid if it exists
            CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
            if (subDevice != null && subDevice.getDevice().equals(device)) {
                return subDevice;
            }
        }

        return null;
    }

    /**
     * Create and return a new {@link CachedBluetoothDevice}. This assumes
     * that {@link #findDevice} has already been called and returned null.
     * @param device the new Bluetooth device
     * @return the newly created CachedBluetoothDevice object
     */
    public CachedBluetoothDevice addDevice(BluetoothDevice device) {
        return addDevice(device, /*leScanFilters=*/null);
    }

    /**
     * Create and return a new {@link CachedBluetoothDevice}. This assumes
     * that {@link #findDevice} has already been called and returned null.
     * @param device the new Bluetooth device
     * @param leScanFilters the BLE scan filters which the device matched
     * @return the newly created CachedBluetoothDevice object
     */
    public CachedBluetoothDevice addDevice(BluetoothDevice device, List<ScanFilter> leScanFilters) {
        CachedBluetoothDevice newDevice;
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        synchronized (this) {
            newDevice = findDevice(device);
            if (newDevice == null) {
                newDevice = new CachedBluetoothDevice(mContext, profileManager, device);
                mCsipDeviceManager.initCsipDeviceIfNeeded(newDevice);
                mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(newDevice, leScanFilters);
                if (!mCsipDeviceManager.setMemberDeviceIfNeeded(newDevice)
                        && !mHearingAidDeviceManager.setSubDeviceIfNeeded(newDevice)) {
                    mCachedDevices.add(newDevice);
                    mBtManager.getEventManager().dispatchDeviceAdded(newDevice);
                }
            }
        }

        return newDevice;
    }

    /**
     * Returns device summary of the pair of the hearing aid / CSIP passed as the parameter.
     *
     * @param CachedBluetoothDevice device
     * @return Device summary, or if the pair does not exist or if it is not a hearing aid or
     * a CSIP set member, then {@code null}.
     */
    public synchronized String getSubDeviceSummary(CachedBluetoothDevice device) {
        final Set<CachedBluetoothDevice> memberDevices = device.getMemberDevice();
        // TODO: check the CSIP group size instead of the real member device set size, and adjust
        // the size restriction.
        if (!memberDevices.isEmpty()) {
            for (CachedBluetoothDevice memberDevice : memberDevices) {
                if (memberDevice.isConnected()) {
                    return memberDevice.getConnectionSummary();
                }
            }
        }
        CachedBluetoothDevice subDevice = device.getSubDevice();
        if (subDevice != null && subDevice.isConnected()) {
            return subDevice.getConnectionSummary();
        }
        return null;
    }

    /**
     * Search for existing sub device {@link CachedBluetoothDevice}.
     *
     * @param device the address of the Bluetooth device
     * @return true for found sub / member device or false.
     */
    public synchronized boolean isSubDevice(BluetoothDevice device) {
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (!cachedDevice.getDevice().equals(device)) {
                // Check the member devices of the coordinated set if it exists
                Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
                if (!memberDevices.isEmpty()) {
                    for (CachedBluetoothDevice memberDevice : memberDevices) {
                        if (memberDevice.getDevice().equals(device)) {
                            return true;
                        }
                    }
                    continue;
                }
                // Check sub devices of hearing aid if it exists
                CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
                if (subDevice != null && subDevice.getDevice().equals(device)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Updates the Hearing Aid devices; specifically the HiSyncId's. This routine is called when the
     * Hearing Aid Service is connected and the HiSyncId's are now available.
     */
    public synchronized void updateHearingAidsDevices() {
        mHearingAidDeviceManager.updateHearingAidsDevices();
    }

    /**
     * Updates the Csip devices; specifically the GroupId's. This routine is called when the
     * CSIS is connected and the GroupId's are now available.
     */
    public synchronized void updateCsipDevices() {
        mCsipDeviceManager.updateCsipDevices();
    }

    /**
     * Attempts to get the name of a remote device, otherwise returns the address.
     *
     * @param device The remote device.
     * @return The name, or if unavailable, the address.
     */
    public String getName(BluetoothDevice device) {
        if (isOngoingPairByCsip(device)) {
            CachedBluetoothDevice firstDevice =
                    mCsipDeviceManager.getFirstMemberDevice(mGroupIdOfLateBonding);
            if (firstDevice != null && firstDevice.getName() != null) {
                return firstDevice.getName();
            }
        }

        CachedBluetoothDevice cachedDevice = findDevice(device);
        if (cachedDevice != null && cachedDevice.getName() != null) {
            return cachedDevice.getName();
        }

        String name = device.getAlias();
        if (name != null) {
            return name;
        }

        return device.getAddress();
    }

    public synchronized void clearNonBondedDevices() {
        clearNonBondedSubDevices();
        final List<CachedBluetoothDevice> removedCachedDevice = new ArrayList<>();
        mCachedDevices.stream()
                .filter(cachedDevice -> cachedDevice.getBondState() == BluetoothDevice.BOND_NONE)
                .forEach(cachedDevice -> {
                    cachedDevice.release();
                    removedCachedDevice.add(cachedDevice);
                });
        mCachedDevices.removeAll(removedCachedDevice);
    }

    private void clearNonBondedSubDevices() {
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
            if (!memberDevices.isEmpty()) {
                for (Object it : memberDevices.toArray()) {
                    CachedBluetoothDevice memberDevice = (CachedBluetoothDevice) it;
                    // Member device exists and it is not bonded
                    if (memberDevice.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                        cachedDevice.removeMemberDevice(memberDevice);
                    }
                }
                return;
            }
            CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
            if (subDevice != null
                    && subDevice.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                // Sub device exists and it is not bonded
                subDevice.release();
                cachedDevice.setSubDevice(null);
            }
        }
    }

    public synchronized void onScanningStateChanged(boolean started) {
        if (!started) return;
        // If starting a new scan, clear old visibility
        // Iterate in reverse order since devices may be removed.
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            cachedDevice.setJustDiscovered(false);
            final Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
            if (!memberDevices.isEmpty()) {
                for (CachedBluetoothDevice memberDevice : memberDevices) {
                    memberDevice.setJustDiscovered(false);
                }
                return;
            }
            final CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
            if (subDevice != null) {
                subDevice.setJustDiscovered(false);
            }
        }
    }

    public synchronized void onBluetoothStateChanged(int bluetoothState) {
        // When Bluetooth is turning off, we need to clear the non-bonded devices
        // Otherwise, they end up showing up on the next BT enable
        if (bluetoothState == BluetoothAdapter.STATE_TURNING_OFF) {
            for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
                CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
                final Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
                if (!memberDevices.isEmpty()) {
                    for (CachedBluetoothDevice memberDevice : memberDevices) {
                        if (memberDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                            cachedDevice.removeMemberDevice(memberDevice);
                        }
                    }
                } else {
                    CachedBluetoothDevice subDevice = cachedDevice.getSubDevice();
                    if (subDevice != null) {
                        if (subDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                            cachedDevice.setSubDevice(null);
                        }
                    }
                }
                if (cachedDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    cachedDevice.setJustDiscovered(false);
                    cachedDevice.release();
                    mCachedDevices.remove(i);
                }
            }

            // To clear the SetMemberPair flag when the Bluetooth is turning off.
            mOngoingSetMemberPair = null;
            mIsLateBonding = false;
            mGroupIdOfLateBonding = BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }
    }

    public synchronized boolean onProfileConnectionStateChangedIfProcessed(CachedBluetoothDevice
            cachedDevice, int state, int profileId) {
        if (profileId == BluetoothProfile.HEARING_AID) {
            return mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(cachedDevice,
                state);
        }
        if (profileId == BluetoothProfile.HEADSET
                || profileId == BluetoothProfile.A2DP
                || profileId == BluetoothProfile.LE_AUDIO
                || profileId == BluetoothProfile.CSIP_SET_COORDINATOR) {
            return mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(cachedDevice,
                state);
        }
        return false;
    }

    /** Handles when the device been set as active/inactive. */
    public synchronized void onActiveDeviceChanged(CachedBluetoothDevice cachedBluetoothDevice) {
        if (cachedBluetoothDevice.isHearingAidDevice()) {
            mHearingAidDeviceManager.onActiveDeviceChanged(cachedBluetoothDevice);
        }
    }

    public synchronized void onDeviceUnpaired(CachedBluetoothDevice device) {
        device.setGroupId(BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
        CachedBluetoothDevice mainDevice = mCsipDeviceManager.findMainDevice(device);
        // Should iterate through the cloned set to avoid ConcurrentModificationException
        final Set<CachedBluetoothDevice> memberDevices = new HashSet<>(device.getMemberDevice());
        if (!memberDevices.isEmpty()) {
            // Main device is unpaired, also unpair the member devices
            for (CachedBluetoothDevice memberDevice : memberDevices) {
                memberDevice.unpair();
                memberDevice.setGroupId(BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
                device.removeMemberDevice(memberDevice);
            }
        } else if (mainDevice != null) {
            // Member device is unpaired, also unpair the main device
            mainDevice.unpair();
        }
        mainDevice = mHearingAidDeviceManager.findMainDevice(device);
        CachedBluetoothDevice subDevice = device.getSubDevice();
        if (subDevice != null) {
            // Main device is unpaired, to unpair sub device
            subDevice.unpair();
            device.setSubDevice(null);
        } else if (mainDevice != null) {
            // Sub device unpaired, to unpair main device
            mainDevice.unpair();
            mainDevice.setSubDevice(null);
        }
    }

    /**
     * Called when we found a set member of a group. The function will check the {@code groupId} if
     * it exists and the bond state of the device is BOND_NOE, and if there isn't any ongoing pair
     * , and then return {@code true} to pair the device automatically.
     *
     * @param device The found device
     * @param groupId The group id of the found device
     *
     * @return {@code true}, if the device should pair automatically; Otherwise, return
     * {@code false}.
     */
    private synchronized boolean shouldPairByCsip(BluetoothDevice device, int groupId) {
        boolean isOngoingSetMemberPair = mOngoingSetMemberPair != null;
        int bondState = device.getBondState();
        boolean groupExists = mCsipDeviceManager.isExistedGroupId(groupId);
        Log.d(TAG,
                "isOngoingSetMemberPair=" + isOngoingSetMemberPair + ", bondState=" + bondState
                        + ", groupExists=" + groupExists + ", groupId=" + groupId);

        if (isOngoingSetMemberPair || bondState != BluetoothDevice.BOND_NONE || !groupExists) {
            return false;
        }
        return true;
    }

    private synchronized boolean checkLateBonding(int groupId) {
        CachedBluetoothDevice firstDevice = mCsipDeviceManager.getFirstMemberDevice(groupId);
        if (firstDevice == null) {
            Log.d(TAG, "No first device in group: " + groupId);
            return false;
        }

        Timestamp then = firstDevice.getBondTimestamp();
        if (then == null) {
            Log.d(TAG, "No bond timestamp");
            return true;
        }

        Timestamp now = new Timestamp(System.currentTimeMillis());

        long diff = (now.getTime() - then.getTime());
        Log.d(TAG, "Time difference to first bonding: " + diff + "ms");

        return diff > sLateBondingTimeoutMillis;
    }

    /**
     * Called to check if there is an ongoing bonding for the device and it is late bonding.
     * If the device is not matching the ongoing bonding device then false will be returned.
     *
     * @param device The device to check.
     */
    public synchronized boolean isLateBonding(BluetoothDevice device) {
        if (!isOngoingPairByCsip(device)) {
            Log.d(TAG, "isLateBonding: pair not ongoing or not matching device");
            return false;
        }

        Log.d(TAG, "isLateBonding: " + mIsLateBonding);
        return mIsLateBonding;
    }

    /**
     * Called when we found a set member of a group. The function will check the {@code groupId} if
     * it exists and the bond state of the device is BOND_NONE, and if there isn't any ongoing pair
     * , and then pair the device automatically.
     *
     * @param device The found device
     * @param groupId The group id of the found device
     */
    public synchronized void pairDeviceByCsip(BluetoothDevice device, int groupId) {
        if (!shouldPairByCsip(device, groupId)) {
            return;
        }
        Log.d(TAG, "Bond " + device.getAnonymizedAddress() + " groupId=" + groupId + " by CSIP ");
        mOngoingSetMemberPair = device;
        mIsLateBonding = checkLateBonding(groupId);
        mGroupIdOfLateBonding = groupId;
        syncConfigFromMainDevice(device, groupId);
        if (!device.createBond(BluetoothDevice.TRANSPORT_LE)) {
            Log.d(TAG, "Bonding could not be started");
            mOngoingSetMemberPair = null;
            mIsLateBonding = false;
            mGroupIdOfLateBonding = BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        }
    }

    private void syncConfigFromMainDevice(BluetoothDevice device, int groupId) {
        if (!isOngoingPairByCsip(device)) {
            return;
        }
        CachedBluetoothDevice memberDevice = findDevice(device);
        CachedBluetoothDevice mainDevice = mCsipDeviceManager.findMainDevice(memberDevice);
        if (mainDevice == null) {
            mainDevice = mCsipDeviceManager.getCachedDevice(groupId);
        }

        if (mainDevice == null || mainDevice.equals(memberDevice)) {
            Log.d(TAG, "no mainDevice");
            return;
        }

        // The memberDevice set PhonebookAccessPermission
        device.setPhonebookAccessPermission(mainDevice.getDevice().getPhonebookAccessPermission());
    }

    /**
     * Called when the bond state change. If the bond state change is related with the
     * ongoing set member pair, the cachedBluetoothDevice will be created but the UI
     * would not be updated. For the other case, return {@code false} to go through the normal
     * flow.
     *
     * @param device The device
     * @param bondState The new bond state
     *
     * @return {@code true}, if the bond state change for the device is handled inside this
     * function, and would not like to update the UI. If not, return {@code false}.
     */
    public synchronized boolean onBondStateChangedIfProcess(BluetoothDevice device, int bondState) {
        if (!isOngoingPairByCsip(device)) {
            return false;
        }

        if (bondState == BluetoothDevice.BOND_BONDING) {
            return true;
        }

        mOngoingSetMemberPair = null;
        mIsLateBonding = false;
        mGroupIdOfLateBonding = BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        if (bondState != BluetoothDevice.BOND_NONE) {
            if (findDevice(device) == null) {
                final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
                CachedBluetoothDevice newDevice =
                        new CachedBluetoothDevice(mContext, profileManager, device);
                mCachedDevices.add(newDevice);
                findDevice(device).connect();
            }
        }

        return true;
    }

    /**
     * Check if the device is the one which is initial paired locally by CSIP. The setting
     * would depned on it to accept the pairing request automatically
     *
     * @param device The device
     *
     * @return {@code true}, if the device is ongoing pair by CSIP. Otherwise, return
     * {@code false}.
     */
    public boolean isOngoingPairByCsip(BluetoothDevice device) {
        return mOngoingSetMemberPair != null && mOngoingSetMemberPair.equals(device);
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
