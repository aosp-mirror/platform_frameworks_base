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

import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BluetoothPowerCalculatorTest {
    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE, 10.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX, 50.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX, 100.0);

    @Test
    public void testTimerBasedModel() {
        setDurationsAndPower(mStatsRule.getUidStats(Process.BLUETOOTH_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                1000, 2000, 3000, 0);

        setDurationsAndPower(mStatsRule.getUidStats(APP_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                4000, 5000, 6000, 0);

        setDurationsAndPower((BatteryStatsImpl.ControllerActivityCounterImpl)
                        mStatsRule.getBatteryStats().getBluetoothControllerActivity(),
                6000, 8000, 10000, 0);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().powerProfileModeledOnly().build(),
                calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID)).isNull();
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.24722, 15000);
        assertBluetoothPowerAndDuration(
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_BLUETOOTH),
                0.51944, 9000, 0.51944, 0.36111);
    }

    @Test
    public void testReportedPowerBasedModel() {
        setDurationsAndPower(mStatsRule.getUidStats(Process.BLUETOOTH_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                1000, 2000, 3000, 360000);

        setDurationsAndPower(mStatsRule.getUidStats(APP_UID)
                        .getOrCreateBluetoothControllerActivityLocked(),
                4000, 5000, 6000, 720000);

        setDurationsAndPower((BatteryStatsImpl.ControllerActivityCounterImpl)
                        mStatsRule.getBatteryStats().getBluetoothControllerActivity(),
                6000, 8000, 10000, 1260000);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().powerProfileModeledOnly().build(),
                calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID)).isNull();
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.2, 15000);
        assertBluetoothPowerAndDuration(
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_BLUETOOTH),
                0.45, 9000, 0.45, 0.3);
    }

    private void setDurationsAndPower(
            BatteryStatsImpl.ControllerActivityCounterImpl controllerActivity, int idleDurationMs,
            int rxDurationMs, int txDurationMs, long powerMaMs) {
        controllerActivity.getIdleTimeCounter().addCountLocked(idleDurationMs);
        controllerActivity.getRxTimeCounter().addCountLocked(rxDurationMs);
        controllerActivity.getTxTimeCounters()[0].addCountLocked(txDurationMs);
        controllerActivity.getPowerCounter().addCountLocked(powerMaMs);
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

    private void assertBluetoothPowerAndDuration(@Nullable SystemBatteryConsumer batteryConsumer,
            double powerMah, int durationMs, double consumedPower, double attributedPower) {
        assertBluetoothPowerAndDuration(batteryConsumer, powerMah, durationMs);

        assertThat(batteryConsumer.getConsumedPower())
                .isWithin(PRECISION).of(consumedPower);

        assertThat(batteryConsumer.getPowerConsumedByApps())
                .isWithin(PRECISION).of(attributedPower);
    }
}
