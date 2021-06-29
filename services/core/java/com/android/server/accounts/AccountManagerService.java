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
import android.accounts.AccountManagerInternal;
import android.accounts.AccountManagerResponse;
import android.accounts.AuthenticatorDescription;
import android.accounts.CantAddAccountActivity;
import android.accounts.ChooseAccountActivity;
import android.accounts.GrantCredentialsPermissionActivity;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManager;
import android.accounts.IAccountManagerResponse;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
            mService = new AccountManagerService(new Injector(getContext()));
            publishBinderService(Context.ACCOUNT_SERVICE, mService);
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.onUnlockUser(userHandle);
        }

        @Override
        public void onStopUser(int userHandle) {
            Slog.i(TAG, "onStopUser " + userHandle);
            mService.purgeUserData(userHandle);
        }
    }

    final Context mContext;

    private final PackageManager mPackageManager;
    private final AppOpsManager mAppOpsManager;
    private UserManager mUserManager;
    private final Injector mInjector;

    final MessageHandler mHandler;

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_COPY_SHARED_ACCOUNT = 4;

    private final IAccountAuthenticatorCache mAuthenticatorCache;
    private static final String PRE_N_DATABASE_NAME = "accounts.db";
    private static final Intent ACCOUNTS_CHANGED_INTENT;

    private static final int SIGNATURE_CHECK_MISMATCH = 0;
    private static final int SIGNATURE_CHECK_MATCH = 1;
    private static final int SIGNATURE_CHECK_UID_MATCH = 2;

    static {
        ACCOUNTS_CHANGED_INTENT = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
        ACCOUNTS_CHANGED_INTENT.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();

    static class UserAccounts {
        private final int userId;
        final AccountsDb accountsDb;
        private final HashMap<Pair<Pair<Account, String>, Integer>, NotificationId>
                credentialsPermissionNotificationIds = new HashMap<>();
        private final HashMap<Account, NotificationId> signinRequiredNotificationIds
                = new HashMap<>();
        final Object cacheLock = new Object();
        final Object dbLock = new Object(); // if needed, dbLock must be obtained before cacheLock
        /** protected by the {@link #cacheLock} */
        final HashMap<String, Account[]> accountCache = new LinkedHashMap<>();
        /** protected by the {@link #cacheLock} */
        private final Map<Account, Map<String, String>> userDataCache = new HashMap<>();
        /** protected by the {@link #cacheLock} */
        private final Map<Account, Map<String, String>> authTokenCache = new HashMap<>();
        /** protected by the {@link #cacheLock} */
        private final TokenCache accountTokenCaches = new TokenCache();
        /** protected by the {@link #cacheLock} */
        private final Map<Account, Map<String, Integer>> visibilityCache = new HashMap<>();

        /** protected by the {@link #mReceiversForType},
         *  type -> (packageName -> number of active receivers)
         *  type == null is used to get notifications about all account types
         */
        private final Map<String, Map<String, Integer>> mReceiversForType = new HashMap<>();

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
        private SQLiteStatement statementForLogging; // TODO Move to AccountsDb

        UserAccounts(Context context, int userId, File preNDbFile, File deDbFile) {
            this.userId = userId;
            synchronized (dbLock) {
                synchronized (cacheLock) {
                    accountsDb = AccountsDb.create(context, userId, preNDbFile, deDbFile);
                }
            }
        }
    }

    private final SparseArray<UserAccounts> mUsers = new SparseArray<>();
    private final SparseBooleanArray mLocalUnlockedUsers = new SparseBooleanArray();
    // Not thread-safe. Only use in synchronized context
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private CopyOnWriteArrayList<AccountManagerInternal.OnAppPermissionChangeListener>
            mAppPermissionChangeListeners = new CopyOnWriteArrayList<>();

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

    public AccountManagerService(Injector injector) {
        mInjector = injector;
        mContext = injector.getContext();
        mPackageManager = mContext.getPackageManager();
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mHandler = new MessageHandler(injector.getMessageHandlerLooper());
        mAuthenticatorCache = mInjector.getAccountAuthenticatorCache();
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
                    final String removedPackageName = intent.getData().getSchemeSpecificPart();
                    Runnable purgingRunnable = new Runnable() {
                        @Override
                        public void run() {
                            purgeOldGrantsAll();
                            // Notify authenticator about removed app?
                            removeVisibilityValuesForPackage(removedPackageName);
                        }
                    };
                    mHandler.post(purgingRunnable);
                }
            }
        }, intentFilter);

        injector.addLocalService(new AccountManagerInternalImpl());

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (userId < 1) return;
                    Slog.i(TAG, "User " + userId + " removed");
                    purgeUserData(userId);
                }
            }
        }, UserHandle.ALL, userFilter, null, null);

        // Need to cancel account request notifications if the update/install can access the account
        new PackageMonitor() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                // Called on a handler, and running as the system
                cancelAccountAccessRequestNotificationIfNeeded(uid, true);
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                // Called on a handler, and running as the system
                cancelAccountAccessRequestNotificationIfNeeded(uid, true);
            }
        }.register(mContext, mHandler.getLooper(), UserHandle.ALL, true);

        // Cancel account request notification if an app op was preventing the account access
        mAppOpsManager.startWatchingMode(AppOpsManager.OP_GET_ACCOUNTS, null,
                new AppOpsManager.OnOpChangedInternalListener() {
            @Override
            public void onOpChanged(int op, String packageName) {
                try {
                    final int userId = ActivityManager.getCurrentUser();
                    final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
                    final int mode = mAppOpsManager.checkOpNoThrow(
                            AppOpsManager.OP_GET_ACCOUNTS, uid, packageName);
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            cancelAccountAccessRequestNotificationIfNeeded(packageName, uid, true);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                } catch (NameNotFoundException e) {
                    /* ignore */
                }
            }
        });

        // Cancel account request notification if a permission was preventing the account access
        mPackageManager.addOnPermissionsChangeListener(
                (int uid) -> {
            Account[] accounts = null;
            String[] packageNames = mPackageManager.getPackagesForUid(uid);
            if (packageNames != null) {
                final int userId = UserHandle.getUserId(uid);
                final long identity = Binder.clearCallingIdentity();
                try {
                    for (String packageName : packageNames) {
                                // if app asked for permission we need to cancel notification even
                                // for O+ applications.
                                if (mPackageManager.checkPermission(
                                        Manifest.permission.GET_ACCOUNTS,
                                        packageName) != PackageManager.PERMISSION_GRANTED) {
                                    continue;
                                }

                        if (accounts == null) {
                            accounts = getAccountsAsUser(null, userId, "android");
                            if (ArrayUtils.isEmpty(accounts)) {
                                return;
                            }
                        }

                        for (Account account : accounts) {
                            cancelAccountAccessRequestNotificationIfNeeded(
                                    account, uid, packageName, true);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        });
    }

    private void cancelAccountAccessRequestNotificationIfNeeded(int uid,
            boolean checkAccess) {
        Account[] accounts = getAccountsAsUser(null, UserHandle.getUserId(uid), "android");
        for (Account account : accounts) {
            cancelAccountAccessRequestNotificationIfNeeded(account, uid, checkAccess);
        }
    }

    private void cancelAccountAccessRequestNotificationIfNeeded(String packageName, int uid,
            boolean checkAccess) {
        Account[] accounts = getAccountsAsUser(null, UserHandle.getUserId(uid), "android");
        for (Account account : accounts) {
            cancelAccountAccessRequestNotificationIfNeeded(account, uid, packageName, checkAccess);
        }
    }

    private void cancelAccountAccessRequestNotificationIfNeeded(Account account, int uid,
            boolean checkAccess) {
        String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (packageNames != null) {
            for (String packageName : packageNames) {
                cancelAccountAccessRequestNotificationIfNeeded(account, uid,
                        packageName, checkAccess);
            }
        }
    }

    private void cancelAccountAccessRequestNotificationIfNeeded(Account account,
            int uid, String packageName, boolean checkAccess) {
        if (!checkAccess || hasAccountAccess(account, packageName,
                UserHandle.getUserHandleForUid(uid))) {
            cancelNotification(getCredentialPermissionNotificationId(account,
                    AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE, uid),
                    UserHandle.getUserHandleForUid(uid));
        }
    }

    @Override
    public boolean addAccountExplicitlyWithVisibility(Account account, String password,
            Bundle extras, Map packageToVisibility) {
        Bundle.setDefusable(extras, true);
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addAccountExplicitly: " + account + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot explicitly add accounts of type: %s",
                    callingUid, account.type);
            throw new SecurityException(msg);
        }
        /*
         * Child users are not allowed to add accounts. Only the accounts that are shared by the
         * parent profile can be added to child profile.
         *
         * TODO: Only allow accounts that were shared to be added by a limited user.
         */
        // fails if the account already exists
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return addAccountInternal(accounts, account, password, extras, callingUid,
                    (Map<String, Integer>) packageToVisibility);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName,
            String accountType) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        boolean isSystemUid = UserHandle.isSameApp(callingUid, Process.SYSTEM_UID);
        List<String> managedTypes = getTypesForCaller(callingUid, userId, isSystemUid);

        if ((accountType != null && !managedTypes.contains(accountType))
                || (accountType == null && !isSystemUid)) {
            throw new SecurityException(
                    "getAccountsAndVisibilityForPackage() called from unauthorized uid "
                            + callingUid + " with packageName=" + packageName);
        }
        if (accountType != null) {
            managedTypes = new ArrayList<String>();
            managedTypes.add(accountType);
        }

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsAndVisibilityForPackage(packageName, managedTypes, callingUid,
                    accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /*
     * accountTypes may not be null
     */
    private Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName,
            List<String> accountTypes, Integer callingUid, UserAccounts accounts) {
        if (!packageExistsForUser(packageName, accounts.userId)) {
            Log.d(TAG, "Package not found " + packageName);
            return new LinkedHashMap<>();
        }

        Map<Account, Integer> result = new LinkedHashMap<>();
        for (String accountType : accountTypes) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    final Account[] accountsOfType = accounts.accountCache.get(accountType);
                    if (accountsOfType != null) {
                        for (Account account : accountsOfType) {
                            result.put(account,
                                    resolveAccountVisibility(account, packageName, accounts));
                        }
                    }
                }
            }
        }
        return filterSharedAccounts(accounts, result, callingUid, packageName);
    }

    @Override
    public Map<String, Integer> getPackagesAndVisibilityForAccount(Account account) {
        Preconditions.checkNotNull(account, "account cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)
                && !isSystemUid(callingUid)) {
            String msg =
                    String.format("uid %s cannot get secrets for account %s", callingUid, account);
            throw new SecurityException(msg);
        }

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    return getPackagesAndVisibilityForAccountLocked(account, accounts);
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }

    }

    /**
     * Returns Map with all package names and visibility values for given account.
     * The method and returned map must be guarded by accounts.cacheLock
     *
     * @param account Account to get visibility values.
     * @param accounts UserAccount that currently hosts the account and application
     *
     * @return Map with cache for package names to visibility.
     */
    private @NonNull Map<String, Integer> getPackagesAndVisibilityForAccountLocked(Account account,
            UserAccounts accounts) {
        Map<String, Integer> accountVisibility = accounts.visibilityCache.get(account);
        if (accountVisibility == null) {
            Log.d(TAG, "Visibility was not initialized");
            accountVisibility = new HashMap<>();
            accounts.visibilityCache.put(account, accountVisibility);
        }
        return accountVisibility;
    }

    @Override
    public int getAccountVisibility(Account account, String packageName) {
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)
            && !isSystemUid(callingUid)) {
            String msg = String.format(
                    "uid %s cannot get secrets for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            if (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)) {
                int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
                if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                    return visibility;
                } else {
                   return AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
                }
            }
            if (AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) {
                int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
                if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                    return visibility;
                } else {
                   return AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE;
                }
            }
            return resolveAccountVisibility(account, packageName, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Method returns visibility for given account and package name.
     *
     * @param account The account to check visibility.
     * @param packageName Package name to check visibility.
     * @param accounts UserAccount that currently hosts the account and application
     *
     * @return Visibility value, AccountManager.VISIBILITY_UNDEFINED if no value was stored.
     *
     */
    private int getAccountVisibilityFromCache(Account account, String packageName,
            UserAccounts accounts) {
        synchronized (accounts.cacheLock) {
            Map<String, Integer> accountVisibility =
                    getPackagesAndVisibilityForAccountLocked(account, accounts);
            Integer visibility = accountVisibility.get(packageName);
            return visibility != null ? visibility : AccountManager.VISIBILITY_UNDEFINED;
        }
    }

    /**
     * Method which handles default values for Account visibility.
     *
     * @param account The account to check visibility.
     * @param packageName Package name to check visibility
     * @param accounts UserAccount that currently hosts the account and application
     *
     * @return Visibility value, the method never returns AccountManager.VISIBILITY_UNDEFINED
     *
     */
    private Integer resolveAccountVisibility(Account account, @NonNull String packageName,
            UserAccounts accounts) {
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        int uid = -1;
        try {
            long identityToken = clearCallingIdentity();
            try {
                uid = mPackageManager.getPackageUidAsUser(packageName, accounts.userId);
            } finally {
                restoreCallingIdentity(identityToken);
            }
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return AccountManager.VISIBILITY_NOT_VISIBLE;
        }

        // System visibility can not be restricted.
        if (UserHandle.isSameApp(uid, Process.SYSTEM_UID)) {
            return AccountManager.VISIBILITY_VISIBLE;
        }

        int signatureCheckResult =
                checkPackageSignature(account.type, uid, accounts.userId);

        // Authenticator can not restrict visibility to itself.
        if (signatureCheckResult == SIGNATURE_CHECK_UID_MATCH) {
            return AccountManager.VISIBILITY_VISIBLE; // Authenticator can always see the account
        }

        // Return stored value if it was set.
        int visibility = getAccountVisibilityFromCache(account, packageName, accounts);

        if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
            return visibility;
        }

        boolean isPrivileged = isPermittedForPackage(packageName, uid, accounts.userId,
                Manifest.permission.GET_ACCOUNTS_PRIVILEGED);

        // Device/Profile owner gets visibility by default.
        if (isProfileOwner(uid)) {
            return AccountManager.VISIBILITY_VISIBLE;
        }

        boolean preO = isPreOApplication(packageName);
        if ((signatureCheckResult != SIGNATURE_CHECK_MISMATCH)
                || (preO && checkGetAccountsPermission(packageName, uid, accounts.userId))
                || (checkReadContactsPermission(packageName, uid, accounts.userId)
                    && accountTypeManagesContacts(account.type, accounts.userId))
                || isPrivileged) {
            // Use legacy for preO apps with GET_ACCOUNTS permission or pre/postO with signature
            // match.
            visibility = getAccountVisibilityFromCache(account,
                    AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED == visibility) {
                visibility = AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
            }
        } else {
            visibility = getAccountVisibilityFromCache(account,
                    AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED == visibility) {
                visibility = AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE;
            }
        }
        return visibility;
    }

    /**
     * Checks targetSdk for a package;
     *
     * @param packageName Package name
     *
     * @return True if package's target SDK is below {@link android.os.Build.VERSION_CODES#O}, or
     *         undefined
     */
    private boolean isPreOApplication(String packageName) {
        try {
            long identityToken = clearCallingIdentity();
            ApplicationInfo applicationInfo;
            try {
                applicationInfo = mPackageManager.getApplicationInfo(packageName, 0);
            } finally {
                restoreCallingIdentity(identityToken);
            }

            if (applicationInfo != null) {
                int version = applicationInfo.targetSdkVersion;
                return version < android.os.Build.VERSION_CODES.O;
            }
            return true;
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return true;
        }
    }

    @Override
    public boolean setAccountVisibility(Account account, String packageName, int newVisibility) {
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)
            && !isSystemUid(callingUid)) {
            String msg = String.format(
                    "uid %s cannot get secrets for accounts of type: %s",
                    callingUid,
                    account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return setAccountVisibility(account, packageName, newVisibility, true /* notify */,
                accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean isVisible(int visibility) {
        return visibility == AccountManager.VISIBILITY_VISIBLE ||
            visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
    }

    /**
     * Updates visibility for given account name and package.
     *
     * @param account Account to update visibility.
     * @param packageName Package name for which visibility is updated.
     * @param newVisibility New visibility calue
     * @param notify if the flag is set applications will get notification about visibility change
     * @param accounts UserAccount that currently hosts the account and application
     *
     * @return True if account visibility was changed.
     */
    private boolean setAccountVisibility(Account account, String packageName, int newVisibility,
            boolean notify, UserAccounts accounts) {
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                Map<String, Integer> packagesToVisibility;
                List<String> accountRemovedReceivers;
                if (notify) {
                    if (isSpecialPackageKey(packageName)) {
                        packagesToVisibility =
                                getRequestingPackages(account, accounts);
                        accountRemovedReceivers = getAccountRemovedReceivers(account, accounts);
                    } else {
                        if (!packageExistsForUser(packageName, accounts.userId)) {
                            return false; // package is not installed.
                        }
                        packagesToVisibility = new HashMap<>();
                        packagesToVisibility.put(packageName,
                                resolveAccountVisibility(account, packageName, accounts));
                        accountRemovedReceivers = new ArrayList<>();
                        if (shouldNotifyPackageOnAccountRemoval(account, packageName, accounts)) {
                            accountRemovedReceivers.add(packageName);
                        }
                    }
                } else {
                    // Notifications will not be send - only used during add account.
                    if (!isSpecialPackageKey(packageName) &&
                            !packageExistsForUser(packageName, accounts.userId)) {
                        // package is not installed and not meta value.
                        return false;
                    }
                    packagesToVisibility = Collections.emptyMap();
                    accountRemovedReceivers = Collections.emptyList();
                }

                if (!updateAccountVisibilityLocked(account, packageName, newVisibility, accounts)) {
                    return false;
                }

                if (notify) {
                    for (Entry<String, Integer> packageToVisibility : packagesToVisibility
                            .entrySet()) {
                        int oldVisibility = packageToVisibility.getValue();
                        int currentVisibility =
                            resolveAccountVisibility(account, packageName, accounts);
                        if (isVisible(oldVisibility) != isVisible(currentVisibility)) {
                            notifyPackage(packageToVisibility.getKey(), accounts);
                        }
                    }
                    for (String packageNameToNotify : accountRemovedReceivers) {
                        sendAccountRemovedBroadcast(account, packageNameToNotify, accounts.userId);
                    }
                    sendAccountsChangedBroadcast(accounts.userId);
                }
                return true;
            }
        }
    }

    // Update account visibility in cache and database.
    private boolean updateAccountVisibilityLocked(Account account, String packageName,
            int newVisibility, UserAccounts accounts) {
        final long accountId = accounts.accountsDb.findDeAccountId(account);
        if (accountId < 0) {
            return false;
        }

        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            if (!accounts.accountsDb.setAccountVisibility(accountId, packageName,
                    newVisibility)) {
                return false;
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        Map<String, Integer> accountVisibility =
            getPackagesAndVisibilityForAccountLocked(account, accounts);
        accountVisibility.put(packageName, newVisibility);
        return true;
    }

    @Override
    public void registerAccountListener(String[] accountTypes, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);

        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            registerAccountListener(accountTypes, opPackageName, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void registerAccountListener(String[] accountTypes, String opPackageName,
            UserAccounts accounts) {
        synchronized (accounts.mReceiversForType) {
            if (accountTypes == null) {
                // null for any type
                accountTypes = new String[] {null};
            }
            for (String type : accountTypes) {
                Map<String, Integer> receivers = accounts.mReceiversForType.get(type);
                if (receivers == null) {
                    receivers = new HashMap<>();
                    accounts.mReceiversForType.put(type, receivers);
                }
                Integer cnt = receivers.get(opPackageName);
                receivers.put(opPackageName, cnt != null ? cnt + 1 : 1);
            }
        }
    }

    @Override
    public void unregisterAccountListener(String[] accountTypes, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            unregisterAccountListener(accountTypes, opPackageName, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void unregisterAccountListener(String[] accountTypes, String opPackageName,
            UserAccounts accounts) {
        synchronized (accounts.mReceiversForType) {
            if (accountTypes == null) {
                // null for any type
                accountTypes = new String[] {null};
            }
            for (String type : accountTypes) {
                Map<String, Integer> receivers = accounts.mReceiversForType.get(type);
                if (receivers == null || receivers.get(opPackageName) == null) {
                    throw new IllegalArgumentException("attempt to unregister wrong receiver");
                }
                Integer cnt = receivers.get(opPackageName);
                if (cnt == 1) {
                    receivers.remove(opPackageName);
                } else {
                    receivers.put(opPackageName, cnt - 1);
                }
            }
        }
    }

    // Send notification to all packages which can potentially see the account
    private void sendNotificationAccountUpdated(Account account, UserAccounts accounts) {
        Map<String, Integer> packagesToVisibility = getRequestingPackages(account, accounts);

        for (Entry<String, Integer> packageToVisibility : packagesToVisibility.entrySet()) {
            if ((packageToVisibility.getValue() != AccountManager.VISIBILITY_NOT_VISIBLE)
                    && (packageToVisibility.getValue()
                        != AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE)) {
                notifyPackage(packageToVisibility.getKey(), accounts);
            }
        }
    }

    /**
     * Sends a direct intent to a package, notifying it of account visibility change.
     *
     * @param packageName to send Account to
     * @param accounts UserAccount that currently hosts the account
     */
    private void notifyPackage(String packageName, UserAccounts accounts) {
        Intent intent = new Intent(AccountManager.ACTION_VISIBLE_ACCOUNTS_CHANGED);
        intent.setPackage(packageName);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, new UserHandle(accounts.userId));
    }

    // Returns a map from package name to visibility, for packages subscribed
    // to notifications about any account type, or type of provided account
    // account type or all types.
    private Map<String, Integer> getRequestingPackages(Account account, UserAccounts accounts) {
        Set<String> packages = new HashSet<>();
        synchronized (accounts.mReceiversForType) {
            for (String type : new String[] {account.type, null}) {
                Map<String, Integer> receivers = accounts.mReceiversForType.get(type);
                if (receivers != null) {
                    packages.addAll(receivers.keySet());
                }
            }
        }
        Map<String, Integer> result = new HashMap<>();
        for (String packageName : packages) {
            result.put(packageName, resolveAccountVisibility(account, packageName, accounts));
        }
        return result;
    }

    // Returns a list of packages listening to ACTION_ACCOUNT_REMOVED able to see the account.
    private List<String> getAccountRemovedReceivers(Account account, UserAccounts accounts) {
        Intent intent = new Intent(AccountManager.ACTION_ACCOUNT_REMOVED);
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        List<ResolveInfo> receivers =
            mPackageManager.queryBroadcastReceiversAsUser(intent, 0, accounts.userId);
        List<String> result = new ArrayList<>();
        if (receivers == null) {
            return result;
        }
        for (ResolveInfo resolveInfo: receivers) {
            String packageName = resolveInfo.activityInfo.applicationInfo.packageName;
            int visibility = resolveAccountVisibility(account, packageName, accounts);
            if (visibility == AccountManager.VISIBILITY_VISIBLE
                || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {
                result.add(packageName);
            }
        }
        return result;
    }

    // Returns true if given package is listening to ACTION_ACCOUNT_REMOVED and can see the account.
    private boolean shouldNotifyPackageOnAccountRemoval(Account account,
            String packageName, UserAccounts accounts) {
        int visibility = resolveAccountVisibility(account, packageName, accounts);
        if (visibility != AccountManager.VISIBILITY_VISIBLE
            && visibility != AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {
            return false;
        }

        Intent intent = new Intent(AccountManager.ACTION_ACCOUNT_REMOVED);
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.setPackage(packageName);
        List<ResolveInfo> receivers =
            mPackageManager.queryBroadcastReceiversAsUser(intent, 0, accounts.userId);
        return (receivers != null && receivers.size() > 0);
    }

    private boolean packageExistsForUser(String packageName, int userId) {
        try {
            long identityToken = clearCallingIdentity();
            try {
                mPackageManager.getPackageUidAsUser(packageName, userId);
                return true;
            } finally {
                restoreCallingIdentity(identityToken);
            }
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns true if packageName is one of special values.
     */
    private boolean isSpecialPackageKey(String packageName) {
        return (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                || AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName));
    }

    private void sendAccountsChangedBroadcast(int userId) {
        Log.i(TAG, "the accounts changed, sending broadcast of "
                + ACCOUNTS_CHANGED_INTENT.getAction());
        mContext.sendBroadcastAsUser(ACCOUNTS_CHANGED_INTENT, new UserHandle(userId));
    }

    private void sendAccountRemovedBroadcast(Account account, String packageName, int userId) {
        Intent intent = new Intent(AccountManager.ACTION_ACCOUNT_REMOVED);
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.setPackage(packageName);
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
        intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        mContext.sendBroadcastAsUser(intent, new UserHandle(userId));
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
                    + " isCeDatabaseAttached=" + accounts.accountsDb.isCeDatabaseAttached()
                    + " userLocked=" + mLocalUnlockedUsers.get(accounts.userId));
        }

        if (invalidateAuthenticatorCache) {
            mAuthenticatorCache.invalidateCache(accounts.userId);
        }

        final HashMap<String, Integer> knownAuth = getAuthenticatorTypeAndUIDForUser(
                mAuthenticatorCache, accounts.userId);
        boolean userUnlocked = isLocalUnlockedUser(accounts.userId);

        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                boolean accountDeleted = false;

                // Get a map of stored authenticator types to UID
                final AccountsDb accountsDb = accounts.accountsDb;
                Map<String, Integer> metaAuthUid = accountsDb.findMetaAuthUid();
                // Create a list of authenticator type whose previous uid no longer exists
                HashSet<String> obsoleteAuthType = Sets.newHashSet();
                SparseBooleanArray knownUids = null;
                for (Entry<String, Integer> authToUidEntry : metaAuthUid.entrySet()) {
                    String type = authToUidEntry.getKey();
                    int uid = authToUidEntry.getValue();
                    Integer knownUid = knownAuth.get(type);
                    if (knownUid != null && uid == knownUid) {
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
                        if (!knownUids.get(uid)) {
                            // The authenticator is not presently available to the cache. And the
                            // package no longer has a data directory (so we surmise it isn't
                            // updating). So purge its data from the account databases.
                            obsoleteAuthType.add(type);
                            // And delete it from the TABLE_META
                            accountsDb.deleteMetaByAuthTypeAndUid(type, uid);
                        }
                    }
                }

                // Add the newly registered authenticator to TABLE_META. If old authenticators have
                // been re-enabled (after being updated for example), then we just overwrite the old
                // values.
                for (Entry<String, Integer> entry : knownAuth.entrySet()) {
                    accountsDb.insertOrReplaceMetaAuthTypeAndUid(entry.getKey(), entry.getValue());
                }

                final Map<Long, Account> accountsMap = accountsDb.findAllDeAccounts();
                try {
                    accounts.accountCache.clear();
                    final HashMap<String, ArrayList<String>> accountNamesByType
                            = new LinkedHashMap<>();
                    for (Entry<Long, Account> accountEntry : accountsMap.entrySet()) {
                        final long accountId = accountEntry.getKey();
                        final Account account = accountEntry.getValue();
                        if (obsoleteAuthType.contains(account.type)) {
                            Slog.w(TAG, "deleting account " + account.name + " because type "
                                    + account.type
                                    + "'s registered authenticator no longer exist.");
                            Map<String, Integer> packagesToVisibility =
                                    getRequestingPackages(account, accounts);
                            List<String> accountRemovedReceivers =
                                getAccountRemovedReceivers(account, accounts);
                            accountsDb.beginTransaction();
                            try {
                                accountsDb.deleteDeAccount(accountId);
                                // Also delete from CE table if user is unlocked; if user is
                                // currently locked the account will be removed later by
                                // syncDeCeAccountsLocked
                                if (userUnlocked) {
                                    accountsDb.deleteCeAccount(accountId);
                                }
                                accountsDb.setTransactionSuccessful();
                            } finally {
                                accountsDb.endTransaction();
                            }
                            accountDeleted = true;

                            logRecord(AccountsDb.DEBUG_ACTION_AUTHENTICATOR_REMOVE,
                                    AccountsDb.TABLE_ACCOUNTS, accountId, accounts);

                            accounts.userDataCache.remove(account);
                            accounts.authTokenCache.remove(account);
                            accounts.accountTokenCaches.remove(account);
                            accounts.visibilityCache.remove(account);

                            for (Entry<String, Integer> packageToVisibility :
                                    packagesToVisibility.entrySet()) {
                                if (isVisible(packageToVisibility.getValue())) {
                                    notifyPackage(packageToVisibility.getKey(), accounts);
                                }
                            }
                            for (String packageName : accountRemovedReceivers) {
                                sendAccountRemovedBroadcast(account, packageName, accounts.userId);
                            }
                        } else {
                            ArrayList<String> accountNames = accountNamesByType.get(account.type);
                            if (accountNames == null) {
                                accountNames = new ArrayList<>();
                                accountNamesByType.put(account.type, accountNames);
                            }
                            accountNames.add(account.name);
                        }
                    }
                    for (Map.Entry<String, ArrayList<String>> cur : accountNamesByType.entrySet()) {
                        final String accountType = cur.getKey();
                        final ArrayList<String> accountNames = cur.getValue();
                        final Account[] accountsForType = new Account[accountNames.size()];
                        for (int i = 0; i < accountsForType.length; i++) {
                            accountsForType[i] = new Account(accountNames.get(i), accountType,
                                    UUID.randomUUID().toString());
                        }
                        accounts.accountCache.put(accountType, accountsForType);
                    }
                    accounts.visibilityCache.putAll(accountsDb.findAllVisibilityValues());
                } finally {
                    if (accountDeleted) {
                        sendAccountsChangedBroadcast(accounts.userId);
                    }
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

    static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(
            Context context,
            int userId) {
        AccountAuthenticatorCache authCache = new AccountAuthenticatorCache(context);
        return getAuthenticatorTypeAndUIDForUser(authCache, userId);
    }

    private static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(
            IAccountAuthenticatorCache authCache,
            int userId) {
        HashMap<String, Integer> knownAuth = new LinkedHashMap<>();
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
                File preNDbFile = new File(mInjector.getPreNDatabaseName(userId));
                File deDbFile = new File(mInjector.getDeDatabaseName(userId));
                accounts = new UserAccounts(mContext, userId, preNDbFile, deDbFile);
                initializeDebugDbSizeAndCompileSqlStatementForLogging(accounts);
                mUsers.append(userId, accounts);
                purgeOldGrants(accounts);
                validateAccounts = true;
            }
            // open CE database if necessary
            if (!accounts.accountsDb.isCeDatabaseAttached() && mLocalUnlockedUsers.get(userId)) {
                Log.i(TAG, "User " + userId + " is unlocked - opening CE database");
                synchronized (accounts.dbLock) {
                    synchronized (accounts.cacheLock) {
                        File ceDatabaseFile = new File(mInjector.getCeDatabaseName(userId));
                        accounts.accountsDb.attachCeDatabase(ceDatabaseFile);
                    }
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
        List<Account> accountsToRemove = accounts.accountsDb.findCeAccountsNotInDe();
        if (!accountsToRemove.isEmpty()) {
            Slog.i(TAG, "Accounts " + accountsToRemove + " were previously deleted while user "
                    + accounts.userId + " was locked. Removing accounts from CE tables");
            logRecord(accounts, AccountsDb.DEBUG_ACTION_SYNC_DE_CE_ACCOUNTS,
                    AccountsDb.TABLE_ACCOUNTS);

            for (Account account : accountsToRemove) {
                removeAccountInternal(accounts, account, Process.SYSTEM_UID);
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
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                List<Integer> uids = accounts.accountsDb.findAllUidGrants();
                for (int uid : uids) {
                    final boolean packageExists = mPackageManager.getPackagesForUid(uid) != null;
                    if (packageExists) {
                        continue;
                    }
                    Log.d(TAG, "deleting grants for UID " + uid
                            + " because its package is no longer installed");
                    accounts.accountsDb.deleteGrantsByUid(uid);
                }
            }
        }
    }

    private void removeVisibilityValuesForPackage(String packageName) {
        if (isSpecialPackageKey(packageName)) {
            return;
        }
        synchronized (mUsers) {
            int numberOfUsers = mUsers.size();
            for (int i = 0; i < numberOfUsers; i++) {
                UserAccounts accounts = mUsers.valueAt(i);
                try {
                    mPackageManager.getPackageUidAsUser(packageName, accounts.userId);
                } catch (NameNotFoundException e) {
                    // package does not exist - remove visibility values
                    accounts.accountsDb.deleteAccountVisibilityForPackage(packageName);
                    synchronized (accounts.dbLock) {
                        synchronized (accounts.cacheLock) {
                            for (Account account : accounts.visibilityCache.keySet()) {
                                Map<String, Integer> accountVisibility =
                                        getPackagesAndVisibilityForAccountLocked(account, accounts);
                                accountVisibility.remove(packageName);
                            }
                        }
                    }
              }
          }
        }
    }

    private void purgeUserData(int userId) {
        UserAccounts accounts;
        synchronized (mUsers) {
            accounts = mUsers.get(userId);
            mUsers.remove(userId);
            mLocalUnlockedUsers.delete(userId);
        }
        if (accounts != null) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    accounts.statementForLogging.close();
                    accounts.accountsDb.close();
                }
            }
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

        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                return accounts.accountsDb
                        .findAccountPasswordByNameAndType(account.name, account.type);
            }
        }
    }

    @Override
    public String getPreviousName(Account account) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getPreviousName: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
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
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                AtomicReference<String> previousNameRef = accounts.previousNameCache.get(account);
                if (previousNameRef == null) {
                    String previousName = accounts.accountsDb.findDeAccountPreviousName(account);
                    previousNameRef = new AtomicReference<>(previousName);
                    accounts.previousNameCache.put(account, previousNameRef);
                    return previousName;
                } else {
                    return previousNameRef.get();
                }
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
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(key, "key cannot be null");
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
            if (!accountExistsCache(accounts, account)) {
                return null;
            }
            return readUserDataInternal(accounts, account, key);
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
        mAuthenticatorCache.updateServices(userId);
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
                && callingUid != Process.SYSTEM_UID
                && mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                                != PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
        return addAccountExplicitlyWithVisibility(account, password, extras, null);
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
        Preconditions.checkNotNull(account, "account cannot be null");
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
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                return accounts.accountsDb.updateAccountLastAuthenticatedTime(account);
            }
        }
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
                    for (Account acc : getAccounts(parentUserId, mContext.getOpPackageName())) {
                        if (acc.equals(account)) {
                            mAuthenticator.addAccountFromCredentials(
                                    this, account, accountCredentials);
                            break;
                        }
                    }
                }

                @Override
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    // TODO: Anything to do if if succedded?
                    // TODO: If it failed: Show error notification? Should we remove the shadow
                    // account to avoid retries?
                    // TODO: what we do with the visibility?

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
            Bundle extras, int callingUid, Map<String, Integer> packageToVisibility) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            return false;
        }
        if (!isLocalUnlockedUser(accounts.userId)) {
            Log.w(TAG, "Account " + account + " cannot be added - user " + accounts.userId
                    + " is locked. callingUid=" + callingUid);
            return false;
        }
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    if (accounts.accountsDb.findCeAccountId(account) >= 0) {
                        Log.w(TAG, "insertAccountIntoDatabase: " + account
                                + ", skipping since the account already exists");
                        return false;
                    }
                    long accountId = accounts.accountsDb.insertCeAccount(account, password);
                    if (accountId < 0) {
                        Log.w(TAG, "insertAccountIntoDatabase: " + account
                                + ", skipping the DB insert failed");
                        return false;
                    }
                    // Insert into DE table
                    if (accounts.accountsDb.insertDeAccount(account, accountId) < 0) {
                        Log.w(TAG, "insertAccountIntoDatabase: " + account
                                + ", skipping the DB insert failed");
                        return false;
                    }
                    if (extras != null) {
                        for (String key : extras.keySet()) {
                            final String value = extras.getString(key);
                            if (accounts.accountsDb.insertExtra(accountId, key, value) < 0) {
                                Log.w(TAG, "insertAccountIntoDatabase: " + account
                                        + ", skipping since insertExtra failed for key " + key);
                                return false;
                            }
                        }
                    }

                    if (packageToVisibility != null) {
                        for (Entry<String, Integer> entry : packageToVisibility.entrySet()) {
                            setAccountVisibility(account, entry.getKey() /* package */,
                                    entry.getValue() /* visibility */, false /* notify */,
                                    accounts);
                        }
                    }
                    accounts.accountsDb.setTransactionSuccessful();

                    logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_ADD, AccountsDb.TABLE_ACCOUNTS,
                            accountId,
                            accounts, callingUid);

                    insertAccountIntoCacheLocked(accounts, account);
                } finally {
                    accounts.accountsDb.endTransaction();
                }
            }
        }
        if (getUserManager().getUserInfo(accounts.userId).canHaveProfile()) {
            addAccountToLinkedRestrictedUsers(account, accounts.userId);
        }

        sendNotificationAccountUpdated(account, accounts);
        // Only send LOGIN_ACCOUNTS_CHANGED when the database changed.
        sendAccountsChangedBroadcast(accounts.userId);

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
                    mHandler.sendMessage(mHandler.obtainMessage(
                            MESSAGE_COPY_SHARED_ACCOUNT, parentUserId, user.id, account));
                }
            }
        }
    }

    @Override
    public void hasFeatures(IAccountManagerResponse response,
            Account account, String[] features, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "hasFeatures: " + account
                    + ", response " + response
                    + ", features " + Arrays.toString(features)
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(features != null, "features cannot be null");
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
            result.putString(AccountManager.KEY_ACCOUNT_ACCESS_ID,
                    resultingAccount.getAccessId());
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
                    NotificationId id = accounts.credentialsPermissionNotificationIds.get(pair);
                    cancelNotification(id, new UserHandle(accounts.userId));
                }
            }
        }
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                List<String> accountRemovedReceivers =
                    getAccountRemovedReceivers(accountToRename, accounts);
                accounts.accountsDb.beginTransaction();
                Account renamedAccount = new Account(newName, accountToRename.type);
                if ((accounts.accountsDb.findCeAccountId(renamedAccount) >= 0)) {
                    Log.e(TAG, "renameAccount failed - account with new name already exists");
                    return null;
                }
                try {
                    final long accountId = accounts.accountsDb.findDeAccountId(accountToRename);
                    if (accountId >= 0) {
                        accounts.accountsDb.renameCeAccount(accountId, newName);
                        if (accounts.accountsDb.renameDeAccount(
                                accountId, newName, accountToRename.name)) {
                            accounts.accountsDb.setTransactionSuccessful();
                        } else {
                            Log.e(TAG, "renameAccount failed");
                            return null;
                        }
                    } else {
                        Log.e(TAG, "renameAccount failed - old account does not exist");
                        return null;
                    }
                } finally {
                    accounts.accountsDb.endTransaction();
                }
            /*
             * Database transaction was successful. Clean up cached
             * data associated with the account in the user profile.
             */
                renamedAccount = insertAccountIntoCacheLocked(accounts, renamedAccount);
            /*
             * Extract the data and token caches before removing the
             * old account to preserve the user data associated with
             * the account.
             */
                Map<String, String> tmpData = accounts.userDataCache.get(accountToRename);
                Map<String, String> tmpTokens = accounts.authTokenCache.get(accountToRename);
                Map<String, Integer> tmpVisibility = accounts.visibilityCache.get(accountToRename);
                removeAccountFromCacheLocked(accounts, accountToRename);
            /*
             * Update the cached data associated with the renamed
             * account.
             */
                accounts.userDataCache.put(renamedAccount, tmpData);
                accounts.authTokenCache.put(renamedAccount, tmpTokens);
                accounts.visibilityCache.put(renamedAccount, tmpVisibility);
                accounts.previousNameCache.put(
                        renamedAccount,
                        new AtomicReference<>(accountToRename.name));
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

                sendNotificationAccountUpdated(resultAccount, accounts);
                sendAccountsChangedBroadcast(accounts.userId);
                for (String packageName : accountRemovedReceivers) {
                    sendAccountRemovedBroadcast(accountToRename, packageName, accounts.userId);
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
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");

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
                    NotificationId id = accounts.credentialsPermissionNotificationIds.get(pair);
                    cancelNotification(id, user);
                }
            }
        }
        final long accountId = accounts.accountsDb.findDeAccountId(account);
        logRecord(
                AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_REMOVE,
                AccountsDb.TABLE_ACCOUNTS,
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
        final long accountId = accounts.accountsDb.findDeAccountId(account);
        logRecord(
                AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_REMOVE,
                AccountsDb.TABLE_ACCOUNTS,
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
        boolean isChanged = false;
        boolean userUnlocked = isLocalUnlockedUser(accounts.userId);
        if (!userUnlocked) {
            Slog.i(TAG, "Removing account " + account + " while user "+ accounts.userId
                    + " is still locked. CE data will be removed later");
        }
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                Map<String, Integer> packagesToVisibility = getRequestingPackages(account,
                        accounts);
                List<String> accountRemovedReceivers =
                    getAccountRemovedReceivers(account, accounts);
                accounts.accountsDb.beginTransaction();
                // Set to a dummy value, this will only be used if the database
                // transaction succeeds.
                long accountId = -1;
                try {
                    accountId = accounts.accountsDb.findDeAccountId(account);
                    if (accountId >= 0) {
                        isChanged = accounts.accountsDb.deleteDeAccount(accountId);
                    }
                    // always delete from CE table if CE storage is available
                    // DE account could be removed while CE was locked
                    if (userUnlocked) {
                        long ceAccountId = accounts.accountsDb.findCeAccountId(account);
                        if (ceAccountId >= 0) {
                            accounts.accountsDb.deleteCeAccount(ceAccountId);
                        }
                    }
                    accounts.accountsDb.setTransactionSuccessful();
                } finally {
                    accounts.accountsDb.endTransaction();
                }
                if (isChanged) {
                    removeAccountFromCacheLocked(accounts, account);
                    for (Entry<String, Integer> packageToVisibility : packagesToVisibility
                            .entrySet()) {
                        if ((packageToVisibility.getValue() == AccountManager.VISIBILITY_VISIBLE)
                                || (packageToVisibility.getValue()
                                    == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE)) {
                            notifyPackage(packageToVisibility.getKey(), accounts);
                        }
                    }

                    // Only broadcast LOGIN_ACCOUNTS_CHANGED if a change occurred.
                    sendAccountsChangedBroadcast(accounts.userId);
                    for (String packageName : accountRemovedReceivers) {
                        sendAccountRemovedBroadcast(account, packageName, accounts.userId);
                    }
                    String action = userUnlocked ? AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE
                            : AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE_DE;
                    logRecord(action, AccountsDb.TABLE_ACCOUNTS, accountId, accounts);
                }
            }
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

        if (isChanged) {
            synchronized (accounts.credentialsPermissionNotificationIds) {
                for (Pair<Pair<Account, String>, Integer> key
                        : accounts.credentialsPermissionNotificationIds.keySet()) {
                    if (account.equals(key.first.first)
                            && AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE.equals(key.first.second)) {
                        final int uid = (Integer) key.second;
                        mHandler.post(() -> cancelAccountAccessRequestNotificationIfNeeded(
                                account, uid, false));
                    }
                }
            }
        }

        return isChanged;
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken) {
        int callerUid = Binder.getCallingUid();
        Preconditions.checkNotNull(accountType, "accountType cannot be null");
        Preconditions.checkNotNull(authToken, "authToken cannot be null");
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "invalidateAuthToken: accountType " + accountType
                    + ", caller's uid " + callerUid
                    + ", pid " + Binder.getCallingPid());
        }
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            List<Pair<Account, String>> deletedTokens;
            synchronized (accounts.dbLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    deletedTokens = invalidateAuthTokenLocked(accounts, accountType, authToken);
                    accounts.accountsDb.setTransactionSuccessful();
                } finally {
                    accounts.accountsDb.endTransaction();
                }
                synchronized (accounts.cacheLock) {
                    for (Pair<Account, String> tokenInfo : deletedTokens) {
                        Account act = tokenInfo.first;
                        String tokenType = tokenInfo.second;
                        writeAuthTokenIntoCacheLocked(accounts, act, tokenType, null);
                    }
                    // wipe out cached token in memory.
                    accounts.accountTokenCaches.remove(accountType, authToken);
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private List<Pair<Account, String>> invalidateAuthTokenLocked(UserAccounts accounts, String accountType,
            String authToken) {
        // TODO Move to AccountsDB
        List<Pair<Account, String>> results = new ArrayList<>();
        Cursor cursor = accounts.accountsDb.findAuthtokenForAllAccounts(accountType, authToken);

        try {
            while (cursor.moveToNext()) {
                String authTokenId = cursor.getString(0);
                String accountName = cursor.getString(1);
                String authTokenType = cursor.getString(2);
                accounts.accountsDb.deleteAuthToken(authTokenId);
                results.add(Pair.create(new Account(accountName, accountType), authTokenType));
            }
        } finally {
            cursor.close();
        }
        return results;
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
        synchronized (accounts.dbLock) {
            accounts.accountsDb.beginTransaction();
            boolean updateCache = false;
            try {
                long accountId = accounts.accountsDb.findDeAccountId(account);
                if (accountId < 0) {
                    return false;
                }
                accounts.accountsDb.deleteAuthtokensByAccountIdAndType(accountId, type);
                if (accounts.accountsDb.insertAuthToken(accountId, type, authToken) >= 0) {
                    accounts.accountsDb.setTransactionSuccessful();
                    updateCache = true;
                    return true;
                }
                return false;
            } finally {
                accounts.accountsDb.endTransaction();
                if (updateCache) {
                    synchronized (accounts.cacheLock) {
                        writeAuthTokenIntoCacheLocked(accounts, account, type, authToken);
                    }
                }
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
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(authTokenType, "authTokenType cannot be null");
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
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(authTokenType, "authTokenType cannot be null");
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
        Preconditions.checkNotNull(account, "account cannot be null");
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
        boolean isChanged = false;
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    final long accountId = accounts.accountsDb.findDeAccountId(account);
                    if (accountId >= 0) {
                        accounts.accountsDb.updateCeAccountPassword(accountId, password);
                        accounts.accountsDb.deleteAuthTokensByAccountId(accountId);
                        accounts.authTokenCache.remove(account);
                        accounts.accountTokenCaches.remove(account);
                        accounts.accountsDb.setTransactionSuccessful();
                        // If there is an account whose password will be updated and the database
                        // transactions succeed, then we say that a change has occured. Even if the
                        // new password is the same as the old and there were no authtokens to
                        // delete.
                        isChanged = true;
                        String action = (password == null || password.length() == 0) ?
                                AccountsDb.DEBUG_ACTION_CLEAR_PASSWORD
                                : AccountsDb.DEBUG_ACTION_SET_PASSWORD;
                        logRecord(action, AccountsDb.TABLE_ACCOUNTS, accountId, accounts,
                                callingUid);
                    }
                } finally {
                    accounts.accountsDb.endTransaction();
                    if (isChanged) {
                        // Send LOGIN_ACCOUNTS_CHANGED only if the something changed.
                        sendNotificationAccountUpdated(account, accounts);
                        sendAccountsChangedBroadcast(accounts.userId);
                    }
                }
            }
        }
    }

    @Override
    public void clearPassword(Account account) {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "clearPassword: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
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
            if (!accountExistsCache(accounts, account)) {
                return;
            }
            setUserdataInternal(accounts, account, key, value);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean accountExistsCache(UserAccounts accounts, Account account) {
        synchronized (accounts.cacheLock) {
            if (accounts.accountCache.containsKey(account.type)) {
                for (Account acc : accounts.accountCache.get(account.type)) {
                    if (acc.name.equals(account.name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void setUserdataInternal(UserAccounts accounts, Account account, String key,
            String value) {
        synchronized (accounts.dbLock) {
            accounts.accountsDb.beginTransaction();
            try {
                long accountId = accounts.accountsDb.findDeAccountId(account);
                if (accountId < 0) {
                    return;
                }
                long extrasId = accounts.accountsDb.findExtrasIdByAccountId(accountId, key);
                if (extrasId < 0) {
                    extrasId = accounts.accountsDb.insertExtra(accountId, key, value);
                    if (extrasId < 0) {
                        return;
                    }
                } else if (!accounts.accountsDb.updateExtra(extrasId, value)) {
                    return;
                }
                accounts.accountsDb.setTransactionSuccessful();
            } finally {
                accounts.accountsDb.endTransaction();
            }
            synchronized (accounts.cacheLock) {
                writeUserDataIntoCacheLocked(accounts, account, key, value);
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

    @Override
    public void getAuthTokenLabel(IAccountManagerResponse response, final String accountType,
                                  final String authTokenType)
            throws RemoteException {
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        Preconditions.checkArgument(authTokenType != null, "authTokenType cannot be null");

        final int callingUid = getCallingUid();
        clearCallingIdentity();
        if (UserHandle.getAppId(callingUid) != Process.SYSTEM_UID) {
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
        Preconditions.checkArgument(response != null, "response cannot be null");
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
                                    null,
                                    callerUid,
                                    new AccountAuthenticatorResponse(this),
                                    authTokenType,
                                    true);
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
                            doNotification(
                                    mAccounts,
                                    account,
                                    result.getString(AccountManager.KEY_AUTH_FAILED_MESSAGE),
                                    intent, "android", accounts.userId);
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
            String packageName, int userId) {
        int uid = intent.getIntExtra(
                GrantCredentialsPermissionActivity.EXTRAS_REQUESTING_UID, -1);
        String authTokenType = intent.getStringExtra(
                GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE);
        final String titleAndSubtitle =
                mContext.getString(R.string.permission_request_notification_for_app_with_subtitle,
                getApplicationLabel(packageName), account.name);
        final int index = titleAndSubtitle.indexOf('\n');
        String title = titleAndSubtitle;
        String subtitle = "";
        if (index > 0) {
            title = titleAndSubtitle.substring(0, index);
            subtitle = titleAndSubtitle.substring(index + 1);
        }
        UserHandle user = UserHandle.of(userId);
        Context contextForUser = getContextForUser(user);
        Notification n =
                new Notification.Builder(contextForUser, SystemNotificationChannels.ACCOUNT)
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
                account, authTokenType, uid), n, "android", user.getIdentifier());
    }

    private String getApplicationLabel(String packageName) {
        try {
            return mPackageManager.getApplicationLabel(
                    mPackageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private Intent newGrantCredentialsPermissionIntent(Account account, String packageName,
            int uid, AccountAuthenticatorResponse response, String authTokenType,
            boolean startInNewTask) {

        Intent intent = new Intent(mContext, GrantCredentialsPermissionActivity.class);

        if (startInNewTask) {
            // See FLAG_ACTIVITY_NEW_TASK docs for limitations and benefits of the flag.
            // Since it was set in Eclair+ we can't change it without breaking apps using
            // the intent from a non-Activity context. This is the default behavior.
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        intent.addCategory(getCredentialPermissionNotificationId(account,
                authTokenType, uid).mTag + (packageName != null ? packageName : ""));
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_ACCOUNT, account);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_AUTH_TOKEN_TYPE, authTokenType);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_RESPONSE, response);
        intent.putExtra(GrantCredentialsPermissionActivity.EXTRAS_REQUESTING_UID, uid);

        return intent;
    }

    private NotificationId getCredentialPermissionNotificationId(Account account,
            String authTokenType, int uid) {
        NotificationId nId;
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.credentialsPermissionNotificationIds) {
            final Pair<Pair<Account, String>, Integer> key =
                    new Pair<Pair<Account, String>, Integer>(
                            new Pair<Account, String>(account, authTokenType), uid);
            nId = accounts.credentialsPermissionNotificationIds.get(key);
            if (nId == null) {
                String tag = TAG + ":" + SystemMessage.NOTE_ACCOUNT_CREDENTIAL_PERMISSION
                        + ":" + account.hashCode() + ":" + authTokenType.hashCode() + ":" + uid;
                int id = SystemMessage.NOTE_ACCOUNT_CREDENTIAL_PERMISSION;
                nId = new NotificationId(tag, id);
                accounts.credentialsPermissionNotificationIds.put(key, nId);
            }
        }
        return nId;
    }

    private NotificationId getSigninRequiredNotificationId(UserAccounts accounts, Account account) {
        NotificationId nId;
        synchronized (accounts.signinRequiredNotificationIds) {
            nId = accounts.signinRequiredNotificationIds.get(account);
            if (nId == null) {
                String tag = TAG + ":" + SystemMessage.NOTE_ACCOUNT_REQUIRE_SIGNIN
                        + ":" + account.hashCode();
                int id = SystemMessage.NOTE_ACCOUNT_REQUIRE_SIGNIN;
                nId = new NotificationId(tag, id);
                accounts.signinRequiredNotificationIds.put(account, nId);
            }
        }
        return nId;
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
                    + ", requiredFeatures " + Arrays.toString(requiredFeatures)
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
                    accounts, AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_ADD, AccountsDb.TABLE_ACCOUNTS,
                    uid);
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
                            + ", requiredFeatures " + Arrays.toString(requiredFeatures);
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
                    + ", requiredFeatures " + Arrays.toString(requiredFeatures)
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid()
                    + ", for user id " + userId);
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
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
                    accounts, AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_ADD, AccountsDb.TABLE_ACCOUNTS,
                    userId);
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
                    + ", requiredFeatures " + Arrays.toString(requiredFeatures)
                    + ", expectActivityLaunch " + expectActivityLaunch
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");

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

        // Check to see if the Password should be included to the caller.
        String callerPkg = optionsIn.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        boolean isPasswordForwardingAllowed = isPermitted(
                callerPkg, uid, Manifest.permission.GET_PASSWORD);

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            logRecordWithUid(accounts, AccountsDb.DEBUG_ACTION_CALLED_START_ACCOUNT_ADD,
                    AccountsDb.TABLE_ACCOUNTS, uid);
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

            // Omit passwords if the caller isn't permitted to see them.
            if (!mIsPasswordForwardingAllowed) {
                result.remove(AccountManager.KEY_PASSWORD);
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
        Preconditions.checkArgument(response != null, "response cannot be null");
        // Session bundle is the encrypted bundle of the original bundle created by authenticator.
        // Account type is added to it before encryption.
        if (sessionBundle == null || sessionBundle.size() == 0) {
            throw new IllegalArgumentException("sessionBundle is empty");
        }

        // Only allow the system process to finish session for other users.
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(
                    String.format(
                            "User %s trying to finish session for %s without cross user permission",
                            UserHandle.getCallingUserId(),
                            userId));
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
                    AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_SESSION_FINISH,
                    AccountsDb.TABLE_ACCOUNTS,
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
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        Intent intent = null;
        if (dpmi == null) {
            intent = getDefaultCantAddAccountIntent(errorCode);
        } else if (errorCode == AccountManager.ERROR_CODE_USER_RESTRICTED) {
            intent = dpmi.createUserRestrictionSupportIntent(userId,
                    UserManager.DISALLOW_MODIFY_ACCOUNTS);
        } else if (errorCode == AccountManager.ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE) {
            intent = dpmi.createShowAdminSupportIntent(userId, false);
        }
        if (intent == null) {
            intent = getDefaultCantAddAccountIntent(errorCode);
        }
        long identityToken = clearCallingIdentity();
        try {
            mContext.startActivityAsUser(intent, new UserHandle(userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Called when we don't know precisely who is preventing us from adding an account.
     */
    private Intent getDefaultCantAddAccountIntent(int errorCode) {
        Intent cantAddAccount = new Intent(mContext, CantAddAccountActivity.class);
        cantAddAccount.putExtra(CantAddAccountActivity.EXTRA_ERROR_CODE, errorCode);
        cantAddAccount.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return cantAddAccount;
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
        if (!isAccountManagedByCaller(accountType, callingUid, userId)
                && !isSystemUid(callingUid)) {
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
    public boolean hasAccountAccess(@NonNull Account account,  @NonNull String packageName,
            @NonNull UserHandle userHandle) {
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            throw new SecurityException("Can be called only by system UID");
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkNotNull(userHandle, "userHandle cannot be null");

        final int userId = userHandle.getIdentifier();

        Preconditions.checkArgumentInRange(userId, 0, Integer.MAX_VALUE, "user must be concrete");

        try {
            int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            return hasAccountAccess(account, packageName, uid);
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return false;
        }
    }

    // Returns package with oldest target SDK for given UID.
    private String getPackageNameForUid(int uid) {
        String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (ArrayUtils.isEmpty(packageNames)) {
            return null;
        }
        String packageName = packageNames[0];
        if (packageNames.length == 1) {
            return packageName;
        }
        // Due to visibility changes we want to use package with oldest target SDK
        int oldestVersion = Integer.MAX_VALUE;
        for (String name : packageNames) {
            try {
                ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(name, 0);
                if (applicationInfo != null) {
                    int version = applicationInfo.targetSdkVersion;
                    if (version < oldestVersion) {
                        oldestVersion = version;
                        packageName = name;
                    }
                }
            } catch (NameNotFoundException e) {
                // skip
            }
        }
        return packageName;
    }

    private boolean hasAccountAccess(@NonNull Account account, @Nullable String packageName,
            int uid) {
        if (packageName == null) {
            packageName = getPackageNameForUid(uid);
            if (packageName == null) {
                return false;
            }
        }

        // Use null token which means any token. Having a token means the package
        // is trusted by the authenticator, hence it is fine to access the account.
        if (permissionIsGranted(account, null, uid, UserHandle.getUserId(uid))) {
            return true;
        }
        // In addition to the permissions required to get an auth token we also allow
        // the account to be accessed by apps for which user or authenticator granted visibility.

        int visibility = resolveAccountVisibility(account, packageName,
            getUserAccounts(UserHandle.getUserId(uid)));
        return (visibility == AccountManager.VISIBILITY_VISIBLE
            || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);
    }

    @Override
    public IntentSender createRequestAccountAccessIntentSenderAsUser(@NonNull Account account,
            @NonNull String packageName, @NonNull UserHandle userHandle) {
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            throw new SecurityException("Can be called only by system UID");
        }

        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkNotNull(userHandle, "userHandle cannot be null");

        final int userId = userHandle.getIdentifier();

        Preconditions.checkArgumentInRange(userId, 0, Integer.MAX_VALUE, "user must be concrete");

        final int uid;
        try {
            uid = mPackageManager.getPackageUidAsUser(packageName, userId);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Unknown package " + packageName);
            return null;
        }

        Intent intent = newRequestAccountAccessIntent(account, packageName, uid, null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return PendingIntent.getActivityAsUser(
                    mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    null, new UserHandle(userId)).getIntentSender();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Intent newRequestAccountAccessIntent(Account account, String packageName,
            int uid, RemoteCallback callback) {
        return newGrantCredentialsPermissionIntent(account, packageName, uid,
                new AccountAuthenticatorResponse(new IAccountAuthenticatorResponse.Stub() {
            @Override
            public void onResult(Bundle value) throws RemoteException {
                handleAuthenticatorResponse(true);
            }

            @Override
            public void onRequestContinued() {
                /* ignore */
            }

            @Override
            public void onError(int errorCode, String errorMessage) throws RemoteException {
                handleAuthenticatorResponse(false);
            }

            private void handleAuthenticatorResponse(boolean accessGranted) throws RemoteException {
                cancelNotification(getCredentialPermissionNotificationId(account,
                        AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE, uid),
                        UserHandle.getUserHandleForUid(uid));
                if (callback != null) {
                    Bundle result = new Bundle();
                    result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, accessGranted);
                    callback.sendResult(result);
                }
            }
        }), AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE, false);
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
        private final String mPackageName;
        private final boolean mIncludeManagedNotVisible;

        public GetAccountsByTypeAndFeatureSession(
                UserAccounts accounts,
                IAccountManagerResponse response,
                String type,
                String[] features,
                int callingUid,
                String packageName,
                boolean includeManagedNotVisible) {
            super(accounts, response, type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */);
            mCallingUid = callingUid;
            mFeatures = features;
            mPackageName = packageName;
            mIncludeManagedNotVisible = includeManagedNotVisible;
        }

        @Override
        public void run() throws RemoteException {
            mAccountsOfType = getAccountsFromCache(mAccounts, mAccountType,
                    mCallingUid, mPackageName, mIncludeManagedNotVisible);
            // check whether each account matches the requested features
            mAccountsWithFeatures = new ArrayList<>(mAccountsOfType.length);
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
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId,
                opPackageName);
        if (visibleAccountTypes.isEmpty()) {
            return EMPTY_ACCOUNT_ARRAY;
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsInternal(
                    accounts,
                    callingUid,
                    opPackageName,
                    visibleAccountTypes,
                    false /* includeUserManagedNotVisible */);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Returns accounts for all running users, ignores visibility values.
     *
     * @hide
     */
    @NonNull
    public AccountAndUser[] getRunningAccounts() {
        final int[] runningUserIds;
        try {
            runningUserIds = ActivityManager.getService().getRunningUserIds();
        } catch (RemoteException e) {
            // Running in system_server; should never happen
            throw new RuntimeException(e);
        }
        return getAccounts(runningUserIds);
    }

    /**
     * Returns accounts for all users, ignores visibility values.
     *
     * @hide
     */
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
            Account[] accounts = getAccountsFromCache(
                    userAccounts,
                    null /* type */,
                    Binder.getCallingUid(),
                    null /* packageName */,
                    false /* include managed not visible*/);
            for (Account account : accounts) {
                runningAccounts.add(new AccountAndUser(account, userId));
            }
        }

        AccountAndUser[] accountsArray = new AccountAndUser[runningAccounts.size()];
        return runningAccounts.toArray(accountsArray);
    }

    @Override
    @NonNull
    public Account[] getAccountsAsUser(String type, int userId, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        return getAccountsAsUserForPackage(type, userId, opPackageName /* callingPackage */, -1,
                opPackageName, false /* includeUserManagedNotVisible */);
    }

    @NonNull
    private Account[] getAccountsAsUserForPackage(
            String type,
            int userId,
            String callingPackage,
            int packageUid,
            String opPackageName,
            boolean includeUserManagedNotVisible) {
        int callingUid = Binder.getCallingUid();
        // Only allow the system process to read accounts of other users
        if (userId != UserHandle.getCallingUserId()
                && callingUid != Process.SYSTEM_UID
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

        // If the original calling app was using account choosing activity
        // provided by the framework or authenticator we'll passing in
        // the original caller's uid here, which is what should be used for filtering.
        List<String> managedTypes =
                getTypesManagedByCaller(callingUid, UserHandle.getUserId(callingUid));
        if (packageUid != -1 &&
                ((UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || (type != null && managedTypes.contains(type))))) {
            callingUid = packageUid;
            opPackageName = callingPackage;
        }
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId,
                opPackageName);
        if (visibleAccountTypes.isEmpty()
                || (type != null && !visibleAccountTypes.contains(type))) {
            return EMPTY_ACCOUNT_ARRAY;
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
                    opPackageName,
                    visibleAccountTypes,
                    includeUserManagedNotVisible);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @NonNull
    private Account[] getAccountsInternal(
            UserAccounts userAccounts,
            int callingUid,
            String callingPackage,
            List<String> visibleAccountTypes,
            boolean includeUserManagedNotVisible) {
        ArrayList<Account> visibleAccounts = new ArrayList<>();
        for (String visibleType : visibleAccountTypes) {
            Account[] accountsForType = getAccountsFromCache(
                    userAccounts, visibleType, callingUid, callingPackage,
                    includeUserManagedNotVisible);
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

    @Override
    public void addSharedAccountsFromParentUser(int parentUserId, int userId,
            String opPackageName) {
        checkManageOrCreateUsersPermission("addSharedAccountsFromParentUser");
        Account[] accounts = getAccountsAsUser(null, parentUserId, opPackageName);
        for (Account account : accounts) {
            addSharedAccountAsUser(account, userId);
        }
    }

    private boolean addSharedAccountAsUser(Account account, int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        accounts.accountsDb.deleteSharedAccount(account);
        long accountId = accounts.accountsDb.insertSharedAccount(account);
        if (accountId < 0) {
            Log.w(TAG, "insertAccountIntoDatabase: " + account
                    + ", skipping the DB insert failed");
            return false;
        }
        logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_ADD, AccountsDb.TABLE_SHARED_ACCOUNTS, accountId,
                accounts);
        return true;
    }

    @Override
    public boolean renameSharedAccountAsUser(Account account, String newName, int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        long sharedTableAccountId = accounts.accountsDb.findSharedAccountId(account);
        int r = accounts.accountsDb.renameSharedAccount(account, newName);
        if (r > 0) {
            int callingUid = getCallingUid();
            logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_RENAME, AccountsDb.TABLE_SHARED_ACCOUNTS,
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
        long sharedTableAccountId = accounts.accountsDb.findSharedAccountId(account);
        boolean deleted = accounts.accountsDb.deleteSharedAccount(account);
        if (deleted) {
            logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE, AccountsDb.TABLE_SHARED_ACCOUNTS,
                    sharedTableAccountId, accounts, callingUid);
            removeAccountInternal(accounts, account, callingUid);
        }
        return deleted;
    }

    @Override
    public Account[] getSharedAccountsAsUser(int userId) {
        userId = handleIncomingUser(userId);
        UserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.dbLock) {
            List<Account> accountList = accounts.accountsDb.getSharedAccounts();
            Account[] accountArray = new Account[accountList.size()];
            accountList.toArray(accountArray);
            return accountArray;
        }
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
        if (!UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)) {
            // Don't do opPackageName check - caller is system.
            throw new SecurityException("getAccountsForPackage() called from unauthorized uid "
                    + callingUid + " with uid=" + uid);
        }
        return getAccountsAsUserForPackage(null, UserHandle.getCallingUserId(), packageName, uid,
                opPackageName, true /* includeUserManagedNotVisible */);
    }

    @Override
    @NonNull
    public Account[] getAccountsByTypeForPackage(String type, String packageName,
            String opPackageName) {
        int callingUid =  Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        int packageUid = -1;
        try {
            packageUid = mPackageManager.getPackageUidAsUser(packageName, userId);
        } catch (NameNotFoundException re) {
            Slog.e(TAG, "Couldn't determine the packageUid for " + packageName + re);
            return EMPTY_ACCOUNT_ARRAY;
        }
        if (!UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                && (type != null && !isAccountManagedByCaller(type, callingUid, userId))) {
                return EMPTY_ACCOUNT_ARRAY;
        }
        if (!UserHandle.isSameApp(callingUid, Process.SYSTEM_UID) && type == null) {
            return getAccountsAsUserForPackage(type, userId,
                packageName, packageUid, opPackageName, false /* includeUserManagedNotVisible */);
        }
        return getAccountsAsUserForPackage(type, userId,
                packageName, packageUid, opPackageName, true /* includeUserManagedNotVisible */);
    }

    private boolean needToStartChooseAccountActivity(Account[] accounts, String callingPackage) {
        if (accounts.length < 1) return false;
        if (accounts.length > 1) return true;
        Account account = accounts[0];
        UserAccounts userAccounts = getUserAccounts(UserHandle.getCallingUserId());
        int visibility = resolveAccountVisibility(account, callingPackage, userAccounts);
        if (visibility == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) return true;
        return false;
    }

    private void startChooseAccountActivityWithAccounts(
        IAccountManagerResponse response, Account[] accounts, String callingPackage) {
        Intent intent = new Intent(mContext, ChooseAccountActivity.class);
        intent.putExtra(AccountManager.KEY_ACCOUNTS, accounts);
        intent.putExtra(AccountManager.KEY_ACCOUNT_MANAGER_RESPONSE,
                new AccountManagerResponse(response));
        intent.putExtra(AccountManager.KEY_ANDROID_PACKAGE_NAME, callingPackage);

        mContext.startActivityAsUser(intent, UserHandle.of(UserHandle.getCallingUserId()));
    }

    private void handleGetAccountsResult(
        IAccountManagerResponse response,
        Account[] accounts,
        String callingPackage) {

        if (needToStartChooseAccountActivity(accounts, callingPackage)) {
            startChooseAccountActivityWithAccounts(response, accounts, callingPackage);
            return;
        }
        if (accounts.length == 1) {
            Bundle bundle = new Bundle();
            bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accounts[0].name);
            bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, accounts[0].type);
            onResult(response, bundle);
            return;
        }
        // No qualified account exists, return an empty Bundle.
        onResult(response, new Bundle());
    }

    @Override
    public void getAccountByTypeAndFeatures(
        IAccountManagerResponse response,
        String accountType,
        String[] features,
        String opPackageName) {

        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAccount: accountType " + accountType
                    + ", response " + response
                    + ", features " + Arrays.toString(features)
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        int userId = UserHandle.getCallingUserId();

        long identityToken = clearCallingIdentity();
        try {
            UserAccounts userAccounts = getUserAccounts(userId);
            if (ArrayUtils.isEmpty(features)) {
                Account[] accountsWithManagedNotVisible = getAccountsFromCache(
                    userAccounts, accountType, callingUid, opPackageName,
                    true /* include managed not visible */);
                handleGetAccountsResult(
                    response, accountsWithManagedNotVisible, opPackageName);
                return;
            }

            IAccountManagerResponse retrieveAccountsResponse =
                new IAccountManagerResponse.Stub() {
                @Override
                public void onResult(Bundle value) throws RemoteException {
                    Parcelable[] parcelables = value.getParcelableArray(
                        AccountManager.KEY_ACCOUNTS);
                    Account[] accounts = new Account[parcelables.length];
                    for (int i = 0; i < parcelables.length; i++) {
                        accounts[i] = (Account) parcelables[i];
                    }
                    handleGetAccountsResult(
                        response, accounts, opPackageName);
                }

                @Override
                public void onError(int errorCode, String errorMessage)
                        throws RemoteException {
                    // Will not be called in this case.
                }
            };
            new GetAccountsByTypeAndFeatureSession(
                    userAccounts,
                    retrieveAccountsResponse,
                    accountType,
                    features,
                    callingUid,
                    opPackageName,
                    true /* include managed not visible */).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void getAccountsByFeatures(
            IAccountManagerResponse response,
            String type,
            String[] features,
            String opPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getAccounts: accountType " + type
                    + ", response " + response
                    + ", features " + Arrays.toString(features)
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
            result.putParcelableArray(AccountManager.KEY_ACCOUNTS, EMPTY_ACCOUNT_ARRAY);
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
                Account[] accounts = getAccountsFromCache(userAccounts, type, callingUid,
                        opPackageName, false);
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
                    callingUid,
                    opPackageName,
                    false /* include managed not visible */).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void onAccountAccessed(String token) throws RemoteException {
        final int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) == Process.SYSTEM_UID) {
            return;
        }
        final int userId = UserHandle.getCallingUserId();
        final long identity = Binder.clearCallingIdentity();
        try {
            for (Account account : getAccounts(userId, mContext.getOpPackageName())) {
                if (Objects.equals(account.getAccessId(), token)) {
                    // An app just accessed the account. At this point it knows about
                    // it and there is not need to hide this account from the app.
                    // Do we need to update account visibility here?
                    if (!hasAccountAccess(account, null, uid)) {
                        updateAppPermission(account, AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE,
                                uid, true);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
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
         * into launching arbitrary intents on the device via by tricking to click authenticator
         * supplied entries in the system Settings app.
         */
        protected void checkKeyIntent(
                int authUid,
                Intent intent) throws SecurityException {
            intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            long bid = Binder.clearCallingIdentity();
            try {
                PackageManager pm = mContext.getPackageManager();
                ResolveInfo resolveInfo = pm.resolveActivityAsUser(intent, 0, mAccounts.userId);
                ActivityInfo targetActivityInfo = resolveInfo.activityInfo;
                int targetUid = targetActivityInfo.applicationInfo.uid;
                if (!isExportedSystemActivity(targetActivityInfo)
                        && (PackageManager.SIGNATURE_MATCH != pm.checkSignatures(authUid,
                                targetUid))) {
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

        private boolean isExportedSystemActivity(ActivityInfo activityInfo) {
            String className = activityInfo.name;
            return "android".equals(activityInfo.packageName) &&
                    (GrantCredentialsPermissionActivity.class.getName().equals(className)
                    || CantAddAccountActivity.class.getName().equals(className));
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
            mHandler.removeMessages(MESSAGE_TIMED_OUT, this);
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
                            lastAuthenticatedTime = mAccounts.accountsDb
                                    .findAccountLastAuthenticatedTime(
                                            new Account(mAccountName, mAccountType));
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

    class MessageHandler extends Handler {
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

    private void logRecord(UserAccounts accounts, String action, String tableName) {
        logRecord(action, tableName, -1, accounts);
    }

    private void logRecordWithUid(UserAccounts accounts, String action, String tableName, int uid) {
        logRecord(action, tableName, -1, accounts, uid);
    }

    /*
     * This function receives an opened writable database.
     */
    private void logRecord(String action, String tableName, long accountId,
            UserAccounts userAccount) {
        logRecord(action, tableName, accountId, userAccount, getCallingUid());
    }

    /*
     * This function receives an opened writable database and writes to it in a separate thread.
     */
    private void logRecord(String action, String tableName, long accountId,
            UserAccounts userAccount, int callingUid) {

        class LogRecordTask implements Runnable {
            private final String action;
            private final String tableName;
            private final long accountId;
            private final UserAccounts userAccount;
            private final int callingUid;
            private final long userDebugDbInsertionPoint;

            LogRecordTask(final String action,
                    final String tableName,
                    final long accountId,
                    final UserAccounts userAccount,
                    final int callingUid,
                    final long userDebugDbInsertionPoint) {
                this.action = action;
                this.tableName = tableName;
                this.accountId = accountId;
                this.userAccount = userAccount;
                this.callingUid = callingUid;
                this.userDebugDbInsertionPoint = userDebugDbInsertionPoint;
            }

            @Override
            public void run() {
                SQLiteStatement logStatement = userAccount.statementForLogging;
                logStatement.bindLong(1, accountId);
                logStatement.bindString(2, action);
                logStatement.bindString(3, mDateFormat.format(new Date()));
                logStatement.bindLong(4, callingUid);
                logStatement.bindString(5, tableName);
                logStatement.bindLong(6, userDebugDbInsertionPoint);
                logStatement.execute();
                logStatement.clearBindings();
            }
        }

        LogRecordTask logTask = new LogRecordTask(action, tableName, accountId, userAccount,
                callingUid, userAccount.debugDbInsertionPoint);
        userAccount.debugDbInsertionPoint = (userAccount.debugDbInsertionPoint + 1)
                % AccountsDb.MAX_DEBUG_DB_SIZE;
        mHandler.post(logTask);
    }

    /*
     * This should only be called once to compile the sql statement for logging
     * and to find the insertion point.
     */
    private void initializeDebugDbSizeAndCompileSqlStatementForLogging(UserAccounts userAccount) {
        userAccount.debugDbInsertionPoint = userAccount.accountsDb
                .calculateDebugTableInsertionPoint();
        userAccount.statementForLogging = userAccount.accountsDb.compileSqlStatementForLogging();
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
        if (!DumpUtils.checkDumpPermission(mContext, TAG, fout)) return;
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
        if (isCheckinRequest) {
            // This is a checkin request. *Only* upload the account types and the count of
            // each.
            synchronized (userAccounts.dbLock) {
                userAccounts.accountsDb.dumpDeAccountsTable(fout);
            }
        } else {
            Account[] accounts = getAccountsFromCache(userAccounts, null /* type */,
                    Process.SYSTEM_UID, null /* packageName */, false);
            fout.println("Accounts: " + accounts.length);
            for (Account account : accounts) {
                fout.println("  " + account);
            }

            // Add debug information.
            fout.println();
            synchronized (userAccounts.dbLock) {
                userAccounts.accountsDb.dumpDebugTable(fout);
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

            boolean isUserUnlocked;
            synchronized (mUsers) {
                isUserUnlocked = isLocalUnlockedUser(userAccounts.userId);
            }
            // Following logs are printed only when user is unlocked.
            if (!isUserUnlocked) {
                return;
            }
            fout.println();
            synchronized (userAccounts.dbLock) {
                Map<Account, Map<String, Integer>> allVisibilityValues =
                        userAccounts.accountsDb.findAllVisibilityValues();
                fout.println("Account visibility:");
                for (Account account : allVisibilityValues.keySet()) {
                    fout.println("  " + account.name);
                    Map<String, Integer> visibilities = allVisibilityValues.get(account);
                    for (Entry<String, Integer> entry : visibilities.entrySet()) {
                        fout.println("    " + entry.getKey() + ", " + entry.getValue());
                    }
                }
            }
        }
    }

    private void doNotification(UserAccounts accounts, Account account, CharSequence message,
            Intent intent, String packageName, final int userId) {
        long identityToken = clearCallingIdentity();
        try {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "doNotification: " + message + " intent:" + intent);
            }

            if (intent.getComponent() != null &&
                    GrantCredentialsPermissionActivity.class.getName().equals(
                            intent.getComponent().getClassName())) {
                createNoCredentialsPermissionNotification(account, intent, packageName, userId);
            } else {
                Context contextForUser = getContextForUser(new UserHandle(userId));
                final NotificationId id = getSigninRequiredNotificationId(accounts, account);
                intent.addCategory(id.mTag);

                final String notificationTitleFormat =
                        contextForUser.getText(R.string.notification_title).toString();
                Notification n =
                        new Notification.Builder(contextForUser, SystemNotificationChannels.ACCOUNT)
                        .setWhen(0)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setColor(contextForUser.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(String.format(notificationTitleFormat, account.name))
                        .setContentText(message)
                        .setContentIntent(PendingIntent.getActivityAsUser(
                                mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT,
                                null, new UserHandle(userId)))
                        .build();
                installNotification(id, n, packageName, userId);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void installNotification(NotificationId id, final Notification notification,
            String packageName, int userId) {
        final long token = clearCallingIdentity();
        try {
            INotificationManager notificationManager = mInjector.getNotificationManager();
            try {
                notificationManager.enqueueNotificationWithTag(packageName, packageName,
                        id.mTag, id.mId, notification, userId);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void cancelNotification(NotificationId id, UserHandle user) {
        cancelNotification(id, mContext.getPackageName(), user);
    }

    private void cancelNotification(NotificationId id, String packageName, UserHandle user) {
        long identityToken = clearCallingIdentity();
        try {
            INotificationManager service = mInjector.getNotificationManager();
            service.cancelNotificationWithTag(packageName, id.mTag, id.mId, user.getIdentifier());
        } catch (RemoteException e) {
            /* ignore - local call */
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean isPermittedForPackage(String packageName, int uid, int userId,
            String... permissions) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            for (String perm : permissions) {
                if (pm.checkPermission(perm, packageName, userId)
                        == PackageManager.PERMISSION_GRANTED) {
                    // Checks runtime permission revocation.
                    final int opCode = AppOpsManager.permissionToOpCode(perm);
                    if (opCode == AppOpsManager.OP_NONE || mAppOpsManager.noteOpNoThrow(
                            opCode, uid, packageName) == AppOpsManager.MODE_ALLOWED) {
                        return true;
                    }
                }
            }
        } catch (RemoteException e) {
            /* ignore - local call */
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    private boolean isPermitted(String opPackageName, int callingUid, String... permissions) {
        for (String perm : permissions) {
            if (mContext.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "  caller uid " + callingUid + " has " + perm);
                }
                final int opCode = AppOpsManager.permissionToOpCode(perm);
                if (opCode == AppOpsManager.OP_NONE || mAppOpsManager.noteOpNoThrow(
                        opCode, callingUid, opPackageName) == AppOpsManager.MODE_ALLOWED) {
                    return true;
                }
            }
        }
        return false;
    }

    private int handleIncomingUser(int userId) {
        try {
            return ActivityManager.getService().handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "", null);
        } catch (RemoteException re) {
            // Shouldn't happen, local.
        }
        return userId;
    }

    private boolean isPrivileged(int callingUid) {
        String[] packages;
        long identityToken = Binder.clearCallingIdentity();
        try {
            packages = mPackageManager.getPackagesForUid(callingUid);
            if (packages == null) {
                Log.d(TAG, "No packages for callingUid " + callingUid);
                return false;
            }
            for (String name : packages) {
                try {
                    PackageInfo packageInfo =
                        mPackageManager.getPackageInfo(name, 0 /* flags */);
                    if (packageInfo != null
                        && (packageInfo.applicationInfo.privateFlags
                            & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "Package not found " + e.getMessage());
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        return false;
    }

    private boolean permissionIsGranted(
            Account account, String authTokenType, int callerUid, int userId) {
        if (UserHandle.getAppId(callerUid) == Process.SYSTEM_UID) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Access to " + account + " granted calling uid is system");
            }
            return true;
        }

        if (isPrivileged(callerUid)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Access to " + account + " granted calling uid "
                        + callerUid + " privileged");
            }
            return true;
        }
        if (account != null && isAccountManagedByCaller(account.type, callerUid, userId)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Access to " + account + " granted calling uid "
                        + callerUid + " manages the account");
            }
            return true;
        }
        if (account != null && hasExplicitlyGrantedPermission(account, authTokenType, callerUid)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Access to " + account + " granted calling uid "
                        + callerUid + " user granted access");
            }
            return true;
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Access to " + account + " not granted for uid " + callerUid);
        }

        return false;
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

    // Method checks visibility for applications targeing API level below {@link
    // android.os.Build.VERSION_CODES#O},
    // returns true if the the app has GET_ACCOUNTS or GET_ACCOUNTS_PRIVILEGED permission.
    private boolean checkGetAccountsPermission(String packageName, int uid, int userId) {
        return isPermittedForPackage(packageName, uid, userId, Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.GET_ACCOUNTS_PRIVILEGED);
    }

    private boolean checkReadContactsPermission(String packageName, int uid, int userId) {
        return isPermittedForPackage(packageName, uid, userId, Manifest.permission.READ_CONTACTS);
    }

    // Heuristic to check that account type may be associated with some contacts data and
    // therefore READ_CONTACTS permission grants the access to account by default.
    private boolean accountTypeManagesContacts(String accountType, int userId) {
        if (accountType == null) {
            return false;
        }
        long identityToken = Binder.clearCallingIdentity();
        Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos;
        try {
            serviceInfos = mAuthenticatorCache.getAllServices(userId);
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        // Check contacts related permissions for authenticator.
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo
                : serviceInfos) {
            if (accountType.equals(serviceInfo.type.type)) {
                return isPermittedForPackage(serviceInfo.type.packageName, serviceInfo.uid, userId,
                    Manifest.permission.WRITE_CONTACTS);
            }
        }
        return false;
    }

    /**
     * Method checks package uid and signature with Authenticator which manages accountType.
     *
     * @return SIGNATURE_CHECK_UID_MATCH for uid match, SIGNATURE_CHECK_MATCH for signature match,
     *         SIGNATURE_CHECK_MISMATCH otherwise.
     */
    private int checkPackageSignature(String accountType, int callingUid, int userId) {
        if (accountType == null) {
            return SIGNATURE_CHECK_MISMATCH;
        }

        long identityToken = Binder.clearCallingIdentity();
        Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos;
        try {
            serviceInfos = mAuthenticatorCache.getAllServices(userId);
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        // Check for signature match with Authenticator.
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo
                : serviceInfos) {
            if (accountType.equals(serviceInfo.type.type)) {
                if (serviceInfo.uid == callingUid) {
                    return SIGNATURE_CHECK_UID_MATCH;
                }
                final int sigChk = mPackageManager.checkSignatures(serviceInfo.uid, callingUid);
                if (sigChk == PackageManager.SIGNATURE_MATCH) {
                    return SIGNATURE_CHECK_MATCH;
                }
            }
        }
        return SIGNATURE_CHECK_MISMATCH;
    }

    // returns true for applications with the same signature as authenticator.
    private boolean isAccountManagedByCaller(String accountType, int callingUid, int userId) {
        if (accountType == null) {
            return false;
        } else {
            return getTypesManagedByCaller(callingUid, userId).contains(accountType);
        }
    }

    private List<String> getTypesVisibleToCaller(int callingUid, int userId,
            String opPackageName) {
        return getTypesForCaller(callingUid, userId, true /* isOtherwisePermitted*/);
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
            if (isOtherwisePermitted || (mPackageManager.checkSignatures(serviceInfo.uid,
                    callingUid) == PackageManager.SIGNATURE_MATCH)) {
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
        if (UserHandle.getAppId(callerUid) == Process.SYSTEM_UID) {
            return true;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(callerUid));
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                long grantsCount;
                if (authTokenType != null) {
                    grantsCount = accounts.accountsDb
                            .findMatchingGrantsCount(callerUid, authTokenType, account);
                } else {
                    grantsCount = accounts.accountsDb.findMatchingGrantsCountAnyToken(callerUid,
                            account);
                }
                final boolean permissionGranted = grantsCount > 0;

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
                } catch (NameNotFoundException e) {
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

        if (UserHandle.getAppId(callingUid) != Process.SYSTEM_UID) {
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
    void grantAppPermission(Account account, String authTokenType, int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "grantAppPermission: called with invalid arguments", new Exception());
            return;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                long accountId = accounts.accountsDb.findDeAccountId(account);
                if (accountId >= 0) {
                    accounts.accountsDb.insertGrant(accountId, authTokenType, uid);
                }
                cancelNotification(
                        getCredentialPermissionNotificationId(account, authTokenType, uid),
                        UserHandle.of(accounts.userId));

                cancelAccountAccessRequestNotificationIfNeeded(account, uid, true);
            }
        }

        // Listeners are a final CopyOnWriteArrayList, hence no lock needed.
        for (AccountManagerInternal.OnAppPermissionChangeListener listener
                : mAppPermissionChangeListeners) {
            mHandler.post(() -> listener.onAppPermissionChanged(account, uid));
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
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    long accountId = accounts.accountsDb.findDeAccountId(account);
                    if (accountId >= 0) {
                        accounts.accountsDb.deleteGrantsByAccountIdAuthTokenTypeAndUid(
                                accountId, authTokenType, uid);
                        accounts.accountsDb.setTransactionSuccessful();
                    }
                } finally {
                    accounts.accountsDb.endTransaction();
                }

                cancelNotification(
                        getCredentialPermissionNotificationId(account, authTokenType, uid),
                        UserHandle.of(accounts.userId));
            }
        }

        // Listeners are a final CopyOnWriteArrayList, hence no lock needed.
        for (AccountManagerInternal.OnAppPermissionChangeListener listener
                : mAppPermissionChangeListeners) {
            mHandler.post(() -> listener.onAppPermissionChanged(account, uid));
        }
    }

    private void removeAccountFromCacheLocked(UserAccounts accounts, Account account) {
        final Account[] oldAccountsForType = accounts.accountCache.get(account.type);
        if (oldAccountsForType != null) {
            ArrayList<Account> newAccountsList = new ArrayList<>();
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
        accounts.visibilityCache.remove(account);
    }

    /**
     * This assumes that the caller has already checked that the account is not already present.
     * IMPORTANT: The account being inserted will begin to be tracked for access in remote
     * processes and if you will return this account to apps you should return the result.
     * @return The inserted account which is a new instance that is being tracked.
     */
    private Account insertAccountIntoCacheLocked(UserAccounts accounts, Account account) {
        Account[] accountsForType = accounts.accountCache.get(account.type);
        int oldLength = (accountsForType != null) ? accountsForType.length : 0;
        Account[] newAccountsForType = new Account[oldLength + 1];
        if (accountsForType != null) {
            System.arraycopy(accountsForType, 0, newAccountsForType, 0, oldLength);
        }
        String token = account.getAccessId() != null ? account.getAccessId()
                : UUID.randomUUID().toString();
        newAccountsForType[oldLength] = new Account(account, token);
        accounts.accountCache.put(account.type, newAccountsForType);
        return newAccountsForType[oldLength];
    }

    @NonNull
    private Account[] filterAccounts(UserAccounts accounts, Account[] unfiltered, int callingUid,
            @Nullable String callingPackage, boolean includeManagedNotVisible) {
        String visibilityFilterPackage = callingPackage;
        if (visibilityFilterPackage == null) {
            visibilityFilterPackage = getPackageNameForUid(callingUid);
        }
        Map<Account, Integer> firstPass = new LinkedHashMap<>();
        for (Account account : unfiltered) {
            int visibility = resolveAccountVisibility(account, visibilityFilterPackage, accounts);
            if ((visibility == AccountManager.VISIBILITY_VISIBLE
                    || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE)
                    || (includeManagedNotVisible
                            && (visibility
                                    == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE))) {
                firstPass.put(account, visibility);
            }
        }
        Map<Account, Integer> secondPass =
                filterSharedAccounts(accounts, firstPass, callingUid, callingPackage);

        Account[] filtered = new Account[secondPass.size()];
        filtered = secondPass.keySet().toArray(filtered);
        return filtered;
    }

    @NonNull
    private Map<Account, Integer> filterSharedAccounts(UserAccounts userAccounts,
            @NonNull Map<Account, Integer> unfiltered, int callingUid,
            @Nullable String callingPackage) {
        // first part is to filter shared accounts.
        // unfiltered type check is not necessary.
        if (getUserManager() == null || userAccounts == null || userAccounts.userId < 0
                || callingUid == Process.SYSTEM_UID) {
            return unfiltered;
        }
        UserInfo user = getUserManager().getUserInfo(userAccounts.userId);
        if (user != null && user.isRestricted()) {
            String[] packages = mPackageManager.getPackagesForUid(callingUid);
            if (packages == null) {
                packages = new String[] {};
            }
            // If any of the packages is a visible listed package, return the full set,
            // otherwise return non-shared accounts only.
            // This might be a temporary way to specify a visible list
            String visibleList = mContext.getResources().getString(
                    com.android.internal.R.string.config_appsAuthorizedForSharedAccounts);
            for (String packageName : packages) {
                if (visibleList.contains(";" + packageName + ";")) {
                    return unfiltered;
                }
            }
            Account[] sharedAccounts = getSharedAccountsAsUser(userAccounts.userId);
            if (ArrayUtils.isEmpty(sharedAccounts)) {
                return unfiltered;
            }
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
            } catch (NameNotFoundException e) {
                Log.d(TAG, "Package not found " + e.getMessage());
            }
            Map<Account, Integer> filtered = new LinkedHashMap<>();
            for (Map.Entry<Account, Integer> entry : unfiltered.entrySet()) {
                Account account = entry.getKey();
                if (account.type.equals(requiredAccountType)) {
                    filtered.put(account, entry.getValue());
                } else {
                    boolean found = false;
                    for (Account shared : sharedAccounts) {
                        if (shared.equals(account)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        filtered.put(account, entry.getValue());
                    }
                }
            }
            return filtered;
        } else {
            return unfiltered;
        }
    }

    /*
     * packageName can be null. If not null, it should be used to filter out restricted accounts
     * that the package is not allowed to access.
     *
     * <p>The method shouldn't be called with UserAccounts#cacheLock held, otherwise it will cause a
     * deadlock
     */
    @NonNull
    protected Account[] getAccountsFromCache(UserAccounts userAccounts, String accountType,
            int callingUid, @Nullable String callingPackage, boolean includeManagedNotVisible) {
        Preconditions.checkState(!Thread.holdsLock(userAccounts.cacheLock),
                "Method should not be called with cacheLock");
        if (accountType != null) {
            Account[] accounts;
            synchronized (userAccounts.cacheLock) {
                accounts = userAccounts.accountCache.get(accountType);
            }
            if (accounts == null) {
                return EMPTY_ACCOUNT_ARRAY;
            } else {
                return filterAccounts(userAccounts, Arrays.copyOf(accounts, accounts.length),
                        callingUid, callingPackage, includeManagedNotVisible);
            }
        } else {
            int totalLength = 0;
            Account[] accountsArray;
            synchronized (userAccounts.cacheLock) {
                for (Account[] accounts : userAccounts.accountCache.values()) {
                    totalLength += accounts.length;
                }
                if (totalLength == 0) {
                    return EMPTY_ACCOUNT_ARRAY;
                }
                accountsArray = new Account[totalLength];
                totalLength = 0;
                for (Account[] accountsOfType : userAccounts.accountCache.values()) {
                    System.arraycopy(accountsOfType, 0, accountsArray, totalLength,
                            accountsOfType.length);
                    totalLength += accountsOfType.length;
                }
            }
            return filterAccounts(userAccounts, accountsArray, callingUid, callingPackage,
                    includeManagedNotVisible);
        }
    }

    /** protected by the {@code dbLock}, {@code cacheLock} */
    protected void writeUserDataIntoCacheLocked(UserAccounts accounts,
            Account account, String key, String value) {
        Map<String, String> userDataForAccount = accounts.userDataCache.get(account);
        if (userDataForAccount == null) {
            userDataForAccount = accounts.accountsDb.findUserExtrasForAccount(account);
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

    /** protected by the {@code dbLock}, {@code cacheLock} */
    protected void writeAuthTokenIntoCacheLocked(UserAccounts accounts,
            Account account, String key, String value) {
        Map<String, String> authTokensForAccount = accounts.authTokenCache.get(account);
        if (authTokensForAccount == null) {
            authTokensForAccount = accounts.accountsDb.findAuthTokensByAccount(account);
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
        // Fast path - check if account is already cached
        synchronized (accounts.cacheLock) {
            Map<String, String> authTokensForAccount = accounts.authTokenCache.get(account);
            if (authTokensForAccount != null) {
                return authTokensForAccount.get(authTokenType);
            }
        }
        // If not cached yet - do slow path and sync with db if necessary
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                Map<String, String> authTokensForAccount = accounts.authTokenCache.get(account);
                if (authTokensForAccount == null) {
                    // need to populate the cache for this account
                    authTokensForAccount = accounts.accountsDb.findAuthTokensByAccount(account);
                    accounts.authTokenCache.put(account, authTokensForAccount);
                }
                return authTokensForAccount.get(authTokenType);
            }
        }
    }

    private String readUserDataInternal(UserAccounts accounts, Account account, String key) {
        Map<String, String> userDataForAccount;
        // Fast path - check if data is already cached
        synchronized (accounts.cacheLock) {
            userDataForAccount = accounts.userDataCache.get(account);
        }
        // If not cached yet - do slow path and sync with db if necessary
        if (userDataForAccount == null) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    userDataForAccount = accounts.userDataCache.get(account);
                    if (userDataForAccount == null) {
                        // need to populate the cache for this account
                        userDataForAccount = accounts.accountsDb.findUserExtrasForAccount(account);
                        accounts.userDataCache.put(account, userDataForAccount);
                    }
                }
            }
        }
        return userDataForAccount.get(key);
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

    private final class AccountManagerInternalImpl extends AccountManagerInternal {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private AccountManagerBackupHelper mBackupHelper;

        @Override
        public void requestAccountAccess(@NonNull Account account, @NonNull String packageName,
                @IntRange(from = 0) int userId, @NonNull RemoteCallback callback) {
            if (account == null) {
                Slog.w(TAG, "account cannot be null");
                return;
            }
            if (packageName == null) {
                Slog.w(TAG, "packageName cannot be null");
                return;
            }
            if (userId < UserHandle.USER_SYSTEM) {
                Slog.w(TAG, "user id must be concrete");
                return;
            }
            if (callback == null) {
                Slog.w(TAG, "callback cannot be null");
                return;
            }

            int visibility =
                resolveAccountVisibility(account, packageName, getUserAccounts(userId));
            if (visibility == AccountManager.VISIBILITY_NOT_VISIBLE) {
                Slog.w(TAG, "requestAccountAccess: account is hidden");
                return;
            }

            if (AccountManagerService.this.hasAccountAccess(account, packageName,
                    new UserHandle(userId))) {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
                callback.sendResult(result);
                return;
            }

            final int uid;
            try {
                uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "Unknown package " + packageName);
                return;
            }

            Intent intent = newRequestAccountAccessIntent(account, packageName, uid, callback);
            final UserAccounts userAccounts;
            synchronized (mUsers) {
                userAccounts = mUsers.get(userId);
            }
            SystemNotificationChannels.createAccountChannelForPackage(packageName, uid, mContext);
            doNotification(userAccounts, account, null, intent, packageName, userId);
        }

        @Override
        public void addOnAppPermissionChangeListener(OnAppPermissionChangeListener listener) {
            // Listeners are a final CopyOnWriteArrayList, hence no lock needed.
            mAppPermissionChangeListeners.add(listener);
        }

        @Override
        public boolean hasAccountAccess(@NonNull Account account, @IntRange(from = 0) int uid) {
            return AccountManagerService.this.hasAccountAccess(account, null, uid);
        }

        @Override
        public byte[] backupAccountAccessPermissions(int userId) {
            synchronized (mLock) {
                if (mBackupHelper == null) {
                    mBackupHelper = new AccountManagerBackupHelper(
                            AccountManagerService.this, this);
                }
                return mBackupHelper.backupAccountAccessPermissions(userId);
            }
        }

        @Override
        public void restoreAccountAccessPermissions(byte[] data, int userId) {
            synchronized (mLock) {
                if (mBackupHelper == null) {
                    mBackupHelper = new AccountManagerBackupHelper(
                            AccountManagerService.this, this);
                }
                mBackupHelper.restoreAccountAccessPermissions(data, userId);
            }
        }
    }

    @VisibleForTesting
    static class Injector {
        private final Context mContext;

        public Injector(Context context) {
            mContext = context;
        }

        Looper getMessageHandlerLooper() {
            ServiceThread serviceThread = new ServiceThread(TAG,
                    android.os.Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            serviceThread.start();
            return serviceThread.getLooper();
        }

        Context getContext() {
            return mContext;
        }

        void addLocalService(AccountManagerInternal service) {
            LocalServices.addService(AccountManagerInternal.class, service);
        }

        String getDeDatabaseName(int userId) {
            File databaseFile = new File(Environment.getDataSystemDeDirectory(userId),
                    AccountsDb.DE_DATABASE_NAME);
            return databaseFile.getPath();
        }

        String getCeDatabaseName(int userId) {
            File databaseFile = new File(Environment.getDataSystemCeDirectory(userId),
                    AccountsDb.CE_DATABASE_NAME);
            return databaseFile.getPath();
        }

        String getPreNDatabaseName(int userId) {
            File systemDir = Environment.getDataSystemDirectory();
            File databaseFile = new File(Environment.getUserSystemDirectory(userId),
                    PRE_N_DATABASE_NAME);
            if (userId == 0) {
                // Migrate old file, if it exists, to the new location.
                // Make sure the new file doesn't already exist. A dummy file could have been
                // accidentally created in the old location,
                // causing the new one to become corrupted as well.
                File oldFile = new File(systemDir, PRE_N_DATABASE_NAME);
                if (oldFile.exists() && !databaseFile.exists()) {
                    // Check for use directory; create if it doesn't exist, else renameTo will fail
                    File userDir = Environment.getUserSystemDirectory(userId);
                    if (!userDir.exists()) {
                        if (!userDir.mkdirs()) {
                            throw new IllegalStateException(
                                    "User dir cannot be created: " + userDir);
                        }
                    }
                    if (!oldFile.renameTo(databaseFile)) {
                        throw new IllegalStateException(
                                "User dir cannot be migrated: " + databaseFile);
                    }
                }
            }
            return databaseFile.getPath();
        }

        IAccountAuthenticatorCache getAccountAuthenticatorCache() {
            return new AccountAuthenticatorCache(mContext);
        }

        INotificationManager getNotificationManager() {
            return NotificationManager.getService();
        }
    }

    private static class NotificationId {
        final String mTag;
        private final int mId;

        NotificationId(String tag, int type) {
            mTag = tag;
            mId = type;
        }
    }
}
