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
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.telephony.CellSignalStrength;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

public class MobileRadioPowerCalculator extends PowerCalculator {
    private static final String TAG = "MobRadioPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;

    private static final int NUM_SIGNAL_STRENGTH_LEVELS =
            CellSignalStrength.getNumSignalStrengthLevels();

    private final UsageBasedPowerEstimator mActivePowerEstimator;
    private final UsageBasedPowerEstimator[] mIdlePowerEstimators =
            new UsageBasedPowerEstimator[NUM_SIGNAL_STRENGTH_LEVELS];
    private final UsageBasedPowerEstimator mScanPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
        public long totalAppDurationMs;
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
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {

        PowerAndDuration total = new PowerAndDuration();

        final double powerPerPacketMah = getMobilePowerPerPacket(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            final BatteryStats.Uid uid = app.getBatteryStatsUid();
            calculateApp(app, uid, powerPerPacketMah, total,
                    query.shouldForceUsePowerProfileModel());
        }

        calculateRemaining(total, batteryStats, rawRealtimeUs,
                query.shouldForceUsePowerProfileModel());

        if (total.powerMah != 0) {
            builder.getOrCreateSystemBatteryConsumerBuilder(
                    SystemBatteryConsumer.DRAIN_TYPE_MOBILE_RADIO)
                    .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_MOBILE_RADIO,
                            total.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO, total.powerMah);
        }
    }

    private void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            double powerPerPacketMah, PowerAndDuration total,
            boolean shouldForceUsePowerProfileModel) {
        final long radioActiveDurationMs = calculateDuration(u, BatteryStats.STATS_SINCE_CHARGED);
        total.totalAppDurationMs += radioActiveDurationMs;

        final double powerMah = calculatePower(u, powerPerPacketMah, radioActiveDurationMs,
                shouldForceUsePowerProfileModel);

        app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_MOBILE_RADIO,
                radioActiveDurationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO, powerMah);
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final double mobilePowerPerPacket = getMobilePowerPerPacket(batteryStats, rawRealtimeUs,
                statsType);
        PowerAndDuration total = new PowerAndDuration();
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                final BatteryStats.Uid u = app.uidObj;
                calculateApp(app, u, statsType, mobilePowerPerPacket, total, false);
            }
        }

        BatterySipper radio = new BatterySipper(BatterySipper.DrainType.CELL, null, 0);
        calculateRemaining(total, batteryStats, rawRealtimeUs, false);
        if (total.powerMah != 0) {
            if (total.signalDurationMs != 0) {
                radio.noCoveragePercent =
                        total.noCoverageDurationMs * 100.0 / total.signalDurationMs;
            }
            radio.mobileActive = total.durationMs;
            radio.mobileActiveCount = batteryStats.getMobileRadioActiveUnknownCount(statsType);
            radio.mobileRadioPowerMah = total.powerMah;
            radio.sumPower();
        }
        if (radio.totalPowerMah > 0) {
            sippers.add(radio);
        }
    }

    private void calculateApp(BatterySipper app, BatteryStats.Uid u, int statsType,
            double powerPerPacketMah, PowerAndDuration total,
            boolean shouldForceUsePowerProfileModel) {
        app.mobileActive = calculateDuration(u, statsType);

        app.mobileRadioPowerMah = calculatePower(u, powerPerPacketMah, app.mobileActive,
                shouldForceUsePowerProfileModel);
        total.totalAppDurationMs += app.mobileActive;

        // Add cost of mobile traffic.
        app.mobileRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        app.mobileActiveCount = u.getMobileRadioActiveCount(statsType);
        app.mobileRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);

        if (DEBUG && app.mobileRadioPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": mobile packets "
                    + (app.mobileRxPackets + app.mobileTxPackets)
                    + " active time " + app.mobileActive
                    + " power=" + formatCharge(app.mobileRadioPowerMah));
        }
    }

    private long calculateDuration(BatteryStats.Uid u, int statsType) {
        return u.getMobileRadioActiveTime(statsType) / 1000;
    }

    private double calculatePower(BatteryStats.Uid u, double powerPerPacketMah,
            long radioActiveDurationMs, boolean shouldForceUsePowerProfileModel) {

        final long measuredChargeUC = u.getMobileRadioMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable = !shouldForceUsePowerProfileModel
                && measuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE;
        if (isMeasuredPowerAvailable) {
            return uCtoMah(measuredChargeUC);
        }

        if (radioActiveDurationMs > 0) {
            // We are tracking when the radio is up, so can use the active time to
            // determine power use.
            return calcPowerFromRadioActiveDurationMah(radioActiveDurationMs);
        } else {
            // We are not tracking when the radio is up, so must approximate power use
            // based on the number of packets.
            final long mobileRxPackets = u.getNetworkActivityPackets(
                    BatteryStats.NETWORK_MOBILE_RX_DATA,
                    BatteryStats.STATS_SINCE_CHARGED);
            final long mobileTxPackets = u.getNetworkActivityPackets(
                    BatteryStats.NETWORK_MOBILE_TX_DATA,
                    BatteryStats.STATS_SINCE_CHARGED);
            return (mobileRxPackets + mobileTxPackets) * powerPerPacketMah;
        }
    }

    private void calculateRemaining(MobileRadioPowerCalculator.PowerAndDuration total,
            BatteryStats batteryStats, long rawRealtimeUs,
            boolean shouldForceUsePowerProfileModel) {
        long signalTimeMs = 0;
        double powerMah = 0;

        final long measuredChargeUC = batteryStats.getMobileRadioMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable = !shouldForceUsePowerProfileModel
                && measuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE;
        if (isMeasuredPowerAvailable) {
            powerMah = uCtoMah(measuredChargeUC);
        }

        for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
            long strengthTimeMs = batteryStats.getPhoneSignalStrengthTime(i, rawRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED) / 1000;
            if (!isMeasuredPowerAvailable) {
                final double p = calcIdlePowerAtSignalStrengthMah(strengthTimeMs, i);
                if (DEBUG && p != 0) {
                    Log.d(TAG, "Cell strength #" + i + ": time=" + strengthTimeMs + " power="
                            + formatCharge(p));
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

        if (!isMeasuredPowerAvailable) {
            final double p = calcScanTimePowerMah(scanningTimeMs);
            if (DEBUG && p != 0) {
                Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs + " power=" + formatCharge(
                        p));
            }
            powerMah += p;

            if (remainingActiveTimeMs > 0) {
                powerMah += calcPowerFromRadioActiveDurationMah(remainingActiveTimeMs);
            }
        }
        total.durationMs = radioActiveTimeMs;
        total.powerMah = powerMah;
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

    /**
     * Return estimated power (in mAh) of sending or receiving a packet with the mobile radio.
     */
    private double getMobilePowerPerPacket(BatteryStats stats, long rawRealtimeUs, int statsType) {
        final long radioDataUptimeMs =
                stats.getMobileRadioActiveTime(rawRealtimeUs, statsType) / 1000;
        final double mobilePower = calcPowerFromRadioActiveDurationMah(radioDataUptimeMs);

        final long mobileRx = stats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        final long mobileTx = stats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        final long mobilePackets = mobileRx + mobileTx;

        return mobilePackets != 0 ? mobilePower / mobilePackets : 0;
    }
}
