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
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_REMOVED;
import static android.provider.Settings.Secure.NETSTATS_NETWORK_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_NETWORK_MAX_HISTORY;
import static android.provider.Settings.Secure.NETSTATS_PERSIST_THRESHOLD;
import static android.provider.Settings.Secure.NETSTATS_POLL_INTERVAL;
import static android.provider.Settings.Secure.NETSTATS_TAG_MAX_HISTORY;
import static android.provider.Settings.Secure.NETSTATS_UID_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_UID_MAX_HISTORY;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
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
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.NetworkIdentity;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TrustedTime;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.Objects;
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
import java.util.List;

import libcore.io.IoUtils;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    private static final String TAG = "NetworkStats";
    private static final boolean LOGD = true;
    private static final boolean LOGV = false;

    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;
    private static final int VERSION_NETWORK_INIT = 1;
    private static final int VERSION_UID_INIT = 1;
    private static final int VERSION_UID_WITH_IDENT = 2;
    private static final int VERSION_UID_WITH_TAG = 3;
    private static final int VERSION_UID_WITH_SET = 4;

    private static final int MSG_PERFORM_POLL = 0x1;
    private static final int MSG_PERFORM_POLL_DETAILED = 0x2;

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final IAlarmManager mAlarmManager;
    private final TrustedTime mTime;
    private final NetworkStatsSettings mSettings;

    private final PowerManager.WakeLock mWakeLock;

    private IConnectivityManager mConnManager;

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
    /** Set of historical network layer stats for known networks. */
    private HashMap<NetworkIdentitySet, NetworkStatsHistory> mNetworkStats = Maps.newHashMap();
    /** Set of historical network layer stats for known UIDs. */
    private HashMap<UidStatsKey, NetworkStatsHistory> mUidStats = Maps.newHashMap();

    /** Flag if {@link #mUidStats} have been loaded from disk. */
    private boolean mUidStatsLoaded = false;

    private NetworkStats mLastPollNetworkSnapshot;
    private NetworkStats mLastPollUidSnapshot;
    private NetworkStats mLastPollOperationsSnapshot;

    private NetworkStats mLastPersistNetworkSnapshot;
    private NetworkStats mLastPersistUidSnapshot;

    /** Current counter sets for each UID. */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mOperations = new NetworkStats(0L, 10);

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final AtomicFile mNetworkFile;
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
        mSettings = checkNotNull(settings, "missing NetworkStatsSettings");

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), mHandlerCallback);

        mNetworkFile = new AtomicFile(new File(systemDir, "netstats.bin"));
        mUidFile = new AtomicFile(new File(systemDir, "netstats_uid.bin"));
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void systemReady() {
        synchronized (mStatsLock) {
            // read historical network stats from disk, since policy service
            // might need them right away. we delay loading detailed UID stats
            // until actually needed.
            readNetworkStatsLocked();
        }

        // watch for network interfaces to be claimed
        final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION_IMMEDIATE);
        mContext.registerReceiver(mConnReceiver, connFilter, CONNECTIVITY_INTERNAL, mHandler);

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
            // ouch, no push updates means we fall back to
            // ACTION_NETWORK_STATS_POLL intervals.
            Slog.e(TAG, "unable to register INetworkManagementEventObserver", e);
        }

        registerPollAlarmLocked();
        registerGlobalAlert();

        // bootstrap initial stats to prevent double-counting later
        bootstrapStats();
    }

    private void shutdownLocked() {
        mContext.unregisterReceiver(mConnReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        writeNetworkStatsLocked();
        if (mUidStatsLoaded) {
            writeUidStatsLocked();
        }
        mNetworkStats.clear();
        mUidStats.clear();
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
            Slog.w(TAG, "problem registering for poll alarm: " + e);
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
            Slog.w(TAG, "problem registering for global alert: " + e);
        }
    }

    @Override
    public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        synchronized (mStatsLock) {
            // combine all interfaces that match template
            final NetworkStatsHistory combined = new NetworkStatsHistory(
                    mSettings.getNetworkBucketDuration(), estimateNetworkBuckets(), fields);
            for (NetworkIdentitySet ident : mNetworkStats.keySet()) {
                if (templateMatches(template, ident)) {
                    final NetworkStatsHistory history = mNetworkStats.get(ident);
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

        synchronized (mStatsLock) {
            // use system clock to be externally consistent
            final long now = System.currentTimeMillis();

            final NetworkStats stats = new NetworkStats(end - start, 1);
            final NetworkStats.Entry entry = new NetworkStats.Entry();
            NetworkStatsHistory.Entry historyEntry = null;

            // combine total from all interfaces that match template
            for (NetworkIdentitySet ident : mNetworkStats.keySet()) {
                if (templateMatches(template, ident)) {
                    final NetworkStatsHistory history = mNetworkStats.get(ident);
                    historyEntry = history.getValues(start, end, now, historyEntry);

                    entry.iface = IFACE_ALL;
                    entry.uid = UID_ALL;
                    entry.tag = TAG_NONE;
                    entry.rxBytes = historyEntry.rxBytes;
                    entry.txBytes = historyEntry.txBytes;

                    stats.combineValues(entry);
                }
            }

            return stats;
        }
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
        performPoll(true, false);
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
            synchronized (mStatsLock) {
                mWakeLock.acquire();
                try {
                    updateIfacesLocked();
                } finally {
                    mWakeLock.release();
                }
            }
        }
    };

    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified UPDATE_DEVICE_STATS
            // permission above.
            performPoll(true, false);

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
                // TODO: perform one last stats poll for UID
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
                mHandler.obtainMessage(MSG_PERFORM_POLL).sendToTarget();

                // re-arm global alert for next update
                registerGlobalAlert();
            }
        }
    };

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
        performPollLocked(false, false);

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network state");
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
            mLastPollNetworkSnapshot = mNetworkManager.getNetworkStatsSummary();
            mLastPollUidSnapshot = mNetworkManager.getNetworkStatsUidDetail(UID_ALL);
            mLastPollOperationsSnapshot = new NetworkStats(0L, 0);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
        }
    }

    private void performPoll(boolean detailedPoll, boolean forcePersist) {
        synchronized (mStatsLock) {
            mWakeLock.acquire();
            try {
                performPollLocked(detailedPoll, forcePersist);
            } finally {
                mWakeLock.release();
            }
        }
    }

    /**
     * Periodic poll operation, reading current statistics and recording into
     * {@link NetworkStatsHistory}.
     *
     * @param detailedPoll Indicate if detailed UID stats should be collected
     *            during this poll operation.
     */
    private void performPollLocked(boolean detailedPoll, boolean forcePersist) {
        if (LOGV) Slog.v(TAG, "performPollLocked()");
        final long startRealtime = SystemClock.elapsedRealtime();

        // try refreshing time source when stale
        if (mTime.getCacheAge() > mSettings.getTimeCacheMaxAge()) {
            mTime.forceRefresh();
        }

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();
        final long persistThreshold = mSettings.getPersistThreshold();

        final NetworkStats networkSnapshot;
        final NetworkStats uidSnapshot;
        try {
            networkSnapshot = mNetworkManager.getNetworkStatsSummary();
            uidSnapshot = detailedPoll ? mNetworkManager.getNetworkStatsUidDetail(UID_ALL) : null;
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
            return;
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
            return;
        }

        performNetworkPollLocked(networkSnapshot, currentTime);

        // persist when enough network data has occurred
        final NetworkStats persistNetworkDelta = computeStatsDelta(
                mLastPersistNetworkSnapshot, networkSnapshot, true);
        if (forcePersist || persistNetworkDelta.getTotalBytes() > persistThreshold) {
            writeNetworkStatsLocked();
            mLastPersistNetworkSnapshot = networkSnapshot;
        }

        if (detailedPoll) {
            performUidPollLocked(uidSnapshot, currentTime);

            // persist when enough network data has occurred
            final NetworkStats persistUidDelta = computeStatsDelta(
                    mLastPersistUidSnapshot, uidSnapshot, true);
            if (forcePersist || persistUidDelta.getTotalBytes() > persistThreshold) {
                writeUidStatsLocked();
                mLastPersistUidSnapshot = networkSnapshot;
            }
        }

        if (LOGV) {
            final long duration = SystemClock.elapsedRealtime() - startRealtime;
            Slog.v(TAG, "performPollLocked() took " + duration + "ms");
        }

        // finally, dispatch updated event to any listeners
        final Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
        updatedIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcast(updatedIntent, READ_NETWORK_USAGE_HISTORY);
    }

    /**
     * Update {@link #mNetworkStats} historical usage.
     */
    private void performNetworkPollLocked(NetworkStats networkSnapshot, long currentTime) {
        final HashSet<String> unknownIface = Sets.newHashSet();

        final NetworkStats delta = computeStatsDelta(mLastPollNetworkSnapshot, networkSnapshot, false);
        final long timeStart = currentTime - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = mActiveIfaces.get(entry.iface);
            if (ident == null) {
                unknownIface.add(entry.iface);
                continue;
            }

            final NetworkStatsHistory history = findOrCreateNetworkStatsLocked(ident);
            history.recordData(timeStart, currentTime, entry);
        }

        // trim any history beyond max
        final long maxHistory = mSettings.getNetworkMaxHistory();
        for (NetworkStatsHistory history : mNetworkStats.values()) {
            history.removeBucketsBefore(currentTime - maxHistory);
        }

        mLastPollNetworkSnapshot = networkSnapshot;

        if (LOGD && unknownIface.size() > 0) {
            Slog.w(TAG, "unknown interfaces " + unknownIface.toString() + ", ignoring those stats");
        }
    }

    /**
     * Update {@link #mUidStats} historical usage.
     */
    private void performUidPollLocked(NetworkStats uidSnapshot, long currentTime) {
        ensureUidStatsLoadedLocked();

        final NetworkStats delta = computeStatsDelta(mLastPollUidSnapshot, uidSnapshot, false);
        final NetworkStats operationsDelta = computeStatsDelta(
                mLastPollOperationsSnapshot, mOperations, false);
        final long timeStart = currentTime - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        NetworkStats.Entry operationsEntry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = mActiveIfaces.get(entry.iface);
            if (ident == null) {
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

        // trim any history beyond max
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

        mLastPollUidSnapshot = uidSnapshot;
        mLastPollOperationsSnapshot = mOperations;
        mOperations = new NetworkStats(0L, 10);
    }

    /**
     * Clean up {@link #mUidStats} after UID is removed.
     */
    private void removeUidLocked(int uid) {
        ensureUidStatsLoadedLocked();

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

        // clear kernel stats associated with UID
        resetKernelUidStats(uid);

        // since this was radical rewrite, push to disk
        writeUidStatsLocked();
    }

    private NetworkStatsHistory findOrCreateNetworkStatsLocked(NetworkIdentitySet ident) {
        final NetworkStatsHistory existing = mNetworkStats.get(ident);

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
            mNetworkStats.put(ident, updated);
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

    private void readNetworkStatsLocked() {
        if (LOGV) Slog.v(TAG, "readNetworkStatsLocked()");

        // clear any existing stats and read from disk
        mNetworkStats.clear();

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(mNetworkFile.openRead()));

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
                        mNetworkStats.put(ident, history);
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
            Slog.e(TAG, "problem reading network stats", e);
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
            Slog.e(TAG, "problem reading uid stats", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void writeNetworkStatsLocked() {
        if (LOGV) Slog.v(TAG, "writeNetworkStatsLocked()");

        // TODO: consider duplicating stats and releasing lock while writing

        FileOutputStream fos = null;
        try {
            fos = mNetworkFile.startWrite();
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos));

            out.writeInt(FILE_MAGIC);
            out.writeInt(VERSION_NETWORK_INIT);

            out.writeInt(mNetworkStats.size());
            for (NetworkIdentitySet ident : mNetworkStats.keySet()) {
                final NetworkStatsHistory history = mNetworkStats.get(ident);
                ident.writeToStream(out);
                history.writeToStream(out);
            }

            out.flush();
            mNetworkFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "problem writing stats: ", e);
            if (fos != null) {
                mNetworkFile.failWrite(fos);
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
            Slog.w(TAG, "problem writing stats: ", e);
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
                generateRandomLocked();
                pw.println("Generated stub stats");
                return;
            }

            if (argSet.contains("poll")) {
                performPollLocked(true, true);
                pw.println("Forced poll");
                return;
            }

            pw.println("Active interfaces:");
            for (String iface : mActiveIfaces.keySet()) {
                final NetworkIdentitySet ident = mActiveIfaces.get(iface);
                pw.print("  iface="); pw.print(iface);
                pw.print(" ident="); pw.println(ident.toString());
            }

            pw.println("Known historical stats:");
            for (NetworkIdentitySet ident : mNetworkStats.keySet()) {
                final NetworkStatsHistory history = mNetworkStats.get(ident);
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
    private void generateRandomLocked() {
        final long NET_END = System.currentTimeMillis();
        final long NET_START = NET_END - mSettings.getNetworkMaxHistory();
        final long NET_RX_BYTES = 3 * GB_IN_BYTES;
        final long NET_RX_PACKETS = NET_RX_BYTES / 1024;
        final long NET_TX_BYTES = 2 * GB_IN_BYTES;
        final long NET_TX_PACKETS = NET_TX_BYTES / 1024;

        final long UID_END = System.currentTimeMillis();
        final long UID_START = UID_END - mSettings.getUidMaxHistory();
        final long UID_RX_BYTES = 500 * MB_IN_BYTES;
        final long UID_RX_PACKETS = UID_RX_BYTES / 1024;
        final long UID_TX_BYTES = 100 * MB_IN_BYTES;
        final long UID_TX_PACKETS = UID_TX_BYTES / 1024;
        final long UID_OPERATIONS = UID_RX_BYTES / 2048;

        final List<ApplicationInfo> installedApps = mContext
                .getPackageManager().getInstalledApplications(0);

        mNetworkStats.clear();
        mUidStats.clear();
        for (NetworkIdentitySet ident : mActiveIfaces.values()) {
            findOrCreateNetworkStatsLocked(ident).generateRandom(NET_START, NET_END, NET_RX_BYTES,
                    NET_RX_PACKETS, NET_TX_BYTES, NET_TX_PACKETS, 0L);

            for (ApplicationInfo info : installedApps) {
                final int uid = info.uid;
                findOrCreateUidStatsLocked(ident, uid, SET_DEFAULT, TAG_NONE).generateRandom(
                        UID_START, UID_END, UID_RX_BYTES, UID_RX_PACKETS, UID_TX_BYTES,
                        UID_TX_PACKETS, UID_OPERATIONS);
                findOrCreateUidStatsLocked(ident, uid, SET_FOREGROUND, TAG_NONE).generateRandom(
                        UID_START, UID_END, UID_RX_BYTES, UID_RX_PACKETS, UID_TX_BYTES,
                        UID_TX_PACKETS, UID_OPERATIONS);
            }
        }
    }

    /**
     * Return the delta between two {@link NetworkStats} snapshots, where {@code
     * before} can be {@code null}.
     */
    private static NetworkStats computeStatsDelta(
            NetworkStats before, NetworkStats current, boolean collectStale) {
        if (before != null) {
            return current.subtractClamped(before);
        } else if (collectStale) {
            // caller is okay collecting stale stats for first call.
            return current;
        } else {
            // this is first snapshot; to prevent from double-counting we only
            // observe traffic occuring between known snapshots.
            return new NetworkStats(0L, 10);
        }
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
                    performPoll(false, false);
                    return true;
                }
                case MSG_PERFORM_POLL_DETAILED: {
                    performPoll(true, false);
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

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

        public long getPollInterval() {
            return getSecureLong(NETSTATS_POLL_INTERVAL, 30 * MINUTE_IN_MILLIS);
        }
        public long getPersistThreshold() {
            return getSecureLong(NETSTATS_PERSIST_THRESHOLD, 512 * KB_IN_BYTES);
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
