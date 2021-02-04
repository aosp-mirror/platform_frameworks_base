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

        final BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(1, 1, true);
        builder.setConsumedPower(100);
        builder.setDischargePercentage(20);

        final UidBatteryConsumer.Builder uidBatteryConsumerBuilder =
                builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid);
        uidBatteryConsumerBuilder.setPackageWithHighestDrain("foo");
        uidBatteryConsumerBuilder.setConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE, 300);
        uidBatteryConsumerBuilder.setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 400);
        uidBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 500);
        uidBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_MODELED_POWER_COMPONENT_ID
                        + BatteryConsumer.POWER_COMPONENT_CPU, 510);
        uidBatteryConsumerBuilder.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_CPU, 600);
        uidBatteryConsumerBuilder.setUsageDurationMillis(
                BatteryConsumer.TIME_COMPONENT_CPU_FOREGROUND, 700);
        uidBatteryConsumerBuilder.setUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID, 800);

        final SystemBatteryConsumer.Builder systemBatteryConsumerBuilder =
                builder.getOrCreateSystemBatteryConsumerBuilder(
                        SystemBatteryConsumer.DRAIN_TYPE_CAMERA);
        systemBatteryConsumerBuilder.setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 10100);
        systemBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200);
        systemBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_MODELED_POWER_COMPONENT_ID
                        + BatteryConsumer.POWER_COMPONENT_CPU, 10210);
        systemBatteryConsumerBuilder.setUsageDurationMillis(
                BatteryConsumer.TIME_COMPONENT_CPU, 10300);
        systemBatteryConsumerBuilder.setUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID, 10400);

        return builder.build();
    }

    public void validateBatteryUsageStats(BatteryUsageStats batteryUsageStats) {
        assertThat(batteryUsageStats.getConsumedPower()).isEqualTo(100);
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(20);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        for (UidBatteryConsumer uidBatteryConsumer : uidBatteryConsumers) {
            if (uidBatteryConsumer.getUid() == 2000) {
                assertThat(uidBatteryConsumer.getPackageWithHighestDrain()).isEqualTo("foo");
                assertThat(uidBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_USAGE)).isEqualTo(300);
                assertThat(uidBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(400);
                assertThat(uidBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(500);
                assertThat(uidBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_MODELED_POWER_COMPONENT_ID
                                + BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(510);
                assertThat(uidBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU)).isEqualTo(600);
                assertThat(uidBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU_FOREGROUND)).isEqualTo(700);
                assertThat(uidBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID)).isEqualTo(800);
                assertThat(uidBatteryConsumer.getConsumedPower()).isEqualTo(1710);
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
                assertThat(systemBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_MODELED_POWER_COMPONENT_ID
                                + BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(10210);
                assertThat(systemBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.TIME_COMPONENT_CPU)).isEqualTo(10300);
                assertThat(systemBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID)).isEqualTo(10400);
                assertThat(systemBatteryConsumer.getConsumedPower()).isEqualTo(30510);
            } else {
                fail("Unexpected drain type " + systemBatteryConsumer.getDrainType());
            }
        }
    }
}
