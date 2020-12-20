/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BluetoothPowerCalculatorTest {
    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Mock
    private PowerProfile mMockPowerProfile;
    private MockBatteryStatsImpl mMockBatteryStats;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMockBatteryStats = new MockBatteryStatsImpl(new MockClocks()) {
            @Override
            public boolean hasBluetoothActivityReporting() {
                return true;
            }
        };
        mMockBatteryStats.getOnBatteryTimeBase().setRunning(true, 100_000, 100_000);
        when(mMockPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE)).thenReturn(10.0);
        when(mMockPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX)).thenReturn(50.0);
        when(mMockPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX)).thenReturn(100.0);
    }

    @Test
    public void testTimerBasedModel() {
        setDurationsAndPower(
                mMockBatteryStats.getUidStatsLocked(Process.BLUETOOTH_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                1000, 2000, 3000, 0);

        setDurationsAndPower(mMockBatteryStats.getUidStatsLocked(APP_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                4000, 5000, 6000, 0);

        setDurationsAndPower((BatteryStatsImpl.ControllerActivityCounterImpl)
                        mMockBatteryStats.getBluetoothControllerActivity(),
                6000, 8000, 10000, 0);

        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats();

        assertBluetoothPowerAndDuration(
                getUidBatteryConsumer(batteryUsageStats, Process.BLUETOOTH_UID),
                0.11388, 6000);
        assertBluetoothPowerAndDuration(
                getUidBatteryConsumer(batteryUsageStats, APP_UID),
                0.24722, 15000);
        assertBluetoothPowerAndDuration(
                getBluetoothSystemBatteryConsumer(batteryUsageStats,
                        SystemBatteryConsumer.DRAIN_TYPE_BLUETOOTH),
                0.15833, 9000);
    }

    @Test
    public void testReportedPowerBasedModel() {
        setDurationsAndPower(
                mMockBatteryStats.getUidStatsLocked(Process.BLUETOOTH_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                1000, 2000, 3000, 360000);

        setDurationsAndPower(mMockBatteryStats.getUidStatsLocked(APP_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                4000, 5000, 6000, 720000);

        setDurationsAndPower((BatteryStatsImpl.ControllerActivityCounterImpl)
                        mMockBatteryStats.getBluetoothControllerActivity(),
                6000, 8000, 10000, 1260000);

        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats();

        assertBluetoothPowerAndDuration(
                getUidBatteryConsumer(batteryUsageStats, Process.BLUETOOTH_UID),
                0.1, 6000);
        assertBluetoothPowerAndDuration(
                getUidBatteryConsumer(batteryUsageStats, APP_UID),
                0.2, 15000);
        assertBluetoothPowerAndDuration(
                getBluetoothSystemBatteryConsumer(batteryUsageStats,
                        SystemBatteryConsumer.DRAIN_TYPE_BLUETOOTH),
                0.15, 9000);
    }

    private void setDurationsAndPower(
            BatteryStatsImpl.ControllerActivityCounterImpl controllerActivity, int idleDurationMs,
            int rxDurationMs, int txDurationMs, long powerMaMs) {
        controllerActivity.getIdleTimeCounter().addCountLocked(idleDurationMs);
        controllerActivity.getRxTimeCounter().addCountLocked(rxDurationMs);
        controllerActivity.getTxTimeCounters()[0].addCountLocked(txDurationMs);
        controllerActivity.getPowerCounter().addCountLocked(powerMaMs);
    }

    private BatteryUsageStats buildBatteryUsageStats() {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(0, 0, false);
        builder.getOrCreateUidBatteryConsumerBuilder(
                mMockBatteryStats.getUidStatsLocked(Process.BLUETOOTH_UID));
        builder.getOrCreateUidBatteryConsumerBuilder(
                mMockBatteryStats.getUidStatsLocked(APP_UID));

        BluetoothPowerCalculator bpc = new BluetoothPowerCalculator(mMockPowerProfile);
        bpc.calculate(builder, mMockBatteryStats, 200_000, 200_000, BatteryUsageStatsQuery.DEFAULT,
                null);
        return builder.build();
    }

    private UidBatteryConsumer getUidBatteryConsumer(BatteryUsageStats batteryUsageStats, int uid) {
        for (UidBatteryConsumer ubc : batteryUsageStats.getUidBatteryConsumers()) {
            if (ubc.getUid() == uid) {
                return ubc;
            }
        }
        return null;
    }

    private SystemBatteryConsumer getBluetoothSystemBatteryConsumer(
            BatteryUsageStats batteryUsageStats, int drainType) {
        for (SystemBatteryConsumer sbc : batteryUsageStats.getSystemBatteryConsumers()) {
            if (sbc.getDrainType() == drainType) {
                return sbc;
            }
        }
        return null;
    }

    private void assertBluetoothPowerAndDuration(@Nullable BatteryConsumer batteryConsumer,
            double powerMah, int durationMs) {
        assertThat(batteryConsumer).isNotNull();

        double consumedPower = batteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        assertThat(consumedPower).isWithin(PRECISION).of(powerMah);

        long usageDurationMillis = batteryConsumer.getUsageDurationMillis(
                BatteryConsumer.TIME_COMPONENT_BLUETOOTH);

        assertThat(usageDurationMillis).isEqualTo(durationMs);
    }
}
