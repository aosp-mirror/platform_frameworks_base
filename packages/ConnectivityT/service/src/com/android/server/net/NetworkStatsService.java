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

import static android.Manifest.permission.NETWORK_STATS_PROVIDER;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.app.usage.NetworkStatsManager.PREFIX_DEV;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.IFACE_VT;
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
import static android.net.TrafficStats.UID_TETHERING;
import static android.net.TrafficStats.UNSUPPORTED;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID_TAG;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_XT;
import static android.os.Trace.TRACE_TAG_NETWORK;
import static android.system.OsConstants.ENOENT;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.net.module.util.NetworkCapabilitiesUtils.getDisplayTransport;
import static com.android.net.module.util.NetworkStatsUtils.LIMIT_GLOBAL_ALERT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
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
import android.database.ContentObserver;
import android.net.DataUsageRequest;
import android.net.INetd;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkIdentity;
import android.net.NetworkIdentitySet;
import android.net.NetworkPolicyManager;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStateSnapshot;
import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicObserver;
import android.net.NetworkStatsAccess;
import android.net.NetworkStatsCollection;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TelephonyNetworkSpecifier;
import android.net.TetherStatsParcel;
import android.net.TetheringManager;
import android.net.TrafficStats;
import android.net.UnderlyingNetworkInfo;
import android.net.Uri;
import android.net.netstats.IUsageCallback;
import android.net.netstats.provider.INetworkStatsProvider;
import android.net.netstats.provider.INetworkStatsProviderCallback;
import android.net.netstats.provider.NetworkStatsProvider;
import android.os.Binder;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.NetworkInterfaceProto;
import android.service.NetworkStatsServiceDumpProto;
import android.system.ErrnoException;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionPlan;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FileRotator;
import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.BestClock;
import com.android.net.module.util.BinderUtils;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.LocationPermissionChecker;
import com.android.net.module.util.NetworkStatsUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.net.module.util.Struct.U32;
import com.android.net.module.util.Struct.U8;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public class NetworkStatsService extends INetworkStatsService.Stub {
    static {
        System.loadLibrary("service-connectivity");
    }

    static final String TAG = "NetworkStats";
    static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Perform polling and persist all (FLAG_PERSIST_ALL).
    private static final int MSG_PERFORM_POLL = 1;
    // Perform polling, persist network, and register the global alert again.
    private static final int MSG_PERFORM_POLL_REGISTER_ALERT = 2;
    private static final int MSG_NOTIFY_NETWORK_STATUS = 3;
    // A message for broadcasting ACTION_NETWORK_STATS_UPDATED in handler thread to prevent
    // deadlock.
    private static final int MSG_BROADCAST_NETWORK_STATS_UPDATED = 4;

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
    private static final int DEFAULT_PERFORM_POLL_DELAY_MS = 1000;

    private static final String TAG_NETSTATS_ERROR = "netstats_error";

    /**
     * EventLog tags used when logging into the event log. Note the values must be sync with
     * frameworks/base/services/core/java/com/android/server/EventLogTags.logtags to get correct
     * name translation.
      */
    private static final int LOG_TAG_NETSTATS_MOBILE_SAMPLE = 51100;
    private static final int LOG_TAG_NETSTATS_WIFI_SAMPLE = 51101;

    // TODO: Replace the hardcoded string and move it into ConnectivitySettingsManager.
    private static final String NETSTATS_COMBINE_SUBTYPE_ENABLED =
            "netstats_combine_subtype_enabled";

    // This is current path but may be changed soon.
    private static final String UID_COUNTERSET_MAP_PATH =
            "/sys/fs/bpf/map_netd_uid_counterset_map";
    private static final String COOKIE_TAG_MAP_PATH =
            "/sys/fs/bpf/map_netd_cookie_tag_map";
    private static final String APP_UID_STATS_MAP_PATH =
            "/sys/fs/bpf/map_netd_app_uid_stats_map";
    private static final String STATS_MAP_A_PATH =
            "/sys/fs/bpf/map_netd_stats_map_A";
    private static final String STATS_MAP_B_PATH =
            "/sys/fs/bpf/map_netd_stats_map_B";

    private final Context mContext;
    private final NetworkStatsFactory mStatsFactory;
    private final AlarmManager mAlarmManager;
    private final Clock mClock;
    private final NetworkStatsSettings mSettings;
    private final NetworkStatsObservers mStatsObservers;

    private final File mSystemDir;
    private final File mBaseDir;

    private final PowerManager.WakeLock mWakeLock;

    private final ContentObserver mContentObserver;
    private final ContentResolver mContentResolver;

    protected INetd mNetd;
    private final AlertObserver mAlertObserver = new AlertObserver();

    @VisibleForTesting
    public static final String ACTION_NETWORK_STATS_POLL =
            "com.android.server.action.NETWORK_STATS_POLL";
    public static final String ACTION_NETWORK_STATS_UPDATED =
            "com.android.server.action.NETWORK_STATS_UPDATED";

    private PendingIntent mPollIntent;

    /**
     * Settings that can be changed externally.
     */
    public interface NetworkStatsSettings {
        long getPollInterval();
        long getPollDelay();
        boolean getSampleEnabled();
        boolean getAugmentEnabled();
        /**
         * When enabled, all mobile data is reported under {@link NetworkTemplate#NETWORK_TYPE_ALL}.
         * When disabled, mobile data is broken down by a granular ratType representative of the
         * actual ratType. {@see android.app.usage.NetworkStatsManager#getCollapsedRatType}.
         * Enabling this decreases the level of detail but saves performance, disk space and
         * amount of data logged.
         */
        boolean getCombineSubtypeEnabled();

        class Config {
            public final long bucketDuration;
            public final long rotateAgeMillis;
            public final long deleteAgeMillis;

            public Config(long bucketDuration, long rotateAgeMillis, long deleteAgeMillis) {
                this.bucketDuration = bucketDuration;
                this.rotateAgeMillis = rotateAgeMillis;
                this.deleteAgeMillis = deleteAgeMillis;
            }
        }

        Config getDevConfig();
        Config getXtConfig();
        Config getUidConfig();
        Config getUidTagConfig();

        long getGlobalAlertBytes(long def);
        long getDevPersistBytes(long def);
        long getXtPersistBytes(long def);
        long getUidPersistBytes(long def);
        long getUidTagPersistBytes(long def);
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
    private volatile String[] mMobileIfaces = new String[0];

    /** Set of any ifaces associated with wifi networks since boot. */
    private volatile String[] mWifiIfaces = new String[0];

    /** Set of all ifaces currently used by traffic that does not explicitly specify a Network. */
    @GuardedBy("mStatsLock")
    private Network[] mDefaultNetworks = new Network[0];

    /** Last states of all networks sent from ConnectivityService. */
    @GuardedBy("mStatsLock")
    @Nullable
    private NetworkStateSnapshot[] mLastNetworkStateSnapshots = null;

    private final DropBoxNonMonotonicObserver mNonMonotonicObserver =
            new DropBoxNonMonotonicObserver();

    private static final int MAX_STATS_PROVIDER_POLL_WAIT_TIME_MS = 100;
    private final CopyOnWriteArrayList<NetworkStatsProviderCallbackImpl> mStatsProviderCbList =
            new CopyOnWriteArrayList<>();
    /** Semaphore used to wait for stats provider to respond to request stats update. */
    private final Semaphore mStatsProviderSem = new Semaphore(0, true);

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

    /**
     * Current counter sets for each UID.
     * TODO: maybe remove mActiveUidCounterSet and read UidCouneterSet value from mUidCounterSetMap
     * directly ? But if mActiveUidCounterSet would be accessed very frequently, maybe keep
     * mActiveUidCounterSet to avoid accessing kernel too frequently.
     */
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();
    private final IBpfMap<U32, U8> mUidCounterSetMap;
    private final IBpfMap<CookieTagMapKey, CookieTagMapValue> mCookieTagMap;
    private final IBpfMap<StatsMapKey, StatsMapValue> mStatsMapA;
    private final IBpfMap<StatsMapKey, StatsMapValue> mStatsMapB;
    private final IBpfMap<UidStatsMapKey, StatsMapValue> mAppUidStatsMap;

    /** Data layer operation counters for splicing into other structures. */
    private NetworkStats mUidOperations = new NetworkStats(0L, 10);

    @NonNull
    private final Handler mHandler;

    private volatile boolean mSystemReady;
    private long mPersistThreshold = 2 * MB_IN_BYTES;
    private long mGlobalAlertBytes;

    private static final long POLL_RATE_LIMIT_MS = 15_000;

    private long mLastStatsSessionPoll;

    /** Map from UID to number of opened sessions */
    @GuardedBy("mOpenSessionCallsPerUid")
    private final SparseIntArray mOpenSessionCallsPerUid = new SparseIntArray();

    private final static int DUMP_STATS_SESSION_COUNT = 20;

    @NonNull
    private final Dependencies mDeps;

    @NonNull
    private final NetworkStatsSubscriptionsMonitor mNetworkStatsSubscriptionsMonitor;

    @NonNull
    private final LocationPermissionChecker mLocationPermissionChecker;

    @NonNull
    private final BpfInterfaceMapUpdater mInterfaceMapUpdater;

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

    private final class NetworkStatsHandler extends Handler {
        NetworkStatsHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERFORM_POLL: {
                    performPoll(FLAG_PERSIST_ALL);
                    break;
                }
                case MSG_NOTIFY_NETWORK_STATUS: {
                    // If no cached states, ignore.
                    if (mLastNetworkStateSnapshots == null) break;
                    // TODO (b/181642673): Protect mDefaultNetworks from concurrent accessing.
                    handleNotifyNetworkStatus(
                            mDefaultNetworks, mLastNetworkStateSnapshots, mActiveIface);
                    break;
                }
                case MSG_PERFORM_POLL_REGISTER_ALERT: {
                    performPoll(FLAG_PERSIST_NETWORK);
                    registerGlobalAlert();
                    break;
                }
                case MSG_BROADCAST_NETWORK_STATS_UPDATED: {
                    final Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
                    updatedIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    mContext.sendBroadcastAsUser(updatedIntent, UserHandle.ALL,
                            READ_NETWORK_USAGE_HISTORY);
                    break;
                }
            }
        }
    }

    /** Creates a new NetworkStatsService */
    public static NetworkStatsService create(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        final INetd netd = INetd.Stub.asInterface(
                (IBinder) context.getSystemService(Context.NETD_SERVICE));
        final NetworkStatsService service = new NetworkStatsService(context,
                INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE)),
                alarmManager, wakeLock, getDefaultClock(),
                new DefaultNetworkStatsSettings(), new NetworkStatsFactory(context),
                new NetworkStatsObservers(), getDefaultSystemDir(), getDefaultBaseDir(),
                new Dependencies());

        return service;
    }

    // This must not be called outside of tests, even within the same package, as this constructor
    // does not register the local service. Use the create() helper above.
    @VisibleForTesting
    NetworkStatsService(Context context, INetd netd, AlarmManager alarmManager,
            PowerManager.WakeLock wakeLock, Clock clock, NetworkStatsSettings settings,
            NetworkStatsFactory factory, NetworkStatsObservers statsObservers, File systemDir,
            File baseDir, @NonNull Dependencies deps) {
        mContext = Objects.requireNonNull(context, "missing Context");
        mNetd = Objects.requireNonNull(netd, "missing Netd");
        mAlarmManager = Objects.requireNonNull(alarmManager, "missing AlarmManager");
        mClock = Objects.requireNonNull(clock, "missing Clock");
        mSettings = Objects.requireNonNull(settings, "missing NetworkStatsSettings");
        mWakeLock = Objects.requireNonNull(wakeLock, "missing WakeLock");
        mStatsFactory = Objects.requireNonNull(factory, "missing factory");
        mStatsObservers = Objects.requireNonNull(statsObservers, "missing NetworkStatsObservers");
        mSystemDir = Objects.requireNonNull(systemDir, "missing systemDir");
        mBaseDir = Objects.requireNonNull(baseDir, "missing baseDir");
        mDeps = Objects.requireNonNull(deps, "missing Dependencies");

        final HandlerThread handlerThread = mDeps.makeHandlerThread();
        handlerThread.start();
        mHandler = new NetworkStatsHandler(handlerThread.getLooper());
        mNetworkStatsSubscriptionsMonitor = deps.makeSubscriptionsMonitor(mContext,
                (command) -> mHandler.post(command) , this);
        mContentResolver = mContext.getContentResolver();
        mContentObserver = mDeps.makeContentObserver(mHandler, mSettings,
                mNetworkStatsSubscriptionsMonitor);
        mLocationPermissionChecker = mDeps.makeLocationPermissionChecker(mContext);
        mInterfaceMapUpdater = mDeps.makeBpfInterfaceMapUpdater(mContext, mHandler);
        mInterfaceMapUpdater.start();
        mUidCounterSetMap = mDeps.getUidCounterSetMap();
        mCookieTagMap = mDeps.getCookieTagMap();
        mStatsMapA = mDeps.getStatsMapA();
        mStatsMapB = mDeps.getStatsMapB();
        mAppUidStatsMap = mDeps.getAppUidStatsMap();
    }

    /**
     * Dependencies of NetworkStatsService, for injection in tests.
     */
    // TODO: Move more stuff into dependencies object.
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Create a HandlerThread to use in NetworkStatsService.
         */
        @NonNull
        public HandlerThread makeHandlerThread() {
            return new HandlerThread(TAG);
        }

        /**
         * Create a {@link NetworkStatsSubscriptionsMonitor}, can be used to monitor RAT change
         * event in NetworkStatsService.
         */
        @NonNull
        public NetworkStatsSubscriptionsMonitor makeSubscriptionsMonitor(@NonNull Context context,
                @NonNull Executor executor, @NonNull NetworkStatsService service) {
            // TODO: Update RatType passively in NSS, instead of querying into the monitor
            //  when notifyNetworkStatus.
            return new NetworkStatsSubscriptionsMonitor(context, executor,
                    (subscriberId, type) -> service.handleOnCollapsedRatTypeChanged());
        }

        /**
         * Create a ContentObserver instance which is used to observe settings changes,
         * and dispatch onChange events on handler thread.
         */
        public @NonNull ContentObserver makeContentObserver(@NonNull Handler handler,
                @NonNull NetworkStatsSettings settings,
                @NonNull NetworkStatsSubscriptionsMonitor monitor) {
            return new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, @NonNull Uri uri) {
                    if (!settings.getCombineSubtypeEnabled()) {
                        monitor.start();
                    } else {
                        monitor.stop();
                    }
                }
            };
        }

        /**
         * @see LocationPermissionChecker
         */
        public LocationPermissionChecker makeLocationPermissionChecker(final Context context) {
            return new LocationPermissionChecker(context);
        }

        /** Create BpfInterfaceMapUpdater to update bpf interface map. */
        @NonNull
        public BpfInterfaceMapUpdater makeBpfInterfaceMapUpdater(
                @NonNull Context ctx, @NonNull Handler handler) {
            return new BpfInterfaceMapUpdater(ctx, handler);
        }

        /** Get counter sets map for each UID. */
        public IBpfMap<U32, U8> getUidCounterSetMap() {
            try {
                return new BpfMap<U32, U8>(UID_COUNTERSET_MAP_PATH, BpfMap.BPF_F_RDWR,
                        U32.class, U8.class);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Cannot open uid counter set map: " + e);
                return null;
            }
        }

        /** Gets the cookie tag map */
        public IBpfMap<CookieTagMapKey, CookieTagMapValue> getCookieTagMap() {
            try {
                return new BpfMap<CookieTagMapKey, CookieTagMapValue>(COOKIE_TAG_MAP_PATH,
                        BpfMap.BPF_F_RDWR, CookieTagMapKey.class, CookieTagMapValue.class);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Cannot open cookie tag map: " + e);
                return null;
            }
        }

        /** Gets stats map A */
        public IBpfMap<StatsMapKey, StatsMapValue> getStatsMapA() {
            try {
                return new BpfMap<StatsMapKey, StatsMapValue>(STATS_MAP_A_PATH,
                        BpfMap.BPF_F_RDWR, StatsMapKey.class, StatsMapValue.class);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Cannot open stats map A: " + e);
                return null;
            }
        }

        /** Gets stats map B */
        public IBpfMap<StatsMapKey, StatsMapValue> getStatsMapB() {
            try {
                return new BpfMap<StatsMapKey, StatsMapValue>(STATS_MAP_B_PATH,
                        BpfMap.BPF_F_RDWR, StatsMapKey.class, StatsMapValue.class);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Cannot open stats map B: " + e);
                return null;
            }
        }

        /** Gets the uid stats map */
        public IBpfMap<UidStatsMapKey, StatsMapValue> getAppUidStatsMap() {
            try {
                return new BpfMap<UidStatsMapKey, StatsMapValue>(APP_UID_STATS_MAP_PATH,
                        BpfMap.BPF_F_RDWR, UidStatsMapKey.class, StatsMapValue.class);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Cannot open app uid stats map: " + e);
                return null;
            }
        }
    }

    /**
     * Observer that watches for {@link INetdUnsolicitedEventListener} alerts.
     */
    @VisibleForTesting
    public class AlertObserver extends BaseNetdUnsolicitedEventListener {
        @Override
        public void onQuotaLimitReached(@NonNull String alertName, @NonNull String ifName) {
            PermissionUtils.enforceNetworkStackPermission(mContext);

            if (LIMIT_GLOBAL_ALERT.equals(alertName)) {
                // kick off background poll to collect network stats unless there is already
                // such a call pending; UID stats are handled during normal polling interval.
                if (!mHandler.hasMessages(MSG_PERFORM_POLL_REGISTER_ALERT)) {
                    mHandler.sendEmptyMessageDelayed(MSG_PERFORM_POLL_REGISTER_ALERT,
                            mSettings.getPollDelay());
                }
            }
        }
    }

    public void systemReady() {
        synchronized (mStatsLock) {
            mSystemReady = true;

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
        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.registerTetheringEventCallback(
                (command) -> mHandler.post(command), mTetherListener);

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
            mNetd.registerUnsolicitedEventListener(mAlertObserver);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.wtf(TAG, "Error registering event listener :", e);
        }

        //  schedule periodic pall alarm based on {@link NetworkStatsSettings#getPollInterval()}.
        final PendingIntent pollIntent =
                PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL),
                        PendingIntent.FLAG_IMMUTABLE);

        final long currentRealtime = SystemClock.elapsedRealtime();
        mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, currentRealtime,
                mSettings.getPollInterval(), pollIntent);

        mContentResolver.registerContentObserver(Settings.Global
                .getUriFor(NETSTATS_COMBINE_SUBTYPE_ENABLED),
                        false /* notifyForDescendants */, mContentObserver);

        // Post a runnable on handler thread to call onChange(). It's for getting current value of
        // NETSTATS_COMBINE_SUBTYPE_ENABLED to decide start or stop monitoring RAT type changes.
        mHandler.post(() -> mContentObserver.onChange(false, Settings.Global
                .getUriFor(NETSTATS_COMBINE_SUBTYPE_ENABLED)));

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
        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.unregisterTetheringEventCallback(mTetherListener);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mRemovedReceiver);
        mContext.unregisterReceiver(mUserReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        if (!mSettings.getCombineSubtypeEnabled()) {
            mNetworkStatsSubscriptionsMonitor.stop();
        }

        mContentResolver.unregisterContentObserver(mContentObserver);

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
     * Register for a global alert that is delivered through {@link AlertObserver}
     * or {@link NetworkStatsProviderCallback#onAlertReached()} once a threshold amount of data has
     * been transferred.
     */
    private void registerGlobalAlert() {
        try {
            mNetd.bandwidthSetGlobalAlert(mGlobalAlertBytes);
        } catch (IllegalStateException e) {
            Log.w(TAG, "problem registering for global alert: " + e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
        invokeForAllStatsProviderCallbacks((cb) -> cb.mProvider.onSetAlert(mGlobalAlertBytes));
    }

    @Override
    public INetworkStatsSession openSession() {
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

    private int restrictFlagsForCaller(int flags) {
        // All non-privileged callers are not allowed to turn off POLL_ON_OPEN.
        final boolean isPrivileged = PermissionUtils.checkAnyPermissionOf(mContext,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                android.Manifest.permission.NETWORK_STACK);
        if (!isPrivileged) {
            flags |= NetworkStatsManager.FLAG_POLL_ON_OPEN;
        }
        // Non-system uids are rate limited for POLL_ON_OPEN.
        final int callingUid = Binder.getCallingUid();
        flags = isRateLimitedForPoll(callingUid)
                ? flags & (~NetworkStatsManager.FLAG_POLL_ON_OPEN)
                : flags;
        return flags;
    }

    private INetworkStatsSession openSessionInternal(final int flags, final String callingPackage) {
        final int restrictedFlags = restrictFlagsForCaller(flags);
        if ((restrictedFlags & (NetworkStatsManager.FLAG_POLL_ON_OPEN
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
            private final int mCallingUid = Binder.getCallingUid();
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
                enforceTemplatePermissions(template, callingPackage);
                return internalGetSummaryForNetwork(template, restrictedFlags, start, end,
                        mAccessLevel, mCallingUid);
            }

            @Override
            public NetworkStats getSummaryForNetwork(
                    NetworkTemplate template, long start, long end) {
                enforceTemplatePermissions(template, callingPackage);
                return internalGetSummaryForNetwork(template, restrictedFlags, start, end,
                        mAccessLevel, mCallingUid);
            }

            // TODO: Remove this after all callers are removed.
            @Override
            public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
                enforceTemplatePermissions(template, callingPackage);
                return internalGetHistoryForNetwork(template, restrictedFlags, fields,
                        mAccessLevel, mCallingUid, Long.MIN_VALUE, Long.MAX_VALUE);
            }

            @Override
            public NetworkStatsHistory getHistoryIntervalForNetwork(NetworkTemplate template,
                    int fields, long start, long end) {
                enforceTemplatePermissions(template, callingPackage);
                // TODO(b/200768422): Redact returned history if the template is location
                //  sensitive but the caller is not privileged.
                return internalGetHistoryForNetwork(template, restrictedFlags, fields,
                        mAccessLevel, mCallingUid, start, end);
            }

            @Override
            public NetworkStats getSummaryForAllUid(
                    NetworkTemplate template, long start, long end, boolean includeTags) {
                enforceTemplatePermissions(template, callingPackage);
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
                    throw e;
                }
            }

            @Override
            public NetworkStats getTaggedSummaryForAllUid(
                    NetworkTemplate template, long start, long end) {
                enforceTemplatePermissions(template, callingPackage);
                try {
                    final NetworkStats tagStats = getUidTagComplete()
                            .getSummary(template, start, end, mAccessLevel, mCallingUid);
                    return tagStats;
                } catch (NullPointerException e) {
                    throw e;
                }
            }

            @Override
            public NetworkStatsHistory getHistoryForUid(
                    NetworkTemplate template, int uid, int set, int tag, int fields) {
                enforceTemplatePermissions(template, callingPackage);
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
                enforceTemplatePermissions(template, callingPackage);
                // TODO(b/200768422): Redact returned history if the template is location
                //  sensitive but the caller is not privileged.
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

    private void enforceTemplatePermissions(@NonNull NetworkTemplate template,
            @NonNull String callingPackage) {
        // For a template with wifi network keys, it is possible for a malicious
        // client to track the user locations via querying data usage. Thus, enforce
        // fine location permission check.
        if (!template.getWifiNetworkKeys().isEmpty()) {
            final boolean canAccessFineLocation = mLocationPermissionChecker
                    .checkCallersLocationPermission(callingPackage,
                    null /* featureId */,
                            Binder.getCallingUid(),
                            false /* coarseForTargetSdkLessThanQ */,
                            null /* message */);
            if (!canAccessFineLocation) {
                throw new SecurityException("Access fine location is required when querying"
                        + " with wifi network keys, make sure the app has the necessary"
                        + "permissions and the location toggle is on.");
            }
        }
    }

    private @NetworkStatsAccess.Level int checkAccessLevel(String callingPackage) {
        return NetworkStatsAccess.checkAccessLevel(
                mContext, Binder.getCallingPid(), Binder.getCallingUid(), callingPackage);
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
            if (LOGD) Log.d(TAG, "Resolving plan for " + template);
            final long token = Binder.clearCallingIdentity();
            try {
                plan = mContext.getSystemService(NetworkPolicyManager.class)
                        .getSubscriptionPlan(template);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (LOGD) Log.d(TAG, "Resolved to plan " + plan);
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
                accessLevel, callingUid, start, end);

        final long now = System.currentTimeMillis();
        final NetworkStatsHistory.Entry entry = history.getValues(start, end, now, null);

        final NetworkStats stats = new NetworkStats(end - start, 1);
        stats.insertEntry(new NetworkStats.Entry(IFACE_ALL, UID_ALL, SET_ALL, TAG_NONE,
                METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL, entry.rxBytes, entry.rxPackets,
                entry.txBytes, entry.txPackets, entry.operations));
        return stats;
    }

    /**
     * Return network history, splicing between DEV and XT stats when
     * appropriate.
     */
    private NetworkStatsHistory internalGetHistoryForNetwork(NetworkTemplate template,
            int flags, int fields, @NetworkStatsAccess.Level int accessLevel, int callingUid,
            long start, long end) {
        // We've been using pure XT stats long enough that we no longer need to
        // splice DEV and XT together.
        final SubscriptionPlan augmentPlan = resolveSubscriptionPlan(template, flags);
        synchronized (mStatsLock) {
            return mXtStatsCached.getHistory(template, augmentPlan,
                    UID_ALL, SET_ALL, TAG_NONE, fields, start, end, accessLevel, callingUid);
        }
    }

    private long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        assertSystemReady();

        return internalGetSummaryForNetwork(template,
                NetworkStatsManager.FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN, start, end,
                NetworkStatsAccess.Level.DEVICE, Binder.getCallingUid()).getTotalBytes();
    }

    private NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
        assertSystemReady();

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
            Log.w(TAG, "Snapshots only available for calling UID");
            return new NetworkStats(SystemClock.elapsedRealtime(), 0);
        }

        // TODO: switch to data layer stats once kernel exports
        // for now, read network layer stats and flatten across all ifaces.
        // This function is used to query NeworkStats for calle's uid. The only caller method
        // TrafficStats#getDataLayerSnapshotForUid alrady claim no special permission to query
        // its own NetworkStats.
        final long ident = Binder.clearCallingIdentity();
        final NetworkStats networkLayer;
        try {
            networkLayer = readNetworkStatsUidDetail(uid, INTERFACES_ALL, TAG_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
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
    public NetworkStats getUidStatsForTransport(int transport) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        try {
            final String[] relevantIfaces =
                    transport == TRANSPORT_WIFI ? mWifiIfaces : mMobileIfaces;
            // TODO(b/215633405) : mMobileIfaces and mWifiIfaces already contain the stacked
            // interfaces, so this is not useful, remove it.
            final String[] ifacesToQuery =
                    mStatsFactory.augmentWithStackedInterfaces(relevantIfaces);
            return getNetworkStatsUidDetail(ifacesToQuery);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error compiling UID stats", e);
            return new NetworkStats(0L, 0);
        }
    }

    @Override
    public String[] getMobileIfaces() {
        // TODO (b/192758557): Remove debug log.
        if (CollectionUtils.contains(mMobileIfaces, null)) {
            throw new NullPointerException(
                    "null element in mMobileIfaces: " + Arrays.toString(mMobileIfaces));
        }
        return mMobileIfaces.clone();
    }

    @Override
    public void incrementOperationCount(int uid, int tag, int operationCount) {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, TAG);
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

    private void setKernelCounterSet(int uid, int set) {
        if (mUidCounterSetMap == null) {
            Log.wtf(TAG, "Fail to set UidCounterSet: Null bpf map");
            return;
        }

        if (set == SET_DEFAULT) {
            try {
                mUidCounterSetMap.deleteEntry(new U32(uid));
            } catch (ErrnoException e) {
                Log.w(TAG, "UidCounterSetMap.deleteEntry(" + uid + ") failed with errno: " + e);
            }
            return;
        }

        try {
            mUidCounterSetMap.updateEntry(new U32(uid), new U8((short) set));
        } catch (ErrnoException e) {
            Log.w(TAG, "UidCounterSetMap.updateEntry(" + uid + ", " + set
                    + ") failed with errno: " + e);
        }
    }

    @VisibleForTesting
    public void noteUidForeground(int uid, boolean uidForeground) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        synchronized (mStatsLock) {
            final int set = uidForeground ? SET_FOREGROUND : SET_DEFAULT;
            final int oldSet = mActiveUidCounterSet.get(uid, SET_DEFAULT);
            if (oldSet != set) {
                mActiveUidCounterSet.put(uid, set);
                setKernelCounterSet(uid, set);
            }
        }
    }

    /**
     * Notify {@code NetworkStatsService} about network status changed.
     */
    public void notifyNetworkStatus(
            @NonNull Network[] defaultNetworks,
            @NonNull NetworkStateSnapshot[] networkStates,
            @Nullable String activeIface,
            @NonNull UnderlyingNetworkInfo[] underlyingNetworkInfos) {
        PermissionUtils.enforceNetworkStackPermission(mContext);

        final long token = Binder.clearCallingIdentity();
        try {
            handleNotifyNetworkStatus(defaultNetworks, networkStates, activeIface);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Update the VPN underlying interfaces only after the poll is made and tun data has been
        // migrated. Otherwise the migration would use the new interfaces instead of the ones that
        // were current when the polled data was transferred.
        mStatsFactory.updateUnderlyingNetworkInfos(underlyingNetworkInfos);
    }

    @Override
    public void forceUpdate() {
        PermissionUtils.enforceNetworkStackPermission(mContext);

        final long token = Binder.clearCallingIdentity();
        try {
            performPoll(FLAG_PERSIST_ALL);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Advise persistence threshold; may be overridden internally. */
    public void advisePersistThreshold(long thresholdBytes) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        // clamp threshold into safe range
        mPersistThreshold = NetworkStatsUtils.constrain(thresholdBytes,
                128 * KB_IN_BYTES, 2 * MB_IN_BYTES);
        if (LOGV) {
            Log.v(TAG, "advisePersistThreshold() given " + thresholdBytes + ", clamped to "
                    + mPersistThreshold);
        }

        final long oldGlobalAlertBytes = mGlobalAlertBytes;

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

        if (oldGlobalAlertBytes != mGlobalAlertBytes) {
            registerGlobalAlert();
        }
    }

    @Override
    public DataUsageRequest registerUsageCallback(@NonNull String callingPackage,
                @NonNull DataUsageRequest request, @NonNull IUsageCallback callback) {
        Objects.requireNonNull(callingPackage, "calling package is null");
        Objects.requireNonNull(request, "DataUsageRequest is null");
        Objects.requireNonNull(request.template, "NetworkTemplate is null");
        Objects.requireNonNull(callback, "callback is null");

        int callingUid = Binder.getCallingUid();
        @NetworkStatsAccess.Level int accessLevel = checkAccessLevel(callingPackage);
        DataUsageRequest normalizedRequest;
        final long token = Binder.clearCallingIdentity();
        try {
            normalizedRequest = mStatsObservers.register(mContext,
                    request, callback, callingUid, accessLevel);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Create baseline stats
        mHandler.sendMessage(mHandler.obtainMessage(MSG_PERFORM_POLL));

        return normalizedRequest;
   }

    @Override
    public void unregisterUsageRequest(DataUsageRequest request) {
        Objects.requireNonNull(request, "DataUsageRequest is null");

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
        final int callingUid = Binder.getCallingUid();
        if (callingUid != android.os.Process.SYSTEM_UID && callingUid != uid) {
            return UNSUPPORTED;
        }
        return nativeGetUidStat(uid, type);
    }

    @Override
    public long getIfaceStats(@NonNull String iface, int type) {
        Objects.requireNonNull(iface);
        long nativeIfaceStats = nativeGetIfaceStat(iface, type);
        if (nativeIfaceStats == -1) {
            return nativeIfaceStats;
        } else {
            // When tethering offload is in use, nativeIfaceStats does not contain usage from
            // offload, add it back here. Note that the included statistics might be stale
            // since polling newest stats from hardware might impact system health and not
            // suitable for TrafficStats API use cases.
            return nativeIfaceStats + getProviderIfaceStats(iface, type);
        }
    }

    @Override
    public long getTotalStats(int type) {
        long nativeTotalStats = nativeGetTotalStat(type);
        if (nativeTotalStats == -1) {
            return nativeTotalStats;
        } else {
            // Refer to comment in getIfaceStats
            return nativeTotalStats + getProviderIfaceStats(IFACE_ALL, type);
        }
    }

    private long getProviderIfaceStats(@Nullable String iface, int type) {
        final NetworkStats providerSnapshot = getNetworkStatsFromProviders(STATS_PER_IFACE);
        final HashSet<String> limitIfaces;
        if (iface == IFACE_ALL) {
            limitIfaces = null;
        } else {
            limitIfaces = new HashSet<>();
            limitIfaces.add(iface);
        }
        final NetworkStats.Entry entry = providerSnapshot.getTotal(null, limitIfaces);
        switch (type) {
            case TrafficStats.TYPE_RX_BYTES:
                return entry.rxBytes;
            case TrafficStats.TYPE_RX_PACKETS:
                return entry.rxPackets;
            case TrafficStats.TYPE_TX_BYTES:
                return entry.txBytes;
            case TrafficStats.TYPE_TX_PACKETS:
                return entry.txPackets;
            default:
                return 0;
        }
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
     * Listener that watches for {@link TetheringManager} to claim interface pairs.
     */
    private final TetheringManager.TetheringEventCallback mTetherListener =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onUpstreamChanged(@Nullable Network network) {
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

            final UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
            if (userHandle == null) return;

            synchronized (mStatsLock) {
                mWakeLock.acquire();
                try {
                    removeUserLocked(userHandle);
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
     * Handle collapsed RAT type changed event.
     */
    @VisibleForTesting
    public void handleOnCollapsedRatTypeChanged() {
        // Protect service from frequently updating. Remove pending messages if any.
        mHandler.removeMessages(MSG_NOTIFY_NETWORK_STATUS);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_NOTIFY_NETWORK_STATUS), mSettings.getPollDelay());
    }

    private void handleNotifyNetworkStatus(
            Network[] defaultNetworks,
            NetworkStateSnapshot[] snapshots,
            String activeIface) {
        synchronized (mStatsLock) {
            mWakeLock.acquire();
            try {
                mActiveIface = activeIface;
                handleNotifyNetworkStatusLocked(defaultNetworks, snapshots);
            } finally {
                mWakeLock.release();
            }
        }
    }

    /**
     * Inspect all current {@link NetworkStateSnapshot}s to derive mapping from {@code iface} to
     * {@link NetworkStatsHistory}. When multiple networks are active on a single {@code iface},
     * they are combined under a single {@link NetworkIdentitySet}.
     */
    @GuardedBy("mStatsLock")
    private void handleNotifyNetworkStatusLocked(@NonNull Network[] defaultNetworks,
            @NonNull NetworkStateSnapshot[] snapshots) {
        if (!mSystemReady) return;
        if (LOGV) Log.v(TAG, "handleNotifyNetworkStatusLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.

        // poll, but only persist network stats to keep codepath fast. UID stats
        // will be persisted during next alarm poll event.
        performPollLocked(FLAG_PERSIST_NETWORK);

        // Rebuild active interfaces based on connected networks
        mActiveIfaces.clear();
        mActiveUidIfaces.clear();
        // Update the list of default networks.
        mDefaultNetworks = defaultNetworks;

        mLastNetworkStateSnapshots = snapshots;

        final boolean combineSubtypeEnabled = mSettings.getCombineSubtypeEnabled();
        final ArraySet<String> mobileIfaces = new ArraySet<>();
        final ArraySet<String> wifiIfaces = new ArraySet<>();
        for (NetworkStateSnapshot snapshot : snapshots) {
            final int displayTransport =
                    getDisplayTransport(snapshot.getNetworkCapabilities().getTransportTypes());
            final boolean isMobile = (NetworkCapabilities.TRANSPORT_CELLULAR == displayTransport);
            final boolean isWifi = (NetworkCapabilities.TRANSPORT_WIFI == displayTransport);
            final boolean isDefault = CollectionUtils.contains(
                    mDefaultNetworks, snapshot.getNetwork());
            final int ratType = combineSubtypeEnabled ? NetworkTemplate.NETWORK_TYPE_ALL
                    : getRatTypeForStateSnapshot(snapshot);
            final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, snapshot,
                    isDefault, ratType);

            // Traffic occurring on the base interface is always counted for
            // both total usage and UID details.
            final String baseIface = snapshot.getLinkProperties().getInterfaceName();
            if (baseIface != null) {
                findOrCreateNetworkIdentitySet(mActiveIfaces, baseIface).add(ident);
                findOrCreateNetworkIdentitySet(mActiveUidIfaces, baseIface).add(ident);

                // Build a separate virtual interface for VT (Video Telephony) data usage.
                // Only do this when IMS is not metered, but VT is metered.
                // If IMS is metered, then the IMS network usage has already included VT usage.
                // VT is considered always metered in framework's layer. If VT is not metered
                // per carrier's policy, modem will report 0 usage for VT calls.
                if (snapshot.getNetworkCapabilities().hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_IMS) && !ident.isMetered()) {

                    // Copy the identify from IMS one but mark it as metered.
                    NetworkIdentity vtIdent = new NetworkIdentity.Builder()
                            .setType(ident.getType())
                            .setRatType(ident.getRatType())
                            .setSubscriberId(ident.getSubscriberId())
                            .setWifiNetworkKey(ident.getWifiNetworkKey())
                            .setRoaming(ident.isRoaming()).setMetered(true)
                            .setDefaultNetwork(true)
                            .setOemManaged(ident.getOemManaged())
                            .setSubId(ident.getSubId()).build();
                    final String ifaceVt = IFACE_VT + getSubIdForMobile(snapshot);
                    findOrCreateNetworkIdentitySet(mActiveIfaces, ifaceVt).add(vtIdent);
                    findOrCreateNetworkIdentitySet(mActiveUidIfaces, ifaceVt).add(vtIdent);
                }

                if (isMobile) {
                    mobileIfaces.add(baseIface);
                }
                if (isWifi) {
                    wifiIfaces.add(baseIface);
                }
            }

            // Traffic occurring on stacked interfaces is usually clatd.
            //
            // UID stats are always counted on the stacked interface and never on the base
            // interface, because the packets on the base interface do not actually match
            // application sockets (they're not IPv4) and thus the app uid is not known.
            // For receive this is obvious: packets must be translated from IPv6 to IPv4
            // before the application socket can be found.
            // For transmit: either they go through the clat daemon which by virtue of going
            // through userspace strips the original socket association during the IPv4 to
            // IPv6 translation process, or they are offloaded by eBPF, which doesn't:
            // However, on an ebpf device the accounting is done in cgroup ebpf hooks,
            // which don't trigger again post ebpf translation.
            // (as such stats accounted to the clat uid are ignored)
            //
            // Interface stats are more complicated.
            //
            // eBPF offloaded 464xlat'ed packets never hit base interface ip6tables, and thus
            // *all* statistics are collected by iptables on the stacked v4-* interface.
            //
            // Additionally for ingress all packets bound for the clat IPv6 address are dropped
            // in ip6tables raw prerouting and thus even non-offloaded packets are only
            // accounted for on the stacked interface.
            //
            // For egress, packets subject to eBPF offload never appear on the base interface
            // and only appear on the stacked interface. Thus to ensure packets increment
            // interface stats, we must collate data from stacked interfaces. For xt_qtaguid
            // (or non eBPF offloaded) TX they would appear on both, however egress interface
            // accounting is explicitly bypassed for traffic from the clat uid.
            //
            // TODO: This code might be combined to above code.
            for (String iface : snapshot.getLinkProperties().getAllInterfaceNames()) {
                // baseIface has been handled, so ignore it.
                if (TextUtils.equals(baseIface, iface)) continue;
                if (iface != null) {
                    findOrCreateNetworkIdentitySet(mActiveIfaces, iface).add(ident);
                    findOrCreateNetworkIdentitySet(mActiveUidIfaces, iface).add(ident);
                    if (isMobile) {
                        mobileIfaces.add(iface);
                    }
                    if (isWifi) {
                        wifiIfaces.add(iface);
                    }

                    mStatsFactory.noteStackedIface(iface, baseIface);
                }
            }
        }

        mMobileIfaces = mobileIfaces.toArray(new String[0]);
        mWifiIfaces = wifiIfaces.toArray(new String[0]);
        // TODO (b/192758557): Remove debug log.
        if (CollectionUtils.contains(mMobileIfaces, null)) {
            throw new NullPointerException(
                    "null element in mMobileIfaces: " + Arrays.toString(mMobileIfaces));
        }
        if (CollectionUtils.contains(mWifiIfaces, null)) {
            throw new NullPointerException(
                    "null element in mWifiIfaces: " + Arrays.toString(mWifiIfaces));
        }
    }

    private static int getSubIdForMobile(@NonNull NetworkStateSnapshot state) {
        if (!state.getNetworkCapabilities().hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            throw new IllegalArgumentException("Mobile state need capability TRANSPORT_CELLULAR");
        }

        final NetworkSpecifier spec = state.getNetworkCapabilities().getNetworkSpecifier();
        if (spec instanceof TelephonyNetworkSpecifier) {
             return ((TelephonyNetworkSpecifier) spec).getSubscriptionId();
        } else {
            Log.wtf(TAG, "getSubIdForState invalid NetworkSpecifier");
            return INVALID_SUBSCRIPTION_ID;
        }
    }

    /**
     * For networks with {@code TRANSPORT_CELLULAR}, get ratType that was obtained through
     * {@link PhoneStateListener}. Otherwise, return 0 given that other networks with different
     * transport types do not actually fill this value.
     */
    private int getRatTypeForStateSnapshot(@NonNull NetworkStateSnapshot state) {
        if (!state.getNetworkCapabilities().hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return 0;
        }

        return mNetworkStatsSubscriptionsMonitor.getRatTypeForSubscriberId(state.getSubscriberId());
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
        final NetworkStats xtSnapshot = readNetworkStatsSummaryXt();
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotDev");
        final NetworkStats devSnapshot = readNetworkStatsSummaryDev();
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // Snapshot for dev/xt stats from all custom stats providers. Counts per-interface data
        // from stats providers that isn't already counted by dev and XT stats.
        Trace.traceBegin(TRACE_TAG_NETWORK, "snapshotStatsProvider");
        final NetworkStats providersnapshot = getNetworkStatsFromProviders(STATS_PER_IFACE);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        xtSnapshot.combineAllValues(providersnapshot);
        devSnapshot.combineAllValues(providersnapshot);

        // For xt/dev, we pass a null VPN array because usage is aggregated by UID, so VPN traffic
        // can't be reattributed to responsible apps.
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordDev");
        mDevRecorder.recordSnapshotLocked(devSnapshot, mActiveIfaces, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordXt");
        mXtRecorder.recordSnapshotLocked(xtSnapshot, mActiveIfaces, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // For per-UID stats, pass the VPN info so VPN traffic is reattributed to responsible apps.
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordUid");
        mUidRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);
        Trace.traceBegin(TRACE_TAG_NETWORK, "recordUidTag");
        mUidTagRecorder.recordSnapshotLocked(uidSnapshot, mActiveUidIfaces, currentTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);

        // We need to make copies of member fields that are sent to the observer to avoid
        // a race condition between the service handler thread and the observer's
        mStatsObservers.updateStats(xtSnapshot, uidSnapshot, new ArrayMap<>(mActiveIfaces),
                new ArrayMap<>(mActiveUidIfaces), currentTime);
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
            Log.w(TAG, "problem reading network stats: " + e);
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
        if (LOGV) Log.v(TAG, "performPollLocked(flags=0x" + Integer.toHexString(flags) + ")");
        Trace.traceBegin(TRACE_TAG_NETWORK, "performPollLocked");

        final boolean persistNetwork = (flags & FLAG_PERSIST_NETWORK) != 0;
        final boolean persistUid = (flags & FLAG_PERSIST_UID) != 0;
        final boolean persistForce = (flags & FLAG_PERSIST_FORCE) != 0;

        performPollFromProvidersLocked();

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
        mHandler.sendMessage(mHandler.obtainMessage(MSG_BROADCAST_NETWORK_STATS_UPDATED));

        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    @GuardedBy("mStatsLock")
    private void performPollFromProvidersLocked() {
        // Request asynchronous stats update from all providers for next poll. And wait a bit of
        // time to allow providers report-in given that normally binder call should be fast. Note
        // that size of list might be changed because addition/removing at the same time. For
        // addition, the stats of the missed provider can only be collected in next poll;
        // for removal, wait might take up to MAX_STATS_PROVIDER_POLL_WAIT_TIME_MS
        // once that happened.
        // TODO: request with a valid token.
        Trace.traceBegin(TRACE_TAG_NETWORK, "provider.requestStatsUpdate");
        final int registeredCallbackCount = mStatsProviderCbList.size();
        mStatsProviderSem.drainPermits();
        invokeForAllStatsProviderCallbacks(
                (cb) -> cb.mProvider.onRequestStatsUpdate(0 /* unused */));
        try {
            mStatsProviderSem.tryAcquire(registeredCallbackCount,
                    MAX_STATS_PROVIDER_POLL_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Strictly speaking it's possible a provider happened to deliver between the timeout
            // and the log, and that doesn't matter too much as this is just a debug log.
            Log.d(TAG, "requestStatsUpdate - providers responded "
                    + mStatsProviderSem.availablePermits()
                    + "/" + registeredCallbackCount + " : " + e);
        }
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

        EventLog.writeEvent(LOG_TAG_NETSTATS_MOBILE_SAMPLE,
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                currentTime);

        // collect wifi sample
        template = buildTemplateWifiWildcard();
        devTotal = mDevRecorder.getTotalSinceBootLocked(template);
        xtTotal = mXtRecorder.getTotalSinceBootLocked(template);
        uidTotal = mUidRecorder.getTotalSinceBootLocked(template);

        EventLog.writeEvent(LOG_TAG_NETSTATS_WIFI_SAMPLE,
                devTotal.rxBytes, devTotal.rxPackets, devTotal.txBytes, devTotal.txPackets,
                xtTotal.rxBytes, xtTotal.rxPackets, xtTotal.txBytes, xtTotal.txPackets,
                uidTotal.rxBytes, uidTotal.rxPackets, uidTotal.txBytes, uidTotal.txPackets,
                currentTime);
    }

    // deleteKernelTagData can ignore ENOENT; otherwise we should log an error
    private void logErrorIfNotErrNoent(final ErrnoException e, final String msg) {
        if (e.errno != ENOENT) Log.e(TAG, msg, e);
    }

    private <K extends StatsMapKey, V extends StatsMapValue> void deleteStatsMapTagData(
            IBpfMap<K, V> statsMap, int uid) {
        try {
            statsMap.forEach((key, value) -> {
                if (key.uid == uid) {
                    try {
                        statsMap.deleteEntry(key);
                    } catch (ErrnoException e) {
                        logErrorIfNotErrNoent(e, "Failed to delete data(uid = " + key.uid + ")");
                    }
                }
            });
        } catch (ErrnoException e) {
            Log.e(TAG, "FAILED to delete tag data from stats map", e);
        }
    }

    /**
     * Deletes uid tag data from CookieTagMap, StatsMapA, StatsMapB, and UidStatsMap
     * @param uid
     */
    private void deleteKernelTagData(int uid) {
        try {
            mCookieTagMap.forEach((key, value) -> {
                // If SkDestroyListener deletes the socket tag while this code is running,
                // forEach will either restart iteration from the beginning or return null,
                // depending on when the deletion happens.
                // If it returns null, continue iteration to delete the data and in fact it would
                // just iterate from first key because BpfMap#getNextKey would return first key
                // if the current key is not exist.
                if (value != null && value.uid == uid) {
                    try {
                        mCookieTagMap.deleteEntry(key);
                    } catch (ErrnoException e) {
                        logErrorIfNotErrNoent(e, "Failed to delete data(cookie = " + key + ")");
                    }
                }
            });
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to delete tag data from cookie tag map", e);
        }

        deleteStatsMapTagData(mStatsMapA, uid);
        deleteStatsMapTagData(mStatsMapB, uid);

        try {
            mUidCounterSetMap.deleteEntry(new U32(uid));
        } catch (ErrnoException e) {
            logErrorIfNotErrNoent(e, "Failed to delete tag data from uid counter set map");
        }

        try {
            mAppUidStatsMap.deleteEntry(new UidStatsMapKey(uid));
        } catch (ErrnoException e) {
            logErrorIfNotErrNoent(e, "Failed to delete tag data from app uid stats map");
        }
    }

    /**
     * Clean up {@link #mUidRecorder} after UID is removed.
     */
    @GuardedBy("mStatsLock")
    private void removeUidsLocked(int... uids) {
        if (LOGV) Log.v(TAG, "removeUidsLocked() for UIDs " + Arrays.toString(uids));

        // Perform one last poll before removing
        performPollLocked(FLAG_PERSIST_ALL);

        mUidRecorder.removeUidsLocked(uids);
        mUidTagRecorder.removeUidsLocked(uids);

        // Clear kernel stats associated with UID
        for (int uid : uids) {
            deleteKernelTagData(uid);
        }
    }

    /**
     * Clean up {@link #mUidRecorder} after user is removed.
     */
    @GuardedBy("mStatsLock")
    private void removeUserLocked(@NonNull UserHandle userHandle) {
        if (LOGV) Log.v(TAG, "removeUserLocked() for UserHandle=" + userHandle);

        // Build list of UIDs that we should clean up
        final ArrayList<Integer> uids = new ArrayList<>();
        final List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_ANY_USER
                | PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ApplicationInfo app : apps) {
            final int uid = userHandle.getUid(app.uid);
            uids.add(uid);
        }

        removeUidsLocked(CollectionUtils.toIntArray(uids));
    }

    /**
     * Set the warning and limit to all registered custom network stats providers.
     * Note that invocation of any interface will be sent to all providers.
     */
    public void setStatsProviderWarningAndLimitAsync(
            @NonNull String iface, long warning, long limit) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        if (LOGV) {
            Log.v(TAG, "setStatsProviderWarningAndLimitAsync("
                    + iface + "," + warning + "," + limit + ")");
        }
        invokeForAllStatsProviderCallbacks((cb) -> cb.mProvider.onSetWarningAndLimit(iface,
                warning, limit));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter rawWriter, String[] args) {
        if (!PermissionUtils.checkDumpPermission(mContext, TAG, rawWriter)) return;

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

            pw.println("Configs:");
            pw.increaseIndent();
            pw.print(NETSTATS_COMBINE_SUBTYPE_ENABLED, mSettings.getCombineSubtypeEnabled());
            pw.println();
            pw.decreaseIndent();

            pw.println("Active interfaces:");
            pw.increaseIndent();
            for (int i = 0; i < mActiveIfaces.size(); i++) {
                pw.print("iface", mActiveIfaces.keyAt(i));
                pw.print("ident", mActiveIfaces.valueAt(i));
                pw.println();
            }
            pw.decreaseIndent();

            pw.println("Active UID interfaces:");
            pw.increaseIndent();
            for (int i = 0; i < mActiveUidIfaces.size(); i++) {
                pw.print("iface", mActiveUidIfaces.keyAt(i));
                pw.print("ident", mActiveUidIfaces.valueAt(i));
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

            pw.println("Stats Providers:");
            pw.increaseIndent();
            invokeForAllStatsProviderCallbacks((cb) -> {
                pw.println(cb.mTag + " Xt:");
                pw.increaseIndent();
                pw.print(cb.getCachedStats(STATS_PER_IFACE).toString());
                pw.decreaseIndent();
                if (includeUid) {
                    pw.println(cb.mTag + " Uid:");
                    pw.increaseIndent();
                    pw.print(cb.getCachedStats(STATS_PER_UID).toString());
                    pw.decreaseIndent();
                }
            });
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

    @GuardedBy("mStatsLock")
    private void dumpProtoLocked(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(new FileOutputStream(fd));

        // TODO Right now it writes all history.  Should it limit to the "since-boot" log?

        dumpInterfaces(proto, NetworkStatsServiceDumpProto.ACTIVE_INTERFACES_FIELD_NUMBER,
                mActiveIfaces);
        dumpInterfaces(proto, NetworkStatsServiceDumpProto.ACTIVE_UID_INTERFACES_FIELD_NUMBER,
                mActiveUidIfaces);
        mDevRecorder.dumpDebugLocked(proto, NetworkStatsServiceDumpProto.DEV_STATS_FIELD_NUMBER);
        mXtRecorder.dumpDebugLocked(proto, NetworkStatsServiceDumpProto.XT_STATS_FIELD_NUMBER);
        mUidRecorder.dumpDebugLocked(proto, NetworkStatsServiceDumpProto.UID_STATS_FIELD_NUMBER);
        mUidTagRecorder.dumpDebugLocked(proto,
                NetworkStatsServiceDumpProto.UID_TAG_STATS_FIELD_NUMBER);

        proto.flush();
    }

    private static void dumpInterfaces(ProtoOutputStream proto, long tag,
            ArrayMap<String, NetworkIdentitySet> ifaces) {
        for (int i = 0; i < ifaces.size(); i++) {
            final long start = proto.start(tag);

            proto.write(NetworkInterfaceProto.INTERFACE_FIELD_NUMBER, ifaces.keyAt(i));
            ifaces.valueAt(i).dumpDebug(proto, NetworkInterfaceProto.IDENTITIES_FIELD_NUMBER);

            proto.end(start);
        }
    }

    private NetworkStats readNetworkStatsSummaryDev() {
        try {
            return mStatsFactory.readNetworkStatsSummaryDev();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private NetworkStats readNetworkStatsSummaryXt() {
        try {
            return mStatsFactory.readNetworkStatsSummaryXt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private NetworkStats readNetworkStatsUidDetail(int uid, String[] ifaces, int tag) {
        try {
            return mStatsFactory.readNetworkStatsDetail(uid, ifaces, tag);
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
        final NetworkStats uidSnapshot = readNetworkStatsUidDetail(UID_ALL,  ifaces, TAG_ALL);

        // fold tethering stats and operations into uid snapshot
        final NetworkStats tetherSnapshot = getNetworkStatsTethering(STATS_PER_UID);
        tetherSnapshot.filter(UID_ALL, ifaces, TAG_ALL);
        mStatsFactory.apply464xlatAdjustments(uidSnapshot, tetherSnapshot);
        uidSnapshot.combineAllValues(tetherSnapshot);

        // get a stale copy of uid stats snapshot provided by providers.
        final NetworkStats providerStats = getNetworkStatsFromProviders(STATS_PER_UID);
        providerStats.filter(UID_ALL, ifaces, TAG_ALL);
        mStatsFactory.apply464xlatAdjustments(uidSnapshot, providerStats);
        uidSnapshot.combineAllValues(providerStats);

        uidSnapshot.combineAllValues(mUidOperations);

        return uidSnapshot;
    }

    /**
     * Return snapshot of current non-offloaded tethering statistics. Will return empty
     * {@link NetworkStats} if any problems are encountered, or queried by {@code STATS_PER_IFACE}
     * since it is already included by {@link #nativeGetIfaceStat}.
     * See {@code OffloadTetheringStatsProvider} for offloaded tethering stats.
     */
    // TODO: Remove this by implementing {@link NetworkStatsProvider} for non-offloaded
    //  tethering stats.
    private @NonNull NetworkStats getNetworkStatsTethering(int how) throws RemoteException {
         // We only need to return per-UID stats. Per-device stats are already counted by
        // interface counters.
        if (how != STATS_PER_UID) {
            return new NetworkStats(SystemClock.elapsedRealtime(), 0);
        }

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        try {
            final TetherStatsParcel[] tetherStatsParcels = mNetd.tetherGetStats();
            for (TetherStatsParcel tetherStats : tetherStatsParcels) {
                try {
                    stats.combineValues(new NetworkStats.Entry(tetherStats.iface, UID_TETHERING,
                            SET_DEFAULT, TAG_NONE, tetherStats.rxBytes, tetherStats.rxPackets,
                            tetherStats.txBytes, tetherStats.txPackets, 0L));
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IllegalStateException("invalid tethering stats " + e);
                }
            }
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
        }
        return stats;
    }

    // TODO: It is copied from ConnectivityService, consider refactor these check permission
    //  functions to a proper util.
    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissions) + ".");
        }
    }

    /**
     * Registers a custom provider of {@link android.net.NetworkStats} to combine the network
     * statistics that cannot be seen by the kernel to system. To unregister, invoke the
     * {@code unregister()} of the returned callback.
     *
     * @param tag a human readable identifier of the custom network stats provider.
     * @param provider the {@link INetworkStatsProvider} binder corresponding to the
     *                 {@link NetworkStatsProvider} to be registered.
     *
     * @return a {@link INetworkStatsProviderCallback} binder
     *         interface, which can be used to report events to the system.
     */
    public @NonNull INetworkStatsProviderCallback registerNetworkStatsProvider(
            @NonNull String tag, @NonNull INetworkStatsProvider provider) {
        enforceAnyPermissionOf(NETWORK_STATS_PROVIDER,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
        Objects.requireNonNull(provider, "provider is null");
        Objects.requireNonNull(tag, "tag is null");
        final NetworkPolicyManager netPolicyManager = mContext
                .getSystemService(NetworkPolicyManager.class);
        try {
            NetworkStatsProviderCallbackImpl callback = new NetworkStatsProviderCallbackImpl(
                    tag, provider, mStatsProviderSem, mAlertObserver,
                    mStatsProviderCbList, netPolicyManager);
            mStatsProviderCbList.add(callback);
            Log.d(TAG, "registerNetworkStatsProvider from " + callback.mTag + " uid/pid="
                    + getCallingUid() + "/" + getCallingPid());
            return callback;
        } catch (RemoteException e) {
            Log.e(TAG, "registerNetworkStatsProvider failed", e);
        }
        return null;
    }

    // Collect stats from local cache of providers.
    private @NonNull NetworkStats getNetworkStatsFromProviders(int how) {
        final NetworkStats ret = new NetworkStats(0L, 0);
        invokeForAllStatsProviderCallbacks((cb) -> ret.combineAllValues(cb.getCachedStats(how)));
        return ret;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<S, T extends Throwable> {
        void accept(S s) throws T;
    }

    private void invokeForAllStatsProviderCallbacks(
            @NonNull ThrowingConsumer<NetworkStatsProviderCallbackImpl, RemoteException> task) {
        for (final NetworkStatsProviderCallbackImpl cb : mStatsProviderCbList) {
            try {
                task.accept(cb);
            } catch (RemoteException e) {
                Log.e(TAG, "Fail to broadcast to provider: " + cb.mTag, e);
            }
        }
    }

    private static class NetworkStatsProviderCallbackImpl extends INetworkStatsProviderCallback.Stub
            implements IBinder.DeathRecipient {
        @NonNull final String mTag;

        @NonNull final INetworkStatsProvider mProvider;
        @NonNull private final Semaphore mSemaphore;
        @NonNull final AlertObserver mAlertObserver;
        @NonNull final CopyOnWriteArrayList<NetworkStatsProviderCallbackImpl> mStatsProviderCbList;
        @NonNull final NetworkPolicyManager mNetworkPolicyManager;

        @NonNull private final Object mProviderStatsLock = new Object();

        @GuardedBy("mProviderStatsLock")
        // Track STATS_PER_IFACE and STATS_PER_UID separately.
        private final NetworkStats mIfaceStats = new NetworkStats(0L, 0);
        @GuardedBy("mProviderStatsLock")
        private final NetworkStats mUidStats = new NetworkStats(0L, 0);

        NetworkStatsProviderCallbackImpl(
                @NonNull String tag, @NonNull INetworkStatsProvider provider,
                @NonNull Semaphore semaphore,
                @NonNull AlertObserver alertObserver,
                @NonNull CopyOnWriteArrayList<NetworkStatsProviderCallbackImpl> cbList,
                @NonNull NetworkPolicyManager networkPolicyManager)
                throws RemoteException {
            mTag = tag;
            mProvider = provider;
            mProvider.asBinder().linkToDeath(this, 0);
            mSemaphore = semaphore;
            mAlertObserver = alertObserver;
            mStatsProviderCbList = cbList;
            mNetworkPolicyManager = networkPolicyManager;
        }

        @NonNull
        public NetworkStats getCachedStats(int how) {
            synchronized (mProviderStatsLock) {
                NetworkStats stats;
                switch (how) {
                    case STATS_PER_IFACE:
                        stats = mIfaceStats;
                        break;
                    case STATS_PER_UID:
                        stats = mUidStats;
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid type: " + how);
                }
                // Callers might be able to mutate the returned object. Return a defensive copy
                // instead of local reference.
                return stats.clone();
            }
        }

        @Override
        public void notifyStatsUpdated(int token, @Nullable NetworkStats ifaceStats,
                @Nullable NetworkStats uidStats) {
            // TODO: 1. Use token to map ifaces to correct NetworkIdentity.
            //       2. Store the difference and store it directly to the recorder.
            synchronized (mProviderStatsLock) {
                if (ifaceStats != null) mIfaceStats.combineAllValues(ifaceStats);
                if (uidStats != null) mUidStats.combineAllValues(uidStats);
            }
            mSemaphore.release();
        }

        @Override
        public void notifyAlertReached() throws RemoteException {
            // This binder object can only have been obtained by a process that holds
            // NETWORK_STATS_PROVIDER. Thus, no additional permission check is required.
            BinderUtils.withCleanCallingIdentity(() ->
                    mAlertObserver.onQuotaLimitReached(LIMIT_GLOBAL_ALERT, null /* unused */));
        }

        @Override
        public void notifyWarningReached() {
            Log.d(TAG, mTag + ": notifyWarningReached");
            BinderUtils.withCleanCallingIdentity(() ->
                    mNetworkPolicyManager.notifyStatsProviderWarningReached());
        }

        @Override
        public void notifyLimitReached() {
            Log.d(TAG, mTag + ": notifyLimitReached");
            BinderUtils.withCleanCallingIdentity(() ->
                    mNetworkPolicyManager.notifyStatsProviderLimitReached());
        }

        @Override
        public void binderDied() {
            Log.d(TAG, mTag + ": binderDied");
            mStatsProviderCbList.remove(this);
        }

        @Override
        public void unregister() {
            Log.d(TAG, mTag + ": unregister");
            mStatsProviderCbList.remove(this);
        }

    }

    private void assertSystemReady() {
        if (!mSystemReady) {
            throw new IllegalStateException("System not ready");
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
        DefaultNetworkStatsSettings() {}

        @Override
        public long getPollInterval() {
            return 30 * MINUTE_IN_MILLIS;
        }
        @Override
        public long getPollDelay() {
            return DEFAULT_PERFORM_POLL_DELAY_MS;
        }
        @Override
        public long getGlobalAlertBytes(long def) {
            return def;
        }
        @Override
        public boolean getSampleEnabled() {
            return true;
        }
        @Override
        public boolean getAugmentEnabled() {
            return true;
        }
        @Override
        public boolean getCombineSubtypeEnabled() {
            return false;
        }
        @Override
        public Config getDevConfig() {
            return new Config(HOUR_IN_MILLIS, 15 * DAY_IN_MILLIS, 90 * DAY_IN_MILLIS);
        }
        @Override
        public Config getXtConfig() {
            return getDevConfig();
        }
        @Override
        public Config getUidConfig() {
            return new Config(2 * HOUR_IN_MILLIS, 15 * DAY_IN_MILLIS, 90 * DAY_IN_MILLIS);
        }
        @Override
        public Config getUidTagConfig() {
            return new Config(2 * HOUR_IN_MILLIS, 5 * DAY_IN_MILLIS, 15 * DAY_IN_MILLIS);
        }
        @Override
        public long getDevPersistBytes(long def) {
            return def;
        }
        @Override
        public long getXtPersistBytes(long def) {
            return def;
        }
        @Override
        public long getUidPersistBytes(long def) {
            return def;
        }
        @Override
        public long getUidTagPersistBytes(long def) {
            return def;
        }
    }

    private static native long nativeGetTotalStat(int type);
    private static native long nativeGetIfaceStat(String iface, int type);
    private static native long nativeGetUidStat(int uid, int type);
}
