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
import android.content.pm.PackageManager;
import android.content.pm.RegisteredServicesCache;
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
import android.os.Binder;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.Notification;
import android.Manifest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.R;

/**
 * A system service that provides  account, password, and authtoken management for all
 * accounts on the device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. This service is not accessed by users directly,
 * instead one uses an instance of {@link AccountManager}, which can be accessed as follows:
 *    AccountManager accountManager =
 *      (AccountManager)context.getSystemService(Context.ACCOUNT_SERVICE)
 * @hide
 */
public class AccountManagerService extends IAccountManager.Stub {
    private static final String TAG = "AccountManagerService";

    private static final int TIMEOUT_DELAY_MS = 1000 * 60;
    private static final String DATABASE_NAME = "accounts.db";
    private static final int DATABASE_VERSION = 3;

    private final Context mContext;

    private HandlerThread mMessageThread;
    private final MessageHandler mMessageHandler;

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_CONNECTED = 7;
    private static final int MESSAGE_DISCONNECTED = 8;

    private final AccountAuthenticatorCache mAuthenticatorCache;
    private final AuthenticatorBindHelper mBindHelper;
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
    private static final Intent ACCOUNTS_CHANGED_INTENT =
            new Intent(Constants.LOGIN_ACCOUNTS_CHANGED_ACTION);

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
            SystemProperties.getBoolean("ro.monkey", false)
                    && SystemProperties.getBoolean("ro.debuggable", false);
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
        mBindHelper = new AuthenticatorBindHelper(mContext, mAuthenticatorCache, mMessageHandler,
                MESSAGE_CONNECTED, MESSAGE_DISCONNECTED);

