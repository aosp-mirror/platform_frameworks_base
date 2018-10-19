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
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * CachedBluetoothDeviceManager manages the set of remote Bluetooth devices.
 */
public class CachedBluetoothDeviceManager {
    private static final String TAG = "CachedBluetoothDeviceManager";
    private static final boolean DEBUG = Utils.D;

    private Context mContext;
    private final LocalBluetoothManager mBtManager;

    @VisibleForTesting
    final List<CachedBluetoothDevice> mCachedDevices =
        new ArrayList<CachedBluetoothDevice>();
    // Contains the list of hearing aid devices that should not be shown in the UI.
    @VisibleForTesting
    final List<CachedBluetoothDevice> mHearingAidDevicesNotAddedInCache
        = new ArrayList<CachedBluetoothDevice>();
    // Maintains a list of devices which are added in mCachedDevices and have hiSyncIds.
    @VisibleForTesting
    final Map<Long, CachedBluetoothDevice> mCachedDevicesMapForHearingAids
        = new HashMap<Long, CachedBluetoothDevice>();

    CachedBluetoothDeviceManager(Context context, LocalBluetoothManager localBtManager) {
        mContext = context;
        mBtManager = localBtManager;
    }

    public synchronized Collection<CachedBluetoothDevice> getCachedDevicesCopy() {
        return new ArrayList<CachedBluetoothDevice>(mCachedDevices);
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
        }
        for (CachedBluetoothDevice notCachedDevice : mHearingAidDevicesNotAddedInCache) {
            if (notCachedDevice.getDevice().equals(device)) {
                return notCachedDevice;
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
    public CachedBluetoothDevice addDevice(LocalBluetoothAdapter adapter,
            LocalBluetoothProfileManager profileManager,
            BluetoothDevice device) {
        CachedBluetoothDevice newDevice = new CachedBluetoothDevice(mContext, adapter,
            profileManager, device);
        if (profileManager.getHearingAidProfile() != null
            && profileManager.getHearingAidProfile().getHiSyncId(newDevice.getDevice())
                != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
            newDevice.setHiSyncId(profileManager.getHearingAidProfile()
                .getHiSyncId(newDevice.getDevice()));
        }
        // Just add one of the hearing aids from a pair in the list that is shown in the UI.
        if (isPairAddedInCache(newDevice.getHiSyncId())) {
            synchronized (this) {
                mHearingAidDevicesNotAddedInCache.add(newDevice);
            }
        } else {
            synchronized (this) {
                mCachedDevices.add(newDevice);
                if (newDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID
                    && !mCachedDevicesMapForHearingAids.containsKey(newDevice.getHiSyncId())) {
                    mCachedDevicesMapForHearingAids.put(newDevice.getHiSyncId(), newDevice);
                }
                mBtManager.getEventManager().dispatchDeviceAdded(newDevice);
            }
        }

        return newDevice;
    }

    /**
     * Returns true if the one of the two hearing aid devices is already cached for UI.
     *
     * @param long hiSyncId
     * @return {@code True} if one of the two hearing aid devices is is already cached for UI.
     */
    private synchronized boolean isPairAddedInCache(long hiSyncId) {
        if (hiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID) {
            return false;
        }
        if(mCachedDevicesMapForHearingAids.containsKey(hiSyncId)) {
            return true;
        }
        return false;
    }

    /**
     * Returns device summary of the pair of the hearing aid passed as the parameter.
     *
     * @param CachedBluetoothDevice device
     * @return Device summary, or if the pair does not exist or if its not a hearing aid,
     * then {@code null}.
     */
    public synchronized String getHearingAidPairDeviceSummary(CachedBluetoothDevice device) {
        String pairDeviceSummary = null;
        CachedBluetoothDevice otherHearingAidDevice =
            getHearingAidOtherDevice(device, device.getHiSyncId());
        if (otherHearingAidDevice != null) {
            pairDeviceSummary = otherHearingAidDevice.getConnectionSummary();
        }
        log("getHearingAidPairDeviceSummary: pairDeviceSummary=" + pairDeviceSummary
            + ", otherHearingAidDevice=" + otherHearingAidDevice);
 
        return pairDeviceSummary;
    }

    /**
     * Adds the 2nd hearing aid in a pair in a list that maintains the hearing aids that are
     * not dispalyed in the UI.
     *
     * @param CachedBluetoothDevice device
     */
    public synchronized void addDeviceNotaddedInMap(CachedBluetoothDevice device) {
        mHearingAidDevicesNotAddedInCache.add(device);
    }

    /**
     * Updates the Hearing Aid devices; specifically the HiSyncId's. This routine is called when the
     * Hearing Aid Service is connected and the HiSyncId's are now available.
     * @param LocalBluetoothProfileManager profileManager
     */
    public synchronized void updateHearingAidsDevices(LocalBluetoothProfileManager profileManager) {
        HearingAidProfile profileProxy = profileManager.getHearingAidProfile();
        if (profileProxy == null) {
            log("updateHearingAidsDevices: getHearingAidProfile() is null");
            return;
        }
        final Set<Long> syncIdChangedSet = new HashSet<Long>();
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (cachedDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                continue;
            }

            long newHiSyncId = profileProxy.getHiSyncId(cachedDevice.getDevice());

            if (newHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                cachedDevice.setHiSyncId(newHiSyncId);
                syncIdChangedSet.add(newHiSyncId);
            }
        }
        for (Long syncId : syncIdChangedSet) {
            onHiSyncIdChanged(syncId);
        }
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

        String name = device.getAliasName();
        if (name != null) {
            return name;
        }

        return device.getAddress();
    }

    public synchronized void clearNonBondedDevices() {

        mCachedDevicesMapForHearingAids.entrySet().removeIf(entries
            -> entries.getValue().getBondState() == BluetoothDevice.BOND_NONE);

        mCachedDevices.removeIf(cachedDevice
            -> cachedDevice.getBondState() == BluetoothDevice.BOND_NONE);

        mHearingAidDevicesNotAddedInCache.removeIf(hearingAidDevice
            -> hearingAidDevice.getBondState() == BluetoothDevice.BOND_NONE);
    }

    public synchronized void onScanningStateChanged(boolean started) {
        if (!started) return;
        // If starting a new scan, clear old visibility
        // Iterate in reverse order since devices may be removed.
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            cachedDevice.setJustDiscovered(false);
        }
        for (int i = mHearingAidDevicesNotAddedInCache.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice notCachedDevice = mHearingAidDevicesNotAddedInCache.get(i);
            notCachedDevice.setJustDiscovered(false);
        }
    }

