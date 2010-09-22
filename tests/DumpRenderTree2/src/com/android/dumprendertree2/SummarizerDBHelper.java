/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;

/**
 * A basic class that wraps database accesses inside itself and provides functionality to
 * store and retrieve AbstractResults.
 */
public class SummarizerDBHelper {
    private static final String KEY_ID = "id";
    private static final String KEY_PATH = "path";
    private static final String KEY_BYTES = "bytes";

    private static final String DATABASE_NAME = "SummarizerDB";
    private static final int DATABASE_VERSION = 1;

    static final String EXPECTED_FAILURES_TABLE = "expectedFailures";
    static final String UNEXPECTED_FAILURES_TABLE = "unexpectedFailures";
    static final String EXPECTED_PASSES_TABLE = "expextedPasses";
    static final String UNEXPECTED_PASSES_TABLE = "unexpextedPasses";
    private static final Set<String> TABLES_NAMES = new HashSet<String>();
    {
        TABLES_NAMES.add(EXPECTED_FAILURES_TABLE);
        TABLES_NAMES.add(EXPECTED_PASSES_TABLE);
        TABLES_NAMES.add(UNEXPECTED_FAILURES_TABLE);
        TABLES_NAMES.add(UNEXPECTED_PASSES_TABLE);
    }

    private static final void createTables(SQLiteDatabase db) {
        String cmd;
        for (String tableName : TABLES_NAMES) {
            cmd = "create table " + tableName + " ("
                    + KEY_ID + " integer primary key autoincrement, "
                    + KEY_PATH + " text not null, "
                    + KEY_BYTES + " blob not null);";
            db.execSQL(cmd);
        }
    }

    private static final void dropTables(SQLiteDatabase db) {
        for (String tableName : TABLES_NAMES) {
            db.execSQL("DROP TABLE IF EXISTS " + tableName);
        }
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            dropTables(db);
            createTables(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            /** NOOP for now, because we will never upgrade the db */
        }

        public void reset(SQLiteDatabase db) {
            dropTables(db);
            createTables(db);
        }
    }

    private DatabaseHelper mDbHelper;
    private SQLiteDatabase mDb;

    private final Context mContext;

    public SummarizerDBHelper(Context ctx) {
        mContext = ctx;
        mDbHelper = new DatabaseHelper(mContext);
    }

    public void reset() {
        mDbHelper.reset(this.mDb);
    }

    public void open() throws SQLException {
        mDb = mDbHelper.getWritableDatabase();
    }

    public void close() {
        mDbHelper.close();
    }

    public void insertAbstractResult(AbstractResult result, String table) {
        ContentValues cv = new ContentValues();
        cv.put(KEY_PATH, result.getRelativePath());
        cv.put(KEY_BYTES, result.getBytes());
        mDb.insert(table, null, cv);
    }

    public Cursor getAbstractResults(String table) throws SQLException {
        return mDb.query(false, table, new String[] {KEY_BYTES}, null, null, null, null,
                KEY_PATH + " ASC", null);
    }

    public static AbstractResult getAbstractResult(Cursor cursor) {
        return AbstractResult.create(cursor.getBlob(cursor.getColumnIndex(KEY_BYTES)));
    }
}