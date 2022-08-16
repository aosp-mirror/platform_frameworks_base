/*
 * Copyright (C) 2021 The Android Open Source Project
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


import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import static com.google.common.truth.Truth.assertThat;

import android.app.usage.NetworkStatsManager;
import android.net.NetworkCapabilities;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.WorkSource;
import android.os.connectivity.WifiActivityEnergyInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class WifiPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Mock
    NetworkStatsManager mNetworkStatsManager;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE, 360.0)
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX, 480.0)
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX, 720.0)
            .setAveragePower(PowerProfile.POWER_WIFI_ON, 360.0)
            .setAveragePower(PowerProfile.POWER_WIFI_SCAN, 480.0)
            .setAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN, 720.0)
            .setAveragePower(PowerProfile.POWER_WIFI_ACTIVE, 1080.0)
            .initMeasuredEnergyStatsLocked();

    /** Sets up a batterystats object with pre-populated network values. */
    private BatteryStatsImpl setupTestNetworkNumbers() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteNetworkInterfaceForTransports("wifi",
                new int[]{NetworkCapabilities.TRANSPORT_WIFI});

        mStatsRule.setNetworkStats(buildNetworkStats(10000, 1000, 100, 2000, 20));

        return batteryStats;
    }

    private NetworkStats buildNetworkStats(long elapsedRealtime, int rxBytes, int rxPackets,
            int txBytes, int txPackets) {
        return new NetworkStats(elapsedRealtime, 1)
                .addEntry(new NetworkStats.Entry("wifi", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes, rxPackets,
                        txBytes, txPackets, 100))
                .addEntry(new NetworkStats.Entry("wifi", Process.WIFI_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1111, 111,
                        2222, 22, 111));
    }

    /** Sets up an WifiActivityEnergyInfo for ActivityController-model-based tests. */
    private WifiActivityEnergyInfo setupPowerControllerBasedModelEnergyNumbersInfo() {
        return new WifiActivityEnergyInfo(10000,
                WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 1000, 2000, 3000, 4000);
    }

    @Test
    public void testPowerControllerBasedModel_nonMeasured() {
        final BatteryStatsImpl batteryStats = setupTestNetworkNumbers();
        final WifiActivityEnergyInfo energyInfo = setupPowerControllerBasedModelEnergyNumbersInfo();

        batteryStats.noteWifiScanStartedLocked(APP_UID, 500, 500);
        batteryStats.noteWifiScanStoppedLocked(APP_UID, 1500, 1500);

        batteryStats.updateWifiState(energyInfo, POWER_DATA_UNAVAILABLE, 2000, 2000,
                mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(2473);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.3964);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(4001);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.86666);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer appsConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.866666);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testPowerControllerBasedModel_powerProfile_byProcessState() {
        final BatteryStatsImpl batteryStats = setupTestNetworkNumbers();

        mStatsRule.setTime(1000, 1000);

        BatteryStatsImpl.Uid uid = batteryStats.getUidStatsLocked(APP_UID);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);

        batteryStats.updateWifiState(new WifiActivityEnergyInfo(2000,
                        WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 1000, 2000, 3000, 4000),
                POWER_DATA_UNAVAILABLE, 2000, 2000,
                mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 3000);

        mStatsRule.setNetworkStats(buildNetworkStats(4000, 5000, 200, 7000, 80));

        batteryStats.updateWifiState(new WifiActivityEnergyInfo(4000,
                        WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 5000, 6000, 7000, 8000),
                POWER_DATA_UNAVAILABLE, 4000, 4000,
                mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .powerProfileModeledOnly()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(12423);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(2.0214666);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(1.1214666);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.9);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }

    @Test
    public void testPowerControllerBasedModel_measured() {
        final BatteryStatsImpl batteryStats = setupTestNetworkNumbers();
        final WifiActivityEnergyInfo energyInfo = setupPowerControllerBasedModelEnergyNumbersInfo();

        batteryStats.updateWifiState(energyInfo, 1_000_000, 1000, 1000, mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(1423);
        /* Same ratio as in testPowerControllerBasedModel_nonMeasured but scaled by 1_000_000uC. */
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.2214666 / (0.2214666 + 0.645200) * 1_000_000 / 3600000);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(4002);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.27777);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        BatteryConsumer appsConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.277777);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testPowerControllerBasedModel_measured_byProcessState() {
        final BatteryStatsImpl batteryStats = setupTestNetworkNumbers();

        mStatsRule.setTime(1000, 1000);

        BatteryStatsImpl.Uid uid = batteryStats.getUidStatsLocked(APP_UID);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);

        batteryStats.updateWifiState(new WifiActivityEnergyInfo(2000,
                        WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 1000, 2000, 3000, 4000),
                1_000_000, 2000, 2000,
                mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 3000);

        mStatsRule.setNetworkStats(buildNetworkStats(4000, 5000, 200, 7000, 80));

        batteryStats.updateWifiState(new WifiActivityEnergyInfo(4000,
                        WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 5000, 6000, 7000, 8000),
                5_000_000, 4000, 4000,
                mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(12423);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(1.0325211);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(0.5517519);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.4807691);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }

    /** Sets up batterystats object with prepopulated network & timer data for Timer-model tests. */
    private BatteryStatsImpl setupTimerBasedModelTestNumbers() {
        final BatteryStatsImpl batteryStats = setupTestNetworkNumbers();
        batteryStats.noteWifiScanStartedLocked(APP_UID, 1000, 1000);
        batteryStats.noteWifiScanStoppedLocked(APP_UID, 2000, 2000);
        batteryStats.noteWifiRunningLocked(new WorkSource(APP_UID), 3000, 3000);
        batteryStats.noteWifiStoppedLocked(new WorkSource(APP_UID), 4000, 4000);
        batteryStats.noteWifiRunningLocked(new WorkSource(Process.WIFI_UID), 1111, 2222);
        batteryStats.noteWifiStoppedLocked(new WorkSource(Process.WIFI_UID), 3333, 4444);
        return batteryStats;
    }

    @Test
    public void testTimerBasedModel_nonMeasured() {
        final BatteryStatsImpl batteryStats = setupTimerBasedModelTestNumbers();

        // Don't pass WifiActivityEnergyInfo, making WifiPowerCalculator rely exclusively
        // on the packet counts.
        batteryStats.updateWifiState(/* energyInfo */ null, POWER_DATA_UNAVAILABLE, 1000, 1000,
                mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(1000);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.8231573);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testTimerBasedModel_measured() {
        final BatteryStatsImpl batteryStats = setupTimerBasedModelTestNumbers();

        // Don't pass WifiActivityEnergyInfo, making WifiPowerCalculator rely exclusively
        // on the packet counts.
        batteryStats.updateWifiState(/* energyInfo */ null, 1_000_000, 1000, 1000,
                mNetworkStatsManager);

        WifiPowerCalculator calculator = new WifiPowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(1000);
        /* Same ratio as in testTimerBasedModel_nonMeasured but scaled by 1_000_000uC. */
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isWithin(PRECISION).of(0.8231573 / (0.8231573 + 0.8759216) * 1_000_000 / 3600000);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_WIFI))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }
}