        mSimWatcher = new SimWatcher(mContext);
        sThis.set(this);
    }

    public String getPassword(Account account) {
        checkAuthenticateAccountsPermission(account);

        long identityToken = clearCallingIdentity();
        try {
            return readPasswordFromDatabase(account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPasswordFromDatabase(Account account) {
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
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            return readUserDataFromDatabase(account, key);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readUserDataFromDatabase(Account account, String key) {
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
            db.endTransaction();
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
            sendAccountsChangedBroadcast();
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

    public void removeAccount(IAccountManagerResponse response, Account account) {
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
            super(response, account.type, false /* expectActivityLaunch */);
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
            if (result != null && result.containsKey(Constants.BOOLEAN_RESULT_KEY)
                    && !result.containsKey(Constants.INTENT_KEY)) {
                final boolean removalAllowed = result.getBoolean(Constants.BOOLEAN_RESULT_KEY);
                if (removalAllowed) {
                    removeAccount(mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    Bundle result2 = new Bundle();
                    result2.putBoolean(Constants.BOOLEAN_RESULT_KEY, removalAllowed);
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
        checkManageAccountsPermission();
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
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountId(db, account);
            if (accountId < 0) {
                return null;
            }
            return getAuthToken(db, accountId, authTokenType);
        } finally {
            db.endTransaction();
        }
    }

    public String peekAuthToken(Account account, String authTokenType) {
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            return readAuthTokenFromDatabase(account, authTokenType);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            cacheAuthToken(account, authTokenType, authToken);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setPassword(Account account, String password) {
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            ContentValues values = new ContentValues();
            values.put(ACCOUNTS_PASSWORD, password);
            mOpenHelper.getWritableDatabase().update(TABLE_ACCOUNTS, values,
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                    new String[]{account.name, account.type});
            sendAccountsChangedBroadcast();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void sendAccountsChangedBroadcast() {
        mContext.sendBroadcast(ACCOUNTS_CHANGED_INTENT);
    }

    public void clearPassword(Account account) {
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            setPassword(account, null);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void sendResult(IAccountManagerResponse response, Bundle bundle) {
        if (response != null) {
            try {
                response.onResult(bundle);
            } catch (RemoteException e) {
                // if the caller is dead then there is no one to care about remote
                // exceptions
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "failure while notifying response", e);
                }
            }
        }
    }

    public void setUserData(Account account, String key, String value) {
        checkAuthenticateAccountsPermission(account);
        long identityToken = clearCallingIdentity();
        try {
            writeUserdataIntoDatabase(account, key, value);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void writeUserdataIntoDatabase(Account account, String key, String value) {
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

    public void getAuthToken(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean notifyOnAuthFailure,
            final boolean expectActivityLaunch, final Bundle loginOptions) {
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
                    result.putString(Constants.AUTHTOKEN_KEY, authToken);
                    result.putString(Constants.ACCOUNT_NAME_KEY, account.name);
                    result.putString(Constants.ACCOUNT_TYPE_KEY, account.type);
                    onResult(response, result);
                    return;
                }
            }

            new Session(response, account.type, expectActivityLaunch) {
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
                        if (result.containsKey(Constants.AUTH_TOKEN_LABEL_KEY)) {
                            Intent intent = newGrantCredentialsPermissionIntent(account, callerUid,
                                    new AccountAuthenticatorResponse(this),
                                    authTokenType,
                                    result.getString(Constants.AUTH_TOKEN_LABEL_KEY));
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(Constants.INTENT_KEY, intent);
                            onResult(bundle);
                            return;
                        }
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
                            doNotification(
                                    account, result.getString(Constants.AUTH_FAILED_MESSAGE_KEY),
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
        final CharSequence subtitleFormatString =
                mContext.getText(R.string.permission_request_notification_subtitle);
        n.setLatestEventInfo(mContext,
                mContext.getText(R.string.permission_request_notification_title),
                String.format(subtitleFormatString.toString(), account.name),
                PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT));
        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(getCredentialPermissionNotificationId(account, authTokenType, uid), n);
    }

    private Intent newGrantCredentialsPermissionIntent(Account account, int uid,
            AccountAuthenticatorResponse response, String authTokenType, String authTokenLabel) {
        RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo =
                mAuthenticatorCache.getServiceInfo(
                        AuthenticatorDescription.newKey(account.type));
        if (serviceInfo == null) {
            throw new IllegalArgumentException("unknown account type: " + account.type);
        }

        final Context authContext;
        try {
            authContext = mContext.createPackageContext(
                serviceInfo.type.packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("unknown account type: " + account.type);
        }

        Intent intent = new Intent(mContext, GrantCredentialsPermissionActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(
                String.valueOf(getCredentialPermissionNotificationId(account, authTokenType, uid)));
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_ACCOUNT, account);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_LABEL, authTokenLabel);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE, authTokenType);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_RESPONSE, response);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_ACCOUNT_TYPE_LABEL,
                        authContext.getString(serviceInfo.type.labelId));
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_PACKAGES,
                        mContext.getPackageManager().getPackagesForUid(uid));
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
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, accountType, expectActivityLaunch) {
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
            final Account account, final boolean expectActivityLaunch) {
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, expectActivityLaunch) {
                public void run() throws RemoteException {
                    mAuthenticator.confirmCredentials(this, account);
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

    public void confirmPassword(IAccountManagerResponse response, final Account account,
            final String password) {
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, false /* expectActivityLaunch */) {
                public void run() throws RemoteException {
                    mAuthenticator.confirmPassword(this, account, password);
                }
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", confirmPassword"
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
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, account.type, expectActivityLaunch) {
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
        checkManageAccountsPermission();
        long identityToken = clearCallingIdentity();
        try {
            new Session(response, accountType, expectActivityLaunch) {
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
            super(response, type, false /* expectActivityLaunch */);
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

            try {
                mAuthenticator.hasFeatures(this, mAccountsOfType[mCurrentAccount], mFeatures);
            } catch (RemoteException e) {
                onError(Constants.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        public void onResult(Bundle result) {
            mNumResults++;
            if (result == null) {
                onError(Constants.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                return;
            }
            if (result.getBoolean(Constants.BOOLEAN_RESULT_KEY, false)) {
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
                    result.putParcelableArray(Constants.ACCOUNTS_KEY, accounts);
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
        checkReadAccountsPermission();
        if (features != null && type == null) {
            if (response != null) {
                try {
                    response.onError(Constants.ERROR_CODE_BAD_ARGUMENTS, "type is null");
                } catch (RemoteException e) {
                    // ignore this
                }
            }
            return;
        }
        long identityToken = clearCallingIdentity();
        try {
            if (features == null || features.length == 0) {
                getAccountsByType(type);
                return;
            }
            new GetAccountsByTypeAndFeatureSession(response, type, features).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean cacheAuthToken(Account account, String authTokenType, String authToken) {
        return saveAuthTokenToDatabase(account, authTokenType, authToken);
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

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;


        IAccountAuthenticator mAuthenticator = null;

        public Session(IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch) {
            super();
            if (response == null) throw new IllegalArgumentException("response is null");
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
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
                String accountName = result.getString(Constants.ACCOUNT_NAME_KEY);
                String accountType = result.getString(Constants.ACCOUNT_TYPE_KEY);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);
                    cancelNotification(getSigninRequiredNotificationId(account));
                }
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
                Notification n = new Notification(android.R.drawable.stat_sys_warning, null,
                        0 /* when */);
                n.setLatestEventInfo(mContext, mContext.getText(R.string.notification_title),
                        message, PendingIntent.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT));
                ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(getSigninRequiredNotificationId(account), n);
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

    private void checkBinderPermission(String permission) {
        final int uid = Binder.getCallingUid();
        if (mContext.checkCallingOrSelfPermission(permission) !=
                PackageManager.PERMISSION_GRANTED) {
            String msg = "caller uid " + uid + " lacks " + permission;
            Log.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "caller uid " + uid + " has " + permission);
        }
    }

    private boolean permissionIsGranted(Account account, String authTokenType, int callerUid) {
        final boolean fromAuthenticator = hasAuthenticatorUid(account.type, callerUid);
        final boolean hasExplicitGrants = hasExplicitlyGrantedPermission(account, authTokenType);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "checkGrantsOrCallingUidAgainstAuthenticator: caller uid "
                    + callerUid + ", account " + account
                    + ": is authenticator? " + fromAuthenticator
                    + ", has explicit permission? " + hasExplicitGrants);
        }
        return fromAuthenticator || hasExplicitGrants;
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
            Log.w(TAG, "no credentials permission for usage of " + account + ", "
                    + authTokenType + " by uid " + Binder.getCallingUid()
                    + " but ignoring since this is a monkey build");
            return true;
        }
        return permissionGranted;
    }

    private void checkCallingUidAgainstAuthenticator(Account account) {
        final int uid = Binder.getCallingUid();
        if (!hasAuthenticatorUid(account.type, uid)) {
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

    /**
     * Allow callers with the given uid permission to get credentials for account/authTokenType.
     * <p>
     * Although this is public it can only be accessed via the AccountManagerService object
     * which is in the system. This means we don't need to protect it with permissions.
     * @hide
     */
    public void grantAppPermission(Account account, String authTokenType, int uid) {
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
