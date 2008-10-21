/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.server.checkin;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.provider.BaseColumns;
import android.provider.Checkin;
import android.util.Log;

import java.io.File;

/**
 * Content provider for the database used to store events and statistics
 * while they wait to be uploaded by the checkin service.
 */
public class CheckinProvider extends ContentProvider {
    /** Class identifier for logging. */
    private static final String TAG = "CheckinProvider";

    /** Filename of database (in /data directory). */
    private static final String DATABASE_FILENAME = "checkin.db";

    /** Version of database schema.  */
    private static final int DATABASE_VERSION = 1;

    /** Maximum number of events recorded. */
    private static final int EVENT_LIMIT = 1000;

    /** Maximum size of individual event data. */
    private static final int EVENT_SIZE = 8192;

    /** Maximum number of crashes recorded. */
    private static final int CRASH_LIMIT = 25;

    /** Maximum size of individual crashes recorded. */
    private static final int CRASH_SIZE = 16384;

    /** Permission required for access to the 'properties' database. */
    private static final String PROPERTIES_PERMISSION =
            "android.permission.ACCESS_CHECKIN_PROPERTIES";

    /** Lock for stats read-modify-write update cycle (see {@link #insert}). */
    private final Object mStatsLock = new Object();

    /** The underlying SQLite database. */
    private SQLiteOpenHelper mOpenHelper;

    private static class OpenHelper extends SQLiteOpenHelper {
        public OpenHelper(Context context) {
            super(context, DATABASE_FILENAME, null, DATABASE_VERSION);

            // The database used to live in /data/checkin.db.
            File oldLocation = Environment.getDataDirectory();
            File old = new File(oldLocation, DATABASE_FILENAME);
            File file = context.getDatabasePath(DATABASE_FILENAME);

            // Try to move the file to the new location.
            // TODO: Remove this code before shipping.
            if (old.exists() && !file.exists() && !old.renameTo(file)) {
               Log.e(TAG, "Can't rename " + old + " to " + file);
            }
            if (old.exists() && !old.delete()) {
               // Clean up the old data file in any case.
               Log.e(TAG, "Can't remove " + old);
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Checkin.Events.TABLE_NAME + " (" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Checkin.Events.TAG + " TEXT NOT NULL," +
                    Checkin.Events.VALUE + " TEXT DEFAULT \"\"," +
                    Checkin.Events.DATE + " INTEGER NOT NULL)");

            db.execSQL("CREATE INDEX events_index ON " +
                    Checkin.Events.TABLE_NAME + " (" +
                    Checkin.Events.TAG + ")");

            db.execSQL("CREATE TABLE " + Checkin.Stats.TABLE_NAME + " (" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Checkin.Stats.TAG + " TEXT UNIQUE," +
                    Checkin.Stats.COUNT + " INTEGER DEFAULT 0," +
                    Checkin.Stats.SUM + " REAL DEFAULT 0.0)");

            db.execSQL("CREATE TABLE " + Checkin.Crashes.TABLE_NAME + " (" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Checkin.Crashes.DATA + " TEXT NOT NULL," +
                    Checkin.Crashes.LOGS + " TEXT)");

            db.execSQL("CREATE TABLE " + Checkin.Properties.TABLE_NAME + " (" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Checkin.Properties.TAG + " TEXT UNIQUE ON CONFLICT REPLACE,"
                    + Checkin.Properties.VALUE + " TEXT DEFAULT \"\")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int old, int version) {
            db.execSQL("DROP TABLE IF EXISTS " + Checkin.Events.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + Checkin.Stats.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + Checkin.Crashes.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + Checkin.Properties.TABLE_NAME);
            onCreate(db);
        }
    }

    @Override public boolean onCreate() {
        mOpenHelper = new OpenHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] select,
            String where, String[] args, String sort) {
        checkPermissions(uri);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(uri.getPathSegments().get(0));
        if (uri.getPathSegments().size() == 2) {
            qb.appendWhere("_id=" + ContentUris.parseId(uri));
        } else if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Invalid query URI: " + uri);
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = qb.query(db, select, where, args, null, null, sort);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkPermissions(uri);
        if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Invalid insert URI: " + uri);
        }

