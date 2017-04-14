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

package com.android.server;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.oemlock.IOemLockService;
import android.service.persistentdata.PersistentDataBlockManager;

/**
 * Service for managing the OEM lock state of the device.
 *
 * The current implementation is a wrapper around the previous implementation of OEM lock.
 *  - the DISALLOW_OEM_UNLOCK user restriction was set if the carrier disallowed unlock
 *  - the user allows unlock in settings which calls PDBM.setOemUnlockEnabled()
 */
public class OemLockService extends SystemService {
    private Context mContext;

    public OemLockService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.OEM_LOCK_SERVICE, mService);
    }

    private boolean doIsOemUnlockAllowedByCarrier() {
        return !UserManager.get(mContext).hasUserRestriction(UserManager.DISALLOW_OEM_UNLOCK);
    }

    private boolean doIsOemUnlockAllowedByUser() {
        final PersistentDataBlockManager pdbm = (PersistentDataBlockManager)
            mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);

        final long token = Binder.clearCallingIdentity();
        try {
            return pdbm.getOemUnlockEnabled();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Implements the binder interface for the service.
     */
    private final IBinder mService = new IOemLockService.Stub() {
        @Override
        public void setOemUnlockAllowedByCarrier(boolean allowed, @Nullable byte[] signature) {
            enforceManageCarrierOemUnlockPermission();
            enforceUserIsAdmin();

            // Note: this implementation does not require a signature

            // Continue using user restriction for backwards compatibility
            final UserHandle userHandle = UserHandle.of(UserHandle.getCallingUserId());
            final long token = Binder.clearCallingIdentity();
            try {
                UserManager.get(mContext)
                        .setUserRestriction(UserManager.DISALLOW_OEM_UNLOCK, !allowed, userHandle);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isOemUnlockAllowedByCarrier() {
            enforceManageCarrierOemUnlockPermission();
            return doIsOemUnlockAllowedByCarrier();
        }

        @Override
        public void setOemUnlockAllowedByUser(boolean allowedByUser) {
            if (ActivityManager.isUserAMonkey()) {
                // Prevent a monkey from changing this
                return;
            }

            enforceManageUserOemUnlockPermission();
            enforceUserIsAdmin();

            final PersistentDataBlockManager pdbm = (PersistentDataBlockManager)
                    mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);

            final long token = Binder.clearCallingIdentity();
            try {
                // The method name is misleading as it really just means whether or not the device
                // can be unlocked but doesn't actually do any unlocking.
                pdbm.setOemUnlockEnabled(allowedByUser);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isOemUnlockAllowedByUser() {
            enforceManageUserOemUnlockPermission();
            return doIsOemUnlockAllowedByUser();
        }
    };

    private void enforceManageCarrierOemUnlockPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_CARRIER_OEM_UNLOCK_STATE,
                "Can't manage OEM unlock allowed by carrier");
    }

    private void enforceManageUserOemUnlockPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_USER_OEM_UNLOCK_STATE,
                "Can't manage OEM unlock allowed by user");
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
