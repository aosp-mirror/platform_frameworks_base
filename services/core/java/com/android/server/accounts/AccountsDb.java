/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.accounts;

import android.accounts.Account;
import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.FileUtils;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence layer abstraction for accessing accounts_ce/accounts_de databases.
 *
 * <p>At first, CE database needs to be {@link #attachCeDatabase(File) attached to DE},
 * in order for the tables to be available. All operations with CE database are done through the
 * connection to the DE database, to which it is attached. This approach allows atomic
 * transactions across two databases</p>
 */
class AccountsDb implements AutoCloseable {
    private static final String TAG = "AccountsDb";

    private static final String DATABASE_NAME = "accounts.db";
    private static final int PRE_N_DATABASE_VERSION = 9;
    private static final int CE_DATABASE_VERSION = 10;
    private static final int DE_DATABASE_VERSION = 3; // Added visibility support in O

    static final String TABLE_ACCOUNTS = "accounts";
    private static final String ACCOUNTS_ID = "_id";
    private static final String ACCOUNTS_NAME = "name";
    private static final String ACCOUNTS_TYPE = "type";
    private static final String ACCOUNTS_TYPE_COUNT = "count(type)";
    private static final String ACCOUNTS_PASSWORD = "password";
    private static final String ACCOUNTS_PREVIOUS_NAME = "previous_name";
    private static final String ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS =
            "last_password_entry_time_millis_epoch";

    private static final String TABLE_AUTHTOKENS = "authtokens";
    private static final String AUTHTOKENS_ID = "_id";
    private static final String AUTHTOKENS_ACCOUNTS_ID = "accounts_id";
    private static final String AUTHTOKENS_TYPE = "type";
    private static final String AUTHTOKENS_AUTHTOKEN = "authtoken";

    private static final String TABLE_VISIBILITY = "visibility";
    private static final String VISIBILITY_ACCOUNTS_ID = "accounts_id";
    private static final String VISIBILITY_PACKAGE = "_package";
    private static final String VISIBILITY_VALUE = "value";

    private static final String TABLE_GRANTS = "grants";
    private static final String GRANTS_ACCOUNTS_ID = "accounts_id";
    private static final String GRANTS_AUTH_TOKEN_TYPE = "auth_token_type";
    private static final String GRANTS_GRANTEE_UID = "uid";

    private static final String TABLE_EXTRAS = "extras";
    private static final String EXTRAS_ID = "_id";
    private static final String EXTRAS_ACCOUNTS_ID = "accounts_id";
    private static final String EXTRAS_KEY = "key";
    private static final String EXTRAS_VALUE = "value";

    private static final String TABLE_META = "meta";
    private static final String META_KEY = "key";
    private static final String META_VALUE = "value";

    static final String TABLE_SHARED_ACCOUNTS = "shared_accounts";
    private static final String SHARED_ACCOUNTS_ID = "_id";

    private static String TABLE_DEBUG = "debug_table";

    // Columns for debug_table table
    private static String DEBUG_TABLE_ACTION_TYPE = "action_type";
    private static String DEBUG_TABLE_TIMESTAMP = "time";
    private static String DEBUG_TABLE_CALLER_UID = "caller_uid";
    private static String DEBUG_TABLE_TABLE_NAME = "table_name";
    private static String DEBUG_TABLE_KEY = "primary_key";

    // These actions correspond to the occurrence of real actions. Since
    // these are called by the authenticators, the uid associated will be
    // of the authenticator.
    static String DEBUG_ACTION_SET_PASSWORD = "action_set_password";
    static String DEBUG_ACTION_CLEAR_PASSWORD = "action_clear_password";
    static String DEBUG_ACTION_ACCOUNT_ADD = "action_account_add";
    static String DEBUG_ACTION_ACCOUNT_REMOVE = "action_account_remove";
    static String DEBUG_ACTION_ACCOUNT_REMOVE_DE = "action_account_remove_de";
    static String DEBUG_ACTION_AUTHENTICATOR_REMOVE = "action_authenticator_remove";
    static String DEBUG_ACTION_ACCOUNT_RENAME = "action_account_rename";

    // These actions don't necessarily correspond to any action on
    // accountDb taking place. As an example, there might be a request for
    // addingAccount, which might not lead to addition of account on grounds
    // of bad authentication. We will still be logging it to keep track of
    // who called.
    static String DEBUG_ACTION_CALLED_ACCOUNT_ADD = "action_called_account_add";
    static String DEBUG_ACTION_CALLED_ACCOUNT_REMOVE = "action_called_account_remove";
    static String DEBUG_ACTION_SYNC_DE_CE_ACCOUNTS = "action_sync_de_ce_accounts";

    //This action doesn't add account to accountdb. Account is only
    // added in finishSession which may be in a different user profile.
    static String DEBUG_ACTION_CALLED_START_ACCOUNT_ADD = "action_called_start_account_add";
    static String DEBUG_ACTION_CALLED_ACCOUNT_SESSION_FINISH =
            "action_called_account_session_finish";

    static final String CE_DATABASE_NAME = "accounts_ce.db";
    static final String DE_DATABASE_NAME = "accounts_de.db";
    private static final String CE_DB_PREFIX = "ceDb.";
    private static final String CE_TABLE_ACCOUNTS = CE_DB_PREFIX + TABLE_ACCOUNTS;
    private static final String CE_TABLE_AUTHTOKENS = CE_DB_PREFIX + TABLE_AUTHTOKENS;
    private static final String CE_TABLE_EXTRAS = CE_DB_PREFIX + TABLE_EXTRAS;

    static final int MAX_DEBUG_DB_SIZE = 64;

    private static final String[] ACCOUNT_TYPE_COUNT_PROJECTION =
            new String[] { ACCOUNTS_TYPE, ACCOUNTS_TYPE_COUNT};

    private static final String COUNT_OF_MATCHING_GRANTS = ""
            + "SELECT COUNT(*) FROM " + TABLE_GRANTS + ", " + TABLE_ACCOUNTS
            + " WHERE " + GRANTS_ACCOUNTS_ID + "=" + ACCOUNTS_ID
            + " AND " + GRANTS_GRANTEE_UID + "=?"
            + " AND " + GRANTS_AUTH_TOKEN_TYPE + "=?"
            + " AND " + ACCOUNTS_NAME + "=?"
            + " AND " + ACCOUNTS_TYPE + "=?";

    private static final String COUNT_OF_MATCHING_GRANTS_ANY_TOKEN = ""
            + "SELECT COUNT(*) FROM " + TABLE_GRANTS + ", " + TABLE_ACCOUNTS
            + " WHERE " + GRANTS_ACCOUNTS_ID + "=" + ACCOUNTS_ID
            + " AND " + GRANTS_GRANTEE_UID + "=?"
            + " AND " + ACCOUNTS_NAME + "=?"
            + " AND " + ACCOUNTS_TYPE + "=?";

    private static final String SELECTION_ACCOUNTS_ID_BY_ACCOUNT =
        "accounts_id=(select _id FROM accounts WHERE name=? AND type=?)";

    private static final String[] COLUMNS_AUTHTOKENS_TYPE_AND_AUTHTOKEN =
            {AUTHTOKENS_TYPE, AUTHTOKENS_AUTHTOKEN};

    private static final String[] COLUMNS_EXTRAS_KEY_AND_VALUE = {EXTRAS_KEY, EXTRAS_VALUE};

    private static final String ACCOUNT_ACCESS_GRANTS = ""
            + "SELECT " + AccountsDb.ACCOUNTS_NAME + ", "
            + AccountsDb.GRANTS_GRANTEE_UID
            + " FROM " + AccountsDb.TABLE_ACCOUNTS
            + ", " + AccountsDb.TABLE_GRANTS
            + " WHERE " + AccountsDb.GRANTS_ACCOUNTS_ID
            + "=" + AccountsDb.ACCOUNTS_ID;

    private static final String META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX =
            "auth_uid_for_type:";
    private static final String META_KEY_DELIMITER = ":";
    private static final String SELECTION_META_BY_AUTHENTICATOR_TYPE = META_KEY + " LIKE ?";

    private final DeDatabaseHelper mDeDatabase;
    private final Context mContext;
    private final File mPreNDatabaseFile;

    final Object mDebugStatementLock = new Object();
    private volatile long mDebugDbInsertionPoint = -1;
    private volatile SQLiteStatement mDebugStatementForLogging; // not thread safe.

    AccountsDb(DeDatabaseHelper deDatabase, Context context, File preNDatabaseFile) {
        mDeDatabase = deDatabase;
        mContext = context;
        mPreNDatabaseFile = preNDatabaseFile;
    }

    private static class CeDatabaseHelper extends SQLiteOpenHelper {

        CeDatabaseHelper(Context context, String ceDatabaseName) {
            super(context, ceDatabaseName, null, CE_DATABASE_VERSION);
        }

        /**
         * This call needs to be made while the mCacheLock is held.
         * @param db The database.
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "Creating CE database " + getDatabaseName());
            db.execSQL("CREATE TABLE " + TABLE_ACCOUNTS + " ( "
                    + ACCOUNTS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + ACCOUNTS_NAME + " TEXT NOT NULL, "
                    + ACCOUNTS_TYPE + " TEXT NOT NULL, "
                    + ACCOUNTS_PASSWORD + " TEXT, "
                    + "UNIQUE(" + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + "))");

            db.execSQL("CREATE TABLE " + TABLE_AUTHTOKENS + " (  "
                    + AUTHTOKENS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,  "
                    + AUTHTOKENS_ACCOUNTS_ID + " INTEGER NOT NULL, "
                    + AUTHTOKENS_TYPE + " TEXT NOT NULL,  "
                    + AUTHTOKENS_AUTHTOKEN + " TEXT,  "
                    + "UNIQUE (" + AUTHTOKENS_ACCOUNTS_ID + "," + AUTHTOKENS_TYPE + "))");

            db.execSQL("CREATE TABLE " + TABLE_EXTRAS + " ( "
                    + EXTRAS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + EXTRAS_ACCOUNTS_ID + " INTEGER, "
                    + EXTRAS_KEY + " TEXT NOT NULL, "
                    + EXTRAS_VALUE + " TEXT, "
                    + "UNIQUE(" + EXTRAS_ACCOUNTS_ID + "," + EXTRAS_KEY + "))");

            createAccountsDeletionTrigger(db);
        }

        private void createAccountsDeletionTrigger(SQLiteDatabase db) {
            db.execSQL(""
                    + " CREATE TRIGGER " + TABLE_ACCOUNTS + "Delete DELETE ON " + TABLE_ACCOUNTS
                    + " BEGIN"
                    + "   DELETE FROM " + TABLE_AUTHTOKENS
                    + "     WHERE " + AUTHTOKENS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + "   DELETE FROM " + TABLE_EXTRAS
                    + "     WHERE " + EXTRAS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + " END");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "Upgrade CE from version " + oldVersion + " to version " + newVersion);

            if (oldVersion == 9) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "onUpgrade upgrading to v10");
                }
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_META);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_SHARED_ACCOUNTS);
                // Recreate the trigger, since the old one references the table to be removed
                db.execSQL("DROP TRIGGER IF EXISTS " + TABLE_ACCOUNTS + "Delete");
                createAccountsDeletionTrigger(db);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_GRANTS);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_DEBUG);
                oldVersion++;
            }

            if (oldVersion != newVersion) {
                Log.e(TAG, "failed to upgrade version " + oldVersion + " to version " + newVersion);
            }
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.e(TAG, "onDowngrade: recreate accounts CE table");
            resetDatabase(db);
            onCreate(db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opened database " + CE_DATABASE_NAME);
        }


        /**
         * Creates a new {@code CeDatabaseHelper}. If pre-N db file is present at the old location,
         * it also performs migration to the new CE database.
         */
        static CeDatabaseHelper create(
                Context context,
                File preNDatabaseFile,
                File ceDatabaseFile) {
            boolean newDbExists = ceDatabaseFile.exists();
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "CeDatabaseHelper.create ceDatabaseFile=" + ceDatabaseFile
                        + " oldDbExists=" + preNDatabaseFile.exists()
                        + " newDbExists=" + newDbExists);
            }
            boolean removeOldDb = false;
            if (!newDbExists && preNDatabaseFile.exists()) {
                removeOldDb = migratePreNDbToCe(preNDatabaseFile, ceDatabaseFile);
            }
            // Try to open and upgrade if necessary
            CeDatabaseHelper ceHelper = new CeDatabaseHelper(context, ceDatabaseFile.getPath());
            ceHelper.getWritableDatabase();
            ceHelper.close();
            if (removeOldDb) {
                Slog.i(TAG, "Migration complete - removing pre-N db " + preNDatabaseFile);
                if (!SQLiteDatabase.deleteDatabase(preNDatabaseFile)) {
                    Slog.e(TAG, "Cannot remove pre-N db " + preNDatabaseFile);
                }
            }
            return ceHelper;
        }

        private static boolean migratePreNDbToCe(File oldDbFile, File ceDbFile) {
            Slog.i(TAG, "Moving pre-N DB " + oldDbFile + " to CE " + ceDbFile);
            try {
                FileUtils.copyFileOrThrow(oldDbFile, ceDbFile);
            } catch (IOException e) {
                Slog.e(TAG, "Cannot copy file to " + ceDbFile + " from " + oldDbFile, e);
                // Try to remove potentially damaged file if I/O error occurred
                deleteDbFileWarnIfFailed(ceDbFile);
                return false;
            }
            return true;
        }
    }

    /**
     * Returns information about auth tokens and their account for the specified query
     * parameters.
     * Output is in the format:
     * <pre><code> | AUTHTOKEN_ID |  ACCOUNT_NAME | AUTH_TOKEN_TYPE |</code></pre>
     */
    Cursor findAuthtokenForAllAccounts(String accountType, String authToken) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        return db.rawQuery(
                "SELECT " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_ID
                        + ", " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_NAME
                        + ", " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_TYPE
                        + " FROM " + CE_TABLE_ACCOUNTS
                        + " JOIN " + CE_TABLE_AUTHTOKENS
                        + " ON " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_ID
                        + " = " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_ACCOUNTS_ID
                        + " WHERE " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_AUTHTOKEN
                        + " = ? AND " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_TYPE + " = ?",
                new String[]{authToken, accountType});
    }

    Map<String, String> findAuthTokensByAccount(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        HashMap<String, String> authTokensForAccount = new HashMap<>();
        Cursor cursor = db.query(CE_TABLE_AUTHTOKENS,
                COLUMNS_AUTHTOKENS_TYPE_AND_AUTHTOKEN,
                SELECTION_ACCOUNTS_ID_BY_ACCOUNT,
                new String[] {account.name, account.type},
                null, null, null);
        try {
            while (cursor.moveToNext()) {
                final String type = cursor.getString(0);
                final String authToken = cursor.getString(1);
                authTokensForAccount.put(type, authToken);
            }
        } finally {
            cursor.close();
        }
        return authTokensForAccount;
    }

    boolean deleteAuthtokensByAccountIdAndType(long accountId, String authtokenType) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        return db.delete(CE_TABLE_AUTHTOKENS,
                AUTHTOKENS_ACCOUNTS_ID + "=?" + " AND " + AUTHTOKENS_TYPE + "=?",
                new String[]{String.valueOf(accountId), authtokenType}) > 0;
    }

    boolean deleteAuthToken(String authTokenId) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        return db.delete(
                CE_TABLE_AUTHTOKENS, AUTHTOKENS_ID + "= ?",
                new String[]{authTokenId}) > 0;
    }

    long insertAuthToken(long accountId, String authTokenType, String authToken) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        ContentValues values = new ContentValues();
        values.put(AUTHTOKENS_ACCOUNTS_ID, accountId);
        values.put(AUTHTOKENS_TYPE, authTokenType);
        values.put(AUTHTOKENS_AUTHTOKEN, authToken);
        return db.insert(
                CE_TABLE_AUTHTOKENS, AUTHTOKENS_AUTHTOKEN, values);
    }

    int updateCeAccountPassword(long accountId, String password) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_PASSWORD, password);
        return db.update(
                CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?",
                new String[] {String.valueOf(accountId)});
    }

    boolean renameCeAccount(long accountId, String newName) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, newName);
        final String[] argsAccountId = {String.valueOf(accountId)};
        return db.update(
                CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId) > 0;
    }

    boolean deleteAuthTokensByAccountId(long accountId) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        return db.delete(CE_TABLE_AUTHTOKENS, AUTHTOKENS_ACCOUNTS_ID + "=?",
                new String[] {String.valueOf(accountId)}) > 0;
    }

    long findExtrasIdByAccountId(long accountId, String key) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        Cursor cursor = db.query(
                CE_TABLE_EXTRAS, new String[]{EXTRAS_ID},
                EXTRAS_ACCOUNTS_ID + "=" + accountId + " AND " + EXTRAS_KEY + "=?",
                new String[]{key}, null, null, null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            }
            return -1;
        } finally {
            cursor.close();
        }
    }

    boolean updateExtra(long extrasId, String value) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        ContentValues values = new ContentValues();
        values.put(EXTRAS_VALUE, value);
        int rows = db.update(
                TABLE_EXTRAS, values, EXTRAS_ID + "=?",
                new String[]{String.valueOf(extrasId)});
        return rows == 1;
    }

    long insertExtra(long accountId, String key, String value) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        ContentValues values = new ContentValues();
        values.put(EXTRAS_KEY, key);
        values.put(EXTRAS_ACCOUNTS_ID, accountId);
        values.put(EXTRAS_VALUE, value);
        return db.insert(CE_TABLE_EXTRAS, EXTRAS_KEY, values);
    }

    Map<String, String> findUserExtrasForAccount(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        Map<String, String> userExtrasForAccount = new HashMap<>();
        String[] selectionArgs = {account.name, account.type};
        try (Cursor cursor = db.query(CE_TABLE_EXTRAS,
                COLUMNS_EXTRAS_KEY_AND_VALUE,
                SELECTION_ACCOUNTS_ID_BY_ACCOUNT,
                selectionArgs,
                null, null, null)) {
            while (cursor.moveToNext()) {
                final String tmpkey = cursor.getString(0);
                final String value = cursor.getString(1);
                userExtrasForAccount.put(tmpkey, value);
            }
        }
        return userExtrasForAccount;
    }

    long findCeAccountId(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        String[] columns = { ACCOUNTS_ID };
        String selection = "name=? AND type=?";
        String[] selectionArgs = {account.name, account.type};
        try (Cursor cursor = db.query(CE_TABLE_ACCOUNTS, columns, selection, selectionArgs,
                null, null, null)) {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            }
            return -1;
        }
    }

    String findAccountPasswordByNameAndType(String name, String type) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        String selection = ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?";
        String[] selectionArgs = {name, type};
        String[] columns = {ACCOUNTS_PASSWORD};
        try (Cursor cursor = db.query(CE_TABLE_ACCOUNTS, columns, selection, selectionArgs,
                null, null, null)) {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            }
            return null;
        }
    }

    long insertCeAccount(Account account, String password) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, account.name);
        values.put(ACCOUNTS_TYPE, account.type);
        values.put(ACCOUNTS_PASSWORD, password);
        return db.insert(
                CE_TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
    }


    static class DeDatabaseHelper extends SQLiteOpenHelper {

        private final int mUserId;
        private volatile boolean mCeAttached;

        private DeDatabaseHelper(Context context, int userId, String deDatabaseName) {
            super(context, deDatabaseName, null, DE_DATABASE_VERSION);
            mUserId = userId;
        }

        /**
         * This call needs to be made while the mCacheLock is held. The way to
         * ensure this is to get the lock any time a method is called ont the DatabaseHelper
         * @param db The database.
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "Creating DE database for user " + mUserId);
            db.execSQL("CREATE TABLE " + TABLE_ACCOUNTS + " ( "
                    + ACCOUNTS_ID + " INTEGER PRIMARY KEY, "
                    + ACCOUNTS_NAME + " TEXT NOT NULL, "
                    + ACCOUNTS_TYPE + " TEXT NOT NULL, "
                    + ACCOUNTS_PREVIOUS_NAME + " TEXT, "
                    + ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS + " INTEGER DEFAULT 0, "
                    + "UNIQUE(" + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + "))");

            db.execSQL("CREATE TABLE " + TABLE_META + " ( "
                    + META_KEY + " TEXT PRIMARY KEY NOT NULL, "
                    + META_VALUE + " TEXT)");

            createGrantsTable(db);
            createSharedAccountsTable(db);
            createAccountsDeletionTrigger(db);
            createDebugTable(db);
            createAccountsVisibilityTable(db);
            createAccountsDeletionVisibilityCleanupTrigger(db);
        }

        private void createSharedAccountsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_SHARED_ACCOUNTS + " ( "
                    + ACCOUNTS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + ACCOUNTS_NAME + " TEXT NOT NULL, "
                    + ACCOUNTS_TYPE + " TEXT NOT NULL, "
                    + "UNIQUE(" + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + "))");
        }

        private void createAccountsDeletionTrigger(SQLiteDatabase db) {
            db.execSQL(""
                    + " CREATE TRIGGER " + TABLE_ACCOUNTS + "Delete DELETE ON " + TABLE_ACCOUNTS
                    + " BEGIN"
                    + "   DELETE FROM " + TABLE_GRANTS
                    + "     WHERE " + GRANTS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + " END");
        }

        private void createGrantsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_GRANTS + " (  "
                    + GRANTS_ACCOUNTS_ID + " INTEGER NOT NULL, "
                    + GRANTS_AUTH_TOKEN_TYPE + " STRING NOT NULL,  "
                    + GRANTS_GRANTEE_UID + " INTEGER NOT NULL,  "
                    + "UNIQUE (" + GRANTS_ACCOUNTS_ID + "," + GRANTS_AUTH_TOKEN_TYPE
                    +   "," + GRANTS_GRANTEE_UID + "))");
        }

        private void createAccountsVisibilityTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_VISIBILITY + " ( "
                  + VISIBILITY_ACCOUNTS_ID + " INTEGER NOT NULL, "
                  + VISIBILITY_PACKAGE + " TEXT NOT NULL, "
                  + VISIBILITY_VALUE + " INTEGER, "
                  + "PRIMARY KEY(" + VISIBILITY_ACCOUNTS_ID + "," + VISIBILITY_PACKAGE + "))");
        }

        static void createDebugTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_DEBUG + " ( "
                    + ACCOUNTS_ID + " INTEGER,"
                    + DEBUG_TABLE_ACTION_TYPE + " TEXT NOT NULL, "
                    + DEBUG_TABLE_TIMESTAMP + " DATETIME,"
                    + DEBUG_TABLE_CALLER_UID + " INTEGER NOT NULL,"
                    + DEBUG_TABLE_TABLE_NAME + " TEXT NOT NULL,"
                    + DEBUG_TABLE_KEY + " INTEGER PRIMARY KEY)");
            db.execSQL("CREATE INDEX timestamp_index ON " + TABLE_DEBUG + " ("
                    + DEBUG_TABLE_TIMESTAMP + ")");
        }

        private void createAccountsDeletionVisibilityCleanupTrigger(SQLiteDatabase db) {
            db.execSQL(""
                   + " CREATE TRIGGER "
                   + TABLE_ACCOUNTS + "DeleteVisibility DELETE ON " + TABLE_ACCOUNTS
                   + " BEGIN"
                   + "   DELETE FROM " + TABLE_VISIBILITY
                   + "     WHERE " + VISIBILITY_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                   + " END");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "upgrade from version " + oldVersion + " to version " + newVersion);

            if (oldVersion == 1) {
                createAccountsVisibilityTable(db);
                createAccountsDeletionVisibilityCleanupTrigger(db);
                oldVersion = 3; // skip version 2 which had uid based table
            }

            if (oldVersion == 2) {
                // Remove uid based table and replace it with packageName based
                db.execSQL("DROP TRIGGER IF EXISTS " + TABLE_ACCOUNTS + "DeleteVisibility");
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_VISIBILITY);
                createAccountsVisibilityTable(db);
                createAccountsDeletionVisibilityCleanupTrigger(db);
                oldVersion++;
            }

            if (oldVersion != newVersion) {
                Log.e(TAG, "failed to upgrade version " + oldVersion + " to version " + newVersion);
            }
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.e(TAG, "onDowngrade: recreate accounts DE table");
            resetDatabase(db);
            onCreate(db);
        }

        public SQLiteDatabase getReadableDatabaseUserIsUnlocked() {
            if(!mCeAttached) {
                Log.wtf(TAG, "getReadableDatabaseUserIsUnlocked called while user " + mUserId
                        + " is still locked. CE database is not yet available.", new Throwable());
            }
            return super.getReadableDatabase();
        }

        public SQLiteDatabase getWritableDatabaseUserIsUnlocked() {
            if(!mCeAttached) {
                Log.wtf(TAG, "getWritableDatabaseUserIsUnlocked called while user " + mUserId
                        + " is still locked. CE database is not yet available.", new Throwable());
            }
            return super.getWritableDatabase();
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opened database " + DE_DATABASE_NAME);
        }

        private void migratePreNDbToDe(File preNDbFile) {
            Log.i(TAG, "Migrate pre-N database to DE preNDbFile=" + preNDbFile);
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("ATTACH DATABASE '" +  preNDbFile.getPath() + "' AS preNDb");
            db.beginTransaction();
            // Copy accounts fields
            db.execSQL("INSERT INTO " + TABLE_ACCOUNTS
                    + "(" + ACCOUNTS_ID + "," + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + ", "
                    + ACCOUNTS_PREVIOUS_NAME + ", " + ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS
                    + ") "
                    + "SELECT " + ACCOUNTS_ID + "," + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + ", "
                    + ACCOUNTS_PREVIOUS_NAME + ", " + ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS
                    + " FROM preNDb." + TABLE_ACCOUNTS);
            // Copy SHARED_ACCOUNTS
            db.execSQL("INSERT INTO " + TABLE_SHARED_ACCOUNTS
                    + "(" + SHARED_ACCOUNTS_ID + "," + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + ") " +
                    "SELECT " + SHARED_ACCOUNTS_ID + "," + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE
                    + " FROM preNDb." + TABLE_SHARED_ACCOUNTS);
            // Copy DEBUG_TABLE
            db.execSQL("INSERT INTO " + TABLE_DEBUG
                    + "(" + ACCOUNTS_ID + "," + DEBUG_TABLE_ACTION_TYPE + ","
                    + DEBUG_TABLE_TIMESTAMP + "," + DEBUG_TABLE_CALLER_UID + ","
                    + DEBUG_TABLE_TABLE_NAME + "," + DEBUG_TABLE_KEY + ") " +
                    "SELECT " + ACCOUNTS_ID + "," + DEBUG_TABLE_ACTION_TYPE + ","
                    + DEBUG_TABLE_TIMESTAMP + "," + DEBUG_TABLE_CALLER_UID + ","
                    + DEBUG_TABLE_TABLE_NAME + "," + DEBUG_TABLE_KEY
                    + " FROM preNDb." + TABLE_DEBUG);
            // Copy GRANTS
            db.execSQL("INSERT INTO " + TABLE_GRANTS
                    + "(" + GRANTS_ACCOUNTS_ID + "," + GRANTS_AUTH_TOKEN_TYPE + ","
                    + GRANTS_GRANTEE_UID + ") " +
                    "SELECT " + GRANTS_ACCOUNTS_ID + "," + GRANTS_AUTH_TOKEN_TYPE + ","
                    + GRANTS_GRANTEE_UID + " FROM preNDb." + TABLE_GRANTS);
            // Copy META
            db.execSQL("INSERT INTO " + TABLE_META
                    + "(" + META_KEY + "," + META_VALUE + ") "
                    + "SELECT " + META_KEY + "," + META_VALUE + " FROM preNDb." + TABLE_META);
            db.setTransactionSuccessful();
            db.endTransaction();

            db.execSQL("DETACH DATABASE preNDb");
        }
    }

    boolean deleteDeAccount(long accountId) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null) > 0;
    }

    long insertSharedAccount(Account account) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, account.name);
        values.put(ACCOUNTS_TYPE, account.type);
        return db.insert(
                TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME, values);
    }

    boolean deleteSharedAccount(Account account) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                new String[]{account.name, account.type}) > 0;
    }

    int renameSharedAccount(Account account, String newName) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, newName);
        return db.update(TABLE_SHARED_ACCOUNTS,
                values,
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                new String[] {account.name, account.type});
    }

    List<Account> getSharedAccounts() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        ArrayList<Account> accountList = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_SHARED_ACCOUNTS, new String[] {ACCOUNTS_NAME, ACCOUNTS_TYPE},
                    null, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ACCOUNTS_NAME);
                int typeIndex = cursor.getColumnIndex(ACCOUNTS_TYPE);
                do {
                    accountList.add(new Account(cursor.getString(nameIndex),
                            cursor.getString(typeIndex)));
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return accountList;
    }

    long findSharedAccountId(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Cursor cursor = db.query(TABLE_SHARED_ACCOUNTS, new String[]{
                        ACCOUNTS_ID},
                "name=? AND type=?", new String[]{account.name, account.type}, null, null,
                null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            }
            return -1;
        } finally {
            cursor.close();
        }
    }

    long findAccountLastAuthenticatedTime(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        return DatabaseUtils.longForQuery(db,
                "SELECT " + AccountsDb.ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS
                        + " FROM " + TABLE_ACCOUNTS + " WHERE " + ACCOUNTS_NAME + "=? AND "
                        + ACCOUNTS_TYPE + "=?",
                new String[] {account.name, account.type});
    }

    boolean updateAccountLastAuthenticatedTime(Account account) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS, System.currentTimeMillis());
        int rowCount = db.update(TABLE_ACCOUNTS,
                values,
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                new String[] { account.name, account.type });
        return rowCount > 0;
    }

    void dumpDeAccountsTable(PrintWriter pw) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_ACCOUNTS, ACCOUNT_TYPE_COUNT_PROJECTION,
                null, null, ACCOUNTS_TYPE, null, null);
        try {
            while (cursor.moveToNext()) {
                // print type,count
                pw.println(cursor.getString(0) + "," + cursor.getString(1));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    long findDeAccountId(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        String[] columns = {ACCOUNTS_ID};
        String selection = "name=? AND type=?";
        String[] selectionArgs = {account.name, account.type};
        try (Cursor cursor = db.query(TABLE_ACCOUNTS, columns, selection, selectionArgs,
                null, null, null)) {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            }
            return -1;
        }
    }

    Map<Long, Account> findAllDeAccounts() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        LinkedHashMap<Long, Account> map = new LinkedHashMap<>();
        String[] columns = {ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME};
        try (Cursor cursor = db.query(TABLE_ACCOUNTS, columns,
                null, null, null, null, ACCOUNTS_ID)) {
            while (cursor.moveToNext()) {
                final long accountId = cursor.getLong(0);
                final String accountType = cursor.getString(1);
                final String accountName = cursor.getString(2);

                final Account account = new Account(accountName, accountType);
                map.put(accountId, account);
            }
        }
        return map;
    }

    String findDeAccountPreviousName(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        String[] columns = {ACCOUNTS_PREVIOUS_NAME};
        String selection = ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?";
        String[] selectionArgs = {account.name, account.type};
        try (Cursor cursor = db.query(TABLE_ACCOUNTS, columns, selection, selectionArgs,
                null, null, null)) {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            }
        }
        return null;
    }

    long insertDeAccount(Account account, long accountId) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(ACCOUNTS_ID, accountId);
        values.put(ACCOUNTS_NAME, account.name);
        values.put(ACCOUNTS_TYPE, account.type);
        values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS, System.currentTimeMillis());
        return db.insert(TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
    }

    boolean renameDeAccount(long accountId, String newName, String previousName) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, newName);
        values.put(ACCOUNTS_PREVIOUS_NAME, previousName);
        final String[] argsAccountId = {String.valueOf(accountId)};
        return db.update(TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId) > 0;
    }

    boolean deleteGrantsByAccountIdAuthTokenTypeAndUid(long accountId,
            String authTokenType, long uid) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(TABLE_GRANTS,
                GRANTS_ACCOUNTS_ID + "=? AND " + GRANTS_AUTH_TOKEN_TYPE + "=? AND "
                        + GRANTS_GRANTEE_UID + "=?",
                new String[] {String.valueOf(accountId), authTokenType, String.valueOf(uid)}) > 0;
    }

    List<Integer> findAllUidGrants() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        List<Integer> result = new ArrayList<>();
        final Cursor cursor = db.query(TABLE_GRANTS,
                new String[]{GRANTS_GRANTEE_UID},
                null, null, GRANTS_GRANTEE_UID, null, null);
        try {
            while (cursor.moveToNext()) {
                final int uid = cursor.getInt(0);
                result.add(uid);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    long findMatchingGrantsCount(int uid, String authTokenType, Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        String[] args = {String.valueOf(uid), authTokenType, account.name, account.type};
        return DatabaseUtils.longForQuery(db, COUNT_OF_MATCHING_GRANTS, args);
    }

    long findMatchingGrantsCountAnyToken(int uid, Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        String[] args = {String.valueOf(uid), account.name, account.type};
        return DatabaseUtils.longForQuery(db, COUNT_OF_MATCHING_GRANTS_ANY_TOKEN, args);
    }

    long insertGrant(long accountId, String authTokenType, int uid) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(GRANTS_ACCOUNTS_ID, accountId);
        values.put(GRANTS_AUTH_TOKEN_TYPE, authTokenType);
        values.put(GRANTS_GRANTEE_UID, uid);
        return db.insert(TABLE_GRANTS, GRANTS_ACCOUNTS_ID, values);
    }

    boolean deleteGrantsByUid(int uid) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(TABLE_GRANTS, GRANTS_GRANTEE_UID + "=?",
                new String[] {Integer.toString(uid)}) > 0;
    }

    boolean setAccountVisibility(long accountId, String packageName, int visibility) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(VISIBILITY_ACCOUNTS_ID, String.valueOf(accountId));
        values.put(VISIBILITY_PACKAGE, packageName);
        values.put(VISIBILITY_VALUE, String.valueOf(visibility));
        return (db.replace(TABLE_VISIBILITY, VISIBILITY_VALUE, values) != -1);
    }

    Integer findAccountVisibility(Account account, String packageName) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        final Cursor cursor = db.query(TABLE_VISIBILITY, new String[] {VISIBILITY_VALUE},
                SELECTION_ACCOUNTS_ID_BY_ACCOUNT + " AND " + VISIBILITY_PACKAGE + "=? ",
                new String[] {account.name, account.type, packageName}, null, null, null);
        try {
            while (cursor.moveToNext()) {
                return cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    Integer findAccountVisibility(long accountId, String packageName) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        final Cursor cursor = db.query(TABLE_VISIBILITY, new String[] {VISIBILITY_VALUE},
                VISIBILITY_ACCOUNTS_ID + "=? AND " + VISIBILITY_PACKAGE + "=? ",
                new String[] {String.valueOf(accountId), packageName}, null, null, null);
        try {
            while (cursor.moveToNext()) {
                return cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    Account findDeAccountByAccountId(long accountId) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        final Cursor cursor = db.query(TABLE_ACCOUNTS, new String[] {ACCOUNTS_NAME, ACCOUNTS_TYPE},
                ACCOUNTS_ID + "=? ", new String[] {String.valueOf(accountId)}, null, null, null);
        try {
            while (cursor.moveToNext()) {
                return new Account(cursor.getString(0), cursor.getString(1));
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    /**
     * Returns a map from packageNames to visibility.
     */
    Map<String, Integer> findAllVisibilityValuesForAccount(Account account) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Map<String, Integer> result = new HashMap<>();
        final Cursor cursor =
                db.query(TABLE_VISIBILITY, new String[] {VISIBILITY_PACKAGE, VISIBILITY_VALUE},
                        SELECTION_ACCOUNTS_ID_BY_ACCOUNT, new String[] {account.name, account.type},
                        null, null, null);
        try {
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getInt(1));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Returns a map account -> (package -> visibility)
     */
    Map <Account, Map<String, Integer>> findAllVisibilityValues() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Map<Account, Map<String, Integer>> result = new HashMap<>();
        Cursor cursor = db.rawQuery(
                "SELECT " + TABLE_VISIBILITY + "." + VISIBILITY_PACKAGE
                        + ", " + TABLE_VISIBILITY + "." + VISIBILITY_VALUE
                        + ", " + TABLE_ACCOUNTS + "." + ACCOUNTS_NAME
                        + ", " + TABLE_ACCOUNTS + "." + ACCOUNTS_TYPE
                        + " FROM " + TABLE_VISIBILITY
                        + " JOIN " + TABLE_ACCOUNTS
                        + " ON " + TABLE_ACCOUNTS + "." + ACCOUNTS_ID
                        + " = " + TABLE_VISIBILITY + "." + VISIBILITY_ACCOUNTS_ID, null);
        try {
            while (cursor.moveToNext()) {
                String packageName = cursor.getString(0);
                Integer visibility = cursor.getInt(1);
                String accountName = cursor.getString(2);
                String accountType = cursor.getString(3);
                Account account = new Account(accountName, accountType);
                Map <String, Integer> accountVisibility = result.get(account);
                if (accountVisibility == null) {
                    accountVisibility = new HashMap<>();
                    result.put(account, accountVisibility);
                }
                accountVisibility.put(packageName, visibility);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    boolean deleteAccountVisibilityForPackage(String packageName) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(TABLE_VISIBILITY, VISIBILITY_PACKAGE + "=? ",
                new String[] {packageName}) > 0;
    }

    long insertOrReplaceMetaAuthTypeAndUid(String authenticatorType, int uid) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(META_KEY,
                META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + authenticatorType);
        values.put(META_VALUE, uid);
        return db.insertWithOnConflict(TABLE_META, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    Map<String, Integer> findMetaAuthUid() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Cursor metaCursor = db.query(
                TABLE_META,
                new String[]{META_KEY, META_VALUE},
                SELECTION_META_BY_AUTHENTICATOR_TYPE,
                new String[]{META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + "%"},
                null /* groupBy */,
                null /* having */,
                META_KEY);
        Map<String, Integer> map = new LinkedHashMap<>();
        try {
            while (metaCursor.moveToNext()) {
                String type = TextUtils
                        .split(metaCursor.getString(0), META_KEY_DELIMITER)[1];
                String uidStr = metaCursor.getString(1);
                if (TextUtils.isEmpty(type) || TextUtils.isEmpty(uidStr)) {
                    // Should never happen.
                    Slog.e(TAG, "Auth type empty: " + TextUtils.isEmpty(type)
                            + ", uid empty: " + TextUtils.isEmpty(uidStr));
                    continue;
                }
                int uid = Integer.parseInt(metaCursor.getString(1));
                map.put(type, uid);
            }
        } finally {
            metaCursor.close();
        }
        return map;
    }

    boolean deleteMetaByAuthTypeAndUid(String type, int uid) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        return db.delete(
                TABLE_META,
                META_KEY + "=? AND " + META_VALUE + "=?",
                new String[]{
                        META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + type,
                        String.valueOf(uid)}
        ) > 0;
    }

    /**
     * Returns list of all grants as {@link Pair pairs} of account name and UID.
     */
    List<Pair<String, Integer>> findAllAccountGrants() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(ACCOUNT_ACCESS_GRANTS, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return Collections.emptyList();
            }
            List<Pair<String, Integer>> results = new ArrayList<>();
            do {
                final String accountName = cursor.getString(0);
                final int uid = cursor.getInt(1);
                results.add(Pair.create(accountName, uid));
            } while (cursor.moveToNext());
            return results;
        }
    }

    private static class PreNDatabaseHelper extends SQLiteOpenHelper {
        private final Context mContext;
        private final int mUserId;

        PreNDatabaseHelper(Context context, int userId, String preNDatabaseName) {
            super(context, preNDatabaseName, null, PRE_N_DATABASE_VERSION);
            mContext = context;
            mUserId = userId;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // We use PreNDatabaseHelper only if pre-N db exists
            throw new IllegalStateException("Legacy database cannot be created - only upgraded!");
        }

        private void createSharedAccountsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_SHARED_ACCOUNTS + " ( "
                    + ACCOUNTS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + ACCOUNTS_NAME + " TEXT NOT NULL, "
                    + ACCOUNTS_TYPE + " TEXT NOT NULL, "
                    + "UNIQUE(" + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE + "))");
        }

        private void addLastSuccessfullAuthenticatedTimeColumn(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS + " ADD COLUMN "
                    + ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS + " DEFAULT 0");
        }

        private void addOldAccountNameColumn(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE " + TABLE_ACCOUNTS + " ADD COLUMN " + ACCOUNTS_PREVIOUS_NAME);
        }

        private void addDebugTable(SQLiteDatabase db) {
            DeDatabaseHelper.createDebugTable(db);
        }

        private void createAccountsDeletionTrigger(SQLiteDatabase db) {
            db.execSQL(""
                    + " CREATE TRIGGER " + TABLE_ACCOUNTS + "Delete DELETE ON " + TABLE_ACCOUNTS
                    + " BEGIN"
                    + "   DELETE FROM " + TABLE_AUTHTOKENS
                    + "     WHERE " + AUTHTOKENS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + "   DELETE FROM " + TABLE_EXTRAS
                    + "     WHERE " + EXTRAS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + "   DELETE FROM " + TABLE_GRANTS
                    + "     WHERE " + GRANTS_ACCOUNTS_ID + "=OLD." + ACCOUNTS_ID + " ;"
                    + " END");
        }

        private void createGrantsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_GRANTS + " (  "
                    + GRANTS_ACCOUNTS_ID + " INTEGER NOT NULL, "
                    + GRANTS_AUTH_TOKEN_TYPE + " STRING NOT NULL,  "
                    + GRANTS_GRANTEE_UID + " INTEGER NOT NULL,  "
                    + "UNIQUE (" + GRANTS_ACCOUNTS_ID + "," + GRANTS_AUTH_TOKEN_TYPE
                    +   "," + GRANTS_GRANTEE_UID + "))");
        }

        static long insertMetaAuthTypeAndUid(SQLiteDatabase db, String authenticatorType, int uid) {
            ContentValues values = new ContentValues();
            values.put(META_KEY,
                    META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + authenticatorType);
            values.put(META_VALUE, uid);
            return db.insert(TABLE_META, null, values);
        }

        private void populateMetaTableWithAuthTypeAndUID(SQLiteDatabase db,
                Map<String, Integer> authTypeAndUIDMap) {
            for (Map.Entry<String, Integer> entry : authTypeAndUIDMap.entrySet()) {
                insertMetaAuthTypeAndUid(db, entry.getKey(), entry.getValue());
            }
        }

        /**
         * Pre-N database may need an upgrade before splitting
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.e(TAG, "upgrade from version " + oldVersion + " to version " + newVersion);

            if (oldVersion == 1) {
                // no longer need to do anything since the work is done
                // when upgrading from version 2
                oldVersion++;
            }

            if (oldVersion == 2) {
                createGrantsTable(db);
                db.execSQL("DROP TRIGGER " + TABLE_ACCOUNTS + "Delete");
                createAccountsDeletionTrigger(db);
                oldVersion++;
            }

            if (oldVersion == 3) {
                db.execSQL("UPDATE " + TABLE_ACCOUNTS + " SET " + ACCOUNTS_TYPE +
                        " = 'com.google' WHERE " + ACCOUNTS_TYPE + " == 'com.google.GAIA'");
                oldVersion++;
            }

            if (oldVersion == 4) {
                createSharedAccountsTable(db);
                oldVersion++;
            }

            if (oldVersion == 5) {
                addOldAccountNameColumn(db);
                oldVersion++;
            }

            if (oldVersion == 6) {
                addLastSuccessfullAuthenticatedTimeColumn(db);
                oldVersion++;
            }

            if (oldVersion == 7) {
                addDebugTable(db);
                oldVersion++;
            }

            if (oldVersion == 8) {
                populateMetaTableWithAuthTypeAndUID(
                        db,
                        AccountManagerService.getAuthenticatorTypeAndUIDForUser(mContext, mUserId));
                oldVersion++;
            }

            if (oldVersion != newVersion) {
                Log.e(TAG, "failed to upgrade version " + oldVersion + " to version " + newVersion);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opened database " + DATABASE_NAME);
        }
    }

    List<Account> findCeAccountsNotInDe() {
        SQLiteDatabase db = mDeDatabase.getReadableDatabaseUserIsUnlocked();
        // Select accounts from CE that do not exist in DE
        Cursor cursor = db.rawQuery(
                "SELECT " + ACCOUNTS_NAME + "," + ACCOUNTS_TYPE
                        + " FROM " + CE_TABLE_ACCOUNTS
                        + " WHERE NOT EXISTS "
                        + " (SELECT " + ACCOUNTS_ID + " FROM " + TABLE_ACCOUNTS
                        + " WHERE " + ACCOUNTS_ID + "=" + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_ID
                        + " )", null);
        try {
            List<Account> accounts = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String accountName = cursor.getString(0);
                String accountType = cursor.getString(1);
                accounts.add(new Account(accountName, accountType));
            }
            return accounts;
        } finally {
            cursor.close();
        }
    }

    boolean deleteCeAccount(long accountId) {
        SQLiteDatabase db = mDeDatabase.getWritableDatabaseUserIsUnlocked();
        return db.delete(
                CE_TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null) > 0;
    }

    boolean isCeDatabaseAttached() {
        return mDeDatabase.mCeAttached;
    }

    void beginTransaction() {
        mDeDatabase.getWritableDatabase().beginTransaction();
    }

    void setTransactionSuccessful() {
        mDeDatabase.getWritableDatabase().setTransactionSuccessful();
    }

    void endTransaction() {
        mDeDatabase.getWritableDatabase().endTransaction();
    }

    void attachCeDatabase(File ceDbFile) {
        CeDatabaseHelper.create(mContext, mPreNDatabaseFile, ceDbFile);
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        db.execSQL("ATTACH DATABASE '" +  ceDbFile.getPath()+ "' AS ceDb");
        mDeDatabase.mCeAttached = true;
    }

    /*
     * Finds the row key where the next insertion should take place. Returns number of rows
     * if it is less {@link #MAX_DEBUG_DB_SIZE}, otherwise finds the lowest number available.
     */
    long calculateDebugTableInsertionPoint() {
        try {
            SQLiteDatabase db = mDeDatabase.getReadableDatabase();
            String queryCountDebugDbRows = "SELECT COUNT(*) FROM " + TABLE_DEBUG;
            int size = (int) DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
            if (size < MAX_DEBUG_DB_SIZE) {
                return size;
            }

            // This query finds the smallest timestamp value (and if 2 records have
            // same timestamp, the choose the lower id).
            queryCountDebugDbRows =
                    "SELECT " + DEBUG_TABLE_KEY
                    + " FROM " + TABLE_DEBUG
                    + " ORDER BY "  + DEBUG_TABLE_TIMESTAMP + ","
                    + DEBUG_TABLE_KEY
                    + " LIMIT 1";
            return DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to open debug table" + e);
            return -1;
        }
    }

    SQLiteStatement compileSqlStatementForLogging() {
        SQLiteDatabase db = mDeDatabase.getWritableDatabase();
        String sql = "INSERT OR REPLACE INTO " + AccountsDb.TABLE_DEBUG
                + " VALUES (?,?,?,?,?,?)";
        return db.compileStatement(sql);
    }

    /**
     * Returns statement for logging or {@code null} on database open failure.
     * Returned value must be guarded by {link #debugStatementLock}
     */
    @Nullable SQLiteStatement getStatementForLogging() {
        if (mDebugStatementForLogging != null) {
            return mDebugStatementForLogging;
        }
        try {
            mDebugStatementForLogging =  compileSqlStatementForLogging();
            return mDebugStatementForLogging;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to open debug table" + e);
            return null;
        }
    }

    void closeDebugStatement() {
        synchronized (mDebugStatementLock) {
            if (mDebugStatementForLogging != null) {
                mDebugStatementForLogging.close();
                mDebugStatementForLogging = null;
            }
        }
    }

    long reserveDebugDbInsertionPoint() {
        if (mDebugDbInsertionPoint == -1) {
            mDebugDbInsertionPoint = calculateDebugTableInsertionPoint();
            return mDebugDbInsertionPoint;
        }
        mDebugDbInsertionPoint = (mDebugDbInsertionPoint + 1) % MAX_DEBUG_DB_SIZE;
        return mDebugDbInsertionPoint;
    }

    void dumpDebugTable(PrintWriter pw) {
        SQLiteDatabase db = mDeDatabase.getReadableDatabase();
        Cursor cursor = db.query(TABLE_DEBUG, null,
                null, null, null, null, DEBUG_TABLE_TIMESTAMP);
        pw.println("AccountId, Action_Type, timestamp, UID, TableName, Key");
        pw.println("Accounts History");
        try {
            while (cursor.moveToNext()) {
                // print type,count
                pw.println(cursor.getString(0) + "," + cursor.getString(1) + "," +
                        cursor.getString(2) + "," + cursor.getString(3) + ","
                        + cursor.getString(4) + "," + cursor.getString(5));
            }
        } finally {
            cursor.close();
        }
    }

    @Override
    public void close() {
        mDeDatabase.close();
    }

    static void deleteDbFileWarnIfFailed(File dbFile) {
        if (!SQLiteDatabase.deleteDatabase(dbFile)) {
            Log.w(TAG, "Database at " + dbFile + " was not deleted successfully");
        }
    }

    public static AccountsDb create(Context context, int userId, File preNDatabaseFile,
            File deDatabaseFile) {
        boolean newDbExists = deDatabaseFile.exists();
        DeDatabaseHelper deDatabaseHelper = new DeDatabaseHelper(context, userId,
                deDatabaseFile.getPath());
        // If the db just created, and there is a legacy db, migrate it
        if (!newDbExists && preNDatabaseFile.exists()) {
            // Migrate legacy db to the latest version -  PRE_N_DATABASE_VERSION
            PreNDatabaseHelper
                    preNDatabaseHelper = new PreNDatabaseHelper(context, userId,
                    preNDatabaseFile.getPath());
            // Open the database to force upgrade if required
            preNDatabaseHelper.getWritableDatabase();
            preNDatabaseHelper.close();
            // Move data without SPII to DE
            deDatabaseHelper.migratePreNDbToDe(preNDatabaseFile);
        }
        return new AccountsDb(deDatabaseHelper, context, preNDatabaseFile);
    }

    /**
     * Removes all tables and triggers created by AccountManager.
     */
    private static void resetDatabase(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type ='table'", null)) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                // Skip tables managed by SQLiteDatabase
                if ("android_metadata".equals(name) || "sqlite_sequence".equals(name)) {
                    continue;
                }
                db.execSQL("DROP TABLE IF EXISTS " + name);
            }
        }

        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type ='trigger'", null)) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                db.execSQL("DROP TRIGGER IF EXISTS " + name);
            }
        }
    }
}
