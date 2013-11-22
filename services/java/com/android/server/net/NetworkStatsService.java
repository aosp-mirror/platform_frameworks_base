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
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.NetworkIdentity.COMBINE_SUBTYPE_ENABLED;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
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
import static android.provider.Settings.Global.NETSTATS_REPORT_XT_OVER_DEV;
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
import static android.telephony.PhoneStateListener.LISTEN_DATA_CONNECTION_STATE;
import static android.telephony.PhoneStateListener.LISTEN_NONE;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.android.internal.util.ArrayUtils.appendElement;
import static com.android.internal.util.ArrayUtils.contains;
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
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
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
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;
import android.util.MathUtils;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TrustedTime;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.connectivity.Tethering;
import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
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
     * Settings that can be changed externally.
     */
    public interface NetworkStatsSettings {
        public long getPollInterval();
        public long getTimeCacheMaxAge();
        public boolean getSampleEnabled();
        public boolean getReportXtOverDev();

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
    private HashMap<String, NetworkIdentitySet> mActiveIfaces = Maps.newHashMap();
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

    /** Cached {@link #mDevRecorder} stats. */
    private NetworkStatsCollection mDevStatsCached;
    /** Cached {@link #mXtRecorder} stats. */
    private NetworkStatsCollection mXtStatsCached;

    /** Current counter sets for each UID. */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mUidOperations = new NetworkStats(0L, 10);

    private final Handler mHandler;

    private boolean mSystemReady;
    private long mPersistThreshold = 2 * MB_IN_BYTES;
    private long mGlobalAlertBytes;

    public NetworkStatsService(
            Context context, INetworkManagementService networkManager, IAlarmManager alarmManager) {
        this(context, networkManager, alarmManager, NtpTrustedTime.getInstance(context),
                getDefaultSystemDir(), new DefaultNetworkStatsSettings(context));
    }

    private static File getDefaultSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    public NetworkStatsService(Context context, INetworkManagementService networkManager,
            IAlarmManager alarmManager, TrustedTime time, File systemDir,
            NetworkStatsSettings settings) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mTime = checkNotNull(time, "missing TrustedTime");
        mTeleManager = checkNotNull(TelephonyManager.getDefault(), "missing TelephonyManager");
        mSettings = checkNotNull(settings, "missing NetworkStatsSettings");
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper(), mHandlerCallback);

        mSystemDir = checkNotNull(systemDir);
        mBaseDir = new File(systemDir, "netstats");
        mBaseDir.mkdirs();
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
            mDevStatsCached = mDevRecorder.getOrLoadCompleteLocked();
            mXtStatsCached = mXtRecorder.getOrLoadCompleteLocked();

            // bootstrap initial stats to prevent double-counting later
            bootstrapStatsLocked();
        }

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

        // watch for networkType changes that aren't broadcast through
        // CONNECTIVITY_ACTION_IMMEDIATE above.
        if (!COMBINE_SUBTYPE_ENABLED) {
            mTeleManager.listen(mPhoneListener, LISTEN_DATA_CONNECTION_STATE);
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
        mContext.unregisterReceiver(mConnReceiver);
        mContext.unregisterReceiver(mTetherReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        if (!COMBINE_SUBTYPE_ENABLED) {
            mTeleManager.listen(mPhoneListener, LISTEN_NONE);
        }

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

        mDevStatsCached = null;
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
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        assertBandwidthControlEnabled();

        // return an IBinder which holds strong references to any loaded stats
        // for its lifetime; when caller closes only weak references remain.

        return new INetworkStatsSession.Stub() {
            private NetworkStatsCollection mUidComplete;
            private NetworkStatsCollection mUidTagComplete;

            private NetworkStatsCollection getUidComplete() {
                if (mUidComplete == null) {
                    synchronized (mStatsLock) {
                        mUidComplete = mUidRecorder.getOrLoadCompleteLocked();
                    }
                }
                return mUidComplete;
            }

            private NetworkStatsCollection getUidTagComplete() {
                if (mUidTagComplete == null) {
                    synchronized (mStatsLock) {
                        mUidTagComplete = mUidTagRecorder.getOrLoadCompleteLocked();
                    }
                }
                return mUidTagComplete;
            }

            @Override
            public NetworkStats getSummaryForNetwork(
                    NetworkTemplate template, long start, long end) {
                return internalGetSummaryForNetwork(template, start, end);
            }

            @Override
            public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
                return internalGetHistoryForNetwork(template, fields);
            }

            @Override
            public NetworkStats getSummaryForAllUid(
                    NetworkTemplate template, long start, long end, boolean includeTags) {
                final NetworkStats stats = getUidComplete().getSummary(template, start, end);
                if (includeTags) {
                    final NetworkStats tagStats = getUidTagComplete()
                            .getSummary(template, start, end);
                    stats.combineAllValues(tagStats);
                }
                return stats;
            }

            @Override
            public NetworkStatsHistory getHistoryForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields) {
                if (tag == TAG_NONE) {
                    return getUidComplete().getHistory(template, uid, set, tag, fields);
                } else {
                    return getUidTagComplete().getHistory(template, uid, set, tag, fields);
                }
            }

            @Override
            public void close() {
                mUidComplete = null;
                mUidTagComplete = null;
            }
        };
    }

    /**
     * Return network summary, splicing between {@link #mDevStatsCached}
     * and {@link #mXtStatsCached} when appropriate.
     */
    private NetworkStats internalGetSummaryForNetwork(
            NetworkTemplate template, long start, long end) {
        if (!mSettings.getReportXtOverDev()) {
            // shortcut when XT reporting disabled
            return mDevStatsCached.getSummary(template, start, end);
        }

        // splice stats between DEV and XT, switching over from DEV to XT at
        // first atomic bucket.
        final long firstAtomicBucket = mXtStatsCached.getFirstAtomicBucketMillis();
        final NetworkStats dev = mDevStatsCached.getSummary(
                template, Math.min(start, firstAtomicBucket), Math.min(end, firstAtomicBucket));
        final NetworkStats xt = mXtStatsCached.getSummary(
                template, Math.max(start, firstAtomicBucket), Math.max(end, firstAtomicBucket));

        xt.combineAllValues(dev);
        return xt;
    }

    /**
     * Return network history, splicing between {@link #mDevStatsCached}
     * and {@link #mXtStatsCached} when appropriate.
     */
    private NetworkStatsHistory internalGetHistoryForNetwork(NetworkTemplate template, int fields) {
        if (!mSettings.getReportXtOverDev()) {
            // shortcut when XT reporting disabled
            return mDevStatsCached.getHistory(template, UID_ALL, SET_ALL, TAG_NONE, fields);
        }

        // splice stats between DEV and XT, switching over from DEV to XT at
        // first atomic bucket.
        final long firstAtomicBucket = mXtStatsCached.getFirstAtomicBucketMillis();
        final NetworkStatsHistory dev = mDevStatsCached.getHistory(
                template, UID_ALL, SET_ALL, TAG_NONE, fields, Long.MIN_VALUE, firstAtomicBucket);
        final NetworkStatsHistory xt = mXtStatsCached.getHistory(
                template, UID_ALL, SET_ALL, TAG_NONE, fields, firstAtomicBucket, Long.MAX_VALUE);

        xt.recordEntireHistory(dev);
        return xt;
    }

    @Override
    public long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);
        assertBandwidthControlEnabled();
        return internalGetSummaryForNetwork(template, start, end).getTotalBytes();
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

                // remember any ifaces associated with mobile networks
                if (isNetworkTypeMobile(state.networkInfo.getType()) && iface != null) {
                    if (!contains(mMobileIfaces, iface)) {
                        mMobileIfaces = appendElement(String.class, mMobileIfaces, iface);
                    }
                }
            }
        }
    }

    /**
     * Bootstrap initial stats snapshot, usually during {@link #systemReady()}
     * so we have baseline values without double-counting.
     */
    private void bootstrapStatsLocked() {
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        try {
            // snapshot and record current counters; read UID stats first to
            // avoid overcounting dev stats.
            final NetworkStats uidSnapshot = getNetworkStatsUidDetail();
            final NetworkStats xtSnapshot = mNetworkManager.getNetworkStatsSummaryXt();
            final NetworkStats devSnapshot = mNetworkManager.getNetworkStatsSummaryDev();

            mDevRecorder.recordSnapshotLocked(devSnapshot, mActiveIfaces, currentTime);
            mXtRecorder.recordSnapshotLocked(xtSnapshot, mActiveIfaces, currentTime);
            mUidRecorder.recordSnapshotLocked(uidSnapshot, mActiveIfaces, currentTime);
            mUidTagRecorder.recordSnapshotLocked(uidSnapshot, mActiveIfaces, currentTime);

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
            // snapshot and record current counters; read UID stats first to
            // avoid overcounting dev stats.
            final NetworkStats uidSnapshot = getNetworkStatsUidDetail();
            final NetworkStats xtSnapshot = mNetworkManager.getNetworkStatsSummaryXt();
            final NetworkStats devSnapshot = mNetworkManager.getNetworkStatsSummaryDev();

            mDevRecorder.recordSnapshotLocked(devSnapshot, mActiveIfaces, currentTime);
            mXtRecorder.recordSnapshotLocked(xtSnapshot, mActiveIfaces, currentTime);
            mUidRecorder.recordSnapshotLocked(uidSnapshot, mActiveIfaces, currentTime);
            mUidTagRecorder.recordSnapshotLocked(uidSnapshot, mActiveIfaces, currentTime);

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
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final HashSet<String> argSet = new HashSet<String>();
        for (String arg : args) {
            argSet.add(arg);
        }

        // usage: dumpsys netstats --full --uid --tag --poll --checkin
        final boolean poll = argSet.contains("--poll") || argSet.contains("poll");
        final boolean checkin = argSet.contains("--checkin");
        final boolean fullHistory = argSet.contains("--full") || argSet.contains("full");
        final boolean includeUid = argSet.contains("--uid") || argSet.contains("detail");
        final boolean includeTag = argSet.contains("--tag") || argSet.contains("detail");

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        synchronized (mStatsLock) {
            if (poll) {
                performPollLocked(FLAG_PERSIST_ALL | FLAG_PERSIST_FORCE);
                pw.println("Forced poll");
                return;
            }

            if (checkin) {
                // list current stats files to verify rotation
                pw.println("Current files:");
                pw.increaseIndent();
                for (String file : mBaseDir.list()) {
                    pw.println(file);
                }
                pw.decreaseIndent();
                return;
            }

            pw.println("Active interfaces:");
            pw.increaseIndent();
            for (String iface : mActiveIfaces.keySet()) {
                final NetworkIdentitySet ident = mActiveIfaces.get(iface);
                pw.print("iface="); pw.print(iface);
                pw.print(" ident="); pw.println(ident.toString());
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

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
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
                case MSG_REGISTER_GLOBAL_ALERT: {
                    registerGlobalAlert();
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

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
        public boolean getReportXtOverDev() {
            return getGlobalBoolean(NETSTATS_REPORT_XT_OVER_DEV, true);
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
