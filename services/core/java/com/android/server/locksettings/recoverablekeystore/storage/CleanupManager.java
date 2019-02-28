/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.content.Context;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.WrappedKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cleans up data when user is removed.
 */
public class CleanupManager {
    private static final String TAG = "CleanupManager";

    private final Context mContext;
    private final UserManager mUserManager;
    private final RecoverableKeyStoreDb mDatabase;
    private final RecoverySnapshotStorage mSnapshotStorage;
    private final ApplicationKeyStorage mApplicationKeyStorage;

    // Serial number can not be changed at runtime.
    private Map<Integer, Long> mSerialNumbers; // Always in sync with the database.

    /**
     * Creates a new instance of the class.
     * IMPORTANT: {@code verifyKnownUsers} must be called before the first data access.
     */
    public static CleanupManager getInstance(
            Context context,
            RecoverySnapshotStorage snapshotStorage,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            ApplicationKeyStorage applicationKeyStorage) {
        return new CleanupManager(
                context,
                snapshotStorage,
                recoverableKeyStoreDb,
                UserManager.get(context),
                applicationKeyStorage);
    }

    @VisibleForTesting
    CleanupManager(
            Context context,
            RecoverySnapshotStorage snapshotStorage,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            UserManager userManager,
            ApplicationKeyStorage applicationKeyStorage) {
        mContext = context;
        mSnapshotStorage = snapshotStorage;
        mDatabase = recoverableKeyStoreDb;
        mUserManager = userManager;
        mApplicationKeyStorage = applicationKeyStorage;
    }

    /**
     * Registers recovery agent in the system, if necessary.
     */
    public synchronized void registerRecoveryAgent(int userId, int uid) {
        if (mSerialNumbers == null) {
            // Table was uninitialized.
            verifyKnownUsers();
        }
        // uid is ignored since recovery agent is a system app.
        Long storedSerialNumber =  mSerialNumbers.get(userId);
        if (storedSerialNumber == null) {
            storedSerialNumber = -1L;
        }
        if (storedSerialNumber != -1) {
            // User was already registered.
            return;
        }
        // User was added after {@code verifyAllUsers} call.
        long currentSerialNumber = mUserManager.getSerialNumberForUser(UserHandle.of(userId));
        if (currentSerialNumber != -1) {
            storeUserSerialNumber(userId, currentSerialNumber);
        }
    }

    /**
     * Removes data if serial number for a user was changed.
     */
    public synchronized void verifyKnownUsers() {
        mSerialNumbers =  mDatabase.getUserSerialNumbers();
        List<Integer> deletedUserIds = new ArrayList<Integer>(){};
        for (Map.Entry<Integer, Long> entry : mSerialNumbers.entrySet()) {
            Integer userId = entry.getKey();
            Long storedSerialNumber = entry.getValue();
            if (storedSerialNumber == null) {
                storedSerialNumber = -1L;
            }
            long currentSerialNumber = mUserManager.getSerialNumberForUser(UserHandle.of(userId));
            if (currentSerialNumber == -1) {
                // User was removed.
                deletedUserIds.add(userId);
                removeDataForUser(userId);
            } else if (storedSerialNumber == -1) {
                // User is detected for the first time
                storeUserSerialNumber(userId, currentSerialNumber);
            } else if (storedSerialNumber != currentSerialNumber) {
                // User has unexpected serial number - delete data related to old serial number.
                deletedUserIds.add(userId);
                removeDataForUser(userId);
                // Register new user.
                storeUserSerialNumber(userId, currentSerialNumber);
            }
        }

        for (Integer deletedUser : deletedUserIds) {
            mSerialNumbers.remove(deletedUser);
        }
    }

    private void storeUserSerialNumber(int userId, long userSerialNumber) {
        Log.d(TAG, "Storing serial number for user " + userId + ".");
        mSerialNumbers.put(userId, userSerialNumber);
        mDatabase.setUserSerialNumber(userId, userSerialNumber);
    }

    /**
     * Removes all data for given user, including
     *
     * <ul>
     *     <li> Recovery snapshots for all agents belonging to the {@code userId}.
     *     <li> Entries with data related to {@code userId} from the database.
     * </ul>
     */
    private void removeDataForUser(int userId) {
        Log.d(TAG, "Removing data for user " + userId + ".");
        List<Integer> recoveryAgents = mDatabase.getRecoveryAgents(userId);
        for (Integer uid : recoveryAgents) {
            mSnapshotStorage.remove(uid);
            removeAllKeysForRecoveryAgent(userId, uid);
        }

        mDatabase.removeUserFromAllTables(userId);
    }

    /**
     * Removes keys from Android KeyStore for the recovery agent;
     * Doesn't remove encrypted key material from the database.
     */
    private void removeAllKeysForRecoveryAgent(int userId, int uid) {
        int generationId = mDatabase.getPlatformKeyGenerationId(userId);
        Map<String, WrappedKey> allKeys = mDatabase.getAllKeys(userId, uid, generationId);
        for (String alias : allKeys.keySet()) {
            try {
                // Delete KeyStore copy.
                mApplicationKeyStorage.deleteEntry(userId, uid, alias);
            } catch (ServiceSpecificException e) {
                // Ignore errors during key removal.
                Log.e(TAG, "Error while removing recoverable key " + alias + " : " + e);
            }
        }
    }
}
