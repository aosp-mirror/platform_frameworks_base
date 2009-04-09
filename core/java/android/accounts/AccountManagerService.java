/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;

import com.android.internal.telephony.TelephonyIntents;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

/**
 * A system service that provides  account, password, and authtoken management for all
 * accounts on the device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. This service is not accessed by users directly,
 * instead one uses an instance of {@link AccountManager}, which can be accessed as follows:
 *    AccountManager accountManager =
 *      (AccountManager)context.getSystemService(Context.ACCOUNT_SERVICE)
 */
public class AccountManagerService extends IAccountManager.Stub {
    private static final String TAG = "AccountManagerService";

    private static final int TIMEOUT_DELAY_MS = 1000 * 60;
    private static final String DATABASE_NAME = "accounts.db";
    private static final int DATABASE_VERSION = 2;

    private final Context mContext;

    private HandlerThread mMessageThread;
    private final MessageHandler mMessageHandler;

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_CONNECTED = 7;
    private static final int MESSAGE_DISCONNECTED = 8;

    private final AccountAuthenticatorCache mAuthenticatorCache;
    private final AuthenticatorBindHelper mBindHelper;
    public final HashMap<AuthTokenKey, String> mAuthTokenCache = Maps.newHashMap();
    private final DatabaseHelper mOpenHelper;
    private final SimWatcher mSimWatcher;

    private static final String TABLE_ACCOUNTS = "accounts";
    private static final String ACCOUNTS_ID = "_id";
    private static final String ACCOUNTS_NAME = "name";
    private static final String ACCOUNTS_TYPE = "type";
    private static final String ACCOUNTS_PASSWORD = "password";

    private static final String TABLE_AUTHTOKENS = "authtokens";
    private static final String AUTHTOKENS_ID = "_id";
    private static final String AUTHTOKENS_ACCOUNTS_ID = "accounts_id";
    private static final String AUTHTOKENS_TYPE = "type";
    private static final String AUTHTOKENS_AUTHTOKEN = "authtoken";

    private static final String TABLE_EXTRAS = "extras";
    private static final String EXTRAS_ID = "_id";
    private static final String EXTRAS_ACCOUNTS_ID = "accounts_id";
    private static final String EXTRAS_KEY = "key";
    private static final String EXTRAS_VALUE = "value";

    private static final String TABLE_META = "meta";
    private static final String META_KEY = "key";
    private static final String META_VALUE = "value";

    private static final String[] ACCOUNT_NAME_TYPE_PROJECTION =
            new String[]{ACCOUNTS_ID, ACCOUNTS_NAME, ACCOUNTS_TYPE};
    private static final Intent ACCOUNTS_CHANGED_INTENT =
            new Intent(Constants.LOGIN_ACCOUNTS_CHANGED_ACTION);

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private static final int NOTIFICATION_ID = 234;

    public class AuthTokenKey {
        public final Account mAccount;
        public final String mAuthTokenType;
        private final int mHashCode;