        long id;
        String table = uri.getPathSegments().get(0);
        if (Checkin.Events.TABLE_NAME.equals(table)) {
            id = insertEvent(values);
        } else if (Checkin.Stats.TABLE_NAME.equals(table)) {
            id = insertStats(values);
        } else if (Checkin.Crashes.TABLE_NAME.equals(table)) {
            id = insertCrash(values);
        } else {
            id = mOpenHelper.getWritableDatabase().insert(table, null, values);
        }

        if (id < 0) {
            return null;
        } else {
            uri = ContentUris.withAppendedId(uri, id);
            getContext().getContentResolver().notifyChange(uri, null);
            return uri;
        }
    }

    /**
     * Insert an entry into the events table.
     * Trims old events from the table to keep the size bounded.
     * @param values to insert
     * @return the row ID of the new entry
     */
    private long insertEvent(ContentValues values) {
        String value = values.getAsString(Checkin.Events.VALUE);
        if (value != null && value.length() > EVENT_SIZE) {
            // Event values are readable text, so they can be truncated.
            value = value.substring(0, EVENT_SIZE - 3) + "...";
            values.put(Checkin.Events.VALUE, value);
        }

        if (!values.containsKey(Checkin.Events.DATE)) {
            values.put(Checkin.Events.DATE, System.currentTimeMillis());
        }

        // TODO: Make this more efficient; don't do it on every insert.
        // Also, consider keeping the most recent instance of every tag,
        // and possibly update a counter when events are deleted.

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.execSQL("DELETE FROM " +
                Checkin.Events.TABLE_NAME + " WHERE " +
                Checkin.Events._ID + " IN (SELECT " +
                Checkin.Events._ID + " FROM " +
                Checkin.Events.TABLE_NAME + " ORDER BY " +
                Checkin.Events.DATE + " DESC LIMIT -1 OFFSET " +
                (EVENT_LIMIT - 1) + ")");
        return db.insert(Checkin.Events.TABLE_NAME, null, values);
    }

    /**
     * Add an entry into the stats table.
     * For statistics, instead of just inserting a row into the database,
     * we add the count and sum values to the existing values (if any)
     * for the specified tag.  This must be done with a lock held,
     * to avoid a race condition during the read-modify-write update.
     * @param values to insert
     * @return the row ID of the modified entry
     */
    private long insertStats(ContentValues values) {
        synchronized (mStatsLock) {
            String tag = values.getAsString(Checkin.Stats.TAG);
            if (tag == null) {
                throw new IllegalArgumentException("Tag required:" + values);
            }

            // Look for existing values with this tag.
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            Cursor cursor = db.query(false,
                    Checkin.Stats.TABLE_NAME,
                    new String[] {
                            Checkin.Stats._ID,
                            Checkin.Stats.COUNT,
                            Checkin.Stats.SUM
                    },
                    Checkin.Stats.TAG + "=?",
                    new String[] { tag },
                    null, null, null, null /* limit */);

            try {
                if (cursor == null || !cursor.moveToNext()) {
                    // This is a new statistic, insert it directly.
                    return db.insert(Checkin.Stats.TABLE_NAME, null, values);
                } else {
                    // Depend on SELECT column order to avoid getColumnIndex()
                    long id = cursor.getLong(0);
                    int count = cursor.getInt(1);
                    double sum = cursor.getDouble(2);

                    Integer countAdd = values.getAsInteger(Checkin.Stats.COUNT);
                    if (countAdd != null) count += countAdd.intValue();

                    Double sumAdd = values.getAsDouble(Checkin.Stats.SUM);
                    if (sumAdd != null) sum += sumAdd.doubleValue();

                    if (count <= 0 && sum == 0.0) {
                        // Updated to nothing: delete the row!
                        cursor.deleteRow();
                        getContext().getContentResolver().notifyChange(
                                ContentUris.withAppendedId(Checkin.Stats.CONTENT_URI, id), null);
                        return -1;
                    } else {
                        if (countAdd != null) cursor.updateInt(1, count);
                        if (sumAdd != null) cursor.updateDouble(2, sum);
                        cursor.commitUpdates();
                        return id;
                    }
                }
            } finally {
                // Always clean up the cursor.
                if (cursor != null) cursor.close();
            }
        }
    }

    /**
     * Add an entry into the crashes table.
     * @param values to insert
     * @return the row ID of the modified entry
     */
    private long insertCrash(ContentValues values) {
        try {
            int crashSize = values.getAsString(Checkin.Crashes.DATA).length();
            if (crashSize > CRASH_SIZE) {
                // The crash is too big.  Don't report it, but do log a stat.
                Checkin.updateStats(getContext().getContentResolver(),
                        Checkin.Stats.Tag.CRASHES_TRUNCATED, 1, 0.0);
                throw new IllegalArgumentException("Too big: " + crashSize);
            }

            // Count the number of crashes reported, even if they roll over.
            Checkin.updateStats(getContext().getContentResolver(),
                    Checkin.Stats.Tag.CRASHES_REPORTED, 1, 0.0);

            // Trim the crashes database, if needed.
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            db.execSQL("DELETE FROM " +
                    Checkin.Crashes.TABLE_NAME + " WHERE " +
                    Checkin.Crashes._ID + " IN (SELECT " +
                    Checkin.Crashes._ID + " FROM " +
                    Checkin.Crashes.TABLE_NAME + " ORDER BY " +
                    Checkin.Crashes._ID + " DESC LIMIT -1 OFFSET " +
                    (CRASH_LIMIT - 1) + ")");

            return db.insert(Checkin.Crashes.TABLE_NAME, null, values);
        } catch (Throwable t) {
            // To avoid an infinite crash-reporting loop, swallow the error.
            Log.e("CheckinProvider", "Error inserting crash: " + t);
            return -1;
        }
    }

    // TODO: optimize bulkInsert, especially for stats?

    @Override
    public int update(Uri uri, ContentValues values,
            String where, String[] args) {
        checkPermissions(uri);
        if (uri.getPathSegments().size() == 2) {
            if (where != null && where.length() > 0) {
                throw new UnsupportedOperationException(
                        "WHERE clause not supported for update: " + uri);
            }
            where = "_id=" + ContentUris.parseId(uri);
            args = null;
        } else if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Invalid update URI: " + uri);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(uri.getPathSegments().get(0), values, where, args);
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int delete(Uri uri, String where, String[] args) {
        checkPermissions(uri);
        if (uri.getPathSegments().size() == 2) {
            if (where != null && where.length() > 0) {
                throw new UnsupportedOperationException(
                        "WHERE clause not supported for delete: " + uri);
            }
            where = "_id=" + ContentUris.parseId(uri);
            args = null;
        } else if (uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Invalid delete URI: " + uri);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.delete(uri.getPathSegments().get(0), where, args);
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public String getType(Uri uri) {
        if (uri.getPathSegments().size() == 1) {
            return "vnd.android.cursor.dir/" + uri.getPathSegments().get(0);
        } else if (uri.getPathSegments().size() == 2) {
            return "vnd.android.cursor.item/" + uri.getPathSegments().get(0);
        } else {
            throw new IllegalArgumentException("Invalid URI: " + uri);
        }
    }

    /**
     * Make sure the caller has permission to the database.
     * @param uri the caller is requesting access to
     * @throws SecurityException if the caller is forbidden.
     */
    private void checkPermissions(Uri uri) {
        if (uri.getPathSegments().size() < 1) {
            throw new IllegalArgumentException("Invalid query URI: " + uri);
        }

        String table = uri.getPathSegments().get(0);
        if (table.equals(Checkin.Properties.TABLE_NAME) &&
            getContext().checkCallingOrSelfPermission(PROPERTIES_PERMISSION) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Cannot access checkin properties");
        }
    }
}
