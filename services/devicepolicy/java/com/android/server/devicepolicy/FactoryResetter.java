/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RecoverySystem;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;

/**
 * Entry point for "factory reset" requests.
 */
final class FactoryResetter {

    private static final String TAG = FactoryResetter.class.getSimpleName();

    // TODO(b/171603586): use an object that's constructed with a Builder instead (then update
    // javadoc)
    /**
     * Factory reset the device.
     */
    public static void factoryReset(Context context, boolean shutdown, String reason,
            boolean force, boolean wipeEuicc, boolean wipeAdoptableStorage,
            boolean wipeFactoryResetProtection) throws IOException {
        Log.i(TAG, "factoryReset(): shutdown=" + shutdown + ", force=" + force
                + ", wipeEuicc=" + wipeEuicc + ", wipeAdoptableStorage=" + wipeAdoptableStorage
                + ", wipeFRP=" + wipeFactoryResetProtection);

        Preconditions.checkCallAuthorization(context.checkCallingOrSelfPermission(
                android.Manifest.permission.MASTER_CLEAR) == PackageManager.PERMISSION_GRANTED);

        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (!force && um.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)) {
            throw new SecurityException("Factory reset is not allowed for this user.");
        }

        if (wipeFactoryResetProtection) {
            PersistentDataBlockManager manager = (PersistentDataBlockManager)
                    context.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
            if (manager != null) {
                Log.w(TAG, "Wiping factory reset protection");
                manager.wipe();
            } else {
                Log.w(TAG, "No need to wipe factory reset protection");
            }
        }

        if (wipeAdoptableStorage) {
            Log.w(TAG, "Wiping adoptable storage");
            StorageManager sm = (StorageManager) context.getSystemService(
                    Context.STORAGE_SERVICE);
            sm.wipeAdoptableDisks();
        }

        RecoverySystem.rebootWipeUserData(context, shutdown, reason, force, wipeEuicc);
    }

    private FactoryResetter() {
        throw new UnsupportedOperationException("Contains only static methods");
    }
}