        public AuthTokenKey(Account account, String authTokenType) {
            mAccount = account;
            mAuthTokenType = authTokenType;
            mHashCode = computeHashCode();
        }

        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof AuthTokenKey)) {
                return false;
            }
            AuthTokenKey other = (AuthTokenKey)o;
            if (!mAccount.equals(other.mAccount)) {
                return false;
            }
            return (mAuthTokenType == null)
                    ? other.mAuthTokenType == null
                    : mAuthTokenType.equals(other.mAuthTokenType);
        }

        private int computeHashCode() {
            int result = 17;
            result = 31 * result + mAccount.hashCode();
            result = 31 * result + ((mAuthTokenType == null) ? 0 : mAuthTokenType.hashCode());
            return result;
        }

        public int hashCode() {
            return mHashCode;
        }
    }

    public AccountManagerService(Context context) {
        mContext = context;

        mOpenHelper = new DatabaseHelper(mContext);

        mMessageThread = new HandlerThread("AccountManagerService");
        mMessageThread.start();
        mMessageHandler = new MessageHandler(mMessageThread.getLooper());

        mAuthenticatorCache = new AccountAuthenticatorCache(mContext);
        mBindHelper = new AuthenticatorBindHelper(mContext, mAuthenticatorCache, mMessageHandler,
                MESSAGE_CONNECTED, MESSAGE_DISCONNECTED);

        mSimWatcher = new SimWatcher(mContext);
    }

    public String getPassword(Account account) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = db.query(TABLE_ACCOUNTS, new String[]{ACCOUNTS_PASSWORD},
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[]{account.mName, account.mType}, null, null, null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public String getUserData(Account account, String key) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId < 0) {
                return null;
            }
            Cursor cursor = db.query(TABLE_EXTRAS, new String[]{EXTRAS_VALUE},
                    EXTRAS_ACCOUNTS_ID + "=" + accountId + " AND " + EXTRAS_KEY + "=?",
                    new String[]{key}, null, null, null);
            try {
                if (cursor.moveToNext()) {
                    return cursor.getString(0);
                }
                return null;
            } finally {
                cursor.close();
            }
        } finally {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
    }

    public String[] getAuthenticatorTypes() {
        Collection<AccountAuthenticatorCache.AuthenticatorInfo> authenticatorCollection =
                mAuthenticatorCache.getAllAuthenticators();
        String[] types = new String[authenticatorCollection.size()];
        int i = 0;
        for (AccountAuthenticatorCache.AuthenticatorInfo authenticator : authenticatorCollection) {
            types[i] = authenticator.mType;
            i++;
        }
        return types;
    }

    public Account[] getAccounts() {
        return getAccountsByType(null);
    }

    public Account[] getAccountsByType(String accountType) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        final String selection = accountType == null ? null : (ACCOUNTS_TYPE + "=?");
        final String[] selectionArgs = accountType == null ? null : new String[]{accountType};
        Cursor cursor = db.query(TABLE_ACCOUNTS, ACCOUNT_NAME_TYPE_PROJECTION,
                selection, selectionArgs, null, null, null);
        try {
            int i = 0;
            Account[] accounts = new Account[cursor.getCount()];
            while (cursor.moveToNext()) {
                accounts[i] = new Account(cursor.getString(1), cursor.getString(2));
                i++;
            }
            return accounts;
        } finally {
            cursor.close();
        }
    }

    public boolean addAccount(Account account, String password, Bundle extras) {
        // fails if the account already exists
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long numMatches = DatabaseUtils.longForQuery(db,
                    "select count(*) from " + TABLE_ACCOUNTS
                            + " WHERE " + ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                    new String[]{account.mName, account.mType});
            if (numMatches > 0) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, account.mName);
            values.put(ACCOUNTS_TYPE, account.mType);
            values.put(ACCOUNTS_PASSWORD, password);
            long accountId = db.insert(TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
            if (accountId < 0) {
                return false;
            }
            if (extras != null) {
                for (String key : extras.keySet()) {
                    final String value = extras.getString(key);
                    if (insertExtra(db, accountId, key, value) < 0) {
                        return false;
                    }
                }
            }
            db.setTransactionSuccessful();
            mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private long insertExtra(SQLiteDatabase db, long accountId, String key, String value) {
        ContentValues values = new ContentValues();
        values.put(EXTRAS_KEY, key);
        values.put(EXTRAS_ACCOUNTS_ID, accountId);
        values.put(EXTRAS_VALUE, value);
        return db.insert(TABLE_EXTRAS, EXTRAS_KEY, values);
    }

    public void removeAccount(Account account) {
        synchronized (mAuthTokenCache) {
            ArrayList<AuthTokenKey> keysToRemove = Lists.newArrayList();
            for (AuthTokenKey key : mAuthTokenCache.keySet()) {
                if (key.mAccount.equals(account)) {
                    keysToRemove.add(key);
                }
            }
            for (AuthTokenKey key : keysToRemove) {
                mAuthTokenCache.remove(key);
            }

            final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            db.delete(TABLE_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                    new String[]{account.mName, account.mType});
            mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
        }
    }

    public void invalidateAuthToken(String accountType, String authToken) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            invalidateAuthToken(db, accountType, authToken);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void invalidateAuthToken(SQLiteDatabase db, String accountType, String authToken) {
        synchronized (mAuthTokenCache) {
            Cursor cursor = db.rawQuery(
                    "SELECT " + TABLE_AUTHTOKENS + "." + AUTHTOKENS_ID
                            + ", " + TABLE_ACCOUNTS + "." + ACCOUNTS_NAME
                            + ", " + TABLE_AUTHTOKENS + "." + AUTHTOKENS_TYPE
                            + " FROM " + TABLE_ACCOUNTS
                            + " JOIN " + TABLE_AUTHTOKENS
                            + " ON " + TABLE_ACCOUNTS + "." + ACCOUNTS_ID
                            + " = " + AUTHTOKENS_ACCOUNTS_ID
                            + " WHERE " + AUTHTOKENS_AUTHTOKEN + " = ? AND "
                            + TABLE_ACCOUNTS + "." + ACCOUNTS_TYPE + " = ?",
                    new String[]{authToken, accountType});
            try {
                while (cursor.moveToNext()) {
                    long authTokenId = cursor.getLong(0);
                    String accountName = cursor.getString(1);
                    String authTokenType = cursor.getString(2);
                    AuthTokenKey key = new AuthTokenKey(new Account(accountName, accountType),
                            authTokenType);
                    mAuthTokenCache.remove(key);
                    db.delete(TABLE_AUTHTOKENS, AUTHTOKENS_ID + "=" + authTokenId, null);
                }
            } finally {
                cursor.close();
            }
        }
    }

    private boolean saveAuthTokenToDatabase(Account account, String type, String authToken) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (saveAuthTokenToDatabase(db, account, type, authToken)) {
                mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
                db.setTransactionSuccessful();
                return true;
            }
            return false;
        } finally {
            db.endTransaction();
        }
    }

    private boolean saveAuthTokenToDatabase(SQLiteDatabase db, Account account, 
            String type, String authToken) {
        long accountId = getAccountId(db, account);
        if (accountId < 0) {
            return false;
        }
        db.delete(TABLE_AUTHTOKENS,
                AUTHTOKENS_ACCOUNTS_ID + "=" + accountId + " AND " + AUTHTOKENS_TYPE + "=?",
                new String[]{type});
        ContentValues values = new ContentValues();
        values.put(AUTHTOKENS_ACCOUNTS_ID, accountId);
        values.put(AUTHTOKENS_TYPE, type);
        values.put(AUTHTOKENS_AUTHTOKEN, authToken);
        return db.insert(TABLE_AUTHTOKENS, AUTHTOKENS_AUTHTOKEN, values) >= 0;
    }

    public String readAuthTokenFromDatabase(Account account, String authTokenType) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId < 0) {
                return null;
            }
            return getAuthToken(db, accountId, authTokenType);
        } finally {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
    }

    public String peekAuthToken(Account account, String authTokenType) {
        synchronized (mAuthTokenCache) {
            AuthTokenKey key = new AuthTokenKey(account, authTokenType);
            if (mAuthTokenCache.containsKey(key)) {
                return mAuthTokenCache.get(key);
            }
            return readAuthTokenFromDatabase(account, authTokenType);
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        cacheAuthToken(account, authTokenType, authToken);
    }

    public void setPassword(Account account, String password) {
        ContentValues values = new ContentValues();
        values.put(ACCOUNTS_PASSWORD, password);
        mOpenHelper.getWritableDatabase().update(TABLE_ACCOUNTS, values,
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[]{account.mName, account.mType});
        mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
    }

    public void clearPassword(Account account) {
        setPassword(account, null);
    }

    public void setUserData(Account account, String key, String value) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId < 0) {
                return;
            }
            long extrasId = getExtrasId(db, accountId, key);
            if (extrasId < 0 ) {
                extrasId = insertExtra(db, accountId, key, value);
                if (extrasId < 0) {
                    return;
                }
            } else {
                ContentValues values = new ContentValues();
                values.put(EXTRAS_VALUE, value);
                if (1 != db.update(TABLE_EXTRAS, values, EXTRAS_ID + "=" + extrasId, null)) {
                    return;
                }

            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void getAuthToken(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean notifyOnAuthFailure,
            final boolean expectActivityLaunch, final Bundle loginOptions) {
        String authToken = getCachedAuthToken(account, authTokenType);
        if (authToken != null) {
            try {
                Bundle result = new Bundle();
                result.putString(Constants.AUTHTOKEN_KEY, authToken);
                result.putString(Constants.ACCOUNT_NAME_KEY, account.mName);
                result.putString(Constants.ACCOUNT_TYPE_KEY, account.mType);
                response.onResult(result);
            } catch (RemoteException e) {
                // if the caller is dead then there is no one to care about remote exceptions
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "failure while notifying response", e);
                }
            }
            return;
        }

        new Session(response, account.mType, expectActivityLaunch) {
            protected String toDebugString(long now) {
                if (loginOptions != null) loginOptions.keySet();
                return super.toDebugString(now) + ", getAuthToken"
                        + ", " + account
                        + ", authTokenType " + authTokenType
                        + ", loginOptions " + loginOptions
                        + ", notifyOnAuthFailure " + notifyOnAuthFailure;
            }

            public void run() throws RemoteException {
                mAuthenticator.getAuthToken(this, account, authTokenType, loginOptions);
            }

            public void onResult(Bundle result) {
                if (result != null) {
                    String authToken = result.getString(Constants.AUTHTOKEN_KEY);
                    if (authToken != null) {
                        String name = result.getString(Constants.ACCOUNT_NAME_KEY);
                        String type = result.getString(Constants.ACCOUNT_TYPE_KEY);
                        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                            onError(Constants.ERROR_CODE_INVALID_RESPONSE,
                                    "the type and name should not be empty");
                            return;
                        }
                        cacheAuthToken(new Account(name, type), authTokenType, authToken);
                    }

                    Intent intent = result.getParcelable(Constants.INTENT_KEY);
                    if (intent != null && notifyOnAuthFailure) {
                        doNotification(result.getString(Constants.AUTH_FAILED_MESSAGE_KEY), intent);
                    }
                }
                super.onResult(result);
            }
        }.bind();
    }


    public void addAcount(final IAccountManagerResponse response,
            final String accountType, final String authTokenType,
            final boolean expectActivityLaunch, final Bundle options) {
        new Session(response, accountType, expectActivityLaunch) {
            public void run() throws RemoteException {
                mAuthenticator.addAccount(this, mAccountType, authTokenType, options);
            }

            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", addAccount"
                        + ", accountType " + accountType;
            }
        }.bind();
    }

    public void confirmCredentials(IAccountManagerResponse response,
            final Account account, final boolean expectActivityLaunch) {
        new Session(response, account.mType, expectActivityLaunch) {
            public void run() throws RemoteException {
                mAuthenticator.confirmCredentials(this, account);
            }
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", confirmCredentials"
                        + ", " + account;
            }
        }.bind();
    }

    public void confirmPassword(IAccountManagerResponse response, final Account account,
            final String password) {
        new Session(response, account.mType, false /* expectActivityLaunch */) {
            public void run() throws RemoteException {
                mAuthenticator.confirmPassword(this, account, password);
            }
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", confirmPassword"
                        + ", " + account;
            }
        }.bind();
    }

    public void updateCredentials(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean expectActivityLaunch,
            final Bundle loginOptions) {
        new Session(response, account.mType, expectActivityLaunch) {
            public void run() throws RemoteException {
                mAuthenticator.updateCredentials(this, account, authTokenType, loginOptions);
            }
            protected String toDebugString(long now) {
                if (loginOptions != null) loginOptions.keySet();
                return super.toDebugString(now) + ", updateCredentials"
                        + ", " + account
                        + ", authTokenType " + authTokenType
                        + ", loginOptions " + loginOptions;
            }
        }.bind();
    }

    public void editProperties(IAccountManagerResponse response, final String accountType,
            final boolean expectActivityLaunch) {
        new Session(response, accountType, expectActivityLaunch) {
            public void run() throws RemoteException {
                mAuthenticator.editProperties(this, mAccountType);
            }
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", editProperties"
                        + ", accountType " + accountType;
            }
        }.bind();
    }

    private boolean cacheAuthToken(Account account, String authTokenType, String authToken) {
        synchronized (mAuthTokenCache) {
            if (saveAuthTokenToDatabase(account, authTokenType, authToken)) {
                final AuthTokenKey key = new AuthTokenKey(account, authTokenType);
                mAuthTokenCache.put(key, authToken);
                return true;
            } else {
                return false;
            }
        }
    }

    private String getCachedAuthToken(Account account, String authTokenType) {
        synchronized (mAuthTokenCache) {
            final AuthTokenKey key = new AuthTokenKey(account, authTokenType);
            if (!mAuthTokenCache.containsKey(key)) return null;
            return mAuthTokenCache.get(key);
        }
    }

    private long getAccountId(SQLiteDatabase db, Account account) {
        Cursor cursor = db.query(TABLE_ACCOUNTS, new String[]{ACCOUNTS_ID},
                "name=? AND type=?", new String[]{account.mName, account.mType}, null, null, null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            }
            return -1;
        } finally {
            cursor.close();
        }
    }

    private long getExtrasId(SQLiteDatabase db, long accountId, String key) {
        Cursor cursor = db.query(TABLE_EXTRAS, new String[]{EXTRAS_ID},
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

    private String getAuthToken(SQLiteDatabase db, long accountId, String authTokenType) {
        Cursor cursor = db.query(TABLE_AUTHTOKENS, new String[]{AUTHTOKENS_AUTHTOKEN},
                AUTHTOKENS_ACCOUNTS_ID + "=" + accountId + " AND " + AUTHTOKENS_TYPE + "=?",
                new String[]{authTokenType},
                null, null, null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    private abstract class Session extends IAccountAuthenticatorResponse.Stub
            implements AuthenticatorBindHelper.Callback, IBinder.DeathRecipient {
        IAccountManagerResponse mResponse;
        final String mAccountType;
        final boolean mExpectActivityLaunch;
        final long mCreationTime;

        private int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;


        IAccountAuthenticator mAuthenticator = null;

        public Session(IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch) {
            super();
            if (response == null) throw new IllegalArgumentException("response is null");
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            synchronized (mSessions) {
                mSessions.put(toString(), this);
            }
            try {
                response.asBinder().linkToDeath(this, 0 /* flags */);
            } catch (RemoteException e) {
                mResponse = null;
                binderDied();
            }
        }

        IAccountManagerResponse getResponseAndClose() {
            if (mResponse == null) {
                // this session has already been closed
                return null;
            }
            IAccountManagerResponse response = mResponse;
            close(); // this clears mResponse so we need to save the response before this call
            return response;
        }

        private void close() {
            synchronized (mSessions) {
                if (mSessions.remove(toString()) == null) {
                    // the session was already closed, so bail out now
                    return;
                }
            }
            if (mResponse != null) {
                // stop listening for response deaths
                mResponse.asBinder().unlinkToDeath(this, 0 /* flags */);

                // clear this so that we don't accidentally send any further results
                mResponse = null;
            }
            cancelTimeout();
            unbind();
        }

        public void binderDied() {
            mResponse = null;
            close();
        }

        protected String toDebugString() {
            return toDebugString(SystemClock.elapsedRealtime());
        }

        protected String toDebugString(long now) {
            return "Session: expectLaunch " + mExpectActivityLaunch
                    + ", connected " + (mAuthenticator != null)
                    + ", stats (" + mNumResults + "/" + mNumRequestContinued
                    + "/" + mNumErrors + ")"
                    + ", lifetime " + ((now - mCreationTime) / 1000.0);
        }

        void bind() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "initiating bind to authenticator type " + mAccountType);
            }
            if (!mBindHelper.bind(mAccountType, this)) {
                Log.d(TAG, "bind attempt failed for " + toDebugString());
                onError(Constants.ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
            }
        }

        private void unbind() {
            if (mAuthenticator != null) {
                mAuthenticator = null;
                mBindHelper.unbind(this);
            }
        }

        public void scheduleTimeout() {
            mMessageHandler.sendMessageDelayed(
                    mMessageHandler.obtainMessage(MESSAGE_TIMED_OUT, this), TIMEOUT_DELAY_MS);
        }

        public void cancelTimeout() {
            mMessageHandler.removeMessages(MESSAGE_TIMED_OUT, this);
        }

        public void onConnected(IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);
            try {
                run();
            } catch (RemoteException e) {
                onError(Constants.ERROR_CODE_REMOTE_EXCEPTION,
                        "remote exception");
            }
        }

        public abstract void run() throws RemoteException;

        public void onDisconnected() {
            mAuthenticator = null;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                onError(Constants.ERROR_CODE_REMOTE_EXCEPTION,
                        "disconnected");
            }
        }

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                onError(Constants.ERROR_CODE_REMOTE_EXCEPTION,
                        "timeout");
            }
        }

        public void onResult(Bundle result) {
            mNumResults++;
            if (result != null && !TextUtils.isEmpty(result.getString(Constants.AUTHTOKEN_KEY))) {
                cancelNotification();
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(Constants.INTENT_KEY)) {
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        response.onError(Constants.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        response.onResult(result);
                    }
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        public void onRequestContinued() {
            mNumRequestContinued++;
        }

        public void onError(int errorCode, String errorMessage) {
            mNumErrors++;
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Session.onError: " + errorCode + ", " + errorMessage);
            }
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Session.onError: responding");
                }
                try {
                    response.onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onError: caught RemoteException while responding", e);
                    }
                }
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Session.onError: already closed");
                }
            }
        }
    }

    private class MessageHandler extends Handler {
        MessageHandler(Looper looper) {
            super(looper);
        }
        
        public void handleMessage(Message msg) {
            if (mBindHelper.handleMessage(msg)) {
                return;
            }
            switch (msg.what) {
                case MESSAGE_TIMED_OUT:
                    Session session = (Session)msg.obj;
                    session.onTimedOut();
                    break;

                default:
                    throw new IllegalStateException("unhandled message: " + msg.what);
            }
        }
    }

    private class DatabaseHelper extends SQLiteOpenHelper {
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
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

            db.execSQL("CREATE TABLE " + TABLE_META + " ( "
                    + META_KEY + " TEXT PRIMARY KEY NOT NULL, "
                    + META_VALUE + " TEXT)");

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
            Log.e(TAG, "upgrade from version " + oldVersion + " to version " + newVersion);

            if (oldVersion == 1) {
                db.execSQL(""
                        + " CREATE TRIGGER " + TABLE_ACCOUNTS + "Delete DELETE ON " + TABLE_ACCOUNTS
                        + " BEGIN"
                        + "   DELETE FROM " + TABLE_AUTHTOKENS
                        + "     WHERE " + AUTHTOKENS_ACCOUNTS_ID + " =OLD." + ACCOUNTS_ID + " ;"
                        + "   DELETE FROM " + TABLE_EXTRAS
                        + "     WHERE " + EXTRAS_ACCOUNTS_ID + " =OLD." + ACCOUNTS_ID + " ;"
                        + " END");
                oldVersion++;
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opened database " + DATABASE_NAME);
        }
    }

    private void setMetaValue(String key, String value) {
        ContentValues values = new ContentValues();
        values.put(META_KEY, key);
        values.put(META_VALUE, value);
        mOpenHelper.getWritableDatabase().replace(TABLE_META, META_KEY, values);
    }

    private String getMetaValue(String key) {
        Cursor c = mOpenHelper.getReadableDatabase().query(TABLE_META,
                new String[]{META_VALUE}, META_KEY + "=?", new String[]{key}, null, null, null);
        try {
            if (c.moveToNext()) {
                return c.getString(0);
            }
            return null;
        } finally {
            c.close();
        }
    }

    private class SimWatcher extends BroadcastReceiver {
        public SimWatcher(Context context) {
            // Re-scan the SIM card when the SIM state changes, and also if
            // the disk recovers from a full state (we may have failed to handle
            // things properly while the disk was full).
            final IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
            context.registerReceiver(this, filter);
        }
        
        /**
         * Compare the IMSI to the one stored in the login service's
         * database.  If they differ, erase all passwords and
         * authtokens (and store the new IMSI).
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check IMSI on every update; nothing happens if the IMSI is missing or unchanged.
            String imsi = ((TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE)).getSubscriberId();
            if (TextUtils.isEmpty(imsi)) return;

            String storedImsi = getMetaValue("imsi");

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "current IMSI=" + imsi + "; stored IMSI=" + storedImsi);
            }

            if (!imsi.equals(storedImsi) && !"initial".equals(storedImsi)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "wiping all passwords and authtokens");
                }
                SQLiteDatabase db = mOpenHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    db.execSQL("DELETE from " + TABLE_AUTHTOKENS);
                    db.execSQL("UPDATE " + TABLE_ACCOUNTS + " SET " + ACCOUNTS_PASSWORD + " = ''");
                    mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            setMetaValue("imsi", imsi);
        }
    }

    public IBinder onBind(Intent intent) {
        return asBinder();
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        synchronized (mSessions) {
            final long now = SystemClock.elapsedRealtime();
            fout.println("AccountManagerService: " + mSessions.size() + " sessions");
            for (Session session : mSessions.values()) {
                fout.println("  " + session.toDebugString(now));
            }
        }

        fout.println();

        mAuthenticatorCache.dump(fd, fout, args);
    }

    private void doNotification(CharSequence message, Intent intent) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "doNotification: " + message + " intent:" + intent);
        }

        // TODO(fredq) add this back in when we fix permissions
//       Notification n = new Notification(android.R.drawable.stat_sys_warning, null, 0 /* when */);
//        n.setLatestEventInfo(mContext, mContext.getText(R.string.notification_title), message,
//            PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT));
//        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
//                .notify(NOTIFICATION_ID, n);
    }

    private void cancelNotification() {
//        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
//            .cancel(NOTIFICATION_ID);
    }
}
