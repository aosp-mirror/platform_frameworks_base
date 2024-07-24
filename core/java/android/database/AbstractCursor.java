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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This is an abstract cursor class that handles a lot of the common code
 * that all cursors need to deal with and is provided for convenience reasons.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
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

    @UnsupportedAppUsage
    private Uri mNotifyUri;
    private List<Uri> mNotifyUris;

    private final Object mSelfObserverLock = new Object();
    private ContentObserver mSelfObserver;
    private boolean mSelfObserverRegistered;

    private final DataSetObservable mDataSetObservable = new DataSetObservable();
    private final ContentObservable mContentObservable = new ContentObservable();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Bundle mExtras = Bundle.EMPTY;

    /** CloseGuard to detect leaked cursor **/
    private final CloseGuard mCloseGuard;

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
            final int size = mNotifyUris.size();
            for (int i = 0; i < size; ++i) {
                final Uri notifyUri = mNotifyUris.get(i);
                mContentResolver.registerContentObserver(notifyUri, true, mSelfObserver);
            }
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
        if (mCloseGuard != null) {
            mCloseGuard.close();
        }
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
        mCloseGuard = initCloseGuard();
        if (mCloseGuard != null) {
            mCloseGuard.open("AbstractCursor.close");
        }
    }

    @android.ravenwood.annotation.RavenwoodReplace
    private CloseGuard initCloseGuard() {
        return CloseGuard.get();
    }

    private CloseGuard initCloseGuard$ravenwood() {
        return null;
    }

    @Override
    public final int getPosition() {
        return mPos;
    }

    @Override
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
        }

        return result;
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
        return mPos == 0 && getCount() != 0;
    }

    @Override
    public final boolean isLast() {
        int cnt = getCount();
        return mPos == (cnt - 1) && cnt != 0;
    }

    @Override
    public final boolean isBeforeFirst() {
        if (getCount() == 0) {
            return true;
        }
        return mPos == -1;
    }

    @Override
    public final boolean isAfterLast() {
        if (getCount() == 0) {
            return true;
        }
        return mPos == getCount();
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
            String availableColumns = "";
            try {
                availableColumns = Arrays.toString(getColumnNames());
            } catch (Exception e) {
                Log.d(TAG, "Cannot collect column names for debug purposes", e);
            }
            throw new IllegalArgumentException("column '" + columnName
                    + "' does not exist. Available columns: " + availableColumns);
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
            if (mNotifyUris != null && selfChange) {
                final int size = mNotifyUris.size();
                for (int i = 0; i < size; ++i) {
                    final Uri notifyUri = mNotifyUris.get(i);
                    mContentResolver.notifyChange(notifyUri, mSelfObserver);
                }
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
        setNotificationUris(cr, Arrays.asList(notifyUri));
    }

    @Override
    public void setNotificationUris(@NonNull ContentResolver cr, @NonNull List<Uri> notifyUris) {
        Objects.requireNonNull(cr);
        Objects.requireNonNull(notifyUris);

        setNotificationUris(cr, notifyUris, cr.getUserId(), true);
    }

    /**
     * Set the notification uri but with an observer for a particular user's view. Also allows
     * disabling the use of a self observer, which is sensible if either
     * a) the cursor's owner calls {@link #onChange(boolean)} whenever the content changes, or
     * b) the cursor is known not to have any content observers.
     * @hide
     */
    public void setNotificationUris(ContentResolver cr, List<Uri> notifyUris, int userHandle,
            boolean registerSelfObserver) {
        synchronized (mSelfObserverLock) {
            mNotifyUris = notifyUris;
            mNotifyUri = mNotifyUris.get(0);
            mContentResolver = cr;
            if (mSelfObserver != null) {
                mContentResolver.unregisterContentObserver(mSelfObserver);
                mSelfObserverRegistered = false;
            }
            if (registerSelfObserver) {
                mSelfObserver = new SelfContentObserver(this);
                final int size = mNotifyUris.size();
                for (int i = 0; i < size; ++i) {
                    final Uri notifyUri = mNotifyUris.get(i);
                    mContentResolver.registerContentObserver(
                            notifyUri, true, mSelfObserver, userHandle);
                }
                mSelfObserverRegistered = true;
            }
        }
    }

    @Override
    public Uri getNotificationUri() {
        synchronized (mSelfObserverLock) {
            return mNotifyUri;
        }
    }

    @Override
    public List<Uri> getNotificationUris() {
        synchronized (mSelfObserverLock) {
            return mNotifyUris;
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
        if (-1 == mPos || getCount() == mPos) {
            throw new CursorIndexOutOfBoundsException(mPos, getCount());
        }
    }

    @Override
    protected void finalize() {
        if (mSelfObserver != null && mSelfObserverRegistered == true) {
            mContentResolver.unregisterContentObserver(mSelfObserver);
        }
        try {
            if (mCloseGuard != null) mCloseGuard.warnIfOpen();
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
