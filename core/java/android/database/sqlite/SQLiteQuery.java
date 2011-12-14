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

import android.database.CursorWindow;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * A SQLite program that represents a query that reads the resulting rows into a CursorWindow.
 * This class is used by SQLiteCursor and isn't useful itself.
 *
 * SQLiteQuery is not internally synchronized so code using a SQLiteQuery from multiple
 * threads should perform its own synchronization when using the SQLiteQuery.
 */
public final class SQLiteQuery extends SQLiteProgram {
    private static final String TAG = "SQLiteQuery";

    private static native long nativeFillWindow(int databasePtr, int statementPtr, int windowPtr,
            int offsetParam, int startPos, int requiredPos, boolean countAllRows);

    private static native int nativeColumnCount(int statementPtr);
    private static native String nativeColumnName(int statementPtr, int columnIndex);

    /** The index of the unbound OFFSET parameter */
    private int mOffsetIndex = 0;

    private boolean mClosed = false;

    /**
     * Create a persistent query object.
     *
     * @param db The database that this query object is associated with
     * @param query The SQL string for this query. 
     * @param offsetIndex The 1-based index to the OFFSET parameter, 
     */
    /* package */ SQLiteQuery(SQLiteDatabase db, String query, int offsetIndex, String[] bindArgs) {
        super(db, query);
        mOffsetIndex = offsetIndex;
        bindAllArgsAsStrings(bindArgs);
    }

    /**
     * Constructor used to create new instance to replace a given instance of this class.
     * This constructor is used when the current Query object is now associated with a different
     * {@link SQLiteDatabase} object.
     *
     * @param db The database that this query object is associated with
     * @param query the instance of {@link SQLiteQuery} to be replaced
     */
    /* package */ SQLiteQuery(SQLiteDatabase db, SQLiteQuery query) {
        super(db, query.mSql);
        this.mBindArgs = query.mBindArgs;
        this.mOffsetIndex = query.mOffsetIndex;
    }

    /**
     * Reads rows into a buffer. This method acquires the database lock.
     *
     * @param window The window to fill into
     * @param startPos The start position for filling the window.
     * @param requiredPos The position of a row that MUST be in the window.
     * If it won't fit, then the query should discard part of what it filled.
     * @param countAllRows True to count all rows that the query would
     * return regardless of whether they fit in the window.
     * @return Number of rows that were enumerated.  Might not be all rows
     * unless countAllRows is true.
     */
    /* package */ int fillWindow(CursorWindow window,
            int startPos, int requiredPos, boolean countAllRows) {
        mDatabase.lock(mSql);
        long timeStart = SystemClock.uptimeMillis();
        try {
            acquireReference();
            try {
                window.acquireReference();
                long result = nativeFillWindow(nHandle, nStatement, window.mWindowPtr,
                        mOffsetIndex, startPos, requiredPos, countAllRows);
                int actualPos = (int)(result >> 32);
                int countedRows = (int)result;
                window.setStartPosition(actualPos);
                if (SQLiteDebug.DEBUG_LOG_SLOW_QUERIES) {
                    long elapsed = SystemClock.uptimeMillis() - timeStart;
                    if (SQLiteDebug.shouldLogSlowQuery(elapsed)) {
                        Log.d(TAG, "fillWindow took " + elapsed
                                + " ms: window=\"" + window
                                + "\", startPos=" + startPos
                                + ", requiredPos=" + requiredPos
                                + ", offset=" + mOffsetIndex
                                + ", actualPos=" + actualPos
                                + ", filledRows=" + window.getNumRows()
                                + ", countedRows=" + countedRows
                                + ", query=\"" + mSql + "\""
                                + ", args=[" + (mBindArgs != null ?
                                        TextUtils.join(", ", mBindArgs.values()) : "")
                                + "]");
                    }
                }
                mDatabase.logTimeStat(mSql, timeStart);
                return countedRows;
            } catch (IllegalStateException e){
                // simply ignore it
                return 0;
            } catch (SQLiteDatabaseCorruptException e) {
                mDatabase.onCorruption();
                throw e;
            } catch (SQLiteException e) {
                Log.e(TAG, "exception: " + e.getMessage() + "; query: " + mSql);
                throw e;
            } finally {
                window.releaseReference();
            }
        } finally {
            releaseReference();
            mDatabase.unlock();
        }
    }

    /**
     * Get the column count for the statement. Only valid on query based
     * statements. The database must be locked
     * when calling this method.
     * 
     * @return The number of column in the statement's result set.
     */
    /* package */ int columnCountLocked() {
        acquireReference();
        try {
            return nativeColumnCount(nStatement);
        } finally {
            releaseReference();
        }
    }

    /**
     * Retrieves the column name for the given column index. The database must be locked
     * when calling this method.
     * 
     * @param columnIndex the index of the column to get the name for
     * @return The requested column's name
     */
    /* package */ String columnNameLocked(int columnIndex) {
        acquireReference();
        try {
            return nativeColumnName(nStatement, columnIndex);
        } finally {
            releaseReference();
        }
    }

    @Override
    public String toString() {
        return "SQLiteQuery: " + mSql;
    }

    @Override
    public void close() {
        super.close();
        mClosed = true;
    }

    /**
     * Called by SQLiteCursor when it is requeried.
     */
    /* package */ void requery() {
        if (mClosed) {
            throw new IllegalStateException("requerying a closed cursor");
        }
        compileAndbindAllArgs();
    }
}
