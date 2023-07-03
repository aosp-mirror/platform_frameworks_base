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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServiceMetadataEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RootOfTrustEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

/**
 * Helper for creating the recoverable key database.
 */
class RecoverableKeyStoreDbHelper extends SQLiteOpenHelper {
    private static final String TAG = "RecoverableKeyStoreDbHp";

    // v6 - added user id serial number.
    // v7 - added bad guess counter for remote LSKF check;
    static final int DATABASE_VERSION_7 = 7;
    private static final String DATABASE_NAME = "recoverablekeystore.db";

    private static final String SQL_CREATE_KEYS_ENTRY =
            "CREATE TABLE " + KeysEntry.TABLE_NAME + "( "
                    + KeysEntry._ID + " INTEGER PRIMARY KEY,"
                    + KeysEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_UID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_ALIAS + " TEXT,"
                    + KeysEntry.COLUMN_NAME_NONCE + " BLOB,"
                    + KeysEntry.COLUMN_NAME_WRAPPED_KEY + " BLOB,"
                    + KeysEntry.COLUMN_NAME_GENERATION_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_LAST_SYNCED_AT + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_RECOVERY_STATUS + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_KEY_METADATA + " BLOB,"
                    + "UNIQUE(" + KeysEntry.COLUMN_NAME_UID + ","
                    + KeysEntry.COLUMN_NAME_ALIAS + "))";

    private static final String SQL_CREATE_USER_METADATA_ENTRY =
            "CREATE TABLE " + UserMetadataEntry.TABLE_NAME + "( "
                    + UserMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + UserMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER UNIQUE,"
                    + UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID + " INTEGER,"
                    + UserMetadataEntry.COLUMN_NAME_USER_SERIAL_NUMBER + " INTEGER DEFAULT -1)";


    private static final String SQL_CREATE_USER_METADATA_ENTRY_FOR_V7 =
            "CREATE TABLE " + UserMetadataEntry.TABLE_NAME + "( "
                    + UserMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + UserMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER UNIQUE,"
                    + UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID + " INTEGER,"
                    + UserMetadataEntry.COLUMN_NAME_USER_SERIAL_NUMBER + " INTEGER DEFAULT -1,"
                    + UserMetadataEntry.COLUMN_NAME_BAD_REMOTE_GUESS_COUNTER
                    + " INTEGER DEFAULT 0)";

    private static final String SQL_CREATE_RECOVERY_SERVICE_METADATA_ENTRY =
            "CREATE TABLE " + RecoveryServiceMetadataEntry.TABLE_NAME + " ("
                    + RecoveryServiceMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST + " TEXT,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY + " BLOB,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_PATH + " BLOB,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_SERIAL + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES + " TEXT,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS + " BLOB,"
                    + "UNIQUE("
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID  + ","
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + "))";

    private static final String SQL_CREATE_ROOT_OF_TRUST_ENTRY =
            "CREATE TABLE " + RootOfTrustEntry.TABLE_NAME + " ("
                    + RootOfTrustEntry._ID + " INTEGER PRIMARY KEY,"
                    + RootOfTrustEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + RootOfTrustEntry.COLUMN_NAME_UID + " INTEGER,"
                    + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + " TEXT,"
                    + RootOfTrustEntry.COLUMN_NAME_CERT_PATH + " BLOB,"
                    + RootOfTrustEntry.COLUMN_NAME_CERT_SERIAL + " INTEGER,"
                    + "UNIQUE("
                    + RootOfTrustEntry.COLUMN_NAME_USER_ID  + ","
                    + RootOfTrustEntry.COLUMN_NAME_UID  + ","
                    + RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS + "))";

    private static final String SQL_DELETE_KEYS_ENTRY =
            "DROP TABLE IF EXISTS " + KeysEntry.TABLE_NAME;

    private static final String SQL_DELETE_USER_METADATA_ENTRY =
            "DROP TABLE IF EXISTS " + UserMetadataEntry.TABLE_NAME;

    private static final String SQL_DELETE_RECOVERY_SERVICE_METADATA_ENTRY =
            "DROP TABLE IF EXISTS " + RecoveryServiceMetadataEntry.TABLE_NAME;

    private static final String SQL_DELETE_ROOT_OF_TRUST_ENTRY =
            "DROP TABLE IF EXISTS " + RootOfTrustEntry.TABLE_NAME;

    RecoverableKeyStoreDbHelper(Context context) {
        super(context, DATABASE_NAME, null, getDbVersion(context));
    }

    private static int getDbVersion(Context context) {
        return DATABASE_VERSION_7;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_KEYS_ENTRY);
        db.execSQL(SQL_CREATE_USER_METADATA_ENTRY_FOR_V7);
        db.execSQL(SQL_CREATE_RECOVERY_SERVICE_METADATA_ENTRY);
        db.execSQL(SQL_CREATE_ROOT_OF_TRUST_ENTRY);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.e(TAG, "Recreating recoverablekeystore after unexpected version downgrade.");
        dropAllKnownTables(db); // Wipe database.
        onCreate(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            if (oldVersion < 2) {
                dropAllKnownTables(db); // Wipe database.
                onCreate(db);
                return;
            }

            if (oldVersion < 3 && newVersion >= 3) {
                upgradeDbForVersion3(db);
                oldVersion = 3;
            }

            if (oldVersion < 4 && newVersion >= 4) {
                upgradeDbForVersion4(db);
                oldVersion = 4;
            }

            if (oldVersion < 5 && newVersion >= 5) {
                upgradeDbForVersion5(db);
                oldVersion = 5;
            }

            if (oldVersion < 6 && newVersion >= 6) {
                upgradeDbForVersion6(db);
                oldVersion = 6;
            }

            if (oldVersion < 7 && newVersion >= 7) {
                try {
                    upgradeDbForVersion7(db);
                } catch (SQLiteException e) {
                    Log.w(TAG, "Column was added without version update - ignore error", e);
                }
                oldVersion = 7;
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Recreating recoverablekeystore after unexpected upgrade error.", e);
            dropAllKnownTables(db); // Wipe database.
            onCreate(db);
            return;
        }
        if (oldVersion != newVersion) {
            Log.e(TAG, "Failed to update recoverablekeystore database to the most recent version");
        }
    }

    private void dropAllKnownTables(SQLiteDatabase db) {
            db.execSQL(SQL_DELETE_KEYS_ENTRY);
            db.execSQL(SQL_DELETE_USER_METADATA_ENTRY);
            db.execSQL(SQL_DELETE_RECOVERY_SERVICE_METADATA_ENTRY);
            db.execSQL(SQL_DELETE_ROOT_OF_TRUST_ENTRY);
    }

    private void upgradeDbForVersion3(SQLiteDatabase db) {
        // Add the two columns for cert path and cert serial number
        addColumnToTable(db, RecoveryServiceMetadataEntry.TABLE_NAME,
                RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_PATH, "BLOB", /*defaultStr=*/ null);
        addColumnToTable(db, RecoveryServiceMetadataEntry.TABLE_NAME,
                RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_SERIAL, "INTEGER", /*defaultStr=*/
                null);
    }

    private void upgradeDbForVersion4(SQLiteDatabase db) {
        Log.d(TAG, "Updating recoverable keystore database to version 4");
        // Add new table with two columns for cert path and cert serial number.
        db.execSQL(SQL_CREATE_ROOT_OF_TRUST_ENTRY);
        // adds column to store root of trust currently used by the recovery agent
        addColumnToTable(db, RecoveryServiceMetadataEntry.TABLE_NAME,
                RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST, "TEXT",
                /*defaultStr=*/ null);
    }

    private void upgradeDbForVersion5(SQLiteDatabase db) {
        Log.d(TAG, "Updating recoverable keystore database to version 5");
        // adds a column to store the metadata for application keys
        addColumnToTable(db, KeysEntry.TABLE_NAME,
                KeysEntry.COLUMN_NAME_KEY_METADATA, "BLOB", /*defaultStr=*/ null);
    }

    private void upgradeDbForVersion6(SQLiteDatabase db) {
        Log.d(TAG, "Updating recoverable keystore database to version 6");
        // adds a column to store the user serial number
        addColumnToTable(db, UserMetadataEntry.TABLE_NAME,
                UserMetadataEntry.COLUMN_NAME_USER_SERIAL_NUMBER,
                "INTEGER DEFAULT -1",
                 /*defaultStr=*/ null);
    }

    private void upgradeDbForVersion7(SQLiteDatabase db) {
        Log.d(TAG, "Updating recoverable keystore database to version 7");
        addColumnToTable(db, UserMetadataEntry.TABLE_NAME,
                UserMetadataEntry.COLUMN_NAME_BAD_REMOTE_GUESS_COUNTER,
                "INTEGER DEFAULT 0",
                 /*defaultStr=*/ null);
    }

    private static void addColumnToTable(
            SQLiteDatabase db, String tableName, String column, String columnType,
            String defaultStr) {
        Log.d(TAG, "Adding column " + column + " to " + tableName + ".");

        String alterStr = "ALTER TABLE " + tableName + " ADD COLUMN " + column + " " + columnType;
        if (defaultStr != null && !defaultStr.isEmpty()) {
            alterStr += " DEFAULT " + defaultStr;
        }

        db.execSQL(alterStr + ";");
    }
}