    public synchronized void onBtClassChanged(BluetoothDevice device) {
        CachedBluetoothDevice cachedDevice = findDevice(device);
        if (cachedDevice != null) {
            cachedDevice.refreshBtClass();
        }
    }

    public synchronized void onUuidChanged(BluetoothDevice device) {
        CachedBluetoothDevice cachedDevice = findDevice(device);
        if (cachedDevice != null) {
            cachedDevice.onUuidChanged();
        }
    }

    public synchronized void onBluetoothStateChanged(int bluetoothState) {
        // When Bluetooth is turning off, we need to clear the non-bonded devices
        // Otherwise, they end up showing up on the next BT enable
        if (bluetoothState == BluetoothAdapter.STATE_TURNING_OFF) {
            for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
                CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
                if (cachedDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    cachedDevice.setJustDiscovered(false);
                    mCachedDevices.remove(i);
                    if (cachedDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID
                        && mCachedDevicesMapForHearingAids.containsKey(cachedDevice.getHiSyncId()))
                    {
                        mCachedDevicesMapForHearingAids.remove(cachedDevice.getHiSyncId());
                    }
                } else {
                    // For bonded devices, we need to clear the connection status so that
                    // when BT is enabled next time, device connection status shall be retrieved
                    // by making a binder call.
                    cachedDevice.clearProfileConnectionState();
                }
            }
            for (int i = mHearingAidDevicesNotAddedInCache.size() - 1; i >= 0; i--) {
                CachedBluetoothDevice notCachedDevice = mHearingAidDevicesNotAddedInCache.get(i);
                if (notCachedDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    notCachedDevice.setJustDiscovered(false);
                    mHearingAidDevicesNotAddedInCache.remove(i);
                } else {
                    // For bonded devices, we need to clear the connection status so that
                    // when BT is enabled next time, device connection status shall be retrieved
                    // by making a binder call.
                    notCachedDevice.clearProfileConnectionState();
                }
            }
        }
    }

    public synchronized void onActiveDeviceChanged(CachedBluetoothDevice activeDevice,
                                                   int bluetoothProfile) {
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            boolean isActive = Objects.equals(cachedDevice, activeDevice);
            cachedDevice.onActiveDeviceChanged(isActive, bluetoothProfile);
        }
    }

    public synchronized void onHiSyncIdChanged(long hiSyncId) {
        int firstMatchedIndex = -1;

        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            if (cachedDevice.getHiSyncId() == hiSyncId) {
                if (firstMatchedIndex != -1) {
                    /* Found the second one */
                    int indexToRemoveFromUi;
                    CachedBluetoothDevice deviceToRemoveFromUi;

                    // Since the hiSyncIds have been updated for a connected pair of hearing aids,
                    // we remove the entry of one the hearing aids from the UI. Unless the
                    // hiSyncId get updated, the system does not know it is a hearing aid, so we add
                    // both the hearing aids as separate entries in the UI first, then remove one
                    // of them after the hiSyncId is populated. We will choose the device that
                    // is not connected to be removed.
                    if (cachedDevice.isConnected()) {
                        indexToRemoveFromUi = firstMatchedIndex;
                        deviceToRemoveFromUi = mCachedDevices.get(firstMatchedIndex);
                        mCachedDevicesMapForHearingAids.put(hiSyncId, cachedDevice);
                    } else {
                        indexToRemoveFromUi = i;
                        deviceToRemoveFromUi = cachedDevice;
                        mCachedDevicesMapForHearingAids.put(hiSyncId,
                                                            mCachedDevices.get(firstMatchedIndex));
                    }

                    mCachedDevices.remove(indexToRemoveFromUi);
                    mHearingAidDevicesNotAddedInCache.add(deviceToRemoveFromUi);
                    log("onHiSyncIdChanged: removed from UI device=" + deviceToRemoveFromUi
                        + ", with hiSyncId=" + hiSyncId);
                    mBtManager.getEventManager().dispatchDeviceRemoved(deviceToRemoveFromUi);
                    break;
                } else {
                    mCachedDevicesMapForHearingAids.put(hiSyncId, cachedDevice);
                    firstMatchedIndex = i;
                }
            }
        }
    }

    public CachedBluetoothDevice getHearingAidOtherDevice(CachedBluetoothDevice thisDevice,
                                                           long hiSyncId) {
        if (hiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID) {
            return null;
        }

        // Searched the lists for the other side device with the matching hiSyncId.
        for (CachedBluetoothDevice notCachedDevice : mHearingAidDevicesNotAddedInCache) {
            if ((hiSyncId == notCachedDevice.getHiSyncId()) &&
                (!Objects.equals(notCachedDevice, thisDevice))) {
                return notCachedDevice;
            }
        }

        CachedBluetoothDevice cachedDevice = mCachedDevicesMapForHearingAids.get(hiSyncId);
        if (!Objects.equals(cachedDevice, thisDevice)) {
            return cachedDevice;
        }
        return null;
    }

    private void hearingAidSwitchDisplayDevice(CachedBluetoothDevice toDisplayDevice,
                                           CachedBluetoothDevice toHideDevice, long hiSyncId)
    {
        log("hearingAidSwitchDisplayDevice: toDisplayDevice=" + toDisplayDevice
            + ", toHideDevice=" + toHideDevice);

        // Remove the "toHideDevice" device from the UI.
        mHearingAidDevicesNotAddedInCache.add(toHideDevice);
        mCachedDevices.remove(toHideDevice);
        mBtManager.getEventManager().dispatchDeviceRemoved(toHideDevice);

        // Add the "toDisplayDevice" device to the UI.
        mHearingAidDevicesNotAddedInCache.remove(toDisplayDevice);
        mCachedDevices.add(toDisplayDevice);
        mCachedDevicesMapForHearingAids.put(hiSyncId, toDisplayDevice);
        mBtManager.getEventManager().dispatchDeviceAdded(toDisplayDevice);
    }

    public synchronized void onProfileConnectionStateChanged(CachedBluetoothDevice cachedDevice,
                                                             int state, int bluetoothProfile) {
        if (bluetoothProfile == BluetoothProfile.HEARING_AID
            && cachedDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID
            && cachedDevice.getBondState() == BluetoothDevice.BOND_BONDED) {

            long hiSyncId = cachedDevice.getHiSyncId();

            CachedBluetoothDevice otherDevice = getHearingAidOtherDevice(cachedDevice, hiSyncId);
            if (otherDevice == null) {
                // no other side device. Nothing to do.
                return;
            }

            if (state == BluetoothProfile.STATE_CONNECTED &&
                mHearingAidDevicesNotAddedInCache.contains(cachedDevice)) {
                hearingAidSwitchDisplayDevice(cachedDevice, otherDevice, hiSyncId);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED
                       && otherDevice.isConnected()) {
                CachedBluetoothDevice mapDevice = mCachedDevicesMapForHearingAids.get(hiSyncId);
                if ((mapDevice != null) && (Objects.equals(cachedDevice, mapDevice))) {
                    hearingAidSwitchDisplayDevice(otherDevice, cachedDevice, hiSyncId);
                }
            }
        }
    }

    public synchronized void onDeviceUnpaired(CachedBluetoothDevice device) {
        final long hiSyncId = device.getHiSyncId();

        if (hiSyncId == BluetoothHearingAid.HI_SYNC_ID_INVALID) return;

        for (int i = mHearingAidDevicesNotAddedInCache.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mHearingAidDevicesNotAddedInCache.get(i);
            if (cachedDevice.getHiSyncId() == hiSyncId) {
                // TODO: Look for more cleanups on unpairing the device.
                mHearingAidDevicesNotAddedInCache.remove(i);
                if (device == cachedDevice) continue;
                log("onDeviceUnpaired: Unpair device=" + cachedDevice);
                cachedDevice.unpair();
            }
        }

        CachedBluetoothDevice mappedDevice = mCachedDevicesMapForHearingAids.get(hiSyncId);
        if ((mappedDevice != null) && (!Objects.equals(device, mappedDevice))) {
            log("onDeviceUnpaired: Unpair mapped device=" + mappedDevice);
            mappedDevice.unpair();
        }
    }

    public synchronized void dispatchAudioModeChanged() {
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            cachedDevice.onAudioModeChanged();
        }
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
