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

package android.app.activity;

import android.content.UriMatcher;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.util.Log;

/** Simple test provider that runs in the local process. */
public class LocalProvider extends ContentProvider {
    private static final String TAG = "LocalProvider";

    private static final String AUTHORITY = "com.android.frameworks.coretests.LocalProvider";
    private static final String TABLE_DATA_NAME = "data";
    public static final Uri TABLE_DATA_URI =
            Uri.parse("content://" + AUTHORITY + "/" + TABLE_DATA_NAME);

    public static final String COLUMN_TEXT_NAME = "text";
    public static final String COLUMN_INTEGER_NAME = "integer";

    public static final String TEXT1 = "first data";
    public static final String TEXT2 = "second data";
    public static final int INTEGER1 = 100;
    public static final int INTEGER2 = 101;

    private SQLiteOpenHelper mOpenHelper;

    private static final int DATA = 1;
    private static final int DATA_ID = 2;
    private static final UriMatcher sURLMatcher = new UriMatcher(
            UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("*", "data", DATA);
        sURLMatcher.addURI("*", "data/#", DATA_ID);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "local.db";
        private static final int DATABASE_VERSION = 1;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_DATA_NAME + " (" +
                       "_id INTEGER PRIMARY KEY," +
                       COLUMN_TEXT_NAME + " TEXT, " +
                       COLUMN_INTEGER_NAME + " INTEGER);");

            // insert alarms
            db.execSQL(getInsertCommand(TEXT1, INTEGER1));
            db.execSQL(getInsertCommand(TEXT2, INTEGER2));
        }

        private String getInsertCommand(String textValue, int integerValue) {
            return "INSERT INTO " + TABLE_DATA_NAME
                    + " (" + COLUMN_TEXT_NAME + ", " + COLUMN_INTEGER_NAME + ") "
                    + "VALUES ('" + textValue + "', " + integerValue + ");";
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


    public LocalProvider() {
    }

    static public Uri getTableDataUriForRow(int rowId) {
        return Uri.parse("content://" + AUTHORITY + "/" + TABLE_DATA_NAME + "/" + rowId);
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        // Generate the body of the query
        int match = sURLMatcher.match(url);
        switch (match) {
            case DATA:
                qb.setTables("data");
                break;
            case DATA_ID:
                qb.setTables("data");
                qb.appendWhere("_id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projectionIn, selection, selectionArgs,
                              null, null, sort);

        if (ret == null) {
            if (false) Log.d(TAG, "Alarms.query: failed");
        } else {
            ret.setNotificationUri(getContext().getContentResolver(), url);
        }

        return ret;
    }

    @Override
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case DATA:
                return "vnd.android.cursor.dir/vnd.google.unit_tests.local";
            case DATA_ID:
                return "vnd.android.cursor.item/vnd.google.unit_tests.local";
            default:
                throw new IllegalArgumentException("Unknown URL");
        }
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int count;
        long rowId = 0;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        switch (match) {
            case DATA_ID: {
                String segment = url.getPathSegments().get(1);
                rowId = Long.parseLong(segment);
                count = db.update("data", values, "_id=" + rowId, null);
                break;
            }
            default: {
                throw new UnsupportedOperationException(
                        "Cannot update URL: " + url);
            }
        }
        if (false) Log.d(TAG, "*** notifyChange() rowId: " + rowId);
        getContext().getContentResolver().notifyChange(url, null);
        return count;
    }


    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        return null;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("delete not supported");
    }
}
