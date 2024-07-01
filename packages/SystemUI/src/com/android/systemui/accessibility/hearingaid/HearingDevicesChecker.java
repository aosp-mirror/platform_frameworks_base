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

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * HearingDevicesChecker provides utility methods to determine the presence and status of
 * connected hearing aid devices.
 *
 * <p>It also filters out devices that are exclusively managed by other applications to avoid
 * interfering with their operation.
 */
@SysUISingleton
public class HearingDevicesChecker {

    private final Context mContext;
    private final LocalBluetoothManager mLocalBluetoothManager;

    @Inject
    public HearingDevicesChecker(
            Context context,
            @Nullable LocalBluetoothManager localBluetoothManager) {
        mContext = context;
        mLocalBluetoothManager = localBluetoothManager;
    }

    /**
     * Checks if any hearing device is already paired.
     *
     * <p>It includes {@link BluetoothDevice.BOND_BONDING} and {@link BluetoothDevice.BOND_BONDED}).
     *
     * <p>A bonded device means it has been paired, but may not connected now.
     *
     * @return {@code true} if any bonded hearing device is found, {@code false} otherwise.
     */
    @WorkerThread
    public boolean isAnyPairedHearingDevice() {
        if (mLocalBluetoothManager == null) {
            return false;
        }
        if (!mLocalBluetoothManager.getBluetoothAdapter().isEnabled()) {
            return false;
        }

        return mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy().stream()
                .anyMatch(device -> device.isHearingAidDevice()
                        && device.getBondState() != BluetoothDevice.BOND_NONE
                        && !isExclusivelyManagedBluetoothDevice(device));
    }

    /**
     * Checks if there are any active hearing device.
     *
     * <p>An active device means it is currently connected and streaming media.
     *
     * @return {@code true} if any active hearing device is found, {@code false} otherwise.
     */
    @WorkerThread
    public boolean isAnyActiveHearingDevice() {
        if (mLocalBluetoothManager == null) {
            return false;
        }
        if (!mLocalBluetoothManager.getBluetoothAdapter().isEnabled()) {
            return false;
        }

        return mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy().stream()
                .anyMatch(device -> BluetoothUtils.isActiveMediaDevice(device)
                        && BluetoothUtils.isAvailableHearingDevice(device)
                        && !isExclusivelyManagedBluetoothDevice(device));
    }

    private boolean isExclusivelyManagedBluetoothDevice(
            @NonNull CachedBluetoothDevice cachedDevice) {
        if (com.android.settingslib.flags.Flags.enableHideExclusivelyManagedBluetoothDevice()) {
            return BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext,
                    cachedDevice.getDevice());
        }
        return false;
    }
}
