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

package android.database;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Config;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.lang.ref.WeakReference;
import java.lang.UnsupportedOperationException;
import java.util.HashMap;
import java.util.Map;


/**
 * This is an abstract cursor class that handles a lot of the common code
 * that all cursors need to deal with and is provided for convenience reasons.
 */
public abstract class AbstractCursor implements CrossProcessCursor {
    private static final String TAG = "Cursor";

    DataSetObservable mDataSetObservable = new DataSetObservable();
    ContentObservable mContentObservable = new ContentObservable();

    /* -------------------------------------------------------- */
    /* These need to be implemented by subclasses */
    abstract public int getCount();

    abstract public String[] getColumnNames();

    abstract public String getString(int column);
    abstract public short getShort(int column);
    abstract public int getInt(int column);
    abstract public long getLong(int column);
    abstract public float getFloat(int column);
    abstract public double getDouble(int column);
    abstract public boolean isNull(int column);

    // TODO implement getBlob in all cursor types
    public byte[] getBlob(int column) {
        throw new UnsupportedOperationException("getBlob is not supported");
    }
    /* -------------------------------------------------------- */
    /* Methods that may optionally be implemented by subclasses */

    /**
     * returns a pre-filled window, return NULL if no such window
     */
    public CursorWindow getWindow() {
        return null;
    }

    public int getColumnCount() {
        return getColumnNames().length;
    }
    
    public void deactivate() {
        deactivateInternal();
    }
    
    /**
     * @hide
     */
    public void deactivateInternal() {
        if (mSelfObserver != null) {
            mContentResolver.unregisterContentObserver(mSelfObserver);
            mSelfObserverRegistered = false;
        }
        mDataSetObservable.notifyInvalidated();
    }
    
    public boolean requery() {
        if (mSelfObserver != null && mSelfObserverRegistered == false) {
            mContentResolver.registerContentObserver(mNotifyUri, true, mSelfObserver);
            mSelfObserverRegistered = true;
        }
        mDataSetObservable.notifyChanged();
        return true;
    }

    public boolean isClosed() {
        return mClosed;
    }
    
    public void close() {
        mClosed = true;
        mContentObservable.unregisterAll();
        deactivateInternal();
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean commitUpdates(Map<? extends Long,? extends Map<String,Object>> values) {
        return false;
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean deleteRow() {
        return false;
    }

    /**
     * This function is called every time the cursor is successfully scrolled
     * to a new position, giving the subclass a chance to update any state it
     * may have. If it returns false the move function will also do so and the
     * cursor will scroll to the beforeFirst position.
     *
     * @param oldPosition the position that we're moving from
     * @param newPosition the position that we're moving to
     * @return true if the move is successful, false otherwise
     */
    public boolean onMove(int oldPosition, int newPosition) {
        return true;
    }

    
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        // Default implementation, uses getString
        String result = getString(columnIndex);
        if (result != null) {
            char[] data = buffer.data;
            if (data == null || data.length < result.length()) {
                buffer.data = result.toCharArray();
            } else {
                result.getChars(0, result.length(), data, 0);
            }
            buffer.sizeCopied = result.length();
        }
    }
    
    /* -------------------------------------------------------- */
    /* Implementation */
    public AbstractCursor() {
        mPos = -1;
        mRowIdColumnIndex = -1;
        mCurrentRowID = null;
        mUpdatedRows = new HashMap<Long, Map<String, Object>>();
    }

    public final int getPosition() {
        return mPos;
    }

    public final boolean moveToPosition(int position) {
        // Make sure position isn't past the end of the cursor
        final int count = getCount();
        if (position >= count) {
            mPos = count;
            return false;
        }

        // Make sure position isn't before the beginning of the cursor
        if (position < 0) {
            mPos = -1;
            return false;
        }

        // Check for no-op moves, and skip the rest of the work for them
        if (position == mPos) {
            return true;
        }

        boolean result = onMove(mPos, position);
        if (result == false) {
            mPos = -1;
        } else {
            mPos = position;
            if (mRowIdColumnIndex != -1) {
                mCurrentRowID = Long.valueOf(getLong(mRowIdColumnIndex));
            }
        }

        return result;
    }
    
    /**
     * Copy data from cursor to CursorWindow
     * @param position start position of data
     * @param window
     */
    public void fillWindow(int position, CursorWindow window) {
        if (position < 0 || position > getCount()) {
            return;
        }
        window.acquireReference();
        try {
            int oldpos = mPos;
            mPos = position - 1;
            window.clear();
            window.setStartPosition(position);
            int columnNum = getColumnCount();
            window.setNumColumns(columnNum);
            while (moveToNext() && window.allocRow()) {            
                for (int i = 0; i < columnNum; i++) {
                    String field = getString(i);
                    if (field != null) {
                        if (!window.putString(field, mPos, i)) {
                            window.freeLastRow();
                            break;
                        }
                    } else {
                        if (!window.putNull(mPos, i)) {
                            window.freeLastRow();
                            break;
                        }
                    }
                }
            }
            
            mPos = oldpos;
        } catch (IllegalStateException e){
            // simply ignore it
        } finally {
            window.releaseReference();
        }
    }

    public final boolean move(int offset) {
        return moveToPosition(mPos + offset);
    }

    public final boolean moveToFirst() {
        return moveToPosition(0);
    }

    public final boolean moveToLast() {
        return moveToPosition(getCount() - 1);
    }

    public final boolean moveToNext() {
        return moveToPosition(mPos + 1);
    }

    public final boolean moveToPrevious() {
        return moveToPosition(mPos - 1);
    }

    public final boolean isFirst() {
        return mPos == 0 && getCount() != 0;
    }

    public final boolean isLast() {
        int cnt = getCount();
        return mPos == (cnt - 1) && cnt != 0;
    }

    public final boolean isBeforeFirst() {
        if (getCount() == 0) {
            return true;
        }
        return mPos == -1;
    }

    public final boolean isAfterLast() {
        if (getCount() == 0) {
            return true;
        }
        return mPos == getCount();
    }

    public int getColumnIndex(String columnName) {
        // Hack according to bug 903852
        final int periodIndex = columnName.lastIndexOf('.');
        if (periodIndex != -1) {
            Exception e = new Exception();
            Log.e(TAG, "requesting column name with table name -- " + columnName, e);
            columnName = columnName.substring(periodIndex + 1);
        }

        String columnNames[] = getColumnNames();
        int length = columnNames.length;
        for (int i = 0; i < length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnName)) {
                return i;
            }
        }

