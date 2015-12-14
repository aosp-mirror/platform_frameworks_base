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

import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

/**
 * Class containing MtpDatabase constants.
 */
class MtpDatabaseConstants {
    static final int DATABASE_VERSION = 1;
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
     * View to join Documents and RootExtra tables to provide roots information.
     */
    static final String VIEW_ROOTS = "Roots";

    static final String COLUMN_DEVICE_ID = "device_id";
    static final String COLUMN_STORAGE_ID = "storage_id";
    static final String COLUMN_OBJECT_HANDLE = "object_handle";
    static final String COLUMN_PARENT_DOCUMENT_ID = "parent_document_id";
    static final String COLUMN_ROW_STATE = "row_state";

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
     * The state represents the raw has a valid object handle but it may be going to be mapped with
     * another rows invalidated. After fetching all documents under the parent, the database tries
     * to map the pending documents and the invalidated documents in order to keep old document ID
     * alive.
     */
    static final int ROW_STATE_PENDING = 2;

    /**
     * Mapping mode that uses MTP identifier to find corresponding rows.
     */
    static final int MAP_BY_MTP_IDENTIFIER = 0;

    /**
     * Mapping mode that uses name to find corresponding rows.
     */
    static final int MAP_BY_NAME = 1;

    static final String SELECTION_DOCUMENT_ID = Document.COLUMN_DOCUMENT_ID + " = ?";
    static final String SELECTION_ROOT_ID = Root.COLUMN_ROOT_ID + " = ?";

    static final String QUERY_CREATE_DOCUMENTS =
            "CREATE TABLE " + TABLE_DOCUMENTS + " (" +
            Document.COLUMN_DOCUMENT_ID +
                " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COLUMN_DEVICE_ID + " INTEGER NOT NULL," +
            COLUMN_STORAGE_ID + " INTEGER," +
            COLUMN_OBJECT_HANDLE + " INTEGER," +
            COLUMN_PARENT_DOCUMENT_ID + " INTEGER," +
            COLUMN_ROW_STATE + " INTEGER NOT NULL," +
            Document.COLUMN_MIME_TYPE + " TEXT," +
            Document.COLUMN_DISPLAY_NAME + " TEXT NOT NULL," +
            Document.COLUMN_SUMMARY + " TEXT," +
            Document.COLUMN_LAST_MODIFIED + " INTEGER," +
            Document.COLUMN_ICON + " INTEGER," +
            Document.COLUMN_FLAGS + " INTEGER NOT NULL," +
            Document.COLUMN_SIZE + " INTEGER NOT NULL);";

    static final String QUERY_CREATE_ROOT_EXTRA =
            "CREATE TABLE " + TABLE_ROOT_EXTRA + " (" +
            Root.COLUMN_ROOT_ID + " INTEGER PRIMARY KEY," +
            Root.COLUMN_FLAGS + " INTEGER NOT NULL," +
            Root.COLUMN_AVAILABLE_BYTES + " INTEGER NOT NULL," +
            Root.COLUMN_CAPACITY_BYTES + " INTEGER NOT NULL," +
            Root.COLUMN_MIME_TYPES + " TEXT NOT NULL);";

    /**
     * Creates a view to join Documents table and RootExtra table on their primary keys to
     * provide DocumentContract.Root equivalent information.
     */
    static final String QUERY_CREATE_VIEW_ROOTS =
            "CREATE VIEW " + VIEW_ROOTS + " AS SELECT " +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_DOCUMENT_ID + " AS " +
                            Root.COLUMN_ROOT_ID + "," +
                    TABLE_ROOT_EXTRA + "." + Root.COLUMN_FLAGS + "," +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_ICON + " AS " +
                            Root.COLUMN_ICON + "," +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_DISPLAY_NAME + " AS " +
                            Root.COLUMN_TITLE + "," +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_SUMMARY + " AS " +
                            Root.COLUMN_SUMMARY + "," +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_DOCUMENT_ID + " AS " +
                    Root.COLUMN_DOCUMENT_ID + "," +
                    TABLE_ROOT_EXTRA + "." + Root.COLUMN_AVAILABLE_BYTES + "," +
                    TABLE_ROOT_EXTRA + "." + Root.COLUMN_CAPACITY_BYTES + "," +
                    TABLE_ROOT_EXTRA + "." + Root.COLUMN_MIME_TYPES + "," +
                    TABLE_DOCUMENTS + "." + COLUMN_ROW_STATE +
            " FROM " + TABLE_DOCUMENTS + " INNER JOIN " + TABLE_ROOT_EXTRA +
            " ON " +
                    COLUMN_PARENT_DOCUMENT_ID + " IS NULL AND " +
                    TABLE_DOCUMENTS + "." + Document.COLUMN_DOCUMENT_ID +
                    "=" +
                    TABLE_ROOT_EXTRA + "." + Root.COLUMN_ROOT_ID;
}
