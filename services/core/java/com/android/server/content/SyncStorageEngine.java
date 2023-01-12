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

package com.android.server.content;

import static com.android.server.content.SyncLogger.logSafe;

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountManager;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.backup.BackupManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentResolver.SyncExemption;
import android.content.Context;
import android.content.ISyncStatusObserver;
import android.content.PeriodicSync;
import android.content.SyncInfo;
import android.content.SyncRequest;
import android.content.SyncStatusInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IntPair;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

/**
 * Singleton that tracks the sync data and overall sync
 * history on the device.
 *
 * @hide
 */
public class SyncStorageEngine {

    private static final String TAG = "SyncManager";
    private static final String TAG_FILE = "SyncManagerFile";

    private static final String XML_ATTR_NEXT_AUTHORITY_ID = "nextAuthorityId";
    private static final String XML_ATTR_LISTEN_FOR_TICKLES = "listen-for-tickles";
    private static final String XML_ATTR_SYNC_RANDOM_OFFSET = "offsetInSeconds";
    private static final String XML_ATTR_ENABLED = "enabled";
    private static final String XML_ATTR_USER = "user";
    private static final String XML_TAG_LISTEN_FOR_TICKLES = "listenForTickles";

    /** Default time for a periodic sync. */
    private static final long DEFAULT_POLL_FREQUENCY_SECONDS = 60 * 60 * 24; // One day

    /** Percentage of period that is flex by default, if no flexMillis is set. */
    private static final double DEFAULT_FLEX_PERCENT_SYNC = 0.04;

    /** Lower bound on sync time from which we assign a default flex time. */
    private static final long DEFAULT_MIN_FLEX_ALLOWED_SECS = 5;

    @VisibleForTesting
    static final long MILLIS_IN_4WEEKS = 1000L * 60 * 60 * 24 * 7 * 4;

    /** Enum value for a sync start event. */
    public static final int EVENT_START = 0;

    /** Enum value for a sync stop event. */
    public static final int EVENT_STOP = 1;

    /** Enum value for a sync with other sources. */
    public static final int SOURCE_OTHER = 0;

    /** Enum value for a local-initiated sync. */
    public static final int SOURCE_LOCAL = 1;

    /** Enum value for a poll-based sync (e.g., upon connection to network) */
    public static final int SOURCE_POLL = 2;

    /** Enum value for a user-initiated sync. */
    public static final int SOURCE_USER = 3;

    /** Enum value for a periodic sync. */
    public static final int SOURCE_PERIODIC = 4;

    /** Enum a sync with a "feed" extra */
    public static final int SOURCE_FEED = 5;

    public static final long NOT_IN_BACKOFF_MODE = -1;

    /**
     * String names for the sync source types.
     *
     * KEEP THIS AND {@link SyncStatusInfo}.SOURCE_COUNT IN SYNC.
     */
    public static final String[] SOURCES = {
            "OTHER",
            "LOCAL",
            "POLL",
            "USER",
            "PERIODIC",
            "FEED"};

    // The MESG column will contain one of these or one of the Error types.
    public static final String MESG_SUCCESS = "success";
    public static final String MESG_CANCELED = "canceled";

    public static final int MAX_HISTORY = 100;

    private static final int MSG_WRITE_STATUS = 1;
    private static final long WRITE_STATUS_DELAY = 1000*60*10; // 10 minutes

    private static final int MSG_WRITE_STATISTICS = 2;
    private static final long WRITE_STATISTICS_DELAY = 1000*60*30; // 1/2 hour

    private static final boolean SYNC_ENABLED_DEFAULT = false;

    // the version of the accounts xml file format
    private static final int ACCOUNTS_VERSION = 3;

    private static HashMap<String, String> sAuthorityRenames;
    private static PeriodicSyncAddedListener mPeriodicSyncAddedListener;

    private final PackageManagerInternal mPackageManagerInternal;

    private volatile boolean mIsClockValid;

    static {
        sAuthorityRenames = new HashMap<String, String>();
        sAuthorityRenames.put("contacts", "com.android.contacts");
        sAuthorityRenames.put("calendar", "com.android.calendar");
    }

    static class AccountInfo {
        final AccountAndUser accountAndUser;
        final HashMap<String, AuthorityInfo> authorities =
                new HashMap<String, AuthorityInfo>();

        AccountInfo(AccountAndUser accountAndUser) {
            this.accountAndUser = accountAndUser;
        }
    }

    /**  Bare bones representation of a sync target. */
    public static class EndPoint {
        public final static EndPoint USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL =
                new EndPoint(null, null, UserHandle.USER_ALL);
        final Account account;
        final int userId;
        final String provider;

        public EndPoint(Account account, String provider, int userId) {
            this.account = account;
            this.provider = provider;
            this.userId = userId;
        }

        /**
         * An Endpoint for a sync matches if it targets the same sync adapter for the same user.
         *
         * @param spec the Endpoint to match. If the spec has null fields, they indicate a wildcard
         * and match any.
         */
        public boolean matchesSpec(EndPoint spec) {
            if (userId != spec.userId
                    && userId != UserHandle.USER_ALL
                    && spec.userId != UserHandle.USER_ALL) {
                return false;
            }
            boolean accountsMatch;
            if (spec.account == null) {
                accountsMatch = true;
            } else {
                accountsMatch = account.equals(spec.account);
            }
            boolean providersMatch;
            if (spec.provider == null) {
                providersMatch = true;
            } else {
                providersMatch = provider.equals(spec.provider);
            }
            return accountsMatch && providersMatch;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(account == null ? "ALL ACCS" : account.name)
                    .append("/")
                    .append(provider == null ? "ALL PDRS" : provider);
            sb.append(":u" + userId);
            return sb.toString();
        }

        public String toSafeString() {
            StringBuilder sb = new StringBuilder();
            sb.append(account == null ? "ALL ACCS" : logSafe(account))
                    .append("/")
                    .append(provider == null ? "ALL PDRS" : provider);
            sb.append(":u" + userId);
            return sb.toString();
        }
    }

    public static class AuthorityInfo {
        // Legal values of getIsSyncable

        /**
         * The syncable state is undefined.
         */
        public static final int UNDEFINED = -2;

        /**
         * Default state for a newly installed adapter. An uninitialized adapter will receive an
         * initialization sync which are governed by a different set of rules to that of regular
         * syncs.
         */
        public static final int NOT_INITIALIZED = -1;
        /**
         * The adapter will not receive any syncs. This is behaviourally equivalent to
         * setSyncAutomatically -> false. However setSyncAutomatically is surfaced to the user
         * while this is generally meant to be controlled by the developer.
         */
        public static final int NOT_SYNCABLE = 0;
        /**
         * The adapter is initialized and functioning. This is the normal state for an adapter.
         */
        public static final int SYNCABLE = 1;
        /**
         * The adapter is syncable but still requires an initialization sync. For example an adapter
         * than has been restored from a previous device will be in this state. Not meant for
         * external use.
         */
        public static final int SYNCABLE_NOT_INITIALIZED = 2;

        /**
         * The adapter is syncable but does not have access to the synced account and needs a
         * user access approval.
         */
        public static final int SYNCABLE_NO_ACCOUNT_ACCESS = 3;

        final EndPoint target;
        final int ident;
        boolean enabled;
        int syncable;
        /** Time at which this sync will run, taking into account backoff. */
        long backoffTime;
        /** Amount of delay due to backoff. */
        long backoffDelay;
        /** Time offset to add to any requests coming to this target. */
        long delayUntil;

        final ArrayList<PeriodicSync> periodicSyncs;

        /**
         * Copy constructor for making deep-ish copies. Only the bundles stored
         * in periodic syncs can make unexpected changes.
         *
         * @param toCopy AuthorityInfo to be copied.
         */
        AuthorityInfo(AuthorityInfo toCopy) {
            target = toCopy.target;
            ident = toCopy.ident;
            enabled = toCopy.enabled;
            syncable = toCopy.syncable;
            backoffTime = toCopy.backoffTime;
            backoffDelay = toCopy.backoffDelay;
            delayUntil = toCopy.delayUntil;
            periodicSyncs = new ArrayList<PeriodicSync>();
            for (PeriodicSync sync : toCopy.periodicSyncs) {
                // Still not a perfect copy, because we are just copying the mappings.
                periodicSyncs.add(new PeriodicSync(sync));
            }
        }

        AuthorityInfo(EndPoint info, int id) {
            target = info;
            ident = id;
            enabled = SYNC_ENABLED_DEFAULT;
            periodicSyncs = new ArrayList<PeriodicSync>();
            defaultInitialisation();
        }

        private void defaultInitialisation() {
            syncable = NOT_INITIALIZED; // default to "unknown"
            backoffTime = -1; // if < 0 then we aren't in backoff mode
            backoffDelay = -1; // if < 0 then we aren't in backoff mode

            if (mPeriodicSyncAddedListener != null) {
                mPeriodicSyncAddedListener.onPeriodicSyncAdded(target, new Bundle(),
                        DEFAULT_POLL_FREQUENCY_SECONDS,
                        calculateDefaultFlexTime(DEFAULT_POLL_FREQUENCY_SECONDS));
            }
        }

        @Override
        public String toString() {
            return target + ", enabled=" + enabled + ", syncable=" + syncable + ", backoff="
                    + backoffTime + ", delay=" + delayUntil;
        }
    }

    public static class SyncHistoryItem {
        int authorityId;
        int historyId;
        long eventTime;
        long elapsedTime;
        int source;
        int event;
        long upstreamActivity;
        long downstreamActivity;
        String mesg;
        boolean initialization;
        Bundle extras;
        int reason;
        int syncExemptionFlag;
    }

    public static class DayStats {
        public final int day;
        public int successCount;
        public long successTime;
        public int failureCount;
        public long failureTime;

        public DayStats(int day) {
            this.day = day;
        }
    }

    interface OnSyncRequestListener {

        /** Called when a sync is needed on an account(s) due to some change in state. */
        public void onSyncRequest(EndPoint info, int reason, Bundle extras,
                @SyncExemption int syncExemptionFlag, int callingUid, int callingPid);
    }

    interface PeriodicSyncAddedListener {
        /** Called when a periodic sync is added. */
        void onPeriodicSyncAdded(EndPoint target, Bundle extras, long pollFrequency, long flex);
    }

    interface OnAuthorityRemovedListener {
        /** Called when an authority is removed. */
        void onAuthorityRemoved(EndPoint removedAuthority);
    }

    /**
     * Validator that maintains a lazy cache of accounts and providers to tell if an authority or
     * account is valid.
     */
    private static class AccountAuthorityValidator {
        final private AccountManager mAccountManager;
        final private PackageManager mPackageManager;
        final private SparseArray<Account[]> mAccountsCache;
        final private SparseArray<ArrayMap<String, Boolean>> mProvidersPerUserCache;

