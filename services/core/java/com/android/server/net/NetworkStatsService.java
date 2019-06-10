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
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.NetworkStack.checkNetworkStackPermission;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.INTERFACES_ALL;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.STATS_PER_IFACE;
import static android.net.NetworkStats.STATS_PER_UID;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.FIELD_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileWildcard;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.TrafficStats.KB_IN_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.os.Trace.TRACE_TAG_NETWORK;
import static android.provider.Settings.Global.NETSTATS_AUGMENT_ENABLED;
import static android.provider.Settings.Global.NETSTATS_DEV_BUCKET_DURATION;
import static android.provider.Settings.Global.NETSTATS_DEV_DELETE_AGE;
import static android.provider.Settings.Global.NETSTATS_DEV_PERSIST_BYTES;
import static android.provider.Settings.Global.NETSTATS_DEV_ROTATE_AGE;
import static android.provider.Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES;
import static android.provider.Settings.Global.NETSTATS_POLL_INTERVAL;
import static android.provider.Settings.Global.NETSTATS_SAMPLE_ENABLED;
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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.NetworkManagementService.LIMIT_GLOBAL_ALERT;
import static com.android.server.NetworkManagementSocketTagger.resetKernelUidStats;
import static com.android.server.NetworkManagementSocketTagger.setKernelCounterSet;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.DataUsageRequest;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkIdentity;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicObserver;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.BestClock;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.NetworkInterfaceProto;
import android.service.NetworkStatsServiceDumpProto;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.connectivity.Tethering;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    static final String TAG = "NetworkStats";
    static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Perform polling and persist all (FLAG_PERSIST_ALL).
    private static final int MSG_PERFORM_POLL = 1;
    // Perform polling, persist network, and register the global alert again.
    private static final int MSG_PERFORM_POLL_REGISTER_ALERT = 2;

    /** Flags to control detail level of poll event. */
    private static final int FLAG_PERSIST_NETWORK = 0x1;
    private static final int FLAG_PERSIST_UID = 0x2;
    private static final int FLAG_PERSIST_ALL = FLAG_PERSIST_NETWORK | FLAG_PERSIST_UID;
    private static final int FLAG_PERSIST_FORCE = 0x100;

    /**
     * When global alert quota is high, wait for this delay before processing each polling,
     * and do not schedule further polls once there is already one queued.
     * This avoids firing the global alert too often on devices with high transfer speeds and
     * high quota.
     */
    private static final int PERFORM_POLL_DELAY_MS = 1000;

    private static final String TAG_NETSTATS_ERROR = "netstats_error";

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final AlarmManager mAlarmManager;
    private final Clock mClock;
    private final TelephonyManager mTeleManager;
    private final NetworkStatsSettings mSettings;
    private final NetworkStatsObservers mStatsObservers;

    private final File mSystemDir;
    private final File mBaseDir;

    private final PowerManager.WakeLock mWakeLock;

    private final boolean mUseBpfTrafficStats;

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
        public boolean getSampleEnabled();
        public boolean getAugmentEnabled();

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
    @GuardedBy("mStatsLock")
    private final ArrayMap<String, NetworkIdentitySet> mActiveIfaces = new ArrayMap<>();

    /** Set of currently active ifaces for UID stats. */
    @GuardedBy("mStatsLock")
    private final ArrayMap<String, NetworkIdentitySet> mActiveUidIfaces = new ArrayMap<>();

    /** Current default active iface. */
    @GuardedBy("mStatsLock")
    private String mActiveIface;

    /** Set of any ifaces associated with mobile networks since boot. */
    @GuardedBy("mStatsLock")
    private String[] mMobileIfaces = new String[0];

    /** Set of all ifaces currently used by traffic that does not explicitly specify a Network. */
    @GuardedBy("mStatsLock")
    private Network[] mDefaultNetworks = new Network[0];

    /** Set containing info about active VPNs and their underlying networks. */
    @GuardedBy("mStatsLock")
    private VpnInfo[] mVpnInfos = new VpnInfo[0];

    private final DropBoxNonMonotonicObserver mNonMonotonicObserver =
            new DropBoxNonMonotonicObserver();

    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mDevRecorder;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mXtRecorder;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mUidRecorder;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mUidTagRecorder;

    /** Cached {@link #mXtRecorder} stats. */
    @GuardedBy("mStatsLock")
    private NetworkStatsCollection mXtStatsCached;

    /** Current counter sets for each UID. */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mUidOperations = new NetworkStats(0L, 10);

    /** Must be set in factory by calling #setHandler. */
    private Handler mHandler;
    private Handler.Callback mHandlerCallback;

    private volatile boolean mSystemReady;
    private long mPersistThreshold = 2 * MB_IN_BYTES;
    private long mGlobalAlertBytes;

    private static final long POLL_RATE_LIMIT_MS = 15_000;

    private long mLastStatsSessionPoll;

    /** Map from UID to number of opened sessions */
    @GuardedBy("mOpenSessionCallsPerUid")
    private final SparseIntArray mOpenSessionCallsPerUid = new SparseIntArray();

    private final static int DUMP_STATS_SESSION_COUNT = 20;

    private static @NonNull File getDefaultSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    private static @NonNull File getDefaultBaseDir() {
        File baseDir = new File(getDefaultSystemDir(), "netstats");
        baseDir.mkdirs();
        return baseDir;
    }

    private static @NonNull Clock getDefaultClock() {
        return new BestClock(ZoneOffset.UTC, SystemClock.currentNetworkTimeClock(),
                Clock.systemUTC());
    }

    private static final class NetworkStatsHandler extends Handler {
        NetworkStatsHandler(Looper looper, Handler.Callback callback) {
            super(looper, callback);
        }
    }

    public static NetworkStatsService create(Context context,
                INetworkManagementService networkManager) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        NetworkStatsService service = new NetworkStatsService(context, networkManager, alarmManager,
                wakeLock, getDefaultClock(), TelephonyManager.getDefault(),
                new DefaultNetworkStatsSettings(context), new NetworkStatsObservers(),
                getDefaultSystemDir(), getDefaultBaseDir());
        service.registerLocalService();

        HandlerThread handlerThread = new HandlerThread(TAG);
        Handler.Callback callback = new HandlerCallback(service);
        handlerThread.start();
        Handler handler = new NetworkStatsHandler(handlerThread.getLooper(), callback);
        service.setHandler(handler, callback);
        return service;
    }

    // This must not be called outside of tests, even within the same package, as this constructor
    // does not register the local service. Use the create() helper above.
    @VisibleForTesting
    NetworkStatsService(Context context, INetworkManagementService networkManager,
            AlarmManager alarmManager, PowerManager.WakeLock wakeLock, Clock clock,
            TelephonyManager teleManager, NetworkStatsSettings settings,
            NetworkStatsObservers statsObservers, File systemDir, File baseDir) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mAlarmManager = checkNotNull(alarmManager, "missing AlarmManager");
        mClock = checkNotNull(clock, "missing Clock");
        mSettings = checkNotNull(settings, "missing NetworkStatsSettings");
        mTeleManager = checkNotNull(teleManager, "missing TelephonyManager");
        mWakeLock = checkNotNull(wakeLock, "missing WakeLock");
        mStatsObservers = checkNotNull(statsObservers, "missing NetworkStatsObservers");
        mSystemDir = checkNotNull(systemDir, "missing systemDir");
        mBaseDir = checkNotNull(baseDir, "missing baseDir");
        mUseBpfTrafficStats = new File("/sys/fs/bpf/map_netd_app_uid_stats_map").exists();
    }

    private void registerLocalService() {
        LocalServices.addService(NetworkStatsManagerInternal.class,
                new NetworkStatsManagerInternalImpl());
    }

    @VisibleForTesting
    void setHandler(Handler handler, Handler.Callback callback) {
        mHandler = handler;
        mHandlerCallback = callback;
    }

    public void systemReady() {
        mSystemReady = true;

        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to track stats");
            return;
        }

        synchronized (mStatsLock) {
            // create data recorders along with historical rotators
            mDevRecorder = buildRecorder(PREFIX_DEV, mSettings.getDevConfig(), false);
            mXtRecorder = buildRecorder(PREFIX_XT, mSettings.getXtConfig(), false);
            mUidRecorder = buildRecorder(PREFIX_UID, mSettings.getUidConfig(), false);
            mUidTagRecorder = buildRecorder(PREFIX_UID_TAG, mSettings.getUidTagConfig(), true);

            updatePersistThresholdsLocked();

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

    @GuardedBy("mStatsLock")
    private void shutdownLocked() {
        mContext.unregisterReceiver(mTetherReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mUserReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        final long currentTime = mClock.millis();

        // persist any pending stats
        mDevRecorder.forcePersistLocked(currentTime);
        mXtRecorder.forcePersistLocked(currentTime);
        mUidRecorder.forcePersistLocked(currentTime);
        mUidTagRecorder.forcePersistLocked(currentTime);

        mSystemReady = false;
    }

    @GuardedBy("mStatsLock")
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
        // NOTE: if callers want to get non-augmented data, they should go
        // through the public API
        return openSessionInternal(NetworkStatsManager.FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN, null);
    }

    @Override
    public INetworkStatsSession openSessionForUsageStats(int flags, String callingPackage) {
        return openSessionInternal(flags, callingPackage);
    }

    private boolean isRateLimitedForPoll(int callingUid) {
        if (callingUid == android.os.Process.SYSTEM_UID) {
            return false;
        }

        final long lastCallTime;
        final long now = SystemClock.elapsedRealtime();
        synchronized (mOpenSessionCallsPerUid) {
            int calls = mOpenSessionCallsPerUid.get(callingUid, 0);
            mOpenSessionCallsPerUid.put(callingUid, calls + 1);
            lastCallTime = mLastStatsSessionPoll;
            mLastStatsSessionPoll = now;
        }

        return now - lastCallTime < POLL_RATE_LIMIT_MS;
    }

    private INetworkStatsSession openSessionInternal(final int flags, final String callingPackage) {
        assertBandwidthControlEnabled();

        final int callingUid = Binder.getCallingUid();
        final int usedFlags = isRateLimitedForPoll(callingUid)
                ? flags & (~NetworkStatsManager.FLAG_POLL_ON_OPEN)
                : flags;
        if ((usedFlags & (NetworkStatsManager.FLAG_POLL_ON_OPEN
                | NetworkStatsManager.FLAG_POLL_FORCE)) != 0) {
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
            private final int mCallingUid = callingUid;
            private final String mCallingPackage = callingPackage;
            private final @NetworkStatsAccess.Level int mAccessLevel = checkAccessLevel(
                    callingPackage);

            private NetworkStatsCollection mUidComplete;
            private NetworkStatsCollection mUidTagComplete;

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
                return getUidComplete().getRelevantUids(mAccessLevel);
            }

            @Override
            public NetworkStats getDeviceSummaryForNetwork(
                    NetworkTemplate template, long start, long end) {
                return internalGetSummaryForNetwork(template, usedFlags, start, end, mAccessLevel,
                        mCallingUid);
            }

            @Override
            public NetworkStats getSummaryForNetwork(
                    NetworkTemplate template, long start, long end) {
                return internalGetSummaryForNetwork(template, usedFlags, start, end, mAccessLevel,
                        mCallingUid);
            }

            @Override
            public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
                return internalGetHistoryForNetwork(template, usedFlags, fields, mAccessLevel,
                        mCallingUid);
            }

            @Override
            public NetworkStats getSummaryForAllUid(
                    NetworkTemplate template, long start, long end, boolean includeTags) {
                try {
                    final NetworkStats stats = getUidComplete()
                            .getSummary(template, start, end, mAccessLevel, mCallingUid);
                    if (includeTags) {
                        final NetworkStats tagStats = getUidTagComplete()
                                .getSummary(template, start, end, mAccessLevel, mCallingUid);
                        stats.combineAllValues(tagStats);
                    }
                    return stats;
                } catch (NullPointerException e) {
                    // TODO: Track down and fix the cause of this crash and remove this catch block.
                    Slog.wtf(TAG, "NullPointerException in getSummaryForAllUid", e);
                    throw e;
                }
            }

            @Override
            public NetworkStatsHistory getHistoryForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields) {
                // NOTE: We don't augment UID-level statistics
                if (tag == TAG_NONE) {
                    return getUidComplete().getHistory(template, null, uid, set, tag, fields,
                            Long.MIN_VALUE, Long.MAX_VALUE, mAccessLevel, mCallingUid);
                } else {
                    return getUidTagComplete().getHistory(template, null, uid, set, tag, fields,
                            Long.MIN_VALUE, Long.MAX_VALUE, mAccessLevel, mCallingUid);
                }
            }

            @Override
            public NetworkStatsHistory getHistoryIntervalForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields,
                    long start, long end) {
                // NOTE: We don't augment UID-level statistics
                if (tag == TAG_NONE) {
                    return getUidComplete().getHistory(template, null, uid, set, tag, fields,
                            start, end, mAccessLevel, mCallingUid);
                } else if (uid == Binder.getCallingUid()) {
                    return getUidTagComplete().getHistory(template, null, uid, set, tag, fields,
                            start, end, mAccessLevel, mCallingUid);
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
     * Find the most relevant {@link SubscriptionPlan} for the given
     * {@link NetworkTemplate} and flags. This is typically used to augment
     * local measurement results to match a known anchor from the carrier.
     */
    private SubscriptionPlan resolveSubscriptionPlan(NetworkTemplate template, int flags) {
        SubscriptionPlan plan = null;
        if ((flags & NetworkStatsManager.FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN) != 0
                && mSettings.getAugmentEnabled()) {
            if (LOGD) Slog.d(TAG, "Resolving plan for " + template);
            final long token = Binder.clearCallingIdentity();
            try {
                plan = LocalServices.getService(NetworkPolicyManagerInternal.class)
                        .getSubscriptionPlan(template);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (LOGD) Slog.d(TAG, "Resolved to plan " + plan);
        }
        return plan;
    }

    /**
     * Return network summary, splicing between DEV and XT stats when
     * appropriate.
     */
    private NetworkStats internalGetSummaryForNetwork(NetworkTemplate template, int flags,
            long start, long end, @NetworkStatsAccess.Level int accessLevel, int callingUid) {
        // We've been using pure XT stats long enough that we no longer need to
        // splice DEV and XT together.
        final NetworkStatsHistory history = internalGetHistoryForNetwork(template, flags, FIELD_ALL,
                accessLevel, callingUid);

        final long now = System.currentTimeMillis();
        final NetworkStatsHistory.Entry entry = history.getValues(start, end, now, null);

        final NetworkStats stats = new NetworkStats(end - start, 1);
        stats.addValues(new NetworkStats.Entry(IFACE_ALL, UID_ALL, SET_ALL, TAG_NONE,
                METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL, entry.rxBytes, entry.rxPackets,
                entry.txBytes, entry.txPackets, entry.operations));
        return stats;
    }

    /**
     * Return network history, splicing between DEV and XT stats when
     * appropriate.
     */
    private NetworkStatsHistory internalGetHistoryForNetwork(NetworkTemplate template,
            int flags, int fields, @NetworkStatsAccess.Level int accessLevel, int callingUid) {
        // We've been using pure XT stats long enough that we no longer need to
        // splice DEV and XT together.
        final SubscriptionPlan augmentPlan = resolveSubscriptionPlan(template, flags);
        synchronized (mStatsLock) {
            return mXtStatsCached.getHistory(template, augmentPlan,
                    UID_ALL, SET_ALL, TAG_NONE, fields, Long.MIN_VALUE, Long.MAX_VALUE,
                    accessLevel, callingUid);
        }
    }

    private long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        assertSystemReady();
        assertBandwidthControlEnabled();

        // NOTE: if callers want to get non-augmented data, they should go
        // through the public API
        return internalGetSummaryForNetwork(template,
                NetworkStatsManager.FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN, start, end,
                NetworkStatsAccess.Level.DEVICE, Binder.getCallingUid()).getTotalBytes();
    }

    private NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
        assertSystemReady();
        assertBandwidthControlEnabled();

        final NetworkStatsCollection uidComplete;
        synchronized (mStatsLock) {
            uidComplete = mUidRecorder.getOrLoadCompleteLocked();
        }
        return uidComplete.getSummary(template, start, end, NetworkStatsAccess.Level.DEVICE,
                android.os.Process.SYSTEM_UID);
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
            networkLayer = mNetworkManager.getNetworkStatsUidDetail(uid,
                    NetworkStats.INTERFACES_ALL);
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
    public NetworkStats getDetailedUidStats(String[] requiredIfaces) {
        try {
            final String[] ifacesToQuery =
                    NetworkStatsFactory.augmentWithStackedInterfaces(requiredIfaces);
            return getNetworkStatsUidDetail(ifacesToQuery);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error compiling UID stats", e);
            return new NetworkStats(0L, 0);
        }
    }

    @Override
    public String[] getMobileIfaces() {
        return mMobileIfaces;
    }

    @Override
    public void incrementOperationCount(int uid, int tag, int operationCount) {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, TAG);
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

    @VisibleForTesting
    void setUidForeground(int uid, boolean uidForeground) {
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
    public void forceUpdateIfaces(
            Network[] defaultNetworks,
            VpnInfo[] vpnArray,
            NetworkState[] networkStates,
            String activeIface) {
        checkNetworkStackPermission(mContext);
        assertBandwidthControlEnabled();

        final long token = Binder.clearCallingIdentity();
        try {
            updateIfaces(defaultNetworks, vpnArray, networkStates, activeIface);
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

    private void advisePersistThreshold(long thresholdBytes) {
        assertBandwidthControlEnabled();

        // clamp threshold into safe range
        mPersistThreshold = MathUtils.constrain(thresholdBytes, 128 * KB_IN_BYTES, 2 * MB_IN_BYTES);
        if (LOGV) {
            Slog.v(TAG, "advisePersistThreshold() given " + thresholdBytes + ", clamped to "
                    + mPersistThreshold);
        }

        // update and persist if beyond new thresholds
        final long currentTime = mClock.millis();
        synchronized (mStatsLock) {
            if (!mSystemReady) return;

            updatePersistThresholdsLocked();

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
        mHandler.sendMessage(mHandler.obtainMessage(MSG_PERFORM_POLL));

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

    @Override
    public long getUidStats(int uid, int type) {
        return nativeGetUidStat(uid, type, checkBpfStatsEnable());
    }

    @Override
    public long getIfaceStats(String iface, int type) {
        long nativeIfaceStats = nativeGetIfaceStat(iface, type, checkBpfStatsEnable());
        if (nativeIfaceStats == -1) {
            return nativeIfaceStats;
        } else {
            // When tethering offload is in use, nativeIfaceStats does not contain usage from
            // offload, add it back here.
            // When tethering offload is not in use, nativeIfaceStats contains tethering usage.
            // this does not cause double-counting of tethering traffic, because
            // NetdTetheringStatsProvider returns zero NetworkStats
            // when called with STATS_PER_IFACE.
            return nativeIfaceStats + getTetherStats(iface, type);
        }
    }

    @Override
    public long getTotalStats(int type) {
        long nativeTotalStats = nativeGetTotalStat(type, checkBpfStatsEnable());
        if (nativeTotalStats == -1) {
            return nativeTotalStats;
        } else {
            // Refer to comment in getIfaceStats
            return nativeTotalStats + getTetherStats(IFACE_ALL, type);
        }
    }

    private long getTetherStats(String iface, int type) {
        final NetworkStats tetherSnapshot;
        final long token = Binder.clearCallingIdentity();
        try {
            tetherSnapshot = getNetworkStatsTethering(STATS_PER_IFACE);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error get TetherStats: " + e);
            return 0;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        HashSet<String> limitIfaces;
        if (iface == IFACE_ALL) {
            limitIfaces = null;
        } else {
            limitIfaces = new HashSet<String>();
            limitIfaces.add(iface);
        }
        NetworkStats.Entry entry = tetherSnapshot.getTotal(null, limitIfaces);
        if (LOGD) Slog.d(TAG, "TetherStats: iface=" + iface + " type=" + type +
                " entry=" + entry);
        switch (type) {
            case 0: // TYPE_RX_BYTES
                return entry.rxBytes;
            case 1: // TYPE_RX_PACKETS
                return entry.rxPackets;
            case 2: // TYPE_TX_BYTES
                return entry.txBytes;
            case 3: // TYPE_TX_PACKETS
                return entry.txPackets;
            default:
                return 0;
        }
    }

    private boolean checkBpfStatsEnable() {
        return mUseBpfTrafficStats;
    }

    /**
     * Update {@link NetworkStatsRecorder} and {@link #mGlobalAlertBytes} to
     * reflect current {@link #mPersistThreshold} value. Always defers to
     * {@link Global} values when defined.
     */
    @GuardedBy("mStatsLock")
    private void updatePersistThresholdsLocked() {
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
                // kick off background poll to collect network stats unless there is already
                // such a call pending; UID stats are handled during normal polling interval.
                if (!mHandler.hasMessages(MSG_PERFORM_POLL_REGISTER_ALERT)) {
                    mHandler.sendEmptyMessageDelayed(MSG_PERFORM_POLL_REGISTER_ALERT,
                            PERFORM_POLL_DELAY_MS);
                }
            }
        }
    };

    private void updateIfaces(
            Network[] defaultNetworks,
            VpnInfo[] vpnArray,
            NetworkState[] networkStates,
            String activeIface) {
        synchronized (mStatsLock) {
            mWakeLock.acquire();
            try {
                mVpnInfos = vpnArray;
                mActiveIface = activeIface;
                updateIfacesLocked(defaultNetworks, networkStates);
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
    @GuardedBy("mStatsLock")
    private void updateIfacesLocked(Network[] defaultNetworks, NetworkState[] states) {
        if (!mSystemReady) return;
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.

        // poll, but only persist network stats to keep codepath fast. UID stats
        // will be persisted during next alarm poll event.
        performPollLocked(FLAG_PERSIST_NETWORK);

        // Rebuild active interfaces based on connected networks
        mActiveIfaces.clear();
        mActiveUidIfaces.clear();
        if (defaultNetworks != null) {
            // Caller is ConnectivityService. Update the list of default networks.
            mDefaultNetworks = defaultNetworks;
        }

        final ArraySet<String> mobileIfaces = new ArraySet<>();
        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                final boolean isMobile = isNetworkTypeMobile(state.networkInfo.getType());
                final boolean isDefault = ArrayUtils.contains(mDefaultNetworks, state.network);
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state,
                        isDefault);

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
                                ident.getRoaming(), true /* metered */,
                                true /* onDefaultNetwork */);
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

                        NetworkStatsFactory.noteStackedIface(stackedIface, baseIface);
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

    @GuardedBy("mStatsLock")
    private void recordSnapshotLocked(long currentTime) throws RemoteException {
        // snapshot and record current counters; read UID stats first to
        // avoid over counting dev stats.
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotUid");
        final NetworkStats uidSnapshot = getNetworkStatsUidDetail(INTERFACES_ALL);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotXt");
        final NetworkStats xtSnapshot = getNetworkStatsXt();
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotDev");
        final NetworkStats devSnapshot = mNetworkManager.getNetworkStatsSummaryDev();
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // Tethering snapshot for dev and xt stats. Counts per-interface data from tethering stats
        // providers that isn't already counted by dev and XT stats.
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotTether");
        final NetworkStats tetherSnapshot = getNetworkStatsTethering(STATS_PER_IFACE);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        xtSnapshot.combineAllValues(tetherSnapshot);
        devSnapshot.combineAllValues(tetherSnapshot);

        // For xt/dev, we pass a null VPN array because usage is aggregated by UID, so VPN traffic
        // can't be reattributed to responsible apps.
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordDev");
        mDevRecorder.recordSnapshotLocked(
                devSnapshot, mActiveIfaces, null /* vpnArray */, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordXt");
        mXtRecorder.recordSnapshotLocked(
                xtSnapshot, mActiveIfaces, null /* vpnArray */, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // For per-UID stats, pass the VPN info so VPN traffic is reattributed to responsible apps.
        VpnInfo[] vpnArray = mVpnInfos;
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordUid");
        mUidRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, vpnArray, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordUidTag");
        mUidTagRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, vpnArray, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // We need to make copies of member fields that are sent to the observer to avoid
        // a race condition between the service handler thread and the observer's
        mStatsObservers.updateStats(xtSnapshot, uidSnapshot, new ArrayMap<>(mActiveIfaces),
                new ArrayMap<>(mActiveUidIfaces), vpnArray, currentTime);
    }

    /**
     * Bootstrap initial stats snapshot, usually during {@link #systemReady()}
     * so we have baseline values without double-counting.
     */
    @GuardedBy("mStatsLock")
    private void bootstrapStatsLocked() {
        final long currentTime = mClock.millis();

        try {
            recordSnapshotLocked(currentTime);
        } catch (IllegalStateException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void performPoll(int flags) {
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
    @GuardedBy("mStatsLock")
    private void performPollLocked(int flags) {
        if (!mSystemReady) return;
        if (LOGV) Slog.v(TAG, "performPollLocked(flags=0x" + Integer.toHexString(flags) + ")");
        Trace.traceBegin(TRACE_TAG_NETWORK, "performPollLocked");

        final boolean persistNetwork = (flags & FLAG_PERSIST_NETWORK) != 0;
        final boolean persistUid = (flags & FLAG_PERSIST_UID) != 0;
        final boolean persistForce = (flags & FLAG_PERSIST_FORCE) != 0;

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mClock.millis();

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
        Trace.traceBegin(TRACE_TAG_NETWORK, "[persisting]");
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
        Trace.traceEnd(TRACE_TAG_NETWORK);

        if (mSettings.getSampleEnabled()) {
            // sample stats after each full poll
            performSampleLocked();
        }

        // finally, dispatch updated event to any listeners
        final Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
        updatedIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(updatedIntent, UserHandle.ALL,
                READ_NETWORK_USAGE_HISTORY);

        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    /**
     * Sample recent statistics summary into {@link EventLog}.
     */
    @GuardedBy("mStatsLock")
    private void performSampleLocked() {
        // TODO: migrate trustedtime fixes to separate binary log events
        final long currentTime = mClock.millis();

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
                currentTime);

        // collect wifi sample
        template = buildTemplateWifiWildcard();
        devTotal = mDevRecorder.getTotalSinceBootLocked(template);
        xtTotal = mXtRecorder.getTotalSinceBootLocked(template);
        uidTotal = mUidRecorder.getTotalSinceBootLocked(template);

        EventLogTags.writeNetstatsWifiSample(
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                currentTime);
    }

    /**
     * Clean up {@link #mUidRecorder} after UID is removed.
     */
    @GuardedBy("mStatsLock")
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
    @GuardedBy("mStatsLock")
    private void removeUserLocked(int userId) {
        if (LOGV) Slog.v(TAG, "removeUserLocked() for userId=" + userId);

        // Build list of UIDs that we should clean up
        int[] uids = new int[0];
        final List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_ANY_USER
                | PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ApplicationInfo app : apps) {
            final int uid = UserHandle.getUid(userId, app.uid);
            uids = ArrayUtils.appendInt(uids, uid);
        }

        removeUidsLocked(uids);
    }

    private class NetworkStatsManagerInternalImpl extends NetworkStatsManagerInternal {
        @Override
        public long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
            Trace.traceBegin(TRACE_TAG_NETWORK, "getNetworkTotalBytes");
            try {
                return NetworkStatsService.this.getNetworkTotalBytes(template, start, end);
            } finally {
                Trace.traceEnd(TRACE_TAG_NETWORK);
            }
        }

        @Override
        public NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
            Trace.traceBegin(TRACE_TAG_NETWORK, "getNetworkUidBytes");
            try {
                return NetworkStatsService.this.getNetworkUidBytes(template, start, end);
            } finally {
                Trace.traceEnd(TRACE_TAG_NETWORK);
            }
        }

        @Override
        public void setUidForeground(int uid, boolean uidForeground) {
            NetworkStatsService.this.setUidForeground(uid, uidForeground);
        }

        @Override
        public void advisePersistThreshold(long thresholdBytes) {
            NetworkStatsService.this.advisePersistThreshold(thresholdBytes);
        }

        @Override
        public void forceUpdate() {
            NetworkStatsService.this.forceUpdate();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter rawWriter, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, rawWriter)) return;

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
            if (args.length > 0 && "--proto".equals(args[0])) {
                // In this case ignore all other arguments.
                dumpProtoLocked(fd);
                return;
            }

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

            // Get the top openSession callers
            final SparseIntArray calls;
            synchronized (mOpenSessionCallsPerUid) {
                calls = mOpenSessionCallsPerUid.clone();
            }

            final int N = calls.size();
            final long[] values = new long[N];
            for (int j = 0; j < N; j++) {
                values[j] = ((long) calls.valueAt(j) << 32) | calls.keyAt(j);
            }
            Arrays.sort(values);

            pw.println("Top openSession callers (uid=count):");
            pw.increaseIndent();
            final int end = Math.max(0, N - DUMP_STATS_SESSION_COUNT);
            for (int j = N - 1; j >= end; j--) {
                final int uid = (int) (values[j] & 0xffffffff);
                final int count = (int) (values[j] >> 32);
                pw.print(uid); pw.print("="); pw.println(count);
            }
            pw.decreaseIndent();
            pw.println();

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

    @GuardedBy("mStatsLock")
    private void dumpProtoLocked(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        // TODO Right now it writes all history.  Should it limit to the "since-boot" log?

        dumpInterfaces(proto, NetworkStatsServiceDumpProto.ACTIVE_INTERFACES, mActiveIfaces);
        dumpInterfaces(proto, NetworkStatsServiceDumpProto.ACTIVE_UID_INTERFACES, mActiveUidIfaces);
        mDevRecorder.writeToProtoLocked(proto, NetworkStatsServiceDumpProto.DEV_STATS);
        mXtRecorder.writeToProtoLocked(proto, NetworkStatsServiceDumpProto.XT_STATS);
        mUidRecorder.writeToProtoLocked(proto, NetworkStatsServiceDumpProto.UID_STATS);
        mUidTagRecorder.writeToProtoLocked(proto, NetworkStatsServiceDumpProto.UID_TAG_STATS);

        proto.flush();
    }

    private static void dumpInterfaces(ProtoOutputStream proto, long tag,
            ArrayMap<String, NetworkIdentitySet> ifaces) {
        for (int i = 0; i < ifaces.size(); i++) {
            final long start = proto.start(tag);

            proto.write(NetworkInterfaceProto.INTERFACE, ifaces.keyAt(i));
            ifaces.valueAt(i).writeToProto(proto, NetworkInterfaceProto.IDENTITIES);

            proto.end(start);
        }
    }

    /**
     * Return snapshot of current UID statistics, including any
     * {@link TrafficStats#UID_TETHERING}, video calling data usage, and {@link #mUidOperations}
     * values.
     *
     * @param ifaces A list of interfaces the stats should be restricted to, or
     *               {@link NetworkStats#INTERFACES_ALL}.
     */
    private NetworkStats getNetworkStatsUidDetail(String[] ifaces)
            throws RemoteException {

        // TODO: remove 464xlat adjustments from NetworkStatsFactory and apply all at once here.
        final NetworkStats uidSnapshot = mNetworkManager.getNetworkStatsUidDetail(UID_ALL,
                ifaces);

        // fold tethering stats and operations into uid snapshot
        final NetworkStats tetherSnapshot = getNetworkStatsTethering(STATS_PER_UID);
        tetherSnapshot.filter(UID_ALL, ifaces, TAG_ALL);
        NetworkStatsFactory.apply464xlatAdjustments(uidSnapshot, tetherSnapshot,
                mUseBpfTrafficStats);
        uidSnapshot.combineAllValues(tetherSnapshot);

        final TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);

        // fold video calling data usage stats into uid snapshot
        final NetworkStats vtStats = telephonyManager.getVtDataUsage(STATS_PER_UID);
        if (vtStats != null) {
            vtStats.filter(UID_ALL, ifaces, TAG_ALL);
            NetworkStatsFactory.apply464xlatAdjustments(uidSnapshot, vtStats,
                    mUseBpfTrafficStats);
            uidSnapshot.combineAllValues(vtStats);
        }

        uidSnapshot.combineAllValues(mUidOperations);

        return uidSnapshot;
    }

    /**
     * Return snapshot of current XT statistics with video calling data usage statistics.
     */
    private NetworkStats getNetworkStatsXt() throws RemoteException {
        final NetworkStats xtSnapshot = mNetworkManager.getNetworkStatsSummaryXt();

        final TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);

        // Merge video calling data usage into XT
        final NetworkStats vtSnapshot = telephonyManager.getVtDataUsage(STATS_PER_IFACE);
        if (vtSnapshot != null) {
            xtSnapshot.combineAllValues(vtSnapshot);
        }

        return xtSnapshot;
    }

    /**
     * Return snapshot of current tethering statistics. Will return empty
     * {@link NetworkStats} if any problems are encountered.
     */
    private NetworkStats getNetworkStatsTethering(int how) throws RemoteException {
        try {
            return mNetworkManager.getNetworkStatsTethering(how);
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
                    mService.performPoll(FLAG_PERSIST_ALL);
                    return true;
                }
                case MSG_PERFORM_POLL_REGISTER_ALERT: {
                    mService.performPoll(FLAG_PERSIST_NETWORK);
                    mService.registerGlobalAlert();
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    }

    private void assertSystemReady() {
        if (!mSystemReady) {
            throw new IllegalStateException("System not ready");
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
            Log.w(TAG, "Found non-monotonic values; saving to dropbox");

            // record error for debugging
            final StringBuilder builder = new StringBuilder();
            builder.append("found non-monotonic " + cookie + " values at left[" + leftIndex
                    + "] - right[" + rightIndex + "]\n");
            builder.append("left=").append(left).append('\n');
            builder.append("right=").append(right).append('\n');

            mContext.getSystemService(DropBoxManager.class).addText(TAG_NETSTATS_ERROR,
                    builder.toString());
        }

        @Override
        public void foundNonMonotonic(
                NetworkStats stats, int statsIndex, String cookie) {
            Log.w(TAG, "Found non-monotonic values; saving to dropbox");

            final StringBuilder builder = new StringBuilder();
            builder.append("Found non-monotonic " + cookie + " values at [" + statsIndex + "]\n");
            builder.append("stats=").append(stats).append('\n');

            mContext.getSystemService(DropBoxManager.class).addText(TAG_NETSTATS_ERROR,
                    builder.toString());
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
        public long getGlobalAlertBytes(long def) {
            return getGlobalLong(NETSTATS_GLOBAL_ALERT_BYTES, def);
        }
        @Override
        public boolean getSampleEnabled() {
            return getGlobalBoolean(NETSTATS_SAMPLE_ENABLED, true);
        }
        @Override
        public boolean getAugmentEnabled() {
            return getGlobalBoolean(NETSTATS_AUGMENT_ENABLED, true);
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

    private static int TYPE_RX_BYTES;
    private static int TYPE_RX_PACKETS;
    private static int TYPE_TX_BYTES;
    private static int TYPE_TX_PACKETS;
    private static int TYPE_TCP_RX_PACKETS;
    private static int TYPE_TCP_TX_PACKETS;

    private static native long nativeGetTotalStat(int type, boolean useBpfStats);
    private static native long nativeGetIfaceStat(String iface, int type, boolean useBpfStats);
    private static native long nativeGetUidStat(int uid, int type, boolean useBpfStats);
}
