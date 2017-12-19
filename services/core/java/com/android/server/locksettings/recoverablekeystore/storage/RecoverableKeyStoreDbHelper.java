package com.android.server.locksettings.recoverablekeystore.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;

/**
 * Helper for creating the recoverable key database.
 */
class RecoverableKeyStoreDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "recoverablekeystore.db";

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + KeysEntry.TABLE_NAME + "( "
                    + KeysEntry._ID + " INTEGER PRIMARY KEY,"
                    + KeysEntry.COLUMN_NAME_UID + " INTEGER UNIQUE,"
                    + KeysEntry.COLUMN_NAME_ALIAS + " TEXT UNIQUE,"
                    + KeysEntry.COLUMN_NAME_NONCE + " BLOB,"
                    + KeysEntry.COLUMN_NAME_WRAPPED_KEY + " BLOB,"
                    + KeysEntry.COLUMN_NAME_GENERATION_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_LAST_SYNCED_AT + " INTEGER)";

    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS " + KeysEntry.TABLE_NAME;

    RecoverableKeyStoreDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DELETE_ENTRIES);
        onCreate(db);
    }
}
