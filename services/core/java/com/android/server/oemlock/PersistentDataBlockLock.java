/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.oemlock;

import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Slog;

/**
 * Implementation of the OEM lock using the persistent data block to communicate with the
 * bootloader.
 *
 * The carrier flag is stored as a user restriction on the system user. The user flag is set in the
 * presistent data block but depends on the carrier flag.
 */
class PersistentDataBlockLock extends OemLock {
    private static final String TAG = "OemLock";

    private Context mContext;

    PersistentDataBlockLock(Context context) {
        mContext = context;
    }

    @Override
    @Nullable
    String getLockName() {
        return "";
    }

    @Override
    void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
        // Note: this implementation does not require a signature
        if (signature != null) {
            Slog.w(TAG, "Signature provided but is not being used");
        }

        // Continue using user restriction for backwards compatibility
        UserManager.get(mContext).setUserRestriction(
                UserManager.DISALLOW_OEM_UNLOCK, !allowed, UserHandle.SYSTEM);

        if (!allowed) {
            disallowUnlockIfNotUnlocked();
        }
    }

    @Override
    boolean isOemUnlockAllowedByCarrier() {
        return !UserManager.get(mContext)
                .hasUserRestriction(UserManager.DISALLOW_OEM_UNLOCK, UserHandle.SYSTEM);
    }

    @Override
    void setOemUnlockAllowedByDevice(boolean allowedByDevice) {
        // The method name is misleading as it really just means whether or not the device can be
        // unlocked but doesn't actually do any unlocking.
        final PersistentDataBlockManager pdbm = (PersistentDataBlockManager)
                mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
        if (pdbm == null) {
            Slog.w(TAG, "PersistentDataBlock is not supported on this device");
            return;
        }
        pdbm.setOemUnlockEnabled(allowedByDevice);
    }

    @Override
    boolean isOemUnlockAllowedByDevice() {
        final PersistentDataBlockManager pdbm = (PersistentDataBlockManager)
            mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
        if (pdbm == null) {
            Slog.w(TAG, "PersistentDataBlock is not supported on this device");
            return false;
        }
        return pdbm.getOemUnlockEnabled();
    }

    /**
     * Update state to prevent the bootloader from being able to unlock the device unless the device
     * has already been unlocked by the bootloader in which case it is too late as it would remain
     * unlocked.
     */
    private void disallowUnlockIfNotUnlocked() {
        final PersistentDataBlockManager pdbm = (PersistentDataBlockManager)
            mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
        if (pdbm == null) {
            Slog.w(TAG, "PersistentDataBlock is not supported on this device");
            return;
        }
        if (pdbm.getFlashLockState() != PersistentDataBlockManager.FLASH_LOCK_UNLOCKED) {
            pdbm.setOemUnlockEnabled(false);
        }
    }
}
