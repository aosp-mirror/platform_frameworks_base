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

/**
 * A SQLite program that represents a query that reads the resulting rows into a CursorWindow.
 * This class is used by SQLiteCursor and isn't useful itself.
 *
 * SQLiteQuery is not internally synchronized so code using a SQLiteQuery from multiple
 * threads should perform its own synchronization when using the SQLiteQuery.
 */
public class SQLiteQuery extends SQLiteProgram {
    private static final String TAG = "SQLiteQuery";

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
    }

    /**
     * Reads rows into a buffer. This method acquires the database lock.
     *
     * @param window The window to fill into
     * @return number of total rows in the query
     */
    /* package */ int fillWindow(CursorWindow window,
            int maxRead, int lastPos) {
        long timeStart = SystemClock.uptimeMillis();
        mDatabase.lock();
        mDatabase.logTimeStat(mSql, timeStart, SQLiteDatabase.GET_LOCK_LOG_PREFIX);
        try {
            acquireReference();
            try {
                window.acquireReference();
                // if the start pos is not equal to 0, then most likely window is
                // too small for the data set, loading by another thread
                // is not safe in this situation. the native code will ignore maxRead
                int numRows = native_fill_window(window, window.getStartPosition(), mOffsetIndex,
                        maxRead, lastPos);
                mDatabase.logTimeStat(mSql, timeStart);
                return numRows;
            } catch (IllegalStateException e){
                // simply ignore it
                return 0;
            } catch (SQLiteDatabaseCorruptException e) {
                mDatabase.onCorruption();
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
            return native_column_count();
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
            return native_column_name(columnIndex);
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

    private final native int native_fill_window(CursorWindow window,
            int startPos, int offsetParam, int maxRead, int lastPos);

    private final native int native_column_count();

    private final native String native_column_name(int columnIndex);
}
