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

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.Log;
import android.util.SparseArray;

import java.util.Arrays;

/**
 * WiFi power calculator for when BatteryStats supports energy reporting
 * from the WiFi controller.
 */
public class WifiPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = PowerCalculator.DEBUG;
    private static final String TAG = "WifiPowerCalculator";

    private static final BatteryConsumer.Key[] UNINITIALIZED_KEYS = new BatteryConsumer.Key[0];

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

        public BatteryConsumer.Key[] keys;
        public double[] powerPerKeyMah;
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
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_WIFI;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        BatteryConsumer.Key[] keys = UNINITIALIZED_KEYS;
        long totalAppDurationMs = 0;
        double totalAppPowerMah = 0;
        final PowerDurationAndTraffic powerDurationAndTraffic = new PowerDurationAndTraffic();
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (keys == UNINITIALIZED_KEYS) {
                if (query.isProcessStateDataNeeded()) {
                    keys = app.getKeys(BatteryConsumer.POWER_COMPONENT_WIFI);
                    powerDurationAndTraffic.keys = keys;
                    powerDurationAndTraffic.powerPerKeyMah = new double[keys.length];
                } else {
                    keys = null;
                }
            }

            final long consumptionUC =
                    app.getBatteryStatsUid().getWifiMeasuredBatteryConsumptionUC();
            final int powerModel = getPowerModel(consumptionUC, query);

            calculateApp(powerDurationAndTraffic, app.getBatteryStatsUid(), powerModel,
                    rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED,
                    batteryStats.hasWifiActivityReporting(), consumptionUC);
            if (!app.isVirtualUid()) {
                totalAppDurationMs += powerDurationAndTraffic.durationMs;
                totalAppPowerMah += powerDurationAndTraffic.powerMah;
            }

            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI,
                    powerDurationAndTraffic.durationMs);
            app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI,
                    powerDurationAndTraffic.powerMah, powerModel);

            if (query.isProcessStateDataNeeded() && keys != null) {
                for (int j = 0; j < keys.length; j++) {
                    BatteryConsumer.Key key = keys[j];
                    final int processState = key.processState;
                    if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                        // Already populated with the total across all process states
                        continue;
                    }

                    app.setConsumedPower(key, powerDurationAndTraffic.powerPerKeyMah[j],
                            powerModel);
                }
            }
        }

        final long consumptionUC = batteryStats.getWifiMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);
        calculateRemaining(powerDurationAndTraffic, powerModel, batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED, batteryStats.hasWifiActivityReporting(),
                totalAppDurationMs, totalAppPowerMah, consumptionUC);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI,
                        powerDurationAndTraffic.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI,
                        totalAppPowerMah + powerDurationAndTraffic.powerMah, powerModel);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI,
                        totalAppPowerMah, powerModel);
    }

    private void calculateApp(PowerDurationAndTraffic powerDurationAndTraffic,
            BatteryStats.Uid u, @BatteryConsumer.PowerModel int powerModel,
            long rawRealtimeUs, int statsType, boolean hasWifiActivityReporting,
            long consumptionUC) {

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

        if (hasWifiActivityReporting && mHasWifiPowerController) {
            final BatteryStats.ControllerActivityCounter counter = u.getWifiControllerActivity();
            if (counter != null) {
                final BatteryStats.LongCounter rxTimeCounter = counter.getRxTimeCounter();
                final BatteryStats.LongCounter txTimeCounter = counter.getTxTimeCounters()[0];
                final BatteryStats.LongCounter idleTimeCounter = counter.getIdleTimeCounter();

                final long rxTime = rxTimeCounter.getCountLocked(statsType);
                final long txTime = txTimeCounter.getCountLocked(statsType);
                final long idleTime = idleTimeCounter.getCountLocked(statsType);

                powerDurationAndTraffic.durationMs = idleTime + rxTime + txTime;
                if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                    powerDurationAndTraffic.powerMah
                            = calcPowerFromControllerDataMah(rxTime, txTime, idleTime);
                } else {
                    powerDurationAndTraffic.powerMah = uCtoMah(consumptionUC);
                }

                if (DEBUG && powerDurationAndTraffic.powerMah != 0) {
                    Log.d(TAG, "UID " + u.getUid() + ": idle=" + idleTime + "ms rx=" + rxTime
                            + "ms tx=" + txTime + "ms power=" + BatteryStats.formatCharge(
                            powerDurationAndTraffic.powerMah));
                }

                if (powerDurationAndTraffic.keys != null) {
                    for (int i = 0; i < powerDurationAndTraffic.keys.length; i++) {
                        final int processState = powerDurationAndTraffic.keys[i].processState;
                        if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                            continue;
                        }

                        if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                            powerDurationAndTraffic.powerPerKeyMah[i] =
                                    calcPowerFromControllerDataMah(
                                            rxTimeCounter.getCountForProcessState(processState),
                                            txTimeCounter.getCountForProcessState(processState),
                                            idleTimeCounter.getCountForProcessState(processState));
                        } else {
                            powerDurationAndTraffic.powerPerKeyMah[i] =
                                    uCtoMah(u.getWifiMeasuredBatteryConsumptionUC(processState));
                        }
                    }
                }
            } else {
                powerDurationAndTraffic.durationMs = 0;
                powerDurationAndTraffic.powerMah = 0;
                if (powerDurationAndTraffic.powerPerKeyMah != null) {
                    Arrays.fill(powerDurationAndTraffic.powerPerKeyMah, 0);
                }
            }
        } else {
            final long wifiRunningTime = u.getWifiRunningTime(rawRealtimeUs, statsType) / 1000;
            powerDurationAndTraffic.durationMs = wifiRunningTime;

            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                final long wifiScanTimeMs = u.getWifiScanTime(rawRealtimeUs, statsType) / 1000;
                long batchTimeMs = 0;
                for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {
                    batchTimeMs += u.getWifiBatchedScanTime(bin, rawRealtimeUs, statsType) / 1000;
                }
                powerDurationAndTraffic.powerMah = calcPowerWithoutControllerDataMah(
                        powerDurationAndTraffic.wifiRxPackets,
                        powerDurationAndTraffic.wifiTxPackets,
                        wifiRunningTime, wifiScanTimeMs, batchTimeMs);
            } else {
                powerDurationAndTraffic.powerMah = uCtoMah(consumptionUC);
            }

            if (powerDurationAndTraffic.powerPerKeyMah != null) {
                // Per-process state attribution is not supported in the absence of WiFi
                // activity reporting
                Arrays.fill(powerDurationAndTraffic.powerPerKeyMah, 0);
            }

            if (DEBUG && powerDurationAndTraffic.powerMah != 0) {
                Log.d(TAG, "UID " + u.getUid() + ": power=" + BatteryStats.formatCharge(
                        powerDurationAndTraffic.powerMah));
            }
        }
    }

    private void calculateRemaining(PowerDurationAndTraffic powerDurationAndTraffic,
            @BatteryConsumer.PowerModel int powerModel, BatteryStats stats, long rawRealtimeUs,
            int statsType, boolean hasWifiActivityReporting, long totalAppDurationMs,
            double totalAppPowerMah, long consumptionUC) {

        long totalDurationMs;
        double totalPowerMah = 0;

        if (powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY) {
            totalPowerMah = uCtoMah(consumptionUC);
        }

        if (hasWifiActivityReporting && mHasWifiPowerController) {
            final BatteryStats.ControllerActivityCounter counter =
                    stats.getWifiControllerActivity();

            final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
            final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
            final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);

            totalDurationMs = idleTimeMs + rxTimeMs + txTimeMs;

            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                totalPowerMah = counter.getPowerCounter().getCountLocked(statsType)
                        / (double) (1000 * 60 * 60);
                if (totalPowerMah == 0) {
                    // Some controllers do not report power drain, so we can calculate it here.
                    totalPowerMah = calcPowerFromControllerDataMah(rxTimeMs, txTimeMs, idleTimeMs);
                }
            }
        } else {
            totalDurationMs = stats.getGlobalWifiRunningTime(rawRealtimeUs, statsType) / 1000;
            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                totalPowerMah = calcGlobalPowerWithoutControllerDataMah(totalDurationMs);
            }
        }

        powerDurationAndTraffic.durationMs = Math.max(0, totalDurationMs - totalAppDurationMs);
        powerDurationAndTraffic.powerMah = Math.max(0, totalPowerMah - totalAppPowerMah);

        if (DEBUG) {
            Log.d(TAG, "left over WiFi power: " + BatteryStats.formatCharge(
                    powerDurationAndTraffic.powerMah));
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
