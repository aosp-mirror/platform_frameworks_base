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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;
import android.util.Log;

import com.android.server.locksettings.recoverablekeystore.WrappedKey;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServiceMetadataEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;



import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Database of recoverable key information.
 *
 * @hide
 */
public class RecoverableKeyStoreDb {
    private static final String TAG = "RecoverableKeyStoreDb";
    private static final int IDLE_TIMEOUT_SECONDS = 30;
    private static final int LAST_SYNCED_AT_UNSYNCED = -1;

    private final RecoverableKeyStoreDbHelper mKeyStoreDbHelper;

    /**
     * A new instance, storing the database in the user directory of {@code context}.
     *
     * @hide
     */
    public static RecoverableKeyStoreDb newInstance(Context context) {
        RecoverableKeyStoreDbHelper helper = new RecoverableKeyStoreDbHelper(context);
        helper.setWriteAheadLoggingEnabled(true);
        helper.setIdleConnectionTimeout(IDLE_TIMEOUT_SECONDS);
        return new RecoverableKeyStoreDb(helper);
    }

    private RecoverableKeyStoreDb(RecoverableKeyStoreDbHelper keyStoreDbHelper) {
        this.mKeyStoreDbHelper = keyStoreDbHelper;
    }

    /**
     * Inserts a key into the database.
     *
     * @param userId The uid of the profile the application is running under.
     * @param uid Uid of the application to whom the key belongs.
     * @param alias The alias of the key in the AndroidKeyStore.
     * @param wrappedKey The wrapped key.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long insertKey(int userId, int uid, String alias, WrappedKey wrappedKey) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_USER_ID, userId);
        values.put(KeysEntry.COLUMN_NAME_UID, uid);
        values.put(KeysEntry.COLUMN_NAME_ALIAS, alias);
        values.put(KeysEntry.COLUMN_NAME_NONCE, wrappedKey.getNonce());
        values.put(KeysEntry.COLUMN_NAME_WRAPPED_KEY, wrappedKey.getKeyMaterial());
        values.put(KeysEntry.COLUMN_NAME_LAST_SYNCED_AT, LAST_SYNCED_AT_UNSYNCED);
        values.put(KeysEntry.COLUMN_NAME_GENERATION_ID, wrappedKey.getPlatformKeyGenerationId());
        values.put(KeysEntry.COLUMN_NAME_RECOVERY_STATUS, wrappedKey.getRecoveryStatus());
        return db.replace(KeysEntry.TABLE_NAME, /*nullColumnHack=*/ null, values);
    }

    /**
     * Gets the key with {@code alias} for the app with {@code uid}.
     *
     * @hide
     */
    @Nullable public WrappedKey getKey(int uid, String alias) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                KeysEntry._ID,
                KeysEntry.COLUMN_NAME_NONCE,
                KeysEntry.COLUMN_NAME_WRAPPED_KEY,
                KeysEntry.COLUMN_NAME_GENERATION_ID,
                KeysEntry.COLUMN_NAME_RECOVERY_STATUS};
        String selection =
                KeysEntry.COLUMN_NAME_UID + " = ? AND "
                + KeysEntry.COLUMN_NAME_ALIAS + " = ?";
        String[] selectionArguments = { Integer.toString(uid), alias };

        try (
            Cursor cursor = db.query(
                KeysEntry.TABLE_NAME,
                projection,
                selection,
                selectionArguments,
                /*groupBy=*/ null,
                /*having=*/ null,
                /*orderBy=*/ null)
        ) {
            int count = cursor.getCount();
            if (count == 0) {
                return null;
            }
            if (count > 1) {
                Log.wtf(TAG,
                        String.format(Locale.US,
                                "%d WrappedKey entries found for uid=%d alias='%s'. "
                                        + "Should only ever be 0 or 1.", count, uid, alias));
                return null;
            }
            cursor.moveToFirst();
            byte[] nonce = cursor.getBlob(
                    cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_NONCE));
            byte[] keyMaterial = cursor.getBlob(
                    cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_WRAPPED_KEY));
            int generationId = cursor.getInt(
                    cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_GENERATION_ID));
            int recoveryStatus = cursor.getInt(
                    cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_RECOVERY_STATUS));
            return new WrappedKey(nonce, keyMaterial, generationId, recoveryStatus);
        }
    }

    /**
     * Returns all statuses for keys {@code uid} and {@code platformKeyGenerationId}.
     *
     * @param uid of the application
     *
     * @return Map from Aliases to status.
     *
     * @hide
     */
    public @NonNull Map<String, Integer> getStatusForAllKeys(int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                KeysEntry._ID,
                KeysEntry.COLUMN_NAME_ALIAS,
                KeysEntry.COLUMN_NAME_RECOVERY_STATUS};
        String selection =
                KeysEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(uid)};

        try (
            Cursor cursor = db.query(
                KeysEntry.TABLE_NAME,
                projection,
                selection,
                selectionArguments,
                /*groupBy=*/ null,
                /*having=*/ null,
                /*orderBy=*/ null)
        ) {
            HashMap<String, Integer> statuses = new HashMap<>();
            while (cursor.moveToNext()) {
                String alias = cursor.getString(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_ALIAS));
                int recoveryStatus = cursor.getInt(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_RECOVERY_STATUS));
                statuses.put(alias, recoveryStatus);
            }
            return statuses;
        }
    }

    /**
     * Updates status for given key.
     * @param uid of the application
     * @param alias of the key
     * @param status - new status
     * @return number of updated entries.
     * @hide
     **/
    public int setRecoveryStatus(int uid, String alias, int status) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_RECOVERY_STATUS, status);
        String selection =
                KeysEntry.COLUMN_NAME_UID + " = ? AND "
                + KeysEntry.COLUMN_NAME_ALIAS + " = ?";
        return db.update(KeysEntry.TABLE_NAME, values, selection,
            new String[] {String.valueOf(uid), alias});
    }

    /**
     * Returns all keys for the given {@code userId} and {@code platformKeyGenerationId}.
     *
     * @param userId User id of the profile to which all the keys are associated.
     * @param platformKeyGenerationId The generation ID of the platform key that wrapped these keys.
     *     (i.e., this should be the most recent generation ID, as older platform keys are not
     *     usable.)
     *
     * @hide
     */
    public Map<String, WrappedKey> getAllKeys(int userId, int platformKeyGenerationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                KeysEntry._ID,
                KeysEntry.COLUMN_NAME_NONCE,
                KeysEntry.COLUMN_NAME_WRAPPED_KEY,
                KeysEntry.COLUMN_NAME_ALIAS,
                KeysEntry.COLUMN_NAME_RECOVERY_STATUS};
        String selection =
                KeysEntry.COLUMN_NAME_USER_ID + " = ? AND "
                + KeysEntry.COLUMN_NAME_GENERATION_ID + " = ?";
        String[] selectionArguments = {
                Integer.toString(userId), Integer.toString(platformKeyGenerationId) };

        try (
            Cursor cursor = db.query(
                KeysEntry.TABLE_NAME,
                projection,
                selection,
                selectionArguments,
                /*groupBy=*/ null,
                /*having=*/ null,
                /*orderBy=*/ null)
        ) {
            HashMap<String, WrappedKey> keys = new HashMap<>();
            while (cursor.moveToNext()) {
                byte[] nonce = cursor.getBlob(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_NONCE));
                byte[] keyMaterial = cursor.getBlob(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_WRAPPED_KEY));
                String alias = cursor.getString(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_ALIAS));
                int recoveryStatus = cursor.getInt(
                        cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_RECOVERY_STATUS));
                keys.put(alias, new WrappedKey(nonce, keyMaterial, platformKeyGenerationId,
                        recoveryStatus));
            }
            return keys;
        }
    }

    /**
     * Sets the {@code generationId} of the platform key for the account owned by {@code userId}.
     *
     * @return The primary key ID of the relation.
     */
    public long setPlatformKeyGenerationId(int userId, int generationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(UserMetadataEntry.COLUMN_NAME_USER_ID, userId);
        values.put(UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID, generationId);
        return db.replace(
                UserMetadataEntry.TABLE_NAME, /*nullColumnHack=*/ null, values);
    }

    /**
     * Returns the generation ID associated with the platform key of the user with {@code userId}.
     */
    public int getPlatformKeyGenerationId(int userId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID};
        String selection =
                UserMetadataEntry.COLUMN_NAME_USER_ID + " = ?";
        String[] selectionArguments = {
                Integer.toString(userId)};

        try (
            Cursor cursor = db.query(
                UserMetadataEntry.TABLE_NAME,
                projection,
                selection,
                selectionArguments,
                /*groupBy=*/ null,
                /*having=*/ null,
                /*orderBy=*/ null)
        ) {
            if (cursor.getCount() == 0) {
                return -1;
            }
            cursor.moveToFirst();
            return cursor.getInt(
                    cursor.getColumnIndexOrThrow(
                            UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID));
        }
    }

    /**
     * Updates the public key of the recovery service into the database.
     *
     * @param userId The uid of the profile the application is running under.
     * @param uid The uid of the application to whom the key belongs.
     * @param publicKey The public key of the recovery service.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setRecoveryServicePublicKey(int userId, int uid, PublicKey publicKey) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY, publicKey.getEncoded());
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(
                RecoveryServiceMetadataEntry.TABLE_NAME, values, selection, selectionArguments);
    }

    /**
     * Returns the uid of the recovery agent for the given user, or -1 if none is set.
     */
    public int getRecoveryAgentUid(int userId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = { RecoveryServiceMetadataEntry.COLUMN_NAME_UID };
        String selection = RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ?";
        String[] selectionArguments = { Integer.toString(userId) };

        try (
            Cursor cursor = db.query(
                    RecoveryServiceMetadataEntry.TABLE_NAME,
                    projection,
                    selection,
                    selectionArguments,
                    /*groupBy=*/ null,
                    /*having=*/ null,
                    /*orderBy=*/ null)
        ) {
            int count = cursor.getCount();
            if (count == 0) {
                return -1;
            }
            cursor.moveToFirst();
            return cursor.getInt(
                    cursor.getColumnIndexOrThrow(RecoveryServiceMetadataEntry.COLUMN_NAME_UID));
        }
    }

    /**
     * Returns the public key of the recovery service.
     *
     * @param userId The uid of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     *
     * @hide
     */
    public PublicKey getRecoveryServicePublicKey(int userId, int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY};
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        try (
                Cursor cursor = db.query(
                        RecoveryServiceMetadataEntry.TABLE_NAME,
                        projection,
                        selection,
                        selectionArguments,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null)
        ) {
            int count = cursor.getCount();
            if (count == 0) {
                return null;
            }
            if (count > 1) {
                Log.wtf(TAG,
                        String.format(Locale.US,
                                "%d PublicKey entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(
                    RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY);
            if (cursor.isNull(idx)) {
                return null;
            }
            byte[] keyBytes = cursor.getBlob(idx);
            X509EncodedKeySpec pkSpec = new X509EncodedKeySpec(keyBytes);
            try {
                return KeyFactory.getInstance("EC").generatePublic(pkSpec);
            } catch (NoSuchAlgorithmException e) {
                // Should never happen
                throw new RuntimeException(e);
            } catch (InvalidKeySpecException e) {
                Log.wtf(TAG,
                        String.format(Locale.US,
                                "Recovery service public key entry cannot be decoded for "
                                        + "userId=%d uid=%d.",
                                userId, uid));
                return null;
            }
        }
    }

    /**
     * Updates the server parameters given by the application initializing the local recovery
     * components.
     *
     * @param userId The uid of the profile the application is running under.
     * @param uid The uid of the application.
     * @param serverParameters The server parameters.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setServerParameters(int userId, int uid, long serverParameters) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMETERS, serverParameters);
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(
                RecoveryServiceMetadataEntry.TABLE_NAME, values, selection, selectionArguments);
    }

    /**
     * Returns the server paramters that was previously set by the application who initialized the
     * local recovery service components.
     *
     * @param userId The uid of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return The server parameters that were previously set, or null if there's none.
     *
     * @hide
     */
    public Long getServerParameters(int userId, int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMETERS};
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        try (
                Cursor cursor = db.query(
                        RecoveryServiceMetadataEntry.TABLE_NAME,
                        projection,
                        selection,
                        selectionArguments,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null)
        ) {
            int count = cursor.getCount();
            if (count == 0) {
                return null;
            }
            if (count > 1) {
                Log.wtf(TAG,
                        String.format(Locale.US,
                                "%d deviceId entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(
                    RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMETERS);
            if (cursor.isNull(idx)) {
                return null;
            } else {
                return cursor.getLong(idx);
            }
        }
    }

    /**
     * Creates an empty row in the recovery service metadata table if such a row doesn't exist for
     * the given userId and uid, so db.update will succeed.
     */
    private void ensureRecoveryServiceMetadataEntryExists(int userId, int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID, userId);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_UID, uid);
        db.insertWithOnConflict(RecoveryServiceMetadataEntry.TABLE_NAME, /*nullColumnHack=*/ null,
                values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Closes all open connections to the database.
     */
    public void close() {
        mKeyStoreDbHelper.close();
    }

    // TODO: Add method for updating the 'last synced' time.
}
