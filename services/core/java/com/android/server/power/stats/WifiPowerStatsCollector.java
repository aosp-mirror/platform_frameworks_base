/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.power.stats;

import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.net.wifi.WifiManager;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.WifiPowerStatsLayout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class WifiPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "WifiPowerStatsCollector";

    private static final long WIFI_ACTIVITY_REQUEST_TIMEOUT = 20000;

    interface Observer {
        void onWifiPowerStatsRetrieved(WifiActivityEnergyInfo info,
                List<BatteryStatsImpl.NetworkStatsDelta> delta, long elapsedRealtimeMs,
                long uptimeMs);
    }

    public interface WifiStatsRetriever {
        interface Callback {
            void onWifiScanTime(int uid, long scanTimeMs, long batchScanTimeMs);
        }

        void retrieveWifiScanTimes(Callback callback);
        long getWifiActiveDuration();
    }

    public interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        PackageManager getPackageManager();
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        Supplier<NetworkStats> getWifiNetworkStatsSupplier();
        WifiManager getWifiManager();
        WifiStatsRetriever getWifiStatsRetriever();
    }

    private final Injector mInjector;
    private final Observer mObserver;

    private WifiPowerStatsLayout mLayout;
    private boolean mIsInitialized;
    private boolean mPowerReportingSupported;

    private PowerStats mPowerStats;
    private long[] mDeviceStats;
    private volatile WifiManager mWifiManager;
    private volatile Supplier<NetworkStats> mNetworkStatsSupplier;
    private volatile WifiStatsRetriever mWifiStatsRetriever;
    private ConsumedEnergyHelper mConsumedEnergyHelper;
    private WifiActivityEnergyInfo mLastWifiActivityInfo;
    private NetworkStats mLastNetworkStats;

    private static class WifiScanTimes {
        public long basicScanTimeMs;
        public long batchedScanTimeMs;
    }
    private final WifiScanTimes mScanTimes = new WifiScanTimes();
    private final SparseArray<WifiScanTimes> mLastScanTimes = new SparseArray<>();
    private long mLastWifiActiveDuration;

    public WifiPowerStatsCollector(Injector injector, Observer observer) {
        super(injector.getHandler(), injector.getPowerStatsCollectionThrottlePeriod(
                        BatteryConsumer.powerComponentIdToString(
                                BatteryConsumer.POWER_COMPONENT_WIFI)),
                injector.getUidResolver(), injector.getClock());
        mInjector = injector;
        mObserver = observer;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled) {
            PackageManager packageManager = mInjector.getPackageManager();
            super.setEnabled(packageManager != null
                    && packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI));
        } else {
            super.setEnabled(false);
        }
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mWifiManager = mInjector.getWifiManager();
        mNetworkStatsSupplier = mInjector.getWifiNetworkStatsSupplier();
        mWifiStatsRetriever = mInjector.getWifiStatsRetriever();
        mPowerReportingSupported =
                mWifiManager != null && mWifiManager.isEnhancedPowerReportingSupported();

        mConsumedEnergyHelper = new ConsumedEnergyHelper(mInjector.getConsumedEnergyRetriever(),
                EnergyConsumerType.WIFI);

        mLayout = new WifiPowerStatsLayout(mConsumedEnergyHelper.getEnergyConsumerCount(),
                mPowerReportingSupported);

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);
        PowerStats.Descriptor powerStatsDescriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_WIFI, mLayout.getDeviceStatsArrayLength(),
                null, 0, mLayout.getUidStatsArrayLength(),
                extras);
        mPowerStats = new PowerStats(powerStatsDescriptor);
        mDeviceStats = mPowerStats.stats;

        mIsInitialized = true;
        return true;
    }

    @Override
    public PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        WifiActivityEnergyInfo activityInfo = null;
        if (mPowerReportingSupported) {
            activityInfo = collectWifiActivityInfo();
        } else {
            collectWifiActivityStats();
        }
        List<BatteryStatsImpl.NetworkStatsDelta> networkStatsDeltas = collectNetworkStats();
        collectWifiScanTime();

        mConsumedEnergyHelper.collectConsumedEnergy(mPowerStats, mLayout);

        if (mObserver != null) {
            mObserver.onWifiPowerStatsRetrieved(activityInfo, networkStatsDeltas,
                    mClock.elapsedRealtime(), mClock.uptimeMillis());
        }
        return mPowerStats;
    }

    private WifiActivityEnergyInfo collectWifiActivityInfo() {
        CompletableFuture<WifiActivityEnergyInfo> immediateFuture = new CompletableFuture<>();
        mWifiManager.getWifiActivityEnergyInfoAsync(Runnable::run,
                immediateFuture::complete);

        WifiActivityEnergyInfo activityInfo;
        try {
            activityInfo = immediateFuture.get(WIFI_ACTIVITY_REQUEST_TIMEOUT,
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot acquire WifiActivityEnergyInfo", e);
            activityInfo = null;
        }

        if (activityInfo == null) {
            return null;
        }

        long rxDuration = 0;
        long txDuration = 0;
        long scanDuration = 0;
        long idleDuration = 0;

        if (mLastWifiActivityInfo != null) {
            rxDuration = activityInfo.getControllerRxDurationMillis()
                    - mLastWifiActivityInfo.getControllerRxDurationMillis();
            txDuration = activityInfo.getControllerTxDurationMillis()
                    - mLastWifiActivityInfo.getControllerTxDurationMillis();
            scanDuration = activityInfo.getControllerScanDurationMillis()
                    - mLastWifiActivityInfo.getControllerScanDurationMillis();
            idleDuration = activityInfo.getControllerIdleDurationMillis()
                    - mLastWifiActivityInfo.getControllerIdleDurationMillis();
        }

        mLayout.setDeviceRxTime(mDeviceStats, rxDuration);
        mLayout.setDeviceTxTime(mDeviceStats, txDuration);
        mLayout.setDeviceScanTime(mDeviceStats, scanDuration);
        mLayout.setDeviceIdleTime(mDeviceStats, idleDuration);

        mPowerStats.durationMs = rxDuration + txDuration + scanDuration + idleDuration;

        mLastWifiActivityInfo = activityInfo;

        return new WifiActivityEnergyInfo(activityInfo.getTimeSinceBootMillis(),
                activityInfo.getStackState(), txDuration, rxDuration, scanDuration, idleDuration);
    }

    private void collectWifiActivityStats() {
        long duration = mWifiStatsRetriever.getWifiActiveDuration();
        mLayout.setDeviceActiveTime(mDeviceStats, Math.max(0, duration - mLastWifiActiveDuration));
        mLastWifiActiveDuration = duration;
        mPowerStats.durationMs = duration;
    }

    private List<BatteryStatsImpl.NetworkStatsDelta> collectNetworkStats() {
        mPowerStats.uidStats.clear();

        NetworkStats networkStats = mNetworkStatsSupplier.get();
        if (networkStats == null) {
            return null;
        }

        List<BatteryStatsImpl.NetworkStatsDelta> delta =
                BatteryStatsImpl.computeDelta(networkStats, mLastNetworkStats);
        mLastNetworkStats = networkStats;
        for (int i = delta.size() - 1; i >= 0; i--) {
            BatteryStatsImpl.NetworkStatsDelta uidDelta = delta.get(i);
            long rxBytes = uidDelta.getRxBytes();
            long txBytes = uidDelta.getTxBytes();
            long rxPackets = uidDelta.getRxPackets();
            long txPackets = uidDelta.getTxPackets();
            if (rxBytes == 0 && txBytes == 0 && rxPackets == 0 && txPackets == 0) {
                continue;
            }

            int uid = mUidResolver.mapUid(uidDelta.getUid());
            long[] stats = mPowerStats.uidStats.get(uid);
            if (stats == null) {
                stats = new long[mLayout.getUidStatsArrayLength()];
                mPowerStats.uidStats.put(uid, stats);
                mLayout.setUidRxBytes(stats, rxBytes);
                mLayout.setUidTxBytes(stats, txBytes);
                mLayout.setUidRxPackets(stats, rxPackets);
                mLayout.setUidTxPackets(stats, txPackets);
            } else {
                mLayout.setUidRxBytes(stats, mLayout.getUidRxBytes(stats) + rxBytes);
                mLayout.setUidTxBytes(stats, mLayout.getUidTxBytes(stats) + txBytes);
                mLayout.setUidRxPackets(stats, mLayout.getUidRxPackets(stats) + rxPackets);
                mLayout.setUidTxPackets(stats, mLayout.getUidTxPackets(stats) + txPackets);
            }
        }
        return delta;
    }

    private void collectWifiScanTime() {
        mScanTimes.basicScanTimeMs = 0;
        mScanTimes.batchedScanTimeMs = 0;
        mWifiStatsRetriever.retrieveWifiScanTimes((uid, scanTimeMs, batchScanTimeMs) -> {
            WifiScanTimes lastScanTimes = mLastScanTimes.get(uid);
            if (lastScanTimes == null) {
                lastScanTimes = new WifiScanTimes();
                mLastScanTimes.put(uid, lastScanTimes);
            }

            long scanTimeDelta = Math.max(0, scanTimeMs - lastScanTimes.basicScanTimeMs);
            long batchScanTimeDelta = Math.max(0,
                    batchScanTimeMs - lastScanTimes.batchedScanTimeMs);
            if (scanTimeDelta != 0 || batchScanTimeDelta != 0) {
                mScanTimes.basicScanTimeMs += scanTimeDelta;
                mScanTimes.batchedScanTimeMs += batchScanTimeDelta;
                uid = mUidResolver.mapUid(uid);
                long[] stats = mPowerStats.uidStats.get(uid);
                if (stats == null) {
                    stats = new long[mLayout.getUidStatsArrayLength()];
                    mPowerStats.uidStats.put(uid, stats);
                    mLayout.setUidScanTime(stats, scanTimeDelta);
                    mLayout.setUidBatchScanTime(stats, batchScanTimeDelta);
                } else {
                    mLayout.setUidScanTime(stats, mLayout.getUidScanTime(stats) + scanTimeDelta);
                    mLayout.setUidBatchScanTime(stats,
                            mLayout.getUidBatchedScanTime(stats) + batchScanTimeDelta);
                }
            }
            lastScanTimes.basicScanTimeMs = scanTimeMs;
            lastScanTimes.batchedScanTimeMs = batchScanTimeMs;
        });

        mLayout.setDeviceBasicScanTime(mDeviceStats, mScanTimes.basicScanTimeMs);
        mLayout.setDeviceBatchedScanTime(mDeviceStats, mScanTimes.batchedScanTimeMs);
    }

    @Override
    protected void onUidRemoved(int uid) {
        super.onUidRemoved(uid);
        mLastScanTimes.remove(uid);
    }
}
