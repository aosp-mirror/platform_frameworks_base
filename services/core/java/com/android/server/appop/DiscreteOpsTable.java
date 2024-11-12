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

package com.android.server.appop;


/**
 * SQLite table for storing app op accesses.
 */
final class DiscreteOpsTable {
    private static final String TABLE_NAME = "app_op_accesses";
    private static final String INDEX_APP_OP = "app_op_access_index";

    static final class Columns {
        /** Auto increment primary key. */
        static final String ID = "id";
        /** UID of the package accessing private data. */
        static final String UID = "uid";
        /** Package accessing private data. */
        static final String PACKAGE_NAME = "package_name";
        /** The device from which the private data is accessed. */
        static final String DEVICE_ID = "device_id";
        /** Op code representing private data i.e. location, mic etc. */
        static final String OP_CODE = "op_code";
        /** Attribution tag provided when accessing the private data. */
        static final String ATTRIBUTION_TAG = "attribution_tag";
        /** Timestamp when private data is accessed, number of milliseconds that have passed
         * since Unix epoch */
        static final String ACCESS_TIME = "access_time";
        /** For how long the private data is accessed. */
        static final String ACCESS_DURATION = "access_duration";
        /** App process state, whether the app is in foreground, background or cached etc. */
        static final String UID_STATE = "uid_state";
        /** App op flags */
        static final String OP_FLAGS = "op_flags";
        /** Attribution flags */
        static final String ATTRIBUTION_FLAGS = "attribution_flags";
        /** Chain id */
        static final String CHAIN_ID = "chain_id";
    }

    static final int UID_INDEX = 1;
    static final int PACKAGE_NAME_INDEX = 2;
    static final int DEVICE_ID_INDEX = 3;
    static final int OP_CODE_INDEX = 4;
    static final int ATTRIBUTION_TAG_INDEX = 5;
    static final int ACCESS_TIME_INDEX = 6;
    static final int ACCESS_DURATION_INDEX = 7;
    static final int UID_STATE_INDEX = 8;
    static final int OP_FLAGS_INDEX = 9;
    static final int ATTRIBUTION_FLAGS_INDEX = 10;
    static final int CHAIN_ID_INDEX = 11;

    static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME + "("
            + Columns.ID + " INTEGER PRIMARY KEY,"
            + Columns.UID + " INTEGER,"
            + Columns.PACKAGE_NAME + " TEXT,"
            + Columns.DEVICE_ID + " TEXT NOT NULL,"
            + Columns.OP_CODE + " INTEGER,"
            + Columns.ATTRIBUTION_TAG + " TEXT,"
            + Columns.ACCESS_TIME + " INTEGER,"
            + Columns.ACCESS_DURATION + " INTEGER,"
            + Columns.UID_STATE + " INTEGER,"
            + Columns.OP_FLAGS + " INTEGER,"
            + Columns.ATTRIBUTION_FLAGS + " INTEGER,"
            + Columns.CHAIN_ID + " INTEGER"
            + ")";

    static final String INSERT_TABLE_SQL = "INSERT INTO " + TABLE_NAME + "("
            + Columns.UID + ", "
            + Columns.PACKAGE_NAME + ", "
            + Columns.DEVICE_ID + ", "
            + Columns.OP_CODE + ", "
            + Columns.ATTRIBUTION_TAG + ", "
            + Columns.ACCESS_TIME + ", "
            + Columns.ACCESS_DURATION + ", "
            + Columns.UID_STATE + ", "
            + Columns.OP_FLAGS + ", "
            + Columns.ATTRIBUTION_FLAGS + ", "
            + Columns.CHAIN_ID + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT_MAX_ATTRIBUTION_CHAIN_ID = "SELECT MAX(" + Columns.CHAIN_ID + ")"
            + " FROM " + TABLE_NAME;

    static final String SELECT_TABLE_DATA = "SELECT DISTINCT "
            + Columns.UID + ","
            + Columns.PACKAGE_NAME + ","
            + Columns.DEVICE_ID + ","
            + Columns.OP_CODE + ","
            + Columns.ATTRIBUTION_TAG + ","
            + Columns.ACCESS_TIME + ","
            + Columns.ACCESS_DURATION + ","
            + Columns.UID_STATE + ","
            + Columns.OP_FLAGS + ","
            + Columns.ATTRIBUTION_FLAGS + ","
            + Columns.CHAIN_ID
            + " FROM " + TABLE_NAME;

    static final String DELETE_TABLE_DATA = "DELETE FROM " + TABLE_NAME;

    static final String DELETE_TABLE_DATA_BEFORE_ACCESS_TIME = "DELETE FROM " + TABLE_NAME
            + " WHERE " + Columns.ACCESS_TIME + " < ?";

    static final String DELETE_DATA_FOR_UID_PACKAGE = "DELETE FROM " + DiscreteOpsTable.TABLE_NAME
            + " WHERE " + Columns.UID + " = ? AND " + Columns.PACKAGE_NAME + " = ?";

    static final String OFFSET_ACCESS_TIME = "UPDATE " + DiscreteOpsTable.TABLE_NAME
            + " SET " + Columns.ACCESS_TIME + " = ACCESS_TIME - ?";

    // Index on access time, uid and op code
    static final String CREATE_INDEX_SQL = "CREATE INDEX IF NOT EXISTS "
            + INDEX_APP_OP + " ON " + TABLE_NAME
            + " (" + Columns.ACCESS_TIME + ", " + Columns.UID + ", " + Columns.OP_CODE + ")";
}
