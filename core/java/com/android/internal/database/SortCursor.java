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

package com.android.internal.database;

import android.annotation.UnsupportedAppUsage;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.util.Log;

/**
 * A variant of MergeCursor that sorts the cursors being merged. If decent
 * performance is ever obtained, it can be put back under android.database.
 */
public class SortCursor extends AbstractCursor
{
    private static final String TAG = "SortCursor";
    @UnsupportedAppUsage
    private Cursor mCursor; // updated in onMove
    @UnsupportedAppUsage
    private Cursor[] mCursors;
    private int [] mSortColumns;
    private final int ROWCACHESIZE = 64;
    private int mRowNumCache[] = new int[ROWCACHESIZE];
    private int mCursorCache[] = new int[ROWCACHESIZE];
    private int mCurRowNumCache[][];
    private int mLastCacheHit = -1;

    private DataSetObserver mObserver = new DataSetObserver() {

        @Override
        public void onChanged() {
            // Reset our position so the optimizations in move-related code
            // don't screw us over
            mPos = -1;
        }

        @Override
        public void onInvalidated() {
            mPos = -1;
        }
    };
    
    @UnsupportedAppUsage
    public SortCursor(Cursor[] cursors, String sortcolumn)
    {
        mCursors = cursors;

        int length = mCursors.length;
        mSortColumns = new int[length];
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] == null) continue;
            
            // Register ourself as a data set observer
            mCursors[i].registerDataSetObserver(mObserver);
            
            mCursors[i].moveToFirst();

            // We don't catch the exception
            mSortColumns[i] = mCursors[i].getColumnIndexOrThrow(sortcolumn);
        }
        mCursor = null;
        String smallest = "";
        for (int j = 0 ; j < length; j++) {
            if (mCursors[j] == null || mCursors[j].isAfterLast())
                continue;
            String current = mCursors[j].getString(mSortColumns[j]);
            if (mCursor == null || current.compareToIgnoreCase(smallest) < 0) {
                smallest = current;
                mCursor = mCursors[j];
            }
        }

        for (int i = mRowNumCache.length - 1; i >= 0; i--) {
            mRowNumCache[i] = -2;
        }
        mCurRowNumCache = new int[ROWCACHESIZE][length];
    }

    @Override
    public int getCount()
    {
        int count = 0;
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] != null) {
                count += mCursors[i].getCount();
            }
        }
        return count;
    }

    @Override
    public boolean onMove(int oldPosition, int newPosition)
    {
        if (oldPosition == newPosition)
            return true;

        /* Find the right cursor
         * Because the client of this cursor (the listadapter/view) tends
         * to jump around in the cursor somewhat, a simple cache strategy
         * is used to avoid having to search all cursors from the start.
         * TODO: investigate strategies for optimizing random access and
         * reverse-order access.
         */

        int cache_entry = newPosition % ROWCACHESIZE;

        if (mRowNumCache[cache_entry] == newPosition) {
            int which = mCursorCache[cache_entry];
            mCursor = mCursors[which];
            if (mCursor == null) {
                Log.w(TAG, "onMove: cache results in a null cursor.");
                return false;
            }
            mCursor.moveToPosition(mCurRowNumCache[cache_entry][which]);
            mLastCacheHit = cache_entry;
            return true;
        }

        mCursor = null;
        int length = mCursors.length;

        if (mLastCacheHit >= 0) {
            for (int i = 0; i < length; i++) {
                if (mCursors[i] == null) continue;
                mCursors[i].moveToPosition(mCurRowNumCache[mLastCacheHit][i]);
            }
        }

        if (newPosition < oldPosition || oldPosition == -1) {
            for (int i = 0 ; i < length; i++) {
                if (mCursors[i] == null) continue;
                mCursors[i].moveToFirst();
            }
            oldPosition = 0;
        }
        if (oldPosition < 0) {
            oldPosition = 0;
        }

        // search forward to the new position
        int smallestIdx = -1;
        for(int i = oldPosition; i <= newPosition; i++) {
            String smallest = "";
            smallestIdx = -1;
            for (int j = 0 ; j < length; j++) {
                if (mCursors[j] == null || mCursors[j].isAfterLast()) {
                    continue;
                }
                String current = mCursors[j].getString(mSortColumns[j]);
                if (smallestIdx < 0 || current.compareToIgnoreCase(smallest) < 0) {
                    smallest = current;
                    smallestIdx = j;
                }
            }
            if (i == newPosition) break;
            if (mCursors[smallestIdx] != null) {
                mCursors[smallestIdx].moveToNext();
            }
        }
        mCursor = mCursors[smallestIdx];
        mRowNumCache[cache_entry] = newPosition;
        mCursorCache[cache_entry] = smallestIdx;
        for (int i = 0; i < length; i++) {
            if (mCursors[i] != null) {
                mCurRowNumCache[cache_entry][i] = mCursors[i].getPosition();
            }
        }
        mLastCacheHit = -1;
        return true;
    }

    @Override
    public String getString(int column)
    {
        return mCursor.getString(column);
    }

    @Override
    public short getShort(int column)
    {
        return mCursor.getShort(column);
    }

    @Override
    public int getInt(int column)
    {
        return mCursor.getInt(column);
    }

    @Override
    public long getLong(int column)
    {
        return mCursor.getLong(column);
    }

    @Override
    public float getFloat(int column)
    {
        return mCursor.getFloat(column);
    }

    @Override
    public double getDouble(int column)
    {
        return mCursor.getDouble(column);
    }

    @Override
    public int getType(int column) {
        return mCursor.getType(column);
    }

    @Override
    public boolean isNull(int column)
    {
        return mCursor.isNull(column);
    }

    @Override
    public byte[] getBlob(int column)
    {
        return mCursor.getBlob(column);   
    }
    
    @Override
    public String[] getColumnNames()
    {
        if (mCursor != null) {
            return mCursor.getColumnNames();
        } else {
            // All of the cursors may be empty, but they can still return
            // this information.
            int length = mCursors.length;
            for (int i = 0 ; i < length ; i++) {
                if (mCursors[i] != null) {
                    return mCursors[i].getColumnNames();
                }
            }
            throw new IllegalStateException("No cursor that can return names");
        }
    }

    @Override
    public void deactivate()
    {
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] == null) continue;
            mCursors[i].deactivate();
        }
    }

    @Override
    public void close() {
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] == null) continue;
            mCursors[i].close();
        }
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] != null) {
                mCursors[i].registerDataSetObserver(observer);
            }
        }
    }
    
    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] != null) {
                mCursors[i].unregisterDataSetObserver(observer);
            }
        }
    }
    
    @Override
    public boolean requery()
    {
        int length = mCursors.length;
        for (int i = 0 ; i < length ; i++) {
            if (mCursors[i] == null) continue;
            
            if (mCursors[i].requery() == false) {
                return false;
            }
        }

        return true;
    }
}
