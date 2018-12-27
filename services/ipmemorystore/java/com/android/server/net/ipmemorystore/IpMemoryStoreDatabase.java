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
import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.NetworkUtils;
import android.net.ipmemorystore.NetworkAttributes;
import android.net.ipmemorystore.Status;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulating class for using the SQLite database backing the memory store.
 *
 * This class groups together the contracts and the SQLite helper used to
 * use the database.
 *
 * @hide
 */
public class IpMemoryStoreDatabase {
    private static final String TAG = IpMemoryStoreDatabase.class.getSimpleName();

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
        public static final String COLTYPE_MTU = "INTEGER DEFAULT -1";

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
        private static final int SCHEMA_VERSION = 2;
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

    @NonNull
    private static byte[] encodeAddressList(@NonNull final List<InetAddress> addresses) {
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        for (final InetAddress address : addresses) {
            final byte[] b = address.getAddress();
            os.write(b.length);
            os.write(b, 0, b.length);
        }
        return os.toByteArray();
    }

    @NonNull
    private static ArrayList<InetAddress> decodeAddressList(@NonNull final byte[] encoded) {
        final ByteArrayInputStream is = new ByteArrayInputStream(encoded);
        final ArrayList<InetAddress> addresses = new ArrayList<>();
        int d = -1;
        while ((d = is.read()) != -1) {
            final byte[] bytes = new byte[d];
            is.read(bytes, 0, d);
            try {
                addresses.add(InetAddress.getByAddress(bytes));
            } catch (UnknownHostException e) { /* Hopefully impossible */ }
        }
        return addresses;
    }

    // Convert a NetworkAttributes object to content values to store them in a table compliant
    // with the contract defined in NetworkAttributesContract.
    @NonNull
    private static ContentValues toContentValues(@NonNull final String key,
            @Nullable final NetworkAttributes attributes, final long expiry) {
        final ContentValues values = new ContentValues();
        values.put(NetworkAttributesContract.COLNAME_L2KEY, key);
        values.put(NetworkAttributesContract.COLNAME_EXPIRYDATE, expiry);
        if (null != attributes) {
            if (null != attributes.assignedV4Address) {
                values.put(NetworkAttributesContract.COLNAME_ASSIGNEDV4ADDRESS,
                        NetworkUtils.inet4AddressToIntHTH(attributes.assignedV4Address));
            }
            if (null != attributes.groupHint) {
                values.put(NetworkAttributesContract.COLNAME_GROUPHINT, attributes.groupHint);
            }
            if (null != attributes.dnsAddresses) {
                values.put(NetworkAttributesContract.COLNAME_DNSADDRESSES,
                        encodeAddressList(attributes.dnsAddresses));
            }
            if (null != attributes.mtu) {
                values.put(NetworkAttributesContract.COLNAME_MTU, attributes.mtu);
            }
        }
        return values;
    }

    // Convert a byte array into content values to store it in a table compliant with the
    // contract defined in PrivateDataContract.
    @NonNull
    private static ContentValues toContentValues(@NonNull final String key,
            @NonNull final String clientId, @NonNull final String name,
            @NonNull final byte[] data) {
        final ContentValues values = new ContentValues();
        values.put(PrivateDataContract.COLNAME_L2KEY, key);
        values.put(PrivateDataContract.COLNAME_CLIENT, clientId);
        values.put(PrivateDataContract.COLNAME_DATANAME, name);
        values.put(PrivateDataContract.COLNAME_DATA, data);
        return values;
    }

    private static final String[] EXPIRY_COLUMN = new String[] {
        NetworkAttributesContract.COLNAME_EXPIRYDATE
    };
    static final int EXPIRY_ERROR = -1; // Legal values for expiry are positive

    static final String SELECT_L2KEY = NetworkAttributesContract.COLNAME_L2KEY + " = ?";

    // Returns the expiry date of the specified row, or one of the error codes above if the
    // row is not found or some other error
    static long getExpiry(@NonNull final SQLiteDatabase db, @NonNull final String key) {
        final Cursor cursor = db.query(NetworkAttributesContract.TABLENAME,
                EXPIRY_COLUMN, // columns
                SELECT_L2KEY, // selection
                new String[] { key }, // selectionArgs
                null, // groupBy
                null, // having
                null // orderBy
        );
        // L2KEY is the primary key ; it should not be possible to get more than one
        // result here. 0 results means the key was not found.
        if (cursor.getCount() != 1) return EXPIRY_ERROR;
        cursor.moveToFirst();
        return cursor.getLong(0); // index in the EXPIRY_COLUMN array
    }

    static final int RELEVANCE_ERROR = -1; // Legal values for relevance are positive

    // Returns the relevance of the specified row, or one of the error codes above if the
    // row is not found or some other error
    static int getRelevance(@NonNull final SQLiteDatabase db, @NonNull final String key) {
        final long expiry = getExpiry(db, key);
        return expiry < 0 ? (int) expiry : RelevanceUtils.computeRelevanceForNow(expiry);
    }

