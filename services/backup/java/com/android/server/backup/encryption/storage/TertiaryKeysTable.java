/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.storage;

import static com.android.server.backup.encryption.storage.BackupEncryptionDbContract.TertiaryKeysEntry;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.ArrayMap;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** Database table for storing and retrieving tertiary keys. */
public class TertiaryKeysTable {
    private final BackupEncryptionDbHelper mHelper;

    TertiaryKeysTable(BackupEncryptionDbHelper helper) {
        mHelper = helper;
    }

    /**
     * Adds the {@code tertiaryKey} to the database.
     *
     * @return The primary key of the inserted row if successful, -1 otherwise.
     */
    public long addKey(TertiaryKey tertiaryKey) throws EncryptionDbException {
        SQLiteDatabase db = mHelper.getWritableDatabaseSafe();
        ContentValues values = new ContentValues();
        values.put(
                TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS,
                tertiaryKey.getSecondaryKeyAlias());
        values.put(TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME, tertiaryKey.getPackageName());
        values.put(
                TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES, tertiaryKey.getWrappedKeyBytes());
        return db.replace(TertiaryKeysEntry.TABLE_NAME, /*nullColumnHack=*/ null, values);
    }

    /** Gets the key wrapped by {@code secondaryKeyAlias} for app with {@code packageName}. */
    public Optional<TertiaryKey> getKey(String secondaryKeyAlias, String packageName)
            throws EncryptionDbException {
        SQLiteDatabase db = mHelper.getReadableDatabaseSafe();
        String[] projection = {
            TertiaryKeysEntry._ID,
            TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS,
            TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME,
            TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES
        };
        String selection =
                TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS
                        + " = ? AND "
                        + TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME
                        + " = ?";
        String[] selectionArguments = {secondaryKeyAlias, packageName};

        try (Cursor cursor =
                db.query(
                        TertiaryKeysEntry.TABLE_NAME,
                        projection,
                        selection,
                        selectionArguments,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null)) {
            int count = cursor.getCount();
            if (count == 0) {
                return Optional.empty();
            }

            cursor.moveToFirst();
            byte[] wrappedKeyBytes =
                    cursor.getBlob(
                            cursor.getColumnIndexOrThrow(
                                    TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES));
            return Optional.of(new TertiaryKey(secondaryKeyAlias, packageName, wrappedKeyBytes));
        }
    }

    /** Returns all keys wrapped with {@code tertiaryKeyAlias} as an unmodifiable map. */
    public Map<String, TertiaryKey> getAllKeys(String secondaryKeyAlias)
            throws EncryptionDbException {
        SQLiteDatabase db = mHelper.getReadableDatabaseSafe();
        String[] projection = {
            TertiaryKeysEntry._ID,
            TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS,
            TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME,
            TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES
        };
        String selection = TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS + " = ?";
        String[] selectionArguments = {secondaryKeyAlias};

        Map<String, TertiaryKey> keysByPackageName = new ArrayMap<>();
        try (Cursor cursor =
                db.query(
                        TertiaryKeysEntry.TABLE_NAME,
                        projection,
                        selection,
                        selectionArguments,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null)) {
            while (cursor.moveToNext()) {
                String packageName =
                        cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                        TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME));
                byte[] wrappedKeyBytes =
                        cursor.getBlob(
                                cursor.getColumnIndexOrThrow(
                                        TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES));
                keysByPackageName.put(
                        packageName,
                        new TertiaryKey(secondaryKeyAlias, packageName, wrappedKeyBytes));
            }
        }
        return Collections.unmodifiableMap(keysByPackageName);
    }
}
