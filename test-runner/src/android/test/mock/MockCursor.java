/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.test.mock;

import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * <P>
 * A mock {@link android.database.Cursor} class that isolates the test code from real
 * Cursor implementation.
 * </P>
 * <P>
 * All methods including ones related to querying the state of the cursor are
 * are non-functional and throw {@link java.lang.UnsupportedOperationException}.
 * </P>
 */
public class MockCursor implements Cursor {
    public int getColumnCount() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getColumnIndex(String columnName) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getColumnIndexOrThrow(String columnName) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public String getColumnName(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public String[] getColumnNames() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getCount() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isNull(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getInt(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public long getLong(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public short getShort(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public float getFloat(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public double getDouble(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public byte[] getBlob(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public String getString(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public Bundle getExtras() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getPosition() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isAfterLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isBeforeFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean move(int offset) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean moveToFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean moveToLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean moveToNext() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean moveToPrevious() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean moveToPosition(int position) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public void deactivate() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public void close() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean isClosed() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean requery() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public void registerContentObserver(ContentObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public void registerDataSetObserver(DataSetObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public Bundle respond(Bundle extras) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public boolean getWantsAllOnMoveCalls() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("deprecation")
    public void setNotificationUri(ContentResolver cr, Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("deprecation")
    public void unregisterContentObserver(ContentObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("deprecation")
    public void unregisterDataSetObserver(DataSetObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int getType(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }
}