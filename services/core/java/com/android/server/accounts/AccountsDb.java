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
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
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
 */
class AccountsDb {
    private static final String TAG = "AccountsDb";

    private static final String DATABASE_NAME = "accounts.db";
    private static final int PRE_N_DATABASE_VERSION = 9;
    private static final int CE_DATABASE_VERSION = 10;
    private static final int DE_DATABASE_VERSION = 1;


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

    private static final String SELECTION_AUTHTOKENS_BY_ACCOUNT =
            AUTHTOKENS_ACCOUNTS_ID + "=(select _id FROM accounts WHERE name=? AND type=?)";

    private static final String[] COLUMNS_AUTHTOKENS_TYPE_AND_AUTHTOKEN = {AUTHTOKENS_TYPE,
            AUTHTOKENS_AUTHTOKEN};

    private static final String SELECTION_USERDATA_BY_ACCOUNT =
            EXTRAS_ACCOUNTS_ID + "=(select _id FROM accounts WHERE name=? AND type=?)";
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

    static class CeDatabaseHelper extends SQLiteOpenHelper {

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
                oldVersion ++;
            }

            if (oldVersion != newVersion) {
                Log.e(TAG, "failed to upgrade version " + oldVersion + " to version " + newVersion);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opened database " + CE_DATABASE_NAME);
        }


        /**
         * Creates a new {@code CeDatabaseHelper}. If pre-N db file is present at the old location,
         * it also performs migration to the new CE database.
         * @param userId id of the user where the database is located
         */
        static CeDatabaseHelper create(
                Context context,
                int userId,
                File preNDatabaseFile,
                File ceDatabaseFile) {
            boolean newDbExists = ceDatabaseFile.exists();
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "CeDatabaseHelper.create userId=" + userId + " oldDbExists="
                        + preNDatabaseFile.exists() + " newDbExists=" + newDbExists);
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

