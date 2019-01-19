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
import android.security.keystore.recovery.RecoveryController;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.locksettings.recoverablekeystore.TestOnlyInsecureCertificateHelper;
import com.android.server.locksettings.recoverablekeystore.WrappedKey;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServiceMetadataEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RootOfTrustEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Database of recoverable key information.
 *
 * @hide
 */
public class RecoverableKeyStoreDb {
    private static final String TAG = "RecoverableKeyStoreDb";
    private static final int IDLE_TIMEOUT_SECONDS = 30;
    private static final int LAST_SYNCED_AT_UNSYNCED = -1;
    private static final String CERT_PATH_ENCODING = "PkiPath";

    private final RecoverableKeyStoreDbHelper mKeyStoreDbHelper;
    private final TestOnlyInsecureCertificateHelper mTestOnlyInsecureCertificateHelper;

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
        this.mTestOnlyInsecureCertificateHelper = new TestOnlyInsecureCertificateHelper();
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
        byte[] keyMetadata = wrappedKey.getKeyMetadata();
        if (keyMetadata == null) {
            values.putNull(KeysEntry.COLUMN_NAME_KEY_METADATA);
        } else {
            values.put(KeysEntry.COLUMN_NAME_KEY_METADATA, keyMetadata);
        }
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
                KeysEntry.COLUMN_NAME_RECOVERY_STATUS,
                KeysEntry.COLUMN_NAME_KEY_METADATA};
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

            // Retrieve the metadata associated with the key
            byte[] keyMetadata;
            int metadataIdx = cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_KEY_METADATA);
            if (cursor.isNull(metadataIdx)) {
                keyMetadata = null;
            } else {
                keyMetadata = cursor.getBlob(metadataIdx);
            }

            return new WrappedKey(nonce, keyMaterial, keyMetadata, generationId, recoveryStatus);
        }
    }

    /**
     * Removes key with {@code alias} for app with {@code uid}.
     *
     * @return {@code true} if deleted a row.
     */
    public boolean removeKey(int uid, String alias) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        String selection = KeysEntry.COLUMN_NAME_UID + " = ? AND " +
                KeysEntry.COLUMN_NAME_ALIAS + " = ?";
        String[] selectionArgs = { Integer.toString(uid), alias };
        return db.delete(KeysEntry.TABLE_NAME, selection, selectionArgs) > 0;
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
     * Returns all keys for the given {@code userId} {@code recoveryAgentUid}
     * and {@code platformKeyGenerationId}.
     *
     * @param userId User id of the profile to which all the keys are associated.
     * @param recoveryAgentUid Uid of the recovery agent which will perform the sync
     * @param platformKeyGenerationId The generation ID of the platform key that wrapped these keys.
     *     (i.e., this should be the most recent generation ID, as older platform keys are not
     *     usable.)
     *
     * @hide
     */
    public Map<String, WrappedKey> getAllKeys(int userId, int recoveryAgentUid,
            int platformKeyGenerationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                KeysEntry._ID,
                KeysEntry.COLUMN_NAME_NONCE,
                KeysEntry.COLUMN_NAME_WRAPPED_KEY,
                KeysEntry.COLUMN_NAME_ALIAS,
                KeysEntry.COLUMN_NAME_RECOVERY_STATUS,
                KeysEntry.COLUMN_NAME_KEY_METADATA};
        String selection =
                KeysEntry.COLUMN_NAME_USER_ID + " = ? AND "
                + KeysEntry.COLUMN_NAME_UID + " = ? AND "
                + KeysEntry.COLUMN_NAME_GENERATION_ID + " = ?";
        String[] selectionArguments = {
                Integer.toString(userId),
                Integer.toString(recoveryAgentUid),
                Integer.toString(platformKeyGenerationId)
            };

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

                // Retrieve the metadata associated with the key
                byte[] keyMetadata;
                int metadataIdx = cursor.getColumnIndexOrThrow(KeysEntry.COLUMN_NAME_KEY_METADATA);
                if (cursor.isNull(metadataIdx)) {
                    keyMetadata = null;
                } else {
                    keyMetadata = cursor.getBlob(metadataIdx);
                }

                keys.put(alias, new WrappedKey(nonce, keyMaterial, keyMetadata,
                        platformKeyGenerationId, recoveryStatus));
            }
            return keys;
        }
    }

    /**
     * Sets the {@code generationId} of the platform key for user {@code userId}.
     *
     * @return The primary key ID of the relation.
     */
    public long setPlatformKeyGenerationId(int userId, int generationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(UserMetadataEntry.COLUMN_NAME_USER_ID, userId);
        values.put(UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID, generationId);
        long result = db.replace(
                UserMetadataEntry.TABLE_NAME, /*nullColumnHack=*/ null, values);
        if (result != -1) {
            invalidateKeysWithOldGenerationId(userId, generationId);
        }
        return result;
    }

    /**
     * Updates status of old keys to {@code RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE}.
     */
    public void invalidateKeysWithOldGenerationId(int userId, int newGenerationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_RECOVERY_STATUS,
                RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
        String selection =
                KeysEntry.COLUMN_NAME_USER_ID + " = ? AND "
                + KeysEntry.COLUMN_NAME_GENERATION_ID + " < ?";
        db.update(KeysEntry.TABLE_NAME, values, selection,
            new String[] {String.valueOf(userId), String.valueOf(newGenerationId)});
    }

    /**
     * Updates status of old keys to {@code RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE}.
     */
    public void invalidateKeysForUserIdOnCustomScreenLock(int userId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_RECOVERY_STATUS,
            RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
        String selection =
            KeysEntry.COLUMN_NAME_USER_ID + " = ?";
        db.update(KeysEntry.TABLE_NAME, values, selection,
            new String[] {String.valueOf(userId)});
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
        return setBytes(userId, uid, RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY,
                publicKey.getEncoded());
    }

    /**
     * Returns the serial number of the XML file containing certificates of the recovery service.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     * @param rootAlias The root of trust alias.
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public Long getRecoveryServiceCertSerial(int userId, int uid, @NonNull String rootAlias) {
        return getLong(userId, uid, rootAlias,
                RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_SERIAL);
    }

    /**
     * Records the serial number of the XML file containing certificates of the recovery service.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param serial The serial number contained in the XML file for recovery service certificates.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setRecoveryServiceCertSerial(int userId, int uid, @NonNull String rootAlias,
            long serial) {
        return setLong(userId, uid, rootAlias, RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_SERIAL,
                serial);
    }

    /**
     * Returns the {@code CertPath} of the recovery service.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     * @param rootAlias The root of trust alias.
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public CertPath getRecoveryServiceCertPath(int userId, int uid, @NonNull String rootAlias) {
        byte[] bytes = getBytes(userId, uid, rootAlias,
                RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_PATH);
        if (bytes == null) {
            return null;
        }
        try {
            return decodeCertPath(bytes);
        } catch (CertificateException e) {
            Log.wtf(TAG,
                    String.format(Locale.US,
                            "Recovery service CertPath entry cannot be decoded for "
                                    + "userId=%d uid=%d.",
                            userId, uid), e);
            return null;
        }
    }

    /**
     * Sets the {@code CertPath} of the recovery service.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param certPath The certificate path of the recovery service.
     * @return The primary key of the inserted row, or -1 if failed.
     * @hide
     */
    public long setRecoveryServiceCertPath(int userId, int uid, @NonNull String rootAlias,
            CertPath certPath) throws CertificateEncodingException {
        if (certPath.getCertificates().size() == 0) {
            throw new CertificateEncodingException("No certificate contained in the cert path.");
        }
        return setBytes(userId, uid, rootAlias, RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_PATH,
                certPath.getEncoded(CERT_PATH_ENCODING));
    }

    /**
     * Returns the list of recovery agents initialized for given {@code userId}
     * @param userId The userId of the profile the application is running under.
     * @return The list of recovery agents
     * @hide
     */
    public @NonNull List<Integer> getRecoveryAgents(int userId) {
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
            ArrayList<Integer> result = new ArrayList<>(count);
            while (cursor.moveToNext()) {
                int uid = cursor.getInt(
                        cursor.getColumnIndexOrThrow(RecoveryServiceMetadataEntry.COLUMN_NAME_UID));
                result.add(uid);
            }
            return result;
        }
    }

    /**
     * Returns the public key of the recovery service.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initializes the local recovery components.
     *
     * @hide
     */
    @Nullable
    public PublicKey getRecoveryServicePublicKey(int userId, int uid) {
        byte[] keyBytes =
                getBytes(userId, uid, RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY);
        if (keyBytes == null) {
            return null;
        }
        try {
            return decodeX509Key(keyBytes);
        } catch (InvalidKeySpecException e) {
            Log.wtf(TAG,
                    String.format(Locale.US,
                            "Recovery service public key entry cannot be decoded for "
                                    + "userId=%d uid=%d.",
                            userId, uid));
            return null;
        }
    }

    /**
     * Updates the list of user secret types used for end-to-end encryption.
     * If no secret types are set, recovery snapshot will not be created.
     * See {@code KeyChainProtectionParams}
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param secretTypes list of secret types
     * @return The primary key of the updated row, or -1 if failed.
     *
     * @hide
     */
    public long setRecoverySecretTypes(int userId, int uid, int[] secretTypes) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        StringJoiner joiner = new StringJoiner(",");
        Arrays.stream(secretTypes).forEach(i -> joiner.add(Integer.toString(i)));
        String typesAsCsv = joiner.toString();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES, typesAsCsv);
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(RecoveryServiceMetadataEntry.TABLE_NAME, values, selection,
            new String[] {String.valueOf(userId), String.valueOf(uid)});
    }

    /**
     * Returns the list of secret types used for end-to-end encryption.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return Secret types or empty array, if types were not set.
     *
     * @hide
     */
    public @NonNull int[] getRecoverySecretTypes(int userId, int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES};
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
                return new int[]{};
            }
            if (count > 1) {
                Log.wtf(TAG,
                        String.format(Locale.US,
                                "%d deviceId entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return new int[]{};
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(
                    RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES);
            if (cursor.isNull(idx)) {
                return new int[]{};
            }
            String csv = cursor.getString(idx);
            if (TextUtils.isEmpty(csv)) {
                return new int[]{};
            }
            String[] types = csv.split(",");
            int[] result = new int[types.length];
            for (int i = 0; i < types.length; i++) {
                try {
                    result[i] = Integer.parseInt(types[i]);
                } catch (NumberFormatException e) {
                    Log.wtf(TAG, "String format error " + e);
                }
            }
            return result;
        }
    }

    /**
     * Active root of trust for the recovery agent.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param rootAlias The root of trust alias.
     * @return The primary key of the updated row, or -1 if failed.
     *
     * @hide
     */
    public long setActiveRootOfTrust(int userId, int uid, @Nullable String rootAlias) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST, rootAlias);
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(RecoveryServiceMetadataEntry.TABLE_NAME, values,
            selection, new String[] {String.valueOf(userId), String.valueOf(uid)});
    }

    /**
     * Active root of trust for the recovery agent.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return Active root of trust alias of null if it was not set
     *
     * @hide
     */
    public @Nullable String getActiveRootOfTrust(int userId, int uid) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST};
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
                    RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST);
            if (cursor.isNull(idx)) {
                return null;
            }
            String result = cursor.getString(idx);
            if (TextUtils.isEmpty(result)) {
                return null;
            }
            return result;
        }
    }

    /**
     * Updates the counterId
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param counterId The counterId.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setCounterId(int userId, int uid, long counterId) {
        return setLong(userId, uid,
                RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID, counterId);
    }

    /**
     * Returns the counter id.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return The counter id
     *
     * @hide
     */
    @Nullable
    public Long getCounterId(int userId, int uid) {
        return getLong(userId, uid, RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID);
    }

    /**
     * Updates the server parameters given by the application initializing the local recovery
     * components.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param serverParams The server parameters.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setServerParams(int userId, int uid, byte[] serverParams) {
        return setBytes(userId, uid,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS, serverParams);
    }

    /**
     * Returns the server paramters that was previously set by the application who initialized the
     * local recovery service components.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return The server parameters that were previously set, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public byte[] getServerParams(int userId, int uid) {
        return getBytes(userId, uid, RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS);
    }

    /**
     * Updates the snapshot version.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param snapshotVersion The snapshot version
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setSnapshotVersion(int userId, int uid, long snapshotVersion) {
        return setLong(userId, uid,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION, snapshotVersion);
    }

    /**
     * Returns the snapshot version
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return The server parameters that were previously set, or null if there's none.
     *
     * @hide
     */
    @Nullable
    public Long getSnapshotVersion(int userId, int uid) {
        return getLong(userId, uid,
            RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION);
    }

    /**
     * Updates a flag indicating that a new snapshot should be created.
     * It will be {@code false} until the first application key is added.
     * After that, the flag will be set to true, if one of the following values is updated:
     * <ul>
     *     <li> List of application keys
     *     <li> Server params.
     *     <li> Lock-screen secret.
     *     <li> Lock-screen secret type.
     *     <li> Trusted hardware certificate.
     * </ul>
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application.
     * @param pending Should create snapshot flag.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long setShouldCreateSnapshot(int userId, int uid, boolean pending) {
        return setLong(userId, uid,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT, pending ? 1 : 0);
    }

    /**
     * Returns {@code true} if new snapshot should be created.
     * Returns {@code false} if the flag was never set.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @return should create snapshot flag
     *
     * @hide
     */
    public boolean getShouldCreateSnapshot(int userId, int uid) {
        Long res = getLong(userId, uid,
                RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT);
        return res != null && res != 0L;
    }


    /**
     * Returns given long value from the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param key from {@code RecoveryServiceMetadataEntry}
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    private Long getLong(int userId, int uid, String key) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                key};
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
                                "%d entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(key);
            if (cursor.isNull(idx)) {
                return null;
            } else {
                return cursor.getLong(idx);
            }
        }
    }

    /**
     * Sets a long value in the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param key defined in {@code RecoveryServiceMetadataEntry}
     * @param value new value.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */

    private long setLong(int userId, int uid, String key, long value) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(
                RecoveryServiceMetadataEntry.TABLE_NAME, values, selection, selectionArguments);
    }

    /**
     * Returns given binary value from the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param key from {@code RecoveryServiceMetadataEntry}
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    private byte[] getBytes(int userId, int uid, String key) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RecoveryServiceMetadataEntry._ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID,
                RecoveryServiceMetadataEntry.COLUMN_NAME_UID,
                key};
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
                                "%d entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(key);
            if (cursor.isNull(idx)) {
                return null;
            } else {
                return cursor.getBlob(idx);
            }
        }
    }

    /**
     * Sets a binary value in the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param key defined in {@code RecoveryServiceMetadataEntry}
     * @param value new value.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    private long setBytes(int userId, int uid, String key, byte[] value) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String selection =
                RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};

        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update(
                RecoveryServiceMetadataEntry.TABLE_NAME, values, selection, selectionArguments);
    }

    /**
     * Returns given binary value from the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param key from {@code RootOfTrustEntry}
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    private byte[] getBytes(int userId, int uid, String rootAlias, String key) {
        rootAlias = mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RootOfTrustEntry._ID,
                RootOfTrustEntry.COLUMN_NAME_USER_ID,
                RootOfTrustEntry.COLUMN_NAME_UID,
                RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS,
                key};
        String selection =
                RootOfTrustEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_UID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias};

        try (
            Cursor cursor = db.query(
                    RootOfTrustEntry.TABLE_NAME,
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
                                "%d entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(key);
            if (cursor.isNull(idx)) {
                return null;
            } else {
                return cursor.getBlob(idx);
            }
        }
    }

    /**
     * Sets a binary value in the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param key defined in {@code RootOfTrustEntry}
     * @param value new value.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    private long setBytes(int userId, int uid, String rootAlias, String key, byte[] value) {
        rootAlias = mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String selection =
                RootOfTrustEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_UID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias};

        ensureRootOfTrustEntryExists(userId, uid, rootAlias);
        return db.update(
                RootOfTrustEntry.TABLE_NAME, values, selection, selectionArguments);
    }

    /**
     * Returns given long value from the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param key from {@code RootOfTrustEntry}
     * @return The value that were previously set, or null if there's none.
     *
     * @hide
     */
    private Long getLong(int userId, int uid, String rootAlias, String key) {
        rootAlias = mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();

        String[] projection = {
                RootOfTrustEntry._ID,
                RootOfTrustEntry.COLUMN_NAME_USER_ID,
                RootOfTrustEntry.COLUMN_NAME_UID,
                RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS,
                key};
        String selection =
                RootOfTrustEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_UID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias};

        try (
            Cursor cursor = db.query(
                    RootOfTrustEntry.TABLE_NAME,
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
                                "%d entries found for userId=%d uid=%d. "
                                        + "Should only ever be 0 or 1.", count, userId, uid));
                return null;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow(key);
            if (cursor.isNull(idx)) {
                return null;
            } else {
                return cursor.getLong(idx);
            }
        }
    }

    /**
     * Sets a long value in the database.
     *
     * @param userId The userId of the profile the application is running under.
     * @param uid The uid of the application who initialized the local recovery components.
     * @param rootAlias The root of trust alias.
     * @param key defined in {@code RootOfTrustEntry}
     * @param value new value.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */

    private long setLong(int userId, int uid, String rootAlias, String key, long value) {
        rootAlias = mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String selection =
                RootOfTrustEntry.COLUMN_NAME_USER_ID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_UID + " = ? AND "
                        + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + " = ?";
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias};

        ensureRootOfTrustEntryExists(userId, uid, rootAlias);
        return db.update(
                RootOfTrustEntry.TABLE_NAME, values, selection, selectionArguments);
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
     * Creates an empty row in the root of trust table if such a row doesn't exist for
     * the given userId and uid, so db.update will succeed.
     */
    private void ensureRootOfTrustEntryExists(int userId, int uid, String rootAlias) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RootOfTrustEntry.COLUMN_NAME_USER_ID, userId);
        values.put(RootOfTrustEntry.COLUMN_NAME_UID, uid);
        values.put(RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS, rootAlias);
        db.insertWithOnConflict(RootOfTrustEntry.TABLE_NAME, /*nullColumnHack=*/ null,
                values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Closes all open connections to the database.
     */
    public void close() {
        mKeyStoreDbHelper.close();
    }

    @Nullable
    private static PublicKey decodeX509Key(byte[] keyBytes) throws InvalidKeySpecException {
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private static CertPath decodeCertPath(byte[] bytes) throws CertificateException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            // Should not happen, as X.509 is mandatory for all providers.
            throw new RuntimeException(e);
        }
        return certFactory.generateCertPath(new ByteArrayInputStream(bytes), CERT_PATH_ENCODING);
    }
}
