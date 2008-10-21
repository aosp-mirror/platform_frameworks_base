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

package android.content;

import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;

import java.util.HashMap;
import java.util.Map;
import java.util.Observable;

/**
 * Caches the contents of a cursor into a Map of String->ContentValues and optionally
 * keeps the cache fresh by registering for updates on the content backing the cursor. The column of
 * the database that is to be used as the key of the map is user-configurable, and the
 * ContentValues contains all columns other than the one that is designated the key.
 * <p>
 * The cursor data is accessed by row key and column name via getValue().
 */
public class ContentQueryMap extends Observable {
    private Cursor mCursor;
    private String[] mColumnNames;
    private int mKeyColumn;

    private Handler mHandlerForUpdateNotifications = null;
    private boolean mKeepUpdated = false;

    private Map<String, ContentValues> mValues = null;

    private ContentObserver mContentObserver;

    /** Set when a cursor change notification is received and is cleared on a call to requery(). */
    private boolean mDirty = false;

    /**
     * Creates a ContentQueryMap that caches the content backing the cursor
     *
     * @param cursor the cursor whose contents should be cached
     * @param columnNameOfKey the column that is to be used as the key of the values map
     * @param keepUpdated true if the cursor's ContentProvider should be monitored for changes and 
     * the map updated when changes do occur
     * @param handlerForUpdateNotifications the Handler that should be used to receive
     *  notifications of changes (if requested). Normally you pass null here, but if
     *  you know that the thread that is creating this isn't a thread that can receive
     *  messages then you can create your own handler and use that here.
     */
    public ContentQueryMap(Cursor cursor, String columnNameOfKey, boolean keepUpdated,
            Handler handlerForUpdateNotifications) {
        mCursor = cursor;
        mColumnNames = mCursor.getColumnNames();
        mKeyColumn = mCursor.getColumnIndexOrThrow(columnNameOfKey);
        mHandlerForUpdateNotifications = handlerForUpdateNotifications;
        setKeepUpdated(keepUpdated);

        // If we aren't keeping the cache updated with the current state of the cursor's 
        // ContentProvider then read it once into the cache. Otherwise the cache will be filled 
        // automatically.
        if (!keepUpdated) {
            readCursorIntoCache();
        }
    }

    /**
     * Change whether or not the ContentQueryMap will register with the cursor's ContentProvider 
     * for change notifications. If you use a ContentQueryMap in an activity you should call this
     * with false in onPause(), which means you need to call it with true in onResume()
     * if want it to be kept updated.
     * @param keepUpdated if true the ContentQueryMap should be registered with the cursor's
     * ContentProvider, false otherwise
     */
    public void setKeepUpdated(boolean keepUpdated) {
        if (keepUpdated == mKeepUpdated) return;
        mKeepUpdated = keepUpdated;

        if (!mKeepUpdated) {
            mCursor.unregisterContentObserver(mContentObserver);
            mContentObserver = null;
        } else {
            if (mHandlerForUpdateNotifications == null) {
                mHandlerForUpdateNotifications = new Handler();
            }
            if (mContentObserver == null) {
                mContentObserver = new ContentObserver(mHandlerForUpdateNotifications) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // If anyone is listening, we need to do this now to broadcast
                        // to the observers.  Otherwise, we'll just set mDirty and
                        // let it query lazily when they ask for the values.
                        if (countObservers() != 0) {
                            requery();
                        } else {
                            mDirty = true;
                        }
                    }
                };
            }
            mCursor.registerContentObserver(mContentObserver);
            // mark dirty, since it is possible the cursor's backing data had changed before we 
            // registered for changes
            mDirty = true;
        }
    }

    /**
     * Access the ContentValues for the row specified by rowName
     * @param rowName which row to read
     * @return the ContentValues for the row, or null if the row wasn't present in the cursor
     */
    public synchronized ContentValues getValues(String rowName) {
        if (mDirty) requery();
        return mValues.get(rowName);
    }

    /** Requeries the cursor and reads the contents into the cache */
    public void requery() {
        mDirty = false;
        mCursor.requery();
        readCursorIntoCache();
        setChanged();
        notifyObservers();
    }

    private synchronized void readCursorIntoCache() {
        // Make a new map so old values returned by getRows() are undisturbed.
        int capacity = mValues != null ? mValues.size() : 0;
        mValues = new HashMap<String, ContentValues>(capacity);
        while (mCursor.moveToNext()) {
            ContentValues values = new ContentValues();
            for (int i = 0; i < mColumnNames.length; i++) {
                if (i != mKeyColumn) {
                    values.put(mColumnNames[i], mCursor.getString(i));
                }
            }
            mValues.put(mCursor.getString(mKeyColumn), values);
        }
    }

    public synchronized Map<String, ContentValues> getRows() {
        if (mDirty) requery();
        return mValues;
    }

    public synchronized void close() {
        if (mContentObserver != null) {
            mCursor.unregisterContentObserver(mContentObserver);
            mContentObserver = null;
        }
        mCursor.close();
        mCursor = null;
    }

    @Override
    protected void finalize() throws Throwable {
        if (mCursor != null) close();
        super.finalize();
    }
}
