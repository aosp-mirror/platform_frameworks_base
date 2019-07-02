/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.RedactingCursor;
import android.net.Uri;
import android.util.ArrayMap;

public class RedactingProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        switch (uri.getLastPathSegment()) {
            case "missing": {
                final MatrixCursor cursor = new MatrixCursor(
                        new String[] { "name", "size", "_data" });
                cursor.addRow(new Object[] { "foo", 10, "/path/to/foo" });
                cursor.addRow(new Object[] { "bar", 20, "/path/to/bar" });

                final ArrayMap<String, Object> redactions = new ArrayMap<>();
                redactions.put("missing", null);
                return RedactingCursor.create(cursor, redactions);
            }
            case "single": {
                final MatrixCursor cursor = new MatrixCursor(
                        new String[] { "name", "size", "_data" });
                cursor.addRow(new Object[] { "foo", 10, "/path/to/foo" });
                cursor.addRow(new Object[] { "bar", 20, "/path/to/bar" });

                final ArrayMap<String, Object> redactions = new ArrayMap<>();
                redactions.put("name", null);
                redactions.put("_data", "/dev/null");
                return RedactingCursor.create(cursor, redactions);
            }
            case "multiple": {
                final MatrixCursor cursor = new MatrixCursor(
                        new String[] { "_data", "name", "_data" });
                cursor.addRow(new Object[] { "/path", "foo", "/path" });

                final ArrayMap<String, Object> redactions = new ArrayMap<>();
                redactions.put("_data", null);
                return RedactingCursor.create(cursor, redactions);
            }
        }

        throw new UnsupportedOperationException();
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
        throw new UnsupportedOperationException();
    }
}