        if (Config.LOGV) {
            if (getCount() > 0) {
                Log.w("AbstractCursor", "Unknown column " + columnName);
            }
        }
        return -1;
    }

    public int getColumnIndexOrThrow(String columnName) {
        final int index = getColumnIndex(columnName);
        if (index < 0) {
            throw new IllegalArgumentException("column '" + columnName + "' does not exist");
        }
        return index;
    }

    public String getColumnName(int columnIndex) {
        return getColumnNames()[columnIndex];
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateBlob(int columnIndex, byte[] value) {
        return update(columnIndex, value);
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateString(int columnIndex, String value) {
        return update(columnIndex, value);
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateShort(int columnIndex, short value) {
        return update(columnIndex, Short.valueOf(value));
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateInt(int columnIndex, int value) {
        return update(columnIndex, Integer.valueOf(value));
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateLong(int columnIndex, long value) {
        return update(columnIndex, Long.valueOf(value));
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateFloat(int columnIndex, float value) {
        return update(columnIndex, Float.valueOf(value));
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateDouble(int columnIndex, double value) {
        return update(columnIndex, Double.valueOf(value));
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean updateToNull(int columnIndex) {
        return update(columnIndex, null);
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean update(int columnIndex, Object obj) {
        if (!supportsUpdates()) {
            return false;
        }

        // Long.valueOf() returns null sometimes!
//        Long rowid = Long.valueOf(getLong(mRowIdColumnIndex));
        Long rowid = new Long(getLong(mRowIdColumnIndex));
        if (rowid == null) {
            throw new IllegalStateException("null rowid. mRowIdColumnIndex = " + mRowIdColumnIndex);
        }

        synchronized(mUpdatedRows) {
            Map<String, Object> row = mUpdatedRows.get(rowid);
            if (row == null) {
                row = new HashMap<String, Object>();
                mUpdatedRows.put(rowid, row);
            }
            row.put(getColumnNames()[columnIndex], obj);
        }

        return true;
    }

    /**
     * Returns <code>true</code> if there are pending updates that have not yet been committed.
     * 
     * @return <code>true</code> if there are pending updates that have not yet been committed.
     * @hide
     * @deprecated
     */
    public boolean hasUpdates() {
        synchronized(mUpdatedRows) {
            return mUpdatedRows.size() > 0;
        }
    }

    /**
     * @hide
     * @deprecated
     */
    public void abortUpdates() {
        synchronized(mUpdatedRows) {
            mUpdatedRows.clear();
        }
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean commitUpdates() {
        return commitUpdates(null);
    }

    /**
     * @hide
     * @deprecated
     */
    public boolean supportsUpdates() {
        return mRowIdColumnIndex != -1;
    }

    public void registerContentObserver(ContentObserver observer) {
        mContentObservable.registerObserver(observer);
    }

    public void unregisterContentObserver(ContentObserver observer) {
        // cursor will unregister all observers when it close
        if (!mClosed) {
            mContentObservable.unregisterObserver(observer);
        }
    }
    
    /**
     * This is hidden until the data set change model has been re-evaluated.
     * @hide
     */
    protected void notifyDataSetChange() {
        mDataSetObservable.notifyChanged();
    }
    
    /**
     * This is hidden until the data set change model has been re-evaluated.
     * @hide
     */
    protected DataSetObservable getDataSetObservable() {
        return mDataSetObservable;
        
    }
    public void registerDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.registerObserver(observer);
        
    }

    public void unregisterDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.unregisterObserver(observer);
    }

    /**
     * Subclasses must call this method when they finish committing updates to notify all
     * observers.
     *
     * @param selfChange
     */
    protected void onChange(boolean selfChange) {
        synchronized (mSelfObserverLock) {
            mContentObservable.dispatchChange(selfChange);
            if (mNotifyUri != null && selfChange) {
                mContentResolver.notifyChange(mNotifyUri, mSelfObserver);
            }
        }
    }

    /**
     * Specifies a content URI to watch for changes.
     *
     * @param cr The content resolver from the caller's context.
     * @param notifyUri The URI to watch for changes. This can be a
     * specific row URI, or a base URI for a whole class of content.
     */
    public void setNotificationUri(ContentResolver cr, Uri notifyUri) {
        synchronized (mSelfObserverLock) {
            mNotifyUri = notifyUri;
            mContentResolver = cr;
            if (mSelfObserver != null) {
                mContentResolver.unregisterContentObserver(mSelfObserver);
            }
            mSelfObserver = new SelfContentObserver(this);
            mContentResolver.registerContentObserver(mNotifyUri, true, mSelfObserver);
            mSelfObserverRegistered = true;
        }
    }

    public boolean getWantsAllOnMoveCalls() {
        return false;
    }

    public Bundle getExtras() {
        return Bundle.EMPTY;
    }

    public Bundle respond(Bundle extras) {
        return Bundle.EMPTY;
    }

    /**
     * This function returns true if the field has been updated and is
     * used in conjunction with {@link #getUpdatedField} to allow subclasses to
     * support reading uncommitted updates. NOTE: This function and
     * {@link #getUpdatedField} should be called together inside of a
     * block synchronized on mUpdatedRows.
     *
     * @param columnIndex the column index of the field to check
     * @return true if the field has been updated, false otherwise
     */
    protected boolean isFieldUpdated(int columnIndex) {
        if (mRowIdColumnIndex != -1 && mUpdatedRows.size() > 0) {
            Map<String, Object> updates = mUpdatedRows.get(mCurrentRowID);
            if (updates != null && updates.containsKey(getColumnNames()[columnIndex])) {
                return true;
            }
        }
        return false;
    }

    /**
     * This function returns the uncommitted updated value for the field
     * at columnIndex.  NOTE: This function and {@link #isFieldUpdated} should
     * be called together inside of a block synchronized on mUpdatedRows.
     *
     * @param columnIndex the column index of the field to retrieve
     * @return the updated value
     */
    protected Object getUpdatedField(int columnIndex) {
        Map<String, Object> updates = mUpdatedRows.get(mCurrentRowID);
        return updates.get(getColumnNames()[columnIndex]);
    }

    /**
     * This function throws CursorIndexOutOfBoundsException if
     * the cursor position is out of bounds. Subclass implementations of
     * the get functions should call this before attempting
     * to retrieve data.
     *
     * @throws CursorIndexOutOfBoundsException
     */
    protected void checkPosition() {
        if (-1 == mPos || getCount() == mPos) {
            throw new CursorIndexOutOfBoundsException(mPos, getCount());
        }
    }

    @Override
    protected void finalize() {
        if (mSelfObserver != null && mSelfObserverRegistered == true) {
            mContentResolver.unregisterContentObserver(mSelfObserver);
        }
    }

    /**
     * Cursors use this class to track changes others make to their URI.
     */
    protected static class SelfContentObserver extends ContentObserver {
        WeakReference<AbstractCursor> mCursor;

        public SelfContentObserver(AbstractCursor cursor) {
            super(null);
            mCursor = new WeakReference<AbstractCursor>(cursor);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return false;
        }

        @Override
        public void onChange(boolean selfChange) {
            AbstractCursor cursor = mCursor.get();
            if (cursor != null) {
                cursor.onChange(false);
            }
        }
    }

    /**
     * This HashMap contains a mapping from Long rowIDs to another Map
     * that maps from String column names to new values. A NULL value means to
     * remove an existing value, and all numeric values are in their class
     * forms, i.e. Integer, Long, Float, etc.
     */
    protected HashMap<Long, Map<String, Object>> mUpdatedRows;

    /**
     * This must be set to the index of the row ID column by any
     * subclass that wishes to support updates.
     */
    protected int mRowIdColumnIndex;

    protected int mPos;
    protected Long mCurrentRowID;
    protected ContentResolver mContentResolver;
    protected boolean mClosed = false;
    private Uri mNotifyUri;
    private ContentObserver mSelfObserver;
    final private Object mSelfObserverLock = new Object();
    private boolean mSelfObserverRegistered;
}
