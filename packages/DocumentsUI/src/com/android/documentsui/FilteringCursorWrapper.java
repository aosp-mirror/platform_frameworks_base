/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.TAG;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.database.AbstractCursor;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.DocumentsContract.Document;
import android.util.Log;

/**
 * Cursor wrapper that filters MIME types not matching given list.
 */
public class FilteringCursorWrapper extends AbstractCursor {
    private final Cursor mCursor;

    private final int[] mPosition;
    private int mCount;

    public FilteringCursorWrapper(Cursor cursor, String[] acceptMimes) {
        this(cursor, acceptMimes, null, Long.MIN_VALUE);
    }

    public FilteringCursorWrapper(Cursor cursor, String[] acceptMimes, String[] rejectMimes) {
        this(cursor, acceptMimes, rejectMimes, Long.MIN_VALUE);
    }

    public FilteringCursorWrapper(
            Cursor cursor, String[] acceptMimes, String[] rejectMimes, long rejectBefore) {
        mCursor = cursor;

        final int count = cursor.getCount();
        mPosition = new int[count];

        cursor.moveToPosition(-1);
        while (cursor.moveToNext() && mCount < count) {
            final String mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            final long lastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
            if (rejectMimes != null && MimePredicate.mimeMatches(rejectMimes, mimeType)) {
                continue;
            }
            if (lastModified < rejectBefore) {
                continue;
            }
            if (MimePredicate.mimeMatches(acceptMimes, mimeType)) {
                mPosition[mCount++] = cursor.getPosition();
            }
        }

        if (DEBUG && mCount != cursor.getCount()) {
            Log.d(TAG, "Before filtering " + cursor.getCount() + ", after " + mCount);
        }
    }

    @Override
    public Bundle getExtras() {
        return mCursor.getExtras();
    }

    @Override
    public void close() {
        super.close();
        mCursor.close();
    }

    @Override
    public boolean onMove(int oldPosition, int newPosition) {
        return mCursor.moveToPosition(mPosition[newPosition]);
    }

    @Override
    public String[] getColumnNames() {
        return mCursor.getColumnNames();
    }

    @Override
    public int getCount() {
        return mCount;
    }

    @Override
    public double getDouble(int column) {
        return mCursor.getDouble(column);
    }

    @Override
    public float getFloat(int column) {
        return mCursor.getFloat(column);
    }

    @Override
    public int getInt(int column) {
        return mCursor.getInt(column);
    }

    @Override
    public long getLong(int column) {
        return mCursor.getLong(column);
    }

    @Override
    public short getShort(int column) {
        return mCursor.getShort(column);
    }

    @Override
    public String getString(int column) {
        return mCursor.getString(column);
    }

    @Override
    public int getType(int column) {
        return mCursor.getType(column);
    }

    @Override
    public boolean isNull(int column) {
        return mCursor.isNull(column);
    }
}
