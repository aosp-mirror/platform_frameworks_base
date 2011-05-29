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

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.SHUTDOWN;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.NetworkStats.UID_ALL;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.IConnectivityManager;
import android.net.INetworkStatsService;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.TrustedTime;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    private static final String TAG = "NetworkStatsService";
    private static final boolean LOGD = true;

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final IAlarmManager mAlarmManager;
    private final TrustedTime mTime;

    private IConnectivityManager mConnManager;

    private static final String ACTION_NETWORK_STATS_POLL =
            "com.android.server.action.NETWORK_STATS_POLL";

    private PendingIntent mPollIntent;

    // TODO: move tweakable params to Settings.Secure
    // TODO: listen for kernel push events through netd instead of polling

    private static final long KB_IN_BYTES = 1024;

    private static final long POLL_INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
    private static final long SUMMARY_BUCKET_DURATION = 6 * DateUtils.HOUR_IN_MILLIS;
    private static final long SUMMARY_MAX_HISTORY = 90 * DateUtils.DAY_IN_MILLIS;

    // TODO: remove these high-frequency testing values
//    private static final long POLL_INTERVAL = 5 * DateUtils.SECOND_IN_MILLIS;
//    private static final long SUMMARY_BUCKET_DURATION = 10 * DateUtils.SECOND_IN_MILLIS;
//    private static final long SUMMARY_MAX_HISTORY = 2 * DateUtils.MINUTE_IN_MILLIS;

    /** Minimum delta required to persist to disk. */
    private static final long SUMMARY_PERSIST_THRESHOLD = 64 * KB_IN_BYTES;

    private static final long TIME_CACHE_MAX_AGE = DateUtils.DAY_IN_MILLIS;

    private final Object mStatsLock = new Object();

    /** Set of active ifaces during this boot. */
    private HashMap<String, InterfaceIdentity> mActiveIface = Maps.newHashMap();
    /** Set of historical stats for known ifaces. */
    private HashMap<InterfaceIdentity, NetworkStatsHistory> mStats = Maps.newHashMap();

    private NetworkStats mLastPollStats;
    private NetworkStats mLastPersistStats;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    // TODO: collect detailed uid stats, storing tag-granularity data until next
    // dropbox, and uid summary for a specific bucket count.

    // TODO: periodically compile statistics and send to dropbox.

    public NetworkStatsService(
            Context context, INetworkManagementService networkManager, IAlarmManager alarmManager) {
        // TODO: move to using cached NtpTrustedTime
        this(context, networkManager, alarmManager, new NtpTrustedTime());
    }

    public NetworkStatsService(Context context, INetworkManagementService networkManager,
            IAlarmManager alarmManager, TrustedTime time) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mAlarmManager = checkNotNull(alarmManager, "missing IAlarmManager");
        mTime = checkNotNull(time, "missing TrustedTime");

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public void systemReady() {
        // read historical stats from disk
        readStatsLocked();

        // watch other system services that claim interfaces
        final IntentFilter ifaceFilter = new IntentFilter();
        ifaceFilter.addAction(CONNECTIVITY_ACTION);
        mContext.registerReceiver(mIfaceReceiver, ifaceFilter, CONNECTIVITY_INTERNAL, mHandler);

        // listen for periodic polling events
        final IntentFilter pollFilter = new IntentFilter(ACTION_NETWORK_STATS_POLL);
        mContext.registerReceiver(mPollReceiver, pollFilter, UPDATE_DEVICE_STATS, mHandler);

        // persist stats during clean shutdown
        final IntentFilter shutdownFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(mShutdownReceiver, shutdownFilter, SHUTDOWN, null);

        try {
            registerPollAlarmLocked();
        } catch (RemoteException e) {
            Slog.w(TAG, "unable to register poll alarm");
        }
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    /**
     * Clear any existing {@link #ACTION_NETWORK_STATS_POLL} alarms, and
     * reschedule based on current {@link #POLL_INTERVAL} value.
     */
    private void registerPollAlarmLocked() throws RemoteException {
        if (mPollIntent != null) {
            mAlarmManager.remove(mPollIntent);
        }

        mPollIntent = PendingIntent.getBroadcast(
                mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL), 0);

        final long currentRealtime = SystemClock.elapsedRealtime();
        mAlarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, currentRealtime, POLL_INTERVAL, mPollIntent);
    }

    @Override
    public NetworkStatsHistory getHistoryForNetwork(int networkTemplate) {
        // TODO: create relaxed permission for reading stats
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, TAG);

        synchronized (mStatsLock) {
            // combine all interfaces that match template
            final String subscriberId = getActiveSubscriberId();
            final NetworkStatsHistory combined = new NetworkStatsHistory(SUMMARY_BUCKET_DURATION);
            for (InterfaceIdentity ident : mStats.keySet()) {
                final NetworkStatsHistory history = mStats.get(ident);
                if (ident.matchesTemplate(networkTemplate, subscriberId)) {
                    // TODO: combine all matching history data into a single history
                }
            }
            return combined;
        }
    }

    @Override
    public NetworkStatsHistory getHistoryForUid(int uid, int networkTemplate) {
        // TODO: create relaxed permission for reading stats
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, TAG);

        // TODO: return history for requested uid
        return null;
    }

    @Override
    public NetworkStats getSummaryPerUid(long start, long end, int networkTemplate) {
        // TODO: create relaxed permission for reading stats
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, TAG);

        // TODO: total UID-granularity usage between time range
        return null;
    }

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to associate {@link TelephonyManager#getSubscriberId()}
     * with mobile interfaces.
     */
    private BroadcastReceiver mIfaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.
            synchronized (mStatsLock) {
                updateIfacesLocked();
            }
        }
    };

    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified UPDATE_DEVICE_STATS
            // permission above.
            synchronized (mStatsLock) {
                // TODO: acquire wakelock while performing poll
                performPollLocked();
            }
        }
    };

    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // verified SHUTDOWN permission above.
            synchronized (mStatsLock) {
                writeStatsLocked();
            }
        }
    };

    /**
     * Inspect all current {@link NetworkState} to derive mapping from {@code
     * iface} to {@link NetworkStatsHistory}. When multiple {@link NetworkInfo}
     * are active on a single {@code iface}, they are combined under a single
     * {@link InterfaceIdentity}.
     */
    private void updateIfacesLocked() {
        if (LOGD) Slog.v(TAG, "updateIfacesLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.
        // TODO: verify that we only poll summary stats, not uid details
        performPollLocked();

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network state");
            return;
        }

        // rebuild active interfaces based on connected networks
        mActiveIface.clear();

        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                // collect networks under their parent interfaces
                final String iface = state.linkProperties.getInterfaceName();
                final InterfaceIdentity ident = findOrCreateInterfaceLocked(iface);
                ident.add(NetworkIdentity.buildNetworkIdentity(mContext, state));
            }
        }
    }

    private void performPollLocked() {
        if (LOGD) Slog.v(TAG, "performPollLocked()");

        // try refreshing time source when stale
        if (mTime.getCacheAge() > TIME_CACHE_MAX_AGE) {
            mTime.forceRefresh();
        }

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        final NetworkStats current;
        try {
            current = mNetworkManager.getNetworkStatsSummary();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network stats");
            return;
        }

        final ArrayList<String> unknownIface = Lists.newArrayList();

        // update historical usage with delta since last poll
        final NetworkStats pollDelta = computeStatsDelta(mLastPollStats, current);
        final long timeStart = currentTime - pollDelta.elapsedRealtime;
        for (String iface : pollDelta.getKnownIfaces()) {
            final InterfaceIdentity ident = mActiveIface.get(iface);
            if (ident == null) {
                unknownIface.add(iface);
                continue;
            }

            final int index = pollDelta.findIndex(iface, UID_ALL);
            final long rx = pollDelta.rx[index];
            final long tx = pollDelta.tx[index];

            final NetworkStatsHistory history = findOrCreateHistoryLocked(ident);
            history.recordData(timeStart, currentTime, rx, tx);
            history.removeBucketsBefore(currentTime - SUMMARY_MAX_HISTORY);
        }

        if (LOGD && unknownIface.size() > 0) {
            Slog.w(TAG, "unknown interfaces " + unknownIface.toString() + ", ignoring those stats");
        }

        mLastPollStats = current;

        // decide if enough has changed to trigger persist
        final NetworkStats persistDelta = computeStatsDelta(mLastPersistStats, current);
        for (String iface : persistDelta.getKnownIfaces()) {
            final int index = persistDelta.findIndex(iface, UID_ALL);
            if (persistDelta.rx[index] > SUMMARY_PERSIST_THRESHOLD
                    || persistDelta.tx[index] > SUMMARY_PERSIST_THRESHOLD) {
                writeStatsLocked();
                mLastPersistStats = current;
                break;
            }
        }
    }

    private NetworkStatsHistory findOrCreateHistoryLocked(InterfaceIdentity ident) {
        NetworkStatsHistory stats = mStats.get(ident);
        if (stats == null) {
            stats = new NetworkStatsHistory(SUMMARY_BUCKET_DURATION);
            mStats.put(ident, stats);
        }
        return stats;
    }

    private InterfaceIdentity findOrCreateInterfaceLocked(String iface) {
        InterfaceIdentity ident = mActiveIface.get(iface);
        if (ident == null) {
            ident = new InterfaceIdentity();
            mActiveIface.put(iface, ident);
        }
        return ident;
    }

    private void readStatsLocked() {
        if (LOGD) Slog.v(TAG, "readStatsLocked()");
        // TODO: read historical stats from disk using AtomicFile
    }

    private void writeStatsLocked() {
        if (LOGD) Slog.v(TAG, "writeStatsLocked()");
        // TODO: persist historical stats to disk using AtomicFile
        // TODO: consider duplicating stats and releasing lock while writing
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("Active interfaces:");
        for (String iface : mActiveIface.keySet()) {
            final InterfaceIdentity ident = mActiveIface.get(iface);
            pw.print("  iface="); pw.print(iface);
            pw.print(" ident="); pw.println(ident.toString());
        }

        pw.println("Known historical stats:");
        for (InterfaceIdentity ident : mStats.keySet()) {
            final NetworkStatsHistory stats = mStats.get(ident);
            pw.print("  ident="); pw.println(ident.toString());
            stats.dump("    ", pw);
        }
    }

    /**
     * Return the delta between two {@link NetworkStats} snapshots, where {@code
     * before} can be {@code null}.
     */
    private static NetworkStats computeStatsDelta(NetworkStats before, NetworkStats current) {
        if (before != null) {
            return current.subtract(before, false);
        } else {
            return current;
        }
    }

    private String getActiveSubscriberId() {
        final TelephonyManager telephony = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephony.getSubscriberId();
    }

}
