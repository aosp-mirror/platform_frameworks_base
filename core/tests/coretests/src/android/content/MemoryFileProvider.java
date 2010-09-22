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

package android.content;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/** Simple test provider that runs in the local process. */
public class MemoryFileProvider extends ContentProvider {
    private static final String TAG = "MemoryFileProvider";

    private static final String DATA_FILE = "data.bin";

    // some random data
    public static final byte[] TEST_BLOB = new byte[] {
        -12,  127, 0, 3, 1, 2, 3, 4, 5, 6, 1, -128, -1, -54, -65, 35,
        -53, -96, -74, -74, -55, -43, -69, 3, 52, -58,
        -121, 127, 87, -73, 16, -13, -103, -65, -128, -36,
        107, 24, 118, -17, 97, 97, -88, 19, -94, -54,
        53, 43, 44, -27, -124, 28, -74, 26, 35, -36,
        16, -124, -31, -31, -128, -79, 108, 116, 43, -17 };

    private SQLiteOpenHelper mOpenHelper;

    private static final int DATA_ID_BLOB = 1;
    private static final int HUGE = 2;
    private static final int FILE = 3;

    private static final UriMatcher sURLMatcher = new UriMatcher(
            UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("*", "data/#/blob", DATA_ID_BLOB);
        sURLMatcher.addURI("*", "huge", HUGE);
        sURLMatcher.addURI("*", "file", FILE);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "local.db";
        private static final int DATABASE_VERSION = 1;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE data (" +
                       "_id INTEGER PRIMARY KEY," +
                       "_blob TEXT, " +
                       "integer INTEGER);");

            // insert alarms
            ContentValues values = new ContentValues();
            values.put("_id", 1);
            values.put("_blob", TEST_BLOB);
            values.put("integer", 100);
            db.insert("data", null, values);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            Log.w(TAG, "Upgrading test database from version " +
                  oldVersion + " to " + currentVersion +
                  ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS data");
            onCreate(db);
        }
    }


    public MemoryFileProvider() {
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        try {
            OutputStream out = getContext().openFileOutput(DATA_FILE, Context.MODE_PRIVATE);
            out.write(TEST_BLOB);
            out.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        throw new UnsupportedOperationException("query not supported");
    }

    @Override
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case DATA_ID_BLOB:
                return "application/octet-stream";
            case FILE:
                return "application/octet-stream";
            default:
                throw new IllegalArgumentException("Unknown URL");
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri url, String mode) throws FileNotFoundException {
        int match = sURLMatcher.match(url);
        switch (match) {
            case DATA_ID_BLOB:
                String sql = "SELECT _blob FROM data WHERE _id=" + url.getPathSegments().get(1);
                return getBlobColumnAsFile(url, mode, sql);
            case HUGE:
                try {
                    return ParcelFileDescriptor.fromData(TEST_BLOB, null);
                } catch (IOException ex) {
                    throw new FileNotFoundException("Error reading " + url + ":" + ex.toString());
                }
            case FILE:
                File file = getContext().getFileStreamPath(DATA_FILE);
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            default:
                throw new FileNotFoundException("No files supported by provider at " + url);
        }
    }

    private ParcelFileDescriptor getBlobColumnAsFile(Uri url, String mode, String sql)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Mode " + mode + " not supported for " + url);
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        return DatabaseUtils.blobFileDescriptorForQuery(db, sql, null);
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("update not supported");
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        throw new UnsupportedOperationException("insert not supported");
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("delete not supported");
    }
}
