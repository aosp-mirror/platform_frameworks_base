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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.text.format.DateUtils;
import android.util.Log;

public class RecentsProvider extends ContentProvider {
    private static final String TAG = "RecentsProvider";

    public static final String AUTHORITY = "com.android.documentsui.recents";

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_RECENT_OPEN = 1;
    private static final int URI_RECENT_CREATE = 2;
    private static final int URI_RESUME = 3;

    static {
        sMatcher.addURI(AUTHORITY, "recent_open", URI_RECENT_OPEN);
        sMatcher.addURI(AUTHORITY, "recent_create", URI_RECENT_CREATE);
        sMatcher.addURI(AUTHORITY, "resume/*", URI_RESUME);
    }

    private static final String TABLE_RECENT_OPEN = "recent_open";
    private static final String TABLE_RECENT_CREATE = "recent_create";
    private static final String TABLE_RESUME = "resume";

    /**
     * String of URIs pointing at a storage backend, stored as a JSON array,
     * starting with root.
     */
    public static final String COL_PATH = "path";
    public static final String COL_PACKAGE_NAME = "package_name";
    public static final String COL_TIMESTAMP = "timestamp";

    private DatabaseHelper mHelper;

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "recents";

        private static final int VERSION_INIT = 1;

        public DatabaseHelper(Context context) {
            super(context, DB_NAME, null, VERSION_INIT);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_RECENT_OPEN + " (" +
                    COL_PATH + " TEXT," +
                    COL_TIMESTAMP + " INTEGER," +
                    ")");

            db.execSQL("CREATE TABLE " + TABLE_RECENT_CREATE + " (" +
                    COL_PATH + " TEXT," +
                    COL_TIMESTAMP + " INTEGER," +
                    ")");

            db.execSQL("CREATE TABLE " + TABLE_RESUME + " (" +
                    COL_PACKAGE_NAME + " TEXT PRIMARY KEY ON CONFLICT REPLACE," +
                    COL_PATH + " TEXT," +
                    COL_TIMESTAMP + " INTEGER," +
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database; wiping app data");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECENT_OPEN);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECENT_CREATE);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RESUME);
            onCreate(db);
        }
    }

    @Override
    public boolean onCreate() {
        mHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final SQLiteDatabase db = mHelper.getReadableDatabase();
        switch (sMatcher.match(uri)) {
            case URI_RECENT_OPEN: {
                return db.query(TABLE_RECENT_OPEN, projection,
                        buildWhereYounger(DateUtils.WEEK_IN_MILLIS), null, null, null, null);
            }
            case URI_RECENT_CREATE: {
                return db.query(TABLE_RECENT_CREATE, projection,
                        buildWhereYounger(DateUtils.WEEK_IN_MILLIS), null, null, null, null);
            }
            case URI_RESUME: {
                final String packageName = uri.getPathSegments().get(1);
                return db.query(TABLE_RESUME, projection, COL_PACKAGE_NAME + "=?",
                        new String[] { packageName }, null, null, null);
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        switch (sMatcher.match(uri)) {
            case URI_RECENT_OPEN: {
                db.insert(TABLE_RECENT_OPEN, null, values);
                db.delete(TABLE_RECENT_OPEN, buildWhereOlder(DateUtils.WEEK_IN_MILLIS), null);
                return uri;
            }
            case URI_RECENT_CREATE: {
                db.insert(TABLE_RECENT_CREATE, null, values);
                db.delete(TABLE_RECENT_CREATE, buildWhereOlder(DateUtils.WEEK_IN_MILLIS), null);
                return uri;
            }
            case URI_RESUME: {
                final String packageName = uri.getPathSegments().get(1);
                values.put(COL_PACKAGE_NAME, packageName);
                db.insert(TABLE_RESUME, null, values);
                return uri;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    private static String buildWhereOlder(long deltaMillis) {
        return COL_TIMESTAMP + "<" + (System.currentTimeMillis() - deltaMillis);
    }

    private static String buildWhereYounger(long deltaMillis) {
        return COL_TIMESTAMP + ">" + (System.currentTimeMillis() - deltaMillis);
    }
}
