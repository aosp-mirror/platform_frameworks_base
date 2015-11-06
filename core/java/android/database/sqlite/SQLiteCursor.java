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

import android.database.AbstractWindowedCursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.os.StrictMode;
import android.util.Log;
import android.util.MutableBoolean;

import java.util.HashMap;
import java.util.Map;

/**
 * A Cursor implementation that exposes results from a query on a
 * {@link SQLiteDatabase}.
 *
 * SQLiteCursor is not internally synchronized so code using a SQLiteCursor from multiple
 * threads should perform its own synchronization when using the SQLiteCursor.
 */
public class SQLiteCursor extends AbstractWindowedCursor {
    static final String TAG = "SQLiteCursor";
    static final int NO_COUNT = -1;

    /** The name of the table to edit */
    private final String mEditTable;

    /** The names of the columns in the rows */
    private final String[] mColumns;

    /** The query object for the cursor */
    private final SQLiteQuery mQuery;

    /** The compiled query this cursor came from */
    private final SQLiteCursorDriver mDriver;

    /** The number of rows in the cursor */
    private int mCount = NO_COUNT;

    /** The number of rows we've found so far. Invariants:
     *  1. mFound >= 0
     *  2. mFound will decrease only when requery() is called
     *  3. mFound == mCount iff mCount != NO_COUNT
     */
    private int mFound = 0;

    /* Cached here for use in method implementations - don't use this to store "permanent" info. */
    private final MutableBoolean mTmpBoolean = new MutableBoolean(false);

    /** The number of rows that can fit in the cursor window, 0 if unknown */
    private int mCursorWindowCapacity;

    /** A mapping of column names to column indices, to speed up lookups */
    private Map<String, Integer> mColumnNameMap;

    /** Used to find out where a cursor was allocated in case it never got released. */
    private final Throwable mStackTrace;

    /**
     * Execute a query and provide access to its result set through a Cursor
     * interface. For a query such as: {@code SELECT name, birth, phone FROM
     * myTable WHERE ... LIMIT 1,20 ORDER BY...} the column names (name, birth,
     * phone) would be in the projection argument and everything from
     * {@code FROM} onward would be in the params argument.
     *
     * @param db a reference to a Database object that is already constructed
     *     and opened. This param is not used any longer
     * @param editTable the name of the table used for this query
     * @param query the rest of the query terms
     *     cursor is finalized
     * @deprecated use {@link #SQLiteCursor(SQLiteCursorDriver, String, SQLiteQuery)} instead
     */
    @Deprecated
    public SQLiteCursor(SQLiteDatabase db, SQLiteCursorDriver driver,
            String editTable, SQLiteQuery query) {
        this(driver, editTable, query);
    }

    /**
     * Execute a query and provide access to its result set through a Cursor
     * interface. For a query such as: {@code SELECT name, birth, phone FROM
     * myTable WHERE ... LIMIT 1,20 ORDER BY...} the column names (name, birth,
     * phone) would be in the projection argument and everything from
     * {@code FROM} onward would be in the params argument.
     *
     * @param editTable the name of the table used for this query
     * @param query the {@link SQLiteQuery} object associated with this cursor object.
     */
    public SQLiteCursor(SQLiteCursorDriver driver, String editTable, SQLiteQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query object cannot be null");
        }
        if (StrictMode.vmSqliteObjectLeaksEnabled()) {
            mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        } else {
            mStackTrace = null;
        }
        mDriver = driver;
        mEditTable = editTable;
        mColumnNameMap = null;
        mQuery = query;

