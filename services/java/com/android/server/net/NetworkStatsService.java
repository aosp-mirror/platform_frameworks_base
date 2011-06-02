/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.SHUTDOWN;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkStats.UID_ALL;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.wifi.WifiManager;
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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.google.android.collect.Maps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private HashMap<String, InterfaceInfo> mActiveIface = Maps.newHashMap();
    /** Set of historical stats for known ifaces. */
    private HashMap<InterfaceInfo, NetworkStatsHistory> mIfaceStats = Maps.newHashMap();

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
        // TODO: protect incoming broadcast with permissions check.
        // TODO: consider migrating this to ConnectivityService, but it might
        // cause a circular dependency.
        final IntentFilter interfaceFilter = new IntentFilter();
        interfaceFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        interfaceFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mInterfaceReceiver, interfaceFilter);

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
    public NetworkStatsHistory[] getNetworkStatsSummary(int networkType) {
        // TODO: return history for requested types
        return null;
    }

    @Override
    public NetworkStatsHistory getNetworkStatsUid(int uid) {
        // TODO: return history for requested uid
        return null;
    }

    /**
     * Receiver that watches for other system components that claim network
     * interfaces. Used to associate {@link TelephonyManager#getSubscriberId()}
     * with mobile interfaces.
     */
    private BroadcastReceiver mInterfaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED.equals(action)) {
                final LinkProperties prop = intent.getParcelableExtra(
                        Phone.DATA_LINK_PROPERTIES_KEY);
                final String iface = prop != null ? prop.getInterfaceName() : null;
                if (iface != null) {
                    final TelephonyManager teleManager = (TelephonyManager) context
                            .getSystemService(Context.TELEPHONY_SERVICE);
                    final InterfaceInfo info = new InterfaceInfo(
                            iface, TYPE_MOBILE, teleManager.getSubscriberId());
                    reportActiveInterface(info);
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                final LinkProperties prop = intent.getParcelableExtra(
                        WifiManager.EXTRA_LINK_PROPERTIES);
                final String iface = prop != null ? prop.getInterfaceName() : null;
                if (iface != null) {
                    final InterfaceInfo info = new InterfaceInfo(iface, TYPE_WIFI, null);
                    reportActiveInterface(info);
                }
            }
        }
    };

    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // already running on background handler, network/io is safe, and
            // caller verified to have UPDATE_DEVICE_STATS permission above.
            synchronized (mStatsLock) {
                // TODO: acquire wakelock while performing poll
                performPollLocked();
            }
        }
    };

    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // persist stats during clean shutdown
            synchronized (mStatsLock) {
                writeStatsLocked();
            }
        }
    };

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

        // update historical usage with delta since last poll
        final NetworkStats pollDelta = computeStatsDelta(mLastPollStats, current);
        final long timeStart = currentTime - pollDelta.elapsedRealtime;
        for (String iface : pollDelta.getKnownIfaces()) {
            final InterfaceInfo info = mActiveIface.get(iface);
            if (info == null) {
                if (LOGD) Slog.w(TAG, "unknown interface " + iface + ", ignoring stats");
                continue;
            }

            final int index = pollDelta.findIndex(iface, UID_ALL);
            final long rx = pollDelta.rx[index];
            final long tx = pollDelta.tx[index];

            final NetworkStatsHistory history = findOrCreateHistoryLocked(info);
            history.recordData(timeStart, currentTime, rx, tx);
            history.removeBucketsBefore(currentTime - SUMMARY_MAX_HISTORY);
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

    private NetworkStatsHistory findOrCreateHistoryLocked(InterfaceInfo info) {
        NetworkStatsHistory stats = mIfaceStats.get(info);
        if (stats == null) {
            stats = new NetworkStatsHistory(
                    info.networkType, info.identity, UID_ALL, SUMMARY_BUCKET_DURATION);
            mIfaceStats.put(info, stats);
        }
        return stats;
    }

    private void readStatsLocked() {
        if (LOGD) Slog.v(TAG, "readStatsLocked()");
        // TODO: read historical stats from disk using AtomicFile
    }

    private void writeStatsLocked() {
        if (LOGD) Slog.v(TAG, "writeStatsLocked()");
        // TODO: persist historical stats to disk using AtomicFile
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("Active interfaces:");
        for (InterfaceInfo info : mActiveIface.values()) {
            info.dump("  ", pw);
        }

        pw.println("Known historical stats:");
        for (NetworkStatsHistory stats : mIfaceStats.values()) {
            stats.dump("  ", pw);
        }
    }

    /**
     * Details for a well-known network interface, including its name, network
     * type, and billing relationship identity (such as IMSI).
     */
    private static class InterfaceInfo {
        public final String iface;
        public final int networkType;
        public final String identity;

        public InterfaceInfo(String iface, int networkType, String identity) {
            this.iface = iface;
            this.networkType = networkType;
            this.identity = identity;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((identity == null) ? 0 : identity.hashCode());
            result = prime * result + ((iface == null) ? 0 : iface.hashCode());
            result = prime * result + networkType;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof InterfaceInfo) {
                final InterfaceInfo info = (InterfaceInfo) obj;
                return equal(iface, info.iface) && networkType == info.networkType
                        && equal(identity, info.identity);
            }
            return false;
        }

        public void dump(String prefix, PrintWriter pw) {
            pw.print(prefix);
            pw.print("InterfaceInfo: iface="); pw.print(iface);
            pw.print(" networkType="); pw.print(networkType);
            pw.print(" identity="); pw.println(identity);
        }
    }

    private void reportActiveInterface(InterfaceInfo info) {
        synchronized (mStatsLock) {
            // TODO: when interface redefined, port over historical stats
            mActiveIface.put(info.iface, info);
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

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

}
