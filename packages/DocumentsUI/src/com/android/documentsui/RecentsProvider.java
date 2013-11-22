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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.text.format.DateUtils;
import android.util.Log;

public class RecentsProvider extends ContentProvider {
    private static final String TAG = "RecentsProvider";

    public static final long MAX_HISTORY_IN_MILLIS = 45 * DateUtils.DAY_IN_MILLIS;

    private static final String AUTHORITY = "com.android.documentsui.recents";

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_RECENT = 1;
    private static final int URI_STATE = 2;
    private static final int URI_RESUME = 3;

    static {
        sMatcher.addURI(AUTHORITY, "recent", URI_RECENT);
        // state/authority/rootId/docId
        sMatcher.addURI(AUTHORITY, "state/*/*/*", URI_STATE);
        // resume/packageName
        sMatcher.addURI(AUTHORITY, "resume/*", URI_RESUME);
    }

    public static final String TABLE_RECENT = "recent";
    public static final String TABLE_STATE = "state";
    public static final String TABLE_RESUME = "resume";

    public static class RecentColumns {
        public static final String KEY = "key";
        public static final String STACK = "stack";
        public static final String TIMESTAMP = "timestamp";
    }

    public static class StateColumns {
        public static final String AUTHORITY = "authority";
        public static final String ROOT_ID = Root.COLUMN_ROOT_ID;
        public static final String DOCUMENT_ID = Document.COLUMN_DOCUMENT_ID;
        public static final String MODE = "mode";
        public static final String SORT_ORDER = "sortOrder";
    }

    public static class ResumeColumns {
        public static final String PACKAGE_NAME = "package_name";
        public static final String STACK = "stack";
        public static final String TIMESTAMP = "timestamp";
        public static final String EXTERNAL = "external";
    }

    public static Uri buildRecent() {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY).appendPath("recent").build();
    }

    public static Uri buildState(String authority, String rootId, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
                .appendPath("state").appendPath(authority).appendPath(rootId).appendPath(documentId)
                .build();
    }

    public static Uri buildResume(String packageName) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY).appendPath("resume").appendPath(packageName).build();
    }

    private DatabaseHelper mHelper;

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "recents.db";

        private static final int VERSION_INIT = 1;
        private static final int VERSION_AS_BLOB = 3;
        private static final int VERSION_ADD_EXTERNAL = 4;
        private static final int VERSION_ADD_RECENT_KEY = 5;

        public DatabaseHelper(Context context) {
            super(context, DB_NAME, null, VERSION_ADD_RECENT_KEY);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            db.execSQL("CREATE TABLE " + TABLE_RECENT + " (" +
                    RecentColumns.KEY + " TEXT PRIMARY KEY ON CONFLICT REPLACE," +
                    RecentColumns.STACK + " BLOB DEFAULT NULL," +
                    RecentColumns.TIMESTAMP + " INTEGER" +
                    ")");

            db.execSQL("CREATE TABLE " + TABLE_STATE + " (" +
                    StateColumns.AUTHORITY + " TEXT," +
                    StateColumns.ROOT_ID + " TEXT," +
                    StateColumns.DOCUMENT_ID + " TEXT," +
                    StateColumns.MODE + " INTEGER," +
                    StateColumns.SORT_ORDER + " INTEGER," +
                    "PRIMARY KEY (" + StateColumns.AUTHORITY + ", " + StateColumns.ROOT_ID + ", "
                    + StateColumns.DOCUMENT_ID + ")" +
                    ")");

            db.execSQL("CREATE TABLE " + TABLE_RESUME + " (" +
                    ResumeColumns.PACKAGE_NAME + " TEXT NOT NULL PRIMARY KEY," +
                    ResumeColumns.STACK + " BLOB DEFAULT NULL," +
                    ResumeColumns.TIMESTAMP + " INTEGER," +
                    ResumeColumns.EXTERNAL + " INTEGER NOT NULL DEFAULT 0" +
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database; wiping app data");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECENT);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_STATE);
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
            case URI_RECENT:
                final long cutoff = System.currentTimeMillis() - MAX_HISTORY_IN_MILLIS;
                return db.query(TABLE_RECENT, projection, RecentColumns.TIMESTAMP + ">" + cutoff,
                        null, null, null, sortOrder);
            case URI_STATE:
                final String authority = uri.getPathSegments().get(1);
                final String rootId = uri.getPathSegments().get(2);
                final String documentId = uri.getPathSegments().get(3);
                return db.query(TABLE_STATE, projection, StateColumns.AUTHORITY + "=? AND "
                        + StateColumns.ROOT_ID + "=? AND " + StateColumns.DOCUMENT_ID + "=?",
                        new String[] { authority, rootId, documentId }, null, null, sortOrder);
            case URI_RESUME:
                final String packageName = uri.getPathSegments().get(1);
                return db.query(TABLE_RESUME, projection, ResumeColumns.PACKAGE_NAME + "=?",
                        new String[] { packageName }, null, null, sortOrder);
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        final ContentValues key = new ContentValues();
        switch (sMatcher.match(uri)) {
            case URI_RECENT:
                values.put(RecentColumns.TIMESTAMP, System.currentTimeMillis());
                db.insert(TABLE_RECENT, null, values);
                final long cutoff = System.currentTimeMillis() - MAX_HISTORY_IN_MILLIS;
                db.delete(TABLE_RECENT, RecentColumns.TIMESTAMP + "<" + cutoff, null);
                return uri;
            case URI_STATE:
                final String authority = uri.getPathSegments().get(1);
                final String rootId = uri.getPathSegments().get(2);
                final String documentId = uri.getPathSegments().get(3);

                key.put(StateColumns.AUTHORITY, authority);
                key.put(StateColumns.ROOT_ID, rootId);
                key.put(StateColumns.DOCUMENT_ID, documentId);

                // Ensure that row exists, then update with changed values
                db.insertWithOnConflict(TABLE_STATE, null, key, SQLiteDatabase.CONFLICT_IGNORE);
                db.update(TABLE_STATE, values, StateColumns.AUTHORITY + "=? AND "
                        + StateColumns.ROOT_ID + "=? AND " + StateColumns.DOCUMENT_ID + "=?",
                        new String[] { authority, rootId, documentId });

                return uri;
            case URI_RESUME:
                values.put(ResumeColumns.TIMESTAMP, System.currentTimeMillis());

                final String packageName = uri.getPathSegments().get(1);
                key.put(ResumeColumns.PACKAGE_NAME, packageName);

                // Ensure that row exists, then update with changed values
                db.insertWithOnConflict(TABLE_RESUME, null, key, SQLiteDatabase.CONFLICT_IGNORE);
                db.update(TABLE_RESUME, values, ResumeColumns.PACKAGE_NAME + "=?",
                        new String[] { packageName });
                return uri;
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
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
}
