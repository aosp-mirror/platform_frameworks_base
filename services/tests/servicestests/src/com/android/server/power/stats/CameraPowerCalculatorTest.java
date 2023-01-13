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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CameraPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP1_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP2_UID = Process.FIRST_APPLICATION_UID + 43;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CAMERA, 360.0)
            .initMeasuredEnergyStatsLocked();

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        synchronized (stats) { // To keep the GuardedBy check happy
            stats.noteCameraOnLocked(APP1_UID, 1000, 1000);
            stats.noteCameraOffLocked(APP1_UID, 2000, 2000);
            stats.noteCameraOnLocked(APP2_UID, 3000, 3000);
            stats.noteCameraOffLocked(APP2_UID, 5000, 5000);
        }

        CameraPowerCalculator calculator =
                new CameraPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer app1Consumer = mStatsRule.getUidBatteryConsumer(APP1_UID);
        assertThat(app1Consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(1000);
        assertThat(app1Consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.1);
        assertThat(app1Consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        UidBatteryConsumer app2Consumer = mStatsRule.getUidBatteryConsumer(APP2_UID);
        assertThat(app2Consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(2000);
        assertThat(app2Consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.2);
        assertThat(app2Consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        final BatteryConsumer deviceBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceBatteryConsumer
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(3000);
        assertThat(deviceBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.3);
        assertThat(deviceBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        final BatteryConsumer appsBatteryConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsBatteryConsumer
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(3000);
        assertThat(appsBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.3);
        assertThat(appsBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testEnergyConsumptionBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        synchronized (stats) { // To keep the GuardedBy check happy
            stats.noteCameraOnLocked(APP1_UID, 1000, 1000);
            stats.noteCameraOffLocked(APP1_UID, 2000, 2000);
            stats.updateCameraEnergyConsumerStatsLocked(720_000, 2100); // 0.72C == 0.2mAh
            stats.noteCameraOnLocked(APP2_UID, 3000, 3000);
            stats.noteCameraOffLocked(APP2_UID, 5000, 5000);
            stats.updateCameraEnergyConsumerStatsLocked(1_080_000, 5100); // 0.3mAh
        }

        CameraPowerCalculator calculator =
                new CameraPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer app1Consumer = mStatsRule.getUidBatteryConsumer(APP1_UID);
        assertThat(app1Consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(1000);
        assertThat(app1Consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.2);
        assertThat(app1Consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer app2Consumer = mStatsRule.getUidBatteryConsumer(APP2_UID);
        assertThat(app2Consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(2000);
        assertThat(app2Consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.3);
        assertThat(app2Consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        final BatteryConsumer deviceBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceBatteryConsumer
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(3000);
        assertThat(deviceBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.5);
        assertThat(deviceBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        final BatteryConsumer appsBatteryConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsBatteryConsumer
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(3000);
        assertThat(appsBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isWithin(PRECISION).of(0.5);
        assertThat(appsBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CAMERA))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }
}
