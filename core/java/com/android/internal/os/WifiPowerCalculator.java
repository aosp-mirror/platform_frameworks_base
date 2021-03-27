/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.os;

import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

/**
 * WiFi power calculator for when BatteryStats supports energy reporting
 * from the WiFi controller.
 */
public class WifiPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "WifiPowerCalculator";
    private final UsageBasedPowerEstimator mIdlePowerEstimator;
    private final UsageBasedPowerEstimator mTxPowerEstimator;
    private final UsageBasedPowerEstimator mRxPowerEstimator;
    private final UsageBasedPowerEstimator mPowerOnPowerEstimator;
    private final UsageBasedPowerEstimator mScanPowerEstimator;
    private final UsageBasedPowerEstimator mBatchScanPowerEstimator;
    private final boolean mHasWifiPowerController;
    private final double mWifiPowerPerPacket;

    private static class PowerDurationAndTraffic {
        public double powerMah;
        public long durationMs;

        public long wifiRxPackets;
        public long wifiTxPackets;
        public long wifiRxBytes;
        public long wifiTxBytes;
    }

    public WifiPowerCalculator(PowerProfile profile) {
        mPowerOnPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_ON));
        mScanPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_SCAN));
        mBatchScanPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN));
        mIdlePowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE));
        mTxPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX));
        mRxPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX));

        mWifiPowerPerPacket = getWifiPowerPerPacket(profile);

        mHasWifiPowerController =
                mIdlePowerEstimator.isSupported() && mTxPowerEstimator.isSupported()
                        && mRxPowerEstimator.isSupported();
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {

        final SystemBatteryConsumer.Builder systemBatteryConsumerBuilder =
                builder.getOrCreateSystemBatteryConsumerBuilder(
                        SystemBatteryConsumer.DRAIN_TYPE_WIFI);

        long totalAppDurationMs = 0;
        double totalAppPowerMah = 0;
        final PowerDurationAndTraffic powerDurationAndTraffic = new PowerDurationAndTraffic();
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(powerDurationAndTraffic, app.getBatteryStatsUid(), rawRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED,
                    batteryStats.hasWifiActivityReporting(),
                    query.shouldForceUsePowerProfileModel());

            totalAppDurationMs += powerDurationAndTraffic.durationMs;
            totalAppPowerMah += powerDurationAndTraffic.powerMah;

            app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_WIFI,
                    powerDurationAndTraffic.durationMs);
            app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI,
                    powerDurationAndTraffic.powerMah);

            if (app.getUid() == Process.WIFI_UID) {
                systemBatteryConsumerBuilder.addUidBatteryConsumer(app);
                app.excludeFromBatteryUsageStats();
            }
        }

        calculateRemaining(powerDurationAndTraffic, batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED,
                batteryStats.hasWifiActivityReporting(),
                query.shouldForceUsePowerProfileModel(),
                totalAppDurationMs, totalAppPowerMah);

        systemBatteryConsumerBuilder
                .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_WIFI,
                        powerDurationAndTraffic.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI,
                        totalAppPowerMah + powerDurationAndTraffic.powerMah)
                .setPowerConsumedByApps(totalAppPowerMah);
    }

    /**
     * We do per-app blaming of WiFi activity. If energy info is reported from the controller,
     * then only the WiFi process gets blamed here since we normalize power calculations and
     * assign all the power drain to apps. If energy info is not reported, we attribute the
     * difference between total running time of WiFi for all apps and the actual running time
     * of WiFi to the WiFi subsystem.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {

        final BatterySipper bs = new BatterySipper(BatterySipper.DrainType.WIFI, null, 0);

        long totalAppDurationMs = 0;
        double totalAppPowerMah = 0;
        final PowerDurationAndTraffic powerDurationAndTraffic = new PowerDurationAndTraffic();
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                calculateApp(powerDurationAndTraffic, app.uidObj, rawRealtimeUs, statsType,
                        batteryStats.hasWifiActivityReporting(), /* force use power model*/ false);

                totalAppDurationMs += powerDurationAndTraffic.durationMs;
                totalAppPowerMah += powerDurationAndTraffic.powerMah;

                app.wifiPowerMah = powerDurationAndTraffic.powerMah;
                app.wifiRunningTimeMs = powerDurationAndTraffic.durationMs;
                app.wifiRxBytes = powerDurationAndTraffic.wifiRxBytes;
                app.wifiRxPackets = powerDurationAndTraffic.wifiRxPackets;
                app.wifiTxBytes = powerDurationAndTraffic.wifiTxBytes;
                app.wifiTxPackets = powerDurationAndTraffic.wifiTxPackets;
                if (app.getUid() == Process.WIFI_UID) {
                    if (DEBUG) Log.d(TAG, "WiFi adding sipper " + app + ": cpu=" + app.cpuTimeMs);
                    app.isAggregated = true;
                    bs.add(app);
                }
            }
        }

        calculateRemaining(powerDurationAndTraffic, batteryStats, rawRealtimeUs, statsType,
                batteryStats.hasWifiActivityReporting(), /* force use power model*/ false,
                totalAppDurationMs, totalAppPowerMah);

        bs.wifiRunningTimeMs += powerDurationAndTraffic.durationMs;
        bs.wifiPowerMah += powerDurationAndTraffic.powerMah;

        if (bs.sumPower() > 0) {
            sippers.add(bs);
        }
    }

    private void calculateApp(PowerDurationAndTraffic powerDurationAndTraffic, BatteryStats.Uid u,
            long rawRealtimeUs, int statsType,
            boolean hasWifiActivityReporting, boolean shouldForceUsePowerProfileModel) {

        powerDurationAndTraffic.wifiRxPackets = u.getNetworkActivityPackets(
                BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        powerDurationAndTraffic.wifiTxPackets = u.getNetworkActivityPackets(
                BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        powerDurationAndTraffic.wifiRxBytes = u.getNetworkActivityBytes(
                BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        powerDurationAndTraffic.wifiTxBytes = u.getNetworkActivityBytes(
                BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);

        final long measuredChargeUC = u.getWifiMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable
                = !shouldForceUsePowerProfileModel && measuredChargeUC != POWER_DATA_UNAVAILABLE;
        if (isMeasuredPowerAvailable) {
            powerDurationAndTraffic.powerMah = uCtoMah(measuredChargeUC);
        }

        if (hasWifiActivityReporting && mHasWifiPowerController) {
            final BatteryStats.ControllerActivityCounter counter = u.getWifiControllerActivity();
            if (counter != null) {
                final long idleTime = counter.getIdleTimeCounter().getCountLocked(statsType);
                final long txTime = counter.getTxTimeCounters()[0].getCountLocked(statsType);
                final long rxTime = counter.getRxTimeCounter().getCountLocked(statsType);

                powerDurationAndTraffic.durationMs = idleTime + rxTime + txTime;
                if (!isMeasuredPowerAvailable) {
                    powerDurationAndTraffic.powerMah
                            = calcPowerFromControllerDataMah(rxTime, txTime, idleTime);
                }

                if (DEBUG && powerDurationAndTraffic.powerMah != 0) {
                    Log.d(TAG, "UID " + u.getUid() + ": idle=" + idleTime + "ms rx=" + rxTime
                            + "ms tx=" + txTime + "ms power=" + formatCharge(
                            powerDurationAndTraffic.powerMah));
                }
            }
        } else {
            final long wifiRunningTime = u.getWifiRunningTime(rawRealtimeUs, statsType) / 1000;
            powerDurationAndTraffic.durationMs = wifiRunningTime;

            if (!isMeasuredPowerAvailable) {
                final long wifiScanTimeMs = u.getWifiScanTime(rawRealtimeUs, statsType) / 1000;
                long batchTimeMs = 0;
                for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {
                    batchTimeMs += u.getWifiBatchedScanTime(bin, rawRealtimeUs, statsType) / 1000;
                }
                powerDurationAndTraffic.powerMah = calcPowerWithoutControllerDataMah(
                        powerDurationAndTraffic.wifiRxPackets,
                        powerDurationAndTraffic.wifiTxPackets,
                        wifiRunningTime, wifiScanTimeMs, batchTimeMs);
            }

            if (DEBUG && powerDurationAndTraffic.powerMah != 0) {
                Log.d(TAG, "UID " + u.getUid() + ": power=" + formatCharge(
                        powerDurationAndTraffic.powerMah));
            }
        }
    }

    private void calculateRemaining(PowerDurationAndTraffic powerDurationAndTraffic,
            BatteryStats stats, long rawRealtimeUs, int statsType,
            boolean hasWifiActivityReporting, boolean shouldForceUsePowerProfileModel,
            long totalAppDurationMs, double totalAppPowerMah) {

        long totalDurationMs;
        double totalPowerMah = 0;

        final long measuredChargeUC = stats.getWifiMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable
                = !shouldForceUsePowerProfileModel && measuredChargeUC != POWER_DATA_UNAVAILABLE;
        if (isMeasuredPowerAvailable) {
            totalPowerMah = uCtoMah(measuredChargeUC);
        }
        if (hasWifiActivityReporting && mHasWifiPowerController) {
            final BatteryStats.ControllerActivityCounter counter =
                    stats.getWifiControllerActivity();

            final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
            final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
            final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);

            totalDurationMs = idleTimeMs + rxTimeMs + txTimeMs;

            if (!isMeasuredPowerAvailable) {
                totalPowerMah = counter.getPowerCounter().getCountLocked(statsType)
                        / (double) (1000 * 60 * 60);
                if (totalPowerMah == 0) {
                    // Some controllers do not report power drain, so we can calculate it here.
                    totalPowerMah = calcPowerFromControllerDataMah(rxTimeMs, txTimeMs, idleTimeMs);
                }
            }
        } else {
            totalDurationMs = stats.getGlobalWifiRunningTime(rawRealtimeUs, statsType) / 1000;
            if (!isMeasuredPowerAvailable) {
                totalPowerMah = calcGlobalPowerWithoutControllerDataMah(totalDurationMs);
            }
        }

        powerDurationAndTraffic.durationMs = Math.max(0, totalDurationMs - totalAppDurationMs);
        powerDurationAndTraffic.powerMah = Math.max(0, totalPowerMah - totalAppPowerMah);

        if (DEBUG) {
            Log.d(TAG, "left over WiFi power: " + formatCharge(powerDurationAndTraffic.powerMah));
        }
    }

    /** Returns (global or uid) estimated wifi power used using WifiControllerActivity data. */
    public double calcPowerFromControllerDataMah(long rxTimeMs, long txTimeMs, long idleTimeMs) {
        return mRxPowerEstimator.calculatePower(rxTimeMs)
                + mTxPowerEstimator.calculatePower(txTimeMs)
                + mIdlePowerEstimator.calculatePower(idleTimeMs);
    }

    /** Returns per-uid estimated wifi power used using non-WifiControllerActivity data. */
    public double calcPowerWithoutControllerDataMah(long rxPackets, long txPackets,
            long wifiRunningTimeMs, long wifiScanTimeMs, long wifiBatchScanTimeMs) {
        return
                (rxPackets + txPackets) * mWifiPowerPerPacket
                + mPowerOnPowerEstimator.calculatePower(wifiRunningTimeMs)
                + mScanPowerEstimator.calculatePower(wifiScanTimeMs)
                + mBatchScanPowerEstimator.calculatePower(wifiBatchScanTimeMs);

    }

    /** Returns global estimated wifi power used using non-WifiControllerActivity data. */
    public double calcGlobalPowerWithoutControllerDataMah(long globalWifiRunningTimeMs) {
        return mPowerOnPowerEstimator.calculatePower(globalWifiRunningTimeMs);
    }

    /**
     * Return estimated power per Wi-Fi packet in mAh/packet where 1 packet = 2 KB.
     */
    private static double getWifiPowerPerPacket(PowerProfile profile) {
        // TODO(b/179392913): Extract average bit rates from system
        final long wifiBps = 1000000;
        final double averageWifiActivePower =
                profile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE) / 3600;
        return averageWifiActivePower / (((double) wifiBps) / 8 / 2048);
    }
}