    // If the attributes are null, this will only write the expiry.
    // Returns an int out of Status.{SUCCESS,ERROR_*}
    static int storeNetworkAttributes(@NonNull final SQLiteDatabase db, @NonNull final String key,
            final long expiry, @Nullable final NetworkAttributes attributes) {
        final ContentValues cv = toContentValues(key, attributes, expiry);
        db.beginTransaction();
        try {
            // Unfortunately SQLite does not have any way to do INSERT OR UPDATE. Options are
            // to either insert with on conflict ignore then update (like done here), or to
            // construct a custom SQL INSERT statement with nested select.
            final long resultId = db.insertWithOnConflict(NetworkAttributesContract.TABLENAME,
                    null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            if (resultId < 0) {
                db.update(NetworkAttributesContract.TABLENAME, cv, SELECT_L2KEY, new String[]{key});
            }
            db.setTransactionSuccessful();
            return Status.SUCCESS;
        } catch (SQLiteException e) {
            // No space left on disk or something
            Log.e(TAG, "Could not write to the memory store", e);
        } finally {
            db.endTransaction();
        }
        return Status.ERROR_STORAGE;
    }

    // Returns an int out of Status.{SUCCESS,ERROR_*}
    static int storeBlob(@NonNull final SQLiteDatabase db, @NonNull final String key,
            @NonNull final String clientId, @NonNull final String name,
            @NonNull final byte[] data) {
        final long res = db.insertWithOnConflict(PrivateDataContract.TABLENAME, null,
                toContentValues(key, clientId, name, data), SQLiteDatabase.CONFLICT_REPLACE);
        return (res == -1) ? Status.ERROR_STORAGE : Status.SUCCESS;
    }

    @Nullable
    static NetworkAttributes retrieveNetworkAttributes(@NonNull final SQLiteDatabase db,
            @NonNull final String key) {
        final Cursor cursor = db.query(NetworkAttributesContract.TABLENAME,
                null, // columns, null means everything
                NetworkAttributesContract.COLNAME_L2KEY + " = ?", // selection
                new String[] { key }, // selectionArgs
                null, // groupBy
                null, // having
                null); // orderBy
        // L2KEY is the primary key ; it should not be possible to get more than one
        // result here. 0 results means the key was not found.
        if (cursor.getCount() != 1) return null;
        cursor.moveToFirst();

        // Make sure the data hasn't expired
        final long expiry = cursor.getLong(
                cursor.getColumnIndexOrThrow(NetworkAttributesContract.COLNAME_EXPIRYDATE));
        if (expiry < System.currentTimeMillis()) return null;

        final NetworkAttributes.Builder builder = new NetworkAttributes.Builder();
        final int assignedV4AddressInt = getInt(cursor,
                NetworkAttributesContract.COLNAME_ASSIGNEDV4ADDRESS, 0);
        final String groupHint = getString(cursor, NetworkAttributesContract.COLNAME_GROUPHINT);
        final byte[] dnsAddressesBlob =
                getBlob(cursor, NetworkAttributesContract.COLNAME_DNSADDRESSES);
        final int mtu = getInt(cursor, NetworkAttributesContract.COLNAME_MTU, -1);
        if (0 != assignedV4AddressInt) {
            builder.setAssignedV4Address(NetworkUtils.intToInet4AddressHTH(assignedV4AddressInt));
        }
        builder.setGroupHint(groupHint);
        if (null != dnsAddressesBlob) {
            builder.setDnsAddresses(decodeAddressList(dnsAddressesBlob));
        }
        if (mtu >= 0) {
            builder.setMtu(mtu);
        }
        return builder.build();
    }

    private static final String[] DATA_COLUMN = new String[] {
            PrivateDataContract.COLNAME_DATA
    };
    @Nullable
    static byte[] retrieveBlob(@NonNull final SQLiteDatabase db, @NonNull final String key,
            @NonNull final String clientId, @NonNull final String name) {
        final Cursor cursor = db.query(PrivateDataContract.TABLENAME,
                DATA_COLUMN, // columns
                PrivateDataContract.COLNAME_L2KEY + " = ? AND " // selection
                + PrivateDataContract.COLNAME_CLIENT + " = ? AND "
                + PrivateDataContract.COLNAME_DATANAME + " = ?",
                new String[] { key, clientId, name }, // selectionArgs
                null, // groupBy
                null, // having
                null); // orderBy
        // The query above is querying by (composite) primary key, so it should not be possible to
        // get more than one result here. 0 results means the key was not found.
        if (cursor.getCount() != 1) return null;
        cursor.moveToFirst();
        return cursor.getBlob(0); // index in the DATA_COLUMN array
    }

    // Helper methods
    static String getString(final Cursor cursor, final String columnName) {
        final int columnIndex = cursor.getColumnIndex(columnName);
        return (columnIndex >= 0) ? cursor.getString(columnIndex) : null;
    }
    static byte[] getBlob(final Cursor cursor, final String columnName) {
        final int columnIndex = cursor.getColumnIndex(columnName);
        return (columnIndex >= 0) ? cursor.getBlob(columnIndex) : null;
    }
    static int getInt(final Cursor cursor, final String columnName, final int defaultValue) {
        final int columnIndex = cursor.getColumnIndex(columnName);
        return (columnIndex >= 0) ? cursor.getInt(columnIndex) : defaultValue;
    }
}
