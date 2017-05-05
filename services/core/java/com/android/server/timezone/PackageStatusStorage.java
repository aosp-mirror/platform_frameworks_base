/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Slog;

import java.io.File;

import static com.android.server.timezone.PackageStatus.CHECK_COMPLETED_FAILURE;
import static com.android.server.timezone.PackageStatus.CHECK_COMPLETED_SUCCESS;
import static com.android.server.timezone.PackageStatus.CHECK_STARTED;

/**
 * Storage logic for accessing/mutating the Android system's persistent state related to time zone
 * update checking. There is expected to be a single instance and all methods synchronized on
 * {@code this} for thread safety.
 */
final class PackageStatusStorage {

    private static final String TAG = "timezone.PackageStatusStorage";

    private static final String DATABASE_NAME = "timezonepackagestatus.db";
    private static final int DATABASE_VERSION = 1;

    /** The table name. It will have a single row with _id == {@link #SINGLETON_ID} */
    private static final String TABLE = "status";
    private static final String COLUMN_ID = "_id";

    /**
     * Column that stores a monotonically increasing lock ID, used to detect concurrent update
     * issues without on-line locks. Incremented on every write.
     */
    private static final String COLUMN_OPTIMISTIC_LOCK_ID = "optimistic_lock_id";

    /**
     * Column that stores the current "check status" of the time zone update application packages.
     */
    private static final String COLUMN_CHECK_STATUS = "check_status";

    /**
     * Column that stores the version of the time zone rules update application being checked / last
     * checked.
     */
    private static final String COLUMN_UPDATE_APP_VERSION = "update_app_package_version";

    /**
     * Column that stores the version of the time zone rules data application being checked / last
     * checked.
     */
    private static final String COLUMN_DATA_APP_VERSION = "data_app_package_version";

    /**
     * The ID of the one row.
     */
    private static final int SINGLETON_ID = 1;

    private static final int UNKNOWN_PACKAGE_VERSION = -1;

    private final DatabaseHelper mDatabaseHelper;

    PackageStatusStorage(Context context) {
        mDatabaseHelper = new DatabaseHelper(context);
    }

    void deleteDatabaseForTests() {
        SQLiteDatabase.deleteDatabase(mDatabaseHelper.getDatabaseFile());
    }

    /**
     * Obtain the current check status of the application packages. Returns {@code null} the first
     * time it is called, or after {@link #resetCheckState()}.
     */
    PackageStatus getPackageStatus() {
        synchronized (this) {
            try {
                return getPackageStatusInternal();
            } catch (IllegalArgumentException e) {
                // This means that data exists in the table but it was bad.
                Slog.e(TAG, "Package status invalid, resetting and retrying", e);

                // Reset the storage so it is in a good state again.
                mDatabaseHelper.recoverFromBadData();
                return getPackageStatusInternal();
            }
        }
    }

    private PackageStatus getPackageStatusInternal() {
        String[] columns = {
                COLUMN_CHECK_STATUS, COLUMN_UPDATE_APP_VERSION, COLUMN_DATA_APP_VERSION
        };
        Cursor cursor = mDatabaseHelper.getReadableDatabase()
                .query(TABLE, columns, COLUMN_ID + " = ?",
                        new String[] { Integer.toString(SINGLETON_ID) },
                        null /* groupBy */, null /* having */, null /* orderBy */);
        if (cursor.getCount() != 1) {
            Slog.e(TAG, "Unable to find package status from package status row. Rows returned: "
                    + cursor.getCount());
            return null;
        }
        cursor.moveToFirst();

        // Determine check status.
        if (cursor.isNull(0)) {
            // This is normal the first time getPackageStatus() is called, or after
            // resetCheckState().
            return null;
        }
        int checkStatus = cursor.getInt(0);

        // Determine package version.
        if (cursor.isNull(1) || cursor.isNull(2)) {
            Slog.e(TAG, "Package version information unexpectedly null");
            return null;
        }
        PackageVersions packageVersions = new PackageVersions(cursor.getInt(1), cursor.getInt(2));

        return new PackageStatus(checkStatus, packageVersions);
    }

