/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Arrays;

public class SomeProvider extends ContentProvider {
    private Cursor mCursor;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return mCursor;
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        final char[] valueRaw = new char[512];
        Arrays.fill(valueRaw, '!');
        final String value = new String(valueRaw);

        final int count = values.getAsInteger(Intent.EXTRA_INDEX);
        final MatrixCursor cursor = new MatrixCursor(new String[] { "_id", "value" });
        for (int i = 0; i < count; i++) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            row.add(0, i);
            row.add(1, value);
        }
        mCursor = cursor;
        return 1;
    }
}