        AccountAuthorityValidator(Context context) {
            mAccountManager = context.getSystemService(AccountManager.class);
            mPackageManager = context.getPackageManager();
            mAccountsCache = new SparseArray<>();
            mProvidersPerUserCache = new SparseArray<>();
        }

        // An account is valid if an installed authenticator has previously created that account
        // on the device
        boolean isAccountValid(Account account, int userId) {
            Account[] accountsForUser = mAccountsCache.get(userId);
            if (accountsForUser == null) {
                accountsForUser = mAccountManager.getAccountsAsUser(userId);
                mAccountsCache.put(userId, accountsForUser);
            }
            return ArrayUtils.contains(accountsForUser, account);
        }

        // An authority is only valid if it has a content provider installed on the system
        boolean isAuthorityValid(String authority, int userId) {
            ArrayMap<String, Boolean> authorityMap = mProvidersPerUserCache.get(userId);
            if (authorityMap == null) {
                authorityMap = new ArrayMap<>();
                mProvidersPerUserCache.put(userId, authorityMap);
            }
            if (!authorityMap.containsKey(authority)) {
                authorityMap.put(authority, mPackageManager.resolveContentProviderAsUser(authority,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId) != null);
            }
            return authorityMap.get(authority);
        }
    }

    // Primary list of all syncable authorities.  Also our global lock.
    @VisibleForTesting
    final SparseArray<AuthorityInfo> mAuthorities =
            new SparseArray<AuthorityInfo>();

    private final HashMap<AccountAndUser, AccountInfo> mAccounts
            = new HashMap<AccountAndUser, AccountInfo>();

    private final SparseArray<ArrayList<SyncInfo>> mCurrentSyncs
            = new SparseArray<ArrayList<SyncInfo>>();

    @VisibleForTesting
    final SparseArray<SyncStatusInfo> mSyncStatus =
            new SparseArray<SyncStatusInfo>();

    private final ArrayList<SyncHistoryItem> mSyncHistory =
            new ArrayList<SyncHistoryItem>();

    private final RemoteCallbackList<ISyncStatusObserver> mChangeListeners
            = new RemoteCallbackList<ISyncStatusObserver>();

    /** Reverse mapping for component name -> <userid -> target id>. */
    private final ArrayMap<ComponentName, SparseArray<AuthorityInfo>> mServices =
            new ArrayMap<ComponentName, SparseArray<AuthorityInfo>>();

    private int mNextAuthorityId = 0;

    // We keep 4 weeks of stats.
    @VisibleForTesting
    final DayStats[] mDayStats = new DayStats[7*4];
    private final Calendar mCal;
    private int mYear;
    private int mYearInDays;

    private final Context mContext;

    private static volatile SyncStorageEngine sSyncStorageEngine = null;

    private int mSyncRandomOffset;

    private static final boolean DELETE_LEGACY_PARCEL_FILES = true;
    private static final String LEGACY_STATUS_FILE_NAME = "status.bin";
    private static final String LEGACY_STATISTICS_FILE_NAME = "stats.bin";

    private static final String SYNC_DIR_NAME = "sync";
    private static final String ACCOUNT_INFO_FILE_NAME = "accounts.xml";
    private static final String STATUS_FILE_NAME = "status";
    private static final String STATISTICS_FILE_NAME = "stats";

    private File mSyncDir;

    /**
     * This file contains the core engine state: all accounts and the
     * settings for them.  It must never be lost, and should be changed
     * infrequently, so it is stored as an XML file.
     */
    private final AtomicFile mAccountInfoFile;

    /**
     * This file contains the current sync status.  We would like to retain
     * it across boots, but its loss is not the end of the world, so we store
     * this information as binary data.
     */
    private final AtomicFile mStatusFile;

    /**
     * This file contains sync statistics.  This is purely debugging information
     * so is written infrequently and can be thrown away at any time.
     */
    private final AtomicFile mStatisticsFile;

    private int mNextHistoryId = 0;
    private SparseArray<Boolean> mMasterSyncAutomatically = new SparseArray<Boolean>();
    private boolean mDefaultMasterSyncAutomatically;

    private OnSyncRequestListener mSyncRequestListener;
    private OnAuthorityRemovedListener mAuthorityRemovedListener;

    private boolean mGrantSyncAdaptersAccountAccess;

    private final MyHandler mHandler;
    private final SyncLogger mLogger;

