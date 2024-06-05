/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.recoverysystem.hal;

import android.hardware.boot.IBootControl;
import android.hardware.boot.V1_0.CommandResult;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.NoSuchElementException;

public class BootControlHIDL implements IBootControl {
    private static final String TAG = "BootControlHIDL";

    final android.hardware.boot.V1_0.IBootControl v1_hal;
    final android.hardware.boot.V1_1.IBootControl v1_1_hal;
    final android.hardware.boot.V1_2.IBootControl v1_2_hal;

    public static boolean isServicePresent() {
        try {
            android.hardware.boot.V1_0.IBootControl.getService(true);
        } catch (RemoteException | NoSuchElementException e) {
            return false;
        }
        return true;
    }

    public static boolean isV1_2ServicePresent() {
        try {
            android.hardware.boot.V1_2.IBootControl.getService(true);
        } catch (RemoteException | NoSuchElementException e) {
            return false;
        }
        return true;
    }

    public static BootControlHIDL getService() throws RemoteException {
        android.hardware.boot.V1_0.IBootControl v1_hal =
                android.hardware.boot.V1_0.IBootControl.getService(true);
        android.hardware.boot.V1_1.IBootControl v1_1_hal =
                android.hardware.boot.V1_1.IBootControl.castFrom(v1_hal);
        android.hardware.boot.V1_2.IBootControl v1_2_hal =
                android.hardware.boot.V1_2.IBootControl.castFrom(v1_hal);
        return new BootControlHIDL(v1_hal, v1_1_hal, v1_2_hal);
    }

    private BootControlHIDL(android.hardware.boot.V1_0.IBootControl v1_hal,
            android.hardware.boot.V1_1.IBootControl v1_1_hal,
            android.hardware.boot.V1_2.IBootControl v1_2_hal) throws RemoteException {
        this.v1_hal = v1_hal;
        this.v1_1_hal = v1_1_hal;
        this.v1_2_hal = v1_2_hal;
        if (v1_hal == null) {
            throw new RemoteException("Failed to find V1.0 BootControl HIDL");
        }
        if (v1_2_hal != null) {
            Slog.i(TAG, "V1.2 version of BootControl HIDL HAL available, using V1.2");
        } else if (v1_1_hal != null) {
            Slog.i(TAG, "V1.1 version of BootControl HIDL HAL available, using V1.1");
        } else {
            Slog.i(TAG, "V1.0 version of BootControl HIDL HAL available, using V1.0");
        }
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public int getActiveBootSlot() throws RemoteException {
        if (v1_2_hal == null) {
            throw new RemoteException("getActiveBootSlot() requires V1.2 BootControl HAL");
        }
        return v1_2_hal.getActiveBootSlot();
    }

    @Override
    public int getCurrentSlot() throws RemoteException {
        return v1_hal.getCurrentSlot();
    }

    @Override
    public int getNumberSlots() throws RemoteException {
        return v1_hal.getNumberSlots();
    }

    @Override
    public int getSnapshotMergeStatus() throws RemoteException {
        if (v1_1_hal == null) {
            throw new RemoteException("getSnapshotMergeStatus() requires V1.1 BootControl HAL");
        }
        return v1_1_hal.getSnapshotMergeStatus();
    }

    @Override
    public String getSuffix(int slot) throws RemoteException {
        return v1_hal.getSuffix(slot);
    }

    @Override
    public boolean isSlotBootable(int slot) throws RemoteException {
        int ret = v1_hal.isSlotBootable(slot);
        if (ret == -1) {
            throw new RemoteException(
                    "isSlotBootable() failed, Slot %d might be invalid.".formatted(slot));
        }
        return ret != 0;
    }

    @Override
    public boolean isSlotMarkedSuccessful(int slot) throws RemoteException {
        int ret = v1_hal.isSlotMarkedSuccessful(slot);
        if (ret == -1) {
            throw new RemoteException(
                    "isSlotMarkedSuccessful() failed, Slot %d might be invalid.".formatted(slot));
        }
        return ret != 0;
    }

    @Override
    public void markBootSuccessful() throws RemoteException {
        CommandResult res = v1_hal.markBootSuccessful();
        if (!res.success) {
            throw new RemoteException("Error markBootSuccessful() " + res.errMsg);
        }
    }

    @Override
    public void setActiveBootSlot(int slot) throws RemoteException {
        CommandResult res = v1_hal.setActiveBootSlot(slot);
        if (!res.success) {
            throw new RemoteException("Error setActiveBootSlot(%d) %s".formatted(slot, res.errMsg));
        }
    }

    @Override
    public void setSlotAsUnbootable(int slot) throws RemoteException {
        CommandResult res = v1_hal.setSlotAsUnbootable(slot);
        if (!res.success) {
            throw new RemoteException(
                    "Error setSlotAsUnbootable(%d) %s".formatted(slot, res.errMsg));
        }
    }

    @Override
    public void setSnapshotMergeStatus(int status) throws RemoteException {
        if (v1_1_hal == null) {
            throw new RemoteException("getSnapshotMergeStatus() requires V1.1 BootControl HAL");
        }
        if (!v1_1_hal.setSnapshotMergeStatus(status)) {
            throw new RemoteException("Error setSnapshotMergeStatus(%d)".formatted(status));
        }
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        return 1;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        return v1_hal.interfaceDescriptor();
    }
}
