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
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileWildcard;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.TrafficStats.KB_IN_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.provider.Settings.Global.NETSTATS_DEV_BUCKET_DURATION;
import static android.provider.Settings.Global.NETSTATS_DEV_DELETE_AGE;
import static android.provider.Settings.Global.NETSTATS_DEV_PERSIST_BYTES;
import static android.provider.Settings.Global.NETSTATS_DEV_ROTATE_AGE;
import static android.provider.Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES;
import static android.provider.Settings.Global.NETSTATS_POLL_INTERVAL;
import static android.provider.Settings.Global.NETSTATS_SAMPLE_ENABLED;
import static android.provider.Settings.Global.NETSTATS_TIME_CACHE_MAX_AGE;
import static android.provider.Settings.Global.NETSTATS_UID_BUCKET_DURATION;
import static android.provider.Settings.Global.NETSTATS_UID_DELETE_AGE;
import static android.provider.Settings.Global.NETSTATS_UID_PERSIST_BYTES;
import static android.provider.Settings.Global.NETSTATS_UID_ROTATE_AGE;
import static android.provider.Settings.Global.NETSTATS_UID_TAG_BUCKET_DURATION;
import static android.provider.Settings.Global.NETSTATS_UID_TAG_DELETE_AGE;
import static android.provider.Settings.Global.NETSTATS_UID_TAG_PERSIST_BYTES;
import static android.provider.Settings.Global.NETSTATS_UID_TAG_ROTATE_AGE;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.android.internal.util.Preconditions.checkArgument;
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
import android.net.DataUsageRequest;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkIdentity;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicObserver;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.MathUtils;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TrustedTime;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.connectivity.Tethering;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    private static final String TAG = "NetworkStats";
    private static final boolean LOGV = false;

    private static final int MSG_PERFORM_POLL = 1;
    private static final int MSG_UPDATE_IFACES = 2;
    private static final int MSG_REGISTER_GLOBAL_ALERT = 3;

    /** Flags to control detail level of poll event. */
    private static final int FLAG_PERSIST_NETWORK = 0x1;
    private static final int FLAG_PERSIST_UID = 0x2;
    private static final int FLAG_PERSIST_ALL = FLAG_PERSIST_NETWORK | FLAG_PERSIST_UID;
    private static final int FLAG_PERSIST_FORCE = 0x100;

    private static final String TAG_NETSTATS_ERROR = "netstats_error";

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final AlarmManager mAlarmManager;
    private final TrustedTime mTime;
    private final TelephonyManager mTeleManager;
    private final NetworkStatsSettings mSettings;
    private final NetworkStatsObservers mStatsObservers;

    private final File mSystemDir;
    private final File mBaseDir;

    private final PowerManager.WakeLock mWakeLock;

    private IConnectivityManager mConnManager;

    @VisibleForTesting
    public static final String ACTION_NETWORK_STATS_POLL =
            "com.android.server.action.NETWORK_STATS_POLL";
    public static final String ACTION_NETWORK_STATS_UPDATED =
            "com.android.server.action.NETWORK_STATS_UPDATED";

    private PendingIntent mPollIntent;

    private static final String PREFIX_DEV = "dev";
    private static final String PREFIX_XT = "xt";
    private static final String PREFIX_UID = "uid";
    private static final String PREFIX_UID_TAG = "uid_tag";

    /**
     * Virtual network interface for video telephony. This is for VT data usage counting purpose.
     */
    public static final String VT_INTERFACE = "vt_data0";

    /**
     * Settings that can be changed externally.
     */
    public interface NetworkStatsSettings {
        public long getPollInterval();
        public long getTimeCacheMaxAge();
        public boolean getSampleEnabled();

        public static class Config {
            public final long bucketDuration;
            public final long rotateAgeMillis;
            public final long deleteAgeMillis;

            public Config(long bucketDuration, long rotateAgeMillis, long deleteAgeMillis) {
                this.bucketDuration = bucketDuration;
                this.rotateAgeMillis = rotateAgeMillis;
                this.deleteAgeMillis = deleteAgeMillis;
            }
        }

        public Config getDevConfig();
        public Config getXtConfig();
        public Config getUidConfig();
        public Config getUidTagConfig();

        public long getGlobalAlertBytes(long def);
        public long getDevPersistBytes(long def);
        public long getXtPersistBytes(long def);
        public long getUidPersistBytes(long def);
        public long getUidTagPersistBytes(long def);
    }

    private final Object mStatsLock = new Object();

    /** Set of currently active ifaces. */
    private final ArrayMap<String, NetworkIdentitySet> mActiveIfaces = new ArrayMap<>();
    /** Set of currently active ifaces for UID stats. */
    private final ArrayMap<String, NetworkIdentitySet> mActiveUidIfaces = new ArrayMap<>();
    /** Current default active iface. */
    private String mActiveIface;
    /** Set of any ifaces associated with mobile networks since boot. */
    private String[] mMobileIfaces = new String[0];

    private final DropBoxNonMonotonicObserver mNonMonotonicObserver =
            new DropBoxNonMonotonicObserver();

    private NetworkStatsRecorder mDevRecorder;
    private NetworkStatsRecorder mXtRecorder;
    private NetworkStatsRecorder mUidRecorder;
    private NetworkStatsRecorder mUidTagRecorder;

    /** Cached {@link #mXtRecorder} stats. */
    private NetworkStatsCollection mXtStatsCached;

    /** Current counter sets for each UID. */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mUidOperations = new NetworkStats(0L, 10);

    /** Must be set in factory by calling #setHandler. */
    private Handler mHandler;
    private Handler.Callback mHandlerCallback;

    private boolean mSystemReady;
    private long mPersistThreshold = 2 * MB_IN_BYTES;
    private long mGlobalAlertBytes;

    private static File getDefaultSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    private static File getDefaultBaseDir() {
        File baseDir = new File(getDefaultSystemDir(), "netstats");
        baseDir.mkdirs();
        return baseDir;
    }

    public static NetworkStatsService create(Context context,
                INetworkManagementService networkManager) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        NetworkStatsService service = new NetworkStatsService(context, networkManager, alarmManager,
                wakeLock, NtpTrustedTime.getInstance(context), TelephonyManager.getDefault(),
                new DefaultNetworkStatsSettings(context), new NetworkStatsObservers(),
                getDefaultSystemDir(), getDefaultBaseDir());

        HandlerThread handlerThread = new HandlerThread(TAG);
        Handler.Callback callback = new HandlerCallback(service);
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper(), callback);
        service.setHandler(handler, callback);
        return service;
    }

    @VisibleForTesting
    NetworkStatsService(Context context, INetworkManagementService networkManager,
            AlarmManager alarmManager, PowerManager.WakeLock wakeLock, TrustedTime time,
            TelephonyManager teleManager, NetworkStatsSettings settings,
            NetworkStatsObservers statsObservers, File systemDir, File baseDir) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mAlarmManager = checkNotNull(alarmManager, "missing AlarmManager");
        mTime = checkNotNull(time, "missing TrustedTime");
        mSettings = checkNotNull(settings, "missing NetworkStatsSettings");
        mTeleManager = checkNotNull(teleManager, "missing TelephonyManager");
        mWakeLock = checkNotNull(wakeLock, "missing WakeLock");
        mStatsObservers = checkNotNull(statsObservers, "missing NetworkStatsObservers");
        mSystemDir = checkNotNull(systemDir, "missing systemDir");
        mBaseDir = checkNotNull(baseDir, "missing baseDir");
    }

    @VisibleForTesting
    void setHandler(Handler handler, Handler.Callback callback) {
        mHandler = handler;
        mHandlerCallback = callback;
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void systemReady() {
        mSystemReady = true;

        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to track stats");
            return;
        }

        // create data recorders along with historical rotators
        mDevRecorder = buildRecorder(PREFIX_DEV, mSettings.getDevConfig(), false);
        mXtRecorder = buildRecorder(PREFIX_XT, mSettings.getXtConfig(), false);
        mUidRecorder = buildRecorder(PREFIX_UID, mSettings.getUidConfig(), false);
        mUidTagRecorder = buildRecorder(PREFIX_UID_TAG, mSettings.getUidTagConfig(), true);

        updatePersistThresholds();

        synchronized (mStatsLock) {
            // upgrade any legacy stats, migrating them to rotated files
            maybeUpgradeLegacyStatsLocked();

            // read historical network stats from disk, since policy service
            // might need them right away.
            mXtStatsCached = mXtRecorder.getOrLoadCompleteLocked();

            // bootstrap initial stats to prevent double-counting later
            bootstrapStatsLocked();
        }

        // watch for tethering changes
        final IntentFilter tetherFilter = new IntentFilter(ACTION_TETHER_STATE_CHANGED);
        mContext.registerReceiver(mTetherReceiver, tetherFilter, null, mHandler);

        // listen for periodic polling events
        final IntentFilter pollFilter = new IntentFilter(ACTION_NETWORK_STATS_POLL);
        mContext.registerReceiver(mPollReceiver, pollFilter, READ_NETWORK_USAGE_HISTORY, mHandler);

        // listen for uid removal to clean stats
        final IntentFilter removedFilter = new IntentFilter(ACTION_UID_REMOVED);
        mContext.registerReceiver(mRemovedReceiver, removedFilter, null, mHandler);

        // listen for user changes to clean stats
        final IntentFilter userFilter = new IntentFilter(ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        // persist stats during clean shutdown
        final IntentFilter shutdownFilter = new IntentFilter(ACTION_SHUTDOWN);
        mContext.registerReceiver(mShutdownReceiver, shutdownFilter);

        try {
            mNetworkManager.registerObserver(mAlertObserver);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }

        registerPollAlarmLocked();
        registerGlobalAlert();
    }

    private NetworkStatsRecorder buildRecorder(
            String prefix, NetworkStatsSettings.Config config, boolean includeTags) {
        final DropBoxManager dropBox = (DropBoxManager) mContext.getSystemService(
                Context.DROPBOX_SERVICE);
        return new NetworkStatsRecorder(new FileRotator(
                mBaseDir, prefix, config.rotateAgeMillis, config.deleteAgeMillis),
                mNonMonotonicObserver, dropBox, prefix, config.bucketDuration, includeTags);
    }

    private void shutdownLocked() {
        mContext.unregisterReceiver(mTetherReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        // persist any pending stats
        mDevRecorder.forcePersistLocked(currentTime);
        mXtRecorder.forcePersistLocked(currentTime);
        mUidRecorder.forcePersistLocked(currentTime);
        mUidTagRecorder.forcePersistLocked(currentTime);

        mDevRecorder = null;
        mXtRecorder = null;
        mUidRecorder = null;
        mUidTagRecorder = null;

        mXtStatsCached = null;

        mSystemReady = false;
    }

    private void maybeUpgradeLegacyStatsLocked() {
        File file;
        try {
            file = new File(mSystemDir, "netstats.bin");
            if (file.exists()) {
                mDevRecorder.importLegacyNetworkLocked(file);
                file.delete();
            }

            file = new File(mSystemDir, "netstats_xt.bin");
            if (file.exists()) {
                file.delete();
            }

            file = new File(mSystemDir, "netstats_uid.bin");
            if (file.exists()) {
                mUidRecorder.importLegacyUidLocked(file);
                mUidTagRecorder.importLegacyUidLocked(file);
                file.delete();
            }
        } catch (IOException e) {
            Log.wtf(TAG, "problem during legacy upgrade", e);
        } catch (OutOfMemoryError e) {
            Log.wtf(TAG, "problem during legacy upgrade", e);
        }
    }

    /**
     * Clear any existing {@link #ACTION_NETWORK_STATS_POLL} alarms, and
     * reschedule based on current {@link NetworkStatsSettings#getPollInterval()}.
     */
    private void registerPollAlarmLocked() {
        if (mPollIntent != null) {
            mAlarmManager.cancel(mPollIntent);
        }

        mPollIntent = PendingIntent.getBroadcast(
                mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL), 0);

        final long currentRealtime = SystemClock.elapsedRealtime();
        mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, currentRealtime,
                mSettings.getPollInterval(), mPollIntent);
    }

    /**
     * Register for a global alert that is delivered through
     * {@link INetworkManagementEventObserver} once a threshold amount of data
     * has been transferred.
     */
    private void registerGlobalAlert() {
        try {
            mNetworkManager.setGlobalAlert(mGlobalAlertBytes);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem registering for global alert: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    @Override
    public INetworkStatsSession openSession() {
        return createSession(null, /* poll on create */ false);
    }

    @Override
    public INetworkStatsSession openSessionForUsageStats(final String callingPackage) {
        return createSession(callingPackage, /* poll on create */ true);
    }

    private INetworkStatsSession createSession(final String callingPackage, boolean pollOnCreate) {
        assertBandwidthControlEnabled();

        if (pollOnCreate) {
            final long ident = Binder.clearCallingIdentity();
            try {
                performPoll(FLAG_PERSIST_ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        // return an IBinder which holds strong references to any loaded stats
        // for its lifetime; when caller closes only weak references remain.

        return new INetworkStatsSession.Stub() {
            private NetworkStatsCollection mUidComplete;
            private NetworkStatsCollection mUidTagComplete;
            private String mCallingPackage = callingPackage;

            private NetworkStatsCollection getUidComplete() {
                synchronized (mStatsLock) {
                    if (mUidComplete == null) {
                        mUidComplete = mUidRecorder.getOrLoadCompleteLocked();
                    }
                    return mUidComplete;
                }
            }

            private NetworkStatsCollection getUidTagComplete() {
                synchronized (mStatsLock) {
                    if (mUidTagComplete == null) {
                        mUidTagComplete = mUidTagRecorder.getOrLoadCompleteLocked();
                    }
                    return mUidTagComplete;
                }
            }

            @Override
            public int[] getRelevantUids() {
                return getUidComplete().getRelevantUids(checkAccessLevel(mCallingPackage));
            }

            @Override
            public NetworkStats getDeviceSummaryForNetwork(NetworkTemplate template, long start,
                    long end) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                if (accessLevel < NetworkStatsAccess.Level.DEVICESUMMARY) {
                    throw new SecurityException("Calling package " + mCallingPackage
                            + " cannot access device summary network stats");
                }
                NetworkStats result = new NetworkStats(end - start, 1);
                final long ident = Binder.clearCallingIdentity();
                try {
                    // Using access level higher than the one we checked for above.
                    // Reason is that we are combining usage data in a way that is not PII
                    // anymore.
                    result.combineAllValues(
                            internalGetSummaryForNetwork(template, start, end,
                                    NetworkStatsAccess.Level.DEVICE));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                return result;
            }

            @Override
            public NetworkStats getSummaryForNetwork(
                    NetworkTemplate template, long start, long end) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                return internalGetSummaryForNetwork(template, start, end, accessLevel);
            }

            @Override
            public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                return internalGetHistoryForNetwork(template, fields, accessLevel);
            }

            @Override
            public NetworkStats getSummaryForAllUid(
                    NetworkTemplate template, long start, long end, boolean includeTags) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                final NetworkStats stats =
                        getUidComplete().getSummary(template, start, end, accessLevel);
                if (includeTags) {
                    final NetworkStats tagStats = getUidTagComplete()
                            .getSummary(template, start, end, accessLevel);
                    stats.combineAllValues(tagStats);
                }
                return stats;
            }

            @Override
            public NetworkStatsHistory getHistoryForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                if (tag == TAG_NONE) {
                    return getUidComplete().getHistory(template, uid, set, tag, fields,
                            accessLevel);
                } else {
                    return getUidTagComplete().getHistory(template, uid, set, tag, fields,
                            accessLevel);
                }
            }

            @Override
            public NetworkStatsHistory getHistoryIntervalForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields,
                    long start, long end) {
                @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(mCallingPackage);
                if (tag == TAG_NONE) {
                    return getUidComplete().getHistory(template, uid, set, tag, fields, start, end,
                            accessLevel);
                } else if (uid == Binder.getCallingUid()) {
                    return getUidTagComplete().getHistory(template, uid, set, tag, fields,
                            start, end, accessLevel);
                } else {
                    throw new SecurityException("Calling package " + mCallingPackage
                            + " cannot access tag information from a different uid");
                }
            }

            @Override
            public void close() {
                mUidComplete = null;
                mUidTagComplete = null;
            }
        };
    }

    private @NetworkStatsAccess.Level int checkAccessLevel(String callingPackage) {
        return NetworkStatsAccess.checkAccessLevel(
                mContext, Binder.getCallingUid(), callingPackage);
    }

    /**
     * Return network summary, splicing between DEV and XT stats when
     * appropriate.
     */
    private NetworkStats internalGetSummaryForNetwork(
            NetworkTemplate template, long start, long end,
            @NetworkStatsAccess.Level int accessLevel) {
        // We've been using pure XT stats long enough that we no longer need to
        // splice DEV and XT together.
        return mXtStatsCached.getSummary(template, start, end, accessLevel);
    }

    /**
     * Return network history, splicing between DEV and XT stats when
     * appropriate.
     */
    private NetworkStatsHistory internalGetHistoryForNetwork(NetworkTemplate template, int fields,
            @NetworkStatsAccess.Level int accessLevel) {
        // We've been using pure XT stats long enough that we no longer need to
        // splice DEV and XT together.
        return mXtStatsCached.getHistory(template, UID_ALL, SET_ALL, TAG_NONE, fields, accessLevel);
    }

    @Override
    public long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        // Special case - since this is for internal use only, don't worry about a full access level
        // check and just require the signature/privileged permission.
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        assertBandwidthControlEnabled();
        return internalGetSummaryForNetwork(template, start, end, NetworkStatsAccess.Level.DEVICE)
                .getTotalBytes();
    }

    @Override
    public NetworkStats getDataLayerSnapshotForUid(int uid) throws RemoteException {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(ACCESS_NETWORK_STATE, TAG);
        }
        assertBandwidthControlEnabled();

        // TODO: switch to data layer stats once kernel exports
        // for now, read network layer stats and flatten across all ifaces
        final long token = Binder.clearCallingIdentity();
        final NetworkStats networkLayer;
        try {
            networkLayer = mNetworkManager.getNetworkStatsUidDetail(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // splice in operation counts
        networkLayer.spliceOperationsFrom(mUidOperations);

        final NetworkStats dataLayer = new NetworkStats(
                networkLayer.getElapsedRealtime(), networkLayer.size());

        NetworkStats.Entry entry = null;
        for (int i = 0; i < networkLayer.size(); i++) {
            entry = networkLayer.getValues(i, entry);
            entry.iface = IFACE_ALL;
            dataLayer.combineValues(entry);
        }

        return dataLayer;
    }

    @Override
    public String[] getMobileIfaces() {
        return mMobileIfaces;
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
            mUidOperations.combineValues(
                    mActiveIface, uid, set, tag, 0L, 0L, 0L, 0L, operationCount);
            mUidOperations.combineValues(
                    mActiveIface, uid, set, TAG_NONE, 0L, 0L, 0L, 0L, operationCount);
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
    public void forceUpdateIfaces() {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        assertBandwidthControlEnabled();

        final long token = Binder.clearCallingIdentity();
        try {
            updateIfaces();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void forceUpdate() {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        assertBandwidthControlEnabled();

        final long token = Binder.clearCallingIdentity();
        try {
            performPoll(FLAG_PERSIST_ALL);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void advisePersistThreshold(long thresholdBytes) {
        mContext.enforceCallingOrSelfPermission(MODIFY_NETWORK_ACCOUNTING, TAG);
        assertBandwidthControlEnabled();

        // clamp threshold into safe range
        mPersistThreshold = MathUtils.constrain(thresholdBytes, 128 * KB_IN_BYTES, 2 * MB_IN_BYTES);
        if (LOGV) {
            Slog.v(TAG, "advisePersistThreshold() given " + thresholdBytes + ", clamped to "
                    + mPersistThreshold);
        }

        // update and persist if beyond new thresholds
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();
        synchronized (mStatsLock) {
            if (!mSystemReady) return;

            updatePersistThresholds();

            mDevRecorder.maybePersistLocked(currentTime);
            mXtRecorder.maybePersistLocked(currentTime);
            mUidRecorder.maybePersistLocked(currentTime);
            mUidTagRecorder.maybePersistLocked(currentTime);
        }

        // re-arm global alert
        registerGlobalAlert();
    }

    @Override
    public DataUsageRequest registerUsageCallback(String callingPackage,
                DataUsageRequest request, Messenger messenger, IBinder binder) {
        checkNotNull(callingPackage, "calling package is null");
        checkNotNull(request, "DataUsageRequest is null");
        checkNotNull(request.template, "NetworkTemplate is null");
        checkNotNull(messenger, "messenger is null");
        checkNotNull(binder, "binder is null");

        int callingUid = Binder.getCallingUid();
        @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(callingPackage);
        DataUsageRequest normalizedRequest;
        final long token = Binder.clearCallingIdentity();
        try {
            normalizedRequest = mStatsObservers.register(request, messenger, binder,
                    callingUid, accessLevel);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Create baseline stats
        mHandler.sendMessage(mHandler.obtainMessage(MSG_PERFORM_POLL, FLAG_PERSIST_ALL));

        return normalizedRequest;
   }

    @Override
    public void unregisterUsageRequest(DataUsageRequest request) {
        checkNotNull(request, "DataUsageRequest is null");

        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            mStatsObservers.unregister(request, callingUid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Update {@link NetworkStatsRecorder} and {@link #mGlobalAlertBytes} to
     * reflect current {@link #mPersistThreshold} value. Always defers to
     * {@link Global} values when defined.
     */
    private void updatePersistThresholds() {
        mDevRecorder.setPersistThreshold(mSettings.getDevPersistBytes(mPersistThreshold));
        mXtRecorder.setPersistThreshold(mSettings.getXtPersistBytes(mPersistThreshold));
        mUidRecorder.setPersistThreshold(mSettings.getUidPersistBytes(mPersistThreshold));
        mUidTagRecorder.setPersistThreshold(mSettings.getUidTagPersistBytes(mPersistThreshold));
        mGlobalAlertBytes = mSettings.getGlobalAlertBytes(mPersistThreshold);
    }

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

            final int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (uid == -1) return;

            synchronized (mStatsLock) {
                mWakeLock.acquire();
                try {
                    removeUidsLocked(uid);
                } finally {
                    mWakeLock.release();
                }
            }
        }
    };

    private BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // On background handler thread, and USER_REMOVED is protected
            // broadcast.

            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) return;

            synchronized (mStatsLock) {
                mWakeLock.acquire();
                try {
                    removeUserLocked(userId);
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
    private INetworkManagementEventObserver mAlertObserver = new BaseNetworkObserver() {
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
                mHandler.obtainMessage(MSG_REGISTER_GLOBAL_ALERT).sendToTarget();
            }
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
        if (!mSystemReady) return;
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.

        // poll, but only persist network stats to keep codepath fast. UID stats
        // will be persisted during next alarm poll event.
        performPollLocked(FLAG_PERSIST_NETWORK);

        final NetworkState[] states;
        final LinkProperties activeLink;
        try {
            states = mConnManager.getAllNetworkState();
            activeLink = mConnManager.getActiveLinkProperties();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        mActiveIface = activeLink != null ? activeLink.getInterfaceName() : null;

        // Rebuild active interfaces based on connected networks
        mActiveIfaces.clear();
        mActiveUidIfaces.clear();

        final ArraySet<String> mobileIfaces = new ArraySet<>();
        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                final boolean isMobile = isNetworkTypeMobile(state.networkInfo.getType());
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);

                // Traffic occurring on the base interface is always counted for
                // both total usage and UID details.
                final String baseIface = state.linkProperties.getInterfaceName();
                if (baseIface != null) {
                    findOrCreateNetworkIdentitySet(mActiveIfaces, baseIface).add(ident);
                    findOrCreateNetworkIdentitySet(mActiveUidIfaces, baseIface).add(ident);

                    // Build a separate virtual interface for VT (Video Telephony) data usage.
                    // Only do this when IMS is not metered, but VT is metered.
                    // If IMS is metered, then the IMS network usage has already included VT usage.
                    // VT is considered always metered in framework's layer. If VT is not metered
                    // per carrier's policy, modem will report 0 usage for VT calls.
                    if (state.networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_IMS) && !ident.getMetered()) {

                        // Copy the identify from IMS one but mark it as metered.
                        NetworkIdentity vtIdent = new NetworkIdentity(ident.getType(),
                                ident.getSubType(), ident.getSubscriberId(), ident.getNetworkId(),
                                ident.getRoaming(), true);
                        findOrCreateNetworkIdentitySet(mActiveIfaces, VT_INTERFACE).add(vtIdent);
                        findOrCreateNetworkIdentitySet(mActiveUidIfaces, VT_INTERFACE).add(vtIdent);
                    }

                    if (isMobile) {
                        mobileIfaces.add(baseIface);
                    }
                }

                // Traffic occurring on stacked interfaces is usually clatd,
                // which is already accounted against its final egress interface
                // by the kernel. Thus, we only need to collect stacked
                // interface stats at the UID level.
                final List<LinkProperties> stackedLinks = state.linkProperties.getStackedLinks();
                for (LinkProperties stackedLink : stackedLinks) {
                    final String stackedIface = stackedLink.getInterfaceName();
                    if (stackedIface != null) {
                        findOrCreateNetworkIdentitySet(mActiveUidIfaces, stackedIface).add(ident);
                        if (isMobile) {
                            mobileIfaces.add(stackedIface);
                        }
                    }
                }
            }
        }

        mMobileIfaces = mobileIfaces.toArray(new String[mobileIfaces.size()]);
    }

    private static <K> NetworkIdentitySet findOrCreateNetworkIdentitySet(
            ArrayMap<K, NetworkIdentitySet> map, K key) {
        NetworkIdentitySet ident = map.get(key);
        if (ident == null) {
            ident = new NetworkIdentitySet();
            map.put(key, ident);
        }
        return ident;
    }

    private void recordSnapshotLocked(long currentTime) throws RemoteException {
        // snapshot and record current counters; read UID stats first to
        // avoid over counting dev stats.
        final NetworkStats uidSnapshot = getNetworkStatsUidDetail();
        final NetworkStats xtSnapshot = getNetworkStatsXtAndVt();
        final NetworkStats devSnapshot = mNetworkManager.getNetworkStatsSummaryDev();


        // For xt/dev, we pass a null VPN array because usage is aggregated by UID, so VPN traffic
        // can't be reattributed to responsible apps.
        mDevRecorder.recordSnapshotLocked(
                devSnapshot, mActiveIfaces, null /* vpnArray */, currentTime);
        mXtRecorder.recordSnapshotLocked(
                xtSnapshot, mActiveIfaces, null /* vpnArray */, currentTime);

        // For per-UID stats, pass the VPN info so VPN traffic is reattributed to responsible apps.
        VpnInfo[] vpnArray = mConnManager.getAllVpnInfo();
        mUidRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, vpnArray, currentTime);
        mUidTagRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, vpnArray, currentTime);

        // We need to make copies of member fields that are sent to the observer to avoid
        // a race condition between the service handler thread and the observer's
        mStatsObservers.updateStats(xtSnapshot, uidSnapshot, new ArrayMap<>(mActiveIfaces),
                new ArrayMap<>(mActiveUidIfaces), vpnArray, currentTime);
    }

    /**
     * Bootstrap initial stats snapshot, usually during {@link #systemReady()}
     * so we have baseline values without double-counting.
     */
    private void bootstrapStatsLocked() {
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        try {
            recordSnapshotLocked(currentTime);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void performPoll(int flags) {
        // try refreshing time source when stale
        if (mTime.getCacheAge() > mSettings.getTimeCacheMaxAge()) {
            mTime.forceRefresh();
        }

        synchronized (mStatsLock) {
            mWakeLock.acquire();

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
        if (!mSystemReady) return;
        if (LOGV) Slog.v(TAG, "performPollLocked(flags=0x" + Integer.toHexString(flags) + ")");

        final long startRealtime = SystemClock.elapsedRealtime();

        final boolean persistNetwork = (flags & FLAG_PERSIST_NETWORK) != 0;
        final boolean persistUid = (flags & FLAG_PERSIST_UID) != 0;
        final boolean persistForce = (flags & FLAG_PERSIST_FORCE) != 0;

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        try {
            recordSnapshotLocked(currentTime);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
            return;
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        // persist any pending data depending on requested flags
        if (persistForce) {
            mDevRecorder.forcePersistLocked(currentTime);
            mXtRecorder.forcePersistLocked(currentTime);
            mUidRecorder.forcePersistLocked(currentTime);
            mUidTagRecorder.forcePersistLocked(currentTime);
        } else {
            if (persistNetwork) {
                mDevRecorder.maybePersistLocked(currentTime);
                mXtRecorder.maybePersistLocked(currentTime);
            }
            if (persistUid) {
                mUidRecorder.maybePersistLocked(currentTime);
                mUidTagRecorder.maybePersistLocked(currentTime);
            }
        }

        if (LOGV) {
            final long duration = SystemClock.elapsedRealtime() - startRealtime;
            Slog.v(TAG, "performPollLocked() took " + duration + "ms");
        }

        if (mSettings.getSampleEnabled()) {
            // sample stats after each full poll
            performSampleLocked();
        }

        // finally, dispatch updated event to any listeners
        final Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
        updatedIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(updatedIntent, UserHandle.ALL,
                READ_NETWORK_USAGE_HISTORY);
    }

    /**
     * Sample recent statistics summary into {@link EventLog}.
     */
    private void performSampleLocked() {
        // TODO: migrate trustedtime fixes to separate binary log events
        final long trustedTime = mTime.hasCache() ? mTime.currentTimeMillis() : -1;

        NetworkTemplate template;
        NetworkStats.Entry devTotal;
        NetworkStats.Entry xtTotal;
        NetworkStats.Entry uidTotal;

        // collect mobile sample
        template = buildTemplateMobileWildcard();
        devTotal = mDevRecorder.getTotalSinceBootLocked(template);
        xtTotal = mXtRecorder.getTotalSinceBootLocked(template);
        uidTotal = mUidRecorder.getTotalSinceBootLocked(template);

        EventLogTags.writeNetstatsMobileSample(
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                trustedTime);

        // collect wifi sample
        template = buildTemplateWifiWildcard();
        devTotal = mDevRecorder.getTotalSinceBootLocked(template);
        xtTotal = mXtRecorder.getTotalSinceBootLocked(template);
        uidTotal = mUidRecorder.getTotalSinceBootLocked(template);

        EventLogTags.writeNetstatsWifiSample(
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                trustedTime);
    }

    /**
     * Clean up {@link #mUidRecorder} after UID is removed.
     */
    private void removeUidsLocked(int... uids) {
        if (LOGV) Slog.v(TAG, "removeUidsLocked() for UIDs " + Arrays.toString(uids));

        // Perform one last poll before removing
        performPollLocked(FLAG_PERSIST_ALL);

        mUidRecorder.removeUidsLocked(uids);
        mUidTagRecorder.removeUidsLocked(uids);

        // Clear kernel stats associated with UID
        for (int uid : uids) {
            resetKernelUidStats(uid);
        }
    }

    /**
     * Clean up {@link #mUidRecorder} after user is removed.
     */
    private void removeUserLocked(int userId) {
        if (LOGV) Slog.v(TAG, "removeUserLocked() for userId=" + userId);

        // Build list of UIDs that we should clean up
        int[] uids = new int[0];
        final List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS);
        for (ApplicationInfo app : apps) {
            final int uid = UserHandle.getUid(userId, app.uid);
            uids = ArrayUtils.appendInt(uids, uid);
        }

        removeUidsLocked(uids);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter rawWriter, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        long duration = DateUtils.DAY_IN_MILLIS;
        final HashSet<String> argSet = new HashSet<String>();
        for (String arg : args) {
            argSet.add(arg);

            if (arg.startsWith("--duration=")) {
                try {
                    duration = Long.parseLong(arg.substring(11));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // usage: dumpsys netstats --full --uid --tag --poll --checkin
        final boolean poll = argSet.contains("--poll") || argSet.contains("poll");
        final boolean checkin = argSet.contains("--checkin");
        final boolean fullHistory = argSet.contains("--full") || argSet.contains("full");
        final boolean includeUid = argSet.contains("--uid") || argSet.contains("detail");
        final boolean includeTag = argSet.contains("--tag") || argSet.contains("detail");

        final IndentingPrintWriter pw = new IndentingPrintWriter(rawWriter, "  ");

        synchronized (mStatsLock) {
            if (poll) {
                performPollLocked(FLAG_PERSIST_ALL | FLAG_PERSIST_FORCE);
                pw.println("Forced poll");
                return;
            }

            if (checkin) {
                final long end = System.currentTimeMillis();
                final long start = end - duration;

                pw.print("v1,");
                pw.print(start / SECOND_IN_MILLIS); pw.print(',');
                pw.print(end / SECOND_IN_MILLIS); pw.println();

                pw.println("xt");
                mXtRecorder.dumpCheckin(rawWriter, start, end);

                if (includeUid) {
                    pw.println("uid");
                    mUidRecorder.dumpCheckin(rawWriter, start, end);
                }
                if (includeTag) {
                    pw.println("tag");
                    mUidTagRecorder.dumpCheckin(rawWriter, start, end);
                }
                return;
            }

            pw.println("Active interfaces:");
            pw.increaseIndent();
            for (int i = 0; i < mActiveIfaces.size(); i++) {
                pw.printPair("iface", mActiveIfaces.keyAt(i));
                pw.printPair("ident", mActiveIfaces.valueAt(i));
                pw.println();
            }
            pw.decreaseIndent();

            pw.println("Active UID interfaces:");
            pw.increaseIndent();
            for (int i = 0; i < mActiveUidIfaces.size(); i++) {
                pw.printPair("iface", mActiveUidIfaces.keyAt(i));
                pw.printPair("ident", mActiveUidIfaces.valueAt(i));
                pw.println();
            }
            pw.decreaseIndent();

            pw.println("Dev stats:");
            pw.increaseIndent();
            mDevRecorder.dumpLocked(pw, fullHistory);
            pw.decreaseIndent();

            pw.println("Xt stats:");
            pw.increaseIndent();
            mXtRecorder.dumpLocked(pw, fullHistory);
            pw.decreaseIndent();

            if (includeUid) {
                pw.println("UID stats:");
                pw.increaseIndent();
                mUidRecorder.dumpLocked(pw, fullHistory);
                pw.decreaseIndent();
            }

            if (includeTag) {
                pw.println("UID tag stats:");
                pw.increaseIndent();
                mUidTagRecorder.dumpLocked(pw, fullHistory);
                pw.decreaseIndent();
            }
        }
    }

    /**
     * Return snapshot of current UID statistics, including any
     * {@link TrafficStats#UID_TETHERING} and {@link #mUidOperations} values.
     */
    private NetworkStats getNetworkStatsUidDetail() throws RemoteException {
        final NetworkStats uidSnapshot = mNetworkManager.getNetworkStatsUidDetail(UID_ALL);

        // fold tethering stats and operations into uid snapshot
        final NetworkStats tetherSnapshot = getNetworkStatsTethering();
        uidSnapshot.combineAllValues(tetherSnapshot);
        uidSnapshot.combineAllValues(mUidOperations);

        return uidSnapshot;
    }

    /**
     * Return snapshot of current XT plus VT statistics.
     */
    private NetworkStats getNetworkStatsXtAndVt() throws RemoteException {
        final NetworkStats xtSnapshot = mNetworkManager.getNetworkStatsSummaryXt();

        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);

        long usage = tm.getVtDataUsage();

        if (LOGV) Slog.d(TAG, "VT call data usage = " + usage);

        final NetworkStats vtSnapshot = new NetworkStats(SystemClock.elapsedRealtime(), 1);

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.iface = VT_INTERFACE;
        entry.uid = -1;
        entry.set = TAG_ALL;
        entry.tag = TAG_NONE;

        // Since modem only tell us the total usage instead of each usage for RX and TX,
        // we need to split it up (though it might not quite accurate). At
        // least we can make sure the data usage report to the user will still be accurate.
        entry.rxBytes = usage / 2;
        entry.rxPackets = 0;
        entry.txBytes = usage - entry.rxBytes;
        entry.txPackets = 0;
        vtSnapshot.combineValues(entry);

        // Merge VT int XT
        xtSnapshot.combineAllValues(vtSnapshot);

        return xtSnapshot;
    }

    /**
     * Return snapshot of current tethering statistics. Will return empty
     * {@link NetworkStats} if any problems are encountered.
     */
    private NetworkStats getNetworkStatsTethering() throws RemoteException {
        try {
            return mNetworkManager.getNetworkStatsTethering();
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
            return new NetworkStats(0L, 10);
        }
    }

    @VisibleForTesting
    static class HandlerCallback implements Handler.Callback {
        private final NetworkStatsService mService;

        HandlerCallback(NetworkStatsService service) {
            this.mService = service;
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERFORM_POLL: {
                    final int flags = msg.arg1;
                    mService.performPoll(flags);
                    return true;
                }
                case MSG_UPDATE_IFACES: {
                    mService.updateIfaces();
                    return true;
                }
                case MSG_REGISTER_GLOBAL_ALERT: {
                    mService.registerGlobalAlert();
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    }

    private void assertBandwidthControlEnabled() {
        if (!isBandwidthControlEnabled()) {
            throw new IllegalStateException("Bandwidth module disabled");
        }
    }

    private boolean isBandwidthControlEnabled() {
        final long token = Binder.clearCallingIdentity();
        try {
            return mNetworkManager.isBandwidthControlEnabled();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private class DropBoxNonMonotonicObserver implements NonMonotonicObserver<String> {
        @Override
        public void foundNonMonotonic(NetworkStats left, int leftIndex, NetworkStats right,
                int rightIndex, String cookie) {
            Log.w(TAG, "found non-monotonic values; saving to dropbox");

            // record error for debugging
            final StringBuilder builder = new StringBuilder();
            builder.append("found non-monotonic " + cookie + " values at left[" + leftIndex
                    + "] - right[" + rightIndex + "]\n");
            builder.append("left=").append(left).append('\n');
            builder.append("right=").append(right).append('\n');

            final DropBoxManager dropBox = (DropBoxManager) mContext.getSystemService(
                    Context.DROPBOX_SERVICE);
            dropBox.addText(TAG_NETSTATS_ERROR, builder.toString());
        }
    }

    /**
     * Default external settings that read from
     * {@link android.provider.Settings.Global}.
     */
    private static class DefaultNetworkStatsSettings implements NetworkStatsSettings {
        private final ContentResolver mResolver;

        public DefaultNetworkStatsSettings(Context context) {
            mResolver = checkNotNull(context.getContentResolver());
            // TODO: adjust these timings for production builds
        }

        private long getGlobalLong(String name, long def) {
            return Settings.Global.getLong(mResolver, name, def);
        }
        private boolean getGlobalBoolean(String name, boolean def) {
            final int defInt = def ? 1 : 0;
            return Settings.Global.getInt(mResolver, name, defInt) != 0;
        }

        @Override
        public long getPollInterval() {
            return getGlobalLong(NETSTATS_POLL_INTERVAL, 30 * MINUTE_IN_MILLIS);
        }
        @Override
        public long getTimeCacheMaxAge() {
            return getGlobalLong(NETSTATS_TIME_CACHE_MAX_AGE, DAY_IN_MILLIS);
        }
        @Override
        public long getGlobalAlertBytes(long def) {
            return getGlobalLong(NETSTATS_GLOBAL_ALERT_BYTES, def);
        }
        @Override
        public boolean getSampleEnabled() {
            return getGlobalBoolean(NETSTATS_SAMPLE_ENABLED, true);
        }
        @Override
        public Config getDevConfig() {
            return new Config(getGlobalLong(NETSTATS_DEV_BUCKET_DURATION, HOUR_IN_MILLIS),
                    getGlobalLong(NETSTATS_DEV_ROTATE_AGE, 15 * DAY_IN_MILLIS),
                    getGlobalLong(NETSTATS_DEV_DELETE_AGE, 90 * DAY_IN_MILLIS));
        }
        @Override
        public Config getXtConfig() {
            return getDevConfig();
        }
        @Override
        public Config getUidConfig() {
            return new Config(getGlobalLong(NETSTATS_UID_BUCKET_DURATION, 2 * HOUR_IN_MILLIS),
                    getGlobalLong(NETSTATS_UID_ROTATE_AGE, 15 * DAY_IN_MILLIS),
                    getGlobalLong(NETSTATS_UID_DELETE_AGE, 90 * DAY_IN_MILLIS));
        }
        @Override
        public Config getUidTagConfig() {
            return new Config(getGlobalLong(NETSTATS_UID_TAG_BUCKET_DURATION, 2 * HOUR_IN_MILLIS),
                    getGlobalLong(NETSTATS_UID_TAG_ROTATE_AGE, 5 * DAY_IN_MILLIS),
                    getGlobalLong(NETSTATS_UID_TAG_DELETE_AGE, 15 * DAY_IN_MILLIS));
        }
        @Override
        public long getDevPersistBytes(long def) {
            return getGlobalLong(NETSTATS_DEV_PERSIST_BYTES, def);
        }
        @Override
        public long getXtPersistBytes(long def) {
            return getDevPersistBytes(def);
        }
        @Override
        public long getUidPersistBytes(long def) {
            return getGlobalLong(NETSTATS_UID_PERSIST_BYTES, def);
        }
        @Override
        public long getUidTagPersistBytes(long def) {
            return getGlobalLong(NETSTATS_UID_TAG_PERSIST_BYTES, def);
        }
    }
}
