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

import static org.junit.Assert.fail;

import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.Parcel;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsTest {

    @Test
    public void testBuilder() {
        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats();
        validateBatteryUsageStats(batteryUsageStats);
    }

    @Test
    public void testParcelability() {
        final BatteryUsageStats outBatteryUsageStats = buildBatteryUsageStats();
        final Parcel outParcel = Parcel.obtain();
        outParcel.writeParcelable(outBatteryUsageStats, 0);
        final byte[] bytes = outParcel.marshall();
        outParcel.recycle();

        final Parcel inParcel = Parcel.obtain();
        inParcel.unmarshall(bytes, 0, bytes.length);
        inParcel.setDataPosition(0);
        final BatteryUsageStats inBatteryUsageStats =
                inParcel.readParcelable(getClass().getClassLoader());
        assertThat(inBatteryUsageStats).isNotNull();
        validateBatteryUsageStats(inBatteryUsageStats);
    }

    private BatteryUsageStats buildBatteryUsageStats() {
        final MockClocks clocks = new MockClocks();
        final MockBatteryStatsImpl batteryStats = new MockBatteryStatsImpl(clocks);
        final BatteryStatsImpl.Uid batteryStatsUid = batteryStats.getUidStatsLocked(2000);

        final BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(1, 1)
                .setDischargePercentage(20)
                .setDischargedPowerRange(1000, 2000)
                .setStatsStartTimestamp(1000);

        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid)
                .setPackageWithHighestDrain("foo")
                .setTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND, 1000)
                .setTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND, 2000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_USAGE, 300)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 400)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 500)
                .setUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU, 600)
                .setUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU_FOREGROUND, 700)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID, 800);

        builder.getOrCreateSystemBatteryConsumerBuilder(SystemBatteryConsumer.DRAIN_TYPE_CAMERA)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10100)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200)
                .setUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU, 10300)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID, 10400)
                .setPowerConsumedByApps(20000);

        return builder.build();
    }

    public void validateBatteryUsageStats(BatteryUsageStats batteryUsageStats) {
        // Camera: (10100 + 10200) - 20000 (consumed by apps) = 300
        // App: 300 + 400 + 500 = 1200
        // Total: 1500
        assertThat(batteryUsageStats.getConsumedPower()).isEqualTo(1500);
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(20);
        assertThat(batteryUsageStats.getDischargedPowerRange().getLower()).isEqualTo(1000);
        assertThat(batteryUsageStats.getDischargedPowerRange().getUpper()).isEqualTo(2000);
        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(1000);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        for (UidBatteryConsumer uidBatteryConsumer : uidBatteryConsumers) {
            if (uidBatteryConsumer.getUid() == 2000) {
                assertThat(uidBatteryConsumer.getPackageWithHighestDrain()).isEqualTo("foo");
                assertThat(uidBatteryConsumer.getTimeInStateMs(
                        UidBatteryConsumer.STATE_FOREGROUND)).isEqualTo(1000);
                assertThat(uidBatteryConsumer.getTimeInStateMs(
                        UidBatteryConsumer.STATE_BACKGROUND)).isEqualTo(2000);
                assertThat(uidBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_USAGE)).isEqualTo(300);
                assertThat(uidBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(400);
                assertThat(uidBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(500);
                assertThat(uidBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU)).isEqualTo(600);
                assertThat(uidBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU_FOREGROUND)).isEqualTo(700);
                assertThat(uidBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID)).isEqualTo(800);
                assertThat(uidBatteryConsumer.getConsumedPower()).isEqualTo(1200);
            } else {
                fail("Unexpected UID " + uidBatteryConsumer.getUid());
            }
        }

        final List<SystemBatteryConsumer> systemBatteryConsumers =
                batteryUsageStats.getSystemBatteryConsumers();
        for (SystemBatteryConsumer systemBatteryConsumer : systemBatteryConsumers) {
            if (systemBatteryConsumer.getDrainType() == SystemBatteryConsumer.DRAIN_TYPE_CAMERA) {
                assertThat(systemBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(10100);
                assertThat(systemBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(10200);
                assertThat(systemBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU)).isEqualTo(10300);
                assertThat(systemBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID)).isEqualTo(10400);
                assertThat(systemBatteryConsumer.getConsumedPower()).isEqualTo(20300);
                assertThat(systemBatteryConsumer.getPowerConsumedByApps()).isEqualTo(20000);
            } else {
                fail("Unexpected drain type " + systemBatteryConsumer.getDrainType());
            }
        }
    }
}
