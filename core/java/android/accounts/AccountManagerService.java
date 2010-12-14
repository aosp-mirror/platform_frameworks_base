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

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.android.internal.R;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.TelephonyIntents;

/**
 * A system service that provides  account, password, and authtoken management for all
 * accounts on the device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. This service is not accessed by users directly,
 * instead one uses an instance of {@link AccountManager}, which can be accessed as follows:
 *    AccountManager accountManager =
 *      (AccountManager)context.getSystemService(Context.ACCOUNT_SERVICE)
 * @hide
 */
public class AccountManagerService
        extends IAccountManager.Stub
        implements RegisteredServicesCacheListener<AuthenticatorDescription> {
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";

    private static final String NO_BROADCAST_FLAG = "nobroadcast";

    private static final String TAG = "AccountManagerService";

    private static final int TIMEOUT_DELAY_MS = 1000 * 60;
    private static final String DATABASE_NAME = "accounts.db";
    private static final int DATABASE_VERSION = 4;

    private final Context mContext;

    private HandlerThread mMessageThread;
    private final MessageHandler mMessageHandler;

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;

    private final AccountAuthenticatorCache mAuthenticatorCache;
    private final DatabaseHelper mOpenHelper;
    private final SimWatcher mSimWatcher;

    private static final String TABLE_ACCOUNTS = "accounts";
    private static final String ACCOUNTS_ID = "_id";
    private static final String ACCOUNTS_NAME = "name";
    private static final String ACCOUNTS_TYPE = "type";
    private static final String ACCOUNTS_TYPE_COUNT = "count(type)";
    private static final String ACCOUNTS_PASSWORD = "password";

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

    private static final String[] ACCOUNT_NAME_TYPE_PROJECTION =
            new String[]{ACCOUNTS_ID, ACCOUNTS_NAME, ACCOUNTS_TYPE};
    private static final String[] ACCOUNT_TYPE_COUNT_PROJECTION =
            new String[] { ACCOUNTS_TYPE, ACCOUNTS_TYPE_COUNT};
    private static final Intent ACCOUNTS_CHANGED_INTENT;

    private static final String COUNT_OF_MATCHING_GRANTS = ""
            + "SELECT COUNT(*) FROM " + TABLE_GRANTS + ", " + TABLE_ACCOUNTS
            + " WHERE " + GRANTS_ACCOUNTS_ID + "=" + ACCOUNTS_ID
            + " AND " + GRANTS_GRANTEE_UID + "=?"
            + " AND " + GRANTS_AUTH_TOKEN_TYPE + "=?"
            + " AND " + ACCOUNTS_NAME + "=?"
            + " AND " + ACCOUNTS_TYPE + "=?";

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private final AtomicInteger mNotificationIds = new AtomicInteger(1);

    private final HashMap<Pair<Pair<Account, String>, Integer>, Integer>
            mCredentialsPermissionNotificationIds =
            new HashMap<Pair<Pair<Account, String>, Integer>, Integer>();
    private final HashMap<Account, Integer> mSigninRequiredNotificationIds =
            new HashMap<Account, Integer>();
    private static AtomicReference<AccountManagerService> sThis =
            new AtomicReference<AccountManagerService>();

    private static final boolean isDebuggableMonkeyBuild =
            SystemProperties.getBoolean("ro.monkey", false);
    private static final Account[] EMPTY_ACCOUNT_ARRAY = new Account[]{};

    static {
        ACCOUNTS_CHANGED_INTENT = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
        ACCOUNTS_CHANGED_INTENT.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
    }

    /**
     * This should only be called by system code. One should only call this after the service
     * has started.
     * @return a reference to the AccountManagerService instance
     * @hide
     */
    public static AccountManagerService getSingleton() {
        return sThis.get();
    }

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
        mAuthenticatorCache.setListener(this, null /* Handler */);

        mSimWatcher = new SimWatcher(mContext);
        sThis.set(this);

        validateAccounts();
    }

    private void validateAccounts() {
        boolean accountDeleted = false;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor cursor = db.query(TABLE_ACCOUNTS,
                new String[]{ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME},
                null, null, null, null, null);
        try {
            while (cursor.moveToNext()) {
                final long accountId = cursor.getLong(0);
                final String accountType = cursor.getString(1);
                final String accountName = cursor.getString(2);
                if (mAuthenticatorCache.getServiceInfo(AuthenticatorDescription.newKey(accountType))
                        == null) {
                    Log.d(TAG, "deleting account " + accountName + " because type "
                            + accountType + " no longer has a registered authenticator");
                    db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null);
                    accountDeleted = true;
                }
            }
        } finally {
            cursor.close();
            if (accountDeleted) {
                sendAccountsChangedBroadcast();
            }
        }
    }

    public void onServiceChanged(AuthenticatorDescription desc, boolean removed) {
        boolean accountDeleted = false;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor cursor = db.query(TABLE_ACCOUNTS,
                new String[]{ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME},
                ACCOUNTS_TYPE + "=?", new String[]{desc.type}, null, null, null);
        try {
            while (cursor.moveToNext()) {
                final long accountId = cursor.getLong(0);
                final String accountType = cursor.getString(1);
                final String accountName = cursor.getString(2);
                Log.d(TAG, "deleting account " + accountName + " because type "
                        + accountType + " no longer has a registered authenticator");
                db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null);
                accountDeleted = true;
            }
        } finally {
            cursor.close();
            if (accountDeleted) {
                sendAccountsChangedBroadcast();
            }
        }
    }

    public String getPassword(Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);

        long identityToken = clearCallingIdentity();
        try {
            return readPasswordFromDatabase(account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPasswordFromDatabase(Account account) {
        if (account == null) {
            return null;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = db.query(TABLE_ACCOUNTS, new String[]{ACCOUNTS_PASSWORD},
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[]{account.name, account.type}, null, null, null);
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
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            return readUserDataFromDatabase(account, key);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readUserDataFromDatabase(Account account, String key) {
        if (account == null) {
            return null;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = db.query(TABLE_EXTRAS, new String[]{EXTRAS_VALUE},
                EXTRAS_ACCOUNTS_ID
                        + "=(select _id FROM accounts WHERE name=? AND type=?) AND "
                        + EXTRAS_KEY + "=?",
                new String[]{account.name, account.type, key}, null, null, null);
        try {
            if (cursor.moveToNext()) {
                return cursor.getString(0);
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public AuthenticatorDescription[] getAuthenticatorTypes() {
        long identityToken = clearCallingIdentity();
        try {
            Collection<AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription>>
                    authenticatorCollection = mAuthenticatorCache.getAllServices();
            AuthenticatorDescription[] types =
                    new AuthenticatorDescription[authenticatorCollection.size()];
            int i = 0;
            for (AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription> authenticator
                    : authenticatorCollection) {
                types[i] = authenticator.type;
                i++;
            }
            return types;
        } finally {
            restoreCallingIdentity(identityToken);
        }
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
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);

        // fails if the account already exists
        long identityToken = clearCallingIdentity();
        try {
            return insertAccountIntoDatabase(account, password, extras);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean insertAccountIntoDatabase(Account account, String password, Bundle extras) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (account == null) {
                return false;
            }
            boolean noBroadcast = false;
            if (account.type.equals(GOOGLE_ACCOUNT_TYPE)) {
                // Look for the 'nobroadcast' flag and remove it since we don't want it to persist
                // in the db.
                noBroadcast = extras.getBoolean(NO_BROADCAST_FLAG, false);
                extras.remove(NO_BROADCAST_FLAG);
            }

            long numMatches = DatabaseUtils.longForQuery(db,
                    "select count(*) from " + TABLE_ACCOUNTS
                            + " WHERE " + ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                    new String[]{account.name, account.type});
            if (numMatches > 0) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_NAME, account.name);
            values.put(ACCOUNTS_TYPE, account.type);
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
            if (!noBroadcast) {
                sendAccountsChangedBroadcast();
            }
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

    public void hasFeatures(IAccountManagerResponse response,
            Account account, String[] features) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (features == null) throw new IllegalArgumentException("features is null");
        checkReadAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new TestFeaturesSession(response, account, features).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class TestFeaturesSession extends Session {
        private final String[] mFeatures;
        private final Account mAccount;

        public TestFeaturesSession(IAccountManagerResponse response,
                Account account, String[] features) {
            super(response, account.type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */);
            mFeatures = features;
            mAccount = account;
        }

        public void run() throws RemoteException {
            try {
                mAuthenticator.hasFeatures(this, mAccount, mFeatures);
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        public void onResult(Bundle result) {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    if (result == null) {
                        onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                        return;
                    }
                    final Bundle newResult = new Bundle();
                    newResult.putBoolean(AccountManager.KEY_BOOLEAN_RESULT,
                            result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false));
                    response.onResult(newResult);
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", hasFeatures"
                    + ", " + mAccount
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    public void removeAccount(IAccountManagerResponse response, Account account) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new RemoveAccountSession(response, account).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class RemoveAccountSession extends Session {
        final Account mAccount;
        public RemoveAccountSession(IAccountManagerResponse response, Account account) {
            super(response, account.type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */);
            mAccount = account;
        }

        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", removeAccount"
                    + ", account " + mAccount;
        }

        public void run() throws RemoteException {
            mAuthenticator.getAccountRemovalAllowed(this, mAccount);
        }

        public void onResult(Bundle result) {
            if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)) {
                final boolean removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                if (removalAllowed) {
                    removeAccount(mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    Bundle result2 = new Bundle();
                    result2.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, removalAllowed);
                    try {
                        response.onResult(result2);
                    } catch (RemoteException e) {
                        // ignore
                    }
                }
            }
            super.onResult(result);
        }
    }

    private void removeAccount(Account account) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.delete(TABLE_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[]{account.name, account.type});
        sendAccountsChangedBroadcast();
    }

    public void invalidateAuthToken(String accountType, String authToken) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (authToken == null) throw new IllegalArgumentException("authToken is null");
        checkManageAccountsOrUseCredentialsPermissions();
        long identityToken = clearCallingIdentity();
        try {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                invalidateAuthToken(db, accountType, authToken);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void invalidateAuthToken(SQLiteDatabase db, String accountType, String authToken) {
        if (authToken == null || accountType == null) {
            return;
        }
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
                db.delete(TABLE_AUTHTOKENS, AUTHTOKENS_ID + "=" + authTokenId, null);
            }
        } finally {
            cursor.close();
        }
    }

    private boolean saveAuthTokenToDatabase(Account account, String type, String authToken) {
        if (account == null || type == null) {
            return false;
        }
        cancelNotification(getSigninRequiredNotificationId(account));
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
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
            if (db.insert(TABLE_AUTHTOKENS, AUTHTOKENS_AUTHTOKEN, values) >= 0) {
                db.setTransactionSuccessful();
                return true;
            }
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public String readAuthTokenFromDatabase(Account account, String authTokenType) {
        if (account == null || authTokenType == null) {
            return null;
        }
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = db.query(TABLE_AUTHTOKENS, new String[]{AUTHTOKENS_AUTHTOKEN},
                AUTHTOKENS_ACCOUNTS_ID + "=(select _id FROM accounts WHERE name=? AND type=?) AND "
                        + AUTHTOKENS_TYPE + "=?",
                new String[]{account.name, account.type, authTokenType},
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

    public String peekAuthToken(Account account, String authTokenType) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            return readAuthTokenFromDatabase(account, authTokenType);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            saveAuthTokenToDatabase(account, authTokenType, authToken);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setPassword(Account account, String password) {
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            setPasswordInDB(account, password);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void setPasswordInDB(Account account, String password) {
        if (account == null) {
            return;
        }
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_PASSWORD, password);
            final long accountId = getAccountId(db, account);
            if (accountId >= 0) {
                final String[] argsAccountId = {String.valueOf(accountId)};
                db.update(TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId);
                db.delete(TABLE_AUTHTOKENS, AUTHTOKENS_ACCOUNTS_ID + "=?", argsAccountId);
                db.setTransactionSuccessful();
            }
        } finally {
            db.endTransaction();
        }
        sendAccountsChangedBroadcast();
    }

    private void sendAccountsChangedBroadcast() {
        mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
    }

    public void clearPassword(Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            setPasswordInDB(account, null);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setUserData(Account account, String key, String value) {
        if (key == null) throw new IllegalArgumentException("key is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        if (account == null) {
            return;
        }
        if (account.type.equals(GOOGLE_ACCOUNT_TYPE) && key.equals("broadcast")) {
            sendAccountsChangedBroadcast();
            return;
        }
        try {
            writeUserdataIntoDatabase(account, key, value);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void writeUserdataIntoDatabase(Account account, String key, String value) {
        if (account == null || key == null) {
            return;
        }
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

    private void onResult(IAccountManagerResponse response, Bundle result) {
        try {
            response.onResult(result);
        } catch (RemoteException e) {
            // if the caller is dead then there is no one to care about remote
            // exceptions
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }

    void getAuthTokenLabel(final IAccountManagerResponse response,
            final Account account, final String authTokenType) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");

        checkBinderPermission(Manifest.permission.USE_CREDENTIALS);

        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, false,
                    false /* stripAuthTokenFromResult */) {
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAuthTokenLabel"
                            + ", " + account
                            + ", authTokenType " + authTokenType;
                }

                public void run() throws RemoteException {
                    mAuthenticator.getAuthTokenLabel(this, authTokenType);
                }

                public void onResult(Bundle result) {
                    if (result != null) {
                        String label = result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL);
                        Bundle bundle = new Bundle();
                        bundle.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, label);
                        super.onResult(bundle);
                        return;
                    } else {
                        super.onResult(result);
                    }
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void getAuthToken(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean notifyOnAuthFailure,
            final boolean expectActivityLaunch, final Bundle loginOptions) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkBinderPermission(Manifest.permission.USE_CREDENTIALS);
        final int callerUid = Binder.getCallingUid();
        final boolean permissionGranted = permissionIsGranted(account, authTokenType, callerUid);

        long identityToken = clearCallingIdentity();
        try {
            // if the caller has permission, do the peek. otherwise go the more expensive
            // route of starting a Session
            if (permissionGranted) {
                String authToken = readAuthTokenFromDatabase(account, authTokenType);
                if (authToken != null) {
                    Bundle result = new Bundle();
                    result.putString(AccountManager.KEY_AUTHTOKEN, authToken);
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                    onResult(response, result);
                    return;
                }
            }

            new Session(response, account.type, expectActivityLaunch,
                    false /* stripAuthTokenFromResult */) {
                protected String toDebugString(long now) {
                    if (loginOptions != null) loginOptions.keySet();
                    return super.toDebugString(now) + ", getAuthToken"
                            + ", " + account
                            + ", authTokenType " + authTokenType
                            + ", loginOptions " + loginOptions
                            + ", notifyOnAuthFailure " + notifyOnAuthFailure;
                }

                public void run() throws RemoteException {
                    // If the caller doesn't have permission then create and return the
                    // "grant permission" intent instead of the "getAuthToken" intent.
                    if (!permissionGranted) {
                        mAuthenticator.getAuthTokenLabel(this, authTokenType);
                    } else {
                        mAuthenticator.getAuthToken(this, account, authTokenType, loginOptions);
                    }
                }

                public void onResult(Bundle result) {
                    if (result != null) {
                        if (result.containsKey(AccountManager.KEY_AUTH_TOKEN_LABEL)) {
                            Intent intent = newGrantCredentialsPermissionIntent(account, callerUid,
                                    new AccountAuthenticatorResponse(this),
                                    authTokenType,
                                    result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL));
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(AccountManager.KEY_INTENT, intent);
                            onResult(bundle);
                            return;
                        }
                        String authToken = result.getString(AccountManager.KEY_AUTHTOKEN);
                        if (authToken != null) {
                            String name = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                            String type = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                            if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                                onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                        "the type and name should not be empty");
                                return;
                            }
                            saveAuthTokenToDatabase(new Account(name, type),
                                    authTokenType, authToken);
                        }

                        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                        if (intent != null && notifyOnAuthFailure) {
                            doNotification(
                                    account, result.getString(AccountManager.KEY_AUTH_FAILED_MESSAGE),
                                    intent);
                        }
                    }
                    super.onResult(result);
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void createNoCredentialsPermissionNotification(Account account, Intent intent) {
        int uid = intent.getIntExtra(
                GrantCredentialsPermissionActivity.EXTRAS_REQUESTING_UID, -1);
        String authTokenType = intent.getStringExtra(
                GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE);
        String authTokenLabel = intent.getStringExtra(
                GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_LABEL);

        Notification n = new Notification(android.R.drawable.stat_sys_warning, null,
                0 /* when */);
        final String titleAndSubtitle =
                mContext.getString(R.string.permission_request_notification_with_subtitle,
                account.name);
        final int index = titleAndSubtitle.indexOf('\n');
        final String title = titleAndSubtitle.substring(0, index);
        final String subtitle = titleAndSubtitle.substring(index + 1);
        n.setLatestEventInfo(mContext,
                title, subtitle,
                PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT));
        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(getCredentialPermissionNotificationId(account, authTokenType, uid), n);
    }

    String getAccountLabel(String accountType) {
        RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo =
            mAuthenticatorCache.getServiceInfo(
                    AuthenticatorDescription.newKey(accountType));
        if (serviceInfo == null) {
            throw new IllegalArgumentException("unknown account type: " + accountType);
        }

        final Context authContext;
        try {
            authContext = mContext.createPackageContext(
                    serviceInfo.type.packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("unknown account type: " + accountType);
        }
        return authContext.getString(serviceInfo.type.labelId);
    }

    private Intent newGrantCredentialsPermissionIntent(Account account, int uid,
            AccountAuthenticatorResponse response, String authTokenType, String authTokenLabel) {

        Intent intent = new Intent(mContext, GrantCredentialsPermissionActivity.class);
        intent.addCategory(
                String.valueOf(getCredentialPermissionNotificationId(account, authTokenType, uid)));

        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_ACCOUNT, account);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE, authTokenType);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_RESPONSE, response);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_REQUESTING_UID, uid);

        return intent;
    }

    private Integer getCredentialPermissionNotificationId(Account account, String authTokenType,
            int uid) {
        Integer id;
        synchronized(mCredentialsPermissionNotificationIds) {
            final Pair<Pair<Account, String>, Integer> key =
                    new Pair<Pair<Account, String>, Integer>(
                            new Pair<Account, String>(account, authTokenType), uid);
            id = mCredentialsPermissionNotificationIds.get(key);
            if (id == null) {
                id = mNotificationIds.incrementAndGet();
                mCredentialsPermissionNotificationIds.put(key, id);
            }
        }
        return id;
    }

    private Integer getSigninRequiredNotificationId(Account account) {
        Integer id;
        synchronized(mSigninRequiredNotificationIds) {
            id = mSigninRequiredNotificationIds.get(account);
            if (id == null) {
                id = mNotificationIds.incrementAndGet();
                mSigninRequiredNotificationIds.put(account, id);
            }
        }
        return id;
    }


    public void addAcount(final IAccountManagerResponse response, final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final boolean expectActivityLaunch, final Bundle options) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */) {
                public void run() throws RemoteException {
                    mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                            options);
                }

                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", addAccount"
                            + ", accountType " + accountType
                            + ", requiredFeatures "
                            + (requiredFeatures != null
                              ? TextUtils.join(",", requiredFeatures)
                              : null);
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void confirmCredentials(IAccountManagerResponse response,
            final Account account, final Bundle options, final boolean expectActivityLaunch) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */) {
                public void run() throws RemoteException {
                    mAuthenticator.confirmCredentials(this, account, options);
                }
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", confirmCredentials"
                            + ", " + account;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void updateCredentials(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean expectActivityLaunch,
            final Bundle loginOptions) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */) {
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
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void editProperties(IAccountManagerResponse response, final String accountType,
            final boolean expectActivityLaunch) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */) {
                public void run() throws RemoteException {
                    mAuthenticator.editProperties(this, mAccountType);
                }
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", editProperties"
                            + ", accountType " + accountType;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class GetAccountsByTypeAndFeatureSession extends Session {
        private final String[] mFeatures;
        private volatile Account[] mAccountsOfType = null;
        private volatile ArrayList<Account> mAccountsWithFeatures = null;
        private volatile int mCurrentAccount = 0;

        public GetAccountsByTypeAndFeatureSession(IAccountManagerResponse response,
            String type, String[] features) {
            super(response, type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */);
            mFeatures = features;
        }

        public void run() throws RemoteException {
            mAccountsOfType = getAccountsByType(mAccountType);
            // check whether each account matches the requested features
            mAccountsWithFeatures = new ArrayList<Account>(mAccountsOfType.length);
            mCurrentAccount = 0;

            checkAccount();
        }

        public void checkAccount() {
            if (mCurrentAccount >= mAccountsOfType.length) {
                sendResult();
                return;
            }

            final IAccountAuthenticator accountAuthenticator = mAuthenticator;
            if (accountAuthenticator == null) {
                // It is possible that the authenticator has died, which is indicated by
                // mAuthenticator being set to null. If this happens then just abort.
                // There is no need to send back a result or error in this case since
                // that already happened when mAuthenticator was cleared.
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "checkAccount: aborting session since we are no longer"
                            + " connected to the authenticator, " + toDebugString());
                }
                return;
            }
            try {
                accountAuthenticator.hasFeatures(this, mAccountsOfType[mCurrentAccount], mFeatures);
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        public void onResult(Bundle result) {
            mNumResults++;
            if (result == null) {
                onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                return;
            }
            if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                mAccountsWithFeatures.add(mAccountsOfType[mCurrentAccount]);
            }
            mCurrentAccount++;
            checkAccount();
        }

        public void sendResult() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    Account[] accounts = new Account[mAccountsWithFeatures.size()];
                    for (int i = 0; i < accounts.length; i++) {
                        accounts[i] = mAccountsWithFeatures.get(i);
                    }
                    Bundle result = new Bundle();
                    result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                    response.onResult(result);
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }


        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", getAccountsByTypeAndFeatures"
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    public Account[] getAccounts(String type) {
        checkReadAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            return getAccountsByType(type);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void getAccountsByFeatures(IAccountManagerResponse response,
            String type, String[] features) {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");
        checkReadAccountsPermission();
        if (features != null && type == null) {
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "type is null");
                } catch (RemoteException e) {
                    // ignore this
                }
            }
            return;
        }
        long identityToken = clearCallingIdentity();
        try {
            if (features == null || features.length == 0) {
                Account[] accounts = getAccountsByType(type);
                Bundle result = new Bundle();
                result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                onResult(response, result);
                return;
            }
            new GetAccountsByTypeAndFeatureSession(response, type, features).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private long getAccountId(SQLiteDatabase db, Account account) {
        Cursor cursor = db.query(TABLE_ACCOUNTS, new String[]{ACCOUNTS_ID},
                "name=? AND type=?", new String[]{account.name, account.type}, null, null, null);
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

    private abstract class Session extends IAccountAuthenticatorResponse.Stub
            implements IBinder.DeathRecipient, ServiceConnection {
        IAccountManagerResponse mResponse;
        final String mAccountType;
        final boolean mExpectActivityLaunch;
        final long mCreationTime;

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;


        IAccountAuthenticator mAuthenticator = null;

        private final boolean mStripAuthTokenFromResult;

        public Session(IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch, boolean stripAuthTokenFromResult) {
            super();
            if (response == null) throw new IllegalArgumentException("response is null");
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
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
            if (!bindToAuthenticator(mAccountType)) {
                Log.d(TAG, "bind attempt failed for " + toDebugString());
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
            }
        }

        private void unbind() {
            if (mAuthenticator != null) {
                mAuthenticator = null;
                mContext.unbindService(this);
            }
        }

        public void scheduleTimeout() {
            mMessageHandler.sendMessageDelayed(
                    mMessageHandler.obtainMessage(MESSAGE_TIMED_OUT, this), TIMEOUT_DELAY_MS);
        }

        public void cancelTimeout() {
            mMessageHandler.removeMessages(MESSAGE_TIMED_OUT, this);
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);
            try {
                run();
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "remote exception");
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            mAuthenticator = null;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "disconnected");
            }
        }

        public abstract void run() throws RemoteException;

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "timeout");
            }
        }

        public void onResult(Bundle result) {
            mNumResults++;
            if (result != null && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
                String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);
                    cancelNotification(getSigninRequiredNotificationId(account));
                }
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(AccountManager.KEY_INTENT)) {
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN);
                        }
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

        /**
         * find the component name for the authenticator and initiate a bind
         * if no authenticator or the bind fails then return false, otherwise return true
         */
        private boolean bindToAuthenticator(String authenticatorType) {
            AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo =
                    mAuthenticatorCache.getServiceInfo(
                            AuthenticatorDescription.newKey(authenticatorType));
            if (authenticatorInfo == null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "there is no authenticator for " + authenticatorType
                            + ", bailing out");
                }
                return false;
            }

            Intent intent = new Intent();
            intent.setAction(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            intent.setComponent(authenticatorInfo.componentName);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "performing bindService to " + authenticatorInfo.componentName);
            }
            if (!mContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "bindService to " + authenticatorInfo.componentName + " failed");
                }
                return false;
            }


            return true;
        }
    }

    private class MessageHandler extends Handler {
        MessageHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
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

    private static String getDatabaseName() {
        if(Environment.isEncryptedFilesystemEnabled()) {
            // Hard-coded path in case of encrypted file system
            return Environment.getSystemSecureDirectory().getPath() + File.separator + DATABASE_NAME;
        } else {
            // Regular path in case of non-encrypted file system
            return DATABASE_NAME;
        }
    }

    private class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context) {
            super(context, AccountManagerService.getDatabaseName(), null, DATABASE_VERSION);
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

            createGrantsTable(db);

            db.execSQL("CREATE TABLE " + TABLE_EXTRAS + " ( "
                    + EXTRAS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + EXTRAS_ACCOUNTS_ID + " INTEGER, "
                    + EXTRAS_KEY + " TEXT NOT NULL, "
                    + EXTRAS_VALUE + " TEXT, "
                    + "UNIQUE(" + EXTRAS_ACCOUNTS_ID + "," + EXTRAS_KEY + "))");

            db.execSQL("CREATE TABLE " + TABLE_META + " ( "
                    + META_KEY + " TEXT PRIMARY KEY NOT NULL, "
                    + META_VALUE + " TEXT)");

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
            // Check IMSI on every update; nothing happens if the IMSI
            // is missing or unchanged.
            TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.w(TAG, "failed to get TelephonyManager");
                return;
            }
            String imsi = telephonyManager.getSubscriberId();

            // If the subscriber ID is an empty string, don't do anything.
            if (TextUtils.isEmpty(imsi)) return;

            // If the current IMSI matches what's stored, don't do anything.
            String storedImsi = getMetaValue("imsi");
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "current IMSI=" + imsi + "; stored IMSI=" + storedImsi);
            }
            if (imsi.equals(storedImsi)) return;

            // If a CDMA phone is unprovisioned, getSubscriberId()
            // will return a different value, but we *don't* erase the
            // passwords.  We only erase them if it has a different
            // subscriber ID once it's provisioned.
            if (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                IBinder service = ServiceManager.checkService(Context.TELEPHONY_SERVICE);
                if (service == null) {
                    Log.w(TAG, "call to checkService(TELEPHONY_SERVICE) failed");
                    return;
                }
                ITelephony telephony = ITelephony.Stub.asInterface(service);
                if (telephony == null) {
                    Log.w(TAG, "failed to get ITelephony interface");
                    return;
                }
                boolean needsProvisioning;
                try {
                    needsProvisioning = telephony.getCdmaNeedsProvisioning();
                } catch (RemoteException e) {
                    Log.w(TAG, "exception while checking provisioning", e);
                    // default to NOT wiping out the passwords
                    needsProvisioning = true;
                }
                if (needsProvisioning) {
                    // if the phone needs re-provisioning, don't do anything.
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "current IMSI=" + imsi + " (needs provisioning); stored IMSI=" +
                              storedImsi);
                    }
                    return;
                }
            }

            if (!imsi.equals(storedImsi) && !TextUtils.isEmpty(storedImsi)) {
                Log.w(TAG, "wiping all passwords and authtokens because IMSI changed ("
                        + "stored=" + storedImsi + ", current=" + imsi + ")");
                SQLiteDatabase db = mOpenHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    db.execSQL("DELETE from " + TABLE_AUTHTOKENS);
                    db.execSQL("UPDATE " + TABLE_ACCOUNTS + " SET " + ACCOUNTS_PASSWORD + " = ''");
                    sendAccountsChangedBroadcast();
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

    /**
     * Searches array of arguments for the specified string
     * @param args array of argument strings
     * @param value value to search for
     * @return true if the value is contained in the array
     */
    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        final boolean isCheckinRequest = scanArgs(args, "--checkin") || scanArgs(args, "-c");

        if (isCheckinRequest) {
            // This is a checkin request. *Only* upload the account types and the count of each.
            SQLiteDatabase db = mOpenHelper.getReadableDatabase();

            Cursor cursor = db.query(TABLE_ACCOUNTS, ACCOUNT_TYPE_COUNT_PROJECTION,
                    null, null, ACCOUNTS_TYPE, null, null);
            try {
                while (cursor.moveToNext()) {
                    // print type,count
                    fout.println(cursor.getString(0) + "," + cursor.getString(1));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else {
            Account[] accounts = getAccountsByType(null /* type */);
            fout.println("Accounts: " + accounts.length);
            for (Account account : accounts) {
                fout.println("  " + account);
            }

            fout.println();
            synchronized (mSessions) {
                final long now = SystemClock.elapsedRealtime();
                fout.println("Active Sessions: " + mSessions.size());
                for (Session session : mSessions.values()) {
                    fout.println("  " + session.toDebugString(now));
                }
            }

            fout.println();
            mAuthenticatorCache.dump(fd, fout, args);
        }
    }

    private void doNotification(Account account, CharSequence message, Intent intent) {
        long identityToken = clearCallingIdentity();
        try {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "doNotification: " + message + " intent:" + intent);
            }

            if (intent.getComponent() != null &&
                    GrantCredentialsPermissionActivity.class.getName().equals(
                            intent.getComponent().getClassName())) {
                createNoCredentialsPermissionNotification(account, intent);
            } else {
                final Integer notificationId = getSigninRequiredNotificationId(account);
                intent.addCategory(String.valueOf(notificationId));
                Notification n = new Notification(android.R.drawable.stat_sys_warning, null,
                        0 /* when */);
                final String notificationTitleFormat =
                        mContext.getText(R.string.notification_title).toString();
                n.setLatestEventInfo(mContext,
                        String.format(notificationTitleFormat, account.name),
                        message, PendingIntent.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT));
                ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(notificationId, n);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void cancelNotification(int id) {
        long identityToken = clearCallingIdentity();
        try {
            ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(id);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /** Succeeds if any of the specified permissions are granted. */
    private void checkBinderPermission(String... permissions) {
        final int uid = Binder.getCallingUid();

        for (String perm : permissions) {
            if (mContext.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "caller uid " + uid + " has " + perm);
                }
                return;
            }
        }

        String msg = "caller uid " + uid + " lacks any of " + TextUtils.join(",", permissions);
        Log.w(TAG, msg);
        throw new SecurityException(msg);
    }

    private boolean inSystemImage(int callerUid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(callerUid);
        for (String name : packages) {
            try {
                PackageInfo packageInfo =
                        mContext.getPackageManager().getPackageInfo(name, 0 /* flags */);
                if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    private boolean permissionIsGranted(Account account, String authTokenType, int callerUid) {
        final boolean inSystemImage = inSystemImage(callerUid);
        final boolean fromAuthenticator = account != null
                && hasAuthenticatorUid(account.type, callerUid);
        final boolean hasExplicitGrants = account != null
                && hasExplicitlyGrantedPermission(account, authTokenType);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "checkGrantsOrCallingUidAgainstAuthenticator: caller uid "
                    + callerUid + ", account " + account
                    + ": is authenticator? " + fromAuthenticator
                    + ", has explicit permission? " + hasExplicitGrants);
        }
        return fromAuthenticator || hasExplicitGrants || inSystemImage;
    }

    private boolean hasAuthenticatorUid(String accountType, int callingUid) {
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo :
                mAuthenticatorCache.getAllServices()) {
            if (serviceInfo.type.type.equals(accountType)) {
                return (serviceInfo.uid == callingUid) ||
                        (mContext.getPackageManager().checkSignatures(serviceInfo.uid, callingUid)
                                == PackageManager.SIGNATURE_MATCH);
            }
        }
        return false;
    }

    private boolean hasExplicitlyGrantedPermission(Account account, String authTokenType) {
        if (Binder.getCallingUid() == android.os.Process.SYSTEM_UID) {
            return true;
        }
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        String[] args = {String.valueOf(Binder.getCallingUid()), authTokenType,
                account.name, account.type};
        final boolean permissionGranted =
                DatabaseUtils.longForQuery(db, COUNT_OF_MATCHING_GRANTS, args) != 0;
        if (!permissionGranted && isDebuggableMonkeyBuild) {
            // TODO: Skip this check when running automated tests. Replace this
            // with a more general solution.
            Log.d(TAG, "no credentials permission for usage of " + account + ", "
                    + authTokenType + " by uid " + Binder.getCallingUid()
                    + " but ignoring since this is a monkey build");
            return true;
        }
        return permissionGranted;
    }

    private void checkCallingUidAgainstAuthenticator(Account account) {
        final int uid = Binder.getCallingUid();
        if (account == null || !hasAuthenticatorUid(account.type, uid)) {
            String msg = "caller uid " + uid + " is different than the authenticator's uid";
            Log.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "caller uid " + uid + " is the same as the authenticator's uid");
        }
    }

    private void checkAuthenticateAccountsPermission(Account account) {
        checkBinderPermission(Manifest.permission.AUTHENTICATE_ACCOUNTS);
        checkCallingUidAgainstAuthenticator(account);
    }

    private void checkReadAccountsPermission() {
        checkBinderPermission(Manifest.permission.GET_ACCOUNTS);
    }

    private void checkManageAccountsPermission() {
        checkBinderPermission(Manifest.permission.MANAGE_ACCOUNTS);
    }

    private void checkManageAccountsOrUseCredentialsPermissions() {
        checkBinderPermission(Manifest.permission.MANAGE_ACCOUNTS,
                Manifest.permission.USE_CREDENTIALS);
    }

    /**
     * Allow callers with the given uid permission to get credentials for account/authTokenType.
     * <p>
     * Although this is public it can only be accessed via the AccountManagerService object
     * which is in the system. This means we don't need to protect it with permissions.
     * @hide
     */
    public void grantAppPermission(Account account, String authTokenType, int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "grantAppPermission: called with invalid arguments", new Exception());
            return;
        }
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId >= 0) {
                ContentValues values = new ContentValues();
                values.put(GRANTS_ACCOUNTS_ID, accountId);
                values.put(GRANTS_AUTH_TOKEN_TYPE, authTokenType);
                values.put(GRANTS_GRANTEE_UID, uid);
                db.insert(TABLE_GRANTS, GRANTS_ACCOUNTS_ID, values);
                db.setTransactionSuccessful();
            }
        } finally {
            db.endTransaction();
        }
        cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid));
    }

    /**
     * Don't allow callers with the given uid permission to get credentials for
     * account/authTokenType.
     * <p>
     * Although this is public it can only be accessed via the AccountManagerService object
     * which is in the system. This means we don't need to protect it with permissions.
     * @hide
     */
    public void revokeAppPermission(Account account, String authTokenType, int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "revokeAppPermission: called with invalid arguments", new Exception());
            return;
        }
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId >= 0) {
                db.delete(TABLE_GRANTS,
                        GRANTS_ACCOUNTS_ID + "=? AND " + GRANTS_AUTH_TOKEN_TYPE + "=? AND "
                                + GRANTS_GRANTEE_UID + "=?",
                        new String[]{String.valueOf(accountId), authTokenType,
                                String.valueOf(uid)});
                db.setTransactionSuccessful();
            }
        } finally {
            db.endTransaction();
        }
        cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid));
    }
}
