/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.net;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Database for storing blobs with a key of name strings.
 * @hide
 */
public class ConnectivityBlobStore {
    private static final String TAG = ConnectivityBlobStore.class.getSimpleName();
    private static final String TABLENAME = "blob_table";
    private static final String ROOT_DIR = "/data/misc/connectivityblobdb/";

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS " + TABLENAME + " ("
            + "owner INTEGER,"
            + "name BLOB,"
            + "blob BLOB,"
            + "UNIQUE(owner, name));";

    private final SQLiteDatabase mDb;

    /**
     * Construct a ConnectivityBlobStore object.
     *
     * @param dbName the filename of the database to create/access.
     */
    public ConnectivityBlobStore(String dbName) {
        this(new File(ROOT_DIR + dbName));
    }

    @VisibleForTesting
    public ConnectivityBlobStore(File file) {
        final SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY)
                .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .build();
        mDb = SQLiteDatabase.openDatabase(file, params);
        mDb.execSQL(CREATE_TABLE);
    }

    /**
     * Stores the blob under the name in the database. Existing blobs by the same name will be
     * replaced.
     *
     * @param name The name of the blob
     * @param blob The blob.
     * @return true if the blob was successfully added. False otherwise.
     * @hide
     */
    public boolean put(@NonNull String name, @NonNull byte[] blob) {
        final int ownerUid = Binder.getCallingUid();
        final ContentValues values = new ContentValues();
        values.put("owner", ownerUid);
        values.put("name", name);
        values.put("blob", blob);

        // No need for try-catch since it is done within db.replace
        // nullColumnHack is for the case where values may be empty since SQL does not allow
        // inserting a completely empty row. Since values is never empty, set this to null.
        final long res = mDb.replace(TABLENAME, null /* nullColumnHack */, values);
        return res > 0;
    }

    /**
     * Retrieves a blob by the name from the database.
     *
     * @param name Name of the blob to retrieve.
     * @return The unstructured blob, that is the blob that was stored using
     *         {@link com.android.internal.net.ConnectivityBlobStore#put}.
     *         Returns null if no blob was found.
     * @hide
     */
    public byte[] get(@NonNull String name) {
        final int ownerUid = Binder.getCallingUid();
        try (Cursor cursor = mDb.query(TABLENAME,
                new String[] {"blob"} /* columns */,
                "owner=? AND name=?" /* selection */,
                new String[] {Integer.toString(ownerUid), name} /* selectionArgs */,
                null /* groupBy */,
                null /* having */,
                null /* orderBy */)) {
            if (cursor.moveToFirst()) {
                return cursor.getBlob(0);
            }
        } catch (SQLException e) {
            Log.e(TAG, "Error in getting " + name + ": " + e);
        }

        return null;
    }

    /**
     * Removes a blob by the name from the database.
     *
     * @param name Name of the blob to be removed.
     * @return True if a blob was removed. False if no such name was found.
     * @hide
     */
    public boolean remove(@NonNull String name) {
        final int ownerUid = Binder.getCallingUid();
        try {
            final int res = mDb.delete(TABLENAME,
                    "owner=? AND name=?" /* whereClause */,
                    new String[] {Integer.toString(ownerUid), name} /* whereArgs */);
            return res > 0;
        } catch (SQLException e) {
            Log.e(TAG, "Error in removing " + name + ": " + e);
            return false;
        }
    }

    /**
     * Lists the name suffixes stored in the database matching the given prefix, sorted in
     * ascending order.
     *
     * @param prefix String of prefix to list from the stored names.
     * @return An array of strings representing the name suffixes stored in the database
     *         matching the given prefix, sorted in ascending order.
     *         The return value may be empty but never null.
     * @hide
     */
    public String[] list(@NonNull String prefix) {
        final int ownerUid = Binder.getCallingUid();
        final List<String> names = new ArrayList<String>();
        try (Cursor cursor = mDb.query(TABLENAME,
                new String[] {"name"} /* columns */,
                "owner=? AND name LIKE ? ESCAPE '\\'" /* selection */,
                new String[] {
                        Integer.toString(ownerUid),
                        DatabaseUtils.escapeForLike(prefix) + "%"
                } /* selectionArgs */,
                null /* groupBy */,
                null /* having */,
                "name ASC" /* orderBy */)) {
            if (cursor.moveToFirst()) {
                do {
                    final String name = cursor.getString(0);
                    names.add(name.substring(prefix.length()));
                } while (cursor.moveToNext());
            }
        } catch (SQLException e) {
            Log.e(TAG, "Error in listing " + prefix + ": " + e);
        }

        return names.toArray(new String[names.size()]);
    }
}
