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
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.UidTraffic;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;

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
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX, 100.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE, 3700);

    @Test
    public void testTimerBasedModel() {
        setupBluetoothEnergyInfo(0, BatteryStats.POWER_DATA_UNAVAILABLE);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertCalculatedPower(0.08216, 0.18169, 0.26388, 0.26386,
                BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testReportedEnergyBasedModel() {
        setupBluetoothEnergyInfo(4000000, BatteryStats.POWER_DATA_UNAVAILABLE);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().includePowerModels().build(),
                calculator);

        assertCalculatedPower(0.08216, 0.18169, 0.30030, 0.26386,
                BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        setupBluetoothEnergyInfo(0, 1200000);

        final BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        assertCalculatedPower(0.10378, 0.22950, 0.33333, 0.33329,
                BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testIgnoreMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        setupBluetoothEnergyInfo(4000000, 1200000);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertCalculatedPower(0.08216, 0.18169, 0.26388, 0.26386,
                BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    private void setupBluetoothEnergyInfo(long reportedEnergyUc, long consumedEnergyUc) {
        final BluetoothActivityEnergyInfo info = new BluetoothActivityEnergyInfo(1000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 7000, 5000, 0,
                reportedEnergyUc);
        info.setUidTraffic(new UidTraffic[]{
                new UidTraffic(Process.BLUETOOTH_UID, 1000, 2000),
                new UidTraffic(APP_UID, 3000, 4000)
        });
        mStatsRule.getBatteryStats().updateBluetoothStateLocked(info,
                consumedEnergyUc, 1000, 1000);
    }

    private void assertCalculatedPower(double bluetoothUidPowerMah, double appPowerMah,
            double devicePowerMah, double allAppsPowerMah, int powerModelPowerProfile) {
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                bluetoothUidPowerMah, 3583, powerModelPowerProfile);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                appPowerMah, 8416, powerModelPowerProfile);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                devicePowerMah, 12000, powerModelPowerProfile);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                allAppsPowerMah, 11999, powerModelPowerProfile);
    }

    private void assertBluetoothPowerAndDuration(@Nullable BatteryConsumer batteryConsumer,
            double powerMah, int durationMs, @BatteryConsumer.PowerModel int powerModel) {
        assertThat(batteryConsumer).isNotNull();

        double consumedPower = batteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        assertThat(consumedPower).isWithin(PRECISION).of(powerMah);
        assertThat(batteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(powerModel);

        long usageDurationMillis = batteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        assertThat(usageDurationMillis).isEqualTo(durationMs);
    }
}
