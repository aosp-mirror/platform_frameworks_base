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

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountManager;
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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
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
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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

    /** String names for the sync source types. */
    public static final String[] SOURCES = { "OTHER",
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
                @SyncExemption int syncExemptionFlag);
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
    private final SparseArray<AuthorityInfo> mAuthorities =
            new SparseArray<AuthorityInfo>();

    private final HashMap<AccountAndUser, AccountInfo> mAccounts
            = new HashMap<AccountAndUser, AccountInfo>();

    private final SparseArray<ArrayList<SyncInfo>> mCurrentSyncs
            = new SparseArray<ArrayList<SyncInfo>>();

    private final SparseArray<SyncStatusInfo> mSyncStatus =
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
    private final DayStats[] mDayStats = new DayStats[7*4];
    private final Calendar mCal;
    private int mYear;
    private int mYearInDays;

    private final Context mContext;

    private static volatile SyncStorageEngine sSyncStorageEngine = null;

    private int mSyncRandomOffset;

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

        File systemDir = new File(dataDir, "system");
        File syncDir = new File(systemDir, "sync");
        syncDir.mkdirs();

        maybeDeleteLegacyPendingInfoLocked(syncDir);

        mAccountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"), "sync-accounts");
        mStatusFile = new AtomicFile(new File(syncDir, "status.bin"), "sync-status");
        mStatisticsFile = new AtomicFile(new File(syncDir, "stats.bin"), "sync-stats");

        readAccountInfoLocked();
        readStatusLocked();
        readStatisticsLocked();
        readAndDeleteLegacyAccountInfoLocked();
        writeAccountInfoLocked();
        writeStatusLocked();
        writeStatisticsLocked();

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

    public void addStatusChangeListener(int mask, ISyncStatusObserver callback) {
        synchronized (mAuthorities) {
            mChangeListeners.register(callback, mask);
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

    void reportChange(int which) {
        ArrayList<ISyncStatusObserver> reports = null;
        synchronized (mAuthorities) {
            int i = mChangeListeners.beginBroadcast();
            while (i > 0) {
                i--;
                Integer mask = (Integer)mChangeListeners.getBroadcastCookie(i);
                if ((which & mask.intValue()) == 0) {
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
            boolean sync, @SyncExemption int syncExemptionFlag, int callingUid) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.d(TAG, "setSyncAutomatically: " + /* account + */" provider " + providerName
                    + ", user " + userId + " -> " + sync);
        }
        mLogger.log("Set sync auto account=", account,
                " user=", userId,
                " authority=", providerName,
                " value=", Boolean.toString(sync),
                " callingUid=", callingUid);
        synchronized (mAuthorities) {
            AuthorityInfo authority =
                    getOrCreateAuthorityLocked(
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
                    syncExemptionFlag);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
            int callingUid) {
        setSyncableStateForEndPoint(new EndPoint(account, providerName, userId), syncable,
                callingUid);
    }

    /**
     * An enabled sync service and a syncable provider's adapter both get resolved to the same
     * persisted variable - namely the "syncable" attribute for an AuthorityInfo in accounts.xml.
     * @param target target to set value for.
     * @param syncable 0 indicates unsyncable, <0 unknown, >0 is active/syncable.
     */
    private void setSyncableStateForEndPoint(EndPoint target, int syncable, int callingUid) {
        AuthorityInfo aInfo;
        mLogger.log("Set syncable ", target, " value=", Integer.toString(syncable),
                " callingUid=", callingUid);
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
                    ContentResolver.SYNC_EXEMPTION_NONE);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
        boolean changed = false;
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
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
            @SyncExemption int syncExemptionFlag, int callingUid) {
        mLogger.log("Set master enabled=", flag, " user=", userId,
                " caller=" + callingUid);
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
                    syncExemptionFlag);
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
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
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
    }

    /**
     * Called when the set of account has changed, given the new array of
     * active accounts.
     */
    public void doDatabaseCleanup(Account[] accounts, int userId) {
        synchronized (mAuthorities) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "Updating for new accounts...");
            }
            SparseArray<AuthorityInfo> removing = new SparseArray<AuthorityInfo>();
            Iterator<AccountInfo> accIt = mAccounts.values().iterator();
            while (accIt.hasNext()) {
                AccountInfo acc = accIt.next();
                if (!ArrayUtils.contains(accounts, acc.accountAndUser.account)
                        && acc.accountAndUser.userId == userId) {
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
                        + " extras=" + activeSyncContext.mSyncOperation.extras);
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
        reportActiveChange();
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

        reportActiveChange();
    }

    /**
     * To allow others to send active change reports, to poke clients.
     */
    public void reportActiveChange() {
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE);
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
            item.extras = op.extras;
            item.event = EVENT_START;
            mSyncHistory.add(0, item);
            while (mSyncHistory.size() > MAX_HISTORY) {
                mSyncHistory.remove(mSyncHistory.size()-1);
            }
            id = item.historyId;
            if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "returning historyId " + id);
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS);
        return id;
    }

    public void stopSyncEvent(long historyId, long elapsedTime, String resultMessage,
                              long downstreamActivity, long upstreamActivity) {
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
                status.lastSuccessTime = lastSyncTime;
                status.lastSuccessSource = item.source;
                status.lastFailureTime = 0;
                status.lastFailureSource = -1;
                status.lastFailureMesg = null;
                status.initialFailureTime = 0;
                ds.successCount++;
                ds.successTime += elapsedTime;
            } else if (!MESG_CANCELED.equals(resultMessage)) {
                if (status.lastFailureTime == 0) {
                    writeStatusNow = true;
                }
                status.totalStats.numFailures++;
                status.todayStats.numFailures++;

                status.lastFailureTime = lastSyncTime;
                status.lastFailureSource = item.source;
                status.lastFailureMesg = resultMessage;
                if (status.initialFailureTime == 0) {
                    status.initialFailureTime = lastSyncTime;
                }
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

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS);
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
            readAndDeleteLegacyAccountInfoLocked();
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
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
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
                String listen = parser.getAttributeValue(null, XML_ATTR_LISTEN_FOR_TICKLES);
                String versionString = parser.getAttributeValue(null, "version");
                int version;
                try {
                    version = (versionString == null) ? 0 : Integer.parseInt(versionString);
                } catch (NumberFormatException e) {
                    version = 0;
                }

                if (version < 3) {
                    mGrantSyncAdaptersAccountAccess = true;
                }

                String nextIdString = parser.getAttributeValue(null, XML_ATTR_NEXT_AUTHORITY_ID);
                try {
                    int id = (nextIdString == null) ? 0 : Integer.parseInt(nextIdString);
                    mNextAuthorityId = Math.max(mNextAuthorityId, id);
                } catch (NumberFormatException e) {
                    // don't care
                }
                String offsetString = parser.getAttributeValue(null, XML_ATTR_SYNC_RANDOM_OFFSET);
                try {
                    mSyncRandomOffset = (offsetString == null) ? 0 : Integer.parseInt(offsetString);
                } catch (NumberFormatException e) {
                    mSyncRandomOffset = 0;
                }
                if (mSyncRandomOffset == 0) {
                    Random random = new Random(System.currentTimeMillis());
                    mSyncRandomOffset = random.nextInt(86400);
                }
                mMasterSyncAutomatically.put(0, listen == null || Boolean.parseBoolean(listen));
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
     * pending.xml was used starting in KLP.
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

    private void parseListenForTickles(XmlPullParser parser) {
        String user = parser.getAttributeValue(null, XML_ATTR_USER);
        int userId = 0;
        try {
            userId = Integer.parseInt(user);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "error parsing the user for listen-for-tickles", e);
        } catch (NullPointerException e) {
            Slog.e(TAG, "the user in listen-for-tickles is null", e);
        }
        String enabled = parser.getAttributeValue(null, XML_ATTR_ENABLED);
        boolean listen = enabled == null || Boolean.parseBoolean(enabled);
        mMasterSyncAutomatically.put(userId, listen);
    }

    private AuthorityInfo parseAuthority(XmlPullParser parser, int version,
            AccountAuthorityValidator validator) {
        AuthorityInfo authority = null;
        int id = -1;
        try {
            id = Integer.parseInt(parser.getAttributeValue(null, "id"));
        } catch (NumberFormatException e) {
            Slog.e(TAG, "error parsing the id of the authority", e);
        } catch (NullPointerException e) {
            Slog.e(TAG, "the id of the authority is null", e);
        }
        if (id >= 0) {
            String authorityName = parser.getAttributeValue(null, "authority");
            String enabled = parser.getAttributeValue(null, XML_ATTR_ENABLED);
            String syncable = parser.getAttributeValue(null, "syncable");
            String accountName = parser.getAttributeValue(null, "account");
            String accountType = parser.getAttributeValue(null, "type");
            String user = parser.getAttributeValue(null, XML_ATTR_USER);
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            int userId = user == null ? 0 : Integer.parseInt(user);
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
                authority.enabled = enabled == null || Boolean.parseBoolean(enabled);
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
                Slog.w(TAG, "Failure adding authority: account="
                        + accountName + " auth=" + authorityName
                        + " enabled=" + enabled
                        + " syncable=" + syncable);
            }
        }
        return authority;
    }

    /**
     * Parse a periodic sync from accounts.xml. Sets the bundle to be empty.
     */
    private PeriodicSync parsePeriodicSync(XmlPullParser parser, AuthorityInfo authorityInfo) {
        Bundle extras = new Bundle(); // Gets filled in later.
        String periodValue = parser.getAttributeValue(null, "period");
        String flexValue = parser.getAttributeValue(null, "flex");
        final long period;
        long flextime;
        try {
            period = Long.parseLong(periodValue);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "error parsing the period of a periodic sync", e);
            return null;
        } catch (NullPointerException e) {
            Slog.e(TAG, "the period of a periodic sync is null", e);
            return null;
        }
        try {
            flextime = Long.parseLong(flexValue);
        } catch (NumberFormatException e) {
            flextime = calculateDefaultFlexTime(period);
            Slog.e(TAG, "Error formatting value parsed for periodic sync flex: " + flexValue
                    + ", using default: "
                    + flextime);
        } catch (NullPointerException expected) {
            flextime = calculateDefaultFlexTime(period);
            Slog.d(TAG, "No flex time specified for this sync, using a default. period: "
                    + period + " flex: " + flextime);
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

    private void parseExtra(XmlPullParser parser, Bundle extras) {
        String name = parser.getAttributeValue(null, "name");
        String type = parser.getAttributeValue(null, "type");
        String value1 = parser.getAttributeValue(null, "value1");
        String value2 = parser.getAttributeValue(null, "value2");

        try {
            if ("long".equals(type)) {
                extras.putLong(name, Long.parseLong(value1));
            } else if ("integer".equals(type)) {
                extras.putInt(name, Integer.parseInt(value1));
            } else if ("double".equals(type)) {
                extras.putDouble(name, Double.parseDouble(value1));
            } else if ("float".equals(type)) {
                extras.putFloat(name, Float.parseFloat(value1));
            } else if ("boolean".equals(type)) {
                extras.putBoolean(name, Boolean.parseBoolean(value1));
            } else if ("string".equals(type)) {
                extras.putString(name, value1);
            } else if ("account".equals(type)) {
                extras.putParcelable(name, new Account(value1, value2));
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "error parsing bundle value", e);
        } catch (NullPointerException e) {
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
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            out.startTag(null, "accounts");
            out.attribute(null, "version", Integer.toString(ACCOUNTS_VERSION));
            out.attribute(null, XML_ATTR_NEXT_AUTHORITY_ID, Integer.toString(mNextAuthorityId));
            out.attribute(null, XML_ATTR_SYNC_RANDOM_OFFSET, Integer.toString(mSyncRandomOffset));

            // Write the Sync Automatically flags for each user
            final int M = mMasterSyncAutomatically.size();
            for (int m = 0; m < M; m++) {
                int userId = mMasterSyncAutomatically.keyAt(m);
                Boolean listen = mMasterSyncAutomatically.valueAt(m);
                out.startTag(null, XML_TAG_LISTEN_FOR_TICKLES);
                out.attribute(null, XML_ATTR_USER, Integer.toString(userId));
                out.attribute(null, XML_ATTR_ENABLED, Boolean.toString(listen));
                out.endTag(null, XML_TAG_LISTEN_FOR_TICKLES);
            }

            final int N = mAuthorities.size();
            for (int i = 0; i < N; i++) {
                AuthorityInfo authority = mAuthorities.valueAt(i);
                EndPoint info = authority.target;
                out.startTag(null, "authority");
                out.attribute(null, "id", Integer.toString(authority.ident));
                out.attribute(null, XML_ATTR_USER, Integer.toString(info.userId));
                out.attribute(null, XML_ATTR_ENABLED, Boolean.toString(authority.enabled));
                out.attribute(null, "account", info.account.name);
                out.attribute(null, "type", info.account.type);
                out.attribute(null, "authority", info.provider);
                out.attribute(null, "syncable", Integer.toString(authority.syncable));
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

    static int getIntColumn(Cursor c, String name) {
        return c.getInt(c.getColumnIndex(name));
    }

    static long getLongColumn(Cursor c, String name) {
        return c.getLong(c.getColumnIndex(name));
    }

    /**
     * TODO Remove it. It's super old code that was used to migrate the information from a sqlite
     * database that we used a long time ago, and is no longer relevant.
     */
    private void readAndDeleteLegacyAccountInfoLocked() {
        // Look for old database to initialize from.
        File file = mContext.getDatabasePath("syncmanager.db");
        if (!file.exists()) {
            return;
        }
        String path = file.getPath();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (SQLiteException e) {
        }

        if (db != null) {
            final boolean hasType = db.getVersion() >= 11;

            // Copy in all of the status information, as well as accounts.
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Slog.v(TAG_FILE, "Reading legacy sync accounts db");
            }
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("stats, status");
            HashMap<String,String> map = new HashMap<String,String>();
            map.put("_id", "status._id as _id");
            map.put("account", "stats.account as account");
            if (hasType) {
                map.put("account_type", "stats.account_type as account_type");
            }
            map.put("authority", "stats.authority as authority");
            map.put("totalElapsedTime", "totalElapsedTime");
            map.put("numSyncs", "numSyncs");
            map.put("numSourceLocal", "numSourceLocal");
            map.put("numSourcePoll", "numSourcePoll");
            map.put("numSourceServer", "numSourceServer");
            map.put("numSourceUser", "numSourceUser");
            map.put("lastSuccessSource", "lastSuccessSource");
            map.put("lastSuccessTime", "lastSuccessTime");
            map.put("lastFailureSource", "lastFailureSource");
            map.put("lastFailureTime", "lastFailureTime");
            map.put("lastFailureMesg", "lastFailureMesg");
            map.put("pending", "pending");
            qb.setProjectionMap(map);
            qb.appendWhere("stats._id = status.stats_id");
            Cursor c = qb.query(db, null, null, null, null, null, null);
            while (c.moveToNext()) {
                String accountName = c.getString(c.getColumnIndex("account"));
                String accountType = hasType
                        ? c.getString(c.getColumnIndex("account_type")) : null;
                if (accountType == null) {
                    accountType = "com.google";
                }
                String authorityName = c.getString(c.getColumnIndex("authority"));
                AuthorityInfo authority =
                        this.getOrCreateAuthorityLocked(
                                new EndPoint(new Account(accountName, accountType),
                                        authorityName,
                                        0 /* legacy is single-user */)
                                , -1,
                                false);
                if (authority != null) {
                    int i = mSyncStatus.size();
                    boolean found = false;
                    SyncStatusInfo st = null;
                    while (i > 0) {
                        i--;
                        st = mSyncStatus.valueAt(i);
                        if (st.authorityId == authority.ident) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        st = new SyncStatusInfo(authority.ident);
                        mSyncStatus.put(authority.ident, st);
                    }
                    st.totalStats.totalElapsedTime = getLongColumn(c, "totalElapsedTime");
                    st.totalStats.numSyncs = getIntColumn(c, "numSyncs");
                    st.totalStats.numSourceLocal = getIntColumn(c, "numSourceLocal");
                    st.totalStats.numSourcePoll = getIntColumn(c, "numSourcePoll");
                    st.totalStats.numSourceOther = getIntColumn(c, "numSourceServer");
                    st.totalStats.numSourceUser = getIntColumn(c, "numSourceUser");
                    st.totalStats.numSourcePeriodic = 0;
                    st.lastSuccessSource = getIntColumn(c, "lastSuccessSource");
                    st.lastSuccessTime = getLongColumn(c, "lastSuccessTime");
                    st.lastFailureSource = getIntColumn(c, "lastFailureSource");
                    st.lastFailureTime = getLongColumn(c, "lastFailureTime");
                    st.lastFailureMesg = c.getString(c.getColumnIndex("lastFailureMesg"));
                    st.pending = getIntColumn(c, "pending") != 0;
                }
            }

            c.close();

            // Retrieve the settings.
            qb = new SQLiteQueryBuilder();
            qb.setTables("settings");
            c = qb.query(db, null, null, null, null, null, null);
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndex("name"));
                String value = c.getString(c.getColumnIndex("value"));
                if (name == null) continue;
                if (name.equals("listen_for_tickles")) {
                    setMasterSyncAutomatically(value == null || Boolean.parseBoolean(value), 0,
                            ContentResolver.SYNC_EXEMPTION_NONE, SyncLogger.CALLING_UID_SELF);
                } else if (name.startsWith("sync_provider_")) {
                    String provider = name.substring("sync_provider_".length(),
                            name.length());
                    int i = mAuthorities.size();
                    while (i > 0) {
                        i--;
                        AuthorityInfo authority = mAuthorities.valueAt(i);
                        if (authority.target.provider.equals(provider)) {
                            authority.enabled = value == null || Boolean.parseBoolean(value);
                            authority.syncable = 1;
                        }
                    }
                }
            }

            c.close();

            db.close();

            (new File(path)).delete();
        }
    }

    public static final int STATUS_FILE_END = 0;
    public static final int STATUS_FILE_ITEM = 100;

    /**
     * Read all sync status back in to the initial engine state.
     */
    private void readStatusLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG_FILE, "Reading " + mStatusFile.getBaseFile());
        }
        try {
            byte[] data = mStatusFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            while ((token=in.readInt()) != STATUS_FILE_END) {
                if (token == STATUS_FILE_ITEM) {
                    SyncStatusInfo status = new SyncStatusInfo(in);
                    if (mAuthorities.indexOfKey(status.authorityId) >= 0) {
                        status.pending = false;
                        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                            Slog.v(TAG_FILE, "Adding status for id " + status.authorityId);
                        }
                        mSyncStatus.put(status.authorityId, status);
                    }
                } else {
                    // Ooops.
                    Slog.w(TAG, "Unknown status token: " + token);
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Slog.i(TAG, "No initial status");
        }
    }

    /**
     * Write all sync status to the sync status file.
     */
    private void writeStatusLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG_FILE, "Writing new " + mStatusFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        mHandler.removeMessages(MSG_WRITE_STATUS);

        FileOutputStream fos = null;
        try {
            fos = mStatusFile.startWrite();
            Parcel out = Parcel.obtain();
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo status = mSyncStatus.valueAt(i);
                out.writeInt(STATUS_FILE_ITEM);
                status.writeToParcel(out, 0);
            }
            out.writeInt(STATUS_FILE_END);
            fos.write(out.marshall());
            out.recycle();

            mStatusFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing status", e1);
            if (fos != null) {
                mStatusFile.failWrite(fos);
            }
        }
    }

    private void requestSync(AuthorityInfo authorityInfo, int reason, Bundle extras,
            @SyncExemption int syncExemptionFlag) {
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && mSyncRequestListener != null) {
            mSyncRequestListener.onSyncRequest(authorityInfo.target, reason, extras,
                    syncExemptionFlag);
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
            Bundle extras, @SyncExemption int syncExemptionFlag) {
        // If this is happening in the system process, then call the syncrequest listener
        // to make a request back to the SyncManager directly.
        // If this is probably a test instance, then call back through the ContentResolver
        // which will know which userId to apply based on the Binder id.
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && mSyncRequestListener != null) {
            mSyncRequestListener.onSyncRequest(
                    new EndPoint(account, authority, userId),
                    reason, extras, syncExemptionFlag);
        } else {
            ContentResolver.requestSync(account, authority, extras);
        }
    }

    public static final int STATISTICS_FILE_END = 0;
    public static final int STATISTICS_FILE_ITEM_OLD = 100;
    public static final int STATISTICS_FILE_ITEM = 101;

    /**
     * Read all sync statistics back in to the initial engine state.
     */
    private void readStatisticsLocked() {
        try {
            byte[] data = mStatisticsFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            int index = 0;
            while ((token=in.readInt()) != STATISTICS_FILE_END) {
                if (token == STATISTICS_FILE_ITEM
                        || token == STATISTICS_FILE_ITEM_OLD) {
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
        } catch (java.io.IOException e) {
            Slog.i(TAG, "No initial statistics");
        }
    }

    /**
     * Write all sync statistics to the sync status file.
     */
    private void writeStatisticsLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Slog.v(TAG, "Writing new " + mStatisticsFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        mHandler.removeMessages(MSG_WRITE_STATISTICS);

        FileOutputStream fos = null;
        try {
            fos = mStatisticsFile.startWrite();
            Parcel out = Parcel.obtain();
            final int N = mDayStats.length;
            for (int i=0; i<N; i++) {
                DayStats ds = mDayStats[i];
                if (ds == null) {
                    break;
                }
                out.writeInt(STATISTICS_FILE_ITEM);
                out.writeInt(ds.day);
                out.writeInt(ds.successCount);
                out.writeLong(ds.successTime);
                out.writeInt(ds.failureCount);
                out.writeLong(ds.failureTime);
            }
            out.writeInt(STATISTICS_FILE_END);
            fos.write(out.marshall());
            out.recycle();

            mStatisticsFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing stats", e1);
            if (fos != null) {
                mStatisticsFile.failWrite(fos);
            }
        }
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
