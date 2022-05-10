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
import android.telephony.CellSignalStrength;
import android.util.Log;
import android.util.SparseArray;

public class MobileRadioPowerCalculator extends PowerCalculator {
    private static final String TAG = "MobRadioPowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;

    private static final int NUM_SIGNAL_STRENGTH_LEVELS =
            CellSignalStrength.getNumSignalStrengthLevels();

    private static final BatteryConsumer.Key[] UNINITIALIZED_KEYS = new BatteryConsumer.Key[0];

    private final UsageBasedPowerEstimator mActivePowerEstimator;
    private final UsageBasedPowerEstimator[] mIdlePowerEstimators =
            new UsageBasedPowerEstimator[NUM_SIGNAL_STRENGTH_LEVELS];
    private final UsageBasedPowerEstimator mScanPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double remainingPowerMah;
        public long totalAppDurationMs;
        public double totalAppPowerMah;
        public long signalDurationMs;
        public long noCoverageDurationMs;
    }

    public MobileRadioPowerCalculator(PowerProfile profile) {
        // Power consumption when radio is active
        double powerRadioActiveMa =
                profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ACTIVE, -1);
        if (powerRadioActiveMa == -1) {
            double sum = 0;
            sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
            for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
            }
            powerRadioActiveMa = sum / (NUM_SIGNAL_STRENGTH_LEVELS + 1);
        }

        mActivePowerEstimator = new UsageBasedPowerEstimator(powerRadioActiveMa);

        // Power consumption when radio is on, but idle
        if (profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ON, -1) != -1) {
            for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                mIdlePowerEstimators[i] = new UsageBasedPowerEstimator(
                        profile.getAveragePower(PowerProfile.POWER_RADIO_ON, i));
            }
        } else {
            double idle = profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE);

            // Magical calculations preserved for historical compatibility
            mIdlePowerEstimators[0] = new UsageBasedPowerEstimator(idle * 25 / 180);
            for (int i = 1; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                mIdlePowerEstimators[i] = new UsageBasedPowerEstimator(Math.max(1, idle / 256));
            }
        }

        mScanPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_SCANNING, 0));
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {

        PowerAndDuration total = new PowerAndDuration();

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        BatteryConsumer.Key[] keys = UNINITIALIZED_KEYS;

        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            final BatteryStats.Uid uid = app.getBatteryStatsUid();
            if (keys == UNINITIALIZED_KEYS) {
                if (query.isProcessStateDataNeeded()) {
                    keys = app.getKeys(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);
                } else {
                    keys = null;
                }
            }

            calculateApp(app, uid, total, query, keys);
        }

        final long totalConsumptionUC = batteryStats.getMobileRadioMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(totalConsumptionUC, query);
        calculateRemaining(total, powerModel, batteryStats, rawRealtimeUs, totalConsumptionUC);

        if (total.remainingPowerMah != 0 || total.totalAppPowerMah != 0) {
            builder.getAggregateBatteryConsumerBuilder(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.remainingPowerMah + total.totalAppPowerMah, powerModel);

            builder.getAggregateBatteryConsumerBuilder(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.totalAppPowerMah, powerModel);
        }
    }

    private void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            PowerAndDuration total,
            BatteryUsageStatsQuery query, BatteryConsumer.Key[] keys) {
        final long radioActiveDurationMs = calculateDuration(u, BatteryStats.STATS_SINCE_CHARGED);
        final long consumptionUC = u.getMobileRadioMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);
        final double powerMah = calculatePower(u, powerModel, radioActiveDurationMs, consumptionUC);

        if (!app.isVirtualUid()) {
            total.totalAppDurationMs += radioActiveDurationMs;
            total.totalAppPowerMah += powerMah;
        }

        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                        radioActiveDurationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO, powerMah,
                        powerModel);

        if (query.isProcessStateDataNeeded() && keys != null) {
            for (BatteryConsumer.Key key: keys) {
                final int processState = key.processState;
                if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                    // Already populated with the total across all process states
                    continue;
                }

                final long durationInStateMs =
                        u.getMobileRadioActiveTimeInProcessState(processState) / 1000;
                final long consumptionInStateUc =
                        u.getMobileRadioMeasuredBatteryConsumptionUC(processState);
                final double powerInStateMah = calculatePower(u, powerModel, durationInStateMs,
                        consumptionInStateUc);
                app.setConsumedPower(key, powerInStateMah, powerModel);
            }
        }
    }

    private long calculateDuration(BatteryStats.Uid u, int statsType) {
        return u.getMobileRadioActiveTime(statsType) / 1000;
    }

    private double calculatePower(BatteryStats.Uid u, @BatteryConsumer.PowerModel int powerModel,
            long radioActiveDurationMs, long measuredChargeUC) {
        if (powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY) {
            return uCtoMah(measuredChargeUC);
        }

        if (radioActiveDurationMs > 0) {
            return calcPowerFromRadioActiveDurationMah(radioActiveDurationMs);
        }
        return 0;
    }

    private void calculateRemaining(PowerAndDuration total,
            @BatteryConsumer.PowerModel int powerModel, BatteryStats batteryStats,
            long rawRealtimeUs, long totalConsumptionUC) {
        long signalTimeMs = 0;
        double powerMah = 0;

        if (powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY) {
            powerMah = uCtoMah(totalConsumptionUC) - total.totalAppPowerMah;
            if (powerMah < 0) powerMah = 0;
        }

        for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
            long strengthTimeMs = batteryStats.getPhoneSignalStrengthTime(i, rawRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED) / 1000;
            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                final double p = calcIdlePowerAtSignalStrengthMah(strengthTimeMs, i);
                if (DEBUG && p != 0) {
                    Log.d(TAG, "Cell strength #" + i + ": time=" + strengthTimeMs + " power="
                            + BatteryStats.formatCharge(p));
                }
                powerMah += p;
            }
            signalTimeMs += strengthTimeMs;
            if (i == 0) {
                total.noCoverageDurationMs = strengthTimeMs;
            }
        }

        final long scanningTimeMs = batteryStats.getPhoneSignalScanningTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        long radioActiveTimeMs = batteryStats.getMobileRadioActiveTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        long remainingActiveTimeMs = radioActiveTimeMs - total.totalAppDurationMs;

        if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
            final double p = calcScanTimePowerMah(scanningTimeMs);
            if (DEBUG && p != 0) {
                Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs
                        + " power=" + BatteryStats.formatCharge(p));
            }
            powerMah += p;

            if (remainingActiveTimeMs > 0) {
                powerMah += calcPowerFromRadioActiveDurationMah(remainingActiveTimeMs);
            }
        }
        total.durationMs = radioActiveTimeMs;
        total.remainingPowerMah = powerMah;
        total.signalDurationMs = signalTimeMs;
    }

    /**
     * Calculates active radio power consumption (in milliamp-hours) from active radio duration.
     */
    public double calcPowerFromRadioActiveDurationMah(long radioActiveDurationMs) {
        return mActivePowerEstimator.calculatePower(radioActiveDurationMs);
    }

    /**
     * Calculates idle radio power consumption (in milliamp-hours) for time spent at a cell signal
     * strength level.
     * see {@link CellSignalStrength#getNumSignalStrengthLevels()}
     */
    public double calcIdlePowerAtSignalStrengthMah(long strengthTimeMs, int strengthLevel) {
        return mIdlePowerEstimators[strengthLevel].calculatePower(strengthTimeMs);
    }

    /**
     * Calculates radio scan power consumption (in milliamp-hours) from scan time.
     */
    public double calcScanTimePowerMah(long scanningTimeMs) {
        return mScanPowerEstimator.calculatePower(scanningTimeMs);
    }
}
