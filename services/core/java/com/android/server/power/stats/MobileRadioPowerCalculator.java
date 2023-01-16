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
package com.android.server.power.stats;

import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.telephony.CellSignalStrength;
import android.telephony.ServiceState;
import android.util.Log;
import android.util.LongArrayQueue;
import android.util.SparseArray;

import com.android.internal.os.PowerProfile;
import com.android.internal.power.ModemPowerProfile;

import java.util.ArrayList;

public class MobileRadioPowerCalculator extends PowerCalculator {
    private static final String TAG = "MobRadioPowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;

    private static final double MILLIS_IN_HOUR = 1000.0 * 60 * 60;

    private static final int NUM_SIGNAL_STRENGTH_LEVELS =
            CellSignalStrength.getNumSignalStrengthLevels();

    private static final BatteryConsumer.Key[] UNINITIALIZED_KEYS = new BatteryConsumer.Key[0];
    private static final int IGNORE = -1;

    private final UsageBasedPowerEstimator mActivePowerEstimator; // deprecated
    private final UsageBasedPowerEstimator[] mIdlePowerEstimators =
            new UsageBasedPowerEstimator[NUM_SIGNAL_STRENGTH_LEVELS]; // deprecated
    private final UsageBasedPowerEstimator mScanPowerEstimator; // deprecated

    @Nullable
    private final UsageBasedPowerEstimator mSleepPowerEstimator;
    @Nullable
    private final UsageBasedPowerEstimator mIdlePowerEstimator;

    private final PowerProfile mPowerProfile;

    private static class PowerAndDuration {
        public long remainingDurationMs;
        public double remainingPowerMah;
        public long totalAppDurationMs;
        public double totalAppPowerMah;
    }

    public MobileRadioPowerCalculator(PowerProfile profile) {
        mPowerProfile = profile;

        final double sleepDrainRateMa = mPowerProfile.getAverageBatteryDrainOrDefaultMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP,
                Double.NaN);
        if (Double.isNaN(sleepDrainRateMa)) {
            mSleepPowerEstimator = null;
        } else {
            mSleepPowerEstimator = new UsageBasedPowerEstimator(sleepDrainRateMa);
        }

        final double idleDrainRateMa = mPowerProfile.getAverageBatteryDrainOrDefaultMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE,
                Double.NaN);
        if (Double.isNaN(idleDrainRateMa)) {
            mIdlePowerEstimator = null;
        } else {
            mIdlePowerEstimator = new UsageBasedPowerEstimator(idleDrainRateMa);
        }

        // Instantiate legacy power estimators
        double powerRadioActiveMa =
                profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ACTIVE, Double.NaN);
        if (Double.isNaN(powerRadioActiveMa)) {
            double sum = 0;
            sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
            for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
            }
            powerRadioActiveMa = sum / (NUM_SIGNAL_STRENGTH_LEVELS + 1);
        }
        mActivePowerEstimator = new UsageBasedPowerEstimator(powerRadioActiveMa);

        if (!Double.isNaN(
                profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ON, Double.NaN))) {
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

        final long totalConsumptionUC = batteryStats.getMobileRadioEnergyConsumptionUC();
        final int powerModel = getPowerModel(totalConsumptionUC, query);

        final double totalActivePowerMah;
        final ArrayList<UidBatteryConsumer.Builder> apps;
        final LongArrayQueue appDurationsMs;
        if (powerModel == BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION) {
            // EnergyConsumer is available, don't bother calculating power.
            totalActivePowerMah = Double.NaN;
            apps = null;
            appDurationsMs = null;
        } else {
            totalActivePowerMah = calculateActiveModemPowerMah(batteryStats, rawRealtimeUs);
            apps = new ArrayList<>();
            appDurationsMs = new LongArrayQueue();
        }

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

            // Sum and populate each app's active radio duration.
            final long radioActiveDurationMs = calculateDuration(uid,
                    BatteryStats.STATS_SINCE_CHARGED);
            if (!app.isVirtualUid()) {
                total.totalAppDurationMs += radioActiveDurationMs;
            }
            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                    radioActiveDurationMs);

            if (powerModel == BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION) {
                // EnergyConsumer is available, populate the consumed power now.
                final long appConsumptionUC = uid.getMobileRadioEnergyConsumptionUC();
                if (appConsumptionUC != BatteryStats.POWER_DATA_UNAVAILABLE) {
                    final double appConsumptionMah = uCtoMah(appConsumptionUC);
                    if (!app.isVirtualUid()) {
                        total.totalAppPowerMah += appConsumptionMah;
                    }
                    app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            appConsumptionMah, powerModel);

                    if (query.isProcessStateDataNeeded() && keys != null) {
                        for (BatteryConsumer.Key key : keys) {
                            final int processState = key.processState;
                            if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                                // Already populated with the total across all process states
                                continue;
                            }
                            final long consumptionInStateUc =
                                    uid.getMobileRadioEnergyConsumptionUC(processState);
                            final double powerInStateMah = uCtoMah(consumptionInStateUc);
                            app.setConsumedPower(key, powerInStateMah, powerModel);
                        }
                    }
                }
            } else {
                // Cache the app and its active duration for later calculations.
                apps.add(app);
                appDurationsMs.addLast(radioActiveDurationMs);
            }
        }

        long totalActiveDurationMs = batteryStats.getMobileRadioActiveTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        if (totalActiveDurationMs < total.totalAppDurationMs) {
            totalActiveDurationMs = total.totalAppDurationMs;
        }

        if (powerModel != BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION) {
            // Need to smear the calculated total active power across the apps based on app
            // active durations.
            final int appSize = apps.size();
            for (int i = 0; i < appSize; i++) {
                final UidBatteryConsumer.Builder app = apps.get(i);
                final long activeDurationMs = appDurationsMs.get(i);

                // Proportionally attribute radio power consumption based on active duration.
                final double appConsumptionMah;
                if (totalActiveDurationMs == 0.0) {
                    appConsumptionMah = 0.0;
                } else {
                    appConsumptionMah =
                            (totalActivePowerMah * activeDurationMs) / totalActiveDurationMs;
                }

                if (!app.isVirtualUid()) {
                    total.totalAppPowerMah += appConsumptionMah;
                }
                app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                        appConsumptionMah, powerModel);

                if (query.isProcessStateDataNeeded() && keys != null) {
                    final BatteryStats.Uid uid = app.getBatteryStatsUid();
                    for (BatteryConsumer.Key key : keys) {
                        final int processState = key.processState;
                        if (processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                            // Already populated with the total across all process states
                            continue;
                        }

                        final long durationInStateMs =
                                uid.getMobileRadioActiveTimeInProcessState(processState) / 1000;
                        // Proportionally attribute per process state radio power consumption
                        // based on time state duration.
                        final double powerInStateMah;
                        if (activeDurationMs == 0.0) {
                            powerInStateMah = 0.0;
                        } else {
                            powerInStateMah =
                                    (appConsumptionMah * durationInStateMs) / activeDurationMs;
                        }
                        app.setConsumedPower(key, powerInStateMah, powerModel);
                    }
                }
            }
        }

        total.remainingDurationMs = totalActiveDurationMs - total.totalAppDurationMs;

        // Calculate remaining power consumption.
        if (powerModel == BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION) {
            total.remainingPowerMah = uCtoMah(totalConsumptionUC) - total.totalAppPowerMah;
            if (total.remainingPowerMah < 0) total.remainingPowerMah = 0;
        } else {
            // Smear unattributed active time and add it to the remaining power consumption.
            total.remainingPowerMah +=
                    (totalActivePowerMah * total.remainingDurationMs) / totalActiveDurationMs;

            // Calculate the inactive modem power consumption.
            final BatteryStats.ControllerActivityCounter modemActivity =
                    batteryStats.getModemControllerActivity();
            double inactivePowerMah = Double.NaN;
            if (modemActivity != null) {
                final long sleepDurationMs = modemActivity.getSleepTimeCounter().getCountLocked(
                        BatteryStats.STATS_SINCE_CHARGED);
                final long idleDurationMs = modemActivity.getIdleTimeCounter().getCountLocked(
                        BatteryStats.STATS_SINCE_CHARGED);
                inactivePowerMah = calcInactiveStatePowerMah(sleepDurationMs, idleDurationMs);
            }
            if (Double.isNaN(inactivePowerMah)) {
                // Modem activity counters unavailable. Use legacy calculations for inactive usage.
                final long scanningTimeMs = batteryStats.getPhoneSignalScanningTime(rawRealtimeUs,
                        BatteryStats.STATS_SINCE_CHARGED) / 1000;
                inactivePowerMah = calcScanTimePowerMah(scanningTimeMs);
                for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                    long strengthTimeMs = batteryStats.getPhoneSignalStrengthTime(i, rawRealtimeUs,
                            BatteryStats.STATS_SINCE_CHARGED) / 1000;
                    inactivePowerMah += calcIdlePowerAtSignalStrengthMah(strengthTimeMs, i);
                }
            }
            if (!Double.isNaN(inactivePowerMah)) {
                total.remainingPowerMah += inactivePowerMah;
            }

        }

        if (total.remainingPowerMah != 0 || total.totalAppPowerMah != 0) {
            builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.remainingDurationMs + total.totalAppDurationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.remainingPowerMah + total.totalAppPowerMah, powerModel);

            builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.totalAppDurationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                            total.totalAppPowerMah, powerModel);
        }
    }

    private long calculateDuration(BatteryStats.Uid u, int statsType) {
        return u.getMobileRadioActiveTime(statsType) / 1000;
    }

    private double calculateActiveModemPowerMah(BatteryStats bs, long elapsedRealtimeUs) {
        final long elapsedRealtimeMs = elapsedRealtimeUs / 1000;
        final int txLvlCount = CellSignalStrength.getNumSignalStrengthLevels();
        double consumptionMah = 0.0;

        if (DEBUG) {
            Log.d(TAG, "Calculating radio power consumption at elapased real timestamp : "
                    + elapsedRealtimeMs + " ms");
        }

        boolean hasConstants = false;

        for (int rat = 0; rat < BatteryStats.RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
            final int freqCount = rat == BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR
                    ? ServiceState.FREQUENCY_RANGE_COUNT : 1;
            for (int freq = 0; freq < freqCount; freq++) {
                for (int txLvl = 0; txLvl < txLvlCount; txLvl++) {
                    final long txDurationMs = bs.getActiveTxRadioDurationMs(rat, freq, txLvl,
                            elapsedRealtimeMs);
                    if (txDurationMs == BatteryStats.DURATION_UNAVAILABLE) {
                        continue;
                    }
                    final double txConsumptionMah = calcTxStatePowerMah(rat, freq, txLvl,
                            txDurationMs);
                    if (Double.isNaN(txConsumptionMah)) {
                        continue;
                    }
                    hasConstants = true;
                    consumptionMah += txConsumptionMah;
                }

                final long rxDurationMs = bs.getActiveRxRadioDurationMs(rat, freq,
                        elapsedRealtimeMs);
                if (rxDurationMs == BatteryStats.DURATION_UNAVAILABLE) {
                    continue;
                }
                final double rxConsumptionMah = calcRxStatePowerMah(rat, freq, rxDurationMs);
                if (Double.isNaN(rxConsumptionMah)) {
                    continue;
                }
                hasConstants = true;
                consumptionMah += rxConsumptionMah;
            }
        }

        if (!hasConstants) {
            final long radioActiveDurationMs = bs.getMobileRadioActiveTime(elapsedRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED) / 1000;
            if (DEBUG) {
                Log.d(TAG,
                        "Failed to calculate radio power consumption. Reattempted with legacy "
                                + "method. Radio active duration : "
                                + radioActiveDurationMs + " ms");
            }
            if (radioActiveDurationMs > 0) {
                consumptionMah = calcPowerFromRadioActiveDurationMah(radioActiveDurationMs);
            } else {
                consumptionMah = 0.0;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Total active radio power consumption calculated to be " + consumptionMah
                    + " mAH.");
        }

        return consumptionMah;
    }

    private static long buildModemPowerProfileKey(@ModemPowerProfile.ModemDrainType int drainType,
            @BatteryStats.RadioAccessTechnology int rat, @ServiceState.FrequencyRange int freqRange,
            int txLevel) {
        long key = PowerProfile.SUBSYSTEM_MODEM;

        // Attach Modem drain type to the key if specified.
        if (drainType != IGNORE) {
            key |= drainType;
        }

        // Attach RadioAccessTechnology to the key if specified.
        switch (rat) {
            case IGNORE:
                // do nothing
                break;
            case BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER:
                key |= ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT;
                break;
            case BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE:
                key |= ModemPowerProfile.MODEM_RAT_TYPE_LTE;
                break;
            case BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR:
                key |= ModemPowerProfile.MODEM_RAT_TYPE_NR;
                break;
            default:
                Log.w(TAG, "Unexpected RadioAccessTechnology : " + rat);
        }

        // Attach NR Frequency Range to the key if specified.
        switch (freqRange) {
            case IGNORE:
                // do nothing
                break;
            case ServiceState.FREQUENCY_RANGE_UNKNOWN:
                key |= ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT;
                break;
            case ServiceState.FREQUENCY_RANGE_LOW:
                key |= ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW;
                break;
            case ServiceState.FREQUENCY_RANGE_MID:
                key |= ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID;
                break;
            case ServiceState.FREQUENCY_RANGE_HIGH:
                key |= ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH;
                break;
            case ServiceState.FREQUENCY_RANGE_MMWAVE:
                key |= ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE;
                break;
            default:
                Log.w(TAG, "Unexpected NR frequency range : " + freqRange);
        }

        // Attach transmission level to the key if specified.
        switch (txLevel) {
            case IGNORE:
                // do nothing
                break;
            case 0:
                key |= ModemPowerProfile.MODEM_TX_LEVEL_0;
                break;
            case 1:
                key |= ModemPowerProfile.MODEM_TX_LEVEL_1;
                break;
            case 2:
                key |= ModemPowerProfile.MODEM_TX_LEVEL_2;
                break;
            case 3:
                key |= ModemPowerProfile.MODEM_TX_LEVEL_3;
                break;
            case 4:
                key |= ModemPowerProfile.MODEM_TX_LEVEL_4;
                break;
            default:
                Log.w(TAG, "Unexpected transmission level : " + txLevel);
        }
        return key;
    }

    /**
     * Calculates active receive radio power consumption (in milliamp-hours) from the given state's
     * duration.
     */
    public double calcRxStatePowerMah(@BatteryStats.RadioAccessTechnology int rat,
            @ServiceState.FrequencyRange int freqRange, long rxDurationMs) {
        final long rxKey = buildModemPowerProfileKey(ModemPowerProfile.MODEM_DRAIN_TYPE_RX, rat,
                freqRange, IGNORE);
        final double drainRateMa = mPowerProfile.getAverageBatteryDrainOrDefaultMa(rxKey,
                Double.NaN);
        if (Double.isNaN(drainRateMa)) {
            Log.w(TAG, "Unavailable Power Profile constant for key 0x" + Long.toHexString(rxKey));
            return Double.NaN;
        }

        final double consumptionMah = drainRateMa * rxDurationMs / MILLIS_IN_HOUR;
        if (DEBUG) {
            Log.d(TAG, "Calculated RX consumption " + consumptionMah + " mAH from a drain rate of "
                    + drainRateMa + " mA and a duration of " + rxDurationMs + " ms for "
                    + ModemPowerProfile.keyToString((int) rxKey));
        }
        return consumptionMah;
    }

    /**
     * Calculates active transmit radio power consumption (in milliamp-hours) from the given state's
     * duration.
     */
    public double calcTxStatePowerMah(@BatteryStats.RadioAccessTechnology int rat,
            @ServiceState.FrequencyRange int freqRange, int txLevel, long txDurationMs) {
        final long txKey = buildModemPowerProfileKey(ModemPowerProfile.MODEM_DRAIN_TYPE_TX, rat,
                freqRange, txLevel);
        final double drainRateMa = mPowerProfile.getAverageBatteryDrainOrDefaultMa(txKey,
                Double.NaN);
        if (Double.isNaN(drainRateMa)) {
            Log.w(TAG, "Unavailable Power Profile constant for key 0x" + Long.toHexString(txKey));
            return Double.NaN;
        }

        final double consumptionMah = drainRateMa * txDurationMs / MILLIS_IN_HOUR;
        if (DEBUG) {
            Log.d(TAG, "Calculated TX consumption " + consumptionMah + " mAH from a drain rate of "
                    + drainRateMa + " mA and a duration of " + txDurationMs + " ms for "
                    + ModemPowerProfile.keyToString((int) txKey));
        }
        return consumptionMah;
    }

    /**
     * Calculates active transmit radio power consumption (in milliamp-hours) from the given state's
     * duration.
     */
    public double calcInactiveStatePowerMah(long sleepDurationMs, long idleDurationMs) {
        if (mSleepPowerEstimator == null || mIdlePowerEstimator == null) return Double.NaN;
        final double sleepConsumptionMah = mSleepPowerEstimator.calculatePower(sleepDurationMs);
        final double idleConsumptionMah = mIdlePowerEstimator.calculatePower(idleDurationMs);
        if (DEBUG) {
            Log.d(TAG, "Calculated sleep consumption " + sleepConsumptionMah
                    + " mAH from a duration of " + sleepDurationMs + " ms and idle consumption "
                    + idleConsumptionMah + " mAH from a duration of " + idleDurationMs);
        }
        return sleepConsumptionMah + idleConsumptionMah;
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
