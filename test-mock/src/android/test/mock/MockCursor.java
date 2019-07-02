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

import java.util.List;

/**
 * A mock {@link android.database.Cursor} class that isolates the test code from real
 * Cursor implementation.
 *
 * <p>
 * All methods including ones related to querying the state of the cursor are
 * are non-functional and throw {@link java.lang.UnsupportedOperationException}.
 *
 * @deprecated Use a mocking framework like <a href="https://github.com/mockito/mockito">Mockito</a>.
 * New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class MockCursor implements Cursor {
    @Override
    public int getColumnCount() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getColumnIndex(String columnName) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String getColumnName(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String[] getColumnNames() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getCount() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isNull(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getInt(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public long getLong(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public short getShort(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public float getFloat(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public double getDouble(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String getString(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void setExtras(Bundle extras) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Bundle getExtras() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getPosition() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isAfterLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isBeforeFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean move(int offset) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean moveToFirst() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean moveToLast() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean moveToNext() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean moveToPrevious() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean moveToPosition(int position) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @Deprecated
    public void deactivate() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean isClosed() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @Deprecated
    public boolean requery() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void registerContentObserver(ContentObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Bundle respond(Bundle extras) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean getWantsAllOnMoveCalls() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void setNotificationUri(ContentResolver cr, Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void setNotificationUris(ContentResolver cr, List<Uri> uris) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Uri getNotificationUri() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public List<Uri> getNotificationUris() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void unregisterContentObserver(ContentObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int getType(int columnIndex) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }
}