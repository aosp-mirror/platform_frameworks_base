package com.android.server.locksettings.recoverablekeystore.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServicePublicKeyEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

/**
 * Helper for creating the recoverable key database.
 */
class RecoverableKeyStoreDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
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
                    + "UNIQUE(" + KeysEntry.COLUMN_NAME_UID + ","
                    + KeysEntry.COLUMN_NAME_ALIAS + "))";

    private static final String SQL_CREATE_USER_METADATA_ENTRY =
            "CREATE TABLE " + UserMetadataEntry.TABLE_NAME + "( "
                    + UserMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + UserMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER UNIQUE,"
                    + UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID + " INTEGER)";

    private static final String SQL_CREATE_RECOVERY_SERVICE_PUBLIC_KEY_ENTRY =
            "CREATE TABLE " + RecoveryServicePublicKeyEntry.TABLE_NAME + " ("
                    + RecoveryServicePublicKeyEntry._ID + " INTEGER PRIMARY KEY,"
                    + RecoveryServicePublicKeyEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + RecoveryServicePublicKeyEntry.COLUMN_NAME_UID + " INTEGER,"
                    + RecoveryServicePublicKeyEntry.COLUMN_NAME_PUBLIC_KEY + " BLOB,"
                    + "UNIQUE("
                    + RecoveryServicePublicKeyEntry.COLUMN_NAME_USER_ID  + ","
                    + RecoveryServicePublicKeyEntry.COLUMN_NAME_UID + "))";

    private static final String SQL_DELETE_KEYS_ENTRY =
            "DROP TABLE IF EXISTS " + KeysEntry.TABLE_NAME;

    private static final String SQL_DELETE_USER_METADATA_ENTRY =
            "DROP TABLE IF EXISTS " + UserMetadataEntry.TABLE_NAME;

    private static final String SQL_DELETE_RECOVERY_SERVICE_PUBLIC_KEY_ENTRY =
            "DROP TABLE IF EXISTS " + RecoveryServicePublicKeyEntry.TABLE_NAME;

    RecoverableKeyStoreDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_KEYS_ENTRY);
        db.execSQL(SQL_CREATE_USER_METADATA_ENTRY);
        db.execSQL(SQL_CREATE_RECOVERY_SERVICE_PUBLIC_KEY_ENTRY);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DELETE_KEYS_ENTRY);
        db.execSQL(SQL_DELETE_USER_METADATA_ENTRY);
        db.execSQL(SQL_DELETE_RECOVERY_SERVICE_PUBLIC_KEY_ENTRY);
        onCreate(db);
    }
}
