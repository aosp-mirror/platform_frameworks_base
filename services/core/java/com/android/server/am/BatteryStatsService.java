/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.server.am;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.State;
import android.hardware.power.stats.StateResidency;
import android.hardware.power.stats.StateResidencyResult;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManagerInternal;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFormatException;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.connectivity.WifiBatteryStats;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.UidHealthStats;
import android.power.PowerStatsInternal;
import android.provider.Settings;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.BatteryUsageStatsProvider;
import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.RailStats;
import com.android.internal.os.RpmStats;
import com.android.internal.os.SystemServerCpuThreadReader.SystemServiceCpuThreadTimes;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ParseUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.net.module.util.NetworkCapabilitiesUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.pm.UserManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub
        implements PowerManagerInternal.LowPowerModeListener,
        BatteryStatsImpl.PlatformIdleStateCallback,
        BatteryStatsImpl.MeasuredEnergyRetriever,
        Watchdog.Monitor {
    static final String TAG = "BatteryStatsService";
    static final boolean DBG = false;

    private static IBatteryStats sService;

    final BatteryStatsImpl mStats;
    private final BatteryStatsImpl.UserInfoProvider mUserManagerUserInfoProvider;
    private final Context mContext;
    private final BatteryExternalStatsWorker mWorker;
    private final BatteryUsageStatsProvider mBatteryUsageStatsProvider;

    private native void getRailEnergyPowerStats(RailStats railStats);
    private CharsetDecoder mDecoderStat = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");
    private static final int MAX_LOW_POWER_STATS_SIZE = 8192;
    private static final int POWER_STATS_QUERY_TIMEOUT_MILLIS = 2000;
    private static final String EMPTY = "Empty";

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Object mLock = new Object();

    private final Object mPowerStatsLock = new Object();
    @GuardedBy("mPowerStatsLock")
    private PowerStatsInternal mPowerStatsInternal = null;
    @GuardedBy("mPowerStatsLock")
    private Map<Integer, String> mEntityNames = new HashMap();
    @GuardedBy("mPowerStatsLock")
    private Map<Integer, Map<Integer, String>> mStateNames = new HashMap();

    @GuardedBy("mStats")
    private int mLastPowerStateFromRadio = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    @GuardedBy("mStats")
    private int mLastPowerStateFromWifi = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    private final INetworkManagementEventObserver mActivityChangeObserver =
            new BaseNetworkObserver() {
                @Override
                public void interfaceClassDataActivityChanged(int transportType, boolean active,
                        long tsNanos, int uid) {
                    final int powerState = active
                            ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                            : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
                    final long timestampNanos;
                    if (tsNanos <= 0) {
                        timestampNanos = SystemClock.elapsedRealtimeNanos();
                    } else {
                        timestampNanos = tsNanos;
                    }

                    switch (transportType) {
                        case NetworkCapabilities.TRANSPORT_CELLULAR:
                            noteMobileRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        case NetworkCapabilities.TRANSPORT_WIFI:
                            noteWifiRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        default:
                            Slog.d(TAG, "Received unexpected transport in "
                                    + "interfaceClassDataActivityChanged unexpected type: "
                                    + transportType);
                    }
                }
            };

    private BatteryManagerInternal mBatteryManagerInternal;

    private void populatePowerEntityMaps() {
        PowerEntity[] entities = mPowerStatsInternal.getPowerEntityInfo();
        if (entities == null) {
            return;
        }

        for (int i = 0; i < entities.length; i++) {
            final PowerEntity entity = entities[i];
            Map<Integer, String> states = new HashMap();
            for (int j = 0; j < entity.states.length; j++) {
                final State state = entity.states[j];
                states.put(state.id, state.name);
            }

            mEntityNames.put(entity.id, entity.name);
            mStateNames.put(entity.id, states);
        }
    }

    /**
     * Replaces the information in the given rpmStats with up-to-date information.
     */
    @Override
    public void fillLowPowerStats(RpmStats rpmStats) {
        synchronized (mPowerStatsLock) {
            if (mPowerStatsInternal == null || mEntityNames.isEmpty() || mStateNames.isEmpty()) {
                return;
            }
        }

        final StateResidencyResult[] results;
        try {
            results = mPowerStatsInternal.getStateResidencyAsync(new int[0])
                    .get(POWER_STATS_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to getStateResidencyAsync", e);
            return;
        }

        if (results == null) return;

        for (int i = 0; i < results.length; i++) {
            final StateResidencyResult result = results[i];
            RpmStats.PowerStateSubsystem subsystem =
                    rpmStats.getSubsystem(mEntityNames.get(result.id));

            for (int j = 0; j < result.stateResidencyData.length; j++) {
                final StateResidency stateResidency = result.stateResidencyData[j];
                subsystem.putState(mStateNames.get(result.id).get(stateResidency.id),
                        stateResidency.totalTimeInStateMs,
                        (int) stateResidency.totalStateEntryCount);
            }
        }
    }

    @Override
    public void fillRailDataStats(RailStats railStats) {
        if (DBG) Slog.d(TAG, "begin getRailEnergyPowerStats");
        try {
            getRailEnergyPowerStats(railStats);
        } finally {
            if (DBG) Slog.d(TAG, "end getRailEnergyPowerStats");
        }
    }

    @Override
    public String getSubsystemLowPowerStats() {
        synchronized (mPowerStatsLock) {
            if (mPowerStatsInternal == null || mEntityNames.isEmpty() || mStateNames.isEmpty()) {
                return EMPTY;
            }
        }

        final StateResidencyResult[] results;
        try {
            results = mPowerStatsInternal.getStateResidencyAsync(new int[0])
                    .get(POWER_STATS_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to getStateResidencyAsync", e);
            return EMPTY;
        }

        if (results == null || results.length == 0) return EMPTY;

        int charsLeft = MAX_LOW_POWER_STATS_SIZE;
        StringBuilder builder = new StringBuilder("SubsystemPowerState");
        for (int i = 0; i < results.length; i++) {
            final StateResidencyResult result = results[i];
            StringBuilder subsystemBuilder = new StringBuilder();
            subsystemBuilder.append(" subsystem_" + i);
            subsystemBuilder.append(" name=" + mEntityNames.get(result.id));

            for (int j = 0; j < result.stateResidencyData.length; j++) {
                final StateResidency stateResidency = result.stateResidencyData[j];
                subsystemBuilder.append(" state_" + j);
                subsystemBuilder.append(" name=" + mStateNames.get(result.id).get(
                        stateResidency.id));
                subsystemBuilder.append(" time=" + stateResidency.totalTimeInStateMs);
                subsystemBuilder.append(" count=" + stateResidency.totalStateEntryCount);
                subsystemBuilder.append(" last entry=" + stateResidency.lastEntryTimestampMs);
            }

            if (subsystemBuilder.length() <= charsLeft) {
                charsLeft -= subsystemBuilder.length();
                builder.append(subsystemBuilder);
            } else {
                Slog.e(TAG, "getSubsystemLowPowerStats: buffer not enough");
                break;
            }
        }

        return builder.toString();
    }

    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network,
                @NonNull NetworkCapabilities networkCapabilities) {
            final String state = networkCapabilities.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    ? "CONNECTED" : "SUSPENDED";
            noteConnectivityChanged(NetworkCapabilitiesUtils.getDisplayTransport(
                    networkCapabilities.getTransportTypes()), state);
        }

        @Override
        public void onLost(Network network) {
            noteConnectivityChanged(-1, "DISCONNECTED");
        }
    };

    BatteryStatsService(Context context, File systemDir, Handler handler) {
        // BatteryStatsImpl expects the ActivityManagerService handler, so pass that one through.
        mContext = context;
        mUserManagerUserInfoProvider = new BatteryStatsImpl.UserInfoProvider() {
            private UserManagerInternal umi;
            @Override
            public int[] getUserIds() {
                if (umi == null) {
                    umi = LocalServices.getService(UserManagerInternal.class);
                }
                return (umi != null) ? umi.getUserIds() : null;
            }
        };
        mHandlerThread = new HandlerThread("batterystats-handler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mStats = new BatteryStatsImpl(systemDir, handler, this,
                this, mUserManagerUserInfoProvider);
        mWorker = new BatteryExternalStatsWorker(context, mStats);
        mStats.setExternalStatsSyncLocked(mWorker);
        mStats.setRadioScanningTimeoutLocked(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout) * 1000L);
        mStats.setPowerProfileLocked(new PowerProfile(context));
        mStats.startTrackingSystemServerCpuTime();

        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(context, mStats);
    }

    public void publish() {
        LocalServices.addService(BatteryStatsInternal.class, new LocalService());
        ServiceManager.addService(BatteryStats.SERVICE_NAME, asBinder());
    }

    public void systemServicesReady() {
        mStats.systemServicesReady(mContext);
        mWorker.systemServicesReady();
        final INetworkManagementService nms = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        try {
            nms.registerObserver(mActivityChangeObserver);
            cm.registerDefaultNetworkCallback(mNetworkCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register INetworkManagement event observer " + e);
        }

        synchronized (mPowerStatsLock) {
            mPowerStatsInternal = LocalServices.getService(PowerStatsInternal.class);
            if (mPowerStatsInternal != null) {
                populatePowerEntityMaps();
            } else {
                Slog.e(TAG, "Could not register PowerStatsInternal");
            }
        }
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);

        Watchdog.getInstance().addMonitor(this);

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext, mHandler);
        dataConnectionStats.startMonitoring();

        registerStatsCallbacks();
    }

    private final class LocalService extends BatteryStatsInternal {
        @Override
        public String[] getWifiIfaces() {
            return mStats.getWifiIfaces().clone();
        }

        @Override
        public String[] getMobileIfaces() {
            return mStats.getMobileIfaces().clone();
        }

        @Override
        public SystemServiceCpuThreadTimes getSystemServiceCpuThreadTimes() {
            return mStats.getSystemServiceCpuThreadTimes();
        }

        @Override
        public void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
            if (DBG) Slog.d(TAG, "Jobs deferred " + uid + ": " + numDeferred + " " + sinceLast);
            BatteryStatsService.this.noteJobsDeferred(uid, numDeferred, sinceLast);
        }

        @Override
        public void noteBinderCallStats(int workSourceUid, long incrementatCallCount,
                Collection<BinderCallsStats.CallStat> callStats) {
            synchronized (BatteryStatsService.this.mLock) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        mStats::noteBinderCallStats, workSourceUid, incrementatCallCount, callStats,
                        SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()));
            }
        }

        @Override
        public void noteBinderThreadNativeIds(int[] binderThreadNativeTids) {
            synchronized (BatteryStatsService.this.mLock) {
                mStats.noteBinderThreadNativeIds(binderThreadNativeTids);
            }
        }
    }

    @Override
    public void monitor() {
        synchronized (mLock) {
        }
        synchronized (mStats) {
        }
    }

    private static void awaitUninterruptibly(Future<?> future) {
        while (true) {
            try {
                future.get();
                return;
            } catch (ExecutionException e) {
                return;
            } catch (InterruptedException e) {
                // Keep looping
            }
        }
    }

    private void syncStats(String reason, int flags) {
        awaitUninterruptibly(mWorker.scheduleSync(reason, flags));
    }

    private void awaitCompletion() {
        final CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
        }
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize the low power observer later.
     */
    public void initPowerManagement() {
        final PowerManagerInternal powerMgr = LocalServices.getService(PowerManagerInternal.class);
        powerMgr.registerLowPowerModeObserver(this);
        synchronized (mStats) {
            mStats.notePowerSaveModeLocked(
                    powerMgr.getLowPowerState(ServiceType.BATTERY_STATS).batterySaverEnabled,
                    SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), true);
        }
        (new WakeupReasonThread()).start();
    }

    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");

        // Drain the handler queue to make sure we've handled all pending works.
        awaitCompletion();

        syncStats("shutdown", BatteryExternalStatsWorker.UPDATE_ALL);

        synchronized (mStats) {
            mStats.shutdownLocked();
        }

        // Shutdown the thread we made.
        mWorker.shutdown();
    }

    public static IBatteryStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(BatteryStats.SERVICE_NAME);
        sService = asInterface(b);
        return sService;
    }

    @Override
    public int getServiceType() {
        return ServiceType.BATTERY_STATS;
    }

    @Override
    public void onLowPowerModeChanged(final PowerSaveState result) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePowerSaveModeLocked(result.batterySaverEnabled,
                            elapsedRealtime, uptime, false);
                }
            });
        }
    }

    /**
     * @return the current statistics object, which may be modified
     * to reflect events that affect battery usage.  You must lock the
     * stats object before doing anything with it.
     */
    public BatteryStatsImpl getActiveStatistics() {
        return mStats;
    }

    /**
     * Schedules a write to disk to occur. This will cause the BatteryStatsImpl
     * object to update with the latest info, then write to disk.
     */
    public void scheduleWriteToDisk() {
        synchronized (mLock) {
            // We still schedule it on the handler so we'll have all existing pending works done.
            mHandler.post(() -> {
                mWorker.scheduleWrite();
            });
        }
    }

    // These are for direct use by the activity manager...

    /**
     * Remove a UID from the BatteryStats and BatteryStats' external dependencies.
     */
    void removeUid(final int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.removeUidStatsLocked(uid, elapsedRealtime);
                }
            });
        }
    }

    void onCleanupUser(final int userId) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.onCleanupUserLocked(userId, elapsedRealtime);
                }
            });
        }
    }

    void onUserRemoved(final int userId) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.onUserRemovedLocked(userId);
                }
            });
        }
    }

    void addIsolatedUid(final int isolatedUid, final int appUid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.addIsolatedUidLocked(isolatedUid, appUid, elapsedRealtime, uptime);
                }
            });
        }
    }

    void removeIsolatedUid(final int isolatedUid, final int appUid) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.scheduleRemoveIsolatedUidLocked(isolatedUid, appUid);
                }
            });
        }
    }

    void noteProcessStart(final String name, final int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__STARTED);
    }

    void noteProcessCrash(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessCrashLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__CRASHED);
    }

    void noteProcessAnr(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessAnrLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    void noteProcessFinish(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessFinishLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__FINISHED);
    }

    /** @param state Process state from ActivityManager.java. */
    void noteUidProcessState(int uid, int state) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteUidProcessStateLocked(uid, state, elapsedRealtime, uptime);
                }
            });
        }
    }

    // Public interface...

    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     */
    public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        awaitCompletion();

        if (mBatteryUsageStatsProvider.shouldUpdateStats(queries,
                mWorker.getLastCollectionTimeStamp())) {
            syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
        }

        return mBatteryUsageStatsProvider.getBatteryUsageStats(queries);
    }

    public byte[] getStatistics() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        // Drain the handler queue to make sure we've handled all pending works, so we'll get
        // an accurate stats.
        awaitCompletion();
        syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
        out.recycle();
        return data;
    }

    /**
     * Returns parceled BatteryStats as a MemoryFile.
     *
     * @param forceUpdate If true, runs a sync to get fresh battery stats. Otherwise,
     *                  returns the current values.
     */
    public ParcelFileDescriptor getStatisticsStream(boolean forceUpdate) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        if (forceUpdate) {
            // Drain the handler queue to make sure we've handled all pending works, so we'll get
            // an accurate stats.
            awaitCompletion();
            syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
        }
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
        if (DBG) Slog.d(TAG, "getStatisticsStream parcel size is:" + data.length);
        out.recycle();
        try {
            return ParcelFileDescriptor.fromData(data, "battery-stats");
        } catch (IOException e) {
            Slog.w(TAG, "Unable to create shared memory", e);
            return null;
        }
    }

    /** Register callbacks for statsd pulled atoms. */
    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        final StatsPullAtomCallbackImpl pullAtomCallback = new StatsPullAtomCallbackImpl();

        statsManager.setPullAtomCallback(
                FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET,
                null, // use default PullAtomMetadata values
                BackgroundThread.getExecutor(), pullAtomCallback);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET_USING_POWER_PROFILE_MODEL,
                null, // use default PullAtomMetadata values
                BackgroundThread.getExecutor(), pullAtomCallback);
    }

    /** StatsPullAtomCallback for pulling BatteryUsageStats data. */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            final BatteryUsageStats bus;
            switch (atomTag) {
                case FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET:
                    bus = getBatteryUsageStats(List.of(BatteryUsageStatsQuery.DEFAULT)).get(0);
                    break;
                case FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET_USING_POWER_PROFILE_MODEL:
                    final BatteryUsageStatsQuery powerProfileQuery =
                            new BatteryUsageStatsQuery.Builder().powerProfileModeledOnly().build();
                    bus = getBatteryUsageStats(List.of(powerProfileQuery)).get(0);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
            }
            // TODO(b/187223764): busTime won't be needed once end_session is a field in BUS.
            final long busTime = System.currentTimeMillis();
            final byte[] statsProto = bus.getStatsProto(busTime);

            data.add(FrameworkStatsLog.buildStatsEvent(atomTag, statsProto));

            return StatsManager.PULL_SUCCESS;
        }
    }

    public boolean isCharging() {
        synchronized (mStats) {
            return mStats.isCharging();
        }
    }

    public long computeBatteryTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeBatteryTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    public long computeChargeTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeChargeTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    public void noteEvent(final int code, final String name, final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteEventLocked(code, name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteSyncStart(final String name, final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteSyncStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__ON);
    }

    public void noteSyncFinish(final String name, final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteSyncFinishLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__OFF);
    }

    /** A scheduled job was started. */
    public void noteJobStart(final String name, final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    /** A scheduled job was finished. */
    public void noteJobFinish(final String name, final int uid, final int stopReason) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobFinishLocked(name, uid, stopReason, elapsedRealtime, uptime);
                }
            });
        }
    }

    void noteJobsDeferred(final int uid, final int numDeferred, final long sinceLast) {
        // No need to enforce calling permission, as it is called from an internal interface
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobsDeferredLocked(uid, numDeferred, sinceLast,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWakupAlarm(final String name, final int uid, final WorkSource workSource,
            final String tag) {
        enforceCallingPermission();
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWakupAlarmLocked(name, uid, localWs, tag,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteAlarmStart(final String name, final WorkSource workSource, final int uid) {
        enforceCallingPermission();
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAlarmStartLocked(name, localWs, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteAlarmFinish(final String name, final WorkSource workSource, final int uid) {
        enforceCallingPermission();
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAlarmFinishLocked(name, localWs, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteStartWakelock(final int uid, final int pid, final String name,
            final String historyName, final int type, final boolean unimportantForLogging) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartWakeLocked(uid, pid, null, name, historyName, type,
                            unimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteStopWakelock(final int uid, final int pid, final String name,
            final String historyName, final int type) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopWakeLocked(uid, pid, null, name, historyName, type,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteStartWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type, final boolean unimportantForLogging) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartWakeFromSourceLocked(localWs, pid, name, historyName,
                            type, unimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteChangeWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type, final WorkSource newWs, final int newPid,
            final String newName, final String newHistoryName, final int newType,
            final boolean newUnimportantForLogging) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteChangeWakelockFromSourceLocked(localWs, pid, name, historyName, type,
                            localNewWs, newPid, newName, newHistoryName, newType,
                            newUnimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteStopWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopWakeFromSourceLocked(localWs, pid, name, historyName, type,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteLongPartialWakelockStart(final String name, final String historyName,
            final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockStart(name, historyName, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteLongPartialWakelockStartFromSource(final String name, final String historyName,
            final WorkSource workSource) {
        enforceCallingPermission();
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockStartFromSource(name, historyName, localWs,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteLongPartialWakelockFinish(final String name, final String historyName,
            final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockFinish(name, historyName, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteLongPartialWakelockFinishFromSource(final String name, final String historyName,
            final WorkSource workSource) {
        enforceCallingPermission();
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockFinishFromSource(name, historyName, localWs,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteStartSensor(final int uid, final int sensor) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartSensorLocked(uid, sensor, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid,
                null, sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__ON);
    }

    public void noteStopSensor(final int uid, final int sensor) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopSensorLocked(uid, sensor, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid,
                null, sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__OFF);
    }

    public void noteVibratorOn(final int uid, final long durationMillis) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVibratorOnLocked(uid, durationMillis, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteVibratorOff(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVibratorOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteGpsChanged(final WorkSource oldWs, final WorkSource newWs) {
        enforceCallingPermission();
        final WorkSource localOldWs = oldWs != null ? new WorkSource(oldWs) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteGpsChangedLocked(localOldWs, localNewWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteGpsSignalQuality(final int signalLevel) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteGpsSignalQualityLocked(signalLevel, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteScreenState(final int state) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final long currentTime = System.currentTimeMillis();
            mHandler.post(() -> {
                if (DBG) Slog.d(TAG, "begin noteScreenState");
                synchronized (mStats) {
                    mStats.noteScreenStateLocked(state, elapsedRealtime, uptime, currentTime);
                }
                if (DBG) Slog.d(TAG, "end noteScreenState");
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_STATE_CHANGED, state);
    }

    public void noteScreenBrightness(final int brightness) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteScreenBrightnessLocked(brightness, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_BRIGHTNESS_CHANGED, brightness);
    }

    public void noteUserActivity(final int uid, final int event) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteUserActivityLocked(uid, event, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWakeUp(final String reason, final int reasonUid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWakeUpLocked(reason, reasonUid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteInteractive(final boolean interactive) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteInteractiveLocked(interactive, elapsedRealtime);
                }
            });
        }
    }

    public void noteConnectivityChanged(final int type, final String extra) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteConnectivityChangedLocked(type, extra, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteMobileRadioPowerState(final int powerState, final long timestampNs,
            final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                final boolean update;
                synchronized (mStats) {
                    // Ignore if no power state change.
                    if (mLastPowerStateFromRadio == powerState) return;

                    mLastPowerStateFromRadio = powerState;
                    update = mStats.noteMobileRadioPowerStateLocked(powerState, timestampNs, uid,
                            elapsedRealtime, uptime);
                }

                if (update) {
                    mWorker.scheduleSync("modem-data", BatteryExternalStatsWorker.UPDATE_RADIO);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.MOBILE_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    public void notePhoneOn() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneOnLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePhoneOff() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneOffLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePhoneSignalStrength(final SignalStrength signalStrength) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneSignalStrengthLocked(signalStrength, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePhoneDataConnectionState(final int dataType, final boolean hasData,
            final int serviceType) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneDataConnectionStateLocked(dataType, hasData, serviceType,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePhoneState(final int state) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                int simState = mContext.getSystemService(TelephonyManager.class).getSimState();
                synchronized (mStats) {
                    mStats.notePhoneStateLocked(state, simState, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiOn() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiOnLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__ON);
    }

    public void noteWifiOff() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiOffLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__OFF);
    }

    public void noteStartAudio(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAudioOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid,
                null, FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__ON);
    }

    public void noteStopAudio(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAudioOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid,
                null, FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__OFF);
    }

    public void noteStartVideo(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVideoOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED,
                uid, null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__ON);
    }

    public void noteStopVideo(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVideoOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED,
                uid, null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__OFF);
    }

    public void noteResetAudio() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetAudioLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, -1, null,
                FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__RESET);
    }

    public void noteResetVideo() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetVideoLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, -1,
                null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__RESET);
    }

    public void noteFlashlightOn(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFlashlightOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__ON);
    }

    public void noteFlashlightOff(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFlashlightOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__OFF);
    }

    public void noteStartCamera(final int uid) {
        enforceCallingPermission();
        if (DBG) Slog.d(TAG, "begin noteStartCamera");
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCameraOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        if (DBG) Slog.d(TAG, "end noteStartCamera");
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__ON);
    }

    public void noteStopCamera(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCameraOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__OFF);
    }

    public void noteResetCamera() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetCameraLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, -1,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__RESET);
    }

    public void noteResetFlashlight() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetFlashlightLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, -1,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__RESET);
    }

    @Override
    public void noteWifiRadioPowerState(final int powerState, final long tsNanos, final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                // There was a change in WiFi power state.
                // Collect data now for the past activity.
                synchronized (mStats) {
                    // Ignore if no power state change.
                    if (mLastPowerStateFromWifi == powerState) return;

                    mLastPowerStateFromWifi = powerState;
                    if (mStats.isOnBattery()) {
                        final String type =
                                (powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                                || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM)
                                ? "active" : "inactive";
                        mWorker.scheduleSync("wifi-data: " + type,
                                BatteryExternalStatsWorker.UPDATE_WIFI);
                    }
                    mStats.noteWifiRadioPowerState(powerState, tsNanos, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.WIFI_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    public void noteWifiRunning(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRunningLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
        // TODO: Log WIFI_RUNNING_STATE_CHANGED in a better spot to include Hotspot too.
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
    }

    public void noteWifiRunningChanged(final WorkSource oldWs, final WorkSource newWs) {
        enforceCallingPermission();
        final WorkSource localOldWs = oldWs != null ? new WorkSource(oldWs) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRunningChangedLocked(
                            localOldWs, localNewWs, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                newWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                oldWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    public void noteWifiStopped(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : ws;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiStoppedLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    public void noteWifiState(final int wifiState, final String accessPoint) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiStateLocked(wifiState, accessPoint, elapsedRealtime);
                }
            });
        }
    }

    public void noteWifiSupplicantStateChanged(final int supplState, final boolean failedAuth) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiSupplicantStateChangedLocked(supplState, failedAuth,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiRssiChanged(final int newRssi) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRssiChangedLocked(newRssi, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteFullWifiLockAcquired(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockAcquiredLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteFullWifiLockReleased(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockReleasedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiScanStarted(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStartedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiScanStopped(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStoppedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiMulticastEnabled(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiMulticastEnabledLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiMulticastDisabled(final int uid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiMulticastDisabledLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteFullWifiLockAcquiredFromSource(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockAcquiredFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteFullWifiLockReleasedFromSource(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockReleasedFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiScanStartedFromSource(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStartedFromSourceLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiScanStoppedFromSource(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStoppedFromSourceLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiBatchedScanStartedFromSource(final WorkSource ws, final int csph) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiBatchedScanStartedFromSourceLocked(localWs, csph,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWifiBatchedScanStoppedFromSource(final WorkSource ws) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiBatchedScanStoppedFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteNetworkInterfaceForTransports(final String iface, int[] transportTypes) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        synchronized (mLock) {
            mHandler.post(() -> {
                mStats.noteNetworkInterfaceForTransports(iface, transportTypes);
            });
        }
    }

    @Override
    public void noteNetworkStatsEnabled() {
        enforceCallingPermission();
        // During device boot, qtaguid isn't enabled until after the inital
        // loading of battery stats. Now that they're enabled, take our initial
        // snapshot for future delta calculation.
        synchronized (mLock) {
            // Still schedule it on the handler to make sure we have existing pending works done
            mHandler.post(() -> {
                mWorker.scheduleSync("network-stats-enabled",
                        BatteryExternalStatsWorker.UPDATE_RADIO
                        | BatteryExternalStatsWorker.UPDATE_WIFI);
            });
        }
    }

    @Override
    public void noteDeviceIdleMode(final int mode, final String activeReason, final int activeUid) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteDeviceIdleModeLocked(mode, activeReason, activeUid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePackageInstalled(final String pkgName, final long versionCode) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePackageInstalledLocked(pkgName, versionCode,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePackageUninstalled(final String pkgName) {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePackageUninstalledLocked(pkgName, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteBleScanStarted(final WorkSource ws, final boolean isUnoptimized) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanStartedFromSourceLocked(localWs, isUnoptimized,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteBleScanStopped(final WorkSource ws, final boolean isUnoptimized) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanStoppedFromSourceLocked(localWs, isUnoptimized,
                            uptime, elapsedRealtime);
                }
            });
        }
    }

    @Override
    public void noteResetBleScan() {
        enforceCallingPermission();
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetBluetoothScanLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteBleScanResults(final WorkSource ws, final int numNewResults) {
        enforceCallingPermission();
        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanResultsFromSourceLocked(localWs, numNewResults,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteWifiControllerActivity(final WifiActivityEnergyInfo info) {
        enforceCallingPermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid wifi data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                mStats.updateWifiState(info, POWER_DATA_UNAVAILABLE, elapsedRealtime, uptime);
            });
        }
    }

    @Override
    public void noteBluetoothControllerActivity(final BluetoothActivityEnergyInfo info) {
        enforceCallingPermission();
        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid bluetooth data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.updateBluetoothStateLocked(
                            info, POWER_DATA_UNAVAILABLE, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    public void noteModemControllerActivity(final ModemActivityInfo info) {
        enforceCallingPermission();

        if (info == null) {
            Slog.e(TAG, "invalid modem data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                mStats.noteModemControllerActivity(info, POWER_DATA_UNAVAILABLE, elapsedRealtime,
                        uptime);
            });
        }
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }

    @Override
    public void setBatteryState(final int status, final int health, final int plugType,
            final int level, final int temp, final int volt, final int chargeUAh,
            final int chargeFullUAh, final long chargeTimeToFullSeconds) {
        enforceCallingPermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final long currentTime = System.currentTimeMillis();
            // We still schedule this task over the handler thread to make sure we've had
            // all existing pending work handled before setting the battery state
            mHandler.post(() -> {
                // BatteryService calls us here and we may update external state. It would be wrong
                // to block such a low level service like BatteryService on external stats like WiFi
                mWorker.scheduleRunnable(() -> {
                    synchronized (mStats) {
                        final boolean onBattery = BatteryStatsImpl.isOnBattery(plugType, status);
                        if (mStats.isOnBattery() == onBattery) {
                            // The battery state has not changed, so we don't need to sync external
                            // stats immediately.
                            mStats.setBatteryStateLocked(status, health, plugType, level, temp,
                                    volt, chargeUAh, chargeFullUAh, chargeTimeToFullSeconds,
                                    elapsedRealtime, uptime, currentTime);
                            return;
                        }
                    }

                    // Sync external stats first as the battery has changed states. If we don't sync
                    // before changing the state, we may not collect the relevant data later.
                    // Order here is guaranteed since we're scheduling from the same thread and we
                    // are using a single threaded executor.
                    mWorker.scheduleSync("battery-state", BatteryExternalStatsWorker.UPDATE_ALL);
                    mWorker.scheduleRunnable(() -> {
                        synchronized (mStats) {
                            mStats.setBatteryStateLocked(status, health, plugType, level, temp,
                                    volt, chargeUAh, chargeFullUAh, chargeTimeToFullSeconds,
                                    elapsedRealtime, uptime, currentTime);
                        }
                    });
                });
            });
        }
    }

    public long getAwakeTimeBattery() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimeBattery();
    }

    public long getAwakeTimePlugged() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimePlugged();
    }

    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    final class WakeupReasonThread extends Thread {
        private static final int MAX_REASON_SIZE = 512;
        private CharsetDecoder mDecoder;
        private ByteBuffer mUtf8Buffer;
        private CharBuffer mUtf16Buffer;

        WakeupReasonThread() {
            super("BatteryStats_wakeupReason");
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            mDecoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");

            mUtf8Buffer = ByteBuffer.allocateDirect(MAX_REASON_SIZE);
            mUtf16Buffer = CharBuffer.allocate(MAX_REASON_SIZE);

            try {
                String reason;
                while ((reason = waitWakeup()) != null) {
                    // Wait for the completion of pending works if there is any
                    awaitCompletion();

                    synchronized (mStats) {
                        mStats.noteWakeupReasonLocked(reason,
                                SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
                    }
                }
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failure reading wakeup reasons", e);
            }
        }

        private String waitWakeup() {
            mUtf8Buffer.clear();
            mUtf16Buffer.clear();
            mDecoder.reset();

            int bytesWritten = nativeWaitWakeup(mUtf8Buffer);
            if (bytesWritten < 0) {
                return null;
            } else if (bytesWritten == 0) {
                return "unknown";
            }

            // Set the buffer's limit to the number of bytes written.
            mUtf8Buffer.limit(bytesWritten);

            // Decode the buffer from UTF-8 to UTF-16.
            // Unmappable characters will be replaced.
            mDecoder.decode(mUtf8Buffer, mUtf16Buffer, true);
            mUtf16Buffer.flip();

            // Create a String from the UTF-16 buffer.
            return mUtf16Buffer.toString();
        }
    }

    private static native int nativeWaitWakeup(ByteBuffer outBuffer);

    private void dumpHelp(PrintWriter pw) {
        pw.println("Battery stats (batterystats) dump options:");
        pw.println("  [--checkin] [--proto] [--history] [--history-start] [--charged] [-c]");
        pw.println("  [--daily] [--reset] [--write] [--new-daily] [--read-daily] [-h] [<package.name>]");
        pw.println("  --checkin: generate output for a checkin report; will write (and clear) the");
        pw.println("             last old completed stats when they had been reset.");
        pw.println("  -c: write the current stats in checkin format.");
        pw.println("  --proto: write the current aggregate stats (without history) in proto format.");
        pw.println("  --history: show only history data.");
        pw.println("  --history-start <num>: show only history data starting at given time offset.");
        pw.println("  --history-create-events <num>: create <num> of battery history events.");
        pw.println("  --charged: only output data since last charged.");
        pw.println("  --daily: only output full daily data.");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --write: force write current collected stats to disk.");
        pw.println("  --new-daily: immediately create and write new daily stats record.");
        pw.println("  --read-daily: read-load last written daily stats.");
        pw.println("  --settings: dump the settings key/values related to batterystats");
        pw.println("  --cpu: dump cpu stats for debugging purpose");
        pw.println("  <package.name>: optional name of package to filter output by.");
        pw.println("  -h: print this help text.");
        pw.println("Battery stats (batterystats) commands:");
        pw.println("  enable|disable <option>");
        pw.println("    Enable or disable a running option.  Option state is not saved across boots.");
        pw.println("    Options are:");
        pw.println("      full-history: include additional detailed events in battery history:");
        pw.println("          wake_lock_in, alarms and proc events");
        pw.println("      no-auto-reset: don't automatically reset stats when unplugged");
        pw.println("      pretend-screen-off: pretend the screen is off, even if screen state changes");
    }

    private void dumpSettings(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            mStats.dumpConstantsLocked(pw);
        }
    }

    private void dumpCpuStats(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            mStats.dumpCpuStatsLocked(pw);
        }
    }

    private void dumpMeasuredEnergyStats(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
        synchronized (mStats) {
            mStats.dumpMeasuredEnergyStatsLocked(pw);
        }
    }

    private int doEnableOrDisable(PrintWriter pw, int i, String[] args, boolean enable) {
        i++;
        if (i >= args.length) {
            pw.println("Missing option argument for " + (enable ? "--enable" : "--disable"));
            dumpHelp(pw);
            return -1;
        }
        if ("full-wake-history".equals(args[i]) || "full-history".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setRecordAllHistoryLocked(enable);
            }
        } else if ("no-auto-reset".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setNoAutoReset(enable);
            }
        } else if ("pretend-screen-off".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setPretendScreenOff(enable);
            }
        } else {
            pw.println("Unknown enable/disable option: " + args[i]);
            dumpHelp(pw);
            return -1;
        }
        return i;
    }


    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        int flags = 0;
        boolean useCheckinFormat = false;
        boolean toProto = false;
        boolean isRealCheckin = false;
        boolean noOutput = false;
        boolean writeData = false;
        long historyStart = -1;
        int reqUid = -1;
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    useCheckinFormat = true;
                    isRealCheckin = true;
                } else if ("--history".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                } else if ("--history-start".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing time argument for --history-since");
                        dumpHelp(pw);
                        return;
                    }
                    historyStart = ParseUtils.parseLong(args[i], 0);
                    writeData = true;
                } else if ("--history-create-events".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing events argument for --history-create-events");
                        dumpHelp(pw);
                        return;
                    }
                    final long events = ParseUtils.parseLong(args[i], 0);
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.createFakeHistoryEvents(events);
                        pw.println("Battery history create events started.");
                        noOutput = true;
                    }
                } else if ("-c".equals(arg)) {
                    useCheckinFormat = true;
                    flags |= BatteryStats.DUMP_INCLUDE_HISTORY;
                } else if ("--proto".equals(arg)) {
                    toProto = true;
                } else if ("--charged".equals(arg)) {
                    flags |= BatteryStats.DUMP_CHARGED_ONLY;
                } else if ("--daily".equals(arg)) {
                    flags |= BatteryStats.DUMP_DAILY_ONLY;
                } else if ("--reset".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.resetAllStatsCmdLocked();
                        pw.println("Battery stats reset.");
                        noOutput = true;
                    }
                } else if ("--write".equals(arg)) {
                    awaitCompletion();
                    syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
                    synchronized (mStats) {
                        mStats.writeSyncLocked();
                        pw.println("Battery stats written.");
                        noOutput = true;
                    }
                } else if ("--new-daily".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.recordDailyStatsLocked();
                        pw.println("New daily stats written.");
                        noOutput = true;
                    }
                } else if ("--read-daily".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.readDailyStatsLocked();
                        pw.println("Last daily stats read.");
                        noOutput = true;
                    }
                } else if ("--enable".equals(arg) || "enable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, true);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Enabled: " + args[i]);
                    return;
                } else if ("--disable".equals(arg) || "disable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, false);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Disabled: " + args[i]);
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("--settings".equals(arg)) {
                    dumpSettings(pw);
                    return;
                } else if ("--cpu".equals(arg)) {
                    dumpCpuStats(pw);
                    return;
                } else  if ("--measured-energy".equals(arg)) {
                    dumpMeasuredEnergyStats(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    flags |= BatteryStats.DUMP_VERBOSE;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        reqUid = mContext.getPackageManager().getPackageUidAsUser(arg,
                                UserHandle.getCallingUserId());
                    } catch (PackageManager.NameNotFoundException e) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }
        if (noOutput) {
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (BatteryStatsHelper.checkWifiOnly(mContext)) {
                flags |= BatteryStats.DUMP_DEVICE_WIFI_ONLY;
            }
            awaitCompletion();
            // Fetch data from external sources and update the BatteryStatsImpl object with them.
            syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (reqUid >= 0) {
            // By default, if the caller is only interested in a specific package, then
            // we only dump the aggregated data since charged.
            if ((flags&(BatteryStats.DUMP_HISTORY_ONLY|BatteryStats.DUMP_CHARGED_ONLY)) == 0) {
                flags |= BatteryStats.DUMP_CHARGED_ONLY;
                // Also if they are doing -c, we don't want history.
                flags &= ~BatteryStats.DUMP_INCLUDE_HISTORY;
            }
        }

        if (toProto) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider);
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpProtoLocked(
                                        mContext, fd, apps, flags, historyStart);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpProtoLocked from UID " + Binder.getCallingUid());
            awaitCompletion();
            synchronized (mStats) {
                mStats.dumpProtoLocked(mContext, fd, apps, flags, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpProtoLocked");
        } else if (useCheckinFormat) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider);
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpCheckinLocked(mContext, pw, apps, flags,
                                        historyStart);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpCheckinLocked from UID " + Binder.getCallingUid());
            awaitCompletion();
            synchronized (mStats) {
                mStats.dumpCheckinLocked(mContext, pw, apps, flags, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpCheckinLocked");
        } else {
            if (DBG) Slog.d(TAG, "begin dumpLocked from UID " + Binder.getCallingUid());
            awaitCompletion();
            synchronized (mStats) {
                mStats.dumpLocked(mContext, pw, flags, reqUid, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpLocked");
        }
    }

    /**
     * Gets a snapshot of cellular stats
     * @hide
     */
    public CellularBatteryStats getCellularBatteryStats() {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            return mStats.getCellularBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Wifi stats
     * @hide
     */
    public WifiBatteryStats getWifiBatteryStats() {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            return mStats.getWifiBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Gps stats
     * @hide
     */
    public GpsBatteryStats getGpsBatteryStats() {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            return mStats.getGpsBatteryStats();
        }
    }

    /**
     * Gets a snapshot of the system health for a particular uid.
     */
    @Override
    public HealthStatsParceler takeUidSnapshot(int requestUid) {
        if (requestUid != Binder.getCallingUid()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                return getHealthStatsForUidLocked(requestUid);
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Crashed while writing for takeUidSnapshot(" + requestUid + ")", ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Gets a snapshot of the system health for a number of uids.
     */
    @Override
    public HealthStatsParceler[] takeUidSnapshots(int[] requestUids) {
        if (!onlyCaller(requestUids)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        final long ident = Binder.clearCallingIdentity();
        int i=-1;
        try {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                final int N = requestUids.length;
                final HealthStatsParceler[] results = new HealthStatsParceler[N];
                for (i=0; i<N; i++) {
                    results[i] = getHealthStatsForUidLocked(requestUids[i]);
                }
                return results;
            }
        } catch (Exception ex) {
            if (DBG) Slog.d(TAG, "Crashed while writing for takeUidSnapshots("
                    + Arrays.toString(requestUids) + ") i=" + i, ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean shouldCollectExternalStats() {
        return (SystemClock.elapsedRealtime() - mWorker.getLastCollectionTimeStamp())
                > mStats.getExternalStatsCollectionRateLimitMs();
    }

    /**
     * Returns whether the Binder.getCallingUid is the only thing in requestUids.
     */
    private static boolean onlyCaller(int[] requestUids) {
        final int caller = Binder.getCallingUid();
        final int N = requestUids.length;
        for (int i=0; i<N; i++) {
            if (requestUids[i] != caller) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets a HealthStatsParceler for the given uid. You should probably call
     * updateExternalStatsSync first.
     */
    HealthStatsParceler getHealthStatsForUidLocked(int requestUid) {
        final HealthStatsBatteryStatsWriter writer = new HealthStatsBatteryStatsWriter();
        final HealthStatsWriter uidWriter = new HealthStatsWriter(UidHealthStats.CONSTANTS);
        final BatteryStats.Uid uid = mStats.getUidStats().get(requestUid);
        if (uid != null) {
            writer.writeUid(uidWriter, mStats, uid);
        }
        return new HealthStatsParceler(uidWriter);
    }

    /**
     * Delay for sending ACTION_CHARGING after device is plugged in.
     *
     * @hide
     */
    public boolean setChargingStateUpdateDelayMillis(int delayMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER, null);
        final long ident = Binder.clearCallingIdentity();

        try {
            final ContentResolver contentResolver = mContext.getContentResolver();
            return Settings.Global.putLong(contentResolver,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    delayMillis);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void updateForegroundTimeIfOnBattery(final String packageName, final int uid,
            final long cpuTimeDiff) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                if (!isOnBattery()) {
                    return;
                }
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Proc ps =
                            mStats.getProcessStatsLocked(uid, packageName, elapsedRealtime, uptime);
                    if (ps != null) {
                        ps.addForegroundTimeLocked(cpuTimeDiff);
                    }
                }
            });
        }
    }

    void noteCurrentTimeChanged() {
        synchronized (mLock) {
            final long currentTime = System.currentTimeMillis();
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCurrentTimeChangedLocked(currentTime, elapsedRealtime, uptime);
                }
            });
        }
    }

    void updateBatteryStatsOnActivityUsage(final String packageName, final String className,
            final int uid, final int userId, final boolean resumed) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    if (resumed) {
                        mStats.noteActivityResumedLocked(uid, elapsedRealtime, uptime);
                    } else {
                        mStats.noteActivityPausedLocked(uid, elapsedRealtime, uptime);
                    }
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED,
                uid, packageName, className,
                resumed
                        ? FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__FOREGROUND
                        : FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__BACKGROUND);
    }

    void noteProcessDied(final int uid, final int pid) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessDiedLocked(uid, pid);
                }
            });
        }
    }

    void reportExcessiveCpu(final int uid, final String processName, final long uptimeSince,
            long cputimeUsed) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.reportExcessiveCpuLocked(uid, processName, uptimeSince, cputimeUsed);
                }
            });
        }
    }

    void noteServiceStartRunning(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.startRunningLocked(uptime);
                }
            });
        }
    }

    void noteServiceStopRunning(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.stopRunningLocked(uptime);
                }
            });
        }
    }

    void noteServiceStartLaunch(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.startLaunchedLocked(uptime);
                }
            });
        }
    }

    void noteServiceStopLaunch(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.stopLaunchedLocked(uptime);
                }
            });
        }
    }

    /**
     * Sets battery AC charger to enabled/disabled, and freezes the battery state.
     */
    @Override
    public void setChargerAcOnline(boolean online, boolean forceUpdate) {
        mBatteryManagerInternal.setChargerAcOnline(online, forceUpdate);
    }

    /**
     * Sets battery level, and freezes the battery state.
     */
    @Override
    public void setBatteryLevel(int level, boolean forceUpdate) {
        mBatteryManagerInternal.setBatteryLevel(level, forceUpdate);
    }

    /**
     * Unplugs battery, and freezes the battery state.
     */
    @Override
    public void unplugBattery(boolean forceUpdate) {
        mBatteryManagerInternal.unplugBattery(forceUpdate);
    }

    /**
     * Unfreezes battery state, returning to current hardware values.
     */
    @Override
    public void resetBattery(boolean forceUpdate) {
        mBatteryManagerInternal.resetBattery(forceUpdate);
    }

    /**
     * Suspend charging even if plugged in.
     */
    @Override
    public void suspendBatteryInput() {
        mBatteryManagerInternal.suspendBatteryInput();
    }
}
