/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MODIFY_NETWORK_ACCOUNTING;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.NetworkTemplate.buildTemplateWifi;
import static android.net.TrafficStats.UID_REMOVED;
import static android.provider.Settings.Secure.NETSTATS_NETWORK_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_NETWORK_MAX_HISTORY;
import static android.provider.Settings.Secure.NETSTATS_PERSIST_THRESHOLD;
import static android.provider.Settings.Secure.NETSTATS_POLL_INTERVAL;
import static android.provider.Settings.Secure.NETSTATS_TAG_MAX_HISTORY;
import static android.provider.Settings.Secure.NETSTATS_UID_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_UID_MAX_HISTORY;
import static android.telephony.PhoneStateListener.LISTEN_DATA_CONNECTION_STATE;
import static android.telephony.PhoneStateListener.LISTEN_NONE;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.NetworkManagementService.LIMIT_GLOBAL_ALERT;
import static com.android.server.NetworkManagementSocketTagger.resetKernelUidStats;
import static com.android.server.NetworkManagementSocketTagger.setKernelCounterSet;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.NetworkIdentity;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicException;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TrustedTime;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.Objects;
import com.android.server.EventLogTags;
import com.android.server.connectivity.Tethering;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import libcore.io.IoUtils;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    private static final String TAG = "NetworkStats";
    private static final boolean LOGD = false;
    private static final boolean LOGV = false;

    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;
    private static final int VERSION_NETWORK_INIT = 1;
    private static final int VERSION_UID_INIT = 1;
    private static final int VERSION_UID_WITH_IDENT = 2;
    private static final int VERSION_UID_WITH_TAG = 3;
    private static final int VERSION_UID_WITH_SET = 4;

    private static final int MSG_PERFORM_POLL = 1;
    private static final int MSG_UPDATE_IFACES = 2;

    /** Flags to control detail level of poll event. */
    private static final int FLAG_PERSIST_NETWORK = 0x1;
    private static final int FLAG_PERSIST_UID = 0x2;
    private static final int FLAG_PERSIST_ALL = FLAG_PERSIST_NETWORK | FLAG_PERSIST_UID;
    private static final int FLAG_PERSIST_FORCE = 0x100;

    /** Sample recent usage after each poll event. */
    private static final boolean ENABLE_SAMPLE_AFTER_POLL = true;

    private static final String TAG_NETSTATS_ERROR = "netstats_error";

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final IAlarmManager mAlarmManager;
    private final TrustedTime mTime;
    private final TelephonyManager mTeleManager;
    private final NetworkStatsSettings mSettings;

    private final PowerManager.WakeLock mWakeLock;

    private IConnectivityManager mConnManager;
    private DropBoxManager mDropBox;

    // @VisibleForTesting
    public static final String ACTION_NETWORK_STATS_POLL =
            "com.android.server.action.NETWORK_STATS_POLL";
    public static final String ACTION_NETWORK_STATS_UPDATED =
            "com.android.server.action.NETWORK_STATS_UPDATED";

    private PendingIntent mPollIntent;

    // TODO: trim empty history objects entirely

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = 1024 * KB_IN_BYTES;
    private static final long GB_IN_BYTES = 1024 * MB_IN_BYTES;

    /**
     * Settings that can be changed externally.
     */
    public interface NetworkStatsSettings {
        public long getPollInterval();
        public long getPersistThreshold();
        public long getNetworkBucketDuration();
        public long getNetworkMaxHistory();
        public long getUidBucketDuration();
        public long getUidMaxHistory();
        public long getTagMaxHistory();
        public long getTimeCacheMaxAge();
    }

    private final Object mStatsLock = new Object();

    /** Set of currently active ifaces. */
    private HashMap<String, NetworkIdentitySet> mActiveIfaces = Maps.newHashMap();
    /** Set of historical {@code dev} stats for known networks. */
    private HashMap<NetworkIdentitySet, NetworkStatsHistory> mNetworkDevStats = Maps.newHashMap();
    /** Set of historical {@code xtables} stats for known networks. */
    private HashMap<NetworkIdentitySet, NetworkStatsHistory> mNetworkXtStats = Maps.newHashMap();
    /** Set of historical {@code xtables} stats for known UIDs. */
    private HashMap<UidStatsKey, NetworkStatsHistory> mUidStats = Maps.newHashMap();

    /** Flag if {@link #mNetworkDevStats} have been loaded from disk. */
    private boolean mNetworkStatsLoaded = false;
    /** Flag if {@link #mUidStats} have been loaded from disk. */
    private boolean mUidStatsLoaded = false;

    private NetworkStats mLastPollNetworkDevSnapshot;
    private NetworkStats mLastPollNetworkXtSnapshot;
    private NetworkStats mLastPollUidSnapshot;
    private NetworkStats mLastPollOperationsSnapshot;

    private NetworkStats mLastPersistNetworkDevSnapshot;
    private NetworkStats mLastPersistNetworkXtSnapshot;
    private NetworkStats mLastPersistUidSnapshot;

    /** Current counter sets for each UID. */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mOperations = new NetworkStats(0L, 10);

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final AtomicFile mNetworkDevFile;
    private final AtomicFile mNetworkXtFile;
    private final AtomicFile mUidFile;

    public NetworkStatsService(
            Context context, INetworkManagementService networkManager, IAlarmManager alarmManager) {
        this(context, networkManager, alarmManager, NtpTrustedTime.getInstance(context),
                getSystemDir(), new DefaultNetworkStatsSettings(context));
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    public NetworkStatsService(Context context, INetworkManagementService networkManager,
            IAlarmManager alarmManager, TrustedTime time, File systemDir,
            NetworkStatsSettings settings) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mAlarmManager = checkNotNull(alarmManager, "missing IAlarmManager");
        mTime = checkNotNull(time, "missing TrustedTime");
        mTeleManager = checkNotNull(TelephonyManager.getDefault(), "missing TelephonyManager");
        mSettings = checkNotNull(settings, "missing NetworkStatsSettings");

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), mHandlerCallback);

        mNetworkDevFile = new AtomicFile(new File(systemDir, "netstats.bin"));
        mNetworkXtFile = new AtomicFile(new File(systemDir, "netstats_xt.bin"));
        mUidFile = new AtomicFile(new File(systemDir, "netstats_uid.bin"));
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void systemReady() {
        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to track stats");
            return;
        }

        synchronized (mStatsLock) {
            // read historical network stats from disk, since policy service
            // might need them right away. we delay loading detailed UID stats
            // until actually needed.
            readNetworkDevStatsLocked();
            readNetworkXtStatsLocked();
            mNetworkStatsLoaded = true;
        }

        // bootstrap initial stats to prevent double-counting later
        bootstrapStats();

        // watch for network interfaces to be claimed
        final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION_IMMEDIATE);
        mContext.registerReceiver(mConnReceiver, connFilter, CONNECTIVITY_INTERNAL, mHandler);

        // watch for tethering changes
        final IntentFilter tetherFilter = new IntentFilter(ACTION_TETHER_STATE_CHANGED);
        mContext.registerReceiver(mTetherReceiver, tetherFilter, CONNECTIVITY_INTERNAL, mHandler);

        // listen for periodic polling events
        final IntentFilter pollFilter = new IntentFilter(ACTION_NETWORK_STATS_POLL);
        mContext.registerReceiver(mPollReceiver, pollFilter, READ_NETWORK_USAGE_HISTORY, mHandler);

        // listen for uid removal to clean stats
        final IntentFilter removedFilter = new IntentFilter(ACTION_UID_REMOVED);
        mContext.registerReceiver(mRemovedReceiver, removedFilter, null, mHandler);

        // persist stats during clean shutdown
        final IntentFilter shutdownFilter = new IntentFilter(ACTION_SHUTDOWN);
        mContext.registerReceiver(mShutdownReceiver, shutdownFilter);

        try {
            mNetworkManager.registerObserver(mAlertObserver);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }

        // watch for networkType changes that aren't broadcast through
        // CONNECTIVITY_ACTION_IMMEDIATE above.
        mTeleManager.listen(mPhoneListener, LISTEN_DATA_CONNECTION_STATE);

        registerPollAlarmLocked();
        registerGlobalAlert();

        mDropBox = (DropBoxManager) mContext.getSystemService(Context.DROPBOX_SERVICE);
    }

    private void shutdownLocked() {
        mContext.unregisterReceiver(mConnReceiver);
        mContext.unregisterReceiver(mTetherReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        mTeleManager.listen(mPhoneListener, LISTEN_NONE);

        if (mNetworkStatsLoaded) {
            writeNetworkDevStatsLocked();
            writeNetworkXtStatsLocked();
        }
        if (mUidStatsLoaded) {
            writeUidStatsLocked();
        }
        mNetworkDevStats.clear();
        mNetworkXtStats.clear();
        mUidStats.clear();
        mNetworkStatsLoaded = false;
        mUidStatsLoaded = false;
    }

    /**
     * Clear any existing {@link #ACTION_NETWORK_STATS_POLL} alarms, and
     * reschedule based on current {@link NetworkStatsSettings#getPollInterval()}.
     */
    private void registerPollAlarmLocked() {
        try {
            if (mPollIntent != null) {
                mAlarmManager.remove(mPollIntent);
            }

            mPollIntent = PendingIntent.getBroadcast(
                    mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL), 0);

            final long currentRealtime = SystemClock.elapsedRealtime();
            mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, currentRealtime,
                    mSettings.getPollInterval(), mPollIntent);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Register for a global alert that is delivered through
     * {@link INetworkManagementEventObserver} once a threshold amount of data
     * has been transferred.
     */
    private void registerGlobalAlert() {
        try {
            final long alertBytes = mSettings.getPersistThreshold();
            mNetworkManager.setGlobalAlert(alertBytes);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem registering for global alert: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    @Override
    public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        return getHistoryForNetworkDev(template, fields);
    }

    private NetworkStatsHistory getHistoryForNetworkDev(NetworkTemplate template, int fields) {
        return getHistoryForNetwork(template, fields, mNetworkDevStats);
    }

    private NetworkStatsHistory getHistoryForNetworkXt(NetworkTemplate template, int fields) {
        return getHistoryForNetwork(template, fields, mNetworkXtStats);
    }

    private NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields,
            HashMap<NetworkIdentitySet, NetworkStatsHistory> source) {
        synchronized (mStatsLock) {
            // combine all interfaces that match template
            final NetworkStatsHistory combined = new NetworkStatsHistory(
                    mSettings.getNetworkBucketDuration(), estimateNetworkBuckets(), fields);
            for (NetworkIdentitySet ident : source.keySet()) {
                if (templateMatches(template, ident)) {
                    final NetworkStatsHistory history = source.get(ident);
                    if (history != null) {
                        combined.recordEntireHistory(history);
                    }
                }
            }
            return combined;
        }
    }

    @Override
    public NetworkStatsHistory getHistoryForUid(
            NetworkTemplate template, int uid, int set, int tag, int fields) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        synchronized (mStatsLock) {
            ensureUidStatsLoadedLocked();

            // combine all interfaces that match template
            final NetworkStatsHistory combined = new NetworkStatsHistory(
                    mSettings.getUidBucketDuration(), estimateUidBuckets(), fields);
            for (UidStatsKey key : mUidStats.keySet()) {
                final boolean setMatches = set == SET_ALL || key.set == set;
                if (templateMatches(template, key.ident) && key.uid == uid && setMatches
                        && key.tag == tag) {
                    final NetworkStatsHistory history = mUidStats.get(key);
                    combined.recordEntireHistory(history);
                }
            }

            return combined;
        }
    }

    @Override
    public NetworkStats getSummaryForNetwork(NetworkTemplate template, long start, long end) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        return getSummaryForNetworkDev(template, start, end);
    }

    private NetworkStats getSummaryForNetworkDev(NetworkTemplate template, long start, long end) {
        return getSummaryForNetwork(template, start, end, mNetworkDevStats);
    }

    private NetworkStats getSummaryForNetworkXt(NetworkTemplate template, long start, long end) {
        return getSummaryForNetwork(template, start, end, mNetworkXtStats);
    }

    private NetworkStats getSummaryForNetwork(NetworkTemplate template, long start, long end,
            HashMap<NetworkIdentitySet, NetworkStatsHistory> source) {
        synchronized (mStatsLock) {
            // use system clock to be externally consistent
            final long now = System.currentTimeMillis();

            final NetworkStats stats = new NetworkStats(end - start, 1);
            final NetworkStats.Entry entry = new NetworkStats.Entry();
            NetworkStatsHistory.Entry historyEntry = null;

            // combine total from all interfaces that match template
            for (NetworkIdentitySet ident : source.keySet()) {
                if (templateMatches(template, ident)) {
                    final NetworkStatsHistory history = source.get(ident);
                    historyEntry = history.getValues(start, end, now, historyEntry);

                    entry.iface = IFACE_ALL;
                    entry.uid = UID_ALL;
                    entry.tag = TAG_NONE;
                    entry.rxBytes = historyEntry.rxBytes;
                    entry.rxPackets = historyEntry.rxPackets;
                    entry.txBytes = historyEntry.txBytes;
                    entry.txPackets = historyEntry.txPackets;

                    stats.combineValues(entry);
                }
            }

            return stats;
        }
    }

    private long getHistoryStartLocked(
            NetworkTemplate template, HashMap<NetworkIdentitySet, NetworkStatsHistory> source) {
        long start = Long.MAX_VALUE;
        for (NetworkIdentitySet ident : source.keySet()) {
            if (templateMatches(template, ident)) {
                final NetworkStatsHistory history = source.get(ident);
                start = Math.min(start, history.getStart());
            }
        }
        return start;
    }

    @Override
    public NetworkStats getSummaryForAllUid(
            NetworkTemplate template, long start, long end, boolean includeTags) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        synchronized (mStatsLock) {
            ensureUidStatsLoadedLocked();

            // use system clock to be externally consistent
            final long now = System.currentTimeMillis();

            final NetworkStats stats = new NetworkStats(end - start, 24);
            final NetworkStats.Entry entry = new NetworkStats.Entry();
            NetworkStatsHistory.Entry historyEntry = null;

            for (UidStatsKey key : mUidStats.keySet()) {
                if (templateMatches(template, key.ident)) {
                    // always include summary under TAG_NONE, and include
                    // other tags when requested.
                    if (key.tag == TAG_NONE || includeTags) {
                        final NetworkStatsHistory history = mUidStats.get(key);
                        historyEntry = history.getValues(start, end, now, historyEntry);

                        entry.iface = IFACE_ALL;
                        entry.uid = key.uid;
                        entry.set = key.set;
                        entry.tag = key.tag;
                        entry.rxBytes = historyEntry.rxBytes;
                        entry.rxPackets = historyEntry.rxPackets;
                        entry.txBytes = historyEntry.txBytes;
                        entry.txPackets = historyEntry.txPackets;
                        entry.operations = historyEntry.operations;

                        if (entry.rxBytes > 0 || entry.rxPackets > 0 || entry.txBytes > 0
                                || entry.txPackets > 0 || entry.operations > 0) {
                            stats.combineValues(entry);
                        }
                    }
                }
            }

            return stats;
        }
    }

    @Override
    public NetworkStats getDataLayerSnapshotForUid(int uid) throws RemoteException {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(ACCESS_NETWORK_STATE, TAG);
        }

        // TODO: switch to data layer stats once kernel exports
        // for now, read network layer stats and flatten across all ifaces
        final NetworkStats networkLayer = mNetworkManager.getNetworkStatsUidDetail(uid);
        final NetworkStats dataLayer = new NetworkStats(
                networkLayer.getElapsedRealtime(), networkLayer.size());

        NetworkStats.Entry entry = null;
        for (int i = 0; i < networkLayer.size(); i++) {
            entry = networkLayer.getValues(i, entry);
            entry.iface = IFACE_ALL;
            dataLayer.combineValues(entry);
        }

        // splice in operation counts
        dataLayer.spliceOperationsFrom(mOperations);
        return dataLayer;
    }

    @Override
    public void incrementOperationCount(int uid, int tag, int operationCount) {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(MODIFY_NETWORK_ACCOUNTING, TAG);
        }

        if (operationCount < 0) {
            throw new IllegalArgumentException("operation count can only be incremented");
        }
        if (tag == TAG_NONE) {
            throw new IllegalArgumentException("operation count must have specific tag");
        }

        synchronized (mStatsLock) {
            final int set = mActiveUidCounterSet.get(uid, SET_DEFAULT);
            mOperations.combineValues(IFACE_ALL, uid, set, tag, 0L, 0L, 0L, 0L, operationCount);
            mOperations.combineValues(IFACE_ALL, uid, set, TAG_NONE, 0L, 0L, 0L, 0L, operationCount);
        }
    }

    @Override
    public void setUidForeground(int uid, boolean uidForeground) {
        mContext.enforceCallingOrSelfPermission(MODIFY_NETWORK_ACCOUNTING, TAG);

        synchronized (mStatsLock) {
            final int set = uidForeground ? SET_FOREGROUND : SET_DEFAULT;
            final int oldSet = mActiveUidCounterSet.get(uid, SET_DEFAULT);
            if (oldSet != set) {
                mActiveUidCounterSet.put(uid, set);
                setKernelCounterSet(uid, set);
            }
        }
    }

    @Override
    public void forceUpdate() {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        performPoll(FLAG_PERSIST_ALL);
    }

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to associate {@link TelephonyManager#getSubscriberId()}
     * with mobile interfaces.
     */
    private BroadcastReceiver mConnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.
            updateIfaces();
        }
    };

    /**
     * Receiver that watches for {@link Tethering} to claim interface pairs.
     */
    private BroadcastReceiver mTetherReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.
            performPoll(FLAG_PERSIST_NETWORK);
        }
    };

    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified UPDATE_DEVICE_STATS
            // permission above.
            performPoll(FLAG_PERSIST_ALL);

            // verify that we're watching global alert
            registerGlobalAlert();
        }
    };

    private BroadcastReceiver mRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and UID_REMOVED is protected
            // broadcast.
            final int uid = intent.getIntExtra(EXTRA_UID, 0);
            synchronized (mStatsLock) {
                mWakeLock.acquire();
                try {
                    removeUidLocked(uid);
                } finally {
                    mWakeLock.release();
                }
            }
        }
    };

    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // SHUTDOWN is protected broadcast.
            synchronized (mStatsLock) {
                shutdownLocked();
            }
        }
    };

    /**
     * Observer that watches for {@link INetworkManagementService} alerts.
     */
    private INetworkManagementEventObserver mAlertObserver = new NetworkAlertObserver() {
        @Override
        public void limitReached(String limitName, String iface) {
            // only someone like NMS should be calling us
            mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

            if (LIMIT_GLOBAL_ALERT.equals(limitName)) {
                // kick off background poll to collect network stats; UID stats
                // are handled during normal polling interval.
                final int flags = FLAG_PERSIST_NETWORK;
                mHandler.obtainMessage(MSG_PERFORM_POLL, flags, 0).sendToTarget();

                // re-arm global alert for next update
                registerGlobalAlert();
            }
        }
    };

    private int mLastPhoneState = TelephonyManager.DATA_UNKNOWN;
    private int mLastPhoneNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

    /**
     * Receiver that watches for {@link TelephonyManager} changes, such as
     * transitioning between network types.
     */
    private PhoneStateListener mPhoneListener = new PhoneStateListener() {
        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            final boolean stateChanged = state != mLastPhoneState;
            final boolean networkTypeChanged = networkType != mLastPhoneNetworkType;

            if (networkTypeChanged && !stateChanged) {
                // networkType changed without a state change, which means we
                // need to roll our own update. delay long enough for
                // ConnectivityManager to process.
                // TODO: add direct event to ConnectivityService instead of
                // relying on this delay.
                if (LOGV) Slog.v(TAG, "triggering delayed updateIfaces()");
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_UPDATE_IFACES), SECOND_IN_MILLIS);
            }

            mLastPhoneState = state;
            mLastPhoneNetworkType = networkType;
        }
    };

    private void updateIfaces() {
        synchronized (mStatsLock) {
            mWakeLock.acquire();
            try {
                updateIfacesLocked();
            } finally {
                mWakeLock.release();
            }
        }
    }

    /**
     * Inspect all current {@link NetworkState} to derive mapping from {@code
     * iface} to {@link NetworkStatsHistory}. When multiple {@link NetworkInfo}
     * are active on a single {@code iface}, they are combined under a single
     * {@link NetworkIdentitySet}.
     */
    private void updateIfacesLocked() {
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.

        // poll, but only persist network stats to keep codepath fast. UID stats
        // will be persisted during next alarm poll event.
        performPollLocked(FLAG_PERSIST_NETWORK);

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        // rebuild active interfaces based on connected networks
        mActiveIfaces.clear();

        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                // collect networks under their parent interfaces
                final String iface = state.linkProperties.getInterfaceName();

                NetworkIdentitySet ident = mActiveIfaces.get(iface);
                if (ident == null) {
                    ident = new NetworkIdentitySet();
                    mActiveIfaces.put(iface, ident);
                }

                ident.add(NetworkIdentity.buildNetworkIdentity(mContext, state));
            }
        }
    }

    /**
     * Bootstrap initial stats snapshot, usually during {@link #systemReady()}
     * so we have baseline values without double-counting.
     */
    private void bootstrapStats() {
        try {
            mLastPollUidSnapshot = mNetworkManager.getNetworkStatsUidDetail(UID_ALL);
            mLastPollNetworkDevSnapshot = mNetworkManager.getNetworkStatsSummary();
            mLastPollNetworkXtSnapshot = computeNetworkXtSnapshotFromUid(mLastPollUidSnapshot);
            mLastPollOperationsSnapshot = new NetworkStats(0L, 0);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void performPoll(int flags) {
        synchronized (mStatsLock) {
            mWakeLock.acquire();

            // try refreshing time source when stale
            if (mTime.getCacheAge() > mSettings.getTimeCacheMaxAge()) {
                mTime.forceRefresh();
            }

            try {
                performPollLocked(flags);
            } finally {
                mWakeLock.release();
            }
        }
    }

    /**
     * Periodic poll operation, reading current statistics and recording into
     * {@link NetworkStatsHistory}.
     */
    private void performPollLocked(int flags) {
        if (LOGV) Slog.v(TAG, "performPollLocked(flags=0x" + Integer.toHexString(flags) + ")");
        final long startRealtime = SystemClock.elapsedRealtime();

        final boolean persistNetwork = (flags & FLAG_PERSIST_NETWORK) != 0;
        final boolean persistUid = (flags & FLAG_PERSIST_UID) != 0;
        final boolean persistForce = (flags & FLAG_PERSIST_FORCE) != 0;

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();
        final long threshold = mSettings.getPersistThreshold();

        final NetworkStats uidSnapshot;
        final NetworkStats networkXtSnapshot;
        final NetworkStats networkDevSnapshot;
        try {
            // collect any tethering stats
            final NetworkStats tetherSnapshot = getNetworkStatsTethering();

            // record uid stats, folding in tethering stats
            uidSnapshot = mNetworkManager.getNetworkStatsUidDetail(UID_ALL);
            uidSnapshot.combineAllValues(tetherSnapshot);
            performUidPollLocked(uidSnapshot, currentTime);

            // record dev network stats
            networkDevSnapshot = mNetworkManager.getNetworkStatsSummary();
            performNetworkDevPollLocked(networkDevSnapshot, currentTime);

            // record xt network stats
            networkXtSnapshot = computeNetworkXtSnapshotFromUid(uidSnapshot);
            performNetworkXtPollLocked(networkXtSnapshot, currentTime);

        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
            return;
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        // persist when enough network data has occurred
        final long persistNetworkDevDelta = computeStatsDelta(
                mLastPersistNetworkDevSnapshot, networkDevSnapshot, true, "devp").getTotalBytes();
        final long persistNetworkXtDelta = computeStatsDelta(
                mLastPersistNetworkXtSnapshot, networkXtSnapshot, true, "xtp").getTotalBytes();
        final boolean networkOverThreshold = persistNetworkDevDelta > threshold
                || persistNetworkXtDelta > threshold;
        if (persistForce || (persistNetwork && networkOverThreshold)) {
            writeNetworkDevStatsLocked();
            writeNetworkXtStatsLocked();
            mLastPersistNetworkDevSnapshot = networkDevSnapshot;
            mLastPersistNetworkXtSnapshot = networkXtSnapshot;
        }

        // persist when enough uid data has occurred
        final long persistUidDelta = computeStatsDelta(
                mLastPersistUidSnapshot, uidSnapshot, true, "uidp").getTotalBytes();
        if (persistForce || (persistUid && persistUidDelta > threshold)) {
            writeUidStatsLocked();
            mLastPersistUidSnapshot = uidSnapshot;
        }

        if (LOGV) {
            final long duration = SystemClock.elapsedRealtime() - startRealtime;
            Slog.v(TAG, "performPollLocked() took " + duration + "ms");
        }

        if (ENABLE_SAMPLE_AFTER_POLL) {
            // sample stats after each full poll
            performSample();
        }

        // finally, dispatch updated event to any listeners
        final Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
        updatedIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcast(updatedIntent, READ_NETWORK_USAGE_HISTORY);
    }

    /**
     * Update {@link #mNetworkDevStats} historical usage.
     */
    private void performNetworkDevPollLocked(NetworkStats networkDevSnapshot, long currentTime) {
        final HashSet<String> unknownIface = Sets.newHashSet();

        final NetworkStats delta = computeStatsDelta(
                mLastPollNetworkDevSnapshot, networkDevSnapshot, false, "dev");
        final long timeStart = currentTime - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = mActiveIfaces.get(entry.iface);
            if (ident == null) {
                unknownIface.add(entry.iface);
                continue;
            }

            final NetworkStatsHistory history = findOrCreateNetworkDevStatsLocked(ident);
            history.recordData(timeStart, currentTime, entry);
        }

        mLastPollNetworkDevSnapshot = networkDevSnapshot;

        if (LOGD && unknownIface.size() > 0) {
            Slog.w(TAG, "unknown dev interfaces " + unknownIface + ", ignoring those stats");
        }
    }

    /**
     * Update {@link #mNetworkXtStats} historical usage.
     */
    private void performNetworkXtPollLocked(NetworkStats networkXtSnapshot, long currentTime) {
        final HashSet<String> unknownIface = Sets.newHashSet();

        final NetworkStats delta = computeStatsDelta(
                mLastPollNetworkXtSnapshot, networkXtSnapshot, false, "xt");
        final long timeStart = currentTime - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = mActiveIfaces.get(entry.iface);
            if (ident == null) {
                unknownIface.add(entry.iface);
                continue;
            }

            final NetworkStatsHistory history = findOrCreateNetworkXtStatsLocked(ident);
            history.recordData(timeStart, currentTime, entry);
        }

        mLastPollNetworkXtSnapshot = networkXtSnapshot;

        if (LOGD && unknownIface.size() > 0) {
            Slog.w(TAG, "unknown xt interfaces " + unknownIface + ", ignoring those stats");
        }
    }

    /**
     * Update {@link #mUidStats} historical usage.
     */
    private void performUidPollLocked(NetworkStats uidSnapshot, long currentTime) {
        ensureUidStatsLoadedLocked();

        final NetworkStats delta = computeStatsDelta(
                mLastPollUidSnapshot, uidSnapshot, false, "uid");
        final NetworkStats operationsDelta = computeStatsDelta(
                mLastPollOperationsSnapshot, mOperations, false, "uidop");
        final long timeStart = currentTime - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        NetworkStats.Entry operationsEntry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = mActiveIfaces.get(entry.iface);
            if (ident == null) {
                if (entry.rxBytes > 0 || entry.rxPackets > 0 || entry.txBytes > 0
                        || entry.txPackets > 0) {
                    Log.w(TAG, "dropping UID delta from unknown iface: " + entry);
                }
                continue;
            }

            // splice in operation counts since last poll
            final int j = operationsDelta.findIndex(IFACE_ALL, entry.uid, entry.set, entry.tag);
            if (j != -1) {
                operationsEntry = operationsDelta.getValues(j, operationsEntry);
                entry.operations = operationsEntry.operations;
            }

            final NetworkStatsHistory history = findOrCreateUidStatsLocked(
                    ident, entry.uid, entry.set, entry.tag);
            history.recordData(timeStart, currentTime, entry);
        }

        mLastPollUidSnapshot = uidSnapshot;
        mLastPollOperationsSnapshot = mOperations.clone();
    }

    /**
     * Sample recent statistics summary into {@link EventLog}.
     */
    private void performSample() {
        final long largestBucketSize = Math.max(
                mSettings.getNetworkBucketDuration(), mSettings.getUidBucketDuration());

        // take sample as atomic buckets
        final long now = mTime.hasCache() ? mTime.currentTimeMillis() : System.currentTimeMillis();
        final long end = now - (now % largestBucketSize) + largestBucketSize;
        final long start = end - largestBucketSize;

        final long trustedTime = mTime.hasCache() ? mTime.currentTimeMillis() : -1;
        long devHistoryStart = Long.MAX_VALUE;

        NetworkTemplate template = null;
        NetworkStats.Entry devTotal = null;
        NetworkStats.Entry xtTotal = null;
        NetworkStats.Entry uidTotal = null;

        // collect mobile sample
        template = buildTemplateMobileAll(getActiveSubscriberId(mContext));
        devTotal = getSummaryForNetworkDev(template, start, end).getTotal(devTotal);
        devHistoryStart = getHistoryStartLocked(template, mNetworkDevStats);
        xtTotal = getSummaryForNetworkXt(template, start, end).getTotal(xtTotal);
        uidTotal = getSummaryForAllUid(template, start, end, false).getTotal(uidTotal);

        EventLogTags.writeNetstatsMobileSample(
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                trustedTime, devHistoryStart);

        // collect wifi sample
        template = buildTemplateWifi();
        devTotal = getSummaryForNetworkDev(template, start, end).getTotal(devTotal);
        devHistoryStart = getHistoryStartLocked(template, mNetworkDevStats);
        xtTotal = getSummaryForNetworkXt(template, start, end).getTotal(xtTotal);
        uidTotal = getSummaryForAllUid(template, start, end, false).getTotal(uidTotal);
        EventLogTags.writeNetstatsWifiSample(
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                trustedTime, devHistoryStart);
    }

    /**
     * Clean up {@link #mUidStats} after UID is removed.
     */
    private void removeUidLocked(int uid) {
        ensureUidStatsLoadedLocked();

        // perform one last poll before removing
        performPollLocked(FLAG_PERSIST_ALL);

        final ArrayList<UidStatsKey> knownKeys = Lists.newArrayList();
        knownKeys.addAll(mUidStats.keySet());

        // migrate all UID stats into special "removed" bucket
        for (UidStatsKey key : knownKeys) {
            if (key.uid == uid) {
                // only migrate combined TAG_NONE history
                if (key.tag == TAG_NONE) {
                    final NetworkStatsHistory uidHistory = mUidStats.get(key);
                    final NetworkStatsHistory removedHistory = findOrCreateUidStatsLocked(
                            key.ident, UID_REMOVED, SET_DEFAULT, TAG_NONE);
                    removedHistory.recordEntireHistory(uidHistory);
                }
                mUidStats.remove(key);
            }
        }

        // clear UID from current stats snapshot
        if (mLastPollUidSnapshot != null) {
            mLastPollUidSnapshot = mLastPollUidSnapshot.withoutUid(uid);
            mLastPollNetworkXtSnapshot = computeNetworkXtSnapshotFromUid(mLastPollUidSnapshot);
        }

        // clear kernel stats associated with UID
        resetKernelUidStats(uid);

        // since this was radical rewrite, push to disk
        writeUidStatsLocked();
    }

    private NetworkStatsHistory findOrCreateNetworkXtStatsLocked(NetworkIdentitySet ident) {
        return findOrCreateNetworkStatsLocked(ident, mNetworkXtStats);
    }

    private NetworkStatsHistory findOrCreateNetworkDevStatsLocked(NetworkIdentitySet ident) {
        return findOrCreateNetworkStatsLocked(ident, mNetworkDevStats);
    }

    private NetworkStatsHistory findOrCreateNetworkStatsLocked(
            NetworkIdentitySet ident, HashMap<NetworkIdentitySet, NetworkStatsHistory> source) {
        final NetworkStatsHistory existing = source.get(ident);

        // update when no existing, or when bucket duration changed
        final long bucketDuration = mSettings.getNetworkBucketDuration();
        NetworkStatsHistory updated = null;
        if (existing == null) {
            updated = new NetworkStatsHistory(bucketDuration, 10);
        } else if (existing.getBucketDuration() != bucketDuration) {
            updated = new NetworkStatsHistory(
                    bucketDuration, estimateResizeBuckets(existing, bucketDuration));
            updated.recordEntireHistory(existing);
        }

        if (updated != null) {
            source.put(ident, updated);
            return updated;
        } else {
            return existing;
        }
    }

    private NetworkStatsHistory findOrCreateUidStatsLocked(
            NetworkIdentitySet ident, int uid, int set, int tag) {
        ensureUidStatsLoadedLocked();

        final UidStatsKey key = new UidStatsKey(ident, uid, set, tag);
        final NetworkStatsHistory existing = mUidStats.get(key);

        // update when no existing, or when bucket duration changed
        final long bucketDuration = mSettings.getUidBucketDuration();
        NetworkStatsHistory updated = null;
        if (existing == null) {
            updated = new NetworkStatsHistory(bucketDuration, 10);
        } else if (existing.getBucketDuration() != bucketDuration) {
            updated = new NetworkStatsHistory(
                    bucketDuration, estimateResizeBuckets(existing, bucketDuration));
            updated.recordEntireHistory(existing);
        }

        if (updated != null) {
            mUidStats.put(key, updated);
            return updated;
        } else {
            return existing;
        }
    }

    private void readNetworkDevStatsLocked() {
        if (LOGV) Slog.v(TAG, "readNetworkDevStatsLocked()");
        readNetworkStats(mNetworkDevFile, mNetworkDevStats);
    }

    private void readNetworkXtStatsLocked() {
        if (LOGV) Slog.v(TAG, "readNetworkXtStatsLocked()");
        readNetworkStats(mNetworkXtFile, mNetworkXtStats);
    }

    private static void readNetworkStats(
            AtomicFile inputFile, HashMap<NetworkIdentitySet, NetworkStatsHistory> output) {
        // clear any existing stats and read from disk
        output.clear();

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(inputFile.openRead()));

            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case VERSION_NETWORK_INIT: {
                    // network := size *(NetworkIdentitySet NetworkStatsHistory)
                    final int size = in.readInt();
                    for (int i = 0; i < size; i++) {
                        final NetworkIdentitySet ident = new NetworkIdentitySet(in);
                        final NetworkStatsHistory history = new NetworkStatsHistory(in);
                        output.put(ident, history);
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unexpected version: " + version);
                }
            }
        } catch (FileNotFoundException e) {
            // missing stats is okay, probably first boot
        } catch (IOException e) {
            Log.wtf(TAG, "problem reading network stats", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void ensureUidStatsLoadedLocked() {
        if (!mUidStatsLoaded) {
            readUidStatsLocked();
            mUidStatsLoaded = true;
        }
    }

    private void readUidStatsLocked() {
        if (LOGV) Slog.v(TAG, "readUidStatsLocked()");

        // clear any existing stats and read from disk
        mUidStats.clear();

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(mUidFile.openRead()));

            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case VERSION_UID_INIT: {
                    // uid := size *(UID NetworkStatsHistory)

                    // drop this data version, since we don't have a good
                    // mapping into NetworkIdentitySet.
                    break;
                }
                case VERSION_UID_WITH_IDENT: {
                    // uid := size *(NetworkIdentitySet size *(UID NetworkStatsHistory))

                    // drop this data version, since this version only existed
                    // for a short time.
                    break;
                }
                case VERSION_UID_WITH_TAG:
                case VERSION_UID_WITH_SET: {
                    // uid := size *(NetworkIdentitySet size *(uid set tag NetworkStatsHistory))
                    final int identSize = in.readInt();
                    for (int i = 0; i < identSize; i++) {
                        final NetworkIdentitySet ident = new NetworkIdentitySet(in);

                        final int size = in.readInt();
                        for (int j = 0; j < size; j++) {
                            final int uid = in.readInt();
                            final int set = (version >= VERSION_UID_WITH_SET) ? in.readInt()
                                    : SET_DEFAULT;
                            final int tag = in.readInt();

                            final UidStatsKey key = new UidStatsKey(ident, uid, set, tag);
                            final NetworkStatsHistory history = new NetworkStatsHistory(in);
                            mUidStats.put(key, history);
                        }
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unexpected version: " + version);
                }
            }
        } catch (FileNotFoundException e) {
            // missing stats is okay, probably first boot
        } catch (IOException e) {
            Log.wtf(TAG, "problem reading uid stats", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void writeNetworkDevStatsLocked() {
        if (LOGV) Slog.v(TAG, "writeNetworkDevStatsLocked()");
        writeNetworkStats(mNetworkDevStats, mNetworkDevFile);
    }

    private void writeNetworkXtStatsLocked() {
        if (LOGV) Slog.v(TAG, "writeNetworkXtStatsLocked()");
        writeNetworkStats(mNetworkXtStats, mNetworkXtFile);
    }

    private void writeNetworkStats(
            HashMap<NetworkIdentitySet, NetworkStatsHistory> input, AtomicFile outputFile) {
        // TODO: consider duplicating stats and releasing lock while writing

        // trim any history beyond max
        if (mTime.hasCache()) {
            final long systemCurrentTime = System.currentTimeMillis();
            final long trustedCurrentTime = mTime.currentTimeMillis();

            final long currentTime = Math.min(systemCurrentTime, trustedCurrentTime);
            final long maxHistory = mSettings.getNetworkMaxHistory();

            for (NetworkStatsHistory history : input.values()) {
                final int beforeSize = history.size();
                history.removeBucketsBefore(currentTime - maxHistory);
                final int afterSize = history.size();

                if (beforeSize > 24 && afterSize < beforeSize / 2) {
                    // yikes, dropping more than half of significant history
                    final StringBuilder builder = new StringBuilder();
                    builder.append("yikes, dropping more than half of history").append('\n');
                    builder.append("systemCurrentTime=").append(systemCurrentTime).append('\n');
                    builder.append("trustedCurrentTime=").append(trustedCurrentTime).append('\n');
                    builder.append("maxHistory=").append(maxHistory).append('\n');
                    builder.append("beforeSize=").append(beforeSize).append('\n');
                    builder.append("afterSize=").append(afterSize).append('\n');
                    mDropBox.addText(TAG_NETSTATS_ERROR, builder.toString());
                }
            }
        }

        FileOutputStream fos = null;
        try {
            fos = outputFile.startWrite();
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos));

            out.writeInt(FILE_MAGIC);
            out.writeInt(VERSION_NETWORK_INIT);

            out.writeInt(input.size());
            for (NetworkIdentitySet ident : input.keySet()) {
                final NetworkStatsHistory history = input.get(ident);
                ident.writeToStream(out);
                history.writeToStream(out);
            }

            out.flush();
            outputFile.finishWrite(fos);
        } catch (IOException e) {
            Log.wtf(TAG, "problem writing stats", e);
            if (fos != null) {
                outputFile.failWrite(fos);
            }
        }
    }

    private void writeUidStatsLocked() {
        if (LOGV) Slog.v(TAG, "writeUidStatsLocked()");

        if (!mUidStatsLoaded) {
            Slog.w(TAG, "asked to write UID stats when not loaded; skipping");
            return;
        }

        // TODO: consider duplicating stats and releasing lock while writing

        // trim any history beyond max
        if (mTime.hasCache()) {
            final long currentTime = Math.min(
                    System.currentTimeMillis(), mTime.currentTimeMillis());
            final long maxUidHistory = mSettings.getUidMaxHistory();
            final long maxTagHistory = mSettings.getTagMaxHistory();
            for (UidStatsKey key : mUidStats.keySet()) {
                final NetworkStatsHistory history = mUidStats.get(key);

                // detailed tags are trimmed sooner than summary in TAG_NONE
                if (key.tag == TAG_NONE) {
                    history.removeBucketsBefore(currentTime - maxUidHistory);
                } else {
                    history.removeBucketsBefore(currentTime - maxTagHistory);
                }
            }
        }

        // build UidStatsKey lists grouped by ident
        final HashMap<NetworkIdentitySet, ArrayList<UidStatsKey>> keysByIdent = Maps.newHashMap();
        for (UidStatsKey key : mUidStats.keySet()) {
            ArrayList<UidStatsKey> keys = keysByIdent.get(key.ident);
            if (keys == null) {
                keys = Lists.newArrayList();
                keysByIdent.put(key.ident, keys);
            }
            keys.add(key);
        }

        FileOutputStream fos = null;
        try {
            fos = mUidFile.startWrite();
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos));

            out.writeInt(FILE_MAGIC);
            out.writeInt(VERSION_UID_WITH_SET);

            out.writeInt(keysByIdent.size());
            for (NetworkIdentitySet ident : keysByIdent.keySet()) {
                final ArrayList<UidStatsKey> keys = keysByIdent.get(ident);
                ident.writeToStream(out);

                out.writeInt(keys.size());
                for (UidStatsKey key : keys) {
                    final NetworkStatsHistory history = mUidStats.get(key);
                    out.writeInt(key.uid);
                    out.writeInt(key.set);
                    out.writeInt(key.tag);
                    history.writeToStream(out);
                }
            }

            out.flush();
            mUidFile.finishWrite(fos);
        } catch (IOException e) {
            Log.wtf(TAG, "problem writing stats", e);
            if (fos != null) {
                mUidFile.failWrite(fos);
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final HashSet<String> argSet = new HashSet<String>();
        for (String arg : args) {
            argSet.add(arg);
        }

        final boolean fullHistory = argSet.contains("full");

        synchronized (mStatsLock) {
            // TODO: remove this testing code, since it corrupts stats
            if (argSet.contains("generate")) {
                generateRandomLocked(args);
                pw.println("Generated stub stats");
                return;
            }

            if (argSet.contains("poll")) {
                performPollLocked(FLAG_PERSIST_ALL | FLAG_PERSIST_FORCE);
                pw.println("Forced poll");
                return;
            }

            pw.println("Active interfaces:");
            for (String iface : mActiveIfaces.keySet()) {
                final NetworkIdentitySet ident = mActiveIfaces.get(iface);
                pw.print("  iface="); pw.print(iface);
                pw.print(" ident="); pw.println(ident.toString());
            }

            pw.println("Known historical dev stats:");
            for (NetworkIdentitySet ident : mNetworkDevStats.keySet()) {
                final NetworkStatsHistory history = mNetworkDevStats.get(ident);
                pw.print("  ident="); pw.println(ident.toString());
                history.dump("  ", pw, fullHistory);
            }

            pw.println("Known historical xt stats:");
            for (NetworkIdentitySet ident : mNetworkXtStats.keySet()) {
                final NetworkStatsHistory history = mNetworkXtStats.get(ident);
                pw.print("  ident="); pw.println(ident.toString());
                history.dump("  ", pw, fullHistory);
            }

            if (argSet.contains("detail")) {
                // since explicitly requested with argument, we're okay to load
                // from disk if not already in memory.
                ensureUidStatsLoadedLocked();

                final ArrayList<UidStatsKey> keys = Lists.newArrayList();
                keys.addAll(mUidStats.keySet());
                Collections.sort(keys);

                pw.println("Detailed UID stats:");
                for (UidStatsKey key : keys) {
                    pw.print("  ident="); pw.print(key.ident.toString());
                    pw.print(" uid="); pw.print(key.uid);
                    pw.print(" set="); pw.print(NetworkStats.setToString(key.set));
                    pw.print(" tag="); pw.println(NetworkStats.tagToString(key.tag));

                    final NetworkStatsHistory history = mUidStats.get(key);
                    history.dump("    ", pw, fullHistory);
                }
            }
        }
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    private void generateRandomLocked(String[] args) {
        final long totalBytes = Long.parseLong(args[1]);
        final long totalTime = Long.parseLong(args[2]);
        
        final PackageManager pm = mContext.getPackageManager();
        final ArrayList<Integer> specialUidList = Lists.newArrayList();
        for (int i = 3; i < args.length; i++) {
            try {
                specialUidList.add(pm.getApplicationInfo(args[i], 0).uid);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        final HashSet<Integer> otherUidSet = Sets.newHashSet();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            if (pm.checkPermission(android.Manifest.permission.INTERNET, info.packageName)
                    == PackageManager.PERMISSION_GRANTED && !specialUidList.contains(info.uid)) {
                otherUidSet.add(info.uid);
            }
        }

        final ArrayList<Integer> otherUidList = new ArrayList<Integer>(otherUidSet);

        final long end = System.currentTimeMillis();
        final long start = end - totalTime;

        mNetworkDevStats.clear();
        mNetworkXtStats.clear();
        mUidStats.clear();

        final Random r = new Random();
        for (NetworkIdentitySet ident : mActiveIfaces.values()) {
            final NetworkStatsHistory devHistory = findOrCreateNetworkDevStatsLocked(ident);
            final NetworkStatsHistory xtHistory = findOrCreateNetworkXtStatsLocked(ident);

            final ArrayList<Integer> uidList = new ArrayList<Integer>();
            uidList.addAll(specialUidList);

            if (uidList.size() == 0) {
                Collections.shuffle(otherUidList);
                uidList.addAll(otherUidList);
            }

            boolean first = true;
            long remainingBytes = totalBytes;
            for (int uid : uidList) {
                final NetworkStatsHistory defaultHistory = findOrCreateUidStatsLocked(
                        ident, uid, SET_DEFAULT, TAG_NONE);
                final NetworkStatsHistory foregroundHistory = findOrCreateUidStatsLocked(
                        ident, uid, SET_FOREGROUND, TAG_NONE);

                final long uidBytes = totalBytes / uidList.size();

                final float fractionDefault = r.nextFloat();
                final long defaultBytes = (long) (uidBytes * fractionDefault);
                final long foregroundBytes = (long) (uidBytes * (1 - fractionDefault));

                defaultHistory.generateRandom(start, end, defaultBytes);
                foregroundHistory.generateRandom(start, end, foregroundBytes);

                if (first) {
                    final long bumpTime = (start + end) / 2;
                    defaultHistory.recordData(
                            bumpTime, bumpTime + DAY_IN_MILLIS, 200 * MB_IN_BYTES, 0);
                    first = false;
                }

                devHistory.recordEntireHistory(defaultHistory);
                devHistory.recordEntireHistory(foregroundHistory);
                xtHistory.recordEntireHistory(defaultHistory);
                xtHistory.recordEntireHistory(foregroundHistory);
            }
        }
    }

    /**
     * Return the delta between two {@link NetworkStats} snapshots, where {@code
     * before} can be {@code null}.
     */
    private NetworkStats computeStatsDelta(
            NetworkStats before, NetworkStats current, boolean collectStale, String type) {
        if (before != null) {
            try {
                return current.subtract(before, false);
            } catch (NonMonotonicException e) {
                Log.w(TAG, "found non-monotonic values; saving to dropbox");

                // record error for debugging
                final StringBuilder builder = new StringBuilder();
                builder.append("found non-monotonic " + type + " values at left[" + e.leftIndex
                        + "] - right[" + e.rightIndex + "]\n");
                builder.append("left=").append(e.left).append('\n');
                builder.append("right=").append(e.right).append('\n');
                mDropBox.addText(TAG_NETSTATS_ERROR, builder.toString());

                try {
                    // return clamped delta to help recover
                    return current.subtract(before, true);
                } catch (NonMonotonicException e1) {
                    Log.wtf(TAG, "found non-monotonic values; returning empty delta", e1);
                    return new NetworkStats(0L, 10);
                }
            }
        } else if (collectStale) {
            // caller is okay collecting stale stats for first call.
            return current;
        } else {
            // this is first snapshot; to prevent from double-counting we only
            // observe traffic occuring between known snapshots.
            return new NetworkStats(0L, 10);
        }
    }

    /**
     * Return snapshot of current tethering statistics. Will return empty
     * {@link NetworkStats} if any problems are encountered.
     */
    private NetworkStats getNetworkStatsTethering() throws RemoteException {
        try {
            final String[] tetheredIfacePairs = mConnManager.getTetheredIfacePairs();
            return mNetworkManager.getNetworkStatsTethering(tetheredIfacePairs);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
            return new NetworkStats(0L, 10);
        }
    }

    private static NetworkStats computeNetworkXtSnapshotFromUid(NetworkStats uidSnapshot) {
        return uidSnapshot.groupedByIface();
    }

    private int estimateNetworkBuckets() {
        return (int) (mSettings.getNetworkMaxHistory() / mSettings.getNetworkBucketDuration());
    }

    private int estimateUidBuckets() {
        return (int) (mSettings.getUidMaxHistory() / mSettings.getUidBucketDuration());
    }

    private static int estimateResizeBuckets(NetworkStatsHistory existing, long newBucketDuration) {
        return (int) (existing.size() * existing.getBucketDuration() / newBucketDuration);
    }

    /**
     * Test if given {@link NetworkTemplate} matches any {@link NetworkIdentity}
     * in the given {@link NetworkIdentitySet}.
     */
    private static boolean templateMatches(NetworkTemplate template, NetworkIdentitySet identSet) {
        for (NetworkIdentity ident : identSet) {
            if (template.matches(ident)) {
                return true;
            }
        }
        return false;
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        /** {@inheritDoc} */
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERFORM_POLL: {
                    final int flags = msg.arg1;
                    performPoll(flags);
                    return true;
                }
                case MSG_UPDATE_IFACES: {
                    updateIfaces();
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

    private static String getActiveSubscriberId(Context context) {
        final TelephonyManager telephony = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephony.getSubscriberId();
    }

    private boolean isBandwidthControlEnabled() {
        try {
            return mNetworkManager.isBandwidthControlEnabled();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return false;
        }
    }

    /**
     * Key uniquely identifying a {@link NetworkStatsHistory} for a UID.
     */
    private static class UidStatsKey implements Comparable<UidStatsKey> {
        public final NetworkIdentitySet ident;
        public final int uid;
        public final int set;
        public final int tag;

        public UidStatsKey(NetworkIdentitySet ident, int uid, int set, int tag) {
            this.ident = ident;
            this.uid = uid;
            this.set = set;
            this.tag = tag;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(ident, uid, set, tag);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof UidStatsKey) {
                final UidStatsKey key = (UidStatsKey) obj;
                return Objects.equal(ident, key.ident) && uid == key.uid && set == key.set
                        && tag == key.tag;
            }
            return false;
        }

        /** {@inheritDoc} */
        public int compareTo(UidStatsKey another) {
            return Integer.compare(uid, another.uid);
        }
    }

    /**
     * Default external settings that read from {@link Settings.Secure}.
     */
    private static class DefaultNetworkStatsSettings implements NetworkStatsSettings {
        private final ContentResolver mResolver;

        public DefaultNetworkStatsSettings(Context context) {
            mResolver = checkNotNull(context.getContentResolver());
            // TODO: adjust these timings for production builds
        }

        private long getSecureLong(String name, long def) {
            return Settings.Secure.getLong(mResolver, name, def);
        }
        private boolean getSecureBoolean(String name, boolean def) {
            final int defInt = def ? 1 : 0;
            return Settings.Secure.getInt(mResolver, name, defInt) != 0;
        }

        public long getPollInterval() {
            return getSecureLong(NETSTATS_POLL_INTERVAL, 30 * MINUTE_IN_MILLIS);
        }
        public long getPersistThreshold() {
            return getSecureLong(NETSTATS_PERSIST_THRESHOLD, 2 * MB_IN_BYTES);
        }
        public long getNetworkBucketDuration() {
            return getSecureLong(NETSTATS_NETWORK_BUCKET_DURATION, HOUR_IN_MILLIS);
        }
        public long getNetworkMaxHistory() {
            return getSecureLong(NETSTATS_NETWORK_MAX_HISTORY, 90 * DAY_IN_MILLIS);
        }
        public long getUidBucketDuration() {
            return getSecureLong(NETSTATS_UID_BUCKET_DURATION, 2 * HOUR_IN_MILLIS);
        }
        public long getUidMaxHistory() {
            return getSecureLong(NETSTATS_UID_MAX_HISTORY, 90 * DAY_IN_MILLIS);
        }
        public long getTagMaxHistory() {
            return getSecureLong(NETSTATS_TAG_MAX_HISTORY, 30 * DAY_IN_MILLIS);
        }
        public long getTimeCacheMaxAge() {
            return DAY_IN_MILLIS;
        }
    }
}
