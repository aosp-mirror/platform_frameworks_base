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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * CachedBluetoothDeviceManager manages the set of remote Bluetooth devices.
 */
public class CachedBluetoothDeviceManager {
    private static final String TAG = "CachedBluetoothDeviceManager";
    private static final boolean DEBUG = BluetoothUtils.D;

    private Context mContext;
    private final LocalBluetoothManager mBtManager;

    @VisibleForTesting
    final List<CachedBluetoothDevice> mCachedDevices = new ArrayList<CachedBluetoothDevice>();
    @VisibleForTesting
    HearingAidDeviceManager mHearingAidDeviceManager;
    @VisibleForTesting
    CsipDeviceManager mCsipDeviceManager;
    BluetoothDevice mOngoingSetMemberPair;

    CachedBluetoothDeviceManager(Context context, LocalBluetoothManager localBtManager) {
        mContext = context;
        mBtManager = localBtManager;
        mHearingAidDeviceManager = new HearingAidDeviceManager(localBtManager, mCachedDevices);
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
            if (memberDevices != null) {
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
     * @param device the address of the new Bluetooth device
     * @return the newly created CachedBluetoothDevice object
     */
    public CachedBluetoothDevice addDevice(BluetoothDevice device) {
        CachedBluetoothDevice newDevice;
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        synchronized (this) {
            newDevice = findDevice(device);
            if (newDevice == null) {
                newDevice = new CachedBluetoothDevice(mContext, profileManager, device);
                mCsipDeviceManager.initCsipDeviceIfNeeded(newDevice);
                mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(newDevice);
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
        if (memberDevices != null) {
            for (CachedBluetoothDevice memberDevice : memberDevices) {
                if (!memberDevice.isConnected()) {
                    return null;
                }
            }

            return device.getConnectionSummary();
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
                if (memberDevices != null) {
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
     * @param LocalBluetoothProfileManager profileManager
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
        mCachedDevices.removeIf(cachedDevice
            -> cachedDevice.getBondState() == BluetoothDevice.BOND_NONE);
    }

    private void clearNonBondedSubDevices() {
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            final Set<CachedBluetoothDevice> memberDevices = cachedDevice.getMemberDevice();
            if (memberDevices != null) {
                for (CachedBluetoothDevice memberDevice : memberDevices) {
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
            if (memberDevices != null) {
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
                if (memberDevices != null) {
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
                    mCachedDevices.remove(i);
                }
            }
        }
    }

    public synchronized boolean onProfileConnectionStateChangedIfProcessed(CachedBluetoothDevice
            cachedDevice, int state, int profileId) {
        if (profileId == BluetoothProfile.HEARING_AID) {
            return mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(cachedDevice,
                state);
        }
        if (profileId == BluetoothProfile.CSIP_SET_COORDINATOR) {
            return mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(cachedDevice,
                state);
        }
        return false;
    }

    public synchronized void onDeviceUnpaired(CachedBluetoothDevice device) {
        CachedBluetoothDevice mainDevice = mCsipDeviceManager.findMainDevice(device);
        final Set<CachedBluetoothDevice> memberDevices = device.getMemberDevice();
        if (memberDevices != null) {
            // Main device is unpaired, to unpair the member device
            for (CachedBluetoothDevice memberDevice : memberDevices) {
                memberDevice.unpair();
                device.removeMemberDevice(memberDevice);
            }
        } else if (mainDevice != null) {
            // the member device unpaired, to unpair main device
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
     * it exists and if there is a ongoing pair, the device would be ignored.
     *
     * @param device The found device
     * @param groupId The group id of the found device
     */
    public synchronized void onSetMemberAppear(BluetoothDevice device, int groupId) {
        Log.d(TAG, "onSetMemberAppear, groupId: " + groupId + " device: " + device.toString());

        if (mOngoingSetMemberPair != null) {
            Log.d(TAG, "Ongoing set memberPairing in process, drop it!");
            return;
        }

        if (mCsipDeviceManager.onSetMemberAppear(device, groupId)) {
            mOngoingSetMemberPair = device;
        }
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
        if (mOngoingSetMemberPair == null || !mOngoingSetMemberPair.equals(device)) {
            return false;
        }

        if (bondState == BluetoothDevice.BOND_BONDING) {
            return true;
        }

        mOngoingSetMemberPair = null;
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
        return !(mOngoingSetMemberPair == null) && mOngoingSetMemberPair.equals(device);
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