    private SyncStorageEngine(Context context, File dataDir, Looper looper) {
        mHandler = new MyHandler(looper);
        mContext = context;
        sSyncStorageEngine = this;
        mLogger = SyncLogger.getInstance();

        mCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));

        mDefaultMasterSyncAutomatically = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_syncstorageengine_masterSyncAutomatically);

        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);

        File systemDir = new File(dataDir, "system");
        mSyncDir = new File(systemDir, SYNC_DIR_NAME);
        mSyncDir.mkdirs();

        maybeDeleteLegacyPendingInfoLocked(mSyncDir);

        mAccountInfoFile = new AtomicFile(new File(mSyncDir, ACCOUNT_INFO_FILE_NAME),
                "sync-accounts");
        mStatusFile = new AtomicFile(new File(mSyncDir, STATUS_FILE_NAME), "sync-status");
        mStatisticsFile = new AtomicFile(new File(mSyncDir, STATISTICS_FILE_NAME), "sync-stats");

        readAccountInfoLocked();
        readStatusLocked();
        readStatisticsLocked();

        if (mLogger.enabled()) {
            final int size = mAuthorities.size();
            mLogger.log("Loaded ", size, " items");
            for (int i = 0; i < size; i++) {
                mLogger.log(mAuthorities.valueAt(i));
            }
        }
    }

    public static SyncStorageEngine newTestInstance(Context context) {
        return new SyncStorageEngine(context, context.getFilesDir(), Looper.getMainLooper());
    }

    public static void init(Context context, Looper looper) {
        if (sSyncStorageEngine != null) {
            return;
        }
        File dataDir = Environment.getDataDirectory();
        sSyncStorageEngine = new SyncStorageEngine(context, dataDir, looper);
    }

    public static SyncStorageEngine getSingleton() {
        if (sSyncStorageEngine == null) {
            throw new IllegalStateException("not initialized");
        }
        return sSyncStorageEngine;
    }

    @NonNull
    File getSyncDir() {
        return mSyncDir;
    }

    protected void setOnSyncRequestListener(OnSyncRequestListener listener) {
        if (mSyncRequestListener == null) {
            mSyncRequestListener = listener;
        }
    }

    protected void setOnAuthorityRemovedListener(OnAuthorityRemovedListener listener) {
        if (mAuthorityRemovedListener == null) {
            mAuthorityRemovedListener = listener;
        }
    }

    protected void setPeriodicSyncAddedListener(PeriodicSyncAddedListener listener) {
        if (mPeriodicSyncAddedListener == null) {
            mPeriodicSyncAddedListener = listener;
        }
    }

    private class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_WRITE_STATUS) {
                synchronized (mAuthorities) {
                    writeStatusLocked();
                }
            } else if (msg.what == MSG_WRITE_STATISTICS) {
                synchronized (mAuthorities) {
                    writeStatisticsLocked();
                }
            }
        }
    }

    public int getSyncRandomOffset() {
        return mSyncRandomOffset;
    }

    public void addStatusChangeListener(int mask, int callingUid, ISyncStatusObserver callback) {
        synchronized (mAuthorities) {
            final long cookie = IntPair.of(callingUid, mask);
            mChangeListeners.register(callback, cookie);
        }
    }

    public void removeStatusChangeListener(ISyncStatusObserver callback) {
        synchronized (mAuthorities) {
            mChangeListeners.unregister(callback);
        }
    }

    /**
     * Figure out a reasonable flex time for cases where none is provided (old api calls).
     * @param syncTimeSeconds requested sync time from now.
     * @return amount of seconds before syncTimeSeconds that the sync can occur.
     *      I.e.
     *      earliest_sync_time = syncTimeSeconds - calculateDefaultFlexTime(syncTimeSeconds)
     * The flex time is capped at a percentage of the {@link #DEFAULT_POLL_FREQUENCY_SECONDS}.
     */
    public static long calculateDefaultFlexTime(long syncTimeSeconds) {
        if (syncTimeSeconds < DEFAULT_MIN_FLEX_ALLOWED_SECS) {
            // Small enough sync request time that we don't add flex time - developer probably
            // wants to wait for an operation to occur before syncing so we honour the
            // request time.
            return 0L;
        } else if (syncTimeSeconds < DEFAULT_POLL_FREQUENCY_SECONDS) {
            return (long) (syncTimeSeconds * DEFAULT_FLEX_PERCENT_SYNC);
        } else {
            // Large enough sync request time that we cap the flex time.
            return (long) (DEFAULT_POLL_FREQUENCY_SECONDS * DEFAULT_FLEX_PERCENT_SYNC);
        }
    }

    void reportChange(int which, EndPoint target) {
        final String syncAdapterPackageName;
        if (target.account == null || target.provider == null) {
            syncAdapterPackageName = null;
        } else {
            syncAdapterPackageName = ContentResolver.getSyncAdapterPackageAsUser(
                    target.account.type, target.provider, target.userId);
        }
        reportChange(which, syncAdapterPackageName, target.userId);
    }

    void reportChange(int which, String callingPackageName, int callingUserId) {
        ArrayList<ISyncStatusObserver> reports = null;
        synchronized (mAuthorities) {
            int i = mChangeListeners.beginBroadcast();
            while (i > 0) {
                i--;
                final long cookie = (long) mChangeListeners.getBroadcastCookie(i);
                final int registerUid = IntPair.first(cookie);
                final int registerUserId = UserHandle.getUserId(registerUid);
                final int mask = IntPair.second(cookie);
                if ((which & mask) == 0 || callingUserId != registerUserId) {
                    continue;
                }
                if (callingPackageName != null && mPackageManagerInternal.filterAppAccess(
                        callingPackageName, registerUid, callingUserId)) {
                    continue;
                }
                if (reports == null) {
                    reports = new ArrayList<ISyncStatusObserver>(i);
                }
                reports.add(mChangeListeners.getBroadcastItem(i));
            }
            mChangeListeners.finishBroadcast();
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "reportChange " + which + " to: " + reports);
        }

        if (reports != null) {
            int i = reports.size();
            while (i > 0) {
                i--;
                try {
                    reports.get(i).onStatusChanged(which);
                } catch (RemoteException e) {
                    // The remote callback list will take care of this for us.
                }
            }
        }
    }

    public boolean getSyncAutomatically(Account account, int userId, String providerName) {
        synchronized (mAuthorities) {
            if (account != null) {
                AuthorityInfo authority = getAuthorityLocked(
                        new EndPoint(account, providerName, userId),
                        "getSyncAutomatically");
                return authority != null && authority.enabled;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authorityInfo = mAuthorities.valueAt(i);
                if (authorityInfo.target.matchesSpec(new EndPoint(account, providerName, userId))
                        && authorityInfo.enabled) {
                    return true;
                }
            }
            return false;
        }
    }

    public void setSyncAutomatically(Account account, int userId, String providerName,
            boolean sync, @SyncExemption int syncExemptionFlag, int callingUid, int callingPid) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.d(TAG, "setSyncAutomatically: " + /* account + */" provider " + providerName
                    + ", user " + userId + " -> " + sync);
        }
        mLogger.log("Set sync auto account=", account,
                " user=", userId,
                " authority=", providerName,
                " value=", Boolean.toString(sync),
                " cuid=", callingUid,
                " cpid=", callingPid
        );
        final AuthorityInfo authority;
        synchronized (mAuthorities) {
            authority = getOrCreateAuthorityLocked(
                    new EndPoint(account, providerName, userId),
                    -1 /* ident */,
                    false);
            if (authority.enabled == sync) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.d(TAG, "setSyncAutomatically: already set to " + sync + ", doing nothing");
                }
                return;
            }
            // If the adapter was syncable but missing its initialization sync, set it to
            // uninitialized now. This is to give it a chance to run any one-time initialization
            // logic.
            if (sync && authority.syncable == AuthorityInfo.SYNCABLE_NOT_INITIALIZED) {
                authority.syncable = AuthorityInfo.NOT_INITIALIZED;
            }
            authority.enabled = sync;
            writeAccountInfoLocked();
        }

        if (sync) {
            requestSync(account, userId, SyncOperation.REASON_SYNC_AUTO, providerName,
                    new Bundle(),
                    syncExemptionFlag, callingUid, callingPid);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, authority.target);
        queueBackup();
    }

    public int getIsSyncable(Account account, int userId, String providerName) {
        synchronized (mAuthorities) {
            if (account != null) {
                AuthorityInfo authority = getAuthorityLocked(
                        new EndPoint(account, providerName, userId),
                        "get authority syncable");
                if (authority == null) {
                    return AuthorityInfo.NOT_INITIALIZED;
                }
                return authority.syncable;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authorityInfo = mAuthorities.valueAt(i);
                if (authorityInfo.target != null
                        && authorityInfo.target.provider.equals(providerName)) {
                    return authorityInfo.syncable;
                }
            }
            return AuthorityInfo.NOT_INITIALIZED;
        }
    }

    public void setIsSyncable(Account account, int userId, String providerName, int syncable,
            int callingUid, int callingPid) {
        setSyncableStateForEndPoint(new EndPoint(account, providerName, userId), syncable,
                callingUid, callingPid);
    }

    /**
     * An enabled sync service and a syncable provider's adapter both get resolved to the same
     * persisted variable - namely the "syncable" attribute for an AuthorityInfo in accounts.xml.
     * @param target target to set value for.
     * @param syncable 0 indicates unsyncable, <0 unknown, >0 is active/syncable.
     */
    private void setSyncableStateForEndPoint(EndPoint target, int syncable,
            int callingUid, int callingPid) {
        AuthorityInfo aInfo;
        mLogger.log("Set syncable ", target, " value=", Integer.toString(syncable),
                " cuid=", callingUid,
                " cpid=", callingPid);
        synchronized (mAuthorities) {
            aInfo = getOrCreateAuthorityLocked(target, -1, false);
            if (syncable < AuthorityInfo.NOT_INITIALIZED) {
                syncable = AuthorityInfo.NOT_INITIALIZED;
            }
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.d(TAG, "setIsSyncable: " + aInfo.toString() + " -> " + syncable);
            }
            if (aInfo.syncable == syncable) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.d(TAG, "setIsSyncable: already set to " + syncable + ", doing nothing");
                }
                return;
            }
            aInfo.syncable = syncable;
            writeAccountInfoLocked();
        }
        if (syncable == AuthorityInfo.SYNCABLE) {
            requestSync(aInfo, SyncOperation.REASON_IS_SYNCABLE, new Bundle(),
                    ContentResolver.SYNC_EXEMPTION_NONE, callingUid, callingPid);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, target);
    }

    public Pair<Long, Long> getBackoff(EndPoint info) {
        synchronized (mAuthorities) {
            AuthorityInfo authority = getAuthorityLocked(info, "getBackoff");
            if (authority != null) {
                return Pair.create(authority.backoffTime, authority.backoffDelay);
            }
            return null;
        }
    }

    /**
     * Update the backoff for the given endpoint. The endpoint may be for a provider/account and
     * the account or provider info be null, which signifies all accounts or providers.
     */
    public void setBackoff(EndPoint info, long nextSyncTime, long nextDelay) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "setBackoff: " + info
                    + " -> nextSyncTime " + nextSyncTime + ", nextDelay " + nextDelay);
        }
        boolean changed;
        synchronized (mAuthorities) {
            if (info.account == null || info.provider == null) {
                // Do more work for a provider sync if the provided info has specified all
                // accounts/providers.
                changed = setBackoffLocked(
                        info.account /* may be null */,
                        info.userId,
                        info.provider /* may be null */,
                        nextSyncTime, nextDelay);
            } else {
                AuthorityInfo authorityInfo =
                        getOrCreateAuthorityLocked(info, -1 /* ident */, true);
                if (authorityInfo.backoffTime == nextSyncTime
                        && authorityInfo.backoffDelay == nextDelay) {
                    changed = false;
                } else {
                    authorityInfo.backoffTime = nextSyncTime;
                    authorityInfo.backoffDelay = nextDelay;
                    changed = true;
                }
            }
        }
        if (changed) {
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, info);
        }
    }

    /**
     * Either set backoff for a specific authority, or set backoff for all the
     * accounts on a specific adapter/all adapters.
     *
     * @param account account for which to set backoff. Null to specify all accounts.
     * @param userId id of the user making this request.
     * @param providerName provider for which to set backoff. Null to specify all providers.
     * @return true if a change occured.
     */
    private boolean setBackoffLocked(Account account, int userId, String providerName,
                                     long nextSyncTime, long nextDelay) {
        boolean changed = false;
        for (AccountInfo accountInfo : mAccounts.values()) {
            if (account != null && !account.equals(accountInfo.accountAndUser.account)
                    && userId != accountInfo.accountAndUser.userId) {
                continue;
            }
            for (AuthorityInfo authorityInfo : accountInfo.authorities.values()) {
                if (providerName != null
                        && !providerName.equals(authorityInfo.target.provider)) {
                    continue;
                }
                if (authorityInfo.backoffTime != nextSyncTime
                        || authorityInfo.backoffDelay != nextDelay) {
                    authorityInfo.backoffTime = nextSyncTime;
                    authorityInfo.backoffDelay = nextDelay;
                    changed = true;
                }
            }
        }
        return changed;
    }

    public void clearAllBackoffsLocked() {
        final ArraySet<Integer> changedUserIds = new ArraySet<>();
        synchronized (mAuthorities) {
            // Clear backoff for all sync adapters.
            for (AccountInfo accountInfo : mAccounts.values()) {
                for (AuthorityInfo authorityInfo : accountInfo.authorities.values()) {
                    if (authorityInfo.backoffTime != NOT_IN_BACKOFF_MODE
                            || authorityInfo.backoffDelay != NOT_IN_BACKOFF_MODE) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, "clearAllBackoffsLocked:"
                                    + " authority:" + authorityInfo.target
                                    + " account:" + accountInfo.accountAndUser.account.name
                                    + " user:" + accountInfo.accountAndUser.userId
                                    + " backoffTime was: " + authorityInfo.backoffTime
                                    + " backoffDelay was: " + authorityInfo.backoffDelay);
                        }
                        authorityInfo.backoffTime = NOT_IN_BACKOFF_MODE;
                        authorityInfo.backoffDelay = NOT_IN_BACKOFF_MODE;
                        changedUserIds.add(accountInfo.accountAndUser.userId);
                    }
                }
            }
        }

        for (int i = changedUserIds.size() - 1; i > 0; i--) {
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                    null /* callingPackageName */, changedUserIds.valueAt(i));
        }
    }

    public long getDelayUntilTime(EndPoint info) {
        synchronized (mAuthorities) {
            AuthorityInfo authority = getAuthorityLocked(info, "getDelayUntil");
            if (authority == null) {
                return 0;
            }
            return authority.delayUntil;
        }
    }

    public void setDelayUntilTime(EndPoint info, long delayUntil) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "setDelayUntil: " + info
                    + " -> delayUntil " + delayUntil);
        }
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(info, -1, true);
            if (authority.delayUntil == delayUntil) {
                return;
            }
            authority.delayUntil = delayUntil;
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, info);
    }

    /**
     * Restore all periodic syncs read from persisted files. Used to restore periodic syncs
     * after an OS update.
     */
    boolean restoreAllPeriodicSyncs() {
        if (mPeriodicSyncAddedListener == null) {
            return false;
        }
        synchronized (mAuthorities) {
            for (int i=0; i<mAuthorities.size(); i++) {
                AuthorityInfo authority = mAuthorities.valueAt(i);
                for (PeriodicSync periodicSync: authority.periodicSyncs) {
                    mPeriodicSyncAddedListener.onPeriodicSyncAdded(authority.target,
                            periodicSync.extras, periodicSync.period, periodicSync.flexTime);
                }
                authority.periodicSyncs.clear();
            }
            writeAccountInfoLocked();
        }
        return true;
    }

    public void setMasterSyncAutomatically(boolean flag, int userId,
            @SyncExemption int syncExemptionFlag, int callingUid, int callingPid) {
        mLogger.log("Set master enabled=", flag, " user=", userId,
                " cuid=", callingUid,
                " cpid=", callingPid);
        synchronized (mAuthorities) {
            Boolean auto = mMasterSyncAutomatically.get(userId);
            if (auto != null && auto.equals(flag)) {
                return;
            }
            mMasterSyncAutomatically.put(userId, flag);
            writeAccountInfoLocked();
        }
        if (flag) {
            requestSync(null, userId, SyncOperation.REASON_MASTER_SYNC_AUTO, null,
                    new Bundle(),
                    syncExemptionFlag, callingUid, callingPid);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                null /* callingPackageName */, userId);
        mContext.sendBroadcast(ContentResolver.ACTION_SYNC_CONN_STATUS_CHANGED);
        queueBackup();
    }

    public boolean getMasterSyncAutomatically(int userId) {
        synchronized (mAuthorities) {
            Boolean auto = mMasterSyncAutomatically.get(userId);
            return auto == null ? mDefaultMasterSyncAutomatically : auto;
        }
    }

    public int getAuthorityCount() {
        synchronized (mAuthorities) {
            return mAuthorities.size();
        }
    }

    public AuthorityInfo getAuthority(int authorityId) {
        synchronized (mAuthorities) {
            return mAuthorities.get(authorityId);
        }
    }

    /**
     * Returns true if there is currently a sync operation being actively processed for the given
     * target.
     */
    public boolean isSyncActive(EndPoint info) {
        synchronized (mAuthorities) {
            for (SyncInfo syncInfo : getCurrentSyncs(info.userId)) {
                AuthorityInfo ainfo = getAuthority(syncInfo.authorityId);
                if (ainfo != null && ainfo.target.matchesSpec(info)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void markPending(EndPoint info, boolean pendingValue) {
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(info,
                    -1 /* desired identifier */,
                    true /* write accounts to storage */);
            if (authority == null) {
                return;
            }
            SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
            status.pending = pendingValue;
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING, info);
    }

    /**
     * Called when the set of account has changed, given the new array of
     * active accounts.
     */
    public void removeStaleAccounts(@Nullable Account[] currentAccounts, int userId) {
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "Updating for new accounts...");
            }
            SparseArray<AuthorityInfo> removing = new SparseArray<AuthorityInfo>();
            Iterator<AccountInfo> accIt = mAccounts.values().iterator();
            while (accIt.hasNext()) {
                AccountInfo acc = accIt.next();
                if (acc.accountAndUser.userId != userId) {
                    continue; // Irrelevant user.
                }
                if ((currentAccounts == null)
                        || !ArrayUtils.contains(currentAccounts, acc.accountAndUser.account)) {
                    // This account no longer exists...
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, "Account removed: " + acc.accountAndUser);
                    }
                    for (AuthorityInfo auth : acc.authorities.values()) {
                        removing.put(auth.ident, auth);
                    }
                    accIt.remove();
                }
            }

            // Clean out all data structures.
            int i = removing.size();
            if (i > 0) {
                while (i > 0) {
                    i--;
                    int ident = removing.keyAt(i);
                    AuthorityInfo auth = removing.valueAt(i);
                    if (mAuthorityRemovedListener != null) {
                        mAuthorityRemovedListener.onAuthorityRemoved(auth.target);
                    }
                    mAuthorities.remove(ident);
                    int j = mSyncStatus.size();
                    while (j > 0) {
                        j--;
                        if (mSyncStatus.keyAt(j) == ident) {
                            mSyncStatus.remove(mSyncStatus.keyAt(j));
                        }
                    }
                    j = mSyncHistory.size();
                    while (j > 0) {
                        j--;
                        if (mSyncHistory.get(j).authorityId == ident) {
                            mSyncHistory.remove(j);
                        }
                    }
                }
                writeAccountInfoLocked();
                writeStatusLocked();
                writeStatisticsLocked();
            }
        }
    }

    /**
     * Called when a sync is starting. Supply a valid ActiveSyncContext with information
     * about the sync.
     */
    public SyncInfo addActiveSync(SyncManager.ActiveSyncContext activeSyncContext) {
        final SyncInfo syncInfo;
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "setActiveSync: account="
                        + " auth=" + activeSyncContext.mSyncOperation.target
                        + " src=" + activeSyncContext.mSyncOperation.syncSource
                        + " extras=" + activeSyncContext.mSyncOperation.getExtrasAsString());
            }
            final EndPoint info = activeSyncContext.mSyncOperation.target;
            AuthorityInfo authorityInfo = getOrCreateAuthorityLocked(
                    info,
                    -1 /* assign a new identifier if creating a new target */,
                    true /* write to storage if this results in a change */);
            syncInfo = new SyncInfo(
                    authorityInfo.ident,
                    authorityInfo.target.account,
                    authorityInfo.target.provider,
                    activeSyncContext.mStartTime);
            getCurrentSyncs(authorityInfo.target.userId).add(syncInfo);
        }
        reportActiveChange(activeSyncContext.mSyncOperation.target);
        return syncInfo;
    }

    /**
     * Called to indicate that a previously active sync is no longer active.
     */
    public void removeActiveSync(SyncInfo syncInfo, int userId) {
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "removeActiveSync: account=" + syncInfo.account
                        + " user=" + userId
                        + " auth=" + syncInfo.authority);
            }
            getCurrentSyncs(userId).remove(syncInfo);
        }

        reportActiveChange(new EndPoint(syncInfo.account, syncInfo.authority, userId));
    }

    /**
     * To allow others to send active change reports, to poke clients.
     */
    public void reportActiveChange(EndPoint target) {
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE, target);
    }

    /**
     * Note that sync has started for the given operation.
     */
    public long insertStartSyncEvent(SyncOperation op, long now) {
        long id;
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "insertStartSyncEvent: " + op);
            }
            AuthorityInfo authority = getAuthorityLocked(op.target, "insertStartSyncEvent");
            if (authority == null) {
                return -1;
            }
            SyncHistoryItem item = new SyncHistoryItem();
            item.initialization = op.isInitialization();
            item.authorityId = authority.ident;
            item.historyId = mNextHistoryId++;
            if (mNextHistoryId < 0) mNextHistoryId = 0;
            item.eventTime = now;
            item.source = op.syncSource;
            item.reason = op.reason;
            item.extras = op.getClonedExtras();
            item.event = EVENT_START;
            item.syncExemptionFlag = op.syncExemptionFlag;
            mSyncHistory.add(0, item);
            while (mSyncHistory.size() > MAX_HISTORY) {
                mSyncHistory.remove(mSyncHistory.size()-1);
            }
            id = item.historyId;
            if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "returning historyId " + id);
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS, op.owningPackage, op.target.userId);
        return id;
    }

    public void stopSyncEvent(long historyId, long elapsedTime, String resultMessage,
                              long downstreamActivity, long upstreamActivity, String opPackageName,
                              int userId) {
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "stopSyncEvent: historyId=" + historyId);
            }
            SyncHistoryItem item = null;
            int i = mSyncHistory.size();
            while (i > 0) {
                i--;
                item = mSyncHistory.get(i);
                if (item.historyId == historyId) {
                    break;
                }
                item = null;
            }

            if (item == null) {
                Slog.w(TAG, "stopSyncEvent: no history for id " + historyId);
                return;
            }

            item.elapsedTime = elapsedTime;
            item.event = EVENT_STOP;
            item.mesg = resultMessage;
            item.downstreamActivity = downstreamActivity;
            item.upstreamActivity = upstreamActivity;

            SyncStatusInfo status = getOrCreateSyncStatusLocked(item.authorityId);

            status.maybeResetTodayStats(isClockValid(), /*force=*/ false);

            status.totalStats.numSyncs++;
            status.todayStats.numSyncs++;
            status.totalStats.totalElapsedTime += elapsedTime;
            status.todayStats.totalElapsedTime += elapsedTime;
            switch (item.source) {
                case SOURCE_LOCAL:
                    status.totalStats.numSourceLocal++;
                    status.todayStats.numSourceLocal++;
                    break;
                case SOURCE_POLL:
                    status.totalStats.numSourcePoll++;
                    status.todayStats.numSourcePoll++;
                    break;
                case SOURCE_USER:
                    status.totalStats.numSourceUser++;
                    status.todayStats.numSourceUser++;
                    break;
                case SOURCE_OTHER:
                    status.totalStats.numSourceOther++;
                    status.todayStats.numSourceOther++;
                    break;
                case SOURCE_PERIODIC:
                    status.totalStats.numSourcePeriodic++;
                    status.todayStats.numSourcePeriodic++;
                    break;
                case SOURCE_FEED:
                    status.totalStats.numSourceFeed++;
                    status.todayStats.numSourceFeed++;
                    break;
            }

            boolean writeStatisticsNow = false;
            int day = getCurrentDayLocked();
            if (mDayStats[0] == null) {
                mDayStats[0] = new DayStats(day);
            } else if (day != mDayStats[0].day) {
                System.arraycopy(mDayStats, 0, mDayStats, 1, mDayStats.length-1);
                mDayStats[0] = new DayStats(day);
                writeStatisticsNow = true;
            } else if (mDayStats[0] == null) {
            }
            final DayStats ds = mDayStats[0];

            final long lastSyncTime = (item.eventTime + elapsedTime);
            boolean writeStatusNow = false;
            if (MESG_SUCCESS.equals(resultMessage)) {
                // - if successful, update the successful columns
                if (status.lastSuccessTime == 0 || status.lastFailureTime != 0) {
                    writeStatusNow = true;
                }
                status.setLastSuccess(item.source, lastSyncTime);
                ds.successCount++;
                ds.successTime += elapsedTime;
            } else if (!MESG_CANCELED.equals(resultMessage)) {
                if (status.lastFailureTime == 0) {
                    writeStatusNow = true;
                }
                status.totalStats.numFailures++;
                status.todayStats.numFailures++;

                status.setLastFailure(item.source, lastSyncTime, resultMessage);

                ds.failureCount++;
                ds.failureTime += elapsedTime;
            } else {
                // Cancel
                status.totalStats.numCancels++;
                status.todayStats.numCancels++;
                writeStatusNow = true;
            }
            final StringBuilder event = new StringBuilder();
            event.append("" + resultMessage + " Source=" + SyncStorageEngine.SOURCES[item.source]
                    + " Elapsed=");
            SyncManager.formatDurationHMS(event, elapsedTime);
            event.append(" Reason=");
            event.append(SyncOperation.reasonToString(null, item.reason));
            if (item.syncExemptionFlag != ContentResolver.SYNC_EXEMPTION_NONE) {
                event.append(" Exemption=");
                switch (item.syncExemptionFlag) {
                    case ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET:
                        event.append("fg");
                        break;
                    case ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP:
                        event.append("top");
                        break;
                    default:
                        event.append(item.syncExemptionFlag);
                        break;
                }
            }
            event.append(" Extras=");
            SyncOperation.extrasToStringBuilder(item.extras, event);

            status.addEvent(event.toString());

            if (writeStatusNow) {
                writeStatusLocked();
            } else if (!mHandler.hasMessages(MSG_WRITE_STATUS)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_WRITE_STATUS),
                        WRITE_STATUS_DELAY);
            }
            if (writeStatisticsNow) {
                writeStatisticsLocked();
            } else if (!mHandler.hasMessages(MSG_WRITE_STATISTICS)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_WRITE_STATISTICS),
                        WRITE_STATISTICS_DELAY);
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS, opPackageName, userId);
    }

    /**
     * Return a list of the currently active syncs. Note that the returned
     * items are the real, live active sync objects, so be careful what you do
     * with it.
     */
    private List<SyncInfo> getCurrentSyncs(int userId) {
        synchronized (mAuthorities) {
            return getCurrentSyncsLocked(userId);
        }
    }

    /**
     * @param userId Id of user to return current sync info.
     * @param canAccessAccounts Determines whether to redact Account information from the result.
     * @return a copy of the current syncs data structure. Will not return null.
     */
    public List<SyncInfo> getCurrentSyncsCopy(int userId, boolean canAccessAccounts) {
        synchronized (mAuthorities) {
            final List<SyncInfo> syncs = getCurrentSyncsLocked(userId);
            final List<SyncInfo> syncsCopy = new ArrayList<SyncInfo>();
            for (SyncInfo sync : syncs) {
                SyncInfo copy;
                if (!canAccessAccounts) {
                    copy = SyncInfo.createAccountRedacted(
                        sync.authorityId, sync.authority, sync.startTime);
                } else {
                    copy = new SyncInfo(sync);
                }
                syncsCopy.add(copy);
            }
            return syncsCopy;
        }
    }

    private List<SyncInfo> getCurrentSyncsLocked(int userId) {
        ArrayList<SyncInfo> syncs = mCurrentSyncs.get(userId);
        if (syncs == null) {
            syncs = new ArrayList<SyncInfo>();
            mCurrentSyncs.put(userId, syncs);
        }
        return syncs;
    }

    /**
     * Return a copy of the specified target with the corresponding sync status
     */
    public Pair<AuthorityInfo, SyncStatusInfo> getCopyOfAuthorityWithSyncStatus(EndPoint info) {
        synchronized (mAuthorities) {
            AuthorityInfo authorityInfo = getOrCreateAuthorityLocked(info,
                    -1 /* assign a new identifier if creating a new target */,
                    true /* write to storage if this results in a change */);
            return createCopyPairOfAuthorityWithSyncStatusLocked(authorityInfo);
        }
    }

    /**
     * Returns the status that matches the target.
     *
     * @param info the endpoint target we are querying status info for.
     * @return the SyncStatusInfo for the endpoint.
     */
    public SyncStatusInfo getStatusByAuthority(EndPoint info) {
        if (info.account == null || info.provider == null) {
            return null;
        }
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i = 0; i < N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);
                if (ainfo != null
                        && ainfo.target.matchesSpec(info)) {
                    return cur;
                }
            }
            return null;
        }
    }

    /** Return true if the pending status is true of any matching authorities. */
    public boolean isSyncPending(EndPoint info) {
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i = 0; i < N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);
                if (ainfo == null) {
                    continue;
                }
                if (!ainfo.target.matchesSpec(info)) {
                    continue;
                }
                if (cur.pending) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Return an array of the current sync status for all authorities.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public ArrayList<SyncHistoryItem> getSyncHistory() {
        synchronized (mAuthorities) {
            final int N = mSyncHistory.size();
            ArrayList<SyncHistoryItem> items = new ArrayList<SyncHistoryItem>(N);
            for (int i=0; i<N; i++) {
                items.add(mSyncHistory.get(i));
            }
            return items;
        }
    }

    /**
     * Return an array of the current per-day statistics.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public DayStats[] getDayStatistics() {
        synchronized (mAuthorities) {
            DayStats[] ds = new DayStats[mDayStats.length];
            System.arraycopy(mDayStats, 0, ds, 0, ds.length);
            return ds;
        }
    }

    private Pair<AuthorityInfo, SyncStatusInfo> createCopyPairOfAuthorityWithSyncStatusLocked(
            AuthorityInfo authorityInfo) {
        SyncStatusInfo syncStatusInfo = getOrCreateSyncStatusLocked(authorityInfo.ident);
        return Pair.create(new AuthorityInfo(authorityInfo), new SyncStatusInfo(syncStatusInfo));
    }

    private int getCurrentDayLocked() {
        mCal.setTimeInMillis(System.currentTimeMillis());
        final int dayOfYear = mCal.get(Calendar.DAY_OF_YEAR);
        if (mYear != mCal.get(Calendar.YEAR)) {
            mYear = mCal.get(Calendar.YEAR);
            mCal.clear();
            mCal.set(Calendar.YEAR, mYear);
            mYearInDays = (int)(mCal.getTimeInMillis()/86400000);
        }
        return dayOfYear + mYearInDays;
    }

    /**
     * Retrieve a target's full info, returning null if one does not exist.
     *
     * @param info info of the target to look up.
     * @param tag If non-null, this will be used in a log message if the
     * requested target does not exist.
     */
    private AuthorityInfo getAuthorityLocked(EndPoint info, String tag) {
        AccountAndUser au = new AccountAndUser(info.account, info.userId);
        AccountInfo accountInfo = mAccounts.get(au);
        if (accountInfo == null) {
            if (tag != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, tag + ": unknown account " + au);
                }
            }
            return null;
        }
        AuthorityInfo authority = accountInfo.authorities.get(info.provider);
        if (authority == null) {
            if (tag != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, tag + ": unknown provider " + info.provider);
                }
            }
            return null;
        }
        return authority;
    }

    /**
     * @param info info identifying target.
     * @param ident unique identifier for target. -1 for none.
     * @param doWrite if true, update the accounts.xml file on the disk.
     * @return the authority that corresponds to the provided sync target, creating it if none
     * exists.
     */
    private AuthorityInfo getOrCreateAuthorityLocked(EndPoint info, int ident, boolean doWrite) {
        AuthorityInfo authority = null;
        AccountAndUser au = new AccountAndUser(info.account, info.userId);
        AccountInfo account = mAccounts.get(au);
        if (account == null) {
            account = new AccountInfo(au);
            mAccounts.put(au, account);
        }
        authority = account.authorities.get(info.provider);
        if (authority == null) {
            authority = createAuthorityLocked(info, ident, doWrite);
            account.authorities.put(info.provider, authority);
        }
        return authority;
    }

    private AuthorityInfo createAuthorityLocked(EndPoint info, int ident, boolean doWrite) {
        AuthorityInfo authority;
        if (ident < 0) {
            ident = mNextAuthorityId;
            mNextAuthorityId++;
            doWrite = true;
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "created a new AuthorityInfo for " + info);
        }
        authority = new AuthorityInfo(info, ident);
        mAuthorities.put(ident, authority);
        if (doWrite) {
            writeAccountInfoLocked();
        }
        return authority;
    }

    public void removeAuthority(EndPoint info) {
        synchronized (mAuthorities) {
            removeAuthorityLocked(info.account, info.userId, info.provider, true /* doWrite */);
        }
    }


    /**
     * Remove an authority associated with a provider. Needs to be a standalone function for
     * backward compatibility.
     */
    private void removeAuthorityLocked(Account account, int userId, String authorityName,
                                       boolean doWrite) {
        AccountInfo accountInfo = mAccounts.get(new AccountAndUser(account, userId));
        if (accountInfo != null) {
            final AuthorityInfo authorityInfo = accountInfo.authorities.remove(authorityName);
            if (authorityInfo != null) {
                if (mAuthorityRemovedListener != null) {
                    mAuthorityRemovedListener.onAuthorityRemoved(authorityInfo.target);
                }
                mAuthorities.remove(authorityInfo.ident);
                if (doWrite) {
                    writeAccountInfoLocked();
                }
            }
        }
    }

    private SyncStatusInfo getOrCreateSyncStatusLocked(int authorityId) {
        SyncStatusInfo status = mSyncStatus.get(authorityId);
        if (status == null) {
            status = new SyncStatusInfo(authorityId);
            mSyncStatus.put(authorityId, status);
        }
        return status;
    }

    public void writeAllState() {
        synchronized (mAuthorities) {
            // Account info is always written so no need to do it here.
            writeStatusLocked();
            writeStatisticsLocked();
        }
    }

    public boolean shouldGrantSyncAdaptersAccountAccess() {
        return mGrantSyncAdaptersAccountAccess;
    }

    /**
     * public for testing
     */
    public void clearAndReadState() {
        synchronized (mAuthorities) {
            mAuthorities.clear();
            mAccounts.clear();
            mServices.clear();
            mSyncStatus.clear();
            mSyncHistory.clear();

            readAccountInfoLocked();
            readStatusLocked();
            readStatisticsLocked();
            writeAccountInfoLocked();
            writeStatusLocked();
            writeStatisticsLocked();
        }
    }

    /**
     * Read all account information back in to the initial engine state.
     */
    private void readAccountInfoLocked() {
        int highestAuthorityId = -1;
        FileInputStream fis = null;
        try {
            fis = mAccountInfoFile.openRead();
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Slog.v(TAG_FILE, "Reading " + mAccountInfoFile.getBaseFile());
            }
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                Slog.i(TAG, "No initial accounts");
                return;
            }

            String tagName = parser.getName();
            if ("accounts".equals(tagName)) {
                boolean listen = parser.getAttributeBoolean(
                        null, XML_ATTR_LISTEN_FOR_TICKLES, true);
                int version = parser.getAttributeInt(null, "version", 0);

                if (version < 3) {
                    mGrantSyncAdaptersAccountAccess = true;
                }

                int nextId = parser.getAttributeInt(null, XML_ATTR_NEXT_AUTHORITY_ID, 0);
                mNextAuthorityId = Math.max(mNextAuthorityId, nextId);

                mSyncRandomOffset = parser.getAttributeInt(null, XML_ATTR_SYNC_RANDOM_OFFSET, 0);
                if (mSyncRandomOffset == 0) {
                    Random random = new Random(System.currentTimeMillis());
                    mSyncRandomOffset = random.nextInt(86400);
                }
                mMasterSyncAutomatically.put(0, listen);
                eventType = parser.next();
                AuthorityInfo authority = null;
                PeriodicSync periodicSync = null;
                AccountAuthorityValidator validator = new AccountAuthorityValidator(mContext);
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (parser.getDepth() == 2) {
                            if ("authority".equals(tagName)) {
                                authority = parseAuthority(parser, version, validator);
                                periodicSync = null;
                                if (authority != null) {
                                    if (authority.ident > highestAuthorityId) {
                                        highestAuthorityId = authority.ident;
                                    }
                                } else {
                                    EventLog.writeEvent(0x534e4554, "26513719", -1,
                                            "Malformed authority");
                                }
                            } else if (XML_TAG_LISTEN_FOR_TICKLES.equals(tagName)) {
                                parseListenForTickles(parser);
                            }
                        } else if (parser.getDepth() == 3) {
                            if ("periodicSync".equals(tagName) && authority != null) {
                                periodicSync = parsePeriodicSync(parser, authority);
                            }
                        } else if (parser.getDepth() == 4 && periodicSync != null) {
                            if ("extra".equals(tagName)) {
                                parseExtra(parser, periodicSync.extras);
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Error reading accounts", e);
            return;
        } catch (java.io.IOException e) {
            if (fis == null) Slog.i(TAG, "No initial accounts");
            else Slog.w(TAG, "Error reading accounts", e);
            return;
        } finally {
            mNextAuthorityId = Math.max(highestAuthorityId + 1, mNextAuthorityId);
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }

        maybeMigrateSettingsForRenamedAuthorities();
    }

    /**
     * Ensure the old pending.bin is deleted, as it has been changed to pending.xml.
     * pending.xml was used starting in KitKat.
     * @param syncDir directory where the sync files are located.
     */
    private void maybeDeleteLegacyPendingInfoLocked(File syncDir) {
        File file = new File(syncDir, "pending.bin");
        if (!file.exists()) {
            return;
        } else {
            file.delete();
        }
    }

    /**
     * some authority names have changed. copy over their settings and delete the old ones
     * @return true if a change was made
     */
    private boolean maybeMigrateSettingsForRenamedAuthorities() {
        boolean writeNeeded = false;

        ArrayList<AuthorityInfo> authoritiesToRemove = new ArrayList<AuthorityInfo>();
        final int N = mAuthorities.size();
        for (int i = 0; i < N; i++) {
            AuthorityInfo authority = mAuthorities.valueAt(i);
            // skip this authority if it isn't one of the renamed ones
            final String newAuthorityName = sAuthorityRenames.get(authority.target.provider);
            if (newAuthorityName == null) {
                continue;
            }

            // remember this authority so we can remove it later. we can't remove it
            // now without messing up this loop iteration
            authoritiesToRemove.add(authority);

            // this authority isn't enabled, no need to copy it to the new authority name since
            // the default is "disabled"
            if (!authority.enabled) {
                continue;
            }

            // if we already have a record of this new authority then don't copy over the settings
            EndPoint newInfo =
                    new EndPoint(authority.target.account,
                            newAuthorityName,
                            authority.target.userId);
            if (getAuthorityLocked(newInfo, "cleanup") != null) {
                continue;
            }

            AuthorityInfo newAuthority =
                    getOrCreateAuthorityLocked(newInfo, -1 /* ident */, false /* doWrite */);
            newAuthority.enabled = true;
            writeNeeded = true;
        }

        for (AuthorityInfo authorityInfo : authoritiesToRemove) {
            removeAuthorityLocked(
                    authorityInfo.target.account,
                    authorityInfo.target.userId,
                    authorityInfo.target.provider,
                    false /* doWrite */);
            writeNeeded = true;
        }

        return writeNeeded;
    }

    private void parseListenForTickles(TypedXmlPullParser parser) {
        int userId = 0;
        try {
            parser.getAttributeInt(null, XML_ATTR_USER);
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "error parsing the user for listen-for-tickles", e);
        }
        boolean listen = parser.getAttributeBoolean(null, XML_ATTR_ENABLED, true);
        mMasterSyncAutomatically.put(userId, listen);
    }

    private AuthorityInfo parseAuthority(TypedXmlPullParser parser, int version,
            AccountAuthorityValidator validator) throws XmlPullParserException {
        AuthorityInfo authority = null;
        int id = -1;
        try {
            id = parser.getAttributeInt(null, "id");
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "error parsing the id of the authority", e);
        }
        if (id >= 0) {
            String authorityName = parser.getAttributeValue(null, "authority");
            boolean enabled = parser.getAttributeBoolean(null, XML_ATTR_ENABLED, true);
            String syncable = parser.getAttributeValue(null, "syncable");
            String accountName = parser.getAttributeValue(null, "account");
            String accountType = parser.getAttributeValue(null, "type");
            int userId = parser.getAttributeInt(null, XML_ATTR_USER, 0);
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            if (accountType == null && packageName == null) {
                accountType = "com.google";
                syncable = String.valueOf(AuthorityInfo.NOT_INITIALIZED);
            }
            authority = mAuthorities.get(id);
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Slog.v(TAG_FILE, "Adding authority:"
                        + " account=" + accountName
                        + " accountType=" + accountType
                        + " auth=" + authorityName
                        + " package=" + packageName
                        + " class=" + className
                        + " user=" + userId
                        + " enabled=" + enabled
                        + " syncable=" + syncable);
            }
            if (authority == null) {
                if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                    Slog.v(TAG_FILE, "Creating authority entry");
                }
                if (accountName != null && authorityName != null) {
                    EndPoint info = new EndPoint(
                            new Account(accountName, accountType),
                            authorityName, userId);
                    if (validator.isAccountValid(info.account, userId)
                            && validator.isAuthorityValid(authorityName, userId)) {
                        authority = getOrCreateAuthorityLocked(info, id, false);
                        // If the version is 0 then we are upgrading from a file format that did not
                        // know about periodic syncs. In that case don't clear the list since we
                        // want the default, which is a daily periodic sync.
                        // Otherwise clear out this default list since we will populate it later
                        // with
                        // the periodic sync descriptions that are read from the configuration file.
                        if (version > 0) {
                            authority.periodicSyncs.clear();
                        }
                    } else {
                        EventLog.writeEvent(0x534e4554, "35028827", -1,
                                "account:" + info.account + " provider:" + authorityName + " user:"
                                        + userId);
                    }
                }
            }
            if (authority != null) {
                authority.enabled = enabled;
                try {
                    authority.syncable = (syncable == null) ?
                            AuthorityInfo.NOT_INITIALIZED : Integer.parseInt(syncable);
                } catch (NumberFormatException e) {
                    // On L we stored this as {"unknown", "true", "false"} so fall back to this
                    // format.
                    if ("unknown".equals(syncable)) {
                        authority.syncable = AuthorityInfo.NOT_INITIALIZED;
                    } else {
                        authority.syncable = Boolean.parseBoolean(syncable) ?
                                AuthorityInfo.SYNCABLE : AuthorityInfo.NOT_SYNCABLE;
                    }

                }
            } else {
                Slog.w(TAG, "Failure adding authority:"
                        + " auth=" + authorityName
                        + " enabled=" + enabled
                        + " syncable=" + syncable);
            }
        }
        return authority;
    }

    /**
     * Parse a periodic sync from accounts.xml. Sets the bundle to be empty.
     */
    private PeriodicSync parsePeriodicSync(TypedXmlPullParser parser, AuthorityInfo authorityInfo) {
        Bundle extras = new Bundle(); // Gets filled in later.
        long period;
        long flextime;
        try {
            period = parser.getAttributeLong(null, "period");
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "error parsing the period of a periodic sync", e);
            return null;
        }
        try {
            flextime = parser.getAttributeLong(null, "flex");
        } catch (XmlPullParserException e) {
            flextime = calculateDefaultFlexTime(period);
            Slog.e(TAG, "Error formatting value parsed for periodic sync flex, using default: "
                    + flextime, e);
        }
        PeriodicSync periodicSync;
        periodicSync =
                new PeriodicSync(authorityInfo.target.account,
                        authorityInfo.target.provider,
                        extras,
                        period, flextime);
        authorityInfo.periodicSyncs.add(periodicSync);
        return periodicSync;
    }

    private void parseExtra(TypedXmlPullParser parser, Bundle extras) {
        String name = parser.getAttributeValue(null, "name");
        String type = parser.getAttributeValue(null, "type");

        try {
            if ("long".equals(type)) {
                extras.putLong(name, parser.getAttributeLong(null, "value1"));
            } else if ("integer".equals(type)) {
                extras.putInt(name, parser.getAttributeInt(null, "value1"));
            } else if ("double".equals(type)) {
                extras.putDouble(name, parser.getAttributeDouble(null, "value1"));
            } else if ("float".equals(type)) {
                extras.putFloat(name, parser.getAttributeFloat(null, "value1"));
            } else if ("boolean".equals(type)) {
                extras.putBoolean(name, parser.getAttributeBoolean(null, "value1"));
            } else if ("string".equals(type)) {
                extras.putString(name, parser.getAttributeValue(null, "value1"));
            } else if ("account".equals(type)) {
                final String value1 = parser.getAttributeValue(null, "value1");
                final String value2 = parser.getAttributeValue(null, "value2");
                extras.putParcelable(name, new Account(value1, value2));
            }
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "error parsing bundle value", e);
        }
    }

    /**
     * Write all account information to the account file.
     */
    private void writeAccountInfoLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG_FILE, "Writing new " + mAccountInfoFile.getBaseFile());
        }
        FileOutputStream fos = null;

        try {
            fos = mAccountInfoFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            out.startTag(null, "accounts");
            out.attributeInt(null, "version", ACCOUNTS_VERSION);
            out.attributeInt(null, XML_ATTR_NEXT_AUTHORITY_ID, mNextAuthorityId);
            out.attributeInt(null, XML_ATTR_SYNC_RANDOM_OFFSET, mSyncRandomOffset);

            // Write the Sync Automatically flags for each user
            final int M = mMasterSyncAutomatically.size();
            for (int m = 0; m < M; m++) {
                int userId = mMasterSyncAutomatically.keyAt(m);
                Boolean listen = mMasterSyncAutomatically.valueAt(m);
                out.startTag(null, XML_TAG_LISTEN_FOR_TICKLES);
                out.attributeInt(null, XML_ATTR_USER, userId);
                out.attributeBoolean(null, XML_ATTR_ENABLED, listen);
                out.endTag(null, XML_TAG_LISTEN_FOR_TICKLES);
            }

            final int N = mAuthorities.size();
            for (int i = 0; i < N; i++) {
                AuthorityInfo authority = mAuthorities.valueAt(i);
                EndPoint info = authority.target;
                out.startTag(null, "authority");
                out.attributeInt(null, "id", authority.ident);
                out.attributeInt(null, XML_ATTR_USER, info.userId);
                out.attributeBoolean(null, XML_ATTR_ENABLED, authority.enabled);
                out.attribute(null, "account", info.account.name);
                out.attribute(null, "type", info.account.type);
                out.attribute(null, "authority", info.provider);
                out.attributeInt(null, "syncable", authority.syncable);
                out.endTag(null, "authority");
            }
            out.endTag(null, "accounts");
            out.endDocument();
            mAccountInfoFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing accounts", e1);
            if (fos != null) {
                mAccountInfoFile.failWrite(fos);
            }
        }
    }

    public static final int STATUS_FILE_END = 0;
    public static final int STATUS_FILE_ITEM = 100;

    private void readStatusParcelLocked(File parcel) {
        try {
            final AtomicFile parcelFile = new AtomicFile(parcel);
            byte[] data = parcelFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            while ((token=in.readInt()) != STATUS_FILE_END) {
                if (token == STATUS_FILE_ITEM) {
                    try {
                        SyncStatusInfo status = new SyncStatusInfo(in);
                        if (mAuthorities.indexOfKey(status.authorityId) >= 0) {
                            status.pending = false;
                            mSyncStatus.put(status.authorityId, status);
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to parse some sync status.", e);
                    }
                } else {
                    // Ooops.
                    Slog.w(TAG, "Unknown status token: " + token);
                    break;
                }
            }
        } catch (IOException e) {
            Slog.i(TAG, "No initial status");
        }
    }

    private void upgradeStatusIfNeededLocked() {
        final File parcelStatus = new File(mSyncDir, LEGACY_STATUS_FILE_NAME);
        if (parcelStatus.exists() && !mStatusFile.exists()) {
            readStatusParcelLocked(parcelStatus);
            writeStatusLocked();
        }

        // if upgrade to proto was successful, delete parcel file
        if (DELETE_LEGACY_PARCEL_FILES && parcelStatus.exists() && mStatusFile.exists()) {
            parcelStatus.delete();
        }
    }

    /**
     * Read all sync status back in to the initial engine state.
     */
    @VisibleForTesting
    void readStatusLocked() {
        upgradeStatusIfNeededLocked();

        if (!mStatusFile.exists()) {
            return;
        }
        try {
            try (FileInputStream in = mStatusFile.openRead()) {
                readStatusInfoLocked(in);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to read status info file.", e);
        }
    }

    private void readStatusInfoLocked(InputStream in) throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatusProto.STATUS:
                    final long token = proto.start(SyncStatusProto.STATUS);
                    final SyncStatusInfo status = readSyncStatusInfoLocked(proto);
                    proto.end(token);
                    if (mAuthorities.indexOfKey(status.authorityId) >= 0) {
                        status.pending = false;
                        mSyncStatus.put(status.authorityId, status);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    private SyncStatusInfo readSyncStatusInfoLocked(ProtoInputStream proto) throws IOException {
        SyncStatusInfo status;
        if (proto.nextField(SyncStatusProto.StatusInfo.AUTHORITY_ID)) {
            //fast-path; this should work for most cases since the authority id is written first
            status = new SyncStatusInfo(proto.readInt(SyncStatusProto.StatusInfo.AUTHORITY_ID));
        } else {
            // placeholder to read other data; assume the default authority id as 0
            status = new SyncStatusInfo(0);
        }

        int successTimesCount = 0;
        int failureTimesCount = 0;
        ArrayList<Pair<Long, String>> lastEventInformation = new ArrayList<>();
        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatusProto.StatusInfo.AUTHORITY_ID:
                    // fast-path failed for some reason, rebuild the status from placeholder object
                    Slog.w(TAG, "Failed to read the authority id via fast-path; "
                            + "some data might not have been read.");
                    status = new SyncStatusInfo(
                            proto.readInt(SyncStatusProto.StatusInfo.AUTHORITY_ID), status);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_SUCCESS_TIME:
                    status.lastSuccessTime = proto.readLong(
                            SyncStatusProto.StatusInfo.LAST_SUCCESS_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_SUCCESS_SOURCE:
                    status.lastSuccessSource = proto.readInt(
                            SyncStatusProto.StatusInfo.LAST_SUCCESS_SOURCE);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_FAILURE_TIME:
                    status.lastFailureTime = proto.readLong(
                            SyncStatusProto.StatusInfo.LAST_FAILURE_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_FAILURE_SOURCE:
                    status.lastFailureSource = proto.readInt(
                            SyncStatusProto.StatusInfo.LAST_FAILURE_SOURCE);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_FAILURE_MESSAGE:
                    status.lastFailureMesg = proto.readString(
                            SyncStatusProto.StatusInfo.LAST_FAILURE_MESSAGE);
                    break;
                case (int) SyncStatusProto.StatusInfo.INITIAL_FAILURE_TIME:
                    status.initialFailureTime = proto.readLong(
                            SyncStatusProto.StatusInfo.INITIAL_FAILURE_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.PENDING:
                    status.pending = proto.readBoolean(SyncStatusProto.StatusInfo.PENDING);
                    break;
                case (int) SyncStatusProto.StatusInfo.INITIALIZE:
                    status.initialize = proto.readBoolean(SyncStatusProto.StatusInfo.INITIALIZE);
                    break;
                case (int) SyncStatusProto.StatusInfo.PERIODIC_SYNC_TIMES:
                    status.addPeriodicSyncTime(
                            proto.readLong(SyncStatusProto.StatusInfo.PERIODIC_SYNC_TIMES));
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_EVENT_INFO:
                    final long eventToken = proto.start(SyncStatusProto.StatusInfo.LAST_EVENT_INFO);
                    final Pair<Long, String> lastEventInfo = parseLastEventInfoLocked(proto);
                    if (lastEventInfo != null) {
                        lastEventInformation.add(lastEventInfo);
                    }
                    proto.end(eventToken);
                    break;
                case (int) SyncStatusProto.StatusInfo.LAST_TODAY_RESET_TIME:
                    status.lastTodayResetTime = proto.readLong(
                            SyncStatusProto.StatusInfo.LAST_TODAY_RESET_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.TOTAL_STATS:
                    final long totalStatsToken = proto.start(
                            SyncStatusProto.StatusInfo.TOTAL_STATS);
                    readSyncStatusStatsLocked(proto, status.totalStats);
                    proto.end(totalStatsToken);
                    break;
                case (int) SyncStatusProto.StatusInfo.TODAY_STATS:
                    final long todayStatsToken = proto.start(
                            SyncStatusProto.StatusInfo.TODAY_STATS);
                    readSyncStatusStatsLocked(proto, status.todayStats);
                    proto.end(todayStatsToken);
                    break;
                case (int) SyncStatusProto.StatusInfo.YESTERDAY_STATS:
                    final long yesterdayStatsToken = proto.start(
                            SyncStatusProto.StatusInfo.YESTERDAY_STATS);
                    readSyncStatusStatsLocked(proto, status.yesterdayStats);
                    proto.end(yesterdayStatsToken);
                    break;
                case (int) SyncStatusProto.StatusInfo.PER_SOURCE_LAST_SUCCESS_TIMES:
                    final long successTime = proto.readLong(
                            SyncStatusProto.StatusInfo.PER_SOURCE_LAST_SUCCESS_TIMES);
                    if (successTimesCount == status.perSourceLastSuccessTimes.length) {
                        Slog.w(TAG, "Attempted to read more per source last success times "
                                + "than expected; data might be corrupted.");
                        break;
                    }
                    status.perSourceLastSuccessTimes[successTimesCount] = successTime;
                    successTimesCount++;
                    break;
                case (int) SyncStatusProto.StatusInfo.PER_SOURCE_LAST_FAILURE_TIMES:
                    final long failureTime = proto.readLong(
                            SyncStatusProto.StatusInfo.PER_SOURCE_LAST_FAILURE_TIMES);
                    if (failureTimesCount == status.perSourceLastFailureTimes.length) {
                        Slog.w(TAG, "Attempted to read more per source last failure times "
                                + "than expected; data might be corrupted.");
                        break;
                    }
                    status.perSourceLastFailureTimes[failureTimesCount] = failureTime;
                    failureTimesCount++;
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    status.populateLastEventsInformation(lastEventInformation);
                    return status;
            }
        }
    }

    private Pair<Long, String> parseLastEventInfoLocked(ProtoInputStream proto) throws IOException {
        long time = 0;
        String message = null;
        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT_TIME:
                    time = proto.readLong(SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT:
                    message = proto.readString(SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return message == null ? null : new Pair<>(time, message);
            }
        }
    }

    private void readSyncStatusStatsLocked(ProtoInputStream proto, SyncStatusInfo.Stats stats)
            throws IOException {
        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatusProto.StatusInfo.Stats.TOTAL_ELAPSED_TIME:
                    stats.totalElapsedTime = proto.readLong(
                            SyncStatusProto.StatusInfo.Stats.TOTAL_ELAPSED_TIME);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SYNCS:
                    stats.numSyncs = proto.readInt(SyncStatusProto.StatusInfo.Stats.NUM_SYNCS);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_FAILURES:
                    stats.numFailures = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_FAILURES);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_CANCELS:
                    stats.numCancels = proto.readInt(SyncStatusProto.StatusInfo.Stats.NUM_CANCELS);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_OTHER:
                    stats.numSourceOther = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_OTHER);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_LOCAL:
                    stats.numSourceLocal = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_LOCAL);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_POLL:
                    stats.numSourcePoll = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_POLL);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_USER:
                    stats.numSourceUser = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_USER);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_PERIODIC:
                    stats.numSourcePeriodic = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_PERIODIC);
                    break;
                case (int) SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_FEED:
                    stats.numSourceFeed = proto.readInt(
                            SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_FEED);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    /**
     * Write all sync status to the sync status file.
     */
    @VisibleForTesting
    void writeStatusLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG_FILE, "Writing new " + mStatusFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        mHandler.removeMessages(MSG_WRITE_STATUS);

        FileOutputStream fos = null;
        try {
            fos = mStatusFile.startWrite();
            writeStatusInfoLocked(fos);
            mStatusFile.finishWrite(fos);
            fos = null;
        } catch (IOException | IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write sync status to proto.", e);
        } finally {
            // when fos is null (successful write), this is a no-op.
            mStatusFile.failWrite(fos);
        }
    }

    private void writeStatusInfoLocked(OutputStream out) {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        final int size = mSyncStatus.size();
        for (int i = 0; i < size; i++) {
            final SyncStatusInfo info = mSyncStatus.valueAt(i);
            final long token = proto.start(SyncStatusProto.STATUS);
            // authority id should be written first to take advantage of the fast path in read
            proto.write(SyncStatusProto.StatusInfo.AUTHORITY_ID, info.authorityId);
            proto.write(SyncStatusProto.StatusInfo.LAST_SUCCESS_TIME, info.lastSuccessTime);
            proto.write(SyncStatusProto.StatusInfo.LAST_SUCCESS_SOURCE, info.lastSuccessSource);
            proto.write(SyncStatusProto.StatusInfo.LAST_FAILURE_TIME, info.lastFailureTime);
            proto.write(SyncStatusProto.StatusInfo.LAST_FAILURE_SOURCE, info.lastFailureSource);
            proto.write(SyncStatusProto.StatusInfo.LAST_FAILURE_MESSAGE, info.lastFailureMesg);
            proto.write(SyncStatusProto.StatusInfo.INITIAL_FAILURE_TIME, info.initialFailureTime);
            proto.write(SyncStatusProto.StatusInfo.PENDING, info.pending);
            proto.write(SyncStatusProto.StatusInfo.INITIALIZE, info.initialize);
            final int periodicSyncTimesSize = info.getPeriodicSyncTimesSize();
            for (int j = 0; j < periodicSyncTimesSize; j++) {
                proto.write(SyncStatusProto.StatusInfo.PERIODIC_SYNC_TIMES,
                        info.getPeriodicSyncTime(j));
            }
            final int lastEventsSize = info.getEventCount();
            for (int j = 0; j < lastEventsSize; j++) {
                final long eventToken = proto.start(SyncStatusProto.StatusInfo.LAST_EVENT_INFO);
                proto.write(SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT_TIME,
                        info.getEventTime(j));
                proto.write(SyncStatusProto.StatusInfo.LastEventInfo.LAST_EVENT, info.getEvent(j));
                proto.end(eventToken);
            }
            proto.write(SyncStatusProto.StatusInfo.LAST_TODAY_RESET_TIME, info.lastTodayResetTime);

            final long totalStatsToken = proto.start(SyncStatusProto.StatusInfo.TOTAL_STATS);
            writeStatusStatsLocked(proto, info.totalStats);
            proto.end(totalStatsToken);
            final long todayStatsToken = proto.start(SyncStatusProto.StatusInfo.TODAY_STATS);
            writeStatusStatsLocked(proto, info.todayStats);
            proto.end(todayStatsToken);
            final long yesterdayStatsToken = proto.start(
                    SyncStatusProto.StatusInfo.YESTERDAY_STATS);
            writeStatusStatsLocked(proto, info.yesterdayStats);
            proto.end(yesterdayStatsToken);

            final int lastSuccessTimesSize = info.perSourceLastSuccessTimes.length;
            for (int j = 0; j < lastSuccessTimesSize; j++) {
                proto.write(SyncStatusProto.StatusInfo.PER_SOURCE_LAST_SUCCESS_TIMES,
                        info.perSourceLastSuccessTimes[j]);
            }
            final int lastFailureTimesSize = info.perSourceLastFailureTimes.length;
            for (int j = 0; j < lastFailureTimesSize; j++) {
                proto.write(SyncStatusProto.StatusInfo.PER_SOURCE_LAST_FAILURE_TIMES,
                        info.perSourceLastFailureTimes[j]);
            }
            proto.end(token);
        }
        proto.flush();
    }

    private void writeStatusStatsLocked(ProtoOutputStream proto, SyncStatusInfo.Stats stats) {
        proto.write(SyncStatusProto.StatusInfo.Stats.TOTAL_ELAPSED_TIME, stats.totalElapsedTime);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SYNCS, stats.numSyncs);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_FAILURES, stats.numFailures);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_CANCELS, stats.numCancels);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_OTHER, stats.numSourceOther);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_LOCAL, stats.numSourceLocal);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_POLL, stats.numSourcePoll);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_USER, stats.numSourceUser);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_PERIODIC, stats.numSourcePeriodic);
        proto.write(SyncStatusProto.StatusInfo.Stats.NUM_SOURCE_FEED, stats.numSourceFeed);
    }

    private void requestSync(AuthorityInfo authorityInfo, int reason, Bundle extras,
            @SyncExemption int syncExemptionFlag, int callingUid, int callingPid) {
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && mSyncRequestListener != null) {
            mSyncRequestListener.onSyncRequest(authorityInfo.target, reason, extras,
                    syncExemptionFlag, callingUid, callingPid);
        } else {
            SyncRequest.Builder req =
                    new SyncRequest.Builder()
                            .syncOnce()
                            .setExtras(extras);
            req.setSyncAdapter(authorityInfo.target.account, authorityInfo.target.provider);
            ContentResolver.requestSync(req.build());
        }
    }

    private void requestSync(Account account, int userId, int reason, String authority,
            Bundle extras, @SyncExemption int syncExemptionFlag, int callingUid, int callingPid) {
        // If this is happening in the system process, then call the syncrequest listener
        // to make a request back to the SyncManager directly.
        // If this is probably a test instance, then call back through the ContentResolver
        // which will know which userId to apply based on the Binder id.
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && mSyncRequestListener != null) {
            mSyncRequestListener.onSyncRequest(
                    new EndPoint(account, authority, userId),
                    reason, extras, syncExemptionFlag, callingUid, callingPid);
        } else {
            ContentResolver.requestSync(account, authority, extras);
        }
    }

    public static final int STATISTICS_FILE_END = 0;
    public static final int STATISTICS_FILE_ITEM_OLD = 100;
    public static final int STATISTICS_FILE_ITEM = 101;

    private void readStatsParcelLocked(File parcel) {
        Parcel in = Parcel.obtain();
        try {
            final AtomicFile parcelFile = new AtomicFile(parcel);
            byte[] data = parcelFile.readFully();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            int index = 0;
            while ((token=in.readInt()) != STATISTICS_FILE_END) {
                if (token == STATISTICS_FILE_ITEM || token == STATISTICS_FILE_ITEM_OLD) {
                    int day = in.readInt();
                    if (token == STATISTICS_FILE_ITEM_OLD) {
                        day = day - 2009 + 14245;  // Magic!
                    }
                    DayStats ds = new DayStats(day);
                    ds.successCount = in.readInt();
                    ds.successTime = in.readLong();
                    ds.failureCount = in.readInt();
                    ds.failureTime = in.readLong();
                    if (index < mDayStats.length) {
                        mDayStats[index] = ds;
                        index++;
                    }
                } else {
                    // Ooops.
                    Slog.w(TAG, "Unknown stats token: " + token);
                    break;
                }
            }
        } catch (IOException e) {
            Slog.i(TAG, "No initial statistics");
        } finally {
            in.recycle();
        }
    }

    private void upgradeStatisticsIfNeededLocked() {
        final File parcelStats = new File(mSyncDir, LEGACY_STATISTICS_FILE_NAME);
        if (parcelStats.exists() && !mStatisticsFile.exists()) {
            readStatsParcelLocked(parcelStats);
            writeStatisticsLocked();
        }

        // if upgrade to proto was successful, delete parcel file
        if (DELETE_LEGACY_PARCEL_FILES && parcelStats.exists() && mStatisticsFile.exists()) {
            parcelStats.delete();
        }
    }

    /**
     * Read all sync statistics back in to the initial engine state.
     */
    private void readStatisticsLocked() {
        upgradeStatisticsIfNeededLocked();

        if (!mStatisticsFile.exists()) {
            return;
        }
        try {
            try (FileInputStream in = mStatisticsFile.openRead()) {
                readDayStatsLocked(in);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to read day stats file.", e);
        }
    }

    private void readDayStatsLocked(InputStream in) throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        int statsCount = 0;
        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatisticsProto.STATS:
                    final long token = proto.start(SyncStatisticsProto.STATS);
                    final DayStats stats = readIndividualDayStatsLocked(proto);
                    proto.end(token);
                    mDayStats[statsCount] = stats;
                    statsCount++;
                    if (statsCount == mDayStats.length) {
                        return;
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    private DayStats readIndividualDayStatsLocked(ProtoInputStream proto) throws IOException {
        DayStats stats;
        if (proto.nextField(SyncStatisticsProto.DayStats.DAY)) {
            // fast-path; this should work for most cases since the day is written first
            stats = new DayStats(proto.readInt(SyncStatisticsProto.DayStats.DAY));
        } else {
            // placeholder to read other data; assume the default day as 0
            stats = new DayStats(0);
        }

        while (true) {
            switch (proto.nextField()) {
                case (int) SyncStatisticsProto.DayStats.DAY:
                    // fast-path failed for some reason, rebuild stats from placeholder object
                    Slog.w(TAG, "Failed to read the day via fast-path; some data "
                            + "might not have been read.");
                    final DayStats temp = new DayStats(
                            proto.readInt(SyncStatisticsProto.DayStats.DAY));
                    temp.successCount = stats.successCount;
                    temp.successTime = stats.successTime;
                    temp.failureCount = stats.failureCount;
                    temp.failureTime = stats.failureTime;
                    stats = temp;
                    break;
                case (int) SyncStatisticsProto.DayStats.SUCCESS_COUNT:
                    stats.successCount = proto.readInt(SyncStatisticsProto.DayStats.SUCCESS_COUNT);
                    break;
                case (int) SyncStatisticsProto.DayStats.SUCCESS_TIME:
                    stats.successTime = proto.readLong(SyncStatisticsProto.DayStats.SUCCESS_TIME);
                    break;
                case (int) SyncStatisticsProto.DayStats.FAILURE_COUNT:
                    stats.failureCount = proto.readInt(SyncStatisticsProto.DayStats.FAILURE_COUNT);
                    break;
                case (int) SyncStatisticsProto.DayStats.FAILURE_TIME:
                    stats.failureTime = proto.readLong(SyncStatisticsProto.DayStats.FAILURE_TIME);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return stats;
            }
        }
    }

    /**
     * Write all sync statistics to the sync status file.
     */
    @VisibleForTesting
    void writeStatisticsLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG, "Writing new " + mStatisticsFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        mHandler.removeMessages(MSG_WRITE_STATISTICS);

        FileOutputStream fos = null;
        try {
            fos = mStatisticsFile.startWrite();
            writeDayStatsLocked(fos);
            mStatisticsFile.finishWrite(fos);
            fos = null;
        } catch (IOException | IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write day stats to proto.", e);
        } finally {
            // when fos is null (successful write), this is a no-op.
            mStatisticsFile.failWrite(fos);
        }
    }

    private void writeDayStatsLocked(OutputStream out)
            throws IOException, IllegalArgumentException {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        final int size = mDayStats.length;
        for (int i = 0; i < size; i++) {
            final DayStats stats = mDayStats[i];
            if (stats == null) {
                break;
            }
            final long token = proto.start(SyncStatisticsProto.STATS);
            // day should be written first to take advantage of the fast path in read
            proto.write(SyncStatisticsProto.DayStats.DAY, stats.day);
            proto.write(SyncStatisticsProto.DayStats.SUCCESS_COUNT, stats.successCount);
            proto.write(SyncStatisticsProto.DayStats.SUCCESS_TIME, stats.successTime);
            proto.write(SyncStatisticsProto.DayStats.FAILURE_COUNT, stats.failureCount);
            proto.write(SyncStatisticsProto.DayStats.FAILURE_TIME, stats.failureTime);
            proto.end(token);
        }
        proto.flush();
    }

    /**
     * Let the BackupManager know that account sync settings have changed. This will trigger
     * {@link com.android.server.backup.SystemBackupAgent} to run.
     */
    public void queueBackup() {
        BackupManager.dataChanged("android");
    }

    public void setClockValid() {
        if (!mIsClockValid) {
            mIsClockValid = true;
            Slog.w(TAG, "Clock is valid now.");
        }
    }

    public boolean isClockValid() {
        return mIsClockValid;
    }

    public void resetTodayStats(boolean force) {
        if (force) {
            Log.w(TAG, "Force resetting today stats.");
        }
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i = 0; i < N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                cur.maybeResetTodayStats(isClockValid(), force);
            }
            writeStatusLocked();
        }
    }
}
