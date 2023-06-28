/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.android.server.am;

import android.annotation.Nullable;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.Parcelable;
import android.os.Process;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.ThreadLocalWorkSource;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.power.PowerStatsInternal;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.power.MeasuredEnergyStats;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;

import libcore.util.EmptyArray;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A Worker that fetches data from external sources (WiFi controller, bluetooth chipset) on a
 * dedicated thread and updates BatteryStatsImpl with that information.
 *
 * As much work as possible is done without holding the BatteryStatsImpl lock, and only the
 * readily available data is pushed into BatteryStatsImpl with the lock held.
 */
class BatteryExternalStatsWorker implements BatteryStatsImpl.ExternalStatsSync {
    private static final String TAG = "BatteryExternalStatsWorker";
    private static final boolean DEBUG = false;

    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;

    // There is some accuracy error in wifi reports so allow some slop in the results.
    private static final long MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS = 750;

    // Delay for clearing out battery stats for UIDs corresponding to a removed user
    public static final int UID_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS = 10_000;

    private final ScheduledExecutorService mExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                    (ThreadFactory) r -> {
                        Thread t = new Thread(
                                () -> {
                                    ThreadLocalWorkSource.setUid(Process.myUid());
                                    r.run();
                                },
                                "batterystats-worker");
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });

    @GuardedBy("mStats")
    private final BatteryStatsImpl mStats;

    @GuardedBy("this")
    private int mUpdateFlags = 0;

    @GuardedBy("this")
    private Future<?> mCurrentFuture = null;

    @GuardedBy("this")
    private String mCurrentReason = null;

    @GuardedBy("this")
    private boolean mOnBattery;

    @GuardedBy("this")
    private boolean mOnBatteryScreenOff;

    @GuardedBy("this")
    private int mScreenState;

    @GuardedBy("this")
    private int[] mPerDisplayScreenStates = null;

    @GuardedBy("this")
    private boolean mUseLatestStates = true;

    @GuardedBy("this")
    private final IntArray mUidsToRemove = new IntArray();

    @GuardedBy("this")
    private Future<?> mWakelockChangesUpdate;

    @GuardedBy("this")
    private Future<?> mBatteryLevelSync;

    @GuardedBy("this")
    private Future<?> mProcessStateSync;

    // If both mStats and mWorkerLock need to be synchronized, mWorkerLock must be acquired first.
    private final Object mWorkerLock = new Object();

    @GuardedBy("mWorkerLock")
    private WifiManager mWifiManager = null;

    @GuardedBy("mWorkerLock")
    private TelephonyManager mTelephony = null;

    @GuardedBy("mWorkerLock")
    private PowerStatsInternal mPowerStatsInternal = null;

    // WiFi keeps an accumulated total of stats. Keep the last WiFi stats so we can compute a delta.
    // (This is unlike Bluetooth, where BatteryStatsImpl is left responsible for taking the delta.)
    @GuardedBy("mWorkerLock")
    private WifiActivityEnergyInfo mLastWifiInfo =
            new WifiActivityEnergyInfo(0, 0, 0, 0, 0, 0);

    /**
     * Maps an {@link EnergyConsumerType} to it's corresponding {@link EnergyConsumer#id}s,
     * unless it is of {@link EnergyConsumer#type}=={@link EnergyConsumerType#OTHER}
     */
    @GuardedBy("mWorkerLock")
    private @Nullable SparseArray<int[]> mEnergyConsumerTypeToIdMap = null;

    /** Snapshot of measured energies, or null if no measured energies are supported. */
    @GuardedBy("mWorkerLock")
    private @Nullable MeasuredEnergySnapshot mMeasuredEnergySnapshot = null;

    /**
     * Timestamp at which all external stats were last collected in
     * {@link SystemClock#elapsedRealtime()} time base.
     */
    @GuardedBy("this")
    private long mLastCollectionTimeStamp;

    final Injector mInjector;

    @VisibleForTesting
    public static class Injector {
        private final Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        public <T> T getSystemService(Class<T> serviceClass) {
            return mContext.getSystemService(serviceClass);
        }

        public <T> T getLocalService(Class<T> serviceClass) {
            return LocalServices.getService(serviceClass);
        }
    }

    BatteryExternalStatsWorker(Context context, BatteryStatsImpl stats) {
        this(new Injector(context), stats);
    }

    @VisibleForTesting
    BatteryExternalStatsWorker(Injector injector, BatteryStatsImpl stats) {
        mInjector = injector;
        mStats = stats;
    }

    public void systemServicesReady() {
        final WifiManager wm = mInjector.getSystemService(WifiManager.class);
        final TelephonyManager tm = mInjector.getSystemService(TelephonyManager.class);
        final PowerStatsInternal psi = mInjector.getLocalService(PowerStatsInternal.class);
        final int voltageMv;
        synchronized (mStats) {
            voltageMv = mStats.getBatteryVoltageMvLocked();
        }

        synchronized (mWorkerLock) {
            mWifiManager = wm;
            mTelephony = tm;
            mPowerStatsInternal = psi;

            boolean[] supportedStdBuckets = null;
            String[] customBucketNames = null;
            if (mPowerStatsInternal != null) {
                final SparseArray<EnergyConsumer> idToConsumer
                        = populateEnergyConsumerSubsystemMapsLocked();
                if (idToConsumer != null) {
                    mMeasuredEnergySnapshot = new MeasuredEnergySnapshot(idToConsumer);
                    try {
                        final EnergyConsumerResult[] initialEcrs = getEnergyConsumptionData().get(
                                EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                        // According to spec, initialEcrs will include 0s for consumers that haven't
                        // used any energy yet, as long as they are supported; however,
                        // attributed uid energies will be absent if their energy is 0.
                        mMeasuredEnergySnapshot.updateAndGetDelta(initialEcrs, voltageMv);
                    } catch (TimeoutException | InterruptedException e) {
                        Slog.w(TAG, "timeout or interrupt reading initial getEnergyConsumedAsync: "
                                + e);
                        // Continue running, later attempts to query may be successful.
                    } catch (ExecutionException e) {
                        Slog.wtf(TAG, "exception reading initial getEnergyConsumedAsync: "
                                + e.getCause());
                        // Continue running, later attempts to query may be successful.
                    }
                    customBucketNames = mMeasuredEnergySnapshot.getOtherOrdinalNames();
                    supportedStdBuckets = getSupportedEnergyBuckets(idToConsumer);
                }
            }
            synchronized (mStats) {
                mStats.initMeasuredEnergyStatsLocked(supportedStdBuckets, customBucketNames);
            }
        }
    }

    @Override
    public synchronized Future<?> scheduleSync(String reason, int flags) {
        return scheduleSyncLocked(reason, flags);
    }

    @Override
    public synchronized Future<?> scheduleCpuSyncDueToRemovedUid(int uid) {
        mUidsToRemove.add(uid);
        return scheduleSyncLocked("remove-uid", UPDATE_CPU);
    }

    @Override
    public synchronized Future<?> scheduleCpuSyncDueToSettingChange() {
        return scheduleSyncLocked("setting-change", UPDATE_CPU);
    }

    @Override
    public Future<?> scheduleSyncDueToScreenStateChange(int flags, boolean onBattery,
            boolean onBatteryScreenOff, int screenState, int[] perDisplayScreenStates) {
        synchronized (BatteryExternalStatsWorker.this) {
            if (mCurrentFuture == null || (mUpdateFlags & UPDATE_CPU) == 0) {
                mOnBattery = onBattery;
                mOnBatteryScreenOff = onBatteryScreenOff;
                mUseLatestStates = false;
            }
            // always update screen state
            mScreenState = screenState;
            mPerDisplayScreenStates = perDisplayScreenStates;
            return scheduleSyncLocked("screen-state", flags);
        }
    }

    @Override
    public Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis) {
        synchronized (BatteryExternalStatsWorker.this) {
            mWakelockChangesUpdate = scheduleDelayedSyncLocked(mWakelockChangesUpdate,
                    () -> {
                        scheduleSync("wakelock-change", UPDATE_CPU);
                        scheduleRunnable(() -> mStats.postBatteryNeedsCpuUpdateMsg());
                    },
                    delayMillis);
            return mWakelockChangesUpdate;
        }
    }

    @Override
    public void cancelCpuSyncDueToWakelockChange() {
        synchronized (BatteryExternalStatsWorker.this) {
            if (mWakelockChangesUpdate != null) {
                mWakelockChangesUpdate.cancel(false);
                mWakelockChangesUpdate = null;
            }
        }
    }

    @Override
    public Future<?> scheduleSyncDueToBatteryLevelChange(long delayMillis) {
        synchronized (BatteryExternalStatsWorker.this) {
            mBatteryLevelSync = scheduleDelayedSyncLocked(mBatteryLevelSync,
                    () -> scheduleSync("battery-level", UPDATE_ALL),
                    delayMillis);
            return mBatteryLevelSync;
        }
    }

    @GuardedBy("this")
    private void cancelSyncDueToBatteryLevelChangeLocked() {
        if (mBatteryLevelSync != null) {
            mBatteryLevelSync.cancel(false);
            mBatteryLevelSync = null;
        }
    }

    @Override
    public void scheduleSyncDueToProcessStateChange(int flags, long delayMillis) {
        synchronized (BatteryExternalStatsWorker.this) {
            mProcessStateSync = scheduleDelayedSyncLocked(mProcessStateSync,
                    () -> scheduleSync("procstate-change", flags),
                    delayMillis);
        }
    }

    public void cancelSyncDueToProcessStateChange() {
        synchronized (BatteryExternalStatsWorker.this) {
            if (mProcessStateSync != null) {
                mProcessStateSync.cancel(false);
                mProcessStateSync = null;
            }
        }
    }

    @Override
    public Future<?> scheduleCleanupDueToRemovedUser(int userId) {
        synchronized (BatteryExternalStatsWorker.this) {
            return mExecutorService.schedule(() -> {
                synchronized (mStats) {
                    mStats.clearRemovedUserUidsLocked(userId);
                }
            }, UID_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Schedule a sync {@param syncRunnable} with a delay. If there's already a scheduled sync, a
     * new sync won't be scheduled unless it is being scheduled to run immediately (delayMillis=0).
     *
     * @param lastScheduledSync the task which was earlier scheduled to run
     * @param syncRunnable the task that needs to be scheduled to run
     * @param delayMillis time after which {@param syncRunnable} needs to be scheduled
     * @return scheduled {@link Future} which can be used to check if task is completed or to
     *         cancel it if needed
     */
    @GuardedBy("this")
    private Future<?> scheduleDelayedSyncLocked(Future<?> lastScheduledSync, Runnable syncRunnable,
            long delayMillis) {
        if (mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }

        if (lastScheduledSync != null) {
            // If there's already a scheduled task, leave it as is if we're trying to
            // re-schedule it again with a delay, otherwise cancel and re-schedule it.
            if (delayMillis == 0) {
                lastScheduledSync.cancel(false);
            } else {
                return lastScheduledSync;
            }
        }

        return mExecutorService.schedule(syncRunnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized Future<?> scheduleWrite() {
        if (mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }

        scheduleSyncLocked("write", UPDATE_ALL);
        // Since we use a single threaded executor, we can assume the next scheduled task's
        // Future finishes after the sync.
        return mExecutorService.submit(mWriteTask);
    }

    /**
     * Schedules a task to run on the BatteryExternalStatsWorker thread. If scheduling more work
     * within the task, never wait on the resulting Future. This will result in a deadlock.
     */
    public synchronized void scheduleRunnable(Runnable runnable) {
        if (!mExecutorService.isShutdown()) {
            mExecutorService.submit(runnable);
        }
    }

    public synchronized void shutdown() {
        mExecutorService.shutdownNow();
    }

    @GuardedBy("this")
    private Future<?> scheduleSyncLocked(String reason, int flags) {
        if (mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }

        if (mCurrentFuture == null) {
            mUpdateFlags = flags;
            mCurrentReason = reason;
            mCurrentFuture = mExecutorService.submit(mSyncTask);
        }
        mUpdateFlags |= flags;
        return mCurrentFuture;
    }

    long getLastCollectionTimeStamp() {
        synchronized (this) {
            return mLastCollectionTimeStamp;
        }
    }

    private final Runnable mSyncTask = new Runnable() {
        @Override
        public void run() {
            // Capture a snapshot of the state we are meant to process.
            final int updateFlags;
            final String reason;
            final int[] uidsToRemove;
            final boolean onBattery;
            final boolean onBatteryScreenOff;
            final int screenState;
            final int[] displayScreenStates;
            final boolean useLatestStates;
            synchronized (BatteryExternalStatsWorker.this) {
                updateFlags = mUpdateFlags;
                reason = mCurrentReason;
                uidsToRemove = mUidsToRemove.size() > 0 ? mUidsToRemove.toArray() : EmptyArray.INT;
                onBattery = mOnBattery;
                onBatteryScreenOff = mOnBatteryScreenOff;
                screenState = mScreenState;
                displayScreenStates = mPerDisplayScreenStates;
                useLatestStates = mUseLatestStates;
                mUpdateFlags = 0;
                mCurrentReason = null;
                mUidsToRemove.clear();
                mCurrentFuture = null;
                mUseLatestStates = true;
                if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                    cancelSyncDueToBatteryLevelChangeLocked();
                }
                if ((updateFlags & UPDATE_CPU) != 0) {
                    cancelCpuSyncDueToWakelockChange();
                }
                if ((updateFlags & UPDATE_ON_PROC_STATE_CHANGE) == UPDATE_ON_PROC_STATE_CHANGE) {
                    cancelSyncDueToProcessStateChange();
                }
            }

            try {
                synchronized (mWorkerLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "begin updateExternalStatsSync reason=" + reason);
                    }
                    try {
                        updateExternalStatsLocked(reason, updateFlags, onBattery,
                                onBatteryScreenOff, screenState, displayScreenStates,
                                useLatestStates);
                    } finally {
                        if (DEBUG) {
                            Slog.d(TAG, "end updateExternalStatsSync");
                        }
                    }
                }

                if ((updateFlags & UPDATE_CPU) != 0) {
                    mStats.updateCpuTimesForAllUids();
                }

                // Clean up any UIDs if necessary.
                synchronized (mStats) {
                    for (int uid : uidsToRemove) {
                        FrameworkStatsLog.write(FrameworkStatsLog.ISOLATED_UID_CHANGED, -1, uid,
                                FrameworkStatsLog.ISOLATED_UID_CHANGED__EVENT__REMOVED);
                        mStats.maybeRemoveIsolatedUidLocked(uid, SystemClock.elapsedRealtime(),
                                SystemClock.uptimeMillis());
                    }
                    mStats.clearPendingRemovedUidsLocked();
                }
            } catch (Exception e) {
                Slog.wtf(TAG, "Error updating external stats: ", e);
            }

            if ((updateFlags & RESET) != 0) {
                synchronized (BatteryExternalStatsWorker.this) {
                    mLastCollectionTimeStamp = 0;
                }
            } else if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                synchronized (BatteryExternalStatsWorker.this) {
                    mLastCollectionTimeStamp = SystemClock.elapsedRealtime();
                }
            }
        }
    };

    private final Runnable mWriteTask = new Runnable() {
        @Override
        public void run() {
            synchronized (mStats) {
                mStats.writeAsyncLocked();
            }
        }
    };

    @GuardedBy("mWorkerLock")
    private void updateExternalStatsLocked(final String reason, int updateFlags, boolean onBattery,
            boolean onBatteryScreenOff, int screenState, int[] displayScreenStates,
            boolean useLatestStates) {
        // We will request data from external processes asynchronously, and wait on a timeout.
        SynchronousResultReceiver wifiReceiver = null;
        SynchronousResultReceiver bluetoothReceiver = null;
        CompletableFuture<ModemActivityInfo> modemFuture = CompletableFuture.completedFuture(null);
        boolean railUpdated = false;

        CompletableFuture<EnergyConsumerResult[]> futureECRs = getMeasuredEnergyLocked(updateFlags);

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_WIFI) != 0) {
            // We were asked to fetch WiFi data.
            // Only fetch WiFi power data if it is supported.
            if (mWifiManager != null && mWifiManager.isEnhancedPowerReportingSupported()) {
                SynchronousResultReceiver tempWifiReceiver = new SynchronousResultReceiver("wifi");
                mWifiManager.getWifiActivityEnergyInfoAsync(
                        new Executor() {
                            @Override
                            public void execute(Runnable runnable) {
                                // run the listener on the binder thread, if it was run on the main
                                // thread it would deadlock since we would be waiting on ourselves
                                runnable.run();
                            }
                        },
                        info -> {
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                            tempWifiReceiver.send(0, bundle);
                        }
                );
                wifiReceiver = tempWifiReceiver;
            }
            synchronized (mStats) {
                mStats.updateRailStatsLocked();
            }
            railUpdated = true;
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_BT) != 0) {
            // We were asked to fetch Bluetooth data.
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                SynchronousResultReceiver resultReceiver =
                        new SynchronousResultReceiver("bluetooth");
                adapter.requestControllerActivityEnergyInfo(
                        Runnable::run,
                        new BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback() {
                            @Override
                            public void onBluetoothActivityEnergyInfoAvailable(
                                    BluetoothActivityEnergyInfo info) {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                                resultReceiver.send(0, bundle);
                            }

                            @Override
                            public void onBluetoothActivityEnergyInfoError(int errorCode) {
                                Slog.w(TAG, "error reading Bluetooth stats: " + errorCode);
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(
                                        BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, null);
                                resultReceiver.send(0, bundle);
                            }
                        }
                );
                bluetoothReceiver = resultReceiver;
            }
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_RADIO) != 0) {
            // We were asked to fetch Telephony data.
            if (mTelephony != null) {
                CompletableFuture<ModemActivityInfo> temp = new CompletableFuture<>();
                mTelephony.requestModemActivityInfo(Runnable::run,
                        new OutcomeReceiver<ModemActivityInfo,
                                TelephonyManager.ModemActivityInfoException>() {
                            @Override
                            public void onResult(ModemActivityInfo result) {
                                temp.complete(result);
                            }

                            @Override
                            public void onError(TelephonyManager.ModemActivityInfoException e) {
                                Slog.w(TAG, "error reading modem stats:" + e);
                                temp.complete(null);
                            }
                        });
                modemFuture = temp;
            }
            if (!railUpdated) {
                synchronized (mStats) {
                    mStats.updateRailStatsLocked();
                }
            }
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_RPM) != 0) {
            // Collect the latest low power stats without holding the mStats lock.
            mStats.fillLowPowerStats();
        }

        final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
        final BluetoothActivityEnergyInfo bluetoothInfo = awaitControllerInfo(bluetoothReceiver);
        ModemActivityInfo modemInfo = null;
        try {
            modemInfo = modemFuture.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            Slog.w(TAG, "timeout or interrupt reading modem stats: " + e);
        } catch (ExecutionException e) {
            Slog.w(TAG, "exception reading modem stats: " + e.getCause());
        }

        final MeasuredEnergySnapshot.MeasuredEnergyDeltaData measuredEnergyDeltas;
        if (mMeasuredEnergySnapshot == null || futureECRs == null) {
            measuredEnergyDeltas = null;
        } else {
            final int voltageMv;
            synchronized (mStats) {
                voltageMv = mStats.getBatteryVoltageMvLocked();
            }

            EnergyConsumerResult[] ecrs;
            try {
                ecrs = futureECRs.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | InterruptedException e) {
                // TODO (b/180519623): Invalidate the MeasuredEnergy derived data until next reset.
                Slog.w(TAG, "timeout or interrupt reading getEnergyConsumedAsync: " + e);
                ecrs = null;
            } catch (ExecutionException e) {
                Slog.wtf(TAG, "exception reading getEnergyConsumedAsync: " + e.getCause());
                ecrs = null;
            }

            measuredEnergyDeltas = mMeasuredEnergySnapshot.updateAndGetDelta(ecrs, voltageMv);
        }

        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long uptime = SystemClock.uptimeMillis();
        final long elapsedRealtimeUs = elapsedRealtime * 1000;
        final long uptimeUs = uptime * 1000;

        // Now that we have finally received all the data, we can tell mStats about it.
        synchronized (mStats) {
            mStats.addHistoryEventLocked(
                    elapsedRealtime,
                    uptime,
                    BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS,
                    reason, 0);

            if ((updateFlags & UPDATE_CPU) != 0) {
                if (useLatestStates) {
                    onBattery = mStats.isOnBatteryLocked();
                    onBatteryScreenOff = mStats.isOnBatteryScreenOffLocked();
                }

                final long[] cpuClusterChargeUC;
                if (measuredEnergyDeltas == null) {
                    cpuClusterChargeUC = null;
                } else {
                    cpuClusterChargeUC = measuredEnergyDeltas.cpuClusterChargeUC;
                }
                mStats.updateCpuTimeLocked(onBattery, onBatteryScreenOff, cpuClusterChargeUC);
            }

            if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                mStats.updateKernelWakelocksLocked(elapsedRealtimeUs);
                mStats.updateKernelMemoryBandwidthLocked(elapsedRealtimeUs);
            }

            if ((updateFlags & UPDATE_RPM) != 0) {
                mStats.updateRpmStatsLocked(elapsedRealtimeUs);
            }

            // Inform mStats about each applicable measured energy (unless addressed elsewhere).
            if (measuredEnergyDeltas != null) {
                final long[] displayChargeUC = measuredEnergyDeltas.displayChargeUC;
                if (displayChargeUC != null && displayChargeUC.length > 0) {
                    // If updating, pass in what BatteryExternalStatsWorker thinks
                    // displayScreenStates is.
                    mStats.updateDisplayMeasuredEnergyStatsLocked(displayChargeUC,
                            displayScreenStates, elapsedRealtime);
                }

                final long gnssChargeUC = measuredEnergyDeltas.gnssChargeUC;
                if (gnssChargeUC != MeasuredEnergySnapshot.UNAVAILABLE) {
                    mStats.updateGnssMeasuredEnergyStatsLocked(gnssChargeUC, elapsedRealtime);
                }
            }
            // Inform mStats about each applicable custom energy bucket.
            if (measuredEnergyDeltas != null
                    && measuredEnergyDeltas.otherTotalChargeUC != null) {
                // Iterate over the custom (EnergyConsumerType.OTHER) ordinals.
                for (int ord = 0; ord < measuredEnergyDeltas.otherTotalChargeUC.length; ord++) {
                    long totalEnergy = measuredEnergyDeltas.otherTotalChargeUC[ord];
                    SparseLongArray uidEnergies = measuredEnergyDeltas.otherUidChargesUC[ord];
                    mStats.updateCustomMeasuredEnergyStatsLocked(ord, totalEnergy, uidEnergies);
                }
            }

            if (bluetoothInfo != null) {
                if (bluetoothInfo.isValid()) {
                    final long btChargeUC = measuredEnergyDeltas != null
                            ? measuredEnergyDeltas.bluetoothChargeUC
                            : MeasuredEnergySnapshot.UNAVAILABLE;
                    mStats.updateBluetoothStateLocked(bluetoothInfo,
                            btChargeUC, elapsedRealtime, uptime);
                } else {
                    Slog.w(TAG, "bluetooth info is invalid: " + bluetoothInfo);
                }
            }
        }

        // WiFi and Modem state are updated without the mStats lock held, because they
        // do some network stats retrieval before internally grabbing the mStats lock.

        if (wifiInfo != null) {
            if (wifiInfo.isValid()) {
                final long wifiChargeUC = measuredEnergyDeltas != null ?
                        measuredEnergyDeltas.wifiChargeUC : MeasuredEnergySnapshot.UNAVAILABLE;
                final NetworkStatsManager networkStatsManager = mInjector.getSystemService(
                        NetworkStatsManager.class);
                mStats.updateWifiState(extractDeltaLocked(wifiInfo),
                        wifiChargeUC, elapsedRealtime, uptime, networkStatsManager);
            } else {
                Slog.w(TAG, "wifi info is invalid: " + wifiInfo);
            }
        }

        if (modemInfo != null) {
            final long mobileRadioChargeUC = measuredEnergyDeltas != null
                    ? measuredEnergyDeltas.mobileRadioChargeUC : MeasuredEnergySnapshot.UNAVAILABLE;
            final NetworkStatsManager networkStatsManager = mInjector.getSystemService(
                    NetworkStatsManager.class);
            mStats.noteModemControllerActivity(modemInfo, mobileRadioChargeUC, elapsedRealtime,
                    uptime, networkStatsManager);
        }

        if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
            // This helps mStats deal with ignoring data from prior to resets.
            mStats.informThatAllExternalStatsAreFlushed();
        }
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(
                        BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    @GuardedBy("mWorkerLock")
    private WifiActivityEnergyInfo extractDeltaLocked(WifiActivityEnergyInfo latest) {
        final long timePeriodMs = latest.getTimeSinceBootMillis()
                - mLastWifiInfo.getTimeSinceBootMillis();
        final long lastScanMs = mLastWifiInfo.getControllerScanDurationMillis();
        final long lastIdleMs = mLastWifiInfo.getControllerIdleDurationMillis();
        final long lastTxMs = mLastWifiInfo.getControllerTxDurationMillis();
        final long lastRxMs = mLastWifiInfo.getControllerRxDurationMillis();
        final long lastEnergy = mLastWifiInfo.getControllerEnergyUsedMicroJoules();

        final long deltaTimeSinceBootMillis = latest.getTimeSinceBootMillis();
        final int deltaStackState = latest.getStackState();
        final long deltaControllerTxDurationMillis;
        final long deltaControllerRxDurationMillis;
        final long deltaControllerScanDurationMillis;
        final long deltaControllerIdleDurationMillis;
        final long deltaControllerEnergyUsedMicroJoules;

        final long txTimeMs = latest.getControllerTxDurationMillis() - lastTxMs;
        final long rxTimeMs = latest.getControllerRxDurationMillis() - lastRxMs;
        final long idleTimeMs = latest.getControllerIdleDurationMillis() - lastIdleMs;
        final long scanTimeMs = latest.getControllerScanDurationMillis() - lastScanMs;

        final boolean wasReset;
        if (txTimeMs < 0 || rxTimeMs < 0 || scanTimeMs < 0 || idleTimeMs < 0) {
            // The stats were reset by the WiFi system (which is why our delta is negative).
            // Returns the unaltered stats. The total on time should not exceed the time
            // duration between reports.
            final long totalOnTimeMs = latest.getControllerTxDurationMillis()
                    + latest.getControllerRxDurationMillis()
                    + latest.getControllerIdleDurationMillis();
            if (totalOnTimeMs <= timePeriodMs + MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS) {
                deltaControllerEnergyUsedMicroJoules = latest.getControllerEnergyUsedMicroJoules();
                deltaControllerRxDurationMillis = latest.getControllerRxDurationMillis();
                deltaControllerTxDurationMillis = latest.getControllerTxDurationMillis();
                deltaControllerIdleDurationMillis = latest.getControllerIdleDurationMillis();
                deltaControllerScanDurationMillis = latest.getControllerScanDurationMillis();
            } else {
                deltaControllerEnergyUsedMicroJoules = 0;
                deltaControllerRxDurationMillis = 0;
                deltaControllerTxDurationMillis = 0;
                deltaControllerIdleDurationMillis = 0;
                deltaControllerScanDurationMillis = 0;
            }
            wasReset = true;
        } else {
            // These times seem to be the most reliable.
            deltaControllerTxDurationMillis = txTimeMs;
            deltaControllerRxDurationMillis = rxTimeMs;
            deltaControllerScanDurationMillis = scanTimeMs;
            deltaControllerIdleDurationMillis = idleTimeMs;
            deltaControllerEnergyUsedMicroJoules =
                    Math.max(0, latest.getControllerEnergyUsedMicroJoules() - lastEnergy);
            wasReset = false;
        }

        mLastWifiInfo = latest;
        WifiActivityEnergyInfo delta = new WifiActivityEnergyInfo(
                deltaTimeSinceBootMillis,
                deltaStackState,
                deltaControllerTxDurationMillis,
                deltaControllerRxDurationMillis,
                deltaControllerScanDurationMillis,
                deltaControllerIdleDurationMillis,
                deltaControllerEnergyUsedMicroJoules);
        if (wasReset) {
            Slog.v(TAG, "WiFi energy data was reset, new WiFi energy data is " + delta);
        }
        return delta;
    }

    /**
     * Map the {@link EnergyConsumerType}s in the given energyArray to
     * their corresponding {@link MeasuredEnergyStats.StandardPowerBucket}s.
     * Does not include custom energy buckets (which are always, by definition, supported).
     *
     * @return array with true for index i if standard energy bucket i is supported.
     */
    private static @Nullable boolean[] getSupportedEnergyBuckets(
            SparseArray<EnergyConsumer> idToConsumer) {
        if (idToConsumer == null) {
            return null;
        }
        final boolean[] buckets = new boolean[MeasuredEnergyStats.NUMBER_STANDARD_POWER_BUCKETS];
        final int size = idToConsumer.size();
        for (int idx = 0; idx < size; idx++) {
            final EnergyConsumer consumer = idToConsumer.valueAt(idx);
            switch (consumer.type) {
                case EnergyConsumerType.BLUETOOTH:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH] = true;
                    break;
                case EnergyConsumerType.CPU_CLUSTER:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_CPU] = true;
                    break;
                case EnergyConsumerType.GNSS:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_GNSS] = true;
                    break;
                case EnergyConsumerType.MOBILE_RADIO:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO] = true;
                    buckets[MeasuredEnergyStats.POWER_BUCKET_PHONE] = true;
                    break;
                case EnergyConsumerType.DISPLAY:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON] = true;
                    buckets[MeasuredEnergyStats.POWER_BUCKET_SCREEN_DOZE] = true;
                    buckets[MeasuredEnergyStats.POWER_BUCKET_SCREEN_OTHER] = true;
                    break;
                case EnergyConsumerType.WIFI:
                    buckets[MeasuredEnergyStats.POWER_BUCKET_WIFI] = true;
                    break;
            }
        }
        return buckets;
    }

    /** Get all {@link EnergyConsumerResult}s with the latest energy usage since boot. */
    @GuardedBy("mWorkerLock")
    @Nullable
    private CompletableFuture<EnergyConsumerResult[]> getEnergyConsumptionData() {
        return getEnergyConsumptionData(new int[0]);
    }

    /**
     * Get {@link EnergyConsumerResult}s of the specified {@link EnergyConsumer} ids with the latest
     * energy usage since boot.
     */
    @GuardedBy("mWorkerLock")
    @Nullable
    private CompletableFuture<EnergyConsumerResult[]> getEnergyConsumptionData(int[] consumerIds) {
        return mPowerStatsInternal.getEnergyConsumedAsync(consumerIds);
    }

    /** Fetch EnergyConsumerResult[] for supported subsystems based on the given updateFlags. */
    @VisibleForTesting
    @GuardedBy("mWorkerLock")
    @Nullable
    public CompletableFuture<EnergyConsumerResult[]> getMeasuredEnergyLocked(
            @ExternalUpdateFlag int flags) {
        if (mMeasuredEnergySnapshot == null || mPowerStatsInternal == null) return null;

        if (flags == UPDATE_ALL) {
            // Gotta catch 'em all... including custom (non-specific) subsystems
            return getEnergyConsumptionData();
        }

        final IntArray energyConsumerIds = new IntArray();
        if ((flags & UPDATE_BT) != 0) {
            addEnergyConsumerIdLocked(energyConsumerIds, EnergyConsumerType.BLUETOOTH);
        }
        if ((flags & UPDATE_CPU) != 0) {
            addEnergyConsumerIdLocked(energyConsumerIds, EnergyConsumerType.CPU_CLUSTER);
        }
        if ((flags & UPDATE_DISPLAY) != 0) {
            addEnergyConsumerIdLocked(energyConsumerIds, EnergyConsumerType.DISPLAY);
        }
        if ((flags & UPDATE_RADIO) != 0) {
            addEnergyConsumerIdLocked(energyConsumerIds, EnergyConsumerType.MOBILE_RADIO);
        }
        if ((flags & UPDATE_WIFI) != 0) {
            addEnergyConsumerIdLocked(energyConsumerIds, EnergyConsumerType.WIFI);
        }

        if (energyConsumerIds.size() == 0) {
            return null;
        }
        return getEnergyConsumptionData(energyConsumerIds.toArray());
    }

    @GuardedBy("mWorkerLock")
    private void addEnergyConsumerIdLocked(
            IntArray energyConsumerIds, @EnergyConsumerType int type) {
        final int[] consumerIds = mEnergyConsumerTypeToIdMap.get(type);
        if (consumerIds == null) return;
        energyConsumerIds.addAll(consumerIds);
    }

    /** Populates the cached type->ids map, and returns the (inverse) id->EnergyConsumer map. */
    @GuardedBy("mWorkerLock")
    private @Nullable SparseArray<EnergyConsumer> populateEnergyConsumerSubsystemMapsLocked() {
        if (mPowerStatsInternal == null) {
            return null;
        }
        final EnergyConsumer[] energyConsumers = mPowerStatsInternal.getEnergyConsumerInfo();
        if (energyConsumers == null || energyConsumers.length == 0) {
            return null;
        }

        // Maps id -> EnergyConsumer (1:1 map)
        final SparseArray<EnergyConsumer> idToConsumer = new SparseArray<>(energyConsumers.length);
        // Maps type -> {ids} (1:n map, since multiple ids might have the same type)
        final SparseArray<IntArray> tempTypeToId = new SparseArray<>();

        // Add all expected EnergyConsumers to the maps
        for (final EnergyConsumer consumer : energyConsumers) {
            // Check for inappropriate ordinals
            if (consumer.ordinal != 0) {
                switch (consumer.type) {
                    case EnergyConsumerType.OTHER:
                    case EnergyConsumerType.CPU_CLUSTER:
                    case EnergyConsumerType.DISPLAY:
                        break;
                    default:
                        Slog.w(TAG, "EnergyConsumer '" + consumer.name + "' has unexpected ordinal "
                                + consumer.ordinal + " for type " + consumer.type);
                        continue; // Ignore this consumer
                }
            }
            idToConsumer.put(consumer.id, consumer);

            IntArray ids = tempTypeToId.get(consumer.type);
            if (ids == null) {
                ids = new IntArray();
                tempTypeToId.put(consumer.type, ids);
            }
            ids.add(consumer.id);
        }

        mEnergyConsumerTypeToIdMap = new SparseArray<>(tempTypeToId.size());
        // Populate mEnergyConsumerTypeToIdMap with EnergyConsumer type to ids mappings
        final int size = tempTypeToId.size();
        for (int i = 0; i < size; i++) {
            final int consumerType = tempTypeToId.keyAt(i);
            final int[] consumerIds = tempTypeToId.valueAt(i).toArray();
            mEnergyConsumerTypeToIdMap.put(consumerType, consumerIds);
        }
        return idToConsumer;
    }
}
