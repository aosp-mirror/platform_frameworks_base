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
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ISyncStatusObserver;
import android.content.PeriodicSync;
import android.content.SyncInfo;
import android.content.SyncStatusInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
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
import java.io.IOException;
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
public class SyncStorageEngine extends Handler {

    private static final String TAG = "SyncManager";
    private static final boolean DEBUG = false;
    private static final String TAG_FILE = "SyncManagerFile";

    private static final String XML_ATTR_NEXT_AUTHORITY_ID = "nextAuthorityId";
    private static final String XML_ATTR_LISTEN_FOR_TICKLES = "listen-for-tickles";
    private static final String XML_ATTR_SYNC_RANDOM_OFFSET = "offsetInSeconds";
    private static final String XML_ATTR_ENABLED = "enabled";
    private static final String XML_ATTR_USER = "user";
    private static final String XML_TAG_LISTEN_FOR_TICKLES = "listenForTickles";

    /** Default time for a periodic sync. */
    private static final long DEFAULT_POLL_FREQUENCY_SECONDS = 60 * 60 * 24; // One day

    /** Percentage of period that is flex by default, if no flex is set. */
    private static final double DEFAULT_FLEX_PERCENT_SYNC = 0.04;

    /** Lower bound on sync time from which we assign a default flex time. */
    private static final long DEFAULT_MIN_FLEX_ALLOWED_SECS = 5;

    @VisibleForTesting
    static final long MILLIS_IN_4WEEKS = 1000L * 60 * 60 * 24 * 7 * 4;

    /** Enum value for a sync start event. */
    public static final int EVENT_START = 0;

    /** Enum value for a sync stop event. */
    public static final int EVENT_STOP = 1;

    // TODO: i18n -- grab these out of resources.
    /** String names for the sync event types. */
    public static final String[] EVENTS = { "START", "STOP" };

    /** Enum value for a server-initiated sync. */
    public static final int SOURCE_SERVER = 0;

    /** Enum value for a local-initiated sync. */
    public static final int SOURCE_LOCAL = 1;
    /**
     * Enum value for a poll-based sync (e.g., upon connection to
     * network)
     */
    public static final int SOURCE_POLL = 2;

    /** Enum value for a user-initiated sync. */
    public static final int SOURCE_USER = 3;

    /** Enum value for a periodic sync. */
    public static final int SOURCE_PERIODIC = 4;

    public static final long NOT_IN_BACKOFF_MODE = -1;

    // TODO: i18n -- grab these out of resources.
    /** String names for the sync source types. */
    public static final String[] SOURCES = { "SERVER",
                                             "LOCAL",
                                             "POLL",
                                             "USER",
                                             "PERIODIC" };

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
    private static final int ACCOUNTS_VERSION = 2;

    private static HashMap<String, String> sAuthorityRenames;

    static {
        sAuthorityRenames = new HashMap<String, String>();
        sAuthorityRenames.put("contacts", "com.android.contacts");
        sAuthorityRenames.put("calendar", "com.android.calendar");
    }

    public static class PendingOperation {
        final Account account;
        final int userId;
        final int reason;
        final int syncSource;
        final String authority;
        final Bundle extras;        // note: read-only.
        final ComponentName serviceName;
        final boolean expedited;

        int authorityId;
        byte[] flatExtras;

        PendingOperation(Account account, int userId, int reason, int source,
                String authority, Bundle extras, boolean expedited) {
            this.account = account;
            this.userId = userId;
            this.syncSource = source;
            this.reason = reason;
            this.authority = authority;
            this.extras = extras != null ? new Bundle(extras) : extras;
            this.expedited = expedited;
            this.authorityId = -1;
            this.serviceName = null;
        }

        PendingOperation(PendingOperation other) {
            this.account = other.account;
            this.userId = other.userId;
            this.reason = other.reason;
            this.syncSource = other.syncSource;
            this.authority = other.authority;
            this.extras = other.extras;
            this.authorityId = other.authorityId;
            this.expedited = other.expedited;
            this.serviceName = other.serviceName;
        }
    }

    static class AccountInfo {
        final AccountAndUser accountAndUser;
        final HashMap<String, AuthorityInfo> authorities =
                new HashMap<String, AuthorityInfo>();

        AccountInfo(AccountAndUser accountAndUser) {
            this.accountAndUser = accountAndUser;
        }
    }

    public static class AuthorityInfo {
        final ComponentName service;
        final Account account;
        final int userId;
        final String authority;
        final int ident;
        boolean enabled;
        int syncable;
        long backoffTime;
        long backoffDelay;
        long delayUntil;
        final ArrayList<PeriodicSync> periodicSyncs;

        /**
         * Copy constructor for making deep-ish copies. Only the bundles stored
         * in periodic syncs can make unexpected changes.
         *
         * @param toCopy AuthorityInfo to be copied.
         */
        AuthorityInfo(AuthorityInfo toCopy) {
            account = toCopy.account;
            userId = toCopy.userId;
            authority = toCopy.authority;
            service = toCopy.service;
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

        /**
         * Create an authority with one periodic sync scheduled with an empty bundle and syncing
         * every day. An empty bundle is considered equal to any other bundle see
         * {@link PeriodicSync.syncExtrasEquals}.
         * @param account Account that this authority syncs.
         * @param userId which user this sync is registered for.
         * @param userId user for which this authority is registered.
         * @param ident id of this authority.
         */
        AuthorityInfo(Account account, int userId, String authority, int ident) {
            this.account = account;
            this.userId = userId;
            this.authority = authority;
            this.service = null;
            this.ident = ident;
            enabled = SYNC_ENABLED_DEFAULT;
            syncable = -1; // default to "unknown"
            backoffTime = -1; // if < 0 then we aren't in backoff mode
            backoffDelay = -1; // if < 0 then we aren't in backoff mode
            periodicSyncs = new ArrayList<PeriodicSync>();
            // Old version adds one periodic sync a day.
            periodicSyncs.add(new PeriodicSync(account, authority,
                                new Bundle(),
                                DEFAULT_POLL_FREQUENCY_SECONDS,
                                calculateDefaultFlexTime(DEFAULT_POLL_FREQUENCY_SECONDS)));
        }

        /**
         * Create an authority with one periodic sync scheduled with an empty bundle and syncing
         * every day using a sync service.
         * @param cname sync service identifier.
         * @param userId user for which this authority is registered.
         * @param ident id of this authority.
         */
        AuthorityInfo(ComponentName cname, int userId, int ident) {
            this.account = null;
            this.userId = userId;
            this.authority = null;
            this.service = cname;
            this.ident = ident;
            // Sync service is always enabled.
            enabled = true;
            syncable = -1; // default to "unknown"
            backoffTime = -1; // if < 0 then we aren't in backoff mode
            backoffDelay = -1; // if < 0 then we aren't in backoff mode
            periodicSyncs = new ArrayList<PeriodicSync>();
            periodicSyncs.add(new PeriodicSync(account, authority,
                                new Bundle(),
                                DEFAULT_POLL_FREQUENCY_SECONDS,
                                calculateDefaultFlexTime(DEFAULT_POLL_FREQUENCY_SECONDS)));
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
        /**
         * Called when a sync is needed on an account(s) due to some change in state.
         * @param account
         * @param userId
         * @param reason
         * @param authority
         * @param extras
         */
        public void onSyncRequest(Account account, int userId, int reason, String authority,
                Bundle extras);
    }

    // Primary list of all syncable authorities.  Also our global lock.
    private final SparseArray<AuthorityInfo> mAuthorities =
            new SparseArray<AuthorityInfo>();

    private final HashMap<AccountAndUser, AccountInfo> mAccounts
            = new HashMap<AccountAndUser, AccountInfo>();

    private final ArrayList<PendingOperation> mPendingOperations =
            new ArrayList<PendingOperation>();

    private final SparseArray<ArrayList<SyncInfo>> mCurrentSyncs
            = new SparseArray<ArrayList<SyncInfo>>();

    private final SparseArray<SyncStatusInfo> mSyncStatus =
            new SparseArray<SyncStatusInfo>();

    private final ArrayList<SyncHistoryItem> mSyncHistory =
            new ArrayList<SyncHistoryItem>();

    private final RemoteCallbackList<ISyncStatusObserver> mChangeListeners
            = new RemoteCallbackList<ISyncStatusObserver>();

    /** Reverse mapping for component name -> <userid -> authority id>. */
    private final HashMap<ComponentName, SparseArray<AuthorityInfo>> mServices =
            new HashMap<ComponentName, SparseArray<AuthorityInfo>>();

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

    /**
     * This file contains the pending sync operations.  It is a binary file,
     * which must be updated every time an operation is added or removed,
     * so we have special handling of it.
     */
    private final AtomicFile mPendingFile;
    private static final int PENDING_FINISH_TO_WRITE = 4;
    private int mNumPendingFinished = 0;

    private int mNextHistoryId = 0;
    private SparseArray<Boolean> mMasterSyncAutomatically = new SparseArray<Boolean>();
    private boolean mDefaultMasterSyncAutomatically;

    private OnSyncRequestListener mSyncRequestListener;

    private SyncStorageEngine(Context context, File dataDir) {
        mContext = context;
        sSyncStorageEngine = this;

        mCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));