        /**
         * Returns information about auth tokens and their account for the specified query
         * parameters.
         * Output is in the format:
         * <pre><code> | AUTHTOKEN_ID |  ACCOUNT_NAME | AUTH_TOKEN_TYPE |</code></pre>
         */
        static Cursor findAuthtokenForAllAccounts(SQLiteDatabase db, String accountType,
                String authToken) {
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

        static boolean deleteAuthtokensByAccountIdAndType(SQLiteDatabase db, long accountId,
                String authtokenType) {
            return db.delete(CE_TABLE_AUTHTOKENS,
                    AUTHTOKENS_ACCOUNTS_ID + "=?" + accountId + " AND " + AUTHTOKENS_TYPE + "=?",
                    new String[]{String.valueOf(accountId), authtokenType}) > 0;
        }

        static boolean deleteAuthToken(SQLiteDatabase db, String authTokenId) {
            return db.delete(
                    CE_TABLE_AUTHTOKENS, AUTHTOKENS_ID + "= ?",
                    new String[]{authTokenId}) > 0;
        }

        static long insertAuthToken(SQLiteDatabase db, long accountId, String authTokenType,
                String authToken) {
            ContentValues values = new ContentValues();
            values.put(AUTHTOKENS_ACCOUNTS_ID, accountId);
            values.put(AUTHTOKENS_TYPE, authTokenType);
            values.put(AUTHTOKENS_AUTHTOKEN, authToken);
            return db.insert(
                    CE_TABLE_AUTHTOKENS, AUTHTOKENS_AUTHTOKEN, values);
        }

        static Map<String, String> findAuthTokensByAccount(final SQLiteDatabase db,
                Account account) {
            HashMap<String, String> authTokensForAccount = new HashMap<>();
            Cursor cursor = db.query(CE_TABLE_AUTHTOKENS,
                    COLUMNS_AUTHTOKENS_TYPE_AND_AUTHTOKEN,
                    SELECTION_AUTHTOKENS_BY_ACCOUNT,
                    new String[]{account.name, account.type},
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

        static int updateAccountPassword(SQLiteDatabase db, long accountId, String password) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_PASSWORD, password);
            return db.update(
                    CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?",
                    new String[]{String.valueOf(accountId)});
        }

        static boolean renameAccount(SQLiteDatabase db, long accountId, String newName) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, newName);
            final String[] argsAccountId = {String.valueOf(accountId)};
            return db.update(
                    CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId) > 0;
        }

        static boolean deleteAuthTokensByAccountId(SQLiteDatabase db, long accountId) {
            return db.delete(
                    CE_TABLE_AUTHTOKENS, AUTHTOKENS_ACCOUNTS_ID + "=?",
                    new String[]{String.valueOf(accountId)}) > 0;
        }

        static long findExtrasIdByAccountId(SQLiteDatabase db, long accountId, String key) {
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

        static boolean updateExtra(SQLiteDatabase db, long extrasId, String value) {
            ContentValues values = new ContentValues();
            values.put(EXTRAS_VALUE, value);
            int rows = db.update(
                    TABLE_EXTRAS, values, EXTRAS_ID + "=?",
                    new String[]{String.valueOf(extrasId)});
            return rows == 1;
        }

        static long insertExtra(SQLiteDatabase db, long accountId, String key, String value) {
            ContentValues values = new ContentValues();
            values.put(EXTRAS_KEY, key);
            values.put(EXTRAS_ACCOUNTS_ID, accountId);
            values.put(EXTRAS_VALUE, value);
            return db.insert(CE_TABLE_EXTRAS, EXTRAS_KEY, values);
        }

        static Map<String, String> findUserExtrasForAccount(SQLiteDatabase db, Account account) {
            Map<String, String> userExtrasForAccount = new HashMap<>();
            Cursor cursor = db.query(CE_TABLE_EXTRAS,
                    COLUMNS_EXTRAS_KEY_AND_VALUE,
                    SELECTION_USERDATA_BY_ACCOUNT,
                    new String[]{account.name, account.type},
                    null, null, null);
            try {
                while (cursor.moveToNext()) {
                    final String tmpkey = cursor.getString(0);
                    final String value = cursor.getString(1);
                    userExtrasForAccount.put(tmpkey, value);
                }
            } finally {
                cursor.close();
            }
            return userExtrasForAccount;
        }

        static long findAccountId(SQLiteDatabase db, Account account) {
            Cursor cursor = db.query(
                    CE_TABLE_ACCOUNTS, new String[]{
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

        static String findAccountPasswordByNameAndType(SQLiteDatabase db, String name,
                String type) {
            Cursor cursor = db.query(CE_TABLE_ACCOUNTS, new String[]{
                            ACCOUNTS_PASSWORD},
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                    new String[]{name, type}, null, null, null);
            try {
                if (cursor.moveToNext()) {
                    return cursor.getString(0);
                }
                return null;
            } finally {
                cursor.close();
            }
        }

        static long insertAccount(SQLiteDatabase db, Account account, String password) {
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, account.name);
            values.put(ACCOUNTS_TYPE, account.type);
            values.put(ACCOUNTS_PASSWORD, password);
            return db.insert(
                    CE_TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
        }

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
            DebugDbHelper.createDebugTable(db);
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

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "upgrade from version " + oldVersion + " to version " + newVersion);

            if (oldVersion != newVersion) {
                Log.e(TAG, "failed to upgrade version " + oldVersion + " to version " + newVersion);
            }
        }

        public void attachCeDatabase(File ceDbFile) {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("ATTACH DATABASE '" +  ceDbFile.getPath()+ "' AS ceDb");
            mCeAttached = true;
        }

        public boolean isCeDatabaseAttached() {
            return mCeAttached;
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

        static boolean deleteAccount(SQLiteDatabase db, long accountId) {
            return db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null) > 0;
        }

        static long insertSharedAccount(SQLiteDatabase db, Account account) {
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, account.name);
            values.put(ACCOUNTS_TYPE, account.type);
            return db.insert(
                    TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME, values);
        }

        static boolean deleteSharedAccount(SQLiteDatabase db, Account account) {
            return db
                    .delete(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                            new String[]{account.name, account.type}) > 0;
        }

        static int renameSharedAccount(SQLiteDatabase db, Account account, String newName) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, newName);
            return db.update(TABLE_SHARED_ACCOUNTS,
                    values,
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE
                            + "=?",
                    new String[]{account.name, account.type});
        }

        static List<Account> getSharedAccounts(SQLiteDatabase db) {
            ArrayList<Account> accountList = new ArrayList<>();
            Cursor cursor = null;
            try {
                cursor = db.query(TABLE_SHARED_ACCOUNTS, new String[]{
                                ACCOUNTS_NAME, ACCOUNTS_TYPE},
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

        static long findSharedAccountId(SQLiteDatabase db, Account account) {
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

        static long findAccountLastAuthenticatedTime(SQLiteDatabase db, Account account) {
            return DatabaseUtils.longForQuery(
                    db,
                    "SELECT " + AccountsDb.ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS
                            + " FROM " +
                            TABLE_ACCOUNTS + " WHERE " + ACCOUNTS_NAME + "=? AND "
                            + ACCOUNTS_TYPE + "=?",
                    new String[] {account.name, account.type});
        }

        static boolean updateAccountLastAuthenticatedTime(SQLiteDatabase db, Account account) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS, System.currentTimeMillis());
            int rowCount = db.update(
                    TABLE_ACCOUNTS,
                    values,
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                    new String[] {
                            account.name, account.type
                    });
            return rowCount > 0;
        }


        static void dumpAccountsTable(SQLiteDatabase db, PrintWriter pw) {
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

        static long findAccountId(SQLiteDatabase db, Account account) {
            Cursor cursor = db.query(
                    TABLE_ACCOUNTS, new String[]{ACCOUNTS_ID},
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

        static Map<Long, Account> findAllAccounts(SQLiteDatabase db) {
            LinkedHashMap<Long, Account> map = new LinkedHashMap<>();
            Cursor cursor = db.query(TABLE_ACCOUNTS,
                    new String[]{ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME},
                    null, null, null, null, ACCOUNTS_ID);
            try {
                while (cursor.moveToNext()) {
                    final long accountId = cursor.getLong(0);
                    final String accountType = cursor.getString(1);
                    final String accountName = cursor.getString(2);

                    final Account account = new Account(accountName, accountType);
                    map.put(accountId, account);
                }
            } finally {
                cursor.close();
            }
            return map;
        }

        static String findAccountPreviousName(SQLiteDatabase db, Account account) {
            Cursor cursor = db.query(
                    TABLE_ACCOUNTS,
                    new String[]{ACCOUNTS_PREVIOUS_NAME},
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE
                            + "=?",
                    new String[]{account.name, account.type},
                    null,
                    null,
                    null);
            try {
                if (cursor.moveToNext()) {
                    return cursor.getString(0);
                }
            } finally {
                cursor.close();
            }
            return null;
        }

        static long insertAccount(SQLiteDatabase db, Account account, long accountId) {
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_ID, accountId);
            values.put(ACCOUNTS_NAME, account.name);
            values.put(ACCOUNTS_TYPE, account.type);
            values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS, System.currentTimeMillis());
            return db.insert(TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
        }

        static boolean renameAccount(SQLiteDatabase db, long accountId, String newName,
                String previousName) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, newName);
            values.put(ACCOUNTS_PREVIOUS_NAME, previousName);
            final String[] argsAccountId = {String.valueOf(accountId)};
            return db.update(
                    TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId) > 0;
        }

        static boolean deleteGrantsByAccountIdAuthTokenTypeAndUid(SQLiteDatabase db, long accountId,
                String authTokenType, long uid) {
            return db.delete(TABLE_GRANTS,
                    GRANTS_ACCOUNTS_ID + "=? AND " + GRANTS_AUTH_TOKEN_TYPE + "=? AND "
                            + GRANTS_GRANTEE_UID + "=?",
                    new String[]{String.valueOf(accountId), authTokenType, String.valueOf(uid)})
                    > 0;
        }

        static List<Integer> findAllUidGrants(SQLiteDatabase db) {
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

        static long findMatchingGrantsCount(SQLiteDatabase db,
                int uid, String authTokenType, Account account) {
            String[] args = {String.valueOf(uid), authTokenType,
                    account.name, account.type};
            return DatabaseUtils
                    .longForQuery(db, COUNT_OF_MATCHING_GRANTS, args);
        }

        static long findMatchingGrantsCountAnyToken(SQLiteDatabase db,
                int uid, Account account) {
            String[] args = {String.valueOf(uid), account.name, account.type};
            return DatabaseUtils.longForQuery(db, COUNT_OF_MATCHING_GRANTS_ANY_TOKEN, args);
        }

        static long insertGrant(SQLiteDatabase db, long accountId, String authTokenType, int uid) {
            ContentValues values = new ContentValues();
            values.put(GRANTS_ACCOUNTS_ID, accountId);
            values.put(GRANTS_AUTH_TOKEN_TYPE, authTokenType);
            values.put(GRANTS_GRANTEE_UID, uid);
            return db.insert(
                    TABLE_GRANTS, GRANTS_ACCOUNTS_ID, values);
        }

        static boolean deleteGrantsByUid(SQLiteDatabase db, int uid) {
            return db.delete(
                    TABLE_GRANTS, GRANTS_GRANTEE_UID + "=?",
                    new String[]{Integer.toString(uid)}) > 0;
        }

        static long insertMetaAuthTypeAndUid(SQLiteDatabase db, String authenticatorType, int uid) {
            ContentValues values = new ContentValues();
            values.put(META_KEY,
                    META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + authenticatorType);
            values.put(META_VALUE, uid);
            return db.insert(TABLE_META, null, values);
        }

        static long insertOrReplaceMetaAuthTypeAndUid(SQLiteDatabase db, String authenticatorType,
                int uid) {
            ContentValues values = new ContentValues();
            values.put(META_KEY,
                    META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + authenticatorType);
            values.put(META_VALUE, uid);
            return db.insertWithOnConflict(TABLE_META, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }

        static Map<String, Integer> findMetaAuthUid(SQLiteDatabase db) {
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

        static boolean deleteMetaByAuthTypeAndUid(SQLiteDatabase db, String type, int uid) {
            return db.delete(
                    TABLE_META,
                    META_KEY + "=? AND " + META_VALUE + "=?",
                    new String[]{
                            META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + type,
                            String.valueOf(uid)}
            ) > 0;
        }

        static List<Pair<String, Integer>> findAllAccountGrants(SQLiteDatabase db) {
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

        static DeDatabaseHelper create(
                Context context,
                int userId,
                File preNDatabaseFile,
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
            return deDatabaseHelper;
        }
    }

    static class PreNDatabaseHelper extends SQLiteOpenHelper {
        private final Context mContext;
        private final int mUserId;

        public PreNDatabaseHelper(Context context, int userId, String preNDatabaseName) {
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
            DebugDbHelper.createDebugTable(db);
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

        private void populateMetaTableWithAuthTypeAndUID(SQLiteDatabase db,
                Map<String, Integer> authTypeAndUIDMap) {
            for (Map.Entry<String, Integer> entry : authTypeAndUIDMap.entrySet()) {
                DeDatabaseHelper.insertMetaAuthTypeAndUid(db, entry.getKey(), entry.getValue());
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

    static class DebugDbHelper{
        private DebugDbHelper() {
        }


        private static void createDebugTable(SQLiteDatabase db) {
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

        static SQLiteStatement compileSqlStatementForLogging(SQLiteDatabase db) {
            String sql = "INSERT OR REPLACE INTO " + AccountsDb.TABLE_DEBUG
                    + " VALUES (?,?,?,?,?,?)";
            return db.compileStatement(sql);
        }

        static int getDebugTableRowCount(SQLiteDatabase db) {
            String queryCountDebugDbRows = "SELECT COUNT(*) FROM " + TABLE_DEBUG;
            return (int) DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
        }

        /*
         * Finds the row key where the next insertion should take place. This should
         * be invoked only if the table has reached its full capacity.
         */
        static int getDebugTableInsertionPoint(SQLiteDatabase db) {
            // This query finds the smallest timestamp value (and if 2 records have
            // same timestamp, the choose the lower id).
            String queryCountDebugDbRows = "SELECT " + DEBUG_TABLE_KEY +
                    " FROM " + TABLE_DEBUG +
                    " ORDER BY "  + DEBUG_TABLE_TIMESTAMP + "," + DEBUG_TABLE_KEY +
                    " LIMIT 1";
            return (int) DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
        }

        static void dumpDebugTable(SQLiteDatabase db, PrintWriter pw) {
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

    }

    static List<Account> findCeAccountsNotInDe(SQLiteDatabase db) {
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

    static boolean deleteCeAccount(SQLiteDatabase db, long accountId) {
        return db.delete(
                CE_TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null) > 0;
    }

    static void deleteDbFileWarnIfFailed(File dbFile) {
        if (!SQLiteDatabase.deleteDatabase(dbFile)) {
            Log.w(TAG, "Database at " + dbFile + " was not deleted successfully");
        }
    }
}
