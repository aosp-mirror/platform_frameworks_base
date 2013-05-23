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

package com.android.server.accounts;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.CantAddAccountActivity;
import android.accounts.GrantCredentialsPermissionActivity;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManager;
import android.accounts.IAccountManagerResponse;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
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
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.UserInfo;
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
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A system service that provides  account, password, and authtoken management for all
 * accounts on the device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. This service is not accessed by users directly,
 * instead one uses an instance of {@link AccountManager}, which can be accessed as follows:
 *    AccountManager accountManager = AccountManager.get(context);
 * @hide
 */
public class AccountManagerService
        extends IAccountManager.Stub
        implements RegisteredServicesCacheListener<AuthenticatorDescription> {
    private static final String TAG = "AccountManagerService";

    private static final int TIMEOUT_DELAY_MS = 1000 * 60;
    private static final String DATABASE_NAME = "accounts.db";
    private static final int DATABASE_VERSION = 5;

    private final Context mContext;

    private final PackageManager mPackageManager;
    private UserManager mUserManager;

    private HandlerThread mMessageThread;
    private final MessageHandler mMessageHandler;

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_COPY_SHARED_ACCOUNT = 4;

    private final IAccountAuthenticatorCache mAuthenticatorCache;

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

    private static final String TABLE_SHARED_ACCOUNTS = "shared_accounts";

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

    private static final String SELECTION_AUTHTOKENS_BY_ACCOUNT =
            AUTHTOKENS_ACCOUNTS_ID + "=(select _id FROM accounts WHERE name=? AND type=?)";
    private static final String[] COLUMNS_AUTHTOKENS_TYPE_AND_AUTHTOKEN = {AUTHTOKENS_TYPE,
            AUTHTOKENS_AUTHTOKEN};

    private static final String SELECTION_USERDATA_BY_ACCOUNT =
            EXTRAS_ACCOUNTS_ID + "=(select _id FROM accounts WHERE name=? AND type=?)";
    private static final String[] COLUMNS_EXTRAS_KEY_AND_VALUE = {EXTRAS_KEY, EXTRAS_VALUE};

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private final AtomicInteger mNotificationIds = new AtomicInteger(1);

    static class UserAccounts {
        private final int userId;
        private final DatabaseHelper openHelper;
        private final HashMap<Pair<Pair<Account, String>, Integer>, Integer>
                credentialsPermissionNotificationIds =
                new HashMap<Pair<Pair<Account, String>, Integer>, Integer>();
        private final HashMap<Account, Integer> signinRequiredNotificationIds =
                new HashMap<Account, Integer>();
        private final Object cacheLock = new Object();
        /** protected by the {@link #cacheLock} */
        private final HashMap<String, Account[]> accountCache =
                new LinkedHashMap<String, Account[]>();
        /** protected by the {@link #cacheLock} */
        private HashMap<Account, HashMap<String, String>> userDataCache =
                new HashMap<Account, HashMap<String, String>>();
        /** protected by the {@link #cacheLock} */
        private HashMap<Account, HashMap<String, String>> authTokenCache =
                new HashMap<Account, HashMap<String, String>>();

        UserAccounts(Context context, int userId) {
            this.userId = userId;
            synchronized (cacheLock) {
                openHelper = new DatabaseHelper(context, userId);
            }
        }
    }

    private final SparseArray<UserAccounts> mUsers = new SparseArray<UserAccounts>();

    private static AtomicReference<AccountManagerService> sThis =
            new AtomicReference<AccountManagerService>();
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

    public AccountManagerService(Context context) {
        this(context, context.getPackageManager(), new AccountAuthenticatorCache(context));
    }

    public AccountManagerService(Context context, PackageManager packageManager,
            IAccountAuthenticatorCache authenticatorCache) {
        mContext = context;
        mPackageManager = packageManager;

        mMessageThread = new HandlerThread("AccountManagerService");
        mMessageThread.start();
        mMessageHandler = new MessageHandler(mMessageThread.getLooper());

        mAuthenticatorCache = authenticatorCache;
        mAuthenticatorCache.setListener(this, null /* Handler */);

        sThis.set(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context1, Intent intent) {
                purgeOldGrantsAll();
            }
        }, intentFilter);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_STARTED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    onUserRemoved(intent);
                } else if (Intent.ACTION_USER_STARTED.equals(action)) {
                    onUserStarted(intent);
                }
            }
        }, UserHandle.ALL, userFilter, null, null);
    }

    public void systemReady() {
    }

    private UserManager getUserManager() {
        if (mUserManager == null) {
            mUserManager = UserManager.get(mContext);
        }
        return mUserManager;
    }

    private UserAccounts initUser(int userId) {
        synchronized (mUsers) {
            UserAccounts accounts = mUsers.get(userId);
            if (accounts == null) {
                accounts = new UserAccounts(mContext, userId);
                mUsers.append(userId, accounts);
                purgeOldGrants(accounts);
                validateAccountsInternal(accounts, true /* invalidateAuthenticatorCache */);
            }
            return accounts;
        }
    }

    private void purgeOldGrantsAll() {
        synchronized (mUsers) {
            for (int i = 0; i < mUsers.size(); i++) {
                purgeOldGrants(mUsers.valueAt(i));
            }
        }
    }

    private void purgeOldGrants(UserAccounts accounts) {
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            final Cursor cursor = db.query(TABLE_GRANTS,
                    new String[]{GRANTS_GRANTEE_UID},
                    null, null, GRANTS_GRANTEE_UID, null, null);
            try {
                while (cursor.moveToNext()) {
                    final int uid = cursor.getInt(0);
                    final boolean packageExists = mPackageManager.getPackagesForUid(uid) != null;
                    if (packageExists) {
                        continue;
                    }
                    Log.d(TAG, "deleting grants for UID " + uid
                            + " because its package is no longer installed");
                    db.delete(TABLE_GRANTS, GRANTS_GRANTEE_UID + "=?",
                            new String[]{Integer.toString(uid)});
                }
            } finally {
                cursor.close();
            }
        }
    }

    /**
     * Validate internal set of accounts against installed authenticators for
     * given user. Clears cached authenticators before validating.
     */
    public void validateAccounts(int userId) {
        final UserAccounts accounts = getUserAccounts(userId);

        // Invalidate user-specific cache to make sure we catch any
        // removed authenticators.
        validateAccountsInternal(accounts, true /* invalidateAuthenticatorCache */);
    }

    /**
     * Validate internal set of accounts against installed authenticators for
     * given user. Clear cached authenticators before validating when requested.
     */
    private void validateAccountsInternal(
            UserAccounts accounts, boolean invalidateAuthenticatorCache) {
        if (invalidateAuthenticatorCache) {
            mAuthenticatorCache.invalidateCache(accounts.userId);
        }

        final HashSet<AuthenticatorDescription> knownAuth = Sets.newHashSet();
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> service :
                mAuthenticatorCache.getAllServices(accounts.userId)) {
            knownAuth.add(service.type);
        }

        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            boolean accountDeleted = false;
            Cursor cursor = db.query(TABLE_ACCOUNTS,
                    new String[]{ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME},
                    null, null, null, null, null);
            try {
                accounts.accountCache.clear();
                final HashMap<String, ArrayList<String>> accountNamesByType =
                        new LinkedHashMap<String, ArrayList<String>>();
                while (cursor.moveToNext()) {
                    final long accountId = cursor.getLong(0);
                    final String accountType = cursor.getString(1);
                    final String accountName = cursor.getString(2);

                    if (!knownAuth.contains(AuthenticatorDescription.newKey(accountType))) {
                        Slog.w(TAG, "deleting account " + accountName + " because type "
                                + accountType + " no longer has a registered authenticator");
                        db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null);
                        accountDeleted = true;
                        final Account account = new Account(accountName, accountType);
                        accounts.userDataCache.remove(account);
                        accounts.authTokenCache.remove(account);
                    } else {
                        ArrayList<String> accountNames = accountNamesByType.get(accountType);
                        if (accountNames == null) {
                            accountNames = new ArrayList<String>();
                            accountNamesByType.put(accountType, accountNames);
                        }
                        accountNames.add(accountName);
                    }
                }
                for (Map.Entry<String, ArrayList<String>> cur
                        : accountNamesByType.entrySet()) {
                    final String accountType = cur.getKey();
                    final ArrayList<String> accountNames = cur.getValue();
                    final Account[] accountsForType = new Account[accountNames.size()];
                    int i = 0;
                    for (String accountName : accountNames) {
                        accountsForType[i] = new Account(accountName, accountType);
                        ++i;
                    }
                    accounts.accountCache.put(accountType, accountsForType);
                }
            } finally {
                cursor.close();
                if (accountDeleted) {
                    sendAccountsChangedBroadcast(accounts.userId);
                }
            }
        }
    }

    private UserAccounts getUserAccountsForCaller() {
        return getUserAccounts(UserHandle.getCallingUserId());
    }

    protected UserAccounts getUserAccounts(int userId) {
        synchronized (mUsers) {
            UserAccounts accounts = mUsers.get(userId);
            if (accounts == null) {
                accounts = initUser(userId);
                mUsers.append(userId, accounts);
            }
            return accounts;
        }
    }

    private void onUserRemoved(Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
        if (userId < 1) return;

        UserAccounts accounts;
        synchronized (mUsers) {
            accounts = mUsers.get(userId);
            mUsers.remove(userId);
        }
        if (accounts == null) {
            File dbFile = new File(getDatabaseName(userId));
            dbFile.delete();
            return;
        }

        synchronized (accounts.cacheLock) {
            accounts.openHelper.close();
            File dbFile = new File(getDatabaseName(userId));
            dbFile.delete();
        }
    }

    private void onUserStarted(Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
        if (userId < 1) return;

        // Check if there's a shared account that needs to be created as an account
        Account[] sharedAccounts = getSharedAccountsAsUser(userId);
        if (sharedAccounts == null || sharedAccounts.length == 0) return;
        Account[] accounts = getAccountsAsUser(null, userId);
        for (Account sa : sharedAccounts) {
            if (ArrayUtils.contains(accounts, sa)) continue;
            // Account doesn't exist. Copy it now.
            copyAccountToUser(sa, UserHandle.USER_OWNER, userId);
        }
    }

    @Override
    public void onServiceChanged(AuthenticatorDescription desc, int userId, boolean removed) {
        validateAccountsInternal(getUserAccounts(userId), false /* invalidateAuthenticatorCache */);
    }

    public String getPassword(Account account) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getPassword: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);

        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            return readPasswordInternal(accounts, account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPasswordInternal(UserAccounts accounts, Account account) {
        if (account == null) {
            return null;
        }

        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
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
    }

    public String getUserData(Account account, String key) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getUserData: " + account
                    + ", key " + key
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        checkAuthenticateAccountsPermission(account);
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            return readUserDataInternal(accounts, account, key);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public AuthenticatorDescription[] getAuthenticatorTypes() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAuthenticatorTypes: "
                    + "caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        final int userId = UserHandle.getCallingUserId();
        final long identityToken = clearCallingIdentity();
        try {
            Collection<AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription>>
                    authenticatorCollection = mAuthenticatorCache.getAllServices(userId);
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

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addAccountExplicitly: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);
        /*
         * Child users are not allowed to add accounts. Only the accounts that are
         * shared by the parent profile can be added to child profile.
         *
         * TODO: Only allow accounts that were shared to be added by
         *     a limited user.
         */

        UserAccounts accounts = getUserAccountsForCaller();
        // fails if the account already exists
        long identityToken = clearCallingIdentity();
        try {
            return addAccountInternal(accounts, account, password, extras, false);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean copyAccountToUser(final Account account, int userFrom, int userTo) {
        final UserAccounts fromAccounts = getUserAccounts(userFrom);
        final UserAccounts toAccounts = getUserAccounts(userTo);
        if (fromAccounts == null || toAccounts == null) {
            return false;
        }

        long identityToken = clearCallingIdentity();
        try {
            new Session(fromAccounts, null, account.type, false,
                    false /* stripAuthTokenFromResult */) {
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAccountCredentialsForClone"
                            + ", " + account.type;
                }

                public void run() throws RemoteException {
                    mAuthenticator.getAccountCredentialsForCloning(this, account);
                }

                public void onResult(Bundle result) {
                    if (result != null) {
                        if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                            // Create a Session for the target user and pass in the bundle
                            completeCloningAccount(result, account, toAccounts);
                        }
                        return;
                    } else {
                        super.onResult(result);
                    }
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return true;
    }

    void completeCloningAccount(final Bundle result, final Account account,
            final UserAccounts targetUser) {
        long id = clearCallingIdentity();
        try {
            new Session(targetUser, null, account.type, false,
                    false /* stripAuthTokenFromResult */) {
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAccountCredentialsForClone"
                            + ", " + account.type;
                }

                public void run() throws RemoteException {
                    // Confirm that the owner's account still exists before this step.
                    UserAccounts owner = getUserAccounts(UserHandle.USER_OWNER);
                    synchronized (owner.cacheLock) {
                        Account[] ownerAccounts = getAccounts(UserHandle.USER_OWNER);
                        for (Account acc : ownerAccounts) {
                            if (acc.equals(account)) {
                                mAuthenticator.addAccountFromCredentials(this, account, result);
                                break;
                            }
                        }
                    }
                }

                public void onResult(Bundle result) {
                    if (result != null) {
                        if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                            // TODO: Anything?
                        } else {
                            // TODO: Show error notification
                            // TODO: Should we remove the shadow account to avoid retries?
                        }
                        return;
                    } else {
                        super.onResult(result);
                    }
                }

                public void onError(int errorCode, String errorMessage) {
                    super.onError(errorCode,  errorMessage);
                    // TODO: Show error notification to user
                    // TODO: Should we remove the shadow account so that it doesn't keep trying?
                }

            }.bind();
        } finally {
            restoreCallingIdentity(id);
        }
    }

    private boolean addAccountInternal(UserAccounts accounts, Account account, String password,
            Bundle extras, boolean restricted) {
        if (account == null) {
            return false;
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long numMatches = DatabaseUtils.longForQuery(db,
                        "select count(*) from " + TABLE_ACCOUNTS
                                + " WHERE " + ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                        new String[]{account.name, account.type});
                if (numMatches > 0) {
                    Log.w(TAG, "insertAccountIntoDatabase: " + account
                            + ", skipping since the account already exists");
                    return false;
                }
                ContentValues values = new ContentValues();
                values.put(ACCOUNTS_NAME, account.name);
                values.put(ACCOUNTS_TYPE, account.type);
                values.put(ACCOUNTS_PASSWORD, password);
                long accountId = db.insert(TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
                if (accountId < 0) {
                    Log.w(TAG, "insertAccountIntoDatabase: " + account
                            + ", skipping the DB insert failed");
                    return false;
                }
                if (extras != null) {
                    for (String key : extras.keySet()) {
                        final String value = extras.getString(key);
                        if (insertExtraLocked(db, accountId, key, value) < 0) {
                            Log.w(TAG, "insertAccountIntoDatabase: " + account
                                    + ", skipping since insertExtra failed for key " + key);
                            return false;
                        }
                    }
                }
                db.setTransactionSuccessful();
                insertAccountIntoCacheLocked(accounts, account);
            } finally {
                db.endTransaction();
            }
            sendAccountsChangedBroadcast(accounts.userId);
        }
        if (accounts.userId == UserHandle.USER_OWNER) {
            addAccountToLimitedUsers(account);
        }
        return true;
    }

    /**
     * Adds the account to all limited users as shared accounts. If the user is currently
     * running, then clone the account too.
     * @param account the account to share with limited users
     */
    private void addAccountToLimitedUsers(Account account) {
        List<UserInfo> users = getUserManager().getUsers();
        for (UserInfo user : users) {
            if (user.isRestricted()) {
                addSharedAccountAsUser(account, user.id);
                try {
                    if (ActivityManagerNative.getDefault().isUserRunning(user.id, false)) {
                        mMessageHandler.sendMessage(mMessageHandler.obtainMessage(
                                MESSAGE_COPY_SHARED_ACCOUNT, UserHandle.USER_OWNER, user.id,
                                account));
                    }
                } catch (RemoteException re) {
                    // Shouldn't happen
                }
            }
        }
    }

    private long insertExtraLocked(SQLiteDatabase db, long accountId, String key, String value) {
        ContentValues values = new ContentValues();
        values.put(EXTRAS_KEY, key);
        values.put(EXTRAS_ACCOUNTS_ID, accountId);
        values.put(EXTRAS_VALUE, value);
        return db.insert(TABLE_EXTRAS, EXTRAS_KEY, values);
    }

    public void hasFeatures(IAccountManagerResponse response,
            Account account, String[] features) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "hasFeatures: " + account
                    + ", response " + response
                    + ", features " + stringArrayToString(features)
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (features == null) throw new IllegalArgumentException("features is null");
        checkReadAccountsPermission();
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            new TestFeaturesSession(accounts, response, account, features).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class TestFeaturesSession extends Session {
        private final String[] mFeatures;
        private final Account mAccount;

        public TestFeaturesSession(UserAccounts accounts, IAccountManagerResponse response,
                Account account, String[] features) {
            super(accounts, response, account.type, false /* expectActivityLaunch */,
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
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                        return;
                    }
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
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
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "removeAccount: " + account
                    + ", response " + response
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        UserHandle user = Binder.getCallingUserHandle();
        UserAccounts accounts = getUserAccountsForCaller();
        if (!canUserModifyAccounts(Binder.getCallingUid())) {
            try {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "User cannot modify accounts");
            } catch (RemoteException re) {
            }
        }

        long identityToken = clearCallingIdentity();

        cancelNotification(getSigninRequiredNotificationId(accounts, account), user);
        synchronized(accounts.credentialsPermissionNotificationIds) {
            for (Pair<Pair<Account, String>, Integer> pair:
                accounts.credentialsPermissionNotificationIds.keySet()) {
                if (account.equals(pair.first.first)) {
                    int id = accounts.credentialsPermissionNotificationIds.get(pair);
                    cancelNotification(id, user);
                }
            }
        }

        try {
            new RemoveAccountSession(accounts, response, account).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class RemoveAccountSession extends Session {
        final Account mAccount;
        public RemoveAccountSession(UserAccounts accounts, IAccountManagerResponse response,
                Account account) {
            super(accounts, response, account.type, false /* expectActivityLaunch */,
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
                    removeAccountInternal(mAccounts, mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
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

    /* For testing */
    protected void removeAccountInternal(Account account) {
        removeAccountInternal(getUserAccountsForCaller(), account);
    }

    private void removeAccountInternal(UserAccounts accounts, Account account) {
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.delete(TABLE_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                    new String[]{account.name, account.type});
            removeAccountFromCacheLocked(accounts, account);
            sendAccountsChangedBroadcast(accounts.userId);
        }
        if (accounts.userId == UserHandle.USER_OWNER) {
            // Owner's account was removed, remove from any users that are sharing
            // this account.
            long id = Binder.clearCallingIdentity();
            try {
                List<UserInfo> users = mUserManager.getUsers(true);
                for (UserInfo user : users) {
                    if (!user.isPrimary() && user.isRestricted()) {
                        removeSharedAccountAsUser(account, user.id);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "invalidateAuthToken: accountType " + accountType
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (authToken == null) throw new IllegalArgumentException("authToken is null");
        checkManageAccountsOrUseCredentialsPermissions();
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            synchronized (accounts.cacheLock) {
                final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    invalidateAuthTokenLocked(accounts, db, accountType, authToken);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void invalidateAuthTokenLocked(UserAccounts accounts, SQLiteDatabase db,
            String accountType, String authToken) {
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
                writeAuthTokenIntoCacheLocked(accounts, db, new Account(accountName, accountType),
                        authTokenType, null);
            }
        } finally {
            cursor.close();
        }
    }

    private boolean saveAuthTokenToDatabase(UserAccounts accounts, Account account, String type,
            String authToken) {
        if (account == null || type == null) {
            return false;
        }
        cancelNotification(getSigninRequiredNotificationId(accounts, account),
                new UserHandle(accounts.userId));
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long accountId = getAccountIdLocked(db, account);
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
                    writeAuthTokenIntoCacheLocked(accounts, db, account, type, authToken);
                    return true;
                }
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    public String peekAuthToken(Account account, String authTokenType) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "peekAuthToken: " + account
                    + ", authTokenType " + authTokenType
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkAuthenticateAccountsPermission(account);
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            return readAuthTokenInternal(accounts, account, authTokenType);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setAuthToken: " + account
                    + ", authTokenType " + authTokenType
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkAuthenticateAccountsPermission(account);
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            saveAuthTokenToDatabase(accounts, account, authTokenType, authToken);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setPassword(Account account, String password) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setAuthToken: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            setPasswordInternal(accounts, account, password);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void setPasswordInternal(UserAccounts accounts, Account account, String password) {
        if (account == null) {
            return;
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                final ContentValues values = new ContentValues();
                values.put(ACCOUNTS_PASSWORD, password);
                final long accountId = getAccountIdLocked(db, account);
                if (accountId >= 0) {
                    final String[] argsAccountId = {String.valueOf(accountId)};
                    db.update(TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId);
                    db.delete(TABLE_AUTHTOKENS, AUTHTOKENS_ACCOUNTS_ID + "=?", argsAccountId);
                    accounts.authTokenCache.remove(account);
                    db.setTransactionSuccessful();
                }
            } finally {
                db.endTransaction();
            }
            sendAccountsChangedBroadcast(accounts.userId);
        }
    }

    private void sendAccountsChangedBroadcast(int userId) {
        Log.i(TAG, "the accounts changed, sending broadcast of "
                + ACCOUNTS_CHANGED_INTENT.getAction());
        mContext.sendBroadcastAsUser(ACCOUNTS_CHANGED_INTENT, new UserHandle(userId));
    }

    public void clearPassword(Account account) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "clearPassword: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            setPasswordInternal(accounts, account, null);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setUserData(Account account, String key, String value) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setUserData: " + account
                    + ", key " + key
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (key == null) throw new IllegalArgumentException("key is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkAuthenticateAccountsPermission(account);
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            setUserdataInternal(accounts, account, key, value);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void setUserdataInternal(UserAccounts accounts, Account account, String key,
            String value) {
        if (account == null || key == null) {
            return;
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long accountId = getAccountIdLocked(db, account);
                if (accountId < 0) {
                    return;
                }
                long extrasId = getExtrasIdLocked(db, accountId, key);
                if (extrasId < 0 ) {
                    extrasId = insertExtraLocked(db, accountId, key, value);
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
                writeUserDataIntoCacheLocked(accounts, db, account, key, value);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    private void onResult(IAccountManagerResponse response, Bundle result) {
        if (result == null) {
            Log.e(TAG, "the result is unexpectedly null", new Exception());
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                    + response);
        }
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

    public void getAuthTokenLabel(IAccountManagerResponse response, final String accountType,
                                  final String authTokenType)
            throws RemoteException {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");

        final int callingUid = getCallingUid();
        clearCallingIdentity();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("can only call from system");
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(callingUid));
        long identityToken = clearCallingIdentity();
        try {
            new Session(accounts, response, accountType, false,
                    false /* stripAuthTokenFromResult */) {
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAuthTokenLabel"
                            + ", " + accountType
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
            final boolean expectActivityLaunch, Bundle loginOptionsIn) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAuthToken: " + account
                    + ", response " + response
                    + ", authTokenType " + authTokenType
                    + ", notifyOnAuthFailure " + notifyOnAuthFailure
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkBinderPermission(Manifest.permission.USE_CREDENTIALS);
        final UserAccounts accounts = getUserAccountsForCaller();
        final RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo;
        authenticatorInfo = mAuthenticatorCache.getServiceInfo(
                AuthenticatorDescription.newKey(account.type), accounts.userId);
        final boolean customTokens =
            authenticatorInfo != null && authenticatorInfo.type.customTokens;

        // Check to see that the app is authorized to access the account, in case it's a
        // restricted account.
        if (!ArrayUtils.contains(getAccounts((String) null), account)) {
            throw new IllegalArgumentException("no such account");
        }
        // skip the check if customTokens
        final int callerUid = Binder.getCallingUid();
        final boolean permissionGranted = customTokens ||
            permissionIsGranted(account, authTokenType, callerUid);

        final Bundle loginOptions = (loginOptionsIn == null) ? new Bundle() :
            loginOptionsIn;
        // let authenticator know the identity of the caller
        loginOptions.putInt(AccountManager.KEY_CALLER_UID, callerUid);
        loginOptions.putInt(AccountManager.KEY_CALLER_PID, Binder.getCallingPid());
        if (notifyOnAuthFailure) {
            loginOptions.putBoolean(AccountManager.KEY_NOTIFY_ON_FAILURE, true);
        }

        long identityToken = clearCallingIdentity();
        try {
            // if the caller has permission, do the peek. otherwise go the more expensive
            // route of starting a Session
            if (!customTokens && permissionGranted) {
                String authToken = readAuthTokenInternal(accounts, account, authTokenType);
                if (authToken != null) {
                    Bundle result = new Bundle();
                    result.putString(AccountManager.KEY_AUTHTOKEN, authToken);
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                    onResult(response, result);
                    return;
                }
            }

            new Session(accounts, response, account.type, expectActivityLaunch,
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
                            if (!customTokens) {
                                saveAuthTokenToDatabase(mAccounts, new Account(name, type),
                                        authTokenType, authToken);
                            }
                        }

                        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                        if (intent != null && notifyOnAuthFailure && !customTokens) {
                            doNotification(mAccounts,
                                    account, result.getString(AccountManager.KEY_AUTH_FAILED_MESSAGE),
                                    intent, accounts.userId);
                        }
                    }
                    super.onResult(result);
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void createNoCredentialsPermissionNotification(Account account, Intent intent,
            int userId) {
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
        String title = titleAndSubtitle;
        String subtitle = "";
        if (index > 0) {
            title = titleAndSubtitle.substring(0, index);
            subtitle = titleAndSubtitle.substring(index + 1);
        }
        UserHandle user = new UserHandle(userId);
        n.setLatestEventInfo(mContext, title, subtitle,
                PendingIntent.getActivityAsUser(mContext, 0, intent,
                        PendingIntent.FLAG_CANCEL_CURRENT, null, user));
        installNotification(getCredentialPermissionNotificationId(
                account, authTokenType, uid), n, user);
    }

    private Intent newGrantCredentialsPermissionIntent(Account account, int uid,
            AccountAuthenticatorResponse response, String authTokenType, String authTokenLabel) {

        Intent intent = new Intent(mContext, GrantCredentialsPermissionActivity.class);
        // See FLAG_ACTIVITY_NEW_TASK docs for limitations and benefits of the flag.
        // Since it was set in Eclair+ we can't change it without breaking apps using
        // the intent from a non-Activity context.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.credentialsPermissionNotificationIds) {
            final Pair<Pair<Account, String>, Integer> key =
                    new Pair<Pair<Account, String>, Integer>(
                            new Pair<Account, String>(account, authTokenType), uid);
            id = accounts.credentialsPermissionNotificationIds.get(key);
            if (id == null) {
                id = mNotificationIds.incrementAndGet();
                accounts.credentialsPermissionNotificationIds.put(key, id);
            }
        }
        return id;
    }

    private Integer getSigninRequiredNotificationId(UserAccounts accounts, Account account) {
        Integer id;
        synchronized (accounts.signinRequiredNotificationIds) {
            id = accounts.signinRequiredNotificationIds.get(account);
            if (id == null) {
                id = mNotificationIds.incrementAndGet();
                accounts.signinRequiredNotificationIds.put(account, id);
            }
        }
        return id;
    }

    public void addAccount(final IAccountManagerResponse response, final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final boolean expectActivityLaunch, final Bundle optionsIn) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addAccount: accountType " + accountType
                    + ", response " + response
                    + ", authTokenType " + authTokenType
                    + ", requiredFeatures " + stringArrayToString(requiredFeatures)
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        checkManageAccountsPermission();

        // Is user disallowed from modifying accounts?
        if (!canUserModifyAccounts(Binder.getCallingUid())) {
            try {
                response.onError(AccountManager.ERROR_CODE_USER_RESTRICTED,
                        "User is not allowed to add an account!");
            } catch (RemoteException re) {
            }
            Intent cantAddAccount = new Intent(mContext, CantAddAccountActivity.class);
            cantAddAccount.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            long identityToken = clearCallingIdentity();
            try {
                mContext.startActivityAsUser(cantAddAccount, UserHandle.CURRENT);
            } finally {
                restoreCallingIdentity(identityToken);
            }
            return;
        }

        UserAccounts accounts = getUserAccountsForCaller();
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final Bundle options = (optionsIn == null) ? new Bundle() : optionsIn;
        options.putInt(AccountManager.KEY_CALLER_UID, uid);
        options.putInt(AccountManager.KEY_CALLER_PID, pid);

        long identityToken = clearCallingIdentity();
        try {
            new Session(accounts, response, accountType, expectActivityLaunch,
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

    @Override
    public void confirmCredentialsAsUser(IAccountManagerResponse response,
            final Account account, final Bundle options, final boolean expectActivityLaunch,
            int userId) {
        // Only allow the system process to read accounts of other users
        if (userId != UserHandle.getCallingUserId()
                && Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("User " + UserHandle.getCallingUserId()
                    + " trying to confirm account credentials for " + userId);
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "confirmCredentials: " + account
                    + ", response " + response
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        checkManageAccountsPermission();
        UserAccounts accounts = getUserAccounts(userId);
        long identityToken = clearCallingIdentity();
        try {
            new Session(accounts, response, account.type, expectActivityLaunch,
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
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "updateCredentials: " + account
                    + ", response " + response
                    + ", authTokenType " + authTokenType
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        checkManageAccountsPermission();
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            new Session(accounts, response, account.type, expectActivityLaunch,
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
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "editProperties: accountType " + accountType
                    + ", response " + response
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        checkManageAccountsPermission();
        UserAccounts accounts = getUserAccountsForCaller();
        long identityToken = clearCallingIdentity();
        try {
            new Session(accounts, response, accountType, expectActivityLaunch,
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
        private int mCallingUid;

        public GetAccountsByTypeAndFeatureSession(UserAccounts accounts,
                IAccountManagerResponse response, String type, String[] features, int callingUid) {
            super(accounts, response, type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */);
            mCallingUid = callingUid;
            mFeatures = features;
        }

        public void run() throws RemoteException {
            synchronized (mAccounts.cacheLock) {
                mAccountsOfType = getAccountsFromCacheLocked(mAccounts, mAccountType, mCallingUid,
                        null);
            }
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
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
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

    /**
     * Returns the accounts for a specific user
     * @hide
     */
    public Account[] getAccounts(int userId) {
        checkReadAccountsPermission();
        UserAccounts accounts = getUserAccounts(userId);
        int callingUid = Binder.getCallingUid();
        long identityToken = clearCallingIdentity();
        try {
            synchronized (accounts.cacheLock) {
                return getAccountsFromCacheLocked(accounts, null, callingUid, null);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Returns accounts for all running users.
     *
     * @hide
     */
    public AccountAndUser[] getRunningAccounts() {
        final int[] runningUserIds;
        try {
            runningUserIds = ActivityManagerNative.getDefault().getRunningUserIds();
        } catch (RemoteException e) {
            // Running in system_server; should never happen
            throw new RuntimeException(e);
        }
        return getAccounts(runningUserIds);
    }

    /** {@hide} */
    public AccountAndUser[] getAllAccounts() {
        final List<UserInfo> users = getUserManager().getUsers();
        final int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users.get(i).id;
        }
        return getAccounts(userIds);
    }

    private AccountAndUser[] getAccounts(int[] userIds) {
        final ArrayList<AccountAndUser> runningAccounts = Lists.newArrayList();
        synchronized (mUsers) {
            for (int userId : userIds) {
                UserAccounts userAccounts = getUserAccounts(userId);
                if (userAccounts == null) continue;
                synchronized (userAccounts.cacheLock) {
                    Account[] accounts = getAccountsFromCacheLocked(userAccounts, null,
                            Binder.getCallingUid(), null);
                    for (int a = 0; a < accounts.length; a++) {
                        runningAccounts.add(new AccountAndUser(accounts[a], userId));
                    }
                }
            }
        }

        AccountAndUser[] accountsArray = new AccountAndUser[runningAccounts.size()];
        return runningAccounts.toArray(accountsArray);
    }

    @Override
    public Account[] getAccountsAsUser(String type, int userId) {
        return getAccountsAsUser(type, userId, null, -1);
    }

    private Account[] getAccountsAsUser(String type, int userId, String callingPackage,
            int packageUid) {
        int callingUid = Binder.getCallingUid();
        // Only allow the system process to read accounts of other users
        if (userId != UserHandle.getCallingUserId()
                && callingUid != Process.myUid()) {
            throw new SecurityException("User " + UserHandle.getCallingUserId()
                    + " trying to get account for " + userId);
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAccounts: accountType " + type
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        // If the original calling app was using the framework account chooser activity, we'll
        // be passed in the original caller's uid here, which is what should be used for filtering.
        if (packageUid != -1 && UserHandle.isSameApp(callingUid, Process.myUid())) {
            callingUid = packageUid;
        }
        checkReadAccountsPermission();
        UserAccounts accounts = getUserAccounts(userId);
        long identityToken = clearCallingIdentity();
        try {
            synchronized (accounts.cacheLock) {
                return getAccountsFromCacheLocked(accounts, type, callingUid, callingPackage);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean addSharedAccountAsUser(Account account, int userId) {
        userId = handleIncomingUser(userId);
        SQLiteDatabase db = getUserAccounts(userId).openHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, account.name);
        values.put(ACCOUNTS_TYPE, account.type);
        db.delete(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[] {account.name, account.type});
        long accountId = db.insert(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME, values);
        if (accountId < 0) {
            Log.w(TAG, "insertAccountIntoDatabase: " + account
                    + ", skipping the DB insert failed");
            return false;
        }
        return true;
    }

    @Override
    public boolean removeSharedAccountAsUser(Account account, int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        int r = db.delete(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[] {account.name, account.type});
        if (r > 0) {
            removeAccountInternal(accounts, account);
        }
        return r > 0;
    }

    @Override
    public Account[] getSharedAccountsAsUser(int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        ArrayList<Account> accountList = new ArrayList<Account>();
        Cursor cursor = null;
        try {
            cursor = accounts.openHelper.getReadableDatabase()
                    .query(TABLE_SHARED_ACCOUNTS, new String[]{ACCOUNTS_NAME, ACCOUNTS_TYPE},
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
        Account[] accountArray = new Account[accountList.size()];
        accountList.toArray(accountArray);
        return accountArray;
    }

    @Override
    public Account[] getAccounts(String type) {
        return getAccountsAsUser(type, UserHandle.getCallingUserId());
    }

    @Override
    public Account[] getAccountsForPackage(String packageName, int uid) {
        int callingUid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(callingUid, Process.myUid())) {
            throw new SecurityException("getAccountsForPackage() called from unauthorized uid "
                    + callingUid + " with uid=" + uid);
        }
        return getAccountsAsUser(null, UserHandle.getCallingUserId(), packageName, uid);
    }

    @Override
    public Account[] getAccountsByTypeForPackage(String type, String packageName) {
        checkBinderPermission(android.Manifest.permission.INTERACT_ACROSS_USERS);
        int packageUid = -1;
        try {
            packageUid = AppGlobals.getPackageManager().getPackageUid(
                    packageName, UserHandle.getCallingUserId());
        } catch (RemoteException re) {
            Slog.e(TAG, "Couldn't determine the packageUid for " + packageName + re);
            return new Account[0];
        }
        return getAccountsAsUser(type, UserHandle.getCallingUserId(), packageName, packageUid);
    }

    public void getAccountsByFeatures(IAccountManagerResponse response,
            String type, String[] features) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAccounts: accountType " + type
                    + ", response " + response
                    + ", features " + stringArrayToString(features)
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");
        checkReadAccountsPermission();
        UserAccounts userAccounts = getUserAccountsForCaller();
        int callingUid = Binder.getCallingUid();
        long identityToken = clearCallingIdentity();
        try {
            if (features == null || features.length == 0) {
                Account[] accounts;
                synchronized (userAccounts.cacheLock) {
                    accounts = getAccountsFromCacheLocked(userAccounts, type, callingUid, null);
                }
                Bundle result = new Bundle();
                result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                onResult(response, result);
                return;
            }
            new GetAccountsByTypeAndFeatureSession(userAccounts, response, type, features,
                    callingUid).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private long getAccountIdLocked(SQLiteDatabase db, Account account) {
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

    private long getExtrasIdLocked(SQLiteDatabase db, long accountId, String key) {
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
        protected final UserAccounts mAccounts;

        public Session(UserAccounts accounts, IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch, boolean stripAuthTokenFromResult) {
            super();
            //if (response == null) throw new IllegalArgumentException("response is null");
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mAccounts = accounts;
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            synchronized (mSessions) {
                mSessions.put(toString(), this);
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0 /* flags */);
                } catch (RemoteException e) {
                    mResponse = null;
                    binderDied();
                }
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
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "disconnected");
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onServiceDisconnected: "
                                + "caught RemoteException while responding", e);
                    }
                }
            }
        }

        public abstract void run() throws RemoteException;

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "timeout");
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onTimedOut: caught RemoteException while responding",
                                e);
                    }
                }
            }
        }

        public void onResult(Bundle result) {
            mNumResults++;
            if (result != null && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
                String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);
                    cancelNotification(getSigninRequiredNotificationId(mAccounts, account),
                            new UserHandle(mAccounts.userId));
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
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, getClass().getSimpleName()
                                    + " calling onError() on response " + response);
                        }
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN);
                        }
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, getClass().getSimpleName()
                                    + " calling onResult() on response " + response);
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
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, getClass().getSimpleName()
                            + " calling onError() on response " + response);
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
            final AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo;
            authenticatorInfo = mAuthenticatorCache.getServiceInfo(
                    AuthenticatorDescription.newKey(authenticatorType), mAccounts.userId);
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
            if (!mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                    new UserHandle(mAccounts.userId))) {
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

                case MESSAGE_COPY_SHARED_ACCOUNT:
                    copyAccountToUser((Account) msg.obj, msg.arg1, msg.arg2);
                    break;

                default:
                    throw new IllegalStateException("unhandled message: " + msg.what);
            }
        }
    }

    private static String getDatabaseName(int userId) {
        File systemDir = Environment.getSystemSecureDirectory();
        File databaseFile = new File(Environment.getUserSystemDirectory(userId), DATABASE_NAME);
        if (userId == 0) {
            // Migrate old file, if it exists, to the new location.
            // Make sure the new file doesn't already exist. A dummy file could have been
            // accidentally created in the old location, causing the new one to become corrupted
            // as well.
            File oldFile = new File(systemDir, DATABASE_NAME);
            if (oldFile.exists() && !databaseFile.exists()) {
                // Check for use directory; create if it doesn't exist, else renameTo will fail
                File userDir = Environment.getUserSystemDirectory(userId);
                if (!userDir.exists()) {
                    if (!userDir.mkdirs()) {
                        throw new IllegalStateException("User dir cannot be created: " + userDir);
                    }
                }
                if (!oldFile.renameTo(databaseFile)) {
                    throw new IllegalStateException("User dir cannot be migrated: " + databaseFile);
                }
            }
        }
        return databaseFile.getPath();
    }

    static class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context, int userId) {
            super(context, AccountManagerService.getDatabaseName(userId), null, DATABASE_VERSION);
        }

        /**
         * This call needs to be made while the mCacheLock is held. The way to
         * ensure this is to get the lock any time a method is called ont the DatabaseHelper
         * @param db The database.
         */
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

            createSharedAccountsTable(db);

            createAccountsDeletionTrigger(db);
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

            if (oldVersion == 4) {
                createSharedAccountsTable(db);
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            fout.println("Permission Denial: can't dump AccountsManager from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }
        final boolean isCheckinRequest = scanArgs(args, "--checkin") || scanArgs(args, "-c");
        final IndentingPrintWriter ipw = new IndentingPrintWriter(fout, "  ");

        final List<UserInfo> users = getUserManager().getUsers();
        for (UserInfo user : users) {
            ipw.println("User " + user + ":");
            ipw.increaseIndent();
            dumpUser(getUserAccounts(user.id), fd, ipw, args, isCheckinRequest);
            ipw.println();
            ipw.decreaseIndent();
        }
    }

    private void dumpUser(UserAccounts userAccounts, FileDescriptor fd, PrintWriter fout,
            String[] args, boolean isCheckinRequest) {
        synchronized (userAccounts.cacheLock) {
            final SQLiteDatabase db = userAccounts.openHelper.getReadableDatabase();

            if (isCheckinRequest) {
                // This is a checkin request. *Only* upload the account types and the count of each.
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
                Account[] accounts = getAccountsFromCacheLocked(userAccounts, null /* type */,
                        Process.myUid(), null);
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
                mAuthenticatorCache.dump(fd, fout, args, userAccounts.userId);
            }
        }
    }

    private void doNotification(UserAccounts accounts, Account account, CharSequence message,
            Intent intent, int userId) {
        long identityToken = clearCallingIdentity();
        try {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "doNotification: " + message + " intent:" + intent);
            }

            if (intent.getComponent() != null &&
                    GrantCredentialsPermissionActivity.class.getName().equals(
                            intent.getComponent().getClassName())) {
                createNoCredentialsPermissionNotification(account, intent, userId);
            } else {
                final Integer notificationId = getSigninRequiredNotificationId(accounts, account);
                intent.addCategory(String.valueOf(notificationId));
                Notification n = new Notification(android.R.drawable.stat_sys_warning, null,
                        0 /* when */);
                UserHandle user = new UserHandle(userId);
                final String notificationTitleFormat =
                        mContext.getText(R.string.notification_title).toString();
                n.setLatestEventInfo(mContext,
                        String.format(notificationTitleFormat, account.name),
                        message, PendingIntent.getActivityAsUser(
                        mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT,
                        null, user));
                installNotification(notificationId, n, user);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    protected void installNotification(final int notificationId, final Notification n,
            UserHandle user) {
        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .notifyAsUser(null, notificationId, n, user);
    }

    protected void cancelNotification(int id, UserHandle user) {
        long identityToken = clearCallingIdentity();
        try {
            ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancelAsUser(null, id, user);
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
                    Log.v(TAG, "  caller uid " + uid + " has " + perm);
                }
                return;
            }
        }

        String msg = "caller uid " + uid + " lacks any of " + TextUtils.join(",", permissions);
        Log.w(TAG, "  " + msg);
        throw new SecurityException(msg);
    }

    private int handleIncomingUser(int userId) {
        try {
            return ActivityManagerNative.getDefault().handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "", null);
        } catch (RemoteException re) {
            // Shouldn't happen, local.
        }
        return userId;
    }

    private boolean inSystemImage(int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);

        final PackageManager userPackageManager;
        try {
            userPackageManager = mContext.createPackageContextAsUser(
                    "android", 0, new UserHandle(callingUserId)).getPackageManager();
        } catch (NameNotFoundException e) {
            return false;
        }

        String[] packages = userPackageManager.getPackagesForUid(callingUid);
        for (String name : packages) {
            try {
                PackageInfo packageInfo = userPackageManager.getPackageInfo(name, 0 /* flags */);
                if (packageInfo != null
                        && (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
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
                && hasExplicitlyGrantedPermission(account, authTokenType, callerUid);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "checkGrantsOrCallingUidAgainstAuthenticator: caller uid "
                    + callerUid + ", " + account
                    + ": is authenticator? " + fromAuthenticator
                    + ", has explicit permission? " + hasExplicitGrants);
        }
        return fromAuthenticator || hasExplicitGrants || inSystemImage;
    }

    private boolean hasAuthenticatorUid(String accountType, int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo :
                mAuthenticatorCache.getAllServices(callingUserId)) {
            if (serviceInfo.type.type.equals(accountType)) {
                return (serviceInfo.uid == callingUid) ||
                        (mPackageManager.checkSignatures(serviceInfo.uid, callingUid)
                                == PackageManager.SIGNATURE_MATCH);
            }
        }
        return false;
    }

    private boolean hasExplicitlyGrantedPermission(Account account, String authTokenType,
            int callerUid) {
        if (callerUid == Process.SYSTEM_UID) {
            return true;
        }
        UserAccounts accounts = getUserAccountsForCaller();
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
            String[] args = { String.valueOf(callerUid), authTokenType,
                    account.name, account.type};
            final boolean permissionGranted =
                    DatabaseUtils.longForQuery(db, COUNT_OF_MATCHING_GRANTS, args) != 0;
            if (!permissionGranted && ActivityManager.isRunningInTestHarness()) {
                // TODO: Skip this check when running automated tests. Replace this
                // with a more general solution.
                Log.d(TAG, "no credentials permission for usage of " + account + ", "
                        + authTokenType + " by uid " + callerUid
                        + " but ignoring since device is in test harness.");
                return true;
            }
            return permissionGranted;
        }
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

    private boolean canUserModifyAccounts(int callingUid) {
        if (callingUid != Process.myUid()) {
            if (getUserManager().getUserRestrictions(
                    new UserHandle(UserHandle.getUserId(callingUid)))
                    .getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS)) {
                return false;
            }
        }
        return true;
    }

    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value)
            throws RemoteException {
        final int callingUid = getCallingUid();

        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException();
        }

        if (value) {
            grantAppPermission(account, authTokenType, uid);
        } else {
            revokeAppPermission(account, authTokenType, uid);
        }
    }

    /**
     * Allow callers with the given uid permission to get credentials for account/authTokenType.
     * <p>
     * Although this is public it can only be accessed via the AccountManagerService object
     * which is in the system. This means we don't need to protect it with permissions.
     * @hide
     */
    private void grantAppPermission(Account account, String authTokenType, int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "grantAppPermission: called with invalid arguments", new Exception());
            return;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long accountId = getAccountIdLocked(db, account);
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
            cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid),
                    new UserHandle(accounts.userId));
        }
    }

    /**
     * Don't allow callers with the given uid permission to get credentials for
     * account/authTokenType.
     * <p>
     * Although this is public it can only be accessed via the AccountManagerService object
     * which is in the system. This means we don't need to protect it with permissions.
     * @hide
     */
    private void revokeAppPermission(Account account, String authTokenType, int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "revokeAppPermission: called with invalid arguments", new Exception());
            return;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long accountId = getAccountIdLocked(db, account);
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
            cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid),
                    new UserHandle(accounts.userId));
        }
    }

    static final private String stringArrayToString(String[] value) {
        return value != null ? ("[" + TextUtils.join(",", value) + "]") : null;
    }

    private void removeAccountFromCacheLocked(UserAccounts accounts, Account account) {
        final Account[] oldAccountsForType = accounts.accountCache.get(account.type);
        if (oldAccountsForType != null) {
            ArrayList<Account> newAccountsList = new ArrayList<Account>();
            for (Account curAccount : oldAccountsForType) {
                if (!curAccount.equals(account)) {
                    newAccountsList.add(curAccount);
                }
            }
            if (newAccountsList.isEmpty()) {
                accounts.accountCache.remove(account.type);
            } else {
                Account[] newAccountsForType = new Account[newAccountsList.size()];
                newAccountsForType = newAccountsList.toArray(newAccountsForType);
                accounts.accountCache.put(account.type, newAccountsForType);
            }
        }
        accounts.userDataCache.remove(account);
        accounts.authTokenCache.remove(account);
    }

    /**
     * This assumes that the caller has already checked that the account is not already present.
     */
    private void insertAccountIntoCacheLocked(UserAccounts accounts, Account account) {
        Account[] accountsForType = accounts.accountCache.get(account.type);
        int oldLength = (accountsForType != null) ? accountsForType.length : 0;
        Account[] newAccountsForType = new Account[oldLength + 1];
        if (accountsForType != null) {
            System.arraycopy(accountsForType, 0, newAccountsForType, 0, oldLength);
        }
        newAccountsForType[oldLength] = account;
        accounts.accountCache.put(account.type, newAccountsForType);
    }

    private Account[] filterSharedAccounts(UserAccounts userAccounts, Account[] unfiltered,
            int callingUid, String callingPackage) {
        if (getUserManager() == null || userAccounts == null || userAccounts.userId < 0
                || callingUid == Process.myUid()) {
            return unfiltered;
        }
        if (mUserManager.getUserInfo(userAccounts.userId).isRestricted()) {
            String[] packages = mPackageManager.getPackagesForUid(callingUid);
            // If any of the packages is a white listed package, return the full set,
            // otherwise return non-shared accounts only.
            // This might be a temporary way to specify a whitelist
            String whiteList = mContext.getResources().getString(
                    com.android.internal.R.string.config_appsAuthorizedForSharedAccounts);
            for (String packageName : packages) {
                if (whiteList.contains(";" + packageName + ";")) {
                    return unfiltered;
                }
            }
            ArrayList<Account> allowed = new ArrayList<Account>();
            Account[] sharedAccounts = getSharedAccountsAsUser(userAccounts.userId);
            if (sharedAccounts == null || sharedAccounts.length == 0) return unfiltered;
            String requiredAccountType = "";
            try {
                // If there's an explicit callingPackage specified, check if that package
                // opted in to see restricted accounts.
                if (callingPackage != null) {
                    PackageInfo pi = mPackageManager.getPackageInfo(callingPackage, 0);
                    if (pi != null && pi.restrictedAccountType != null) {
                        requiredAccountType = pi.restrictedAccountType;
                    }
                } else {
                    // Otherwise check if the callingUid has a package that has opted in
                    for (String packageName : packages) {
                        PackageInfo pi = mPackageManager.getPackageInfo(packageName, 0);
                        if (pi != null && pi.restrictedAccountType != null) {
                            requiredAccountType = pi.restrictedAccountType;
                            break;
                        }
                    }
                }
            } catch (NameNotFoundException nnfe) {
            }
            for (Account account : unfiltered) {
                if (account.type.equals(requiredAccountType)) {
                    allowed.add(account);
                } else {
                    boolean found = false;
                    for (Account shared : sharedAccounts) {
                        if (shared.equals(account)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        allowed.add(account);
                    }
                }
            }
            Account[] filtered = new Account[allowed.size()];
            allowed.toArray(filtered);
            return filtered;
        } else {
            return unfiltered;
        }
    }

    /*
     * packageName can be null. If not null, it should be used to filter out restricted accounts
     * that the package is not allowed to access.
     */
    protected Account[] getAccountsFromCacheLocked(UserAccounts userAccounts, String accountType,
            int callingUid, String callingPackage) {
        if (accountType != null) {
            final Account[] accounts = userAccounts.accountCache.get(accountType);
            if (accounts == null) {
                return EMPTY_ACCOUNT_ARRAY;
            } else {
                return filterSharedAccounts(userAccounts, Arrays.copyOf(accounts, accounts.length),
                        callingUid, callingPackage);
            }
        } else {
            int totalLength = 0;
            for (Account[] accounts : userAccounts.accountCache.values()) {
                totalLength += accounts.length;
            }
            if (totalLength == 0) {
                return EMPTY_ACCOUNT_ARRAY;
            }
            Account[] accounts = new Account[totalLength];
            totalLength = 0;
            for (Account[] accountsOfType : userAccounts.accountCache.values()) {
                System.arraycopy(accountsOfType, 0, accounts, totalLength,
                        accountsOfType.length);
                totalLength += accountsOfType.length;
            }
            return filterSharedAccounts(userAccounts, accounts, callingUid, callingPackage);
        }
    }

    protected void writeUserDataIntoCacheLocked(UserAccounts accounts, final SQLiteDatabase db,
            Account account, String key, String value) {
        HashMap<String, String> userDataForAccount = accounts.userDataCache.get(account);
        if (userDataForAccount == null) {
            userDataForAccount = readUserDataForAccountFromDatabaseLocked(db, account);
            accounts.userDataCache.put(account, userDataForAccount);
        }
        if (value == null) {
            userDataForAccount.remove(key);
        } else {
            userDataForAccount.put(key, value);
        }
    }

    protected void writeAuthTokenIntoCacheLocked(UserAccounts accounts, final SQLiteDatabase db,
            Account account, String key, String value) {
        HashMap<String, String> authTokensForAccount = accounts.authTokenCache.get(account);
        if (authTokensForAccount == null) {
            authTokensForAccount = readAuthTokensForAccountFromDatabaseLocked(db, account);
            accounts.authTokenCache.put(account, authTokensForAccount);
        }
        if (value == null) {
            authTokensForAccount.remove(key);
        } else {
            authTokensForAccount.put(key, value);
        }
    }

    protected String readAuthTokenInternal(UserAccounts accounts, Account account,
            String authTokenType) {
        synchronized (accounts.cacheLock) {
            HashMap<String, String> authTokensForAccount = accounts.authTokenCache.get(account);
            if (authTokensForAccount == null) {
                // need to populate the cache for this account
                final SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
                authTokensForAccount = readAuthTokensForAccountFromDatabaseLocked(db, account);
                accounts.authTokenCache.put(account, authTokensForAccount);
            }
            return authTokensForAccount.get(authTokenType);
        }
    }

    protected String readUserDataInternal(UserAccounts accounts, Account account, String key) {
        synchronized (accounts.cacheLock) {
            HashMap<String, String> userDataForAccount = accounts.userDataCache.get(account);
            if (userDataForAccount == null) {
                // need to populate the cache for this account
                final SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
                userDataForAccount = readUserDataForAccountFromDatabaseLocked(db, account);
                accounts.userDataCache.put(account, userDataForAccount);
            }
            return userDataForAccount.get(key);
        }
    }

    protected HashMap<String, String> readUserDataForAccountFromDatabaseLocked(
            final SQLiteDatabase db, Account account) {
        HashMap<String, String> userDataForAccount = new HashMap<String, String>();
        Cursor cursor = db.query(TABLE_EXTRAS,
                COLUMNS_EXTRAS_KEY_AND_VALUE,
                SELECTION_USERDATA_BY_ACCOUNT,
                new String[]{account.name, account.type},
                null, null, null);
        try {
            while (cursor.moveToNext()) {
                final String tmpkey = cursor.getString(0);
                final String value = cursor.getString(1);
                userDataForAccount.put(tmpkey, value);
            }
        } finally {
            cursor.close();
        }
        return userDataForAccount;
    }

    protected HashMap<String, String> readAuthTokensForAccountFromDatabaseLocked(
            final SQLiteDatabase db, Account account) {
        HashMap<String, String> authTokensForAccount = new HashMap<String, String>();
        Cursor cursor = db.query(TABLE_AUTHTOKENS,
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
}
