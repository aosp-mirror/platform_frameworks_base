/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content;

import com.android.internal.util.ArrayUtils;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.accounts.Account;

/**
 * Extends the schema of a ContentProvider to include the _sync_state table
 * and implements query/insert/update/delete to access that table using the
 * authority "syncstate". This can be used to store the sync state for a
 * set of accounts.
 * 
 * @hide
 */
public class SyncStateContentProviderHelper {
    final SQLiteOpenHelper mOpenHelper;

    private static final String SYNC_STATE_AUTHORITY = "syncstate";
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int STATE = 0;

    private static final Uri CONTENT_URI =
            Uri.parse("content://" + SYNC_STATE_AUTHORITY + "/state");

    private static final String ACCOUNT_WHERE = "_sync_account = ? AND _sync_account_type = ?";

    private final Provider mInternalProviderInterface;

    private static final String SYNC_STATE_TABLE = "_sync_state";
    private static long DB_VERSION = 3;

    private static final String[] ACCOUNT_PROJECTION =
            new String[]{"_sync_account", "_sync_account_type"};

    static {
        sURIMatcher.addURI(SYNC_STATE_AUTHORITY, "state", STATE);
    }

    public SyncStateContentProviderHelper(SQLiteOpenHelper openHelper) {
        mOpenHelper = openHelper;
        mInternalProviderInterface = new Provider();
    }

    public ContentProvider asContentProvider() {
        return mInternalProviderInterface;
    }

    public void createDatabase(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS _sync_state");
        db.execSQL("CREATE TABLE _sync_state (" +
                   "_id INTEGER PRIMARY KEY," +
                   "_sync_account TEXT," +
                   "_sync_account_type TEXT," +
                   "data TEXT," +
                   "UNIQUE(_sync_account, _sync_account_type)" +
                   ");");

        db.execSQL("DROP TABLE IF EXISTS _sync_state_metadata");
        db.execSQL("CREATE TABLE _sync_state_metadata (" +
                    "version INTEGER" +
                    ");");
        ContentValues values = new ContentValues();
        values.put("version", DB_VERSION);
        db.insert("_sync_state_metadata", "version", values);
    }

    protected void onDatabaseOpened(SQLiteDatabase db) {
        long version = DatabaseUtils.longForQuery(db,
                "select version from _sync_state_metadata", null);
        if (version != DB_VERSION) {
            createDatabase(db);
        }
    }

    class Provider extends ContentProvider {
        public boolean onCreate() {
            throw new UnsupportedOperationException("not implemented");
        }

        public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            SQLiteDatabase db = mOpenHelper.getReadableDatabase();
            int match = sURIMatcher.match(url);
            switch (match) {
                case STATE:
                    return db.query(SYNC_STATE_TABLE, projection, selection, selectionArgs,
                            null, null, sortOrder);
                default:
                    throw new UnsupportedOperationException("Cannot query URL: " + url);
            }
        }

        public String getType(Uri uri) {
            throw new UnsupportedOperationException("not implemented");
        }

        public Uri insert(Uri url, ContentValues values) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            int match = sURIMatcher.match(url);
            switch (match) {
                case STATE: {
                    long id = db.insert(SYNC_STATE_TABLE, "feed", values);
                    return CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).build();
                }
                default:
                    throw new UnsupportedOperationException("Cannot insert into URL: " + url);
            }
        }

        public int delete(Uri url, String userWhere, String[] whereArgs) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            switch (sURIMatcher.match(url)) {
                case STATE:
                    return db.delete(SYNC_STATE_TABLE, userWhere, whereArgs);
                default:
                    throw new IllegalArgumentException("Unknown URL " + url);
            }

        }

        public int update(Uri url, ContentValues values, String selection, String[] selectionArgs) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            switch (sURIMatcher.match(url)) {
                case STATE:
                    return db.update(SYNC_STATE_TABLE, values, selection, selectionArgs);
                default:
                    throw new UnsupportedOperationException("Cannot update URL: " + url);
            }

        }
    }

    /**
     * Check if the url matches content that this ContentProvider manages.
     * @param url the Uri to check
     * @return true if this ContentProvider can handle that Uri.
     */
    public boolean matches(Uri url) {
        return (SYNC_STATE_AUTHORITY.equals(url.getAuthority()));
    }

    /**
     * Replaces the contents of the _sync_state table in the destination ContentProvider
     * with the row that matches account, if any, in the source ContentProvider.
     * <p>
     * The ContentProviders must expose the _sync_state table as URI content://syncstate/state.
     * @param dbSrc the database to read from
     * @param dbDest the database to write to
     * @param account the account of the row that should be copied over.
     */
    public void copySyncState(SQLiteDatabase dbSrc, SQLiteDatabase dbDest,
            Account account) {
        final String[] whereArgs = new String[]{account.name, account.type};
        Cursor c = dbSrc.query(SYNC_STATE_TABLE,
                new String[]{"_sync_account", "_sync_account_type", "data"},
                ACCOUNT_WHERE, whereArgs, null, null, null);
        try {
            if (c.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put("_sync_account", c.getString(0));
                values.put("_sync_account_type", c.getString(1));
                values.put("data", c.getBlob(2));
                dbDest.replace(SYNC_STATE_TABLE, "_sync_account", values);
            }
        } finally {
            c.close();
        }
    }

    public void onAccountsChanged(Account[] accounts) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor c = db.query(SYNC_STATE_TABLE, ACCOUNT_PROJECTION, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                final String accountName = c.getString(0);
                final String accountType = c.getString(1);
                Account account = new Account(accountName, accountType);
                if (!ArrayUtils.contains(accounts, account)) {
                    db.delete(SYNC_STATE_TABLE, ACCOUNT_WHERE,
                            new String[]{accountName, accountType});
                }
            }
        } finally {
            c.close();
        }
    }

    public void discardSyncData(SQLiteDatabase db, Account account) {
        if (account != null) {
            db.delete(SYNC_STATE_TABLE, ACCOUNT_WHERE, new String[]{account.name, account.type});
        } else {
            db.delete(SYNC_STATE_TABLE, null, null);
        }
    }

    /**
     * Retrieves the SyncData bytes for the given account. The byte array returned may be null.
     */
    public byte[] readSyncDataBytes(SQLiteDatabase db, Account account) {
        Cursor c = db.query(SYNC_STATE_TABLE, null, ACCOUNT_WHERE,
                new String[]{account.name, account.type}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return c.getBlob(c.getColumnIndexOrThrow("data"));
            }
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * Sets the SyncData bytes for the given account. The bytes array may be null.
     */
    public void writeSyncDataBytes(SQLiteDatabase db, Account account, byte[] data) {
        ContentValues values = new ContentValues();
        values.put("data", data);
        db.update(SYNC_STATE_TABLE, values, ACCOUNT_WHERE,
                new String[]{account.name, account.type});
    }
}
