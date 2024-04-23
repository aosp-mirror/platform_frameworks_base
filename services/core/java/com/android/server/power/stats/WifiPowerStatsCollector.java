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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class WifiPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "WifiPowerStatsCollector";

    private static final long WIFI_ACTIVITY_REQUEST_TIMEOUT = 20000;

    private static final long ENERGY_UNSPECIFIED = -1;

    interface WifiStatsRetriever {
        interface Callback {
            void onWifiScanTime(int uid, long scanTimeMs, long batchScanTimeMs);
        }

        void retrieveWifiScanTimes(Callback callback);
        long getWifiActiveDuration();
    }

    interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        PackageManager getPackageManager();
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        IntSupplier getVoltageSupplier();
        Supplier<NetworkStats> getWifiNetworkStatsSupplier();
        WifiManager getWifiManager();
        WifiStatsRetriever getWifiStatsRetriever();
    }

    private final Injector mInjector;

    private WifiPowerStatsLayout mLayout;
    private boolean mIsInitialized;
    private boolean mPowerReportingSupported;

    private PowerStats mPowerStats;
    private long[] mDeviceStats;
    private volatile WifiManager mWifiManager;
    private volatile Supplier<NetworkStats> mNetworkStatsSupplier;
    private volatile WifiStatsRetriever mWifiStatsRetriever;
    private ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private IntSupplier mVoltageSupplier;
    private int[] mEnergyConsumerIds = new int[0];
    private WifiActivityEnergyInfo mLastWifiActivityInfo =
            new WifiActivityEnergyInfo(0, 0, 0, 0, 0, 0);
    private NetworkStats mLastNetworkStats;
    private long[] mLastConsumedEnergyUws;
    private int mLastVoltageMv;

    private static class WifiScanTimes {
        public long basicScanTimeMs;
        public long batchedScanTimeMs;
    }
    private final WifiScanTimes mScanTimes = new WifiScanTimes();
    private final SparseArray<WifiScanTimes> mLastScanTimes = new SparseArray<>();
    private long mLastWifiActiveDuration;

    public WifiPowerStatsCollector(Injector injector, long throttlePeriodMs) {
        super(injector.getHandler(), throttlePeriodMs, injector.getUidResolver(),
                injector.getClock());
        mInjector = injector;
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

        mConsumedEnergyRetriever = mInjector.getConsumedEnergyRetriever();
        mVoltageSupplier = mInjector.getVoltageSupplier();
        mWifiManager = mInjector.getWifiManager();
        mNetworkStatsSupplier = mInjector.getWifiNetworkStatsSupplier();
        mWifiStatsRetriever = mInjector.getWifiStatsRetriever();
        mPowerReportingSupported =
                mWifiManager != null && mWifiManager.isEnhancedPowerReportingSupported();

        mEnergyConsumerIds = mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.WIFI);
        mLastConsumedEnergyUws = new long[mEnergyConsumerIds.length];
        Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);

        mLayout = new WifiPowerStatsLayout();
        mLayout.addDeviceWifiActivity(mPowerReportingSupported);
        mLayout.addDeviceSectionEnergyConsumers(mEnergyConsumerIds.length);
        mLayout.addUidNetworkStats();
        mLayout.addDeviceSectionUsageDuration();
        mLayout.addDeviceSectionPowerEstimate();
        mLayout.addUidSectionPowerEstimate();

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
    protected PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        if (mPowerReportingSupported) {
            collectWifiActivityInfo();
        } else {
            collectWifiActivityStats();
        }
        collectNetworkStats();
        collectWifiScanTime();

        if (mEnergyConsumerIds.length != 0) {
            collectEnergyConsumers();
        }

        return mPowerStats;
    }

    private void collectWifiActivityInfo() {
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
            return;
        }

        long rxDuration = activityInfo.getControllerRxDurationMillis()
                - mLastWifiActivityInfo.getControllerRxDurationMillis();
        long txDuration = activityInfo.getControllerTxDurationMillis()
                - mLastWifiActivityInfo.getControllerTxDurationMillis();
        long scanDuration = activityInfo.getControllerScanDurationMillis()
                - mLastWifiActivityInfo.getControllerScanDurationMillis();
        long idleDuration = activityInfo.getControllerIdleDurationMillis()
                - mLastWifiActivityInfo.getControllerIdleDurationMillis();

        mLayout.setDeviceRxTime(mDeviceStats, rxDuration);
        mLayout.setDeviceTxTime(mDeviceStats, txDuration);
        mLayout.setDeviceScanTime(mDeviceStats, scanDuration);
        mLayout.setDeviceIdleTime(mDeviceStats, idleDuration);

        mPowerStats.durationMs = rxDuration + txDuration + scanDuration + idleDuration;

        mLastWifiActivityInfo = activityInfo;
    }

    private void collectWifiActivityStats() {
        long duration = mWifiStatsRetriever.getWifiActiveDuration();
        mLayout.setDeviceActiveTime(mDeviceStats, Math.max(0, duration - mLastWifiActiveDuration));
        mLastWifiActiveDuration = duration;
        mPowerStats.durationMs = duration;
    }

    private void collectNetworkStats() {
        mPowerStats.uidStats.clear();

        NetworkStats networkStats = mNetworkStatsSupplier.get();
        if (networkStats == null) {
            return;
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

    private void collectEnergyConsumers() {
        int voltageMv = mVoltageSupplier.getAsInt();
        if (voltageMv <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                    + " mV) when querying energy consumers");
            return;
        }

        int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
        mLastVoltageMv = voltageMv;

        long[] energyUws = mConsumedEnergyRetriever.getConsumedEnergyUws(mEnergyConsumerIds);
        if (energyUws == null) {
            return;
        }

        for (int i = energyUws.length - 1; i >= 0; i--) {
            long energyDelta = mLastConsumedEnergyUws[i] != ENERGY_UNSPECIFIED
                    ? energyUws[i] - mLastConsumedEnergyUws[i] : 0;
            if (energyDelta < 0) {
                // Likely, restart of powerstats HAL
                energyDelta = 0;
            }
            mLayout.setConsumedEnergy(mPowerStats.stats, i, uJtoUc(energyDelta, averageVoltage));
            mLastConsumedEnergyUws[i] = energyUws[i];
        }
    }

    @Override
    protected void onUidRemoved(int uid) {
        super.onUidRemoved(uid);
        mLastScanTimes.remove(uid);
    }
}