        mDefaultMasterSyncAutomatically = mContext.getResources().getBoolean(
               com.android.internal.R.bool.config_syncstorageengine_masterSyncAutomatically);

        File systemDir = new File(dataDir, "system");
        File syncDir = new File(systemDir, "sync");
        syncDir.mkdirs();

        maybeDeleteLegacyPendingInfoLocked(syncDir);

        mAccountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        mStatusFile = new AtomicFile(new File(syncDir, "status.bin"));
        mPendingFile = new AtomicFile(new File(syncDir, "pending.xml"));
        mStatisticsFile = new AtomicFile(new File(syncDir, "stats.bin"));

        readAccountInfoLocked();
        readStatusLocked();
        readPendingOperationsLocked();
        readStatisticsLocked();
        readAndDeleteLegacyAccountInfoLocked();
        writeAccountInfoLocked();
        writeStatusLocked();
        writePendingOperationsLocked();
        writeStatisticsLocked();
    }

    public static SyncStorageEngine newTestInstance(Context context) {
        return new SyncStorageEngine(context, context.getFilesDir());
    }

    public static void init(Context context) {
        if (sSyncStorageEngine != null) {
            return;
        }
        // This call will return the correct directory whether Encrypted File Systems is
        // enabled or not.
        File dataDir = Environment.getSecureDataDirectory();
        sSyncStorageEngine = new SyncStorageEngine(context, dataDir);
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

    @Override public void handleMessage(Message msg) {
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
     * The flex time is capped at a percentage of the {@link DEFAULT_POLL_FREQUENCY_SECONDS}.
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

    private void reportChange(int which) {
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

        if (DEBUG) {
            Log.v(TAG, "reportChange " + which + " to: " + reports);
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
                AuthorityInfo authority = getAuthorityLocked(account, userId, providerName,
                        "getSyncAutomatically");
                return authority != null && authority.enabled;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authority = mAuthorities.valueAt(i);
                if (authority.authority.equals(providerName)
                        && authority.userId == userId
                        && authority.enabled) {
                    return true;
                }
            }
            return false;
        }
    }

    public void setSyncAutomatically(Account account, int userId, String providerName,
            boolean sync) {
        if (DEBUG) {
            Log.d(TAG, "setSyncAutomatically: " + /* account + */" provider " + providerName
                    + ", user " + userId + " -> " + sync);
        }
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(account, userId, providerName, -1,
                    false);
            if (authority.enabled == sync) {
                if (DEBUG) {
                    Log.d(TAG, "setSyncAutomatically: already set to " + sync + ", doing nothing");
                }
                return;
            }
            authority.enabled = sync;
            writeAccountInfoLocked();
        }

        if (sync) {
            requestSync(account, userId, SyncOperation.REASON_SYNC_AUTO, providerName,
                    new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public int getIsSyncable(Account account, int userId, String providerName) {
        synchronized (mAuthorities) {
            if (account != null) {
                AuthorityInfo authority = getAuthorityLocked(account, userId, providerName,
                        "getIsSyncable");
                if (authority == null) {
                    return -1;
                }
                return authority.syncable;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authority = mAuthorities.valueAt(i);
                if (authority.authority.equals(providerName)) {
                    return authority.syncable;
                }
            }
            return -1;
        }
    }

    public void setIsSyncable(Account account, int userId, String providerName, int syncable) {
        if (syncable > 1) {
            syncable = 1;
        } else if (syncable < -1) {
            syncable = -1;
        }
        if (DEBUG) {
            Log.d(TAG, "setIsSyncable: " + account + ", provider " + providerName
                    + ", user " + userId + " -> " + syncable);
        }
        synchronized (mAuthorities) {
            AuthorityInfo authority =
                    getOrCreateAuthorityLocked(account, userId, providerName, -1, false);
            if (authority.syncable == syncable) {
                if (DEBUG) {
                    Log.d(TAG, "setIsSyncable: already set to " + syncable + ", doing nothing");
                }
                return;
            }
            authority.syncable = syncable;
            writeAccountInfoLocked();
        }

        if (syncable > 0) {
            requestSync(account, userId, SyncOperation.REASON_IS_SYNCABLE,  providerName,
                    new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public Pair<Long, Long> getBackoff(Account account, int userId, String providerName) {
        synchronized (mAuthorities) {
            AuthorityInfo authority = getAuthorityLocked(account, userId, providerName,
                    "getBackoff");
            if (authority == null || authority.backoffTime < 0) {
                return null;
            }
            return Pair.create(authority.backoffTime, authority.backoffDelay);
        }
    }

    public void setBackoff(Account account, int userId, String providerName,
            long nextSyncTime, long nextDelay) {
        if (DEBUG) {
            Log.v(TAG, "setBackoff: " + account + ", provider " + providerName
                    + ", user " + userId
                    + " -> nextSyncTime " + nextSyncTime + ", nextDelay " + nextDelay);
        }
        boolean changed = false;
        synchronized (mAuthorities) {
            if (account == null || providerName == null) {
                for (AccountInfo accountInfo : mAccounts.values()) {
                    if (account != null && !account.equals(accountInfo.accountAndUser.account)
                            && userId != accountInfo.accountAndUser.userId) {
                        continue;
                    }
                    for (AuthorityInfo authorityInfo : accountInfo.authorities.values()) {
                        if (providerName != null
                                && !providerName.equals(authorityInfo.authority)) {
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
            } else {
                AuthorityInfo authority =
                        getOrCreateAuthorityLocked(account, userId, providerName, -1 /* ident */,
                                true);
                if (authority.backoffTime == nextSyncTime && authority.backoffDelay == nextDelay) {
                    return;
                }
                authority.backoffTime = nextSyncTime;
                authority.backoffDelay = nextDelay;
                changed = true;
            }
        }

        if (changed) {
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
        }
    }

    /**
     * Callers of this function need to hold a lock for syncQueue object passed in. Bear in mind
     * this function grabs the lock for {@link #mAuthorities}
     * @param syncQueue queue containing pending sync operations.
     */
    public void clearAllBackoffsLocked(SyncQueue syncQueue) {
        boolean changed = false;
        synchronized (mAuthorities) {
            for (AccountInfo accountInfo : mAccounts.values()) {
                for (AuthorityInfo authorityInfo : accountInfo.authorities.values()) {
                    if (authorityInfo.backoffTime != NOT_IN_BACKOFF_MODE
                            || authorityInfo.backoffDelay != NOT_IN_BACKOFF_MODE) {
                        if (DEBUG) {
                            Log.v(TAG, "clearAllBackoffs:"
                                    + " authority:" + authorityInfo.authority
                                    + " account:" + accountInfo.accountAndUser.account.name
                                    + " user:" + accountInfo.accountAndUser.userId
                                    + " backoffTime was: " + authorityInfo.backoffTime
                                    + " backoffDelay was: " + authorityInfo.backoffDelay);
                        }
                        authorityInfo.backoffTime = NOT_IN_BACKOFF_MODE;
                        authorityInfo.backoffDelay = NOT_IN_BACKOFF_MODE;
                        syncQueue.onBackoffChanged(accountInfo.accountAndUser.account,
                                accountInfo.accountAndUser.userId, authorityInfo.authority, 0);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
        }
    }

    public void setDelayUntilTime(Account account, int userId, String providerName,
            long delayUntil) {
        if (DEBUG) {
            Log.v(TAG, "setDelayUntil: " + account + ", provider " + providerName
                    + ", user " + userId + " -> delayUntil " + delayUntil);
        }
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(
                    account, userId, providerName, -1 /* ident */, true);
            if (authority.delayUntil == delayUntil) {
                return;
            }
            authority.delayUntil = delayUntil;
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public long getDelayUntilTime(Account account, int userId, String providerName) {
        synchronized (mAuthorities) {
            AuthorityInfo authority = getAuthorityLocked(account, userId, providerName,
                    "getDelayUntil");
            if (authority == null) {
                return 0;
            }
            return authority.delayUntil;
        }
    }

    private void updateOrRemovePeriodicSync(PeriodicSync toUpdate, int userId, boolean add) {
        if (DEBUG) {
            Log.v(TAG, "addOrRemovePeriodicSync: " + toUpdate.account + ", user " + userId
                    + ", provider " + toUpdate.authority
                    + " -> period " + toUpdate.period + ", extras " + toUpdate.extras);
        }
        synchronized (mAuthorities) {
            if (toUpdate.period <= 0 && add) {
                Log.e(TAG, "period < 0, should never happen in updateOrRemovePeriodicSync: add-"
                        + add);
            }
            if (toUpdate.extras == null) {
                Log.e(TAG, "null extras, should never happen in updateOrRemovePeriodicSync: add-"
                        + add);
            }
            try {
                AuthorityInfo authority =
                        getOrCreateAuthorityLocked(toUpdate.account, userId, toUpdate.authority,
                                -1, false);
                if (add) {
                    // add this periodic sync if an equivalent periodic doesn't already exist.
                    boolean alreadyPresent = false;
                    for (int i = 0, N = authority.periodicSyncs.size(); i < N; i++) {
                        PeriodicSync syncInfo = authority.periodicSyncs.get(i);
                        if (PeriodicSync.syncExtrasEquals(
                                toUpdate.extras,
                                syncInfo.extras)) {
                            if (toUpdate.period == syncInfo.period &&
                                    toUpdate.flexTime == syncInfo.flexTime) {
                                // Absolutely the same.
                                return;
                            }
                            authority.periodicSyncs.set(i, new PeriodicSync(toUpdate));
                            alreadyPresent = true;
                            break;
                        }
                    }
                    // If we added an entry to the periodicSyncs array also add an entry to
                    // the periodic syncs status to correspond to it.
                    if (!alreadyPresent) {
                        authority.periodicSyncs.add(new PeriodicSync(toUpdate));
                        SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
                        status.setPeriodicSyncTime(authority.periodicSyncs.size() - 1, 0L);
                    }
                } else {
                    // Remove any periodic syncs that match the authority and extras.
                    SyncStatusInfo status = mSyncStatus.get(authority.ident);
                    boolean changed = false;
                    Iterator<PeriodicSync> iterator = authority.periodicSyncs.iterator();
                    int i = 0;
                    while (iterator.hasNext()) {
                        PeriodicSync syncInfo = iterator.next();
                        if (PeriodicSync.syncExtrasEquals(syncInfo.extras, toUpdate.extras)) {
                            iterator.remove();
                            changed = true;
                            // If we removed an entry from the periodicSyncs array also
                            // remove the corresponding entry from the status
                            if (status != null) {
                                status.removePeriodicSyncTime(i);
                            } else {
                                Log.e(TAG, "Tried removing sync status on remove periodic sync but"
                                        + "did not find it.");
                            }
                        } else {
                            i++;
                        }
                    }
                    if (!changed) {
                        return;
                    }
                }
            } finally {
                writeAccountInfoLocked();
                writeStatusLocked();
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public void addPeriodicSync(PeriodicSync toAdd, int userId) {
        updateOrRemovePeriodicSync(toAdd, userId, true /* add */);
    }

    public void removePeriodicSync(PeriodicSync toRemove, int userId) {
        updateOrRemovePeriodicSync(toRemove, userId, false /* remove */);
    }

    public List<PeriodicSync> getPeriodicSyncs(Account account, int userId, String providerName) {
        ArrayList<PeriodicSync> syncs = new ArrayList<PeriodicSync>();
        synchronized (mAuthorities) {
            AuthorityInfo authority = getAuthorityLocked(account, userId, providerName,
                    "getPeriodicSyncs");
            if (authority != null) {
                for (PeriodicSync item : authority.periodicSyncs) {
                    // Copy and send out. Necessary for thread-safety although it's parceled.
                    syncs.add(new PeriodicSync(item));
                }
            }
        }
        return syncs;
    }

    public void setMasterSyncAutomatically(boolean flag, int userId) {
        synchronized (mAuthorities) {
            Boolean auto = mMasterSyncAutomatically.get(userId);
            if (auto != null && (boolean) auto == flag) {
                return;
            }
            mMasterSyncAutomatically.put(userId, flag);
            writeAccountInfoLocked();
        }
        if (flag) {
            requestSync(null, userId, SyncOperation.REASON_MASTER_SYNC_AUTO, null,
                    new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
        mContext.sendBroadcast(ContentResolver.ACTION_SYNC_CONN_STATUS_CHANGED);
    }

    public boolean getMasterSyncAutomatically(int userId) {
        synchronized (mAuthorities) {
            Boolean auto = mMasterSyncAutomatically.get(userId);
            return auto == null ? mDefaultMasterSyncAutomatically : auto;
        }
    }

    public void removeAuthority(Account account, int userId, String authority) {
        synchronized (mAuthorities) {
            removeAuthorityLocked(account, userId, authority, true /* doWrite */);
        }
    }

    public AuthorityInfo getAuthority(int authorityId) {
        synchronized (mAuthorities) {
            return mAuthorities.get(authorityId);
        }
    }

    /**
     * Returns true if there is currently a sync operation for the given
     * account or authority actively being processed.
     */
    public boolean isSyncActive(Account account, int userId, String authority) {
        synchronized (mAuthorities) {
            for (SyncInfo syncInfo : getCurrentSyncs(userId)) {
                AuthorityInfo ainfo = getAuthority(syncInfo.authorityId);
                if (ainfo != null && ainfo.account.equals(account)
                        && ainfo.authority.equals(authority)
                        && ainfo.userId == userId) {
                    return true;
                }
            }
        }

        return false;
    }

    public PendingOperation insertIntoPending(PendingOperation op) {
        synchronized (mAuthorities) {
            if (DEBUG) {
                Log.v(TAG, "insertIntoPending: account=" + op.account
                        + " user=" + op.userId
                        + " auth=" + op.authority
                        + " src=" + op.syncSource
                        + " extras=" + op.extras);
            }

            AuthorityInfo authority = getOrCreateAuthorityLocked(op.account, op.userId,
                    op.authority,
                    -1 /* desired identifier */,
                    true /* write accounts to storage */);
            if (authority == null) {
                return null;
            }

            op = new PendingOperation(op);
            op.authorityId = authority.ident;
            mPendingOperations.add(op);
            appendPendingOperationLocked(op);

            SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
            status.pending = true;
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
        return op;
    }

    /**
     * Remove from list of pending operations. If successful, search through list for matching
     * authorities. If there are no more pending syncs for the same authority/account/userid,
     * update the SyncStatusInfo for that authority(authority here is the internal representation
     * of a 'sync operation'.
     * @param op
     * @return
     */
    public boolean deleteFromPending(PendingOperation op) {
        boolean res = false;
        synchronized (mAuthorities) {
            if (DEBUG) {
                Log.v(TAG, "deleteFromPending: account=" + op.account
                    + " user=" + op.userId
                    + " auth=" + op.authority
                    + " src=" + op.syncSource
                    + " extras=" + op.extras);
            }
            if (mPendingOperations.remove(op)) {
                if (mPendingOperations.size() == 0
                        || mNumPendingFinished >= PENDING_FINISH_TO_WRITE) {
                    writePendingOperationsLocked();
                    mNumPendingFinished = 0;
                } else {
                    mNumPendingFinished++;
                }

                AuthorityInfo authority = getAuthorityLocked(op.account, op.userId, op.authority,
                        "deleteFromPending");
                if (authority != null) {
                    if (DEBUG) Log.v(TAG, "removing - " + authority.toString());
                    final int N = mPendingOperations.size();
                    boolean morePending = false;
                    for (int i=0; i<N; i++) {
                        PendingOperation cur = mPendingOperations.get(i);
                        if (cur.account.equals(op.account)
                                && cur.authority.equals(op.authority)
                                && cur.userId == op.userId) {
                            morePending = true;
                            break;
                        }
                    }

                    if (!morePending) {
                        if (DEBUG) Log.v(TAG, "no more pending!");
                        SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
                        status.pending = false;
                    }
                }

                res = true;
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
        return res;
    }

    /**
     * Return a copy of the current array of pending operations.  The
     * PendingOperation objects are the real objects stored inside, so that
     * they can be used with deleteFromPending().
     */
    public ArrayList<PendingOperation> getPendingOperations() {
        synchronized (mAuthorities) {
            return new ArrayList<PendingOperation>(mPendingOperations);
        }
    }

    /**
     * Return the number of currently pending operations.
     */
    public int getPendingOperationCount() {
        synchronized (mAuthorities) {
            return mPendingOperations.size();
        }
    }

    /**
     * Called when the set of account has changed, given the new array of
     * active accounts.
     */
    public void doDatabaseCleanup(Account[] accounts, int userId) {
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "Updating for new accounts...");
            SparseArray<AuthorityInfo> removing = new SparseArray<AuthorityInfo>();
            Iterator<AccountInfo> accIt = mAccounts.values().iterator();
            while (accIt.hasNext()) {
                AccountInfo acc = accIt.next();
                if (!ArrayUtils.contains(accounts, acc.accountAndUser.account)
                        && acc.accountAndUser.userId == userId) {
                    // This account no longer exists...
                    if (DEBUG) {
                        Log.v(TAG, "Account removed: " + acc.accountAndUser);
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
                writePendingOperationsLocked();
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
            if (DEBUG) {
                Log.v(TAG, "setActiveSync: account="
                    + activeSyncContext.mSyncOperation.account
                    + " auth=" + activeSyncContext.mSyncOperation.authority
                    + " src=" + activeSyncContext.mSyncOperation.syncSource
                    + " extras=" + activeSyncContext.mSyncOperation.extras);
            }
            AuthorityInfo authority = getOrCreateAuthorityLocked(
                    activeSyncContext.mSyncOperation.account,
                    activeSyncContext.mSyncOperation.userId,
                    activeSyncContext.mSyncOperation.authority,
                    -1 /* assign a new identifier if creating a new authority */,
                    true /* write to storage if this results in a change */);
            syncInfo = new SyncInfo(authority.ident,
                    authority.account, authority.authority,
                    activeSyncContext.mStartTime);
            getCurrentSyncs(authority.userId).add(syncInfo);
        }

        reportActiveChange();
        return syncInfo;
    }

    /**
     * Called to indicate that a previously active sync is no longer active.
     */
    public void removeActiveSync(SyncInfo syncInfo, int userId) {
        synchronized (mAuthorities) {
            if (DEBUG) {
                Log.v(TAG, "removeActiveSync: account=" + syncInfo.account
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
     * Note that sync has started for the given account and authority.
     */
    public long insertStartSyncEvent(Account accountName, int userId, int reason,
            String authorityName, long now, int source, boolean initialization, Bundle extras) {
        long id;
        synchronized (mAuthorities) {
            if (DEBUG) {
                Log.v(TAG, "insertStartSyncEvent: account=" + accountName + "user=" + userId
                    + " auth=" + authorityName + " source=" + source);
            }
            AuthorityInfo authority = getAuthorityLocked(accountName, userId, authorityName,
                    "insertStartSyncEvent");
            if (authority == null) {
                return -1;
            }
            SyncHistoryItem item = new SyncHistoryItem();
            item.initialization = initialization;
            item.authorityId = authority.ident;
            item.historyId = mNextHistoryId++;
            if (mNextHistoryId < 0) mNextHistoryId = 0;
            item.eventTime = now;
            item.source = source;
            item.reason = reason;
            item.extras = extras;
            item.event = EVENT_START;
            mSyncHistory.add(0, item);
            while (mSyncHistory.size() > MAX_HISTORY) {
                mSyncHistory.remove(mSyncHistory.size()-1);
            }
            id = item.historyId;
            if (DEBUG) Log.v(TAG, "returning historyId " + id);
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS);
        return id;
    }

    public void stopSyncEvent(long historyId, long elapsedTime, String resultMessage,
            long downstreamActivity, long upstreamActivity) {
        synchronized (mAuthorities) {
            if (DEBUG) {
                Log.v(TAG, "stopSyncEvent: historyId=" + historyId);
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
                Log.w(TAG, "stopSyncEvent: no history for id " + historyId);
                return;
            }

            item.elapsedTime = elapsedTime;
            item.event = EVENT_STOP;
            item.mesg = resultMessage;
            item.downstreamActivity = downstreamActivity;
            item.upstreamActivity = upstreamActivity;

            SyncStatusInfo status = getOrCreateSyncStatusLocked(item.authorityId);

            status.numSyncs++;
            status.totalElapsedTime += elapsedTime;
            switch (item.source) {
                case SOURCE_LOCAL:
                    status.numSourceLocal++;
                    break;
                case SOURCE_POLL:
                    status.numSourcePoll++;
                    break;
                case SOURCE_USER:
                    status.numSourceUser++;
                    break;
                case SOURCE_SERVER:
                    status.numSourceServer++;
                    break;
                case SOURCE_PERIODIC:
                    status.numSourcePeriodic++;
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
                status.lastFailureTime = lastSyncTime;
                status.lastFailureSource = item.source;
                status.lastFailureMesg = resultMessage;
                if (status.initialFailureTime == 0) {
                    status.initialFailureTime = lastSyncTime;
                }
                ds.failureCount++;
                ds.failureTime += elapsedTime;
            }

            if (writeStatusNow) {
                writeStatusLocked();
            } else if (!hasMessages(MSG_WRITE_STATUS)) {
                sendMessageDelayed(obtainMessage(MSG_WRITE_STATUS),
                        WRITE_STATUS_DELAY);
            }
            if (writeStatisticsNow) {
                writeStatisticsLocked();
            } else if (!hasMessages(MSG_WRITE_STATISTICS)) {
                sendMessageDelayed(obtainMessage(MSG_WRITE_STATISTICS),
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
     * @return a copy of the current syncs data structure. Will not return
     * null.
     */
    public List<SyncInfo> getCurrentSyncsCopy(int userId) {
        synchronized (mAuthorities) {
            final List<SyncInfo> syncs = getCurrentSyncsLocked(userId);
            final List<SyncInfo> syncsCopy = new ArrayList<SyncInfo>();
            for (SyncInfo sync : syncs) {
                syncsCopy.add(new SyncInfo(sync));
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
     * Return an array of the current sync status for all authorities.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public ArrayList<SyncStatusInfo> getSyncStatus() {
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            ArrayList<SyncStatusInfo> ops = new ArrayList<SyncStatusInfo>(N);
            for (int i=0; i<N; i++) {
                ops.add(mSyncStatus.valueAt(i));
            }
            return ops;
        }
    }

    /**
     * Return a copy of the specified authority with the corresponding sync status
     */
    public Pair<AuthorityInfo, SyncStatusInfo> getCopyOfAuthorityWithSyncStatus(
            Account account, int userId, String authority) {
        synchronized (mAuthorities) {
            AuthorityInfo authorityInfo = getOrCreateAuthorityLocked(account, userId, authority,
                    -1 /* assign a new identifier if creating a new authority */,
                    true /* write to storage if this results in a change */);
            return createCopyPairOfAuthorityWithSyncStatusLocked(authorityInfo);
        }
    }

    /**
     * Return a copy of all authorities with their corresponding sync status
     */
    public ArrayList<Pair<AuthorityInfo, SyncStatusInfo>> getCopyOfAllAuthoritiesWithSyncStatus() {
        synchronized (mAuthorities) {
            ArrayList<Pair<AuthorityInfo, SyncStatusInfo>> infos =
                    new ArrayList<Pair<AuthorityInfo, SyncStatusInfo>>(mAuthorities.size());
            for (int i = 0; i < mAuthorities.size(); i++) {
                infos.add(createCopyPairOfAuthorityWithSyncStatusLocked(mAuthorities.valueAt(i)));
            }
            return infos;
        }
    }

    /**
     * Returns the status that matches the authority and account.
     *
     * @param account the account we want to check
     * @param authority the authority whose row should be selected
     * @return the SyncStatusInfo for the authority or null if none found.
     */
    public SyncStatusInfo getStatusByAccountAndAuthority(Account account, int userId,
            String authority) {
        if (account == null || authority == null) {
          return null;
        }
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);

                if (ainfo != null && ainfo.authority.equals(authority)
                        && ainfo.userId == userId
                        && account.equals(ainfo.account)) {
                  return cur;
                }
            }
            return null;
        }
    }

    /**
     * Return true if the pending status is true of any matching authorities.
     */
    public boolean isSyncPending(Account account, int userId, String authority) {
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);
                if (ainfo == null) {
                    continue;
                }
                if (userId != ainfo.userId) {
                    continue;
                }
                if (account != null && !ainfo.account.equals(account)) {
                    continue;
                }
                if (ainfo.authority.equals(authority) && cur.pending) {
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
     * Retrieve an authority, returning null if one does not exist.
     *
     * @param accountName The name of the account for the authority.
     * @param authorityName The name of the authority itself.
     * @param tag If non-null, this will be used in a log message if the
     * requested authority does not exist.
     */
    private AuthorityInfo getAuthorityLocked(Account accountName, int userId, String authorityName,
            String tag) {
        AccountAndUser au = new AccountAndUser(accountName, userId);
        AccountInfo accountInfo = mAccounts.get(au);
        if (accountInfo == null) {
            if (tag != null) {
                if (DEBUG) {
                    Log.v(TAG, tag + ": unknown account " + au);
                }
            }
            return null;
        }
        AuthorityInfo authority = accountInfo.authorities.get(authorityName);
        if (authority == null) {
            if (tag != null) {
                if (DEBUG) {
                    Log.v(TAG, tag + ": unknown authority " + authorityName);
                }
            }
            return null;
        }

        return authority;
    }

    /**
     * Retrieve an authority, returning null if one does not exist.
     *
     * @param service The service name used for this sync.
     * @param userId The user for whom this sync is scheduled.
     * @param tag If non-null, this will be used in a log message if the
     * requested authority does not exist.
     */
    private AuthorityInfo getAuthorityLocked(ComponentName service, int userId, String tag) {
        AuthorityInfo authority = mServices.get(service).get(userId);
        if (authority == null) {
            if (tag != null) {
                if (DEBUG) {
                    Log.v(TAG, tag + " No authority info found for " + service + " for user "
                            + userId);
                }
            }
            return null;
        }
        return authority;
    }

    /**
     * @param cname identifier for the service.
     * @param userId for the syncs corresponding to this authority.
     * @param ident unique identifier for authority. -1 for none.
     * @param doWrite if true, update the accounts.xml file on the disk.
     * @return the authority that corresponds to the provided sync service, creating it if none
     * exists.
     */
    private AuthorityInfo getOrCreateAuthorityLocked(ComponentName cname, int userId, int ident,
            boolean doWrite) {
        SparseArray<AuthorityInfo> aInfo = mServices.get(cname);
        if (aInfo == null) {
            aInfo = new SparseArray<AuthorityInfo>();
            mServices.put(cname, aInfo);
        }
        AuthorityInfo authority = aInfo.get(userId);
        if (authority == null) {
            if (ident < 0) {
                ident = mNextAuthorityId;
                mNextAuthorityId++;
                doWrite = true;
            }
            if (DEBUG) {
                Log.v(TAG, "created a new AuthorityInfo for " + cname.getPackageName()
                        + ", " + cname.getClassName()
                        + ", user: " + userId);
            }
            authority = new AuthorityInfo(cname, userId, ident);
            aInfo.put(userId, authority);
            mAuthorities.put(ident, authority);
            if (doWrite) {
                writeAccountInfoLocked();
            }
        }
        return authority;
    }

    private AuthorityInfo getOrCreateAuthorityLocked(Account accountName, int userId,
            String authorityName, int ident, boolean doWrite) {
        AccountAndUser au = new AccountAndUser(accountName, userId);
        AccountInfo account = mAccounts.get(au);
        if (account == null) {
            account = new AccountInfo(au);
            mAccounts.put(au, account);
        }
        AuthorityInfo authority = account.authorities.get(authorityName);
        if (authority == null) {
            if (ident < 0) {
                ident = mNextAuthorityId;
                mNextAuthorityId++;
                doWrite = true;
            }
            if (DEBUG) {
                Log.v(TAG, "created a new AuthorityInfo for " + accountName
                        + ", user " + userId
                        + ", provider " + authorityName);
            }
            authority = new AuthorityInfo(accountName, userId, authorityName, ident);
            account.authorities.put(authorityName, authority);
            mAuthorities.put(ident, authority);
            if (doWrite) {
                writeAccountInfoLocked();
            }
        }

        return authority;
    }

    private void removeAuthorityLocked(Account account, int userId, String authorityName,
            boolean doWrite) {
        AccountInfo accountInfo = mAccounts.get(new AccountAndUser(account, userId));
        if (accountInfo != null) {
            final AuthorityInfo authorityInfo = accountInfo.authorities.remove(authorityName);
            if (authorityInfo != null) {
                mAuthorities.remove(authorityInfo.ident);
                if (doWrite) {
                    writeAccountInfoLocked();
                }
            }
        }
    }

    /**
     * Updates (in a synchronized way) the periodic sync time of the specified
     * authority id and target periodic sync
     */
    public void setPeriodicSyncTime(
            int authorityId, PeriodicSync targetPeriodicSync, long when) {
        boolean found = false;
        final AuthorityInfo authorityInfo;
        synchronized (mAuthorities) {
            authorityInfo = mAuthorities.get(authorityId);
            for (int i = 0; i < authorityInfo.periodicSyncs.size(); i++) {
                PeriodicSync periodicSync = authorityInfo.periodicSyncs.get(i);
                if (targetPeriodicSync.equals(periodicSync)) {
                    mSyncStatus.get(authorityId).setPeriodicSyncTime(i, when);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            Log.w(TAG, "Ignoring setPeriodicSyncTime request for a sync that does not exist. " +
                    "Authority: " + authorityInfo.authority);
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

            if (mNumPendingFinished > 0) {
                // Only write these if they are out of date.
                writePendingOperationsLocked();
            }

            // Just always write these...  they are likely out of date.
            writeStatusLocked();
            writeStatisticsLocked();
        }
    }

    /**
     * public for testing
     */
    public void clearAndReadState() {
        synchronized (mAuthorities) {
            mAuthorities.clear();
            mAccounts.clear();
            mServices.clear();
            mPendingOperations.clear();
            mSyncStatus.clear();
            mSyncHistory.clear();

            readAccountInfoLocked();
            readStatusLocked();
            readPendingOperationsLocked();
            readStatisticsLocked();
            readAndDeleteLegacyAccountInfoLocked();
            writeAccountInfoLocked();
            writeStatusLocked();
            writePendingOperationsLocked();
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
                Log.v(TAG, "Reading " + mAccountInfoFile.getBaseFile());
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG) {
                eventType = parser.next();
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
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (parser.getDepth() == 2) {
                            if ("authority".equals(tagName)) {
                                authority = parseAuthority(parser, version);
                                periodicSync = null;
                                if (authority.ident > highestAuthorityId) {
                                    highestAuthorityId = authority.ident;
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
            Log.w(TAG, "Error reading accounts", e);
            return;
        } catch (java.io.IOException e) {
            if (fis == null) Log.i(TAG, "No initial accounts");
            else Log.w(TAG, "Error reading accounts", e);
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
        for (int i=0; i<N; i++) {
            AuthorityInfo authority = mAuthorities.valueAt(i);
            // skip this authority if it isn't one of the renamed ones
            final String newAuthorityName = sAuthorityRenames.get(authority.authority);
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
            if (getAuthorityLocked(authority.account, authority.userId, newAuthorityName, "cleanup")
                    != null) {
                continue;
            }

            AuthorityInfo newAuthority = getOrCreateAuthorityLocked(authority.account,
                    authority.userId, newAuthorityName, -1 /* ident */, false /* doWrite */);
            newAuthority.enabled = true;
            writeNeeded = true;
        }

        for (AuthorityInfo authorityInfo : authoritiesToRemove) {
            removeAuthorityLocked(authorityInfo.account, authorityInfo.userId,
                    authorityInfo.authority, false /* doWrite */);
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
            Log.e(TAG, "error parsing the user for listen-for-tickles", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "the user in listen-for-tickles is null", e);
        }
        String enabled = parser.getAttributeValue(null, XML_ATTR_ENABLED);
        boolean listen = enabled == null || Boolean.parseBoolean(enabled);
        mMasterSyncAutomatically.put(userId, listen);
    }

    private AuthorityInfo parseAuthority(XmlPullParser parser, int version) {
        AuthorityInfo authority = null;
        int id = -1;
        try {
            id = Integer.parseInt(parser.getAttributeValue(null, "id"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "error parsing the id of the authority", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "the id of the authority is null", e);
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
            if (accountType == null) {
                accountType = "com.google";
                syncable = "unknown";
            }
            authority = mAuthorities.get(id);
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Log.v(TAG, "Adding authority: account="
                        + accountName + " auth=" + authorityName
                        + " user=" + userId
                        + " enabled=" + enabled
                        + " syncable=" + syncable);
            }
            if (authority == null) {
                if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                    Log.v(TAG, "Creating entry");
                }
                if (accountName != null && accountType != null) {
                    authority = getOrCreateAuthorityLocked(
                            new Account(accountName, accountType), userId, authorityName, id,
                                false);
                } else {
                    authority = getOrCreateAuthorityLocked(
                            new ComponentName(packageName, className), userId, id, false);
                }
                // If the version is 0 then we are upgrading from a file format that did not
                // know about periodic syncs. In that case don't clear the list since we
                // want the default, which is a daily periodic sync.
                // Otherwise clear out this default list since we will populate it later with
                // the periodic sync descriptions that are read from the configuration file.
                if (version > 0) {
                    authority.periodicSyncs.clear();
                }
            }
            if (authority != null) {
                authority.enabled = enabled == null || Boolean.parseBoolean(enabled);
                if ("unknown".equals(syncable)) {
                    authority.syncable = -1;
                } else {
                    authority.syncable =
                            (syncable == null || Boolean.parseBoolean(syncable)) ? 1 : 0;
                }
            } else {
                Log.w(TAG, "Failure adding authority: account="
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
    private PeriodicSync parsePeriodicSync(XmlPullParser parser, AuthorityInfo authority) {
        Bundle extras = new Bundle(); // Gets filled in later.
        String periodValue = parser.getAttributeValue(null, "period");
        String flexValue = parser.getAttributeValue(null, "flex");
        final long period;
        long flextime;
        try {
            period = Long.parseLong(periodValue);
        } catch (NumberFormatException e) {
            Log.e(TAG, "error parsing the period of a periodic sync", e);
            return null;
        } catch (NullPointerException e) {
            Log.e(TAG, "the period of a periodic sync is null", e);
            return null;
        }
        try {
            flextime = Long.parseLong(flexValue);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error formatting value parsed for periodic sync flex: " + flexValue);
            flextime = calculateDefaultFlexTime(period);
        } catch (NullPointerException expected) {
            flextime = calculateDefaultFlexTime(period);
            Log.d(TAG, "No flex time specified for this sync, using a default. period: "
            + period + " flex: " + flextime);
        }
        final PeriodicSync periodicSync =
                new PeriodicSync(authority.account, authority.authority, extras,
                        period, flextime);
        authority.periodicSyncs.add(periodicSync);
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
            Log.e(TAG, "error parsing bundle value", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "error parsing bundle value", e);
        }
    }

    /**
     * Write all account information to the account file.
     */
    private void writeAccountInfoLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Log.v(TAG, "Writing new " + mAccountInfoFile.getBaseFile());
        }
        FileOutputStream fos = null;

        try {
            fos = mAccountInfoFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
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
                out.startTag(null, "authority");
                out.attribute(null, "id", Integer.toString(authority.ident));
                out.attribute(null, XML_ATTR_USER, Integer.toString(authority.userId));
                out.attribute(null, XML_ATTR_ENABLED, Boolean.toString(authority.enabled));
                if (authority.service == null) {
                    out.attribute(null, "account", authority.account.name);
                    out.attribute(null, "type", authority.account.type);
                    out.attribute(null, "authority", authority.authority);
                } else {
                    out.attribute(null, "package", authority.service.getPackageName());
                    out.attribute(null, "class", authority.service.getClassName());
                }
                if (authority.syncable < 0) {
                    out.attribute(null, "syncable", "unknown");
                } else {
                    out.attribute(null, "syncable", Boolean.toString(authority.syncable != 0));
                }
                for (PeriodicSync periodicSync : authority.periodicSyncs) {
                    out.startTag(null, "periodicSync");
                    out.attribute(null, "period", Long.toString(periodicSync.period));
                    out.attribute(null, "flex", Long.toString(periodicSync.flexTime));
                    final Bundle extras = periodicSync.extras;
                    extrasToXml(out, extras);
                    out.endTag(null, "periodicSync");
                }
                out.endTag(null, "authority");
            }
            out.endTag(null, "accounts");
            out.endDocument();
            mAccountInfoFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing accounts", e1);
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
     * Load sync engine state from the old syncmanager database, and then
     * erase it.  Note that we don't deal with pending operations, active
     * sync, or history.
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
                Log.v(TAG, "Reading legacy sync accounts db");
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
                AuthorityInfo authority = this.getOrCreateAuthorityLocked(
                        new Account(accountName, accountType), 0 /* legacy is single-user */,
                        authorityName, -1, false);
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
                    st.totalElapsedTime = getLongColumn(c, "totalElapsedTime");
                    st.numSyncs = getIntColumn(c, "numSyncs");
                    st.numSourceLocal = getIntColumn(c, "numSourceLocal");
                    st.numSourcePoll = getIntColumn(c, "numSourcePoll");
                    st.numSourceServer = getIntColumn(c, "numSourceServer");
                    st.numSourceUser = getIntColumn(c, "numSourceUser");
                    st.numSourcePeriodic = 0;
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
                    setMasterSyncAutomatically(value == null || Boolean.parseBoolean(value), 0);
                } else if (name.startsWith("sync_provider_")) {
                    String provider = name.substring("sync_provider_".length(),
                            name.length());
                    int i = mAuthorities.size();
                    while (i > 0) {
                        i--;
                        AuthorityInfo authority = mAuthorities.valueAt(i);
                        if (authority.authority.equals(provider)) {
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
            Log.v(TAG, "Reading " + mStatusFile.getBaseFile());
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
                            Log.v(TAG, "Adding status for id "
                                    + status.authorityId);
                        }
                        mSyncStatus.put(status.authorityId, status);
                    }
                } else {
                    // Ooops.
                    Log.w(TAG, "Unknown status token: " + token);
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.i(TAG, "No initial status");
        }
    }

    /**
     * Write all sync status to the sync status file.
     */
    private void writeStatusLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Log.v(TAG, "Writing new " + mStatusFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        removeMessages(MSG_WRITE_STATUS);

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
            Log.w(TAG, "Error writing status", e1);
            if (fos != null) {
                mStatusFile.failWrite(fos);
            }
        }
    }

    public static final int PENDING_OPERATION_VERSION = 3;

    /** Read all pending operations back in to the initial engine state. */
    private void readPendingOperationsLocked() {
        FileInputStream fis = null;
        if (!mPendingFile.getBaseFile().exists()) {
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Log.v(TAG_FILE, "No pending operation file.");
                return;
            }
        }
        try {
            fis = mPendingFile.openRead();
            XmlPullParser parser;
            parser = Xml.newPullParser();
            parser.setInput(fis, null);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) return; // Nothing to read.

            String tagName = parser.getName();
            do {
                PendingOperation pop = null;
                if (eventType == XmlPullParser.START_TAG) {
                    try {
                        tagName = parser.getName();
                        if (parser.getDepth() == 1 && "op".equals(tagName)) {
                            // Verify version.
                            String versionString =
                                    parser.getAttributeValue(null, XML_ATTR_VERSION);
                            if (versionString == null ||
                                    Integer.parseInt(versionString) != PENDING_OPERATION_VERSION) {
                                Log.w(TAG, "Unknown pending operation version " + versionString);
                                throw new java.io.IOException("Unknown version.");
                            }
                            int authorityId = Integer.valueOf(parser.getAttributeValue(
                                    null, XML_ATTR_AUTHORITYID));
                            boolean expedited = Boolean.valueOf(parser.getAttributeValue(
                                    null, XML_ATTR_EXPEDITED));
                            int syncSource = Integer.valueOf(parser.getAttributeValue(
                                    null, XML_ATTR_SOURCE));
                            int reason = Integer.valueOf(parser.getAttributeValue(
                                    null, XML_ATTR_REASON));
                            AuthorityInfo authority = mAuthorities.get(authorityId);
                            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                                Log.v(TAG_FILE, authorityId + " " + expedited + " " + syncSource + " "
                                        + reason);
                            }
                            if (authority != null) {
                                pop = new PendingOperation(
                                        authority.account, authority.userId, reason,
                                        syncSource, authority.authority, new Bundle(),
                                        expedited);
                                pop.flatExtras = null; // No longer used.
                                mPendingOperations.add(pop);
                                if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                                    Log.v(TAG_FILE, "Adding pending op: "
                                            + pop.authority
                                            + " src=" + pop.syncSource
                                            + " reason=" + pop.reason
                                            + " expedited=" + pop.expedited);
                                }
                            } else {
                                // Skip non-existent authority.
                                pop = null;
                                if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                                    Log.v(TAG_FILE, "No authority found for " + authorityId
                                            + ", skipping");
                                }
                            }
                        } else if (parser.getDepth() == 2 &&
                                pop != null &&
                                "extra".equals(tagName)) {
                            parseExtra(parser, pop.extras);
                        }
                    } catch (NumberFormatException e) {
                        Log.d(TAG, "Invalid data in xml file.", e);
                    }
                }
                eventType = parser.next();
            } while(eventType != XmlPullParser.END_DOCUMENT);
        } catch (java.io.IOException e) {
            Log.w(TAG_FILE, "Error reading pending data.", e);
        } catch (XmlPullParserException e) {
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Log.w(TAG_FILE, "Error parsing pending ops xml.", e);
            }
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {}
            }
        }
    }

    private static final String XML_ATTR_AUTHORITYID = "authority_id";
    private static final String XML_ATTR_SOURCE = "source";
    private static final String XML_ATTR_EXPEDITED = "expedited";
    private static final String XML_ATTR_REASON = "reason";
    private static final String XML_ATTR_VERSION = "version";

    /**
     * Write all currently pending ops to the pending ops file.
     */
    private void writePendingOperationsLocked() {
        final int N = mPendingOperations.size();
        FileOutputStream fos = null;
        try {
            if (N == 0) {
                if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                    Log.v(TAG_FILE, "Truncating " + mPendingFile.getBaseFile());
                }
                mPendingFile.truncate();
                return;
            }
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Log.v(TAG_FILE, "Writing new " + mPendingFile.getBaseFile());
            }
            fos = mPendingFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");

            for (int i = 0; i < N; i++) {
                PendingOperation pop = mPendingOperations.get(i);
                writePendingOperationLocked(pop, out);
            }
            out.endDocument();
            mPendingFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing pending operations", e1);
            if (fos != null) {
                mPendingFile.failWrite(fos);
            }
        }
    }

    /** Write all currently pending ops to the pending ops file. */
     private void writePendingOperationLocked(PendingOperation pop, XmlSerializer out)
             throws IOException {
         // Pending operation.
         out.startTag(null, "op");

         out.attribute(null, XML_ATTR_VERSION, Integer.toString(PENDING_OPERATION_VERSION));
         out.attribute(null, XML_ATTR_AUTHORITYID, Integer.toString(pop.authorityId));
         out.attribute(null, XML_ATTR_SOURCE, Integer.toString(pop.syncSource));
         out.attribute(null, XML_ATTR_EXPEDITED, Boolean.toString(pop.expedited));
         out.attribute(null, XML_ATTR_REASON, Integer.toString(pop.reason));
         extrasToXml(out, pop.extras);

         out.endTag(null, "op");
     }

    /**
     * Append the given operation to the pending ops file; if unable to,
     * write all pending ops.
     */
    private void appendPendingOperationLocked(PendingOperation op) {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Log.v(TAG, "Appending to " + mPendingFile.getBaseFile());
        }
        FileOutputStream fos = null;
        try {
            fos = mPendingFile.openAppend();
        } catch (java.io.IOException e) {
            if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
                Log.v(TAG, "Failed append; writing full file");
            }
            writePendingOperationsLocked();
            return;
        }

        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            writePendingOperationLocked(op, out);
            out.endDocument();
            mPendingFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing appending operation", e1);
            mPendingFile.failWrite(fos);
        } finally {
            try {
                fos.close();
            } catch (IOException e) {}
        }
    }

    static private byte[] flattenBundle(Bundle bundle) {
        byte[] flatData = null;
        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            flatData = parcel.marshall();
        } finally {
            parcel.recycle();
        }
        return flatData;
    }

    static private Bundle unflattenBundle(byte[] flatData) {
        Bundle bundle;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(flatData, 0, flatData.length);
            parcel.setDataPosition(0);
            bundle = parcel.readBundle();
        } catch (RuntimeException e) {
            // A RuntimeException is thrown if we were unable to parse the parcel.
            // Create an empty parcel in this case.
            bundle = new Bundle();
        } finally {
            parcel.recycle();
        }
        return bundle;
    }

    private void extrasToXml(XmlSerializer out, Bundle extras) throws java.io.IOException {
        for (String key : extras.keySet()) {
            out.startTag(null, "extra");
            out.attribute(null, "name", key);
            final Object value = extras.get(key);
            if (value instanceof Long) {
                out.attribute(null, "type", "long");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof Integer) {
                out.attribute(null, "type", "integer");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof Boolean) {
                out.attribute(null, "type", "boolean");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof Float) {
                out.attribute(null, "type", "float");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof Double) {
                out.attribute(null, "type", "double");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof String) {
                out.attribute(null, "type", "string");
                out.attribute(null, "value1", value.toString());
            } else if (value instanceof Account) {
                out.attribute(null, "type", "account");
                out.attribute(null, "value1", ((Account)value).name);
                out.attribute(null, "value2", ((Account)value).type);
            }
            out.endTag(null, "extra");
        }
    }

    private void requestSync(Account account, int userId, int reason, String authority,
            Bundle extras) {
        // If this is happening in the system process, then call the syncrequest listener
        // to make a request back to the SyncManager directly.
        // If this is probably a test instance, then call back through the ContentResolver
        // which will know which userId to apply based on the Binder id.
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && mSyncRequestListener != null) {
            mSyncRequestListener.onSyncRequest(account, userId, reason, authority, extras);
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
                    Log.w(TAG, "Unknown stats token: " + token);
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.i(TAG, "No initial statistics");
        }
    }

    /**
     * Write all sync statistics to the sync status file.
     */
    private void writeStatisticsLocked() {
        if (Log.isLoggable(TAG_FILE, Log.VERBOSE)) {
            Log.v(TAG, "Writing new " + mStatisticsFile.getBaseFile());
        }

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        removeMessages(MSG_WRITE_STATISTICS);

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
            Log.w(TAG, "Error writing stats", e1);
            if (fos != null) {
                mStatisticsFile.failWrite(fos);
            }
        }
    }

    /**
     * Dump state of PendingOperations.
     */
    public void dumpPendingOperations(StringBuilder sb) {
        sb.append("Pending Ops: ").append(mPendingOperations.size()).append(" operation(s)\n");
        for (PendingOperation pop : mPendingOperations) {
            sb.append("(" + pop.account)
                .append(", u" + pop.userId)
                .append(", " + pop.authority)
                .append(", " + pop.extras)
                .append(")\n");
        }
    }
}