    /**
     * Generate a new {@link CheckToken} that can be passed to the time zone rules update
     * application.
     */
    CheckToken generateCheckToken(PackageVersions currentInstalledVersions) {
        if (currentInstalledVersions == null) {
            throw new NullPointerException("currentInstalledVersions == null");
        }

        synchronized (this) {
            Integer optimisticLockId = getCurrentOptimisticLockId();
            if (optimisticLockId == null) {
                Slog.w(TAG, "Unable to find optimistic lock ID from package status row");

                // Recover.
                optimisticLockId = mDatabaseHelper.recoverFromBadData();
            }

            int newOptimisticLockId = optimisticLockId + 1;
            boolean statusRowUpdated = writeStatusRow(
                    optimisticLockId, newOptimisticLockId, CHECK_STARTED, currentInstalledVersions);
            if (!statusRowUpdated) {
                Slog.e(TAG, "Unable to update status to CHECK_STARTED in package status row."
                        + " synchronization failure?");
                return null;
            }
            return new CheckToken(newOptimisticLockId, currentInstalledVersions);
        }
    }

    /**
     * Reset the current device state to "unknown".
     */
    void resetCheckState() {
        synchronized(this) {
            Integer optimisticLockId = getCurrentOptimisticLockId();
            if (optimisticLockId == null) {
                Slog.w(TAG, "resetCheckState: Unable to find optimistic lock ID from package"
                        + " status row");
                // Attempt to recover the storage state.
                optimisticLockId = mDatabaseHelper.recoverFromBadData();
            }

            int newOptimisticLockId = optimisticLockId + 1;
            if (!writeStatusRow(optimisticLockId, newOptimisticLockId,
                    null /* status */, null /* packageVersions */)) {
                Slog.e(TAG, "resetCheckState: Unable to reset package status row,"
                        + " newOptimisticLockId=" + newOptimisticLockId);
            }
        }
    }

    /**
     * Update the current device state if possible. Returns true if the update was successful.
     * {@code false} indicates the storage has been changed since the {@link CheckToken} was
     * generated and the update was discarded.
     */
    boolean markChecked(CheckToken checkToken, boolean succeeded) {
        synchronized (this) {
            int optimisticLockId = checkToken.mOptimisticLockId;
            int newOptimisticLockId = optimisticLockId + 1;
            int status = succeeded ? CHECK_COMPLETED_SUCCESS : CHECK_COMPLETED_FAILURE;
            return writeStatusRow(optimisticLockId, newOptimisticLockId,
                    status, checkToken.mPackageVersions);
        }
    }

    // Caller should be synchronized(this)
    private Integer getCurrentOptimisticLockId() {
        final String[] columns = { COLUMN_OPTIMISTIC_LOCK_ID };
        final String querySelection = COLUMN_ID + " = ?";
        final String[] querySelectionArgs = { Integer.toString(SINGLETON_ID) };

        SQLiteDatabase database = mDatabaseHelper.getReadableDatabase();
        try (Cursor cursor = database.query(TABLE, columns, querySelection, querySelectionArgs,
                null /* groupBy */, null /* having */, null /* orderBy */)) {
            if (cursor.getCount() != 1) {
                Slog.w(TAG, cursor.getCount() + " rows returned, expected exactly one.");
                return null;
            }
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    // Caller should be synchronized(this)
    private boolean writeStatusRow(int optimisticLockId, int newOptimisticLockId, Integer status,
            PackageVersions packageVersions) {
        if ((status == null) != (packageVersions == null)) {
            throw new IllegalArgumentException(
                    "Provide both status and packageVersions, or neither.");
        }

        SQLiteDatabase database = mDatabaseHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_OPTIMISTIC_LOCK_ID, newOptimisticLockId);
        if (status == null) {
            values.putNull(COLUMN_CHECK_STATUS);
            values.put(COLUMN_UPDATE_APP_VERSION, UNKNOWN_PACKAGE_VERSION);
            values.put(COLUMN_DATA_APP_VERSION, UNKNOWN_PACKAGE_VERSION);
        } else {
            values.put(COLUMN_CHECK_STATUS, status);
            values.put(COLUMN_UPDATE_APP_VERSION, packageVersions.mUpdateAppVersion);
            values.put(COLUMN_DATA_APP_VERSION, packageVersions.mDataAppVersion);
        }

        String updateSelection = COLUMN_ID + " = ? AND " + COLUMN_OPTIMISTIC_LOCK_ID + " = ?";
        String[] updateSelectionArgs = {
                Integer.toString(SINGLETON_ID), Integer.toString(optimisticLockId)
        };
        int count = database.update(TABLE, values, updateSelection, updateSelectionArgs);
        if (count > 1) {
            // This has to be because of corruption: there should only ever be one row.
            Slog.w(TAG, "writeStatusRow: " + count + " rows updated, expected exactly one.");
            // Reset the table.
            mDatabaseHelper.recoverFromBadData();
        }

        // 1 is the success case. 0 rows updated means the row is missing or the optimistic lock ID
        // was not as expected, this could be because of corruption but is most likely due to an
        // optimistic lock failure. Callers can decide on a case-by-case basis.
        return count == 1;
    }

    /** Only used during tests to force an empty table. */
    void deleteRowForTests() {
        mDatabaseHelper.getWritableDatabase().delete(TABLE, null, null);
    }

    /** Only used during tests to force a known table state. */
    public void forceCheckStateForTests(int checkStatus, PackageVersions packageVersions) {
        int optimisticLockId = getCurrentOptimisticLockId();
        writeStatusRow(optimisticLockId, optimisticLockId, checkStatus, packageVersions);
    }

    static class DatabaseHelper extends SQLiteOpenHelper {

        private final Context mContext;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (" +
                    "_id INTEGER PRIMARY KEY," +
                    COLUMN_OPTIMISTIC_LOCK_ID + " INTEGER NOT NULL," +
                    COLUMN_CHECK_STATUS + " INTEGER," +
                    COLUMN_UPDATE_APP_VERSION + " INTEGER NOT NULL," +
                    COLUMN_DATA_APP_VERSION + " INTEGER NOT NULL" +
                    ");");
            insertInitialRowState(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            // no-op: nothing to upgrade
        }

        /** Recover the initial data row state, returning the new current optimistic lock ID */
        int recoverFromBadData() {
            // Delete the table content.
            SQLiteDatabase writableDatabase = getWritableDatabase();
            writableDatabase.delete(TABLE, null /* whereClause */, null /* whereArgs */);

            // Insert the initial content.
            return insertInitialRowState(writableDatabase);
        }

        /** Insert the initial data row, returning the optimistic lock ID */
        private static int insertInitialRowState(SQLiteDatabase db) {
            // Doesn't matter what it is, but we avoid the obvious starting value each time the row
            // is reset to ensure that old tokens are unlikely to work.
           final int initialOptimisticLockId = (int) System.currentTimeMillis();

            // Insert the one row.
            ContentValues values = new ContentValues();
            values.put(COLUMN_ID, SINGLETON_ID);
            values.put(COLUMN_OPTIMISTIC_LOCK_ID, initialOptimisticLockId);
            values.putNull(COLUMN_CHECK_STATUS);
            values.put(COLUMN_UPDATE_APP_VERSION, UNKNOWN_PACKAGE_VERSION);
            values.put(COLUMN_DATA_APP_VERSION, UNKNOWN_PACKAGE_VERSION);
            long id = db.insert(TABLE, null, values);
            if (id == -1) {
                Slog.w(TAG, "insertInitialRow: could not insert initial row, id=" + id);
                return -1;
            }
            return initialOptimisticLockId;
        }

        File getDatabaseFile() {
            return mContext.getDatabasePath(DATABASE_NAME);
        }
    }
}
