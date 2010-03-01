/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.content;

import com.android.internal.util.ArrayUtils;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.accounts.Account;
import android.content.ContentValues;
import android.provider.SyncStateContract;

/**
 * Extends the schema of a ContentProvider to include the _sync_state table
 * and implements query/insert/update/delete to access that table using the
 * authority "syncstate". This can be used to store the sync state for a
 * set of accounts.
 *
 * @hide
 */
public class SyncStateContentProviderHelper {
    private static final String SELECT_BY_ACCOUNT =
            SyncStateContract.Columns.ACCOUNT_NAME + "=? AND "
                    + SyncStateContract.Columns.ACCOUNT_TYPE + "=?";

    private static final String SYNC_STATE_TABLE = "_sync_state";
    private static final String SYNC_STATE_META_TABLE = "_sync_state_metadata";
    private static final String SYNC_STATE_META_VERSION_COLUMN = "version";

    private static long DB_VERSION = 1;

    private static final String[] ACCOUNT_PROJECTION =
            new String[]{SyncStateContract.Columns.ACCOUNT_NAME,
                    SyncStateContract.Columns.ACCOUNT_TYPE};

    public static final String PATH = "syncstate";

    private static final String QUERY_COUNT_SYNC_STATE_ROWS =
            "SELECT count(*)"
                    + " FROM " + SYNC_STATE_TABLE
                    + " WHERE " + SyncStateContract.Columns._ID + "=?";

    public void createDatabase(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + SYNC_STATE_TABLE);
        db.execSQL("CREATE TABLE " + SYNC_STATE_TABLE + " ("
                + SyncStateContract.Columns._ID + " INTEGER PRIMARY KEY,"
                + SyncStateContract.Columns.ACCOUNT_NAME + " TEXT NOT NULL,"
                + SyncStateContract.Columns.ACCOUNT_TYPE + " TEXT NOT NULL,"
                + SyncStateContract.Columns.DATA + " TEXT,"
                + "UNIQUE(" + SyncStateContract.Columns.ACCOUNT_NAME + ", "
                + SyncStateContract.Columns.ACCOUNT_TYPE + "));");

        db.execSQL("DROP TABLE IF EXISTS " + SYNC_STATE_META_TABLE);
        db.execSQL("CREATE TABLE " + SYNC_STATE_META_TABLE + " ("
                + SYNC_STATE_META_VERSION_COLUMN + " INTEGER);");
        ContentValues values = new ContentValues();
        values.put(SYNC_STATE_META_VERSION_COLUMN, DB_VERSION);
        db.insert(SYNC_STATE_META_TABLE, SYNC_STATE_META_VERSION_COLUMN, values);
    }

    public void onDatabaseOpened(SQLiteDatabase db) {
        long version = DatabaseUtils.longForQuery(db,
                "SELECT " + SYNC_STATE_META_VERSION_COLUMN + " FROM " + SYNC_STATE_META_TABLE,
                null);
        if (version != DB_VERSION) {
            createDatabase(db);
        }
    }

    public Cursor query(SQLiteDatabase db, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        return db.query(SYNC_STATE_TABLE, projection, selection, selectionArgs,
                null, null, sortOrder);
    }

    public long insert(SQLiteDatabase db, ContentValues values) {
        return db.replace(SYNC_STATE_TABLE, SyncStateContract.Columns.ACCOUNT_NAME, values);
    }

    public int delete(SQLiteDatabase db, String userWhere, String[] whereArgs) {
        return db.delete(SYNC_STATE_TABLE, userWhere, whereArgs);
    }

    public int update(SQLiteDatabase db, ContentValues values,
            String selection, String[] selectionArgs) {
        return db.update(SYNC_STATE_TABLE, values, selection, selectionArgs);
    }

    public int update(SQLiteDatabase db, long rowId, Object data) {
        if (DatabaseUtils.longForQuery(db, QUERY_COUNT_SYNC_STATE_ROWS,
                new String[]{Long.toString(rowId)}) < 1) {
            return 0;
        }
        db.execSQL("UPDATE " + SYNC_STATE_TABLE
                + " SET " + SyncStateContract.Columns.DATA + "=?"
                + " WHERE " + SyncStateContract.Columns._ID + "=" + rowId,
                new Object[]{data});
        // assume a row was modified since we know it exists
        return 1;
    }

    public void onAccountsChanged(SQLiteDatabase db, Account[] accounts) {
        Cursor c = db.query(SYNC_STATE_TABLE, ACCOUNT_PROJECTION, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                final String accountName = c.getString(0);
                final String accountType = c.getString(1);
                Account account = new Account(accountName, accountType);
                if (!ArrayUtils.contains(accounts, account)) {
                    db.delete(SYNC_STATE_TABLE, SELECT_BY_ACCOUNT,
                            new String[]{accountName, accountType});
                }
            }
        } finally {
            c.close();
        }
    }
}