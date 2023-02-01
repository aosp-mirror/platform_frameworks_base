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

import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryStats.ControllerActivityCounter;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.Log;
import android.util.SparseArray;

import java.util.Arrays;

public class BluetoothPowerCalculator extends PowerCalculator {
    private static final String TAG = "BluetoothPowerCalc";
    private static final boolean DEBUG = PowerCalculator.DEBUG;

    private static final BatteryConsumer.Key[] UNINITIALIZED_KEYS = new BatteryConsumer.Key[0];

    private final double mIdleMa;
    private final double mRxMa;
    private final double mTxMa;
    private final boolean mHasBluetoothPowerController;

    private static class PowerAndDuration {
        // Return value of BT duration per app
        public long durationMs;
        // Return value of BT power per app
        public double powerMah;

        public BatteryConsumer.Key[] keys;
        public double[] powerPerKeyMah;

        // Aggregated BT duration across all apps
        public long totalDurationMs;
        // Aggregated BT power across all apps
        public double totalPowerMah;
    }

    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
        mHasBluetoothPowerController = mIdleMa != 0 && mRxMa != 0 && mTxMa != 0;
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_BLUETOOTH;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        if (!batteryStats.hasBluetoothActivityReporting()) {
            return;
        }

        BatteryConsumer.Key[] keys = UNINITIALIZED_KEYS;
        final PowerAndDuration powerAndDuration = new PowerAndDuration();

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (keys == UNINITIALIZED_KEYS) {
                if (query.isProcessStateDataNeeded()) {
                    keys = app.getKeys(BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
                    powerAndDuration.keys = keys;
                    powerAndDuration.powerPerKeyMah = new double[keys.length];
                } else {
                    keys = null;
                }
            }
            calculateApp(app, powerAndDuration, query);
        }

        final long measuredChargeUC = batteryStats.getBluetoothMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(measuredChargeUC, query);
        final ControllerActivityCounter activityCounter =
                batteryStats.getBluetoothControllerActivity();
        calculatePowerAndDuration(null, powerModel, measuredChargeUC,
                activityCounter, query.shouldForceUsePowerProfileModel(), powerAndDuration);

        // Subtract what the apps used, but clamp to 0.
        final long systemComponentDurationMs = Math.max(0,
                powerAndDuration.durationMs - powerAndDuration.totalDurationMs);
        if (DEBUG) {
            Log.d(TAG, "Bluetooth active: time=" + (systemComponentDurationMs)
                    + " power=" + BatteryStats.formatCharge(powerAndDuration.powerMah));
        }

        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        powerAndDuration.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        Math.max(powerAndDuration.powerMah, powerAndDuration.totalPowerMah),
                        powerModel);

        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        powerAndDuration.totalDurationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        powerAndDuration.totalPowerMah,
                        powerModel);
    }

    private void calculateApp(UidBatteryConsumer.Builder app, PowerAndDuration powerAndDuration,
            BatteryUsageStatsQuery query) {
        final long measuredChargeUC =
                app.getBatteryStatsUid().getBluetoothMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(measuredChargeUC, query);
        final ControllerActivityCounter activityCounter =
                app.getBatteryStatsUid().getBluetoothControllerActivity();
        calculatePowerAndDuration(app.getBatteryStatsUid(), powerModel, measuredChargeUC,
                activityCounter, query.shouldForceUsePowerProfileModel(), powerAndDuration);

        app.setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH, powerAndDuration.durationMs)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH, powerAndDuration.powerMah,
                        powerModel);

        if (!app.isVirtualUid()) {
            powerAndDuration.totalDurationMs += powerAndDuration.durationMs;
            powerAndDuration.totalPowerMah += powerAndDuration.powerMah;
        }

        if (query.isProcessStateDataNeeded() && powerAndDuration.keys != null) {
            for (int j = 0; j < powerAndDuration.keys.length; j++) {
                BatteryConsumer.Key key = powerAndDuration.keys[j];
                final int processState = key.processState;
                if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                    // Already populated with the powerAndDuration across all process states
                    continue;
                }

                app.setConsumedPower(key, powerAndDuration.powerPerKeyMah[j], powerModel);
            }
        }
    }

    /** Returns bluetooth power usage based on the best data available. */
    private void calculatePowerAndDuration(@Nullable BatteryStats.Uid uid,
            @BatteryConsumer.PowerModel int powerModel,
            long measuredChargeUC, ControllerActivityCounter counter, boolean ignoreReportedPower,
            PowerAndDuration powerAndDuration) {
        if (counter == null) {
            powerAndDuration.durationMs = 0;
            powerAndDuration.powerMah = 0;
            if (powerAndDuration.powerPerKeyMah != null) {
                Arrays.fill(powerAndDuration.powerPerKeyMah, 0);
            }
            return;
        }

        final BatteryStats.LongCounter idleTimeCounter = counter.getIdleTimeCounter();
        final BatteryStats.LongCounter rxTimeCounter = counter.getRxTimeCounter();
        final BatteryStats.LongCounter txTimeCounter = counter.getTxTimeCounters()[0];
        final long idleTimeMs = idleTimeCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED);
        final long rxTimeMs = rxTimeCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED);
        final long txTimeMs = txTimeCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED);

        powerAndDuration.durationMs = idleTimeMs + rxTimeMs + txTimeMs;

        if (powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY) {
            powerAndDuration.powerMah = uCtoMah(measuredChargeUC);
            if (uid != null && powerAndDuration.keys != null) {
                for (int i = 0; i < powerAndDuration.keys.length; i++) {
                    BatteryConsumer.Key key = powerAndDuration.keys[i];
                    final int processState = key.processState;
                    if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                        // Already populated with the powerAndDuration across all process states
                        continue;
                    }

                    powerAndDuration.powerPerKeyMah[i] =
                            uCtoMah(uid.getBluetoothMeasuredBatteryConsumptionUC(processState));
                }
            }
        } else {
            if (!ignoreReportedPower) {
                final double powerMah =
                        counter.getPowerCounter().getCountLocked(BatteryStats.STATS_SINCE_CHARGED)
                                / (double) (1000 * 60 * 60);
                if (powerMah != 0) {
                    powerAndDuration.powerMah = powerMah;
                    if (powerAndDuration.powerPerKeyMah != null) {
                        // Leave this use case unsupported: used energy is reported
                        // via BluetoothActivityEnergyInfo rather than PowerStats HAL.
                        Arrays.fill(powerAndDuration.powerPerKeyMah, 0);
                    }
                    return;
                }
            }

            if (mHasBluetoothPowerController) {
                powerAndDuration.powerMah = calculatePowerMah(rxTimeMs, txTimeMs, idleTimeMs);

                if (powerAndDuration.keys != null) {
                    for (int i = 0; i < powerAndDuration.keys.length; i++) {
                        BatteryConsumer.Key key = powerAndDuration.keys[i];
                        final int processState = key.processState;
                        if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                            // Already populated with the powerAndDuration across all process states
                            continue;
                        }

                        powerAndDuration.powerPerKeyMah[i] =
                                calculatePowerMah(
                                        rxTimeCounter.getCountForProcessState(processState),
                                        txTimeCounter.getCountForProcessState(processState),
                                        idleTimeCounter.getCountForProcessState(processState));
                    }
                }
            } else {
                powerAndDuration.powerMah = 0;
                if (powerAndDuration.powerPerKeyMah != null) {
                    Arrays.fill(powerAndDuration.powerPerKeyMah, 0);
                }
            }
        }
    }

    /** Returns estimated bluetooth power usage based on usage times. */
    public double calculatePowerMah(long rxTimeMs, long txTimeMs, long idleTimeMs) {
        return ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                / (1000 * 60 * 60);
    }
}
