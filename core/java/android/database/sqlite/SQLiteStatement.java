/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.database.sqlite;

import android.os.SystemClock;
import android.util.Log;

/**
 * A pre-compiled statement against a {@link SQLiteDatabase} that can be reused.
 * The statement cannot return multiple rows, but 1x1 result sets are allowed.
 * Don't use SQLiteStatement constructor directly, please use
 * {@link SQLiteDatabase#compileStatement(String)}
 */
public class SQLiteStatement extends SQLiteProgram
{
    private static final String TAG = "SQLiteStatement";

    private final String mSql;

    /**
     * Don't use SQLiteStatement constructor directly, please use
     * {@link SQLiteDatabase#compileStatement(String)}
     * @param db
     * @param sql
     */
    /* package */ SQLiteStatement(SQLiteDatabase db, String sql) {
        super(db, sql);
        if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
            mSql = sql;
        } else {
            mSql = null;
        }
    }

    /**
     * Execute this SQL statement, if it is not a query. For example,
     * CREATE TABLE, DELTE, INSERT, etc.
     *
     * @throws android.database.SQLException If the SQL string is invalid for
     *         some reason
     */
    public void execute() {
        mDatabase.lock();
        boolean logStats = mDatabase.mLogStats;
        long startTime = logStats ? SystemClock.elapsedRealtime() : 0;

        acquireReference();
        try {
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                Log.v(TAG, "execute() for [" + mSql + "]");
            }
            native_execute();
            if (logStats) {
                mDatabase.logTimeStat(false /* write */, startTime, SystemClock.elapsedRealtime());
            }
        } finally {                    
            releaseReference();
            mDatabase.unlock();
        }
    }

    /**
     * Execute this SQL statement and return the ID of the most
     * recently inserted row.  The SQL statement should probably be an
     * INSERT for this to be a useful call.
     *
     * @return the row ID of the last row inserted.
     *
     * @throws android.database.SQLException If the SQL string is invalid for
     *         some reason
     */
    public long executeInsert() {
        mDatabase.lock();
        boolean logStats = mDatabase.mLogStats;
        long startTime = logStats ? SystemClock.elapsedRealtime() : 0;

        acquireReference();
        try {
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                Log.v(TAG, "executeInsert() for [" + mSql + "]");
            }
            native_execute();
            if (logStats) {
                mDatabase.logTimeStat(false /* write */, startTime, SystemClock.elapsedRealtime());
            }
            return mDatabase.lastInsertRow();
        } finally {
            releaseReference();
            mDatabase.unlock();
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a numeric value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws android.database.sqlite.SQLiteDoneException if the query returns zero rows
     */
    public long simpleQueryForLong() {
        mDatabase.lock();
        boolean logStats = mDatabase.mLogStats;
        long startTime = logStats ? SystemClock.elapsedRealtime() : 0;

        acquireReference();
        try {
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                Log.v(TAG, "simpleQueryForLong() for [" + mSql + "]");
            }
            long retValue = native_1x1_long();
            if (logStats) {
                mDatabase.logTimeStat(false /* write */, startTime, SystemClock.elapsedRealtime());
            }
            return retValue;
        } finally {
            releaseReference();
            mDatabase.unlock();
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a text value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws android.database.sqlite.SQLiteDoneException if the query returns zero rows
     */
    public String simpleQueryForString() {
        mDatabase.lock();
        boolean logStats = mDatabase.mLogStats;
        long startTime = logStats ? SystemClock.elapsedRealtime() : 0;

        acquireReference();
        try {
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                Log.v(TAG, "simpleQueryForString() for [" + mSql + "]");
            }
            String retValue = native_1x1_string();
            if (logStats) {
                mDatabase.logTimeStat(false /* write */, startTime, SystemClock.elapsedRealtime());
            }
            return retValue;
        } finally {
            releaseReference();
            mDatabase.unlock();
        }
    }

    private final native void native_execute();
    private final native long native_1x1_long();
    private final native String native_1x1_string();
}
