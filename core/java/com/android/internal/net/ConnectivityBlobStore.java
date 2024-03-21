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

import android.database.sqlite.SQLiteDatabase;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;

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
}
