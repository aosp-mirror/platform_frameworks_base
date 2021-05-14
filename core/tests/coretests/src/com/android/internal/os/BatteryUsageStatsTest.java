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
import android.os.UidBatteryConsumer;
import android.os.UserBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsTest {

    @Test
    public void testBuilder() {
        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats().build();
        validateBatteryUsageStats(batteryUsageStats);
    }

    @Test
    public void testParcelability() {
        final BatteryUsageStats outBatteryUsageStats = buildBatteryUsageStats().build();
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


    @Test
    public void testDefaultSessionDuration() {
        final BatteryUsageStats stats =
                buildBatteryUsageStats().setStatsDuration(10000).build();
        assertThat(stats.getStatsDuration()).isEqualTo(10000);
    }

    @Test
    public void testDump() {
        final BatteryUsageStats stats = buildBatteryUsageStats().build();
        final StringWriter out = new StringWriter();
        try (PrintWriter pw = new PrintWriter(out)) {
            stats.dump(pw, "  ");
        }
        final String dump = out.toString();

        assertThat(dump).contains("Capacity: 4000");
        assertThat(dump).contains("Computed drain: 30000");
        assertThat(dump).contains("actual drain: 1000-2000");
        assertThat(dump).contains("cpu: 20100 apps: 10100 duration: 20s 300ms");
        assertThat(dump).contains("FOO: 20200 apps: 10200 duration: 20s 400ms");
        assertThat(dump).contains("UID 2000: 1200 ( screen=300 cpu=400 FOO=500 )");
        assertThat(dump).contains("User 42: 30.0 ( cpu=10.0 FOO=20.0 )");
    }

    @Test
    public void testPowerComponentNames_existAndUnique() {
        Set<String> allNames = new HashSet<>();
        for (int i = 0; i < BatteryConsumer.POWER_COMPONENT_COUNT; i++) {
            assertThat(BatteryConsumer.powerComponentIdToString(i)).isNotNull();
            allNames.add(BatteryConsumer.powerComponentIdToString(i));
        }
        assertThat(allNames).hasSize(BatteryConsumer.POWER_COMPONENT_COUNT);
    }

    private BatteryUsageStats.Builder buildBatteryUsageStats() {
        final MockClocks clocks = new MockClocks();
        final MockBatteryStatsImpl batteryStats = new MockBatteryStatsImpl(clocks);
        final BatteryStatsImpl.Uid batteryStatsUid = batteryStats.getUidStatsLocked(2000);

        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true)
                        .setBatteryCapacity(4000)
                        .setDischargePercentage(20)
                        .setDischargedPowerRange(1000, 2000)
                        .setStatsStartTimestamp(1000)
                        .setStatsEndTimestamp(3000);
        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid)
                .setPackageWithHighestDrain("foo")
                .setTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND, 1000)
                .setTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND, 2000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_SCREEN, 300)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 400)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 500)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 600)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 800);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10100)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10300)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10400);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(30000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20100)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20200)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20300)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20400);

        builder.getOrCreateUserBatteryConsumerBuilder(42)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 30)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 40);

        return builder;
    }

    public void validateBatteryUsageStats(BatteryUsageStats batteryUsageStats) {
        assertThat(batteryUsageStats.getConsumedPower()).isEqualTo(30000);
        assertThat(batteryUsageStats.getBatteryCapacity()).isEqualTo(4000);
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(20);
        assertThat(batteryUsageStats.getDischargedPowerRange().getLower()).isEqualTo(1000);
        assertThat(batteryUsageStats.getDischargedPowerRange().getUpper()).isEqualTo(2000);
        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(1000);
        assertThat(batteryUsageStats.getStatsEndTimestamp()).isEqualTo(3000);
        assertThat(batteryUsageStats.getStatsDuration()).isEqualTo(2000);

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
                        BatteryConsumer.POWER_COMPONENT_SCREEN)).isEqualTo(300);
                assertThat(uidBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(400);
                assertThat(uidBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(500);
                assertThat(uidBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(600);
                assertThat(uidBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(800);
                assertThat(uidBatteryConsumer.getConsumedPower()).isEqualTo(1200);
                assertThat(uidBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
                assertThat(uidBatteryConsumer.getCustomPowerComponentName(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");
            } else {
                fail("Unexpected UID " + uidBatteryConsumer.getUid());
            }
        }

        final BatteryConsumer appsBatteryConsumer = batteryUsageStats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
        assertThat(appsBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(10100);
        assertThat(appsBatteryConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(10200);
        assertThat(appsBatteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(10300);
        assertThat(appsBatteryConsumer.getUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(10400);
        assertThat(appsBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
        assertThat(appsBatteryConsumer.getCustomPowerComponentName(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");

        final BatteryConsumer deviceBatteryConsumer = batteryUsageStats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        assertThat(deviceBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(20100);
        assertThat(deviceBatteryConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(20200);
        assertThat(deviceBatteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(20300);
        assertThat(deviceBatteryConsumer.getUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(20400);
        assertThat(deviceBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
        assertThat(deviceBatteryConsumer.getCustomPowerComponentName(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");

        final List<UserBatteryConsumer> userBatteryConsumers =
                batteryUsageStats.getUserBatteryConsumers();
        for (UserBatteryConsumer userBatteryConsumer : userBatteryConsumers) {
            if (userBatteryConsumer.getUserId() == 42) {
                assertThat(userBatteryConsumer.getConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(10);
                assertThat(userBatteryConsumer.getConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(20);
                assertThat(userBatteryConsumer.getUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(30);
                assertThat(userBatteryConsumer.getUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(40);
                assertThat(userBatteryConsumer.getConsumedPower()).isEqualTo(30);
                assertThat(userBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
                assertThat(userBatteryConsumer.getCustomPowerComponentName(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");
            } else {
                fail("Unexpected user ID " + userBatteryConsumer.getUserId());
            }
        }
    }
}
