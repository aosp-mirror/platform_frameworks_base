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
import android.accounts.AbstractAccountAuthenticator;
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
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

    public static class Lifecycle extends SystemService {
        private AccountManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new AccountManagerService(getContext());
            publishBinderService(Context.ACCOUNT_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemReady();
            }
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.onUnlockUser(userHandle);
        }
    }

    private static final String DATABASE_NAME = "accounts.db";
    private static final int PRE_N_DATABASE_VERSION = 9;
    private static final int CE_DATABASE_VERSION = 10;
    private static final int DE_DATABASE_VERSION = 1;

    private static final int MAX_DEBUG_DB_SIZE = 64;

    private final Context mContext;

    private final PackageManager mPackageManager;
    private final AppOpsManager mAppOpsManager;
    private UserManager mUserManager;

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

    private static final String TABLE_SHARED_ACCOUNTS = "shared_accounts";
    private static final String SHARED_ACCOUNTS_ID = "_id";

    private static final String PRE_N_DATABASE_NAME = "accounts.db";
    private static final String CE_DATABASE_NAME = "accounts_ce.db";
    private static final String DE_DATABASE_NAME = "accounts_de.db";
    private static final String CE_DB_PREFIX = "ceDb.";
    private static final String CE_TABLE_ACCOUNTS = CE_DB_PREFIX + TABLE_ACCOUNTS;
    private static final String CE_TABLE_AUTHTOKENS = CE_DB_PREFIX + TABLE_AUTHTOKENS;
    private static final String CE_TABLE_EXTRAS = CE_DB_PREFIX + TABLE_EXTRAS;

    private static final String[] ACCOUNT_TYPE_COUNT_PROJECTION =
            new String[] { ACCOUNTS_TYPE, ACCOUNTS_TYPE_COUNT};
    private static final Intent ACCOUNTS_CHANGED_INTENT;

    static {
        ACCOUNTS_CHANGED_INTENT = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
        ACCOUNTS_CHANGED_INTENT.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
    }

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

    private static final String META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX =
            "auth_uid_for_type:";
    private static final String META_KEY_DELIMITER = ":";
    private static final String SELECTION_META_BY_AUTHENTICATOR_TYPE = META_KEY + " LIKE ?";

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private final AtomicInteger mNotificationIds = new AtomicInteger(1);

    static class UserAccounts {
        private final int userId;
        private final DeDatabaseHelper openHelper;
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
        private final HashMap<Account, HashMap<String, String>> userDataCache =
                new HashMap<Account, HashMap<String, String>>();
        /** protected by the {@link #cacheLock} */
        private final HashMap<Account, HashMap<String, String>> authTokenCache =
                new HashMap<Account, HashMap<String, String>>();

        /** protected by the {@link #cacheLock} */
        private final TokenCache accountTokenCaches = new TokenCache();

        /**
         * protected by the {@link #cacheLock}
         *
         * Caches the previous names associated with an account. Previous names
         * should be cached because we expect that when an Account is renamed,
         * many clients will receive a LOGIN_ACCOUNTS_CHANGED broadcast and
         * want to know if the accounts they care about have been renamed.
         *
         * The previous names are wrapped in an {@link AtomicReference} so that
         * we can distinguish between those accounts with no previous names and
         * those whose previous names haven't been cached (yet).
         */
        private final HashMap<Account, AtomicReference<String>> previousNameCache =
                new HashMap<Account, AtomicReference<String>>();

        private int debugDbInsertionPoint = -1;
        private SQLiteStatement statementForLogging;

        UserAccounts(Context context, int userId, File preNDbFile, File deDbFile) {
            this.userId = userId;
            synchronized (cacheLock) {
                openHelper = DeDatabaseHelper.create(context, userId, preNDbFile, deDbFile);
            }
        }
    }

    private final SparseArray<UserAccounts> mUsers = new SparseArray<>();
    private final SparseBooleanArray mLocalUnlockedUsers = new SparseBooleanArray();

    private static AtomicReference<AccountManagerService> sThis = new AtomicReference<>();
    private static final Account[] EMPTY_ACCOUNT_ARRAY = new Account[]{};

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
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);

        mMessageHandler = new MessageHandler(FgThread.get().getLooper());

        mAuthenticatorCache = authenticatorCache;
        mAuthenticatorCache.setListener(this, null /* Handler */);

        sThis.set(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context1, Intent intent) {
                // Don't delete accounts when updating a authenticator's
                // package.
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    /* Purging data requires file io, don't block the main thread. This is probably
                     * less than ideal because we are introducing a race condition where old grants
                     * could be exercised until they are purged. But that race condition existed
                     * anyway with the broadcast receiver.
                     *
                     * Ideally, we would completely clear the cache, purge data from the database,
                     * and then rebuild the cache. All under the cache lock. But that change is too
                     * large at this point.
                     */
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            purgeOldGrantsAll();
                        }
                    };
                    new Thread(r).start();
                }
            }
        }, intentFilter);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    onUserRemoved(intent);
                }
            }
        }, UserHandle.ALL, userFilter, null, null);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The account manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Account Manager Crash", e);
            }
            throw e;
        }
    }

    public void systemReady() {
    }

    private UserManager getUserManager() {
        if (mUserManager == null) {
            mUserManager = UserManager.get(mContext);
        }
        return mUserManager;
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
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "validateAccountsInternal " + accounts.userId
                    + " isCeDatabaseAttached=" + accounts.openHelper.isCeDatabaseAttached()
                    + " userLocked=" + mLocalUnlockedUsers.get(accounts.userId));
        }

        if (invalidateAuthenticatorCache) {
            mAuthenticatorCache.invalidateCache(accounts.userId);
        }

        final HashMap<String, Integer> knownAuth = getAuthenticatorTypeAndUIDForUser(
                mAuthenticatorCache, accounts.userId);
        boolean userUnlocked = isLocalUnlockedUser(accounts.userId);

        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            boolean accountDeleted = false;

            // Get a list of stored authenticator type and UID
            Cursor metaCursor = db.query(
                    TABLE_META,
                    new String[] {META_KEY, META_VALUE},
                    SELECTION_META_BY_AUTHENTICATOR_TYPE,
                    new String[] {META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + "%"},
                    null /* groupBy */,
                    null /* having */,
                    META_KEY);
            // Create a list of authenticator type whose previous uid no longer exists
            HashSet<String> obsoleteAuthType = Sets.newHashSet();
            try {
                SparseBooleanArray knownUids = null;
                while (metaCursor.moveToNext()) {
                    String type = TextUtils.split(metaCursor.getString(0), META_KEY_DELIMITER)[1];
                    String uid = metaCursor.getString(1);
                    if (TextUtils.isEmpty(type) || TextUtils.isEmpty(uid)) {
                        // Should never happen.
                        Slog.e(TAG, "Auth type empty: " + TextUtils.isEmpty(type)
                                + ", uid empty: " + TextUtils.isEmpty(uid));
                        continue;
                    }
                    Integer knownUid = knownAuth.get(type);
                    if (knownUid != null && uid.equals(knownUid.toString())) {
                        // Remove it from the knownAuth list if it's unchanged.
                        knownAuth.remove(type);
                    } else {
                        /*
                         * The authenticator is presently not cached and should only be triggered
                         * when we think an authenticator has been removed (or is being updated).
                         * But we still want to check if any data with the associated uid is
                         * around. This is an (imperfect) signal that the package may be updating.
                         *
                         * A side effect of this is that an authenticator sharing a uid with
                         * multiple apps won't get its credentials wiped as long as some app with
                         * that uid is still on the device. But I suspect that this is a rare case.
                         * And it isn't clear to me how an attacker could really exploit that
                         * feature.
                         *
                         * The upshot is that we don't have to worry about accounts getting
                         * uninstalled while the authenticator's package is being updated.
                         *
                         */
                        if (knownUids == null) {
                            knownUids = getUidsOfInstalledOrUpdatedPackagesAsUser(accounts.userId); 
                        }
                        if (!knownUids.get(Integer.parseInt(uid))) {
                            // The authenticator is not presently available to the cache. And the
                            // package no longer has a data directory (so we surmise it isn't updating).
                            // So purge its data from the account databases.
                            obsoleteAuthType.add(type);
                            // And delete it from the TABLE_META
                            db.delete(
                                    TABLE_META,
                                    META_KEY + "=? AND " + META_VALUE + "=?",
                                    new String[] {
                                            META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + type,
                                            uid}
                                    );
                        }
                    }
                }
            } finally {
                metaCursor.close();
            }

            // Add the newly registered authenticator to TABLE_META. If old authenticators have
            // been renabled (after being updated for example), then we just overwrite the old
            // values.
            Iterator<Entry<String, Integer>> iterator = knownAuth.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, Integer> entry = iterator.next();
                ContentValues values = new ContentValues();
                values.put(META_KEY,
                        META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + entry.getKey());
                values.put(META_VALUE, entry.getValue());
                db.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }

            Cursor cursor = db.query(TABLE_ACCOUNTS,
                    new String[]{ACCOUNTS_ID, ACCOUNTS_TYPE, ACCOUNTS_NAME},
                    null, null, null, null, ACCOUNTS_ID);
            try {
                accounts.accountCache.clear();
                final HashMap<String, ArrayList<String>> accountNamesByType = new LinkedHashMap<>();
                while (cursor.moveToNext()) {
                    final long accountId = cursor.getLong(0);
                    final String accountType = cursor.getString(1);
                    final String accountName = cursor.getString(2);

                    if (obsoleteAuthType.contains(accountType)) {
                        Slog.w(TAG, "deleting account " + accountName + " because type "
                                + accountType + "'s registered authenticator no longer exist.");
                        db.beginTransaction();
                        try {
                            db.delete(TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null);
                            // Also delete from CE table if user is unlocked; if user is currently
                            // locked the account will be removed later by syncDeCeAccountsLocked
                            if (userUnlocked) {
                                db.delete(CE_TABLE_ACCOUNTS, ACCOUNTS_ID + "=" + accountId, null);
                            }
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                        accountDeleted = true;

                        logRecord(db, DebugDbHelper.ACTION_AUTHENTICATOR_REMOVE, TABLE_ACCOUNTS,
                                accountId, accounts);

                        final Account account = new Account(accountName, accountType);
                        accounts.userDataCache.remove(account);
                        accounts.authTokenCache.remove(account);
                        accounts.accountTokenCaches.remove(account);
                    } else {
                        ArrayList<String> accountNames = accountNamesByType.get(accountType);
                        if (accountNames == null) {
                            accountNames = new ArrayList<String>();
                            accountNamesByType.put(accountType, accountNames);
                        }
                        accountNames.add(accountName);
                    }
                }
                for (Map.Entry<String, ArrayList<String>> cur : accountNamesByType.entrySet()) {
                    final String accountType = cur.getKey();
                    final ArrayList<String> accountNames = cur.getValue();
                    final Account[] accountsForType = new Account[accountNames.size()];
                    for (int i = 0; i < accountsForType.length; i++) {
                        accountsForType[i] = new Account(accountNames.get(i), accountType);
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

    private SparseBooleanArray getUidsOfInstalledOrUpdatedPackagesAsUser(int userId) {
        // Get the UIDs of all apps that might have data on the device. We want
        // to preserve user data if the app might otherwise be storing data.
        List<PackageInfo> pkgsWithData =
                mPackageManager.getInstalledPackagesAsUser(
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        SparseBooleanArray knownUids = new SparseBooleanArray(pkgsWithData.size());
        for (PackageInfo pkgInfo : pkgsWithData) {
            if (pkgInfo.applicationInfo != null
                    && (pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                knownUids.put(pkgInfo.applicationInfo.uid, true);
            }
        }
        return knownUids;
    }

    private static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(
            Context context,
            int userId) {
        AccountAuthenticatorCache authCache = new AccountAuthenticatorCache(context);
        return getAuthenticatorTypeAndUIDForUser(authCache, userId);
    }

    private static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(
            IAccountAuthenticatorCache authCache,
            int userId) {
        HashMap<String, Integer> knownAuth = new HashMap<>();
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> service : authCache
                .getAllServices(userId)) {
            knownAuth.put(service.type.type, service.uid);
        }
        return knownAuth;
    }

    private UserAccounts getUserAccountsForCaller() {
        return getUserAccounts(UserHandle.getCallingUserId());
    }

    protected UserAccounts getUserAccounts(int userId) {
        synchronized (mUsers) {
            UserAccounts accounts = mUsers.get(userId);
            boolean validateAccounts = false;
            if (accounts == null) {
                File preNDbFile = new File(getPreNDatabaseName(userId));
                File deDbFile = new File(getDeDatabaseName(userId));
                accounts = new UserAccounts(mContext, userId, preNDbFile, deDbFile);
                initializeDebugDbSizeAndCompileSqlStatementForLogging(
                        accounts.openHelper.getWritableDatabase(), accounts);
                mUsers.append(userId, accounts);
                purgeOldGrants(accounts);
                validateAccounts = true;
            }
            // open CE database if necessary
            if (!accounts.openHelper.isCeDatabaseAttached() && mLocalUnlockedUsers.get(userId)) {
                Log.i(TAG, "User " + userId + " is unlocked - opening CE database");
                synchronized (accounts.cacheLock) {
                    File preNDatabaseFile = new File(getPreNDatabaseName(userId));
                    File ceDatabaseFile = new File(getCeDatabaseName(userId));
                    CeDatabaseHelper.create(mContext, userId, preNDatabaseFile, ceDatabaseFile);
                    accounts.openHelper.attachCeDatabase(ceDatabaseFile);
                }
                syncDeCeAccountsLocked(accounts);
            }
            if (validateAccounts) {
                validateAccountsInternal(accounts, true /* invalidateAuthenticatorCache */);
            }
            return accounts;
        }
    }

    private void syncDeCeAccountsLocked(UserAccounts accounts) {
        Preconditions.checkState(Thread.holdsLock(mUsers), "mUsers lock must be held");
        final SQLiteDatabase db = accounts.openHelper.getReadableDatabaseUserIsUnlocked();
        List<Account> accountsToRemove = CeDatabaseHelper.findCeAccountsNotInDe(db);
        if (!accountsToRemove.isEmpty()) {
            Slog.i(TAG, "Accounts " + accountsToRemove + " were previously deleted while user "
                    + accounts.userId + " was locked. Removing accounts from CE tables");
            logRecord(accounts, DebugDbHelper.ACTION_SYNC_DE_CE_ACCOUNTS, TABLE_ACCOUNTS);

            for (Account account : accountsToRemove) {
                removeAccountInternal(accounts, account, Process.myUid());
            }
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

    private void onUserRemoved(Intent intent) {
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
        if (userId < 1) return;

        UserAccounts accounts;
        boolean userUnlocked;
        synchronized (mUsers) {
            accounts = mUsers.get(userId);
            mUsers.remove(userId);
            userUnlocked = mLocalUnlockedUsers.get(userId);
            mLocalUnlockedUsers.delete(userId);
        }
        if (accounts != null) {
            synchronized (accounts.cacheLock) {
                accounts.openHelper.close();
            }
        }
        Log.i(TAG, "Removing database files for user " + userId);
        File dbFile = new File(getDeDatabaseName(userId));

        deleteDbFileWarnIfFailed(dbFile);
        // Remove CE file if user is unlocked, or FBE is not enabled
        boolean fbeEnabled = StorageManager.isFileEncryptedNativeOrEmulated();
        if (!fbeEnabled || userUnlocked) {
            File ceDb = new File(getCeDatabaseName(userId));
            if (ceDb.exists()) {
                deleteDbFileWarnIfFailed(ceDb);
            }
        }
    }

    private static void deleteDbFileWarnIfFailed(File dbFile) {
        if (!SQLiteDatabase.deleteDatabase(dbFile)) {
            Log.w(TAG, "Database at " + dbFile + " was not deleted successfully");
        }
    }

    @VisibleForTesting
    void onUserUnlocked(Intent intent) {
        onUnlockUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1));
    }

    void onUnlockUser(int userId) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "onUserUnlocked " + userId);
        }
        synchronized (mUsers) {
            mLocalUnlockedUsers.put(userId, true);
        }
        if (userId < 1) return;
        syncSharedAccounts(userId);
    }

    private void syncSharedAccounts(int userId) {
        // Check if there's a shared account that needs to be created as an account
        Account[] sharedAccounts = getSharedAccountsAsUser(userId);
        if (sharedAccounts == null || sharedAccounts.length == 0) return;
        Account[] accounts = getAccountsAsUser(null, userId, mContext.getOpPackageName());
        int parentUserId = UserManager.isSplitSystemUser()
                ? getUserManager().getUserInfo(userId).restrictedProfileParentId
                : UserHandle.USER_SYSTEM;
        if (parentUserId < 0) {
            Log.w(TAG, "User " + userId + " has shared accounts, but no parent user");
            return;
        }
        for (Account sa : sharedAccounts) {
            if (ArrayUtils.contains(accounts, sa)) continue;
            // Account doesn't exist. Copy it now.
            copyAccountToUser(null /*no response*/, sa, parentUserId, userId);
        }
    }

    @Override
    public void onServiceChanged(AuthenticatorDescription desc, int userId, boolean removed) {
        validateAccountsInternal(getUserAccounts(userId), false /* invalidateAuthenticatorCache */);
    }

    @Override
    public String getPassword(Account account) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getPassword: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot get secrets for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return readPasswordInternal(accounts, account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPasswordInternal(UserAccounts accounts, Account account) {
        if (account == null) {
            return null;
        }
        if (!isLocalUnlockedUser(accounts.userId)) {
            Log.w(TAG, "Password is not available - user " + accounts.userId + " data is locked");
            return null;
        }

        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getReadableDatabaseUserIsUnlocked();
            return CeDatabaseHelper.findAccountPasswordByNameAndType(db, account.name,
                    account.type);
        }
    }

    @Override
    public String getPreviousName(Account account) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getPreviousName: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return readPreviousNameInternal(accounts, account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPreviousNameInternal(UserAccounts accounts, Account account) {
        if  (account == null) {
            return null;
        }
        synchronized (accounts.cacheLock) {
            AtomicReference<String> previousNameRef = accounts.previousNameCache.get(account);
            if (previousNameRef == null) {
                final SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
                Cursor cursor = db.query(
                        TABLE_ACCOUNTS,
                        new String[]{ ACCOUNTS_PREVIOUS_NAME },
                        ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                        new String[] { account.name, account.type },
                        null,
                        null,
                        null);
                try {
                    if (cursor.moveToNext()) {
                        String previousName = cursor.getString(0);
                        previousNameRef = new AtomicReference<>(previousName);
                        accounts.previousNameCache.put(account, previousNameRef);
                        return previousName;
                    } else {
                        return null;
                    }
                } finally {
                    cursor.close();
                }
            } else {
                return previousNameRef.get();
            }
        }
    }

    @Override
    public String getUserData(Account account, String key) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            String msg = String.format("getUserData( account: %s, key: %s, callerUid: %s, pid: %s",
                    account, key, callingUid, Binder.getCallingPid());
            Log.v(TAG, msg);
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot get user data for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        if (!isLocalUnlockedUser(userId)) {
            Log.w(TAG, "User " + userId + " data is locked. callingUid " + callingUid);
            return null;
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.cacheLock) {
                if (!accountExistsCacheLocked(accounts, account)) {
                    return null;
                }
                return readUserDataInternalLocked(accounts, account, key);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public AuthenticatorDescription[] getAuthenticatorTypes(int userId) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAuthenticatorTypes: "
                    + "for user id " + userId
                    + " caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        // Only allow the system process to read accounts of other users
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s tying to get authenticator types for %s" ,
                            UserHandle.getCallingUserId(),
                            userId));
        }

        final long identityToken = clearCallingIdentity();
        try {
            return getAuthenticatorTypesInternal(userId);

        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Should only be called inside of a clearCallingIdentity block.
     */
    private AuthenticatorDescription[] getAuthenticatorTypesInternal(int userId) {
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
    }



    private boolean isCrossUser(int callingUid, int userId) {
        return (userId != UserHandle.getCallingUserId()
                && callingUid != Process.myUid()
                && mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                                != PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
        Bundle.setDefusable(extras, true);
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addAccountExplicitly: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot explicitly add accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        /*
         * Child users are not allowed to add accounts. Only the accounts that are
         * shared by the parent profile can be added to child profile.
         *
         * TODO: Only allow accounts that were shared to be added by
         *     a limited user.
         */

        // fails if the account already exists
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return addAccountInternal(accounts, account, password, extras, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void copyAccountToUser(final IAccountManagerResponse response, final Account account,
            final int userFrom, int userTo) {
        int callingUid = Binder.getCallingUid();
        if (isCrossUser(callingUid, UserHandle.USER_ALL)) {
            throw new SecurityException("Calling copyAccountToUser requires "
                    + android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        }
        final UserAccounts fromAccounts = getUserAccounts(userFrom);
        final UserAccounts toAccounts = getUserAccounts(userTo);
        if (fromAccounts == null || toAccounts == null) {
            if (response != null) {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                try {
                    response.onResult(result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report error back to the client." + e);
                }
            }
            return;
        }

        Slog.d(TAG, "Copying account " + account.name
                + " from user " + userFrom + " to user " + userTo);
        long identityToken = clearCallingIdentity();
        try {
            new Session(fromAccounts, response, account.type, false,
                    false /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */) {
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAccountCredentialsForClone"
                            + ", " + account.type;
                }

                @Override
                public void run() throws RemoteException {
                    mAuthenticator.getAccountCredentialsForCloning(this, account);
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    if (result != null
                            && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                        // Create a Session for the target user and pass in the bundle
                        completeCloningAccount(response, result, account, toAccounts, userFrom);
                    } else {
                        super.onResult(result);
                    }
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean accountAuthenticated(final Account account) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            String msg = String.format(
                    "accountAuthenticated( account: %s, callerUid: %s)",
                    account,
                    callingUid);
            Log.v(TAG, msg);
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot notify authentication for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }

        if (!canUserModifyAccounts(userId, callingUid) ||
                !canUserModifyAccountsForType(userId, account.type, callingUid)) {
            return false;
        }

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return updateLastAuthenticatedTime(account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean updateLastAuthenticatedTime(Account account) {
        final UserAccounts accounts = getUserAccountsForCaller();
        synchronized (accounts.cacheLock) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS, System.currentTimeMillis());
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
            int i = db.update(
                    TABLE_ACCOUNTS,
                    values,
                    ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE + "=?",
                    new String[] {
                            account.name, account.type
                    });
            if (i > 0) {
                return true;
            }
        }
        return false;
    }

    private void completeCloningAccount(IAccountManagerResponse response,
            final Bundle accountCredentials, final Account account, final UserAccounts targetUser,
            final int parentUserId){
        Bundle.setDefusable(accountCredentials, true);
        long id = clearCallingIdentity();
        try {
            new Session(targetUser, response, account.type, false,
                    false /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */) {
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAccountCredentialsForClone"
                            + ", " + account.type;
                }

                @Override
                public void run() throws RemoteException {
                    // Confirm that the owner's account still exists before this step.
                    UserAccounts owner = getUserAccounts(parentUserId);
                    synchronized (owner.cacheLock) {
                        for (Account acc : getAccounts(parentUserId,
                                mContext.getOpPackageName())) {
                            if (acc.equals(account)) {
                                mAuthenticator.addAccountFromCredentials(
                                        this, account, accountCredentials);
                                break;
                            }
                        }
                    }
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    // TODO: Anything to do if if succedded?
                    // TODO: If it failed: Show error notification? Should we remove the shadow
                    // account to avoid retries?
                    super.onResult(result);
                }

                @Override
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
            Bundle extras, int callingUid) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            return false;
        }
        if (!isLocalUnlockedUser(accounts.userId)) {
            Log.w(TAG, "Account " + account + " cannot be added - user " + accounts.userId
                    + " is locked. callingUid=" + callingUid);
            return false;
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabaseUserIsUnlocked();
            db.beginTransaction();
            try {
                long numMatches = DatabaseUtils.longForQuery(db,
                        "select count(*) from " + CE_TABLE_ACCOUNTS
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
                long accountId = db.insert(CE_TABLE_ACCOUNTS, ACCOUNTS_NAME, values);
                if (accountId < 0) {
                    Log.w(TAG, "insertAccountIntoDatabase: " + account
                            + ", skipping the DB insert failed");
                    return false;
                }
                // Insert into DE table
                values = new ContentValues();
                values.put(ACCOUNTS_ID, accountId);
                values.put(ACCOUNTS_NAME, account.name);
                values.put(ACCOUNTS_TYPE, account.type);
                values.put(ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS,
                        System.currentTimeMillis());
                if (db.insert(TABLE_ACCOUNTS, ACCOUNTS_NAME, values) < 0) {
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

                logRecord(db, DebugDbHelper.ACTION_ACCOUNT_ADD, TABLE_ACCOUNTS, accountId,
                        accounts, callingUid);

                insertAccountIntoCacheLocked(accounts, account);
            } finally {
                db.endTransaction();
            }
            sendAccountsChangedBroadcast(accounts.userId);
        }
        if (getUserManager().getUserInfo(accounts.userId).canHaveProfile()) {
            addAccountToLinkedRestrictedUsers(account, accounts.userId);
        }
        return true;
    }

    private boolean isLocalUnlockedUser(int userId) {
        synchronized (mUsers) {
            return mLocalUnlockedUsers.get(userId);
        }
    }

    /**
     * Adds the account to all linked restricted users as shared accounts. If the user is currently
     * running, then clone the account too.
     * @param account the account to share with limited users
     *
     */
    private void addAccountToLinkedRestrictedUsers(Account account, int parentUserId) {
        List<UserInfo> users = getUserManager().getUsers();
        for (UserInfo user : users) {
            if (user.isRestricted() && (parentUserId == user.restrictedProfileParentId)) {
                addSharedAccountAsUser(account, user.id);
                if (isLocalUnlockedUser(user.id)) {
                    mMessageHandler.sendMessage(mMessageHandler.obtainMessage(
                            MESSAGE_COPY_SHARED_ACCOUNT, parentUserId, user.id, account));
                }
            }
        }
    }

    private long insertExtraLocked(SQLiteDatabase db, long accountId, String key, String value) {
        ContentValues values = new ContentValues();
        values.put(EXTRAS_KEY, key);
        values.put(EXTRAS_ACCOUNTS_ID, accountId);
        values.put(EXTRAS_VALUE, value);
        return db.insert(CE_TABLE_EXTRAS, EXTRAS_KEY, values);
    }

    @Override
    public void hasFeatures(IAccountManagerResponse response,
            Account account, String[] features, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "hasFeatures: " + account
                    + ", response " + response
                    + ", features " + stringArrayToString(features)
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        if (features == null) throw new IllegalArgumentException("features is null");
        int userId = UserHandle.getCallingUserId();
        checkReadAccountsPermitted(callingUid, account.type, userId,
                opPackageName);

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
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
                    true /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */);
            mFeatures = features;
            mAccount = account;
        }

        @Override
        public void run() throws RemoteException {
            try {
                mAuthenticator.hasFeatures(this, mAccount, mFeatures);
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        @Override
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
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

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", hasFeatures"
                    + ", " + mAccount
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    @Override
    public void renameAccount(
            IAccountManagerResponse response, Account accountToRename, String newName) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "renameAccount: " + accountToRename + " -> " + newName
                + ", caller's uid " + callingUid
                + ", pid " + Binder.getCallingPid());
        }
        if (accountToRename == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(accountToRename.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot rename accounts of type: %s",
                    callingUid,
                    accountToRename.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            Account resultingAccount = renameAccountInternal(accounts, accountToRename, newName);
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_ACCOUNT_NAME, resultingAccount.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, resultingAccount.type);
            try {
                response.onResult(result);
            } catch (RemoteException e) {
                Log.w(TAG, e.getMessage());
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private Account renameAccountInternal(
            UserAccounts accounts, Account accountToRename, String newName) {
        Account resultAccount = null;
        /*
         * Cancel existing notifications. Let authenticators
         * re-post notifications as required. But we don't know if
         * the authenticators have bound their notifications to
         * now stale account name data.
         *
         * With a rename api, we might not need to do this anymore but it
         * shouldn't hurt.
         */
        cancelNotification(
                getSigninRequiredNotificationId(accounts, accountToRename),
                 new UserHandle(accounts.userId));
        synchronized(accounts.credentialsPermissionNotificationIds) {
            for (Pair<Pair<Account, String>, Integer> pair:
                    accounts.credentialsPermissionNotificationIds.keySet()) {
                if (accountToRename.equals(pair.first.first)) {
                    int id = accounts.credentialsPermissionNotificationIds.get(pair);
                    cancelNotification(id, new UserHandle(accounts.userId));
                }
            }
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabaseUserIsUnlocked();
            db.beginTransaction();
            boolean isSuccessful = false;
            Account renamedAccount = new Account(newName, accountToRename.type);
            try {
                final long accountId = getAccountIdLocked(db, accountToRename);
                if (accountId >= 0) {
                    final ContentValues values = new ContentValues();
                    values.put(ACCOUNTS_NAME, newName);
                    final String[] argsAccountId = { String.valueOf(accountId) };
                    db.update(CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId);
                    // Update NAME/PREVIOUS_NAME in DE accounts table
                    values.put(ACCOUNTS_PREVIOUS_NAME, accountToRename.name);
                    db.update(TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId);
                    db.setTransactionSuccessful();
                    isSuccessful = true;
                    logRecord(db, DebugDbHelper.ACTION_ACCOUNT_RENAME, TABLE_ACCOUNTS, accountId,
                            accounts);
                }
            } finally {
                db.endTransaction();
                if (isSuccessful) {
                    /*
                     * Database transaction was successful. Clean up cached
                     * data associated with the account in the user profile.
                     */
                    insertAccountIntoCacheLocked(accounts, renamedAccount);
                    /*
                     * Extract the data and token caches before removing the
                     * old account to preserve the user data associated with
                     * the account.
                     */
                    HashMap<String, String> tmpData = accounts.userDataCache.get(accountToRename);
                    HashMap<String, String> tmpTokens = accounts.authTokenCache.get(accountToRename);
                    removeAccountFromCacheLocked(accounts, accountToRename);
                    /*
                     * Update the cached data associated with the renamed
                     * account.
                     */
                    accounts.userDataCache.put(renamedAccount, tmpData);
                    accounts.authTokenCache.put(renamedAccount, tmpTokens);
                    accounts.previousNameCache.put(
                          renamedAccount,
                          new AtomicReference<String>(accountToRename.name));
                    resultAccount = renamedAccount;

                    int parentUserId = accounts.userId;
                    if (canHaveProfile(parentUserId)) {
                        /*
                         * Owner or system user account was renamed, rename the account for
                         * those users with which the account was shared.
                         */
                        List<UserInfo> users = getUserManager().getUsers(true);
                        for (UserInfo user : users) {
                            if (user.isRestricted()
                                    && (user.restrictedProfileParentId == parentUserId)) {
                                renameSharedAccountAsUser(accountToRename, newName, user.id);
                            }
                        }
                    }
                    sendAccountsChangedBroadcast(accounts.userId);
                }
            }
        }
        return resultAccount;
    }

    private boolean canHaveProfile(final int parentUserId) {
        final UserInfo userInfo = getUserManager().getUserInfo(parentUserId);
        return userInfo != null && userInfo.canHaveProfile();
    }

    @Override
    public void removeAccount(IAccountManagerResponse response, Account account,
            boolean expectActivityLaunch) {
        removeAccountAsUser(
                response,
                account,
                expectActivityLaunch,
                UserHandle.getCallingUserId());
    }

    @Override
    public void removeAccountAsUser(IAccountManagerResponse response, Account account,
            boolean expectActivityLaunch, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "removeAccount: " + account
                    + ", response " + response
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid()
                    + ", for user id " + userId);
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        // Only allow the system process to modify accounts of other users
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s tying remove account for %s" ,
                            UserHandle.getCallingUserId(),
                            userId));
        }
        /*
         * Only the system or authenticator should be allowed to remove accounts for that
         * authenticator.  This will let users remove accounts (via Settings in the system) but not
         * arbitrary applications (like competing authenticators).
         */
        UserHandle user = UserHandle.of(userId);
        if (!isAccountManagedByCaller(account.type, callingUid, user.getIdentifier())
                && !isSystemUid(callingUid)) {
            String msg = String.format(
                    "uid %s cannot remove accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        if (!canUserModifyAccounts(userId, callingUid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_USER_RESTRICTED,
                        "User cannot modify accounts");
            } catch (RemoteException re) {
            }
            return;
        }
        if (!canUserModifyAccountsForType(userId, account.type, callingUid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                        "User cannot modify accounts of this type (policy).");
            } catch (RemoteException re) {
            }
            return;
        }
        long identityToken = clearCallingIdentity();
        UserAccounts accounts = getUserAccounts(userId);
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
        SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
        final long accountId = getAccountIdLocked(db, account);
        logRecord(
                db,
                DebugDbHelper.ACTION_CALLED_ACCOUNT_REMOVE,
                TABLE_ACCOUNTS,
                accountId,
                accounts,
                callingUid);
        try {
            new RemoveAccountSession(accounts, response, account, expectActivityLaunch).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean removeAccountExplicitly(Account account) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "removeAccountExplicitly: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        int userId = Binder.getCallingUserHandle().getIdentifier();
        if (account == null) {
            /*
             * Null accounts should result in returning false, as per
             * AccountManage.addAccountExplicitly(...) java doc.
             */
            Log.e(TAG, "account is null");
            return false;
        } else if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot explicitly add accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        UserAccounts accounts = getUserAccountsForCaller();
        SQLiteDatabase db = accounts.openHelper.getReadableDatabase();
        final long accountId = getAccountIdLocked(db, account);
        logRecord(
                db,
                DebugDbHelper.ACTION_CALLED_ACCOUNT_REMOVE,
                TABLE_ACCOUNTS,
                accountId,
                accounts,
                callingUid);
        long identityToken = clearCallingIdentity();
        try {
            return removeAccountInternal(accounts, account, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private class RemoveAccountSession extends Session {
        final Account mAccount;
        public RemoveAccountSession(UserAccounts accounts, IAccountManagerResponse response,
                Account account, boolean expectActivityLaunch) {
            super(accounts, response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */);
            mAccount = account;
        }

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", removeAccount"
                    + ", account " + mAccount;
        }

        @Override
        public void run() throws RemoteException {
            mAuthenticator.getAccountRemovalAllowed(this, mAccount);
        }

        @Override
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)) {
                final boolean removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                if (removalAllowed) {
                    removeAccountInternal(mAccounts, mAccount, getCallingUid());
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

    @VisibleForTesting
    protected void removeAccountInternal(Account account) {
        removeAccountInternal(getUserAccountsForCaller(), account, getCallingUid());
    }

    private boolean removeAccountInternal(UserAccounts accounts, Account account, int callingUid) {
        int deleted;
        boolean userUnlocked = isLocalUnlockedUser(accounts.userId);
        if (!userUnlocked) {
            Slog.i(TAG, "Removing account " + account + " while user "+ accounts.userId
                    + " is still locked. CE data will be removed later");
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = userUnlocked
                    ? accounts.openHelper.getWritableDatabaseUserIsUnlocked()
                    : accounts.openHelper.getWritableDatabase();
            final long accountId = getAccountIdLocked(db, account);
            db.beginTransaction();
            try {
                deleted = db.delete(TABLE_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE
                                + "=?", new String[]{account.name, account.type});
                if (userUnlocked) {
                    // Delete from CE table
                    deleted = db.delete(CE_TABLE_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE
                            + "=?", new String[]{account.name, account.type});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            removeAccountFromCacheLocked(accounts, account);
            sendAccountsChangedBroadcast(accounts.userId);
            String action = userUnlocked ? DebugDbHelper.ACTION_ACCOUNT_REMOVE
                    : DebugDbHelper.ACTION_ACCOUNT_REMOVE_DE;
            logRecord(db, action, TABLE_ACCOUNTS, accountId, accounts);
        }
        long id = Binder.clearCallingIdentity();
        try {
            int parentUserId = accounts.userId;
            if (canHaveProfile(parentUserId)) {
                // Remove from any restricted profiles that are sharing this account.
                List<UserInfo> users = getUserManager().getUsers(true);
                for (UserInfo user : users) {
                    if (user.isRestricted() && parentUserId == (user.restrictedProfileParentId)) {
                        removeSharedAccountAsUser(account, user.id, callingUid);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return (deleted > 0);
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken) {
        int callerUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "invalidateAuthToken: accountType " + accountType
                    + ", caller's uid " + callerUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (authToken == null) throw new IllegalArgumentException("authToken is null");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.cacheLock) {
                final SQLiteDatabase db = accounts.openHelper.getWritableDatabaseUserIsUnlocked();
                db.beginTransaction();
                try {
                    invalidateAuthTokenLocked(accounts, db, accountType, authToken);
                    invalidateCustomTokenLocked(accounts, accountType, authToken);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void invalidateCustomTokenLocked(
            UserAccounts accounts,
            String accountType,
            String authToken) {
        if (authToken == null || accountType == null) {
            return;
        }
        // Also wipe out cached token in memory.
        accounts.accountTokenCaches.remove(accountType, authToken);
    }

    private void invalidateAuthTokenLocked(UserAccounts accounts, SQLiteDatabase db,
            String accountType, String authToken) {
        if (authToken == null || accountType == null) {
            return;
        }
        Cursor cursor = db.rawQuery(
                "SELECT " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_ID
                        + ", " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_NAME
                        + ", " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_TYPE
                        + " FROM " + CE_TABLE_ACCOUNTS
                        + " JOIN " + CE_TABLE_AUTHTOKENS
                        + " ON " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_ID
                        + " = " + CE_TABLE_AUTHTOKENS + "." + AUTHTOKENS_ACCOUNTS_ID
                        + " WHERE " + CE_TABLE_AUTHTOKENS + "."  + AUTHTOKENS_AUTHTOKEN
                        + " = ? AND " + CE_TABLE_ACCOUNTS + "." + ACCOUNTS_TYPE + " = ?",
                new String[]{authToken, accountType});
        try {
            while (cursor.moveToNext()) {
                long authTokenId = cursor.getLong(0);
                String accountName = cursor.getString(1);
                String authTokenType = cursor.getString(2);
                db.delete(CE_TABLE_AUTHTOKENS, AUTHTOKENS_ID + "=" + authTokenId, null);
                writeAuthTokenIntoCacheLocked(
                        accounts,
                        db,
                        new Account(accountName, accountType),
                        authTokenType,
                        null);
            }
        } finally {
            cursor.close();
        }
    }

    private void saveCachedToken(
            UserAccounts accounts,
            Account account,
            String callerPkg,
            byte[] callerSigDigest,
            String tokenType,
            String token,
            long expiryMillis) {

        if (account == null || tokenType == null || callerPkg == null || callerSigDigest == null) {
            return;
        }
        cancelNotification(getSigninRequiredNotificationId(accounts, account),
                UserHandle.of(accounts.userId));
        synchronized (accounts.cacheLock) {
            accounts.accountTokenCaches.put(
                    account, token, tokenType, callerPkg, callerSigDigest, expiryMillis);
        }
    }

    private boolean saveAuthTokenToDatabase(UserAccounts accounts, Account account, String type,
            String authToken) {
        if (account == null || type == null) {
            return false;
        }
        cancelNotification(getSigninRequiredNotificationId(accounts, account),
                UserHandle.of(accounts.userId));
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabaseUserIsUnlocked();
            db.beginTransaction();
            try {
                long accountId = getAccountIdLocked(db, account);
                if (accountId < 0) {
                    return false;
                }
                db.delete(CE_TABLE_AUTHTOKENS,
                        AUTHTOKENS_ACCOUNTS_ID + "=" + accountId + " AND " + AUTHTOKENS_TYPE + "=?",
                        new String[]{type});
                ContentValues values = new ContentValues();
                values.put(AUTHTOKENS_ACCOUNTS_ID, accountId);
                values.put(AUTHTOKENS_TYPE, type);
                values.put(AUTHTOKENS_AUTHTOKEN, authToken);
                if (db.insert(CE_TABLE_AUTHTOKENS, AUTHTOKENS_AUTHTOKEN, values) >= 0) {
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

    @Override
    public String peekAuthToken(Account account, String authTokenType) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "peekAuthToken: " + account
                    + ", authTokenType " + authTokenType
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot peek the authtokens associated with accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        if (!isLocalUnlockedUser(userId)) {
            Log.w(TAG, "Authtoken not available - user " + userId + " data is locked. callingUid "
                    + callingUid);
            return null;
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return readAuthTokenInternal(accounts, account, authTokenType);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void setAuthToken(Account account, String authTokenType, String authToken) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setAuthToken: " + account
                    + ", authTokenType " + authTokenType
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot set auth tokens associated with accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            saveAuthTokenToDatabase(accounts, account, authTokenType, authToken);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void setPassword(Account account, String password) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setAuthToken: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot set secrets for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            setPasswordInternal(accounts, account, password, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void setPasswordInternal(UserAccounts accounts, Account account, String password,
            int callingUid) {
        if (account == null) {
            return;
        }
        synchronized (accounts.cacheLock) {
            final SQLiteDatabase db = accounts.openHelper.getWritableDatabaseUserIsUnlocked();
            db.beginTransaction();
            try {
                final ContentValues values = new ContentValues();
                values.put(ACCOUNTS_PASSWORD, password);
                final long accountId = getAccountIdLocked(db, account);
                if (accountId >= 0) {
                    final String[] argsAccountId = {String.valueOf(accountId)};
                    db.update(CE_TABLE_ACCOUNTS, values, ACCOUNTS_ID + "=?", argsAccountId);
                    db.delete(CE_TABLE_AUTHTOKENS, AUTHTOKENS_ACCOUNTS_ID + "=?", argsAccountId);
                    accounts.authTokenCache.remove(account);
                    accounts.accountTokenCaches.remove(account);
                    db.setTransactionSuccessful();

                    String action = (password == null || password.length() == 0) ?
                            DebugDbHelper.ACTION_CLEAR_PASSWORD
                            : DebugDbHelper.ACTION_SET_PASSWORD;
                    logRecord(db, action, TABLE_ACCOUNTS, accountId, accounts, callingUid);
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

    @Override
    public void clearPassword(Account account) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "clearPassword: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot clear passwords for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            setPasswordInternal(accounts, account, null, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void setUserData(Account account, String key, String value) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setUserData: " + account
                    + ", key " + key
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (key == null) throw new IllegalArgumentException("key is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format(
                    "uid %s cannot set user data for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.cacheLock) {
                if (!accountExistsCacheLocked(accounts, account)) {
                    return;
                }
                setUserdataInternalLocked(accounts, account, key, value);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean accountExistsCacheLocked(UserAccounts accounts, Account account) {
        if (accounts.accountCache.containsKey(account.type)) {
            for (Account acc : accounts.accountCache.get(account.type)) {
                if (acc.name.equals(account.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setUserdataInternalLocked(UserAccounts accounts, Account account, String key,
            String value) {
        if (account == null || key == null) {
            return;
        }
        final SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long accountId = getAccountIdLocked(db, account);
            if (accountId < 0) {
                return;
            }
            long extrasId = getExtrasIdLocked(db, accountId, key);
            if (extrasId < 0) {
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

    @Override
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
        int userId = UserHandle.getUserId(callingUid);
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new Session(accounts, response, accountType, false /* expectActivityLaunch */,
                    false /* stripAuthTokenFromResult */,  null /* accountName */,
                    false /* authDetailsRequired */) {
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAuthTokenLabel"
                            + ", " + accountType
                            + ", authTokenType " + authTokenType;
                }

                @Override
                public void run() throws RemoteException {
                    mAuthenticator.getAuthTokenLabel(this, authTokenType);
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
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

    @Override
    public void getAuthToken(
            IAccountManagerResponse response,
            final Account account,
            final String authTokenType,
            final boolean notifyOnAuthFailure,
            final boolean expectActivityLaunch,
            final Bundle loginOptions) {
        Bundle.setDefusable(loginOptions, true);
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
        try {
            if (account == null) {
                Slog.w(TAG, "getAuthToken called with null account");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "account is null");
                return;
            }
            if (authTokenType == null) {
                Slog.w(TAG, "getAuthToken called with null authTokenType");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "authTokenType is null");
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report error back to the client." + e);
            return;
        }
        int userId = UserHandle.getCallingUserId();
        long ident = Binder.clearCallingIdentity();
        final UserAccounts accounts;
        final RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo;
        try {
            accounts = getUserAccounts(userId);
            authenticatorInfo = mAuthenticatorCache.getServiceInfo(
                    AuthenticatorDescription.newKey(account.type), accounts.userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        final boolean customTokens =
                authenticatorInfo != null && authenticatorInfo.type.customTokens;

        // skip the check if customTokens
        final int callerUid = Binder.getCallingUid();
        final boolean permissionGranted =
                customTokens || permissionIsGranted(account, authTokenType, callerUid, userId);

        // Get the calling package. We will use it for the purpose of caching.
        final String callerPkg = loginOptions.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        List<String> callerOwnedPackageNames;
        ident = Binder.clearCallingIdentity();
        try {
            callerOwnedPackageNames = Arrays.asList(mPackageManager.getPackagesForUid(callerUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (callerPkg == null || !callerOwnedPackageNames.contains(callerPkg)) {
            String msg = String.format(
                    "Uid %s is attempting to illegally masquerade as package %s!",
                    callerUid,
                    callerPkg);
            throw new SecurityException(msg);
        }

        // let authenticator know the identity of the caller
        loginOptions.putInt(AccountManager.KEY_CALLER_UID, callerUid);
        loginOptions.putInt(AccountManager.KEY_CALLER_PID, Binder.getCallingPid());

        if (notifyOnAuthFailure) {
            loginOptions.putBoolean(AccountManager.KEY_NOTIFY_ON_FAILURE, true);
        }

        long identityToken = clearCallingIdentity();
        try {
            // Distill the caller's package signatures into a single digest.
            final byte[] callerPkgSigDigest = calculatePackageSignatureDigest(callerPkg);

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

            if (customTokens) {
                /*
                 * Look up tokens in the new cache only if the loginOptions don't have parameters
                 * outside of those expected to be injected by the AccountManager, e.g.
                 * ANDORID_PACKAGE_NAME.
                 */
                String token = readCachedTokenInternal(
                        accounts,
                        account,
                        authTokenType,
                        callerPkg,
                        callerPkgSigDigest);
                if (token != null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "getAuthToken: cache hit ofr custom token authenticator.");
                    }
                    Bundle result = new Bundle();
                    result.putString(AccountManager.KEY_AUTHTOKEN, token);
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                    onResult(response, result);
                    return;
                }
            }

            new Session(
                    accounts,
                    response,
                    account.type,
                    expectActivityLaunch,
                    false /* stripAuthTokenFromResult */,
                    account.name,
                    false /* authDetailsRequired */) {
                @Override
                protected String toDebugString(long now) {
                    if (loginOptions != null) loginOptions.keySet();
                    return super.toDebugString(now) + ", getAuthToken"
                            + ", " + account
                            + ", authTokenType " + authTokenType
                            + ", loginOptions " + loginOptions
                            + ", notifyOnAuthFailure " + notifyOnAuthFailure;
                }

                @Override
                public void run() throws RemoteException {
                    // If the caller doesn't have permission then create and return the
                    // "grant permission" intent instead of the "getAuthToken" intent.
                    if (!permissionGranted) {
                        mAuthenticator.getAuthTokenLabel(this, authTokenType);
                    } else {
                        mAuthenticator.getAuthToken(this, account, authTokenType, loginOptions);
                    }
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    if (result != null) {
                        if (result.containsKey(AccountManager.KEY_AUTH_TOKEN_LABEL)) {
                            Intent intent = newGrantCredentialsPermissionIntent(
                                    account,
                                    callerUid,
                                    new AccountAuthenticatorResponse(this),
                                    authTokenType);
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
                            Account resultAccount = new Account(name, type);
                            if (!customTokens) {
                                saveAuthTokenToDatabase(
                                        mAccounts,
                                        resultAccount,
                                        authTokenType,
                                        authToken);
                            }
                            long expiryMillis = result.getLong(
                                    AbstractAccountAuthenticator.KEY_CUSTOM_TOKEN_EXPIRY, 0L);
                            if (customTokens
                                    && expiryMillis > System.currentTimeMillis()) {
                                saveCachedToken(
                                        mAccounts,
                                        account,
                                        callerPkg,
                                        callerPkgSigDigest,
                                        authTokenType,
                                        authToken,
                                        expiryMillis);
                            }
                        }

                        Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                        if (intent != null && notifyOnAuthFailure && !customTokens) {
                            /*
                             * Make sure that the supplied intent is owned by the authenticator
                             * giving it to the system. Otherwise a malicious authenticator could
                             * have users launching arbitrary activities by tricking users to
                             * interact with malicious notifications.
                             */
                            checkKeyIntent(
                                    Binder.getCallingUid(),
                                    intent);
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

    private byte[] calculatePackageSignatureDigest(String callerPkg) {
        MessageDigest digester;
        try {
            digester = MessageDigest.getInstance("SHA-256");
            PackageInfo pkgInfo = mPackageManager.getPackageInfo(
                    callerPkg, PackageManager.GET_SIGNATURES);
            for (Signature sig : pkgInfo.signatures) {
                digester.update(sig.toByteArray());
            }
        } catch (NoSuchAlgorithmException x) {
            Log.wtf(TAG, "SHA-256 should be available", x);
            digester = null;
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Could not find packageinfo for: " + callerPkg);
            digester = null;
        }
        return (digester == null) ? null : digester.digest();
    }

    private void createNoCredentialsPermissionNotification(Account account, Intent intent,
            int userId) {
        int uid = intent.getIntExtra(
                GrantCredentialsPermissionActivity.EXTRAS_REQUESTING_UID, -1);
        String authTokenType = intent.getStringExtra(
                GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE);
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
        Context contextForUser = getContextForUser(user);
        Notification n = new Notification.Builder(contextForUser)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setWhen(0)
                .setColor(contextForUser.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(PendingIntent.getActivityAsUser(mContext, 0, intent,
                        PendingIntent.FLAG_CANCEL_CURRENT, null, user))
                .build();
        installNotification(getCredentialPermissionNotificationId(
                account, authTokenType, uid), n, user);
    }

    private Intent newGrantCredentialsPermissionIntent(Account account, int uid,
            AccountAuthenticatorResponse response, String authTokenType) {

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

    @Override
    public void addAccount(final IAccountManagerResponse response, final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final boolean expectActivityLaunch, final Bundle optionsIn) {
        Bundle.setDefusable(optionsIn, true);
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

        // Is user disallowed from modifying accounts?
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        if (!canUserModifyAccounts(userId, uid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_USER_RESTRICTED,
                        "User is not allowed to add an account!");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_USER_RESTRICTED, userId);
            return;
        }
        if (!canUserModifyAccountsForType(userId, accountType, uid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                        "User cannot modify accounts of this type (policy).");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                    userId);
            return;
        }

        final int pid = Binder.getCallingPid();
        final Bundle options = (optionsIn == null) ? new Bundle() : optionsIn;
        options.putInt(AccountManager.KEY_CALLER_UID, uid);
        options.putInt(AccountManager.KEY_CALLER_PID, pid);

        int usrId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(usrId);
            logRecordWithUid(
                    accounts, DebugDbHelper.ACTION_CALLED_ACCOUNT_ADD, TABLE_ACCOUNTS, uid);
            new Session(accounts, response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */, true /* updateLastAuthenticationTime */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                            options);
                }

                @Override
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
    public void addAccountAsUser(final IAccountManagerResponse response, final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final boolean expectActivityLaunch, final Bundle optionsIn, int userId) {
        Bundle.setDefusable(optionsIn, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addAccount: accountType " + accountType
                    + ", response " + response
                    + ", authTokenType " + authTokenType
                    + ", requiredFeatures " + stringArrayToString(requiredFeatures)
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid()
                    + ", for user id " + userId);
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        // Only allow the system process to add accounts of other users
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s trying to add account for %s" ,
                            UserHandle.getCallingUserId(),
                            userId));
        }

        // Is user disallowed from modifying accounts?
        if (!canUserModifyAccounts(userId, callingUid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_USER_RESTRICTED,
                        "User is not allowed to add an account!");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_USER_RESTRICTED, userId);
            return;
        }
        if (!canUserModifyAccountsForType(userId, accountType, callingUid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                        "User cannot modify accounts of this type (policy).");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                    userId);
            return;
        }

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final Bundle options = (optionsIn == null) ? new Bundle() : optionsIn;
        options.putInt(AccountManager.KEY_CALLER_UID, uid);
        options.putInt(AccountManager.KEY_CALLER_PID, pid);

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            logRecordWithUid(
                    accounts, DebugDbHelper.ACTION_CALLED_ACCOUNT_ADD, TABLE_ACCOUNTS, userId);
            new Session(accounts, response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */, true /* updateLastAuthenticationTime */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                            options);
                }

                @Override
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
    public void startAddAccountSession(
            final IAccountManagerResponse response,
            final String accountType,
            final String authTokenType,
            final String[] requiredFeatures,
            final boolean expectActivityLaunch,
            final Bundle optionsIn) {
        Bundle.setDefusable(optionsIn, true);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG,
                    "startAddAccountSession: accountType " + accountType
                    + ", response " + response
                    + ", authTokenType " + authTokenType
                    + ", requiredFeatures " + stringArrayToString(requiredFeatures)
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is null");
        }

        final int uid = Binder.getCallingUid();
        // Only allow system to start session
        if (!isSystemUid(uid)) {
            String msg = String.format(
                    "uid %s cannot stat add account session.",
                    uid);
            throw new SecurityException(msg);
        }

        final int userId = UserHandle.getUserId(uid);
        if (!canUserModifyAccounts(userId, uid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_USER_RESTRICTED,
                        "User is not allowed to add an account!");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_USER_RESTRICTED, userId);
            return;
        }
        if (!canUserModifyAccountsForType(userId, accountType, uid)) {
            try {
                response.onError(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                        "User cannot modify accounts of this type (policy).");
            } catch (RemoteException re) {
            }
            showCantAddAccount(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                    userId);
            return;
        }
        final int pid = Binder.getCallingPid();
        final Bundle options = (optionsIn == null) ? new Bundle() : optionsIn;
        options.putInt(AccountManager.KEY_CALLER_UID, uid);
        options.putInt(AccountManager.KEY_CALLER_PID, pid);

        // Check to see if the Password should be included to the caller.
        String callerPkg = optionsIn.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        boolean isPasswordForwardingAllowed = isPermitted(
                callerPkg, uid, Manifest.permission.GET_PASSWORD);

        int usrId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(usrId);
            logRecordWithUid(accounts, DebugDbHelper.ACTION_CALLED_START_ACCOUNT_ADD,
                    TABLE_ACCOUNTS, uid);
            new StartAccountSession(
                    accounts,
                    response,
                    accountType,
                    expectActivityLaunch,
                    null /* accountName */,
                    false /* authDetailsRequired */,
                    true /* updateLastAuthenticationTime */,
                    isPasswordForwardingAllowed) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.startAddAccountSession(this, mAccountType, authTokenType,
                            requiredFeatures, options);
                }

                @Override
                protected String toDebugString(long now) {
                    String requiredFeaturesStr = TextUtils.join(",", requiredFeatures);
                    return super.toDebugString(now) + ", startAddAccountSession" + ", accountType "
                            + accountType + ", requiredFeatures "
                            + (requiredFeatures != null ? requiredFeaturesStr : null);
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /** Session that will encrypt the KEY_ACCOUNT_SESSION_BUNDLE in result. */
    private abstract class StartAccountSession extends Session {

        private final boolean mIsPasswordForwardingAllowed;

        public StartAccountSession(
                UserAccounts accounts,
                IAccountManagerResponse response,
                String accountType,
                boolean expectActivityLaunch,
                String accountName,
                boolean authDetailsRequired,
                boolean updateLastAuthenticationTime,
                boolean isPasswordForwardingAllowed) {
            super(accounts, response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, accountName, authDetailsRequired,
                    updateLastAuthenticationTime);
            mIsPasswordForwardingAllowed = isPasswordForwardingAllowed;
        }

        @Override
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            mNumResults++;
            Intent intent = null;
            if (result != null
                    && (intent = result.getParcelable(AccountManager.KEY_INTENT)) != null) {
                checkKeyIntent(
                        Binder.getCallingUid(),
                        intent);
                // Omit passwords if the caller isn't permitted to see them.
                if (!mIsPasswordForwardingAllowed) {
                    result.remove(AccountManager.KEY_PASSWORD);
                }
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(AccountManager.KEY_INTENT)) {
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response == null) {
                return;
            }
            if (result == null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, getClass().getSimpleName() + " calling onError() on response "
                            + response);
                }
                sendErrorResponse(response, AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "null bundle returned");
                return;
            }

            if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) && (intent == null)) {
                // All AccountManager error codes are greater
                // than 0
                sendErrorResponse(response, result.getInt(AccountManager.KEY_ERROR_CODE),
                        result.getString(AccountManager.KEY_ERROR_MESSAGE));
                return;
            }

            // Strip auth token from result.
            result.remove(AccountManager.KEY_AUTHTOKEN);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG,
                        getClass().getSimpleName() + " calling onResult() on response " + response);
            }

            // Get the session bundle created by authenticator. The
            // bundle contains data necessary for finishing the session
            // later. The session bundle will be encrypted here and
            // decrypted later when trying to finish the session.
            Bundle sessionBundle = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
            if (sessionBundle != null) {
                String accountType = sessionBundle.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (TextUtils.isEmpty(accountType)
                        || !mAccountType.equalsIgnoreCase(accountType)) {
                    Log.w(TAG, "Account type in session bundle doesn't match request.");
                }
                // Add accountType info to session bundle. This will
                // override any value set by authenticator.
                sessionBundle.putString(AccountManager.KEY_ACCOUNT_TYPE, mAccountType);

                // Encrypt session bundle before returning to caller.
                try {
                    CryptoHelper cryptoHelper = CryptoHelper.getInstance();
                    Bundle encryptedBundle = cryptoHelper.encryptBundle(sessionBundle);
                    result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, encryptedBundle);
                } catch (GeneralSecurityException e) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.v(TAG, "Failed to encrypt session bundle!", e);
                    }
                    sendErrorResponse(response, AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "failed to encrypt session bundle");
                    return;
                }
            }

            sendResponse(response, result);
        }
    }

    @Override
    public void finishSessionAsUser(IAccountManagerResponse response,
            @NonNull Bundle sessionBundle,
            boolean expectActivityLaunch,
            Bundle appInfo,
            int userId) {
        Bundle.setDefusable(sessionBundle, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG,
                    "finishSession: response "+ response
                            + ", expectActivityLaunch " + expectActivityLaunch
                            + ", caller's uid " + callingUid
                            + ", caller's user id " + UserHandle.getCallingUserId()
                            + ", pid " + Binder.getCallingPid()
                            + ", for user id " + userId);
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }

        // Session bundle is the encrypted bundle of the original bundle created by authenticator.
        // Account type is added to it before encryption.
        if (sessionBundle == null || sessionBundle.size() == 0) {
            throw new IllegalArgumentException("sessionBundle is empty");
        }

        // Only allow the system process to finish session for other users
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s trying to finish session for %s without cross user permission",
                            UserHandle.getCallingUserId(),
                            userId));
        }

        // Only allow system to finish session
        if (!isSystemUid(callingUid)) {
            String msg = String.format(
                    "uid %s cannot finish session because it's not system uid.",
                    callingUid);
            throw new SecurityException(msg);
        }

        if (!canUserModifyAccounts(userId, callingUid)) {
            sendErrorResponse(response,
                    AccountManager.ERROR_CODE_USER_RESTRICTED,
                    "User is not allowed to add an account!");
            showCantAddAccount(AccountManager.ERROR_CODE_USER_RESTRICTED, userId);
            return;
        }

        final int pid = Binder.getCallingPid();
        final Bundle decryptedBundle;
        final String accountType;
        // First decrypt session bundle to get account type for checking permission.
        try {
            CryptoHelper cryptoHelper = CryptoHelper.getInstance();
            decryptedBundle = cryptoHelper.decryptBundle(sessionBundle);
            if (decryptedBundle == null) {
                sendErrorResponse(
                        response,
                        AccountManager.ERROR_CODE_BAD_REQUEST,
                        "failed to decrypt session bundle");
                return;
            }
            accountType = decryptedBundle.getString(AccountManager.KEY_ACCOUNT_TYPE);
            // Account type cannot be null. This should not happen if session bundle was created
            // properly by #StartAccountSession.
            if (TextUtils.isEmpty(accountType)) {
                sendErrorResponse(
                        response,
                        AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                        "accountType is empty");
                return;
            }

            // If by any chances, decryptedBundle contains colliding keys with
            // system info
            // such as AccountManager.KEY_ANDROID_PACKAGE_NAME required by the add account flow or
            // update credentials flow, we should replace with the new values of the current call.
            if (appInfo != null) {
                decryptedBundle.putAll(appInfo);
            }

            // Add info that may be used by add account or update credentials flow.
            decryptedBundle.putInt(AccountManager.KEY_CALLER_UID, callingUid);
            decryptedBundle.putInt(AccountManager.KEY_CALLER_PID, pid);
        } catch (GeneralSecurityException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.v(TAG, "Failed to decrypt session bundle!", e);
            }
            sendErrorResponse(
                    response,
                    AccountManager.ERROR_CODE_BAD_REQUEST,
                    "failed to decrypt session bundle");
            return;
        }

        if (!canUserModifyAccountsForType(userId, accountType, callingUid)) {
            sendErrorResponse(
                    response,
                    AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                    "User cannot modify accounts of this type (policy).");
            showCantAddAccount(AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE,
                    userId);
            return;
        }

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            logRecordWithUid(
                    accounts,
                    DebugDbHelper.ACTION_CALLED_ACCOUNT_SESSION_FINISH,
                    TABLE_ACCOUNTS,
                    callingUid);
            new Session(
                    accounts,
                    response,
                    accountType,
                    expectActivityLaunch,
                    true /* stripAuthTokenFromResult */,
                    null /* accountName */,
                    false /* authDetailsRequired */,
                    true /* updateLastAuthenticationTime */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.finishSession(this, mAccountType, decryptedBundle);
                }

                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now)
                            + ", finishSession"
                            + ", accountType " + accountType;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void showCantAddAccount(int errorCode, int userId) {
        Intent cantAddAccount = new Intent(mContext, CantAddAccountActivity.class);
        cantAddAccount.putExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, errorCode);
        cantAddAccount.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        long identityToken = clearCallingIdentity();
        try {
            mContext.startActivityAsUser(cantAddAccount, new UserHandle(userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void confirmCredentialsAsUser(
            IAccountManagerResponse response,
            final Account account,
            final Bundle options,
            final boolean expectActivityLaunch,
            int userId) {
        Bundle.setDefusable(options, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "confirmCredentials: " + account
                    + ", response " + response
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        // Only allow the system process to read accounts of other users
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s trying to confirm account credentials for %s" ,
                            UserHandle.getCallingUserId(),
                            userId));
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new Session(accounts, response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, account.name,
                    true /* authDetailsRequired */, true /* updateLastAuthenticatedTime */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.confirmCredentials(this, account, options);
                }
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", confirmCredentials"
                            + ", " + account;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void updateCredentials(IAccountManagerResponse response, final Account account,
            final String authTokenType, final boolean expectActivityLaunch,
            final Bundle loginOptions) {
        Bundle.setDefusable(loginOptions, true);
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
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new Session(accounts, response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */, true /* updateLastCredentialTime */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.updateCredentials(this, account, authTokenType, loginOptions);
                }
                @Override
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

    @Override
    public void startUpdateCredentialsSession(
            IAccountManagerResponse response,
            final Account account,
            final String authTokenType,
            final boolean expectActivityLaunch,
            final Bundle loginOptions) {
        Bundle.setDefusable(loginOptions, true);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG,
                    "startUpdateCredentialsSession: " + account + ", response " + response
                            + ", authTokenType " + authTokenType + ", expectActivityLaunch "
                            + expectActivityLaunch + ", caller's uid " + Binder.getCallingUid()
                            + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }

        final int uid = Binder.getCallingUid();
        // Only allow system to start session
        if (!isSystemUid(uid)) {
            String msg = String.format(
                    "uid %s cannot start update credentials session.",
                    uid);
            throw new SecurityException(msg);
        }

        int userId = UserHandle.getCallingUserId();

        // Check to see if the Password should be included to the caller.
        String callerPkg = loginOptions.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        boolean isPasswordForwardingAllowed = isPermitted(
                callerPkg, uid, Manifest.permission.GET_PASSWORD);

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new StartAccountSession(
                    accounts,
                    response,
                    account.type,
                    expectActivityLaunch,
                    account.name,
                    false /* authDetailsRequired */,
                    true /* updateLastCredentialTime */,
                    isPasswordForwardingAllowed) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.startUpdateCredentialsSession(this, account, authTokenType,
                            loginOptions);
                }

                @Override
                protected String toDebugString(long now) {
                    if (loginOptions != null)
                        loginOptions.keySet();
                    return super.toDebugString(now)
                            + ", startUpdateCredentialsSession"
                            + ", " + account
                            + ", authTokenType " + authTokenType
                            + ", loginOptions " + loginOptions;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void isCredentialsUpdateSuggested(
            IAccountManagerResponse response,
            final Account account,
            final String statusToken) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG,
                    "isCredentialsUpdateSuggested: " + account + ", response " + response
                            + ", caller's uid " + Binder.getCallingUid()
                            + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        if (TextUtils.isEmpty(statusToken)) {
            throw new IllegalArgumentException("status token is empty");
        }

        int uid = Binder.getCallingUid();
        // Only allow system to start session
        if (!isSystemUid(uid)) {
            String msg = String.format(
                    "uid %s cannot stat add account session.",
                    uid);
            throw new SecurityException(msg);
        }

        int usrId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(usrId);
            new Session(accounts, response, account.type, false /* expectActivityLaunch */,
                    false /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */) {
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", isCredentialsUpdateSuggested"
                            + ", " + account;
                }

                @Override
                public void run() throws RemoteException {
                    mAuthenticator.isCredentialsUpdateSuggested(this, account, statusToken);
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    IAccountManagerResponse response = getResponseAndClose();
                    if (response == null) {
                        return;
                    }

                    if (result == null) {
                        sendErrorResponse(
                                response,
                                AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle");
                        return;
                    }

                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
                    // Check to see if an error occurred. We know if an error occurred because all
                    // error codes are greater than 0.
                    if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0)) {
                        sendErrorResponse(response,
                                result.getInt(AccountManager.KEY_ERROR_CODE),
                                result.getString(AccountManager.KEY_ERROR_MESSAGE));
                        return;
                    }
                    if (!result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)) {
                        sendErrorResponse(
                                response,
                                AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "no result in response");
                        return;
                    }
                    final Bundle newResult = new Bundle();
                    newResult.putBoolean(AccountManager.KEY_BOOLEAN_RESULT,
                            result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false));
                    sendResponse(response, newResult);
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void editProperties(IAccountManagerResponse response, final String accountType,
            final boolean expectActivityLaunch) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "editProperties: accountType " + accountType
                    + ", response " + response
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(accountType, callingUid, userId) && !isSystemUid(callingUid)) {
            String msg = String.format(
                    "uid %s cannot edit authenticator properites for account type: %s",
                    callingUid,
                    accountType);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new Session(accounts, response, accountType, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */) {
                @Override
                public void run() throws RemoteException {
                    mAuthenticator.editProperties(this, mAccountType);
                }
                @Override
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", editProperties"
                            + ", accountType " + accountType;
                }
            }.bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean someUserHasAccount(@NonNull final Account account) {
        if (!UserHandle.isSameApp(Process.SYSTEM_UID, Binder.getCallingUid())) {
            throw new SecurityException("Only system can check for accounts across users");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            AccountAndUser[] allAccounts = getAllAccounts();
            for (int i = allAccounts.length - 1; i >= 0; i--) {
                if (allAccounts[i].account.equals(account)) {
                    return true;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private class GetAccountsByTypeAndFeatureSession extends Session {
        private final String[] mFeatures;
        private volatile Account[] mAccountsOfType = null;
        private volatile ArrayList<Account> mAccountsWithFeatures = null;
        private volatile int mCurrentAccount = 0;
        private final int mCallingUid;

        public GetAccountsByTypeAndFeatureSession(UserAccounts accounts,
                IAccountManagerResponse response, String type, String[] features, int callingUid) {
            super(accounts, response, type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */);
            mCallingUid = callingUid;
            mFeatures = features;
        }

        @Override
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

        @Override
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
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


        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", getAccountsByTypeAndFeatures"
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    /**
     * Returns the accounts visible to the client within the context of a specific user
     * @hide
     */
    @NonNull
    public Account[] getAccounts(int userId, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId,
                opPackageName);
        if (visibleAccountTypes.isEmpty()) {
            return new Account[0];
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsInternal(
                    accounts,
                    callingUid,
                    null,  // packageName
                    visibleAccountTypes);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Returns accounts for all running users.
     *
     * @hide
     */
    @NonNull
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
    @NonNull
    public AccountAndUser[] getAllAccounts() {
        final List<UserInfo> users = getUserManager().getUsers(true);
        final int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users.get(i).id;
        }
        return getAccounts(userIds);
    }

    @NonNull
    private AccountAndUser[] getAccounts(int[] userIds) {
        final ArrayList<AccountAndUser> runningAccounts = Lists.newArrayList();
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

        AccountAndUser[] accountsArray = new AccountAndUser[runningAccounts.size()];
        return runningAccounts.toArray(accountsArray);
    }

    @Override
    @NonNull
    public Account[] getAccountsAsUser(String type, int userId, String opPackageName) {
        return getAccountsAsUser(type, userId, null, -1, opPackageName);
    }

    @NonNull
    private Account[] getAccountsAsUser(
            String type,
            int userId,
            String callingPackage,
            int packageUid,
            String opPackageName) {
        int callingUid = Binder.getCallingUid();
        // Only allow the system process to read accounts of other users
        if (userId != UserHandle.getCallingUserId()
                && callingUid != Process.myUid()
                && mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                    != PackageManager.PERMISSION_GRANTED) {
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
            opPackageName = callingPackage;
        }

        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId,
                opPackageName);
        if (visibleAccountTypes.isEmpty()
                || (type != null && !visibleAccountTypes.contains(type))) {
            return new Account[0];
        } else if (visibleAccountTypes.contains(type)) {
            // Prune the list down to just the requested type.
            visibleAccountTypes = new ArrayList<>();
            visibleAccountTypes.add(type);
        } // else aggregate all the visible accounts (it won't matter if the
          // list is empty).

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsInternal(
                    accounts,
                    callingUid,
                    callingPackage,
                    visibleAccountTypes);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @NonNull
    private Account[] getAccountsInternal(
            UserAccounts userAccounts,
            int callingUid,
            String callingPackage,
            List<String> visibleAccountTypes) {
        synchronized (userAccounts.cacheLock) {
            ArrayList<Account> visibleAccounts = new ArrayList<>();
            for (String visibleType : visibleAccountTypes) {
                Account[] accountsForType = getAccountsFromCacheLocked(
                        userAccounts, visibleType, callingUid, callingPackage);
                if (accountsForType != null) {
                    visibleAccounts.addAll(Arrays.asList(accountsForType));
                }
            }
            Account[] result = new Account[visibleAccounts.size()];
            for (int i = 0; i < visibleAccounts.size(); i++) {
                result[i] = visibleAccounts.get(i);
            }
            return result;
        }
    }

    @Override
    public void addSharedAccountsFromParentUser(int parentUserId, int userId) {
        checkManageOrCreateUsersPermission("addSharedAccountsFromParentUser");
        Account[] accounts = getAccountsAsUser(null, parentUserId, mContext.getOpPackageName());
        for (Account account : accounts) {
            addSharedAccountAsUser(account, userId);
        }
    }

    private boolean addSharedAccountAsUser(Account account, int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
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
        logRecord(db, DebugDbHelper.ACTION_ACCOUNT_ADD, TABLE_SHARED_ACCOUNTS, accountId, accounts);
        return true;
    }

    @Override
    public boolean renameSharedAccountAsUser(Account account, String newName, int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        long sharedTableAccountId = getAccountIdFromSharedTable(db, account);
        final ContentValues values = new ContentValues();
        values.put(ACCOUNTS_NAME, newName);
        int r = db.update(
                TABLE_SHARED_ACCOUNTS,
                values,
                ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[] { account.name, account.type });
        if (r > 0) {
            int callingUid = getCallingUid();
            logRecord(db, DebugDbHelper.ACTION_ACCOUNT_RENAME, TABLE_SHARED_ACCOUNTS,
                    sharedTableAccountId, accounts, callingUid);
            // Recursively rename the account.
            renameAccountInternal(accounts, account, newName);
        }
        return r > 0;
    }

    @Override
    public boolean removeSharedAccountAsUser(Account account, int userId) {
        return removeSharedAccountAsUser(account, userId, getCallingUid());
    }

    private boolean removeSharedAccountAsUser(Account account, int userId, int callingUid) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        long sharedTableAccountId = getAccountIdFromSharedTable(db, account);
        int r = db.delete(TABLE_SHARED_ACCOUNTS, ACCOUNTS_NAME + "=? AND " + ACCOUNTS_TYPE+ "=?",
                new String[] {account.name, account.type});
        if (r > 0) {
            logRecord(db, DebugDbHelper.ACTION_ACCOUNT_REMOVE, TABLE_SHARED_ACCOUNTS,
                    sharedTableAccountId, accounts, callingUid);
            removeAccountInternal(accounts, account, callingUid);
        }
        return r > 0;
    }

    @Override
    public Account[] getSharedAccountsAsUser(int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        ArrayList<Account> accountList = new ArrayList<>();
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
    @NonNull
    public Account[] getAccounts(String type, String opPackageName) {
        return getAccountsAsUser(type, UserHandle.getCallingUserId(), opPackageName);
    }

    @Override
    @NonNull
    public Account[] getAccountsForPackage(String packageName, int uid, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(callingUid, Process.myUid())) {
            throw new SecurityException("getAccountsForPackage() called from unauthorized uid "
                    + callingUid + " with uid=" + uid);
        }
        return getAccountsAsUser(null, UserHandle.getCallingUserId(), packageName, uid,
                opPackageName);
    }

    @Override
    @NonNull
    public Account[] getAccountsByTypeForPackage(String type, String packageName,
            String opPackageName) {
        int packageUid = -1;
        try {
            packageUid = AppGlobals.getPackageManager().getPackageUid(
                    packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES,
                    UserHandle.getCallingUserId());
        } catch (RemoteException re) {
            Slog.e(TAG, "Couldn't determine the packageUid for " + packageName + re);
            return new Account[0];
        }
        return getAccountsAsUser(type, UserHandle.getCallingUserId(), packageName,
                packageUid, opPackageName);
    }

    @Override
    public void getAccountsByFeatures(
            IAccountManagerResponse response,
            String type,
            String[] features,
            String opPackageName) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAccounts: accountType " + type
                    + ", response " + response
                    + ", features " + stringArrayToString(features)
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");
        int userId = UserHandle.getCallingUserId();

        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId,
                opPackageName);
        if (!visibleAccountTypes.contains(type)) {
            Bundle result = new Bundle();
            // Need to return just the accounts that are from matching signatures.
            result.putParcelableArray(AccountManager.KEY_ACCOUNTS, new Account[0]);
            try {
                response.onResult(result);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot respond to caller do to exception." , e);
            }
            return;
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts userAccounts = getUserAccounts(userId);
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
            new GetAccountsByTypeAndFeatureSession(
                    userAccounts,
                    response,
                    type,
                    features,
                    callingUid).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private long getAccountIdFromSharedTable(SQLiteDatabase db, Account account) {
        Cursor cursor = db.query(TABLE_SHARED_ACCOUNTS, new String[]{ACCOUNTS_ID},
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
        Cursor cursor = db.query(CE_TABLE_EXTRAS, new String[]{EXTRAS_ID},
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
        final String mAccountName;
        // Indicates if we need to add auth details(like last credential time)
        final boolean mAuthDetailsRequired;
        // If set, we need to update the last authenticated time. This is
        // currently
        // used on
        // successful confirming credentials.
        final boolean mUpdateLastAuthenticatedTime;

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;

        IAccountAuthenticator mAuthenticator = null;

        private final boolean mStripAuthTokenFromResult;
        protected final UserAccounts mAccounts;

        public Session(UserAccounts accounts, IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                boolean authDetailsRequired) {
            this(accounts, response, accountType, expectActivityLaunch, stripAuthTokenFromResult,
                    accountName, authDetailsRequired, false /* updateLastAuthenticatedTime */);
        }

        public Session(UserAccounts accounts, IAccountManagerResponse response, String accountType,
                boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                boolean authDetailsRequired, boolean updateLastAuthenticatedTime) {
            super();
            //if (response == null) throw new IllegalArgumentException("response is null");
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mAccounts = accounts;
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            mAccountName = accountName;
            mAuthDetailsRequired = authDetailsRequired;
            mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime;

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

        /**
         * Checks Intents, supplied via KEY_INTENT, to make sure that they don't violate our
         * security policy.
         *
         * In particular we want to make sure that the Authenticator doesn't try to trick users
         * into launching aribtrary intents on the device via by tricking to click authenticator
         * supplied entries in the system Settings app.
         */
        protected void checkKeyIntent(
                int authUid,
                Intent intent) throws SecurityException {
            long bid = Binder.clearCallingIdentity();
            try {
                PackageManager pm = mContext.getPackageManager();
                ResolveInfo resolveInfo = pm.resolveActivityAsUser(intent, 0, mAccounts.userId);
                ActivityInfo targetActivityInfo = resolveInfo.activityInfo;
                int targetUid = targetActivityInfo.applicationInfo.uid;
                if (PackageManager.SIGNATURE_MATCH != pm.checkSignatures(authUid, targetUid)) {
                    String pkgName = targetActivityInfo.packageName;
                    String activityName = targetActivityInfo.name;
                    String tmpl = "KEY_INTENT resolved to an Activity (%s) in a package (%s) that "
                            + "does not share a signature with the supplying authenticator (%s).";
                    throw new SecurityException(
                            String.format(tmpl, activityName, pkgName, mAccountType));
                }
            } finally {
                Binder.restoreCallingIdentity(bid);
            }
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

        @Override
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

        public void cancelTimeout() {
            mMessageHandler.removeMessages(MESSAGE_TIMED_OUT, this);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);
            try {
                run();
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "remote exception");
            }
        }

        @Override
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

        @Override
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            mNumResults++;
            Intent intent = null;
            if (result != null) {
                boolean isSuccessfulConfirmCreds = result.getBoolean(
                        AccountManager.KEY_BOOLEAN_RESULT, false);
                boolean isSuccessfulUpdateCredsOrAddAccount =
                        result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                        && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE);
                // We should only update lastAuthenticated time, if
                // mUpdateLastAuthenticatedTime is true and the confirmRequest
                // or updateRequest was successful
                boolean needUpdate = mUpdateLastAuthenticatedTime
                        && (isSuccessfulConfirmCreds || isSuccessfulUpdateCredsOrAddAccount);
                if (needUpdate || mAuthDetailsRequired) {
                    boolean accountPresent = isAccountPresentForCaller(mAccountName, mAccountType);
                    if (needUpdate && accountPresent) {
                        updateLastAuthenticatedTime(new Account(mAccountName, mAccountType));
                    }
                    if (mAuthDetailsRequired) {
                        long lastAuthenticatedTime = -1;
                        if (accountPresent) {
                            lastAuthenticatedTime = DatabaseUtils.longForQuery(
                                    mAccounts.openHelper.getReadableDatabase(),
                                    "SELECT " + ACCOUNTS_LAST_AUTHENTICATE_TIME_EPOCH_MILLIS
                                            + " FROM " +
                                            TABLE_ACCOUNTS + " WHERE " + ACCOUNTS_NAME + "=? AND "
                                            + ACCOUNTS_TYPE + "=?",
                                    new String[] {
                                            mAccountName, mAccountType
                                    });
                        }
                        result.putLong(AccountManager.KEY_LAST_AUTHENTICATED_TIME,
                                lastAuthenticatedTime);
                    }
                }
            }
            if (result != null
                    && (intent = result.getParcelable(AccountManager.KEY_INTENT)) != null) {
                checkKeyIntent(
                        Binder.getCallingUid(),
                        intent);
            }
            if (result != null
                    && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
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
                        if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) &&
                                (intent == null)) {
                            // All AccountManager error codes are greater than 0
                            response.onError(result.getInt(AccountManager.KEY_ERROR_CODE),
                                    result.getString(AccountManager.KEY_ERROR_MESSAGE));
                        } else {
                            response.onResult(result);
                        }
                    }
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override
        public void onRequestContinued() {
            mNumRequestContinued++;
        }

        @Override
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

            if (!isLocalUnlockedUser(mAccounts.userId)
                    && !authenticatorInfo.componentInfo.directBootAware) {
                Slog.w(TAG, "Blocking binding to authenticator " + authenticatorInfo.componentName
                        + " which isn't encryption aware");
                return false;
            }

            Intent intent = new Intent();
            intent.setAction(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            intent.setComponent(authenticatorInfo.componentName);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "performing bindService to " + authenticatorInfo.componentName);
            }
            if (!mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                    UserHandle.of(mAccounts.userId))) {
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

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_TIMED_OUT:
                    Session session = (Session)msg.obj;
                    session.onTimedOut();
                    break;

                case MESSAGE_COPY_SHARED_ACCOUNT:
                    copyAccountToUser(/*no response*/ null, (Account) msg.obj, msg.arg1, msg.arg2);
                    break;

                default:
                    throw new IllegalStateException("unhandled message: " + msg.what);
            }
        }
    }

    @VisibleForTesting
    String getPreNDatabaseName(int userId) {
        File systemDir = Environment.getDataSystemDirectory();
        File databaseFile = new File(Environment.getUserSystemDirectory(userId),
                PRE_N_DATABASE_NAME);
        if (userId == 0) {
            // Migrate old file, if it exists, to the new location.
            // Make sure the new file doesn't already exist. A dummy file could have been
            // accidentally created in the old location, causing the new one to become corrupted
            // as well.
            File oldFile = new File(systemDir, PRE_N_DATABASE_NAME);
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

    @VisibleForTesting
    String getDeDatabaseName(int userId) {
        File databaseFile = new File(Environment.getDataSystemDeDirectory(userId),
                DE_DATABASE_NAME);
        return databaseFile.getPath();
    }

    @VisibleForTesting
    String getCeDatabaseName(int userId) {
        File databaseFile = new File(Environment.getDataSystemCeDirectory(userId),
                CE_DATABASE_NAME);
        return databaseFile.getPath();
    }

    private static class DebugDbHelper{
        private DebugDbHelper() {
        }

        private static String TABLE_DEBUG = "debug_table";

        // Columns for the table
        private static String ACTION_TYPE = "action_type";
        private static String TIMESTAMP = "time";
        private static String CALLER_UID = "caller_uid";
        private static String TABLE_NAME = "table_name";
        private static String KEY = "primary_key";

        // These actions correspond to the occurrence of real actions. Since
        // these are called by the authenticators, the uid associated will be
        // of the authenticator.
        private static String ACTION_SET_PASSWORD = "action_set_password";
        private static String ACTION_CLEAR_PASSWORD = "action_clear_password";
        private static String ACTION_ACCOUNT_ADD = "action_account_add";
        private static String ACTION_ACCOUNT_REMOVE = "action_account_remove";
        private static String ACTION_ACCOUNT_REMOVE_DE = "action_account_remove_de";
        private static String ACTION_AUTHENTICATOR_REMOVE = "action_authenticator_remove";
        private static String ACTION_ACCOUNT_RENAME = "action_account_rename";

        // These actions don't necessarily correspond to any action on
        // accountDb taking place. As an example, there might be a request for
        // addingAccount, which might not lead to addition of account on grounds
        // of bad authentication. We will still be logging it to keep track of
        // who called.
        private static String ACTION_CALLED_ACCOUNT_ADD = "action_called_account_add";
        private static String ACTION_CALLED_ACCOUNT_REMOVE = "action_called_account_remove";
        private static String ACTION_SYNC_DE_CE_ACCOUNTS = "action_sync_de_ce_accounts";

        //This action doesn't add account to accountdb. Account is only
        // added in finishSession which may be in a different user profile.
        private static String ACTION_CALLED_START_ACCOUNT_ADD = "action_called_start_account_add";
        private static String ACTION_CALLED_ACCOUNT_SESSION_FINISH =
                "action_called_account_session_finish";

        private static SimpleDateFormat dateFromat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        private static void createDebugTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_DEBUG + " ( "
                    + ACCOUNTS_ID + " INTEGER,"
                    + ACTION_TYPE + " TEXT NOT NULL, "
                    + TIMESTAMP + " DATETIME,"
                    + CALLER_UID + " INTEGER NOT NULL,"
                    + TABLE_NAME + " TEXT NOT NULL,"
                    + KEY + " INTEGER PRIMARY KEY)");
            db.execSQL("CREATE INDEX timestamp_index ON " + TABLE_DEBUG + " (" + TIMESTAMP + ")");
        }
    }

    private void logRecord(UserAccounts accounts, String action, String tableName) {
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        logRecord(db, action, tableName, -1, accounts);
    }

    private void logRecordWithUid(UserAccounts accounts, String action, String tableName, int uid) {
        SQLiteDatabase db = accounts.openHelper.getWritableDatabase();
        logRecord(db, action, tableName, -1, accounts, uid);
    }

    /*
     * This function receives an opened writable database.
     */
    private void logRecord(SQLiteDatabase db, String action, String tableName, long accountId,
            UserAccounts userAccount) {
        logRecord(db, action, tableName, accountId, userAccount, getCallingUid());
    }

    /*
     * This function receives an opened writable database.
     */
    private void logRecord(SQLiteDatabase db, String action, String tableName, long accountId,
            UserAccounts userAccount, int callingUid) {
        SQLiteStatement logStatement = userAccount.statementForLogging;
        logStatement.bindLong(1, accountId);
        logStatement.bindString(2, action);
        logStatement.bindString(3, DebugDbHelper.dateFromat.format(new Date()));
        logStatement.bindLong(4, callingUid);
        logStatement.bindString(5, tableName);
        logStatement.bindLong(6, userAccount.debugDbInsertionPoint);
        logStatement.execute();
        logStatement.clearBindings();
        userAccount.debugDbInsertionPoint = (userAccount.debugDbInsertionPoint + 1)
                % MAX_DEBUG_DB_SIZE;
    }

    /*
     * This should only be called once to compile the sql statement for logging
     * and to find the insertion point.
     */
    private void initializeDebugDbSizeAndCompileSqlStatementForLogging(SQLiteDatabase db,
            UserAccounts userAccount) {
        // Initialize the count if not done earlier.
        int size = (int) getDebugTableRowCount(db);
        if (size >= MAX_DEBUG_DB_SIZE) {
            // Table is full, and we need to find the point where to insert.
            userAccount.debugDbInsertionPoint = (int) getDebugTableInsertionPoint(db);
        } else {
            userAccount.debugDbInsertionPoint = size;
        }
        compileSqlStatementForLogging(db, userAccount);
    }

    private void compileSqlStatementForLogging(SQLiteDatabase db, UserAccounts userAccount) {
        String sql = "INSERT OR REPLACE INTO " + DebugDbHelper.TABLE_DEBUG
                + " VALUES (?,?,?,?,?,?)";
        userAccount.statementForLogging = db.compileStatement(sql);
    }

    private long getDebugTableRowCount(SQLiteDatabase db) {
        String queryCountDebugDbRows = "SELECT COUNT(*) FROM " + DebugDbHelper.TABLE_DEBUG;
        return DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
    }

    /*
     * Finds the row key where the next insertion should take place. This should
     * be invoked only if the table has reached its full capacity.
     */
    private long getDebugTableInsertionPoint(SQLiteDatabase db) {
        // This query finds the smallest timestamp value (and if 2 records have
        // same timestamp, the choose the lower id).
        String queryCountDebugDbRows = new StringBuilder()
                .append("SELECT ").append(DebugDbHelper.KEY)
                .append(" FROM ").append(DebugDbHelper.TABLE_DEBUG)
                .append(" ORDER BY ")
                .append(DebugDbHelper.TIMESTAMP).append(",").append(DebugDbHelper.KEY)
                .append(" LIMIT 1")
                .toString();
        return DatabaseUtils.longForQuery(db, queryCountDebugDbRows, null);
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

        private void populateMetaTableWithAuthTypeAndUID(
                SQLiteDatabase db,
                Map<String, Integer> authTypeAndUIDMap) {
            Iterator<Entry<String, Integer>> iterator = authTypeAndUIDMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, Integer> entry = iterator.next();
                ContentValues values = new ContentValues();
                values.put(META_KEY,
                        META_KEY_FOR_AUTHENTICATOR_UID_FOR_TYPE_PREFIX + entry.getKey());
                values.put(META_VALUE, entry.getValue());
                db.insert(TABLE_META, null, values);
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
            db.execSQL("INSERT INTO " + DebugDbHelper.TABLE_DEBUG
                    + "(" + ACCOUNTS_ID + "," + DebugDbHelper.ACTION_TYPE + ","
                    + DebugDbHelper.TIMESTAMP + "," + DebugDbHelper.CALLER_UID + ","
                    + DebugDbHelper.TABLE_NAME + "," + DebugDbHelper.KEY + ") " +
                    "SELECT " + ACCOUNTS_ID + "," + DebugDbHelper.ACTION_TYPE + ","
                    + DebugDbHelper.TIMESTAMP + "," + DebugDbHelper.CALLER_UID + ","
                    + DebugDbHelper.TABLE_NAME + "," + DebugDbHelper.KEY
                    + " FROM preNDb." + DebugDbHelper.TABLE_DEBUG);
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
                PreNDatabaseHelper preNDatabaseHelper = new PreNDatabaseHelper(context, userId,
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

    static class CeDatabaseHelper extends SQLiteOpenHelper {

        public CeDatabaseHelper(Context context, String ceDatabaseName) {
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
                db.execSQL("DROP TABLE IF EXISTS " + DebugDbHelper.TABLE_DEBUG);
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

        static String findAccountPasswordByNameAndType(SQLiteDatabase db, String name,
                String type) {
            Cursor cursor = db.query(CE_TABLE_ACCOUNTS, new String[]{ACCOUNTS_PASSWORD},
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

        /**
         * Creates a new {@code CeDatabaseHelper}. If pre-N db file is present at the old location,
         * it also performs migration to the new CE database.
         * @param context
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
    }

    public IBinder onBind(@SuppressWarnings("unused") Intent intent) {
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

                // Add debug information.
                fout.println();
                Cursor cursor = db.query(DebugDbHelper.TABLE_DEBUG, null,
                        null, null, null, null, DebugDbHelper.TIMESTAMP);
                fout.println("AccountId, Action_Type, timestamp, UID, TableName, Key");
                fout.println("Accounts History");
                try {
                    while (cursor.moveToNext()) {
                        // print type,count
                        fout.println(cursor.getString(0) + "," + cursor.getString(1) + "," +
                                cursor.getString(2) + "," + cursor.getString(3) + ","
                                + cursor.getString(4) + "," + cursor.getString(5));
                    }
                } finally {
                    cursor.close();
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
                UserHandle user = new UserHandle(userId);
                Context contextForUser = getContextForUser(user);
                final String notificationTitleFormat =
                        contextForUser.getText(R.string.notification_title).toString();
                Notification n = new Notification.Builder(contextForUser)
                        .setWhen(0)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setColor(contextForUser.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(String.format(notificationTitleFormat, account.name))
                        .setContentText(message)
                        .setContentIntent(PendingIntent.getActivityAsUser(
                                mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT,
                                null, user))
                        .build();
                installNotification(notificationId, n, user);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @VisibleForTesting
    protected void installNotification(final int notificationId, final Notification n,
            UserHandle user) {
        ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .notifyAsUser(null, notificationId, n, user);
    }

    @VisibleForTesting
    protected void cancelNotification(int id, UserHandle user) {
        long identityToken = clearCallingIdentity();
        try {
            ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancelAsUser(null, id, user);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean isPermitted(String opPackageName, int callingUid, String... permissions) {
        for (String perm : permissions) {
            if (mContext.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "  caller uid " + callingUid + " has " + perm);
                }
                final int opCode = AppOpsManager.permissionToOpCode(perm);
                if (opCode == AppOpsManager.OP_NONE || mAppOpsManager.noteOp(
                        opCode, callingUid, opPackageName) == AppOpsManager.MODE_ALLOWED) {
                    return true;
                }
            }
        }
        return false;
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

    private boolean isPrivileged(int callingUid) {
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
                        && (packageInfo.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    private boolean permissionIsGranted(
            Account account, String authTokenType, int callerUid, int userId) {
        final boolean isPrivileged = isPrivileged(callerUid);
        final boolean fromAuthenticator = account != null
                && isAccountManagedByCaller(account.type, callerUid, userId);
        final boolean hasExplicitGrants = account != null
                && hasExplicitlyGrantedPermission(account, authTokenType, callerUid);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "checkGrantsOrCallingUidAgainstAuthenticator: caller uid "
                    + callerUid + ", " + account
                    + ": is authenticator? " + fromAuthenticator
                    + ", has explicit permission? " + hasExplicitGrants);
        }
        return fromAuthenticator || hasExplicitGrants || isPrivileged;
    }

    private boolean isAccountVisibleToCaller(String accountType, int callingUid, int userId,
            String opPackageName) {
        if (accountType == null) {
            return false;
        } else {
            return getTypesVisibleToCaller(callingUid, userId,
                    opPackageName).contains(accountType);
        }
    }

    private boolean isAccountManagedByCaller(String accountType, int callingUid, int userId) {
        if (accountType == null) {
            return false;
        } else {
            return getTypesManagedByCaller(callingUid, userId).contains(accountType);
        }
    }

    private List<String> getTypesVisibleToCaller(int callingUid, int userId,
            String opPackageName) {
        boolean isPermitted =
                isPermitted(opPackageName, callingUid, Manifest.permission.GET_ACCOUNTS,
                        Manifest.permission.GET_ACCOUNTS_PRIVILEGED);
        return getTypesForCaller(callingUid, userId, isPermitted);
    }

    private List<String> getTypesManagedByCaller(int callingUid, int userId) {
        return getTypesForCaller(callingUid, userId, false);
    }

    private List<String> getTypesForCaller(
            int callingUid, int userId, boolean isOtherwisePermitted) {
        List<String> managedAccountTypes = new ArrayList<>();
        long identityToken = Binder.clearCallingIdentity();
        Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos;
        try {
            serviceInfos = mAuthenticatorCache.getAllServices(userId);
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo :
                serviceInfos) {
            final int sigChk = mPackageManager.checkSignatures(serviceInfo.uid, callingUid);
            if (isOtherwisePermitted || sigChk == PackageManager.SIGNATURE_MATCH) {
                managedAccountTypes.add(serviceInfo.type.type);
            }
        }
        return managedAccountTypes;
    }

    private boolean isAccountPresentForCaller(String accountName, String accountType) {
        if (getUserAccountsForCaller().accountCache.containsKey(accountType)) {
            for (Account account : getUserAccountsForCaller().accountCache.get(accountType)) {
                if (account.name.equals(accountName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void checkManageUsersPermission(String message) {
        if (ActivityManager.checkComponentPermission(
                android.Manifest.permission.MANAGE_USERS, Binder.getCallingUid(), -1, true)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    private static void checkManageOrCreateUsersPermission(String message) {
        if (ActivityManager.checkComponentPermission(android.Manifest.permission.MANAGE_USERS,
                Binder.getCallingUid(), -1, true) != PackageManager.PERMISSION_GRANTED &&
                ActivityManager.checkComponentPermission(android.Manifest.permission.CREATE_USERS,
                        Binder.getCallingUid(), -1, true) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need MANAGE_USERS or CREATE_USERS permission to: "
                    + message);
        }
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

    private boolean isSystemUid(int callingUid) {
        String[] packages = null;
        long ident = Binder.clearCallingIdentity();
        try {
            packages = mPackageManager.getPackagesForUid(callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (packages != null) {
            for (String name : packages) {
                try {
                    PackageInfo packageInfo = mPackageManager.getPackageInfo(name, 0 /* flags */);
                    if (packageInfo != null
                            && (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                                    != 0) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, String.format("Could not find package [%s]", name), e);
                }
            }
        } else {
            Log.w(TAG, "No known packages with uid " + callingUid);
        }
        return false;
    }

    /** Succeeds if any of the specified permissions are granted. */
    private void checkReadAccountsPermitted(
            int callingUid,
            String accountType,
            int userId,
            String opPackageName) {
        if (!isAccountVisibleToCaller(accountType, callingUid, userId, opPackageName)) {
            String msg = String.format(
                    "caller uid %s cannot access %s accounts",
                    callingUid,
                    accountType);
            Log.w(TAG, "  " + msg);
            throw new SecurityException(msg);
        }
    }

    private boolean canUserModifyAccounts(int userId, int callingUid) {
        // the managing app can always modify accounts
        if (isProfileOwner(callingUid)) {
            return true;
        }
        if (getUserManager().getUserRestrictions(new UserHandle(userId))
                .getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS)) {
            return false;
        }
        return true;
    }

    private boolean canUserModifyAccountsForType(int userId, String accountType, int callingUid) {
        // the managing app can always modify accounts
        if (isProfileOwner(callingUid)) {
            return true;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) mContext
                .getSystemService(Context.DEVICE_POLICY_SERVICE);
        String[] typesArray = dpm.getAccountTypesWithManagementDisabledAsUser(userId);
        if (typesArray == null) {
            return true;
        }
        for (String forbiddenType : typesArray) {
            if (forbiddenType.equals(accountType)) {
                return false;
            }
        }
        return true;
    }

    private boolean isProfileOwner(int uid) {
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        return (dpmi != null)
                && dpmi.isActiveAdminWithPolicy(uid, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
    }

    @Override
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
                    UserHandle.of(accounts.userId));
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
        accounts.previousNameCache.remove(account);
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
        UserInfo user = getUserManager().getUserInfo(userAccounts.userId);
        if (user != null && user.isRestricted()) {
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

    protected String readCachedTokenInternal(
            UserAccounts accounts,
            Account account,
            String tokenType,
            String callingPackage,
            byte[] pkgSigDigest) {
        synchronized (accounts.cacheLock) {
            return accounts.accountTokenCaches.get(
                    account, tokenType, callingPackage, pkgSigDigest);
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
                final SQLiteDatabase db = accounts.openHelper.getReadableDatabaseUserIsUnlocked();
                authTokensForAccount = readAuthTokensForAccountFromDatabaseLocked(db, account);
                accounts.authTokenCache.put(account, authTokensForAccount);
            }
            return authTokensForAccount.get(authTokenType);
        }
    }

    protected String readUserDataInternalLocked(
            UserAccounts accounts, Account account, String key) {
        HashMap<String, String> userDataForAccount = accounts.userDataCache.get(account);
        if (userDataForAccount == null) {
            // need to populate the cache for this account
            final SQLiteDatabase db = accounts.openHelper.getReadableDatabaseUserIsUnlocked();
            userDataForAccount = readUserDataForAccountFromDatabaseLocked(db, account);
            accounts.userDataCache.put(account, userDataForAccount);
        }
        return userDataForAccount.get(key);
    }

    protected HashMap<String, String> readUserDataForAccountFromDatabaseLocked(
            final SQLiteDatabase db, Account account) {
        HashMap<String, String> userDataForAccount = new HashMap<>();
        Cursor cursor = db.query(CE_TABLE_EXTRAS,
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

    private Context getContextForUser(UserHandle user) {
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
        } catch (NameNotFoundException e) {
            // Default to mContext, not finding the package system is running as is unlikely.
            return mContext;
        }
    }

    private void sendResponse(IAccountManagerResponse response, Bundle result) {
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

    private void sendErrorResponse(IAccountManagerResponse response, int errorCode,
            String errorMessage) {
        try {
            response.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            // if the caller is dead then there is no one to care about remote
            // exceptions
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }
}
