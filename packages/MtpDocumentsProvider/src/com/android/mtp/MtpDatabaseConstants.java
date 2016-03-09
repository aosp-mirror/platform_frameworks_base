/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.annotation.IntDef;
import android.database.sqlite.SQLiteQueryBuilder;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Class containing MtpDatabase constants.
 */
class MtpDatabaseConstants {
    static final int DATABASE_VERSION = 5;
    static final String DATABASE_NAME = "database";

    static final int FLAG_DATABASE_IN_MEMORY = 1;
    static final int FLAG_DATABASE_IN_FILE = 0;

    /**
     * Table representing documents including root documents.
     */
    static final String TABLE_DOCUMENTS = "Documents";

    /**
     * Table containing additional information only available for root documents.
     * The table uses same primary keys with corresponding documents.
     */
    static final String TABLE_ROOT_EXTRA = "RootExtra";

    /**
     * Table containing last boot count.
     */
    static final String TABLE_LAST_BOOT_COUNT = "LastBootCount";

    /**
     * 'FROM' closure of joining TABLE_DOCUMENTS and TABLE_ROOT_EXTRA.
     */
    static final String JOIN_ROOTS = createJoinFromClosure(
            TABLE_DOCUMENTS,
            TABLE_ROOT_EXTRA,
            Document.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ROOT_ID);

    static final String COLUMN_DEVICE_ID = "device_id";
    static final String COLUMN_STORAGE_ID = "storage_id";
    static final String COLUMN_OBJECT_HANDLE = "object_handle";
    static final String COLUMN_PARENT_DOCUMENT_ID = "parent_document_id";
    static final String COLUMN_DOCUMENT_TYPE = "document_type";
    static final String COLUMN_ROW_STATE = "row_state";
    static final String COLUMN_MAPPING_KEY = "mapping_key";

    /**
     * Value for TABLE_LAST_BOOT_COUNT.
     * Type: INTEGER
     */
    static final String COLUMN_VALUE = "value";

    /**
     * The state represents that the row has a valid object handle.
     */
    static final int ROW_STATE_VALID = 0;

    /**
     * The state represents that the rows added at the previous cycle and need to be updated with
     * fresh values.
     * The row may not have valid object handle. External application can still fetch the documents.
     * If the external application tries to fetch object handle, the provider resolves pending
     * documents with invalidated documents ahead.
     */
    static final int ROW_STATE_INVALIDATED = 1;

    /**
     * The documents are of device/storage that are disconnected now. The documents are invisible
     * but their document ID will be reuse when the device/storage is connected again.
     */
    static final int ROW_STATE_DISCONNECTED = 2;

    @IntDef(value = { DOCUMENT_TYPE_DEVICE, DOCUMENT_TYPE_STORAGE, DOCUMENT_TYPE_OBJECT })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DocumentType {}

    /**
     * Document that represents a MTP device.
     */
    static final int DOCUMENT_TYPE_DEVICE = 0;

    /**
     * Document that represents a MTP storage.
     */
    static final int DOCUMENT_TYPE_STORAGE = 1;

    /**
     * Document that represents a MTP object.
     */
    static final int DOCUMENT_TYPE_OBJECT = 2;

    static final String SELECTION_DOCUMENT_ID = Document.COLUMN_DOCUMENT_ID + " = ?";
    static final String SELECTION_ROOT_ID = Root.COLUMN_ROOT_ID + " = ?";

    static final String QUERY_CREATE_DOCUMENTS =
            "CREATE TABLE " + TABLE_DOCUMENTS + " (" +
            Document.COLUMN_DOCUMENT_ID +
                " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COLUMN_DEVICE_ID + " INTEGER," +
            COLUMN_STORAGE_ID + " INTEGER," +
            COLUMN_OBJECT_HANDLE + " INTEGER," +
            COLUMN_PARENT_DOCUMENT_ID + " INTEGER," +
            COLUMN_ROW_STATE + " INTEGER NOT NULL," +
            COLUMN_DOCUMENT_TYPE + " INTEGER NOT NULL," +
            COLUMN_MAPPING_KEY + " STRING," +
            Document.COLUMN_MIME_TYPE + " TEXT NOT NULL," +
            Document.COLUMN_DISPLAY_NAME + " TEXT NOT NULL," +
            Document.COLUMN_SUMMARY + " TEXT," +
            Document.COLUMN_LAST_MODIFIED + " INTEGER," +
            Document.COLUMN_ICON + " INTEGER," +
            Document.COLUMN_FLAGS + " INTEGER NOT NULL," +
            Document.COLUMN_SIZE + " INTEGER);";

    static final String QUERY_CREATE_ROOT_EXTRA =
            "CREATE TABLE " + TABLE_ROOT_EXTRA + " (" +
            Root.COLUMN_ROOT_ID + " INTEGER PRIMARY KEY," +
            Root.COLUMN_FLAGS + " INTEGER NOT NULL," +
            Root.COLUMN_AVAILABLE_BYTES + " INTEGER," +
            Root.COLUMN_CAPACITY_BYTES + " INTEGER," +
            Root.COLUMN_MIME_TYPES + " TEXT NOT NULL);";

    static final String QUERY_CREATE_LAST_BOOT_COUNT =
            "CREATE TABLE " + TABLE_LAST_BOOT_COUNT + " (value INTEGER NOT NULL);";

    /**
     * Map for columns names to provide DocumentContract.Root compatible columns.
     * @see SQLiteQueryBuilder#setProjectionMap(Map)
     */
    static final Map<String, String> COLUMN_MAP_ROOTS;
    static {
        COLUMN_MAP_ROOTS = new HashMap<>();
        COLUMN_MAP_ROOTS.put(Root.COLUMN_ROOT_ID, TABLE_ROOT_EXTRA + "." + Root.COLUMN_ROOT_ID);
        COLUMN_MAP_ROOTS.put(Root.COLUMN_FLAGS, TABLE_ROOT_EXTRA + "." + Root.COLUMN_FLAGS);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_ICON,
                TABLE_DOCUMENTS + "." + Document.COLUMN_ICON + " AS " + Root.COLUMN_ICON);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_TITLE,
                TABLE_DOCUMENTS + "." + Document.COLUMN_DISPLAY_NAME + " AS " + Root.COLUMN_TITLE);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_SUMMARY,
                TABLE_DOCUMENTS + "." + Document.COLUMN_SUMMARY + " AS " + Root.COLUMN_SUMMARY);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_DOCUMENT_ID,
                TABLE_DOCUMENTS + "." + Document.COLUMN_DOCUMENT_ID +
                " AS " + Root.COLUMN_DOCUMENT_ID);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_AVAILABLE_BYTES, TABLE_ROOT_EXTRA + "." + Root.COLUMN_AVAILABLE_BYTES);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_CAPACITY_BYTES, TABLE_ROOT_EXTRA + "." + Root.COLUMN_CAPACITY_BYTES);
        COLUMN_MAP_ROOTS.put(
                Root.COLUMN_MIME_TYPES, TABLE_ROOT_EXTRA + "." + Root.COLUMN_MIME_TYPES);
        COLUMN_MAP_ROOTS.put(COLUMN_DEVICE_ID, COLUMN_DEVICE_ID);
    }

    private static String createJoinFromClosure(
            String table1, String table2, String column1, String column2) {
        return table1 + " LEFT JOIN " + table2 +
                " ON " + table1 + "." + column1 + " = " + table2 + "." + column2;
    }
}
