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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

/** Helper for creating an instance of the backup encryption database. */
class BackupEncryptionDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    static final String DATABASE_NAME = "backupencryption.db";

    private static final String SQL_CREATE_TERTIARY_KEYS_ENTRY =
            "CREATE TABLE "
                    + TertiaryKeysEntry.TABLE_NAME
                    + " ( "
                    + TertiaryKeysEntry._ID
                    + " INTEGER PRIMARY KEY,"
                    + TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS
                    + " TEXT,"
                    + TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME
                    + " TEXT,"
                    + TertiaryKeysEntry.COLUMN_NAME_WRAPPED_KEY_BYTES
                    + " BLOB,"
                    + "UNIQUE("
                    + TertiaryKeysEntry.COLUMN_NAME_SECONDARY_KEY_ALIAS
                    + ","
                    + TertiaryKeysEntry.COLUMN_NAME_PACKAGE_NAME
                    + "))";

    private static final String SQL_DROP_TERTIARY_KEYS_ENTRY =
            "DROP TABLE IF EXISTS " + TertiaryKeysEntry.TABLE_NAME;

    BackupEncryptionDbHelper(Context context) {
        super(context, DATABASE_NAME, /*factory=*/ null, DATABASE_VERSION);
    }

    public void resetDatabase() throws EncryptionDbException {
        SQLiteDatabase db = getWritableDatabaseSafe();
        db.execSQL(SQL_DROP_TERTIARY_KEYS_ENTRY);
        onCreate(db);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_TERTIARY_KEYS_ENTRY);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DROP_TERTIARY_KEYS_ENTRY);
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DROP_TERTIARY_KEYS_ENTRY);
        onCreate(db);
    }

    /**
     * Calls {@link #getWritableDatabase()}, but catches the unchecked {@link SQLiteException} and
     * rethrows {@link EncryptionDbException}.
     */
    public SQLiteDatabase getWritableDatabaseSafe() throws EncryptionDbException {
        try {
            return super.getWritableDatabase();
        } catch (SQLiteException e) {
            throw new EncryptionDbException(e);
        }
    }

    /**
     * Calls {@link #getReadableDatabase()}, but catches the unchecked {@link SQLiteException} and
     * rethrows {@link EncryptionDbException}.
     */
    public SQLiteDatabase getReadableDatabaseSafe() throws EncryptionDbException {
        try {
            return super.getReadableDatabase();
        } catch (SQLiteException e) {
            throw new EncryptionDbException(e);
        }
    }
}
