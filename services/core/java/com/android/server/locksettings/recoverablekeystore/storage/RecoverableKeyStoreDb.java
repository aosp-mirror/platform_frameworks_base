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

import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.android.server.locksettings.recoverablekeystore.WrappedKey;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

import java.util.HashMap;
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
     * @param uid Uid of the application to whom the key belongs.
     * @param alias The alias of the key in the AndroidKeyStore.
     * @param wrappedKey The wrapped key.
     * @return The primary key of the inserted row, or -1 if failed.
     *
     * @hide
     */
    public long insertKey(int uid, String alias, WrappedKey wrappedKey) {
        SQLiteDatabase db = mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_UID, uid);
        values.put(KeysEntry.COLUMN_NAME_ALIAS, alias);
        values.put(KeysEntry.COLUMN_NAME_NONCE, wrappedKey.getNonce());
        values.put(KeysEntry.COLUMN_NAME_WRAPPED_KEY, wrappedKey.getKeyMaterial());
        values.put(KeysEntry.COLUMN_NAME_LAST_SYNCED_AT, -1);
        values.put(KeysEntry.COLUMN_NAME_GENERATION_ID, wrappedKey.getPlatformKeyGenerationId());
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
                KeysEntry.COLUMN_NAME_GENERATION_ID};
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
            return new WrappedKey(nonce, keyMaterial, generationId);
        }
    }

    /**
     * Returns all keys for the given {@code uid} and {@code platformKeyGenerationId}.
     *
     * @param uid User id of the profile to which all the keys are associated.
     * @param platformKeyGenerationId The generation ID of the platform key that wrapped these keys.
     *     (i.e., this should be the most recent generation ID, as older platform keys are not
     *     usable.)
     *
     * @hide
     */
    public Map<String, WrappedKey> getAllKeys(int uid, int platformKeyGenerationId) {
        SQLiteDatabase db = mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {
                KeysEntry._ID,
                KeysEntry.COLUMN_NAME_NONCE,
                KeysEntry.COLUMN_NAME_WRAPPED_KEY,
                KeysEntry.COLUMN_NAME_ALIAS};
        String selection =
                KeysEntry.COLUMN_NAME_UID + " = ? AND "
                + KeysEntry.COLUMN_NAME_GENERATION_ID + " = ?";
        String[] selectionArguments = {
                Integer.toString(uid), Integer.toString(platformKeyGenerationId) };

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
                keys.put(alias, new WrappedKey(nonce, keyMaterial, platformKeyGenerationId));
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
     * Closes all open connections to the database.
     */
    public void close() {
        mKeyStoreDbHelper.close();
    }

    // TODO: Add method for updating the 'last synced' time.
}
