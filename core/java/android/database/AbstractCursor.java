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
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;


/**
 * This is an abstract cursor class that handles a lot of the common code
 * that all cursors need to deal with and is provided for convenience reasons.
 */
public abstract class AbstractCursor implements CrossProcessCursor {
    private static final String TAG = "Cursor";

    /**
     * @removed This field should not be used.
     */
    protected HashMap<Long, Map<String, Object>> mUpdatedRows;

    /**
     * @removed This field should not be used.
     */
    protected int mRowIdColumnIndex;

    /**
     * @removed This field should not be used.
     */
    protected Long mCurrentRowID;

    /**
     * @deprecated Use {@link #getPosition()} instead.
     */
    @Deprecated
    protected int mPos;

    /**
     * @deprecated Use {@link #isClosed()} instead.
     */
    @Deprecated
    protected boolean mClosed;

    /**
     * @deprecated Do not use.
     */
    @Deprecated
    protected ContentResolver mContentResolver;

    private Uri mNotifyUri;

    private final Object mSelfObserverLock = new Object();
    private ContentObserver mSelfObserver;
    private boolean mSelfObserverRegistered;

    private final DataSetObservable mDataSetObservable = new DataSetObservable();
    private final ContentObservable mContentObservable = new ContentObservable();

    private Bundle mExtras = Bundle.EMPTY;

    /* -------------------------------------------------------- */
    /* These need to be implemented by subclasses */
    @Override
    abstract public int getCount();

    @Override
    abstract public String[] getColumnNames();

    @Override
    abstract public String getString(int column);
    @Override
    abstract public short getShort(int column);
    @Override
    abstract public int getInt(int column);
    @Override
    abstract public long getLong(int column);
    @Override
    abstract public float getFloat(int column);
    @Override
    abstract public double getDouble(int column);
    @Override
    abstract public boolean isNull(int column);

    @Override
    public int getType(int column) {
        // Reflects the assumption that all commonly used field types (meaning everything
        // but blobs) are convertible to strings so it should be safe to call
        // getString to retrieve them.
        return FIELD_TYPE_STRING;
    }

    // TODO implement getBlob in all cursor types
    @Override
    public byte[] getBlob(int column) {
        throw new UnsupportedOperationException("getBlob is not supported");
    }
    /* -------------------------------------------------------- */
    /* Methods that may optionally be implemented by subclasses */

    /**
     * If the cursor is backed by a {@link CursorWindow}, returns a pre-filled
     * window with the contents of the cursor, otherwise null.
     *
     * @return The pre-filled window that backs this cursor, or null if none.
     */
    @Override
    public CursorWindow getWindow() {
        return null;
    }

    @Override
    public int getColumnCount() {
        return getColumnNames().length;
    }

    @Override
    public void deactivate() {
        onDeactivateOrClose();
    }

    /** @hide */
    protected void onDeactivateOrClose() {
        if (mSelfObserver != null) {
            mContentResolver.unregisterContentObserver(mSelfObserver);
            mSelfObserverRegistered = false;
        }
        mDataSetObservable.notifyInvalidated();
    }

    @Override
    public boolean requery() {
        if (mSelfObserver != null && mSelfObserverRegistered == false) {
            mContentResolver.registerContentObserver(mNotifyUri, true, mSelfObserver);
            mSelfObserverRegistered = true;
        }
        mDataSetObservable.notifyChanged();
        return true;
    }

    @Override
    public boolean isClosed() {
        return mClosed;
    }

