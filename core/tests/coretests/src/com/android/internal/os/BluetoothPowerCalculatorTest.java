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
            .initMeasuredEnergyStatsLocked();

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

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.11388, 6000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.24722, 15000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.40555, 24000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.36111, 21000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
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

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.1, 6000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.2, 15000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.35, 24000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.3, 21000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        final BluetoothActivityEnergyInfo info = new BluetoothActivityEnergyInfo(1000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 7000, 5000, 0, 100000);
        info.setUidTraffic(new UidTraffic[]{
                new UidTraffic(Process.BLUETOOTH_UID, 1000, 2000),
                new UidTraffic(APP_UID, 3000, 4000)
        });
        mStatsRule.getBatteryStats().updateBluetoothStateLocked(info, 1200000, 1000, 1000);

        final BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().includePowerModels().build(),
                calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.10378, 3583, BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.22950, 8416, BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.33333, 12000, BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.33329, 11999, BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
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
