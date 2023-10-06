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

import static android.Manifest.permission.MANAGE_CARRIER_OEM_UNLOCK_STATE;
import static android.Manifest.permission.MANAGE_USER_OEM_UNLOCK_STATE;
import static android.Manifest.permission.OEM_UNLOCK_STATE;
import static android.Manifest.permission.READ_OEM_UNLOCK_STATE;

import android.annotation.EnforcePermission;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.oemlock.IOemLockService;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pdb.PersistentDataBlockManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerInternal.UserRestrictionsListener;
import com.android.server.pm.UserRestrictionsUtils;

/**
 * Service for managing the OEM lock state of the device.
 *
 * The OemLock HAL will be used if it is available, otherwise the persistent data block will be
 * used.
 */
public class OemLockService extends SystemService {
    private static final String TAG = "OemLock";

    private static final String FLASH_LOCK_PROP = "ro.boot.flash.locked";
    private static final String FLASH_LOCK_UNLOCKED = "0";

    private Context mContext;
    private OemLock mOemLock;

    public static boolean isHalPresent() {
        return (VendorLockHidl.getOemLockHalService() != null)
                || (VendorLockAidl.getOemLockHalService() != null);
    }

    /** Select the OEM lock implementation */
    private static OemLock getOemLock(Context context) {
        if (VendorLockAidl.getOemLockHalService() != null) {
            Slog.i(TAG, "Using vendor lock via the HAL(aidl)");
            return new VendorLockAidl(context);
        } else if (VendorLockHidl.getOemLockHalService() != null) {
            Slog.i(TAG, "Using vendor lock via the HAL(hidl)");
            return new VendorLockHidl(context);
        } else {
            Slog.i(TAG, "Using persistent data block based lock");
            return new PersistentDataBlockLock(context);
        }
    }

    public OemLockService(Context context) {
        this(context, getOemLock(context));
    }

    OemLockService(Context context, OemLock oemLock) {
        super(context);
        mContext = context;
        mOemLock = oemLock;

        LocalServices.getService(UserManagerInternal.class)
                .addUserRestrictionsListener(mUserRestrictionsListener);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.OEM_LOCK_SERVICE, mService);
    }

    private final UserRestrictionsListener mUserRestrictionsListener =
            new UserRestrictionsListener() {
        @Override
        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                Bundle prevRestrictions) {
            // The admin can prevent OEM unlock with the DISALLOW_FACTORY_RESET user restriction
            if (UserRestrictionsUtils.restrictionsChanged(prevRestrictions, newRestrictions,
                     UserManager.DISALLOW_FACTORY_RESET)) {
                final boolean unlockAllowedByAdmin =
                        !newRestrictions.getBoolean(UserManager.DISALLOW_FACTORY_RESET);
                if (!unlockAllowedByAdmin) {
                    mOemLock.setOemUnlockAllowedByDevice(false);
                    setPersistentDataBlockOemUnlockAllowedBit(false);
                }
            }
        }
    };

    /**
     * Implements the binder interface for the service.
     *
     * This checks for the relevant permissions before forwarding the call to the OEM lock
     * implementation being used on this device.
     */
    private final IBinder mService = new IOemLockService.Stub() {
        @Override
        @Nullable
        @EnforcePermission(MANAGE_CARRIER_OEM_UNLOCK_STATE)
        public String getLockName() {
            super.getLockName_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                return mOemLock.getLockName();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        @EnforcePermission(MANAGE_CARRIER_OEM_UNLOCK_STATE)
        public void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
            super.setOemUnlockAllowedByCarrier_enforcePermission();

            enforceUserIsAdmin();

            final long token = Binder.clearCallingIdentity();
            try {
                mOemLock.setOemUnlockAllowedByCarrier(allowed, signature);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        @EnforcePermission(MANAGE_CARRIER_OEM_UNLOCK_STATE)
        public boolean isOemUnlockAllowedByCarrier() {
            super.isOemUnlockAllowedByCarrier_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
              return mOemLock.isOemUnlockAllowedByCarrier();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // The user has the final say so if they allow unlock, then the device allows the bootloader
        // to OEM unlock it.
        @Override
        @EnforcePermission(MANAGE_USER_OEM_UNLOCK_STATE)
        public void setOemUnlockAllowedByUser(boolean allowedByUser) {
            super.setOemUnlockAllowedByUser_enforcePermission();

            if (ActivityManager.isUserAMonkey()) {
                // Prevent a monkey from changing this
                return;
            }
            enforceUserIsAdmin();

            final long token = Binder.clearCallingIdentity();
            try {
                if (!isOemUnlockAllowedByAdmin()) {
                    throw new SecurityException("Admin does not allow OEM unlock");
                }

                if (!mOemLock.isOemUnlockAllowedByCarrier()) {
                    throw new SecurityException("Carrier does not allow OEM unlock");
                }

                mOemLock.setOemUnlockAllowedByDevice(allowedByUser);
                setPersistentDataBlockOemUnlockAllowedBit(allowedByUser);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        @EnforcePermission(MANAGE_USER_OEM_UNLOCK_STATE)
        public boolean isOemUnlockAllowedByUser() {
            super.isOemUnlockAllowedByUser_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                return mOemLock.isOemUnlockAllowedByDevice();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /** Currently MasterClearConfirm will call isOemUnlockAllowed()
         * to sync PersistentDataBlockOemUnlockAllowedBit which
         * is needed before factory reset
         * TODO: Figure out better place to run sync e.g. adding new API
         */
        @Override
        @EnforcePermission(anyOf = {READ_OEM_UNLOCK_STATE, OEM_UNLOCK_STATE})
        public boolean isOemUnlockAllowed() {
            super.isOemUnlockAllowed_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                boolean allowed = mOemLock.isOemUnlockAllowedByCarrier()
                        && mOemLock.isOemUnlockAllowedByDevice();
                setPersistentDataBlockOemUnlockAllowedBit(allowed);
                return allowed;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        @EnforcePermission(anyOf = {READ_OEM_UNLOCK_STATE, OEM_UNLOCK_STATE})
        public boolean isDeviceOemUnlocked() {
            super.isDeviceOemUnlocked_enforcePermission();

            String locked = SystemProperties.get(FLASH_LOCK_PROP);
            switch (locked) {
                case FLASH_LOCK_UNLOCKED:
                    return true;
                default:
                    return false;
            }
        }
    };

    /**
     * Always synchronize the OemUnlockAllowed bit to the FRP partition, which
     * is used to erase FRP information on a unlockable device.
     */
    private void setPersistentDataBlockOemUnlockAllowedBit(boolean allowed) {
        final PersistentDataBlockManagerInternal pdbmi
                = LocalServices.getService(PersistentDataBlockManagerInternal.class);
        // if mOemLock is PersistentDataBlockLock, then the bit should have already been set
        if (pdbmi != null && !(mOemLock instanceof PersistentDataBlockLock)) {
            Slog.i(TAG, "Update OEM Unlock bit in pst partition to " + allowed);
            pdbmi.forceOemUnlockEnabled(allowed);
        }
    }

    private boolean isOemUnlockAllowedByAdmin() {
        return !UserManager.get(mContext)
                .hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET, UserHandle.SYSTEM);
    }

    private void enforceUserIsAdmin() {
        final int userId = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            if (!UserManager.get(mContext).isUserAdmin(userId)) {
                throw new SecurityException("Must be an admin user");
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