    @Override
    public void close() {
        mClosed = true;
        mContentObservable.unregisterAll();
        onDeactivateOrClose();
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
    @Override
    public boolean onMove(int oldPosition, int newPosition) {
        return true;
    }


    @Override
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
        } else {
            buffer.sizeCopied = 0;
        }
    }

    /* -------------------------------------------------------- */
    /* Implementation */
    public AbstractCursor() {
        mPos = -1;
    }

    @Override
    public final int getPosition() {
        return mPos;
    }

    @Override
    public final boolean moveToPosition(int position) {
        final int moved = onMoveWithBoundsCheck(position);
        switch (moved) {
            case MOVE_OK:
                mPos = position;
                return true;
            case MOVE_AFTER_LAST:
                mPos = getCount();
                return false;
            case MOVE_BEFORE_FIRST:
                mPos = -1;
                return false;
            case MOVE_NOP:
                return true;
            case MOVE_FAILED:
                mPos = -1;
                return false;
            default:
                throw new IllegalStateException("Illegal onMoveWithBoundsCheck return: " + moved);
        }
    }

    /** @hide */
    protected static final int MOVE_OK           = 0;
    /** @hide */
    protected static final int MOVE_AFTER_LAST   = 1;
    /** @hide */
    protected static final int MOVE_BEFORE_FIRST = 2;
    /** @hide */
    protected static final int MOVE_NOP          = 3;
    /** @hide */
    protected static final int MOVE_FAILED       = 4;

    /**
     * Subclasses may override this instead of onMove() to do their own bounds checking. This could
     * be useful in the case where checking bounds can be more efficiently done another way. This is
     * the method that calls onMove(), so overriding implementations should also do that, for the
     * sake of potential subclasses.
     *
     * @param position the position we're trying to move to
     * @return In order of precedence (highest first):
     *         MOVE_AFTER_LAST if the new position is equal to count,
     *         MOVE_BEFORE_FIRST if the new position is -1,
     *         MOVE_NOP if the new position was equal to the old position,
     *         MOVE_FAILED if the move failed (equivalent to onMove() returning false)
     *         MOVE_OK if the move suceeded
     *
     * @hide
     */
    protected int onMoveWithBoundsCheck(int position) {
        // Make sure position isn't past the end of the cursor
        if (isAfterLast(position)) {
            return MOVE_AFTER_LAST;
        }

        // Make sure position isn't before the beginning of the cursor
        if (position < 0) {
            return MOVE_BEFORE_FIRST;
        }

        // Check for no-op moves, and skip the rest of the work for them
        if (position == mPos) {
            return MOVE_NOP;
        }

        boolean result = onMove(mPos, position);
        if (result == false) {
            return MOVE_FAILED;
        }

        return MOVE_OK;
    }

    @Override
    public void fillWindow(int position, CursorWindow window) {
        DatabaseUtils.cursorFillWindow(this, position, window);
    }

    @Override
    public final boolean move(int offset) {
        return moveToPosition(mPos + offset);
    }

    @Override
    public final boolean moveToFirst() {
        return moveToPosition(0);
    }

    @Override
    public final boolean moveToLast() {
        return moveToPosition(getCount() - 1);
    }

    @Override
    public final boolean moveToNext() {
        return moveToPosition(mPos + 1);
    }

    @Override
    public final boolean moveToPrevious() {
        return moveToPosition(mPos - 1);
    }

    @Override
    public final boolean isFirst() {
        return mPos == 0 && !isAfterLast(0);
    }

    @Override
    public final boolean isLast() {
        return !isAfterLast(mPos) && isAfterLast(mPos+1);
    }

    @Override
    public final boolean isBeforeFirst() {
        if (isAfterLast(0)) {
            return true;
        }
        return mPos == -1;
    }

    @Override
    public final boolean isAfterLast() {
        return isAfterLast(Math.max(0, mPos));
    }

    /**
     * The default implementation uses getCount(). If subclasses would prefer deferring the full
     * counting of their result set for performance reasons, they should override this method.
     * This would eliminate <i>most</i> of AbstractCursor's getCount() calls.
     * @hide
     */
    protected boolean isAfterLast(int position) {
        return position >= getCount();
    }

    @Override
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

        if (false) {
            if (getCount() > 0) {
                Log.w("AbstractCursor", "Unknown column " + columnName);
            }
        }
        return -1;
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) {
        final int index = getColumnIndex(columnName);
        if (index < 0) {
            throw new IllegalArgumentException("column '" + columnName + "' does not exist");
        }
        return index;
    }

    @Override
    public String getColumnName(int columnIndex) {
        return getColumnNames()[columnIndex];
    }

    @Override
    public void registerContentObserver(ContentObserver observer) {
        mContentObservable.registerObserver(observer);
    }

    @Override
    public void unregisterContentObserver(ContentObserver observer) {
        // cursor will unregister all observers when it close
        if (!mClosed) {
            mContentObservable.unregisterObserver(observer);
        }
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.registerObserver(observer);
    }

    @Override
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
            mContentObservable.dispatchChange(selfChange, null);
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
    @Override
    public void setNotificationUri(ContentResolver cr, Uri notifyUri) {
        setNotificationUri(cr, notifyUri, UserHandle.myUserId());
    }

    /** @hide - set the notification uri but with an observer for a particular user's view */
    public void setNotificationUri(ContentResolver cr, Uri notifyUri, int userHandle) {
        synchronized (mSelfObserverLock) {
            mNotifyUri = notifyUri;
            mContentResolver = cr;
            if (mSelfObserver != null) {
                mContentResolver.unregisterContentObserver(mSelfObserver);
            }
            mSelfObserver = new SelfContentObserver(this);
            mContentResolver.registerContentObserver(mNotifyUri, true, mSelfObserver, userHandle);
            mSelfObserverRegistered = true;
        }
    }

    @Override
    public Uri getNotificationUri() {
        synchronized (mSelfObserverLock) {
            return mNotifyUri;
        }
    }

    @Override
    public boolean getWantsAllOnMoveCalls() {
        return false;
    }

    @Override
    public void setExtras(Bundle extras) {
        mExtras = (extras == null) ? Bundle.EMPTY : extras;
    }

    @Override
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public Bundle respond(Bundle extras) {
        return Bundle.EMPTY;
    }

    /**
     * @deprecated Always returns false since Cursors do not support updating rows
     */
    @Deprecated
    protected boolean isFieldUpdated(int columnIndex) {
        return false;
    }

    /**
     * @deprecated Always returns null since Cursors do not support updating rows
     */
    @Deprecated
    protected Object getUpdatedField(int columnIndex) {
        return null;
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
        if (-1 == mPos || isAfterLast(mPos)) {
            throw new CursorIndexOutOfBoundsException(mPos, getCount());
        }
    }

    @Override
    protected void finalize() {
        if (mSelfObserver != null && mSelfObserverRegistered == true) {
            mContentResolver.unregisterContentObserver(mSelfObserver);
        }
        try {
            if (!mClosed) close();
        } catch(Exception e) { }
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
}