        mColumns = query.getColumnNames();
    }

    /**
     * Get the database that this cursor is associated with.
     * @return the SQLiteDatabase that this cursor is associated with.
     */
    public SQLiteDatabase getDatabase() {
        return mQuery.getDatabase();
    }

    public boolean onMove(int oldPosition, int newPosition) {
        // Make sure the row at newPosition is present in the window
        if (mWindow == null || newPosition < mWindow.getStartPosition() ||
                newPosition >= (mWindow.getStartPosition() + mWindow.getNumRows())) {
            throw new IllegalStateException("newPosition should be in the window at this point");
        }

        return true;
    }

    /** @hide */
    @Override
    protected int onMoveWithBoundsCheck(int position) {
        if (mCount != NO_COUNT && position >= mCount) {
            return MOVE_AFTER_LAST;
        } else if (position >= mFound) {
            // okay, there are more rows to find -- maybe enough to reach our new position?
            fillWindow(position, false);
            if (position >= mFound) {
                return MOVE_AFTER_LAST; // we tried.
            }
        } else if (position < 0) {
            return MOVE_BEFORE_FIRST;
        } else if (position == mPos) {
            return MOVE_NOP;
        } else {
            fillWindow(position, false);
        }

        if (!onMove(mPos, position)) {
            return MOVE_FAILED;
        }
        return MOVE_OK;
    }

    @Override
    public int getCount() {
        if (mCount == NO_COUNT) {
            // might as well get some data if we don't already have some
            if (mWindow == null) {
                fillWindow(0, true);
            } else {
                traverseQuery(0, 0, null, true);
            }
        }
        assert mCount != NO_COUNT;
        return mCount;
    }

    /** @hide */
    @Override
    protected boolean isAfterLast(int position) {
        if (position < mFound) {
            return false; // we've found enough rows to say this wasn't the end
        } else if (mCount != NO_COUNT) {
            return true; // position was not in what we've counted, and we've counted everything
        }

        // might as well get some data if we don't already have some
        if (mWindow == null) {
            fillWindow(position, false);
        } else {
            traverseQuery(0, position, null, false);
        }
        assert mCount == 0 || mFound > 0;
        return position >= mFound;
    }


    private void fillWindow(int requiredPos, boolean countAll) {
        requiredPos = Math.max(0, requiredPos);
        final int startPos;
        if (mWindow == null) {
            startPos = 0;
        } else {
            final int winStart = mWindow.getStartPosition();
            final int winEnd = winStart + mWindow.getNumRows();
            if (requiredPos >= winStart && requiredPos < winEnd) {
                return; // we already have what we need.
            }
            startPos = DatabaseUtils.cursorPickFillWindowStartPosition(
                    requiredPos, mCursorWindowCapacity);
        }

        clearOrCreateWindow(getDatabase().getPath());
        traverseQuery(startPos, requiredPos, mWindow, countAll);
    }

    private void traverseQuery(int requiredPos) {
        traverseQuery(requiredPos, requiredPos, null, false); // need no data - start=required.
    }

    private void traverseQuery(int startPos, int requiredPos, CursorWindow w, boolean countAll) {
        try {
            MutableBoolean exhausted = mTmpBoolean;
            exhausted.value = false;
            final int found = mQuery.traverse(w, startPos, requiredPos, countAll, exhausted);
            if (w != null && mCursorWindowCapacity == 0) {
                mCursorWindowCapacity = w.getNumRows();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "received count(*) from native_fill_window: " + mCount);
                }
            }
            if (exhausted.value) {
                // we exhausted the whole result set, so we know the count.
                mCount = mFound = found;
            } else {
                mFound = Math.max(mFound, found);
            }
        } catch (RuntimeException ex) {
            // Close the cursor window if the query failed and therefore will
            // not produce any results.  This helps to avoid accidentally leaking
            // the cursor window if the client does not correctly handle exceptions
            // and fails to close the cursor.
            closeWindow();
            throw ex;
        }
    }

    @Override
    public int getColumnIndex(String columnName) {
        // Create mColumnNameMap on demand
        if (mColumnNameMap == null) {
            String[] columns = mColumns;
            int columnCount = columns.length;
            HashMap<String, Integer> map = new HashMap<String, Integer>(columnCount, 1);
            for (int i = 0; i < columnCount; i++) {
                map.put(columns[i], i);
            }
            mColumnNameMap = map;
        }

        // Hack according to bug 903852
        final int periodIndex = columnName.lastIndexOf('.');
        if (periodIndex != -1) {
            Exception e = new Exception();
            Log.e(TAG, "requesting column name with table name -- " + columnName, e);
            columnName = columnName.substring(periodIndex + 1);
        }

        Integer i = mColumnNameMap.get(columnName);
        if (i != null) {
            return i.intValue();
        } else {
            return -1;
        }
    }

    @Override
    public String[] getColumnNames() {
        return mColumns;
    }

    @Override
    public void deactivate() {
        super.deactivate();
        mQuery.deactivate();
        mDriver.cursorDeactivated();
    }

    @Override
    public void close() {
        super.close();
        synchronized (this) {
            mQuery.close();
            mDriver.cursorClosed();
        }
    }

    @Override
    public boolean requery() {
        if (isClosed()) {
            return false;
        }

        synchronized (this) {
            if (!mQuery.getDatabase().isOpen()) {
                return false;
            }

            if (mWindow != null) {
                mWindow.clear();
            }
            mPos = -1;
            mCount = NO_COUNT;

            mQuery.onRequery();
            mDriver.cursorRequeried(this);
        }

        try {
            return super.requery();
        } catch (IllegalStateException e) {
            // for backwards compatibility, just return false
            Log.w(TAG, "requery() failed " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void setWindow(CursorWindow window) {
        super.setWindow(window);
        mCount = NO_COUNT;
    }

    /**
     * Changes the selection arguments. The new values take effect after a call to requery().
     */
    public void setSelectionArguments(String[] selectionArgs) {
        mDriver.setBindArguments(selectionArgs);
    }

    /**
     * Release the native resources, if they haven't been released yet.
     */
    @Override
    protected void finalize() {
        try {
            // if the cursor hasn't been closed yet, close it first
            if (mWindow != null) {
                if (mStackTrace != null) {
                    String sql = mQuery.getSql();
                    int len = sql.length();
                    StrictMode.onSqliteObjectLeaked(
                        "Finalizing a Cursor that has not been deactivated or closed. " +
                        "database = " + mQuery.getDatabase().getLabel() +
                        ", table = " + mEditTable +
                        ", query = " + sql.substring(0, (len > 1000) ? 1000 : len),
                        mStackTrace);
                }
                close();
            }
        } finally {
            super.finalize();
        }
    }
}
