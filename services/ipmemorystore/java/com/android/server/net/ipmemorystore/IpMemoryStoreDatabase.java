/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.net.ipmemorystore;

import android.annotation.NonNull;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Encapsulating class for using the SQLite database backing the memory store.
 *
 * This class groups together the contracts and the SQLite helper used to
 * use the database.
 *
 * @hide
 */
public class IpMemoryStoreDatabase {
    /**
     * Contract class for the Network Attributes table.
     */
    public static class NetworkAttributesContract {
        public static final String TABLENAME = "NetworkAttributes";

        public static final String COLNAME_L2KEY = "l2Key";
        public static final String COLTYPE_L2KEY = "TEXT NOT NULL";

        public static final String COLNAME_EXPIRYDATE = "expiryDate";
        // Milliseconds since the Epoch, in true Java style
        public static final String COLTYPE_EXPIRYDATE = "BIGINT";

        public static final String COLNAME_ASSIGNEDV4ADDRESS = "assignedV4Address";
        public static final String COLTYPE_ASSIGNEDV4ADDRESS = "INTEGER";

        // Please note that the group hint is only a *hint*, hence its name. The client can offer
        // this information to nudge the grouping in the decision it thinks is right, but it can't
        // decide for the memory store what is the same L3 network.
        public static final String COLNAME_GROUPHINT = "groupHint";
        public static final String COLTYPE_GROUPHINT = "TEXT";

        public static final String COLNAME_DNSADDRESSES = "dnsAddresses";
        // Stored in marshalled form as is
        public static final String COLTYPE_DNSADDRESSES = "BLOB";

        public static final String COLNAME_MTU = "mtu";
        public static final String COLTYPE_MTU = "INTEGER";

        public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS "
                + TABLENAME                 + " ("
                + COLNAME_L2KEY             + " " + COLTYPE_L2KEY + " PRIMARY KEY NOT NULL, "
                + COLNAME_EXPIRYDATE        + " " + COLTYPE_EXPIRYDATE        + ", "
                + COLNAME_ASSIGNEDV4ADDRESS + " " + COLTYPE_ASSIGNEDV4ADDRESS + ", "
                + COLNAME_GROUPHINT         + " " + COLTYPE_GROUPHINT         + ", "
                + COLNAME_DNSADDRESSES      + " " + COLTYPE_DNSADDRESSES      + ", "
                + COLNAME_MTU               + " " + COLTYPE_MTU               + ")";
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLENAME;
    }

    /**
     * Contract class for the Private Data table.
     */
    public static class PrivateDataContract {
        public static final String TABLENAME = "PrivateData";

        public static final String COLNAME_L2KEY = "l2Key";
        public static final String COLTYPE_L2KEY = "TEXT NOT NULL";

        public static final String COLNAME_CLIENT = "client";
        public static final String COLTYPE_CLIENT = "TEXT NOT NULL";

        public static final String COLNAME_DATANAME = "dataName";
        public static final String COLTYPE_DATANAME = "TEXT NOT NULL";

        public static final String COLNAME_DATA = "data";
        public static final String COLTYPE_DATA = "BLOB NOT NULL";

        public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS "
                + TABLENAME        + " ("
                + COLNAME_L2KEY    + " " + COLTYPE_L2KEY    + ", "
                + COLNAME_CLIENT   + " " + COLTYPE_CLIENT   + ", "
                + COLNAME_DATANAME + " " + COLTYPE_DATANAME + ", "
                + COLNAME_DATA     + " " + COLTYPE_DATA     + ", "
                + "PRIMARY KEY ("
                + COLNAME_L2KEY    + ", "
                + COLNAME_CLIENT   + ", "
                + COLNAME_DATANAME + "))";
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLENAME;
    }

    // To save memory when the DB is not used, close it after 30s of inactivity. This is
    // determined manually based on what feels right.
    private static final long IDLE_CONNECTION_TIMEOUT_MS = 30_000;

    /** The SQLite DB helper */
    public static class DbHelper extends SQLiteOpenHelper {
        // Update this whenever changing the schema.
        private static final int SCHEMA_VERSION = 1;
        private static final String DATABASE_FILENAME = "IpMemoryStore.db";

        public DbHelper(@NonNull final Context context) {
            super(context, DATABASE_FILENAME, null, SCHEMA_VERSION);
            setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
        }

        /** Called when the database is created */
        public void onCreate(@NonNull final SQLiteDatabase db) {
            db.execSQL(NetworkAttributesContract.CREATE_TABLE);
            db.execSQL(PrivateDataContract.CREATE_TABLE);
        }

        /** Called when the database is upgraded */
        public void onUpgrade(@NonNull final SQLiteDatabase db, final int oldVersion,
                final int newVersion) {
            // No upgrade supported yet.
            db.execSQL(NetworkAttributesContract.DROP_TABLE);
            db.execSQL(PrivateDataContract.DROP_TABLE);
            onCreate(db);
        }

        /** Called when the database is downgraded */
        public void onDowngrade(@NonNull final SQLiteDatabase db, final int oldVersion,
                final int newVersion) {
            // Downgrades always nuke all data and recreate an empty table.
            db.execSQL(NetworkAttributesContract.DROP_TABLE);
            db.execSQL(PrivateDataContract.DROP_TABLE);
            onCreate(db);
        }
    }
}
