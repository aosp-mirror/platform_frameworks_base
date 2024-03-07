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

import static android.os.BatteryConsumer.POWER_COMPONENT_ANY;
import static android.os.BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION;
import static android.os.BatteryConsumer.POWER_MODEL_UNDEFINED;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.Parcel;
import android.os.UidBatteryConsumer;
import android.os.UserBatteryConsumer;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsTest {

    private static final int USER_ID = 42;
    private static final int APP_UID1 = 271;
    private static final int APP_UID2 = 314;

    @Test
    public void testBuilder() {
        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats1(true).build();
        assertBatteryUsageStats1(batteryUsageStats, true);
    }

    @Test
    public void testBuilder_noProcessStateData() {
        BatteryUsageStats batteryUsageStats = buildBatteryUsageStats1(false).build();
        assertBatteryUsageStats1(batteryUsageStats, false);
    }

    @Test
    public void testParcelability_smallNumberOfUids() {
        final BatteryUsageStats outBatteryUsageStats = buildBatteryUsageStats1(true).build();
        final Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(outBatteryUsageStats, 0);

        assertThat(parcel.dataSize()).isLessThan(8000);

        parcel.setDataPosition(0);

        final BatteryUsageStats inBatteryUsageStats =
                parcel.readParcelable(getClass().getClassLoader());
        assertThat(inBatteryUsageStats).isNotNull();
        assertBatteryUsageStats1(inBatteryUsageStats, true);
    }

    @Test
    public void testParcelability_largeNumberOfUids() {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[0]);

        // Without the use of a CursorWindow, this BatteryUsageStats object would generate a Parcel
        // larger than 64 Kb
        final int uidCount = 200;
        for (int i = 0; i < uidCount; i++) {
            BatteryStatsImpl.Uid mockUid = mock(BatteryStatsImpl.Uid.class);
            when(mockUid.getUid()).thenReturn(i);
            builder.getOrCreateUidBatteryConsumerBuilder(mockUid)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, i * 100);
        }

        BatteryUsageStats outBatteryUsageStats = builder.build();

        final Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(outBatteryUsageStats, 0);

        assertThat(parcel.dataSize()).isLessThan(2000);

        parcel.setDataPosition(0);

        final BatteryUsageStats inBatteryUsageStats =
                parcel.readParcelable(getClass().getClassLoader());
        parcel.recycle();

        assertThat(inBatteryUsageStats.getUidBatteryConsumers()).hasSize(uidCount);
        final Map<Integer, UidBatteryConsumer> consumersByUid =
                inBatteryUsageStats.getUidBatteryConsumers().stream().collect(
                        Collectors.toMap(UidBatteryConsumer::getUid, c -> c));
        for (int i = 0; i < uidCount; i++) {
            final UidBatteryConsumer uidBatteryConsumer = consumersByUid.get(i);
            assertThat(uidBatteryConsumer).isNotNull();
            assertThat(uidBatteryConsumer.getConsumedPower()).isEqualTo(i * 100);
        }
    }

    @Test
    public void testDefaultSessionDuration() {
        final BatteryUsageStats stats =
                buildBatteryUsageStats1(true).setStatsDuration(10000).build();
        assertThat(stats.getStatsDuration()).isEqualTo(10000);
    }

    @Test
    public void testDump() {
        final BatteryUsageStats stats = buildBatteryUsageStats1(true).build();
        final StringWriter out = new StringWriter();
        try (PrintWriter pw = new PrintWriter(out)) {
            stats.dump(pw, "  ");
        }
        final String dump = out.toString();

        assertThat(dump).contains("Capacity: 4000");
        assertThat(dump).contains("Computed drain: 30000");
        assertThat(dump).contains("actual drain: 1000-2000");
        assertThat(dump).contains("cpu: 20100 apps: 10100 duration: 20s 300ms");
        assertThat(dump).contains("cpu(fg): 2333 apps: 1333 duration: 3s 332ms");
        assertThat(dump).contains("cpu(bg): 2444 apps: 1444 duration: 4s 442ms");
        assertThat(dump).contains("cpu(fgs): 2555 apps: 1555 duration: 5s 552ms");
        assertThat(dump).contains("cpu(cached): 123 apps: 123 duration: 456ms");
        assertThat(dump).contains("FOO: 20200 apps: 10200 duration: 20s 400ms");
        assertThat(dump).contains("UID 271: 1200 fg: 1777 bg: 1888 fgs: 1999 cached: 123 "
                + "( screen=300 cpu=400 (600ms) cpu:fg=1777 (7s 771ms) cpu:bg=1888 (8s 881ms) "
                + "cpu:fgs=1999 (9s 991ms) cpu:cached=123 (456ms) FOO=500 )");
        assertThat(dump).contains("User 42: 30.0 ( cpu=10.0 (30ms) FOO=20.0 )");
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

    @Test
    public void testAdd() {
        final BatteryUsageStats stats1 = buildBatteryUsageStats1(false).build();
        final BatteryUsageStats stats2 = buildBatteryUsageStats2(new String[]{"FOO"}, true).build();

        final BatteryUsageStats sum =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true, true, 0)
                        .add(stats1)
                        .add(stats2)
                        .build();

        assertBatteryUsageStats(sum, 42345, 50, 2234, 4345, 1234, 1000, 5000, 5000);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                sum.getUidBatteryConsumers();
        for (UidBatteryConsumer uidBatteryConsumer : uidBatteryConsumers) {
            if (uidBatteryConsumer.getUid() == APP_UID1) {
                assertUidBatteryConsumer(uidBatteryConsumer, 2124, null,
                        5321, 6900, 532, 423, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 745,
                        POWER_MODEL_UNDEFINED,
                        956, 1167, 1478,
                        true, 3554, 3776, 3998, 444, 3554, 15542, 3776, 17762, 3998, 19982,
                        444, 1110);
            } else if (uidBatteryConsumer.getUid() == APP_UID2) {
                assertUidBatteryConsumer(uidBatteryConsumer, 1332, "bar",
                        1111, 2220, 2, 333, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 444,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE,
                        555, 666, 777,
                        true, 1777, 1888, 1999, 321, 1777, 7771, 1888, 8881, 1999, 9991,
                        321, 654);
            } else {
                fail("Unexpected UID " + uidBatteryConsumer.getUid());
            }
        }

        assertAggregateBatteryConsumer(sum,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                20223, 20434, 20645, 20856);

        assertAggregateBatteryConsumer(sum,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                40211, 40422, 40633, 40844);
    }

    @Test
    public void testAdd_customComponentMismatch() {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true, true, 0);
        final BatteryUsageStats stats = buildBatteryUsageStats2(new String[]{"BAR"}, false).build();

        assertThrows(IllegalArgumentException.class, () -> builder.add(stats));
    }

    @Test
    public void testAdd_processStateDataMismatch() {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true, true, 0);
        final BatteryUsageStats stats = buildBatteryUsageStats2(new String[]{"FOO"}, false).build();

        assertThrows(IllegalArgumentException.class, () -> builder.add(stats));
    }

    @Test
    public void testXml() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        final BatteryUsageStats stats = buildBatteryUsageStats1(true).build();
        stats.writeXml(serializer);
        serializer.endDocument();

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(in, StandardCharsets.UTF_8.name());
        final BatteryUsageStats fromXml = BatteryUsageStats.createFromXml(parser);

        assertBatteryUsageStats1(fromXml, true);
    }

    private BatteryUsageStats.Builder buildBatteryUsageStats1(boolean includeUserBatteryConsumer) {
        final MockClock clocks = new MockClock();
        final MockBatteryStatsImpl batteryStats = new MockBatteryStatsImpl(clocks);

        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true, true, 0)
                        .setBatteryCapacity(4000)
                        .setDischargePercentage(20)
                        .setDischargedPowerRange(1000, 2000)
                        .setDischargeDurationMs(1234)
                        .setStatsStartTimestamp(1000)
                        .setStatsEndTimestamp(3000);

        addUidBatteryConsumer(builder, batteryStats, APP_UID1, "foo",
                1000, 1500, 500,
                300, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 400,
                BatteryConsumer.POWER_MODEL_POWER_PROFILE, 500, 600, 800,
                1777, 7771, 1888, 8881, 1999, 9991, 123, 456);

        addAggregateBatteryConsumer(builder,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS, 0,
                10100, 10200, 10300, 10400,
                1333, 3331, 1444, 4441, 1555, 5551, 123, 456);

        addAggregateBatteryConsumer(builder,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE, 30000,
                20100, 20200, 20300, 20400,
                2333, 3332, 2444, 4442, 2555, 5552, 123, 456);

        if (includeUserBatteryConsumer) {
            builder.getOrCreateUserBatteryConsumerBuilder(USER_ID)
                    .setConsumedPower(
                            BatteryConsumer.POWER_COMPONENT_CPU, 10)
                    .setConsumedPowerForCustomComponent(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20)
                    .setUsageDurationMillis(
                            BatteryConsumer.POWER_COMPONENT_CPU, 30)
                    .setUsageDurationForCustomComponentMillis(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 40);
        }
        return builder;
    }

    private BatteryUsageStats.Builder buildBatteryUsageStats2(String[] customPowerComponentNames,
            boolean includeProcessStateData) {
        final MockClock clocks = new MockClock();
        final MockBatteryStatsImpl batteryStats = new MockBatteryStatsImpl(clocks);

        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(customPowerComponentNames, true,
                        includeProcessStateData, 0);
        builder.setDischargePercentage(30)
                .setDischargedPowerRange(1234, 2345)
                .setStatsStartTimestamp(2000)
                .setStatsEndTimestamp(5000);

        addUidBatteryConsumer(builder, batteryStats, APP_UID1, null,
                4321, 5400, 32,
                123, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 345, POWER_MODEL_ENERGY_CONSUMPTION,
                456, 567, 678,
                1777, 7771, 1888, 8881, 1999, 9991, 321, 654);

        addUidBatteryConsumer(builder, batteryStats, APP_UID2, "bar",
                1111, 2220, 2,
                333, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 444,
                BatteryConsumer.POWER_MODEL_POWER_PROFILE, 555, 666, 777,
                1777, 7771, 1888, 8881, 1999, 9991, 321, 654);

        addAggregateBatteryConsumer(builder,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS, 0,
                10123, 10234, 10345, 10456,
                4333, 3334, 5444, 4445, 6555, 5556, 321, 654);

        addAggregateBatteryConsumer(builder,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE, 12345,
                20111, 20222, 20333, 20444,
                7333, 3337, 8444, 4448, 9555, 5559, 123, 456);

        return builder;
    }

    private void addUidBatteryConsumer(BatteryUsageStats.Builder builder,
            MockBatteryStatsImpl batteryStats, int uid, String packageWithHighestDrain,
            int timeInProcessStateForeground, int timeInProcessStateBackground,
            int timeInProcessStateForegroundService, double screenPower,
            int screenPowerModel, double cpuPower, int cpuPowerModel, double customComponentPower,
            int cpuDuration, int customComponentDuration, double cpuPowerForeground,
            int cpuDurationForeground, double cpuPowerBackground, int cpuDurationBackground,
            double cpuPowerFgs, int cpuDurationFgs, double cpuPowerCached, long cpuDurationCached) {
        final BatteryStatsImpl.Uid batteryStatsUid = batteryStats.getUidStatsLocked(uid);
        final UidBatteryConsumer.Builder uidBuilder =
                builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid);
        uidBuilder
                .setPackageWithHighestDrain(packageWithHighestDrain)
                .setTimeInProcessStateMs(PROCESS_STATE_FOREGROUND, timeInProcessStateForeground)
                .setTimeInProcessStateMs(PROCESS_STATE_BACKGROUND, timeInProcessStateBackground)
                .setTimeInProcessStateMs(PROCESS_STATE_FOREGROUND_SERVICE,
                        timeInProcessStateForegroundService)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_SCREEN, screenPower, screenPowerModel)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, cpuPower, cpuPowerModel)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, customComponentPower)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, cpuDuration)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, customComponentDuration);
        if (builder.isProcessStateDataNeeded()) {
            final BatteryConsumer.Key cpuFgKey = uidBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND);
            final BatteryConsumer.Key cpuBgKey = uidBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_BACKGROUND);
            final BatteryConsumer.Key cpuFgsKey = uidBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
            final BatteryConsumer.Key cachedKey = uidBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_CACHED);
            uidBuilder
                    .setConsumedPower(cpuFgKey, cpuPowerForeground,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuFgKey, cpuDurationForeground)
                    .setConsumedPower(cpuBgKey, cpuPowerBackground,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuBgKey, cpuDurationBackground)
                    .setConsumedPower(cpuFgsKey, cpuPowerFgs,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuFgsKey, cpuDurationFgs)
                    .setConsumedPower(cachedKey, cpuPowerCached,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cachedKey, cpuDurationCached);
        }
    }

    private void addAggregateBatteryConsumer(BatteryUsageStats.Builder builder, int scope,
            double consumedPower, int cpuPower, int customComponentPower, int cpuDuration,
            int customComponentDuration, double cpuPowerForeground, long cpuDurationForeground,
            double cpuPowerBackground, long cpuDurationBackground, double cpuPowerFgs,
            long cpuDurationFgs, double cpuPowerCached, long cpuDurationCached) {
        final AggregateBatteryConsumer.Builder aggBuilder =
                builder.getAggregateBatteryConsumerBuilder(scope)
                        .setConsumedPower(consumedPower)
                        .setConsumedPower(
                                BatteryConsumer.POWER_COMPONENT_CPU, cpuPower)
                        .setConsumedPowerForCustomComponent(
                                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                                customComponentPower)
                        .setUsageDurationMillis(
                                BatteryConsumer.POWER_COMPONENT_CPU, cpuDuration)
                        .setUsageDurationForCustomComponentMillis(
                                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                                customComponentDuration);
        if (builder.isProcessStateDataNeeded()) {
            final BatteryConsumer.Key cpuFgKey = aggBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND);
            final BatteryConsumer.Key cpuBgKey = aggBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_BACKGROUND);
            final BatteryConsumer.Key cpuFgsKey = aggBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
            final BatteryConsumer.Key cpuCachedKey = aggBuilder.getKey(
                    BatteryConsumer.POWER_COMPONENT_CPU,
                    BatteryConsumer.PROCESS_STATE_CACHED);
            aggBuilder
                    .setConsumedPower(cpuFgKey, cpuPowerForeground,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuFgKey, cpuDurationForeground)
                    .setConsumedPower(cpuBgKey, cpuPowerBackground,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuBgKey, cpuDurationBackground)
                    .setConsumedPower(cpuFgsKey, cpuPowerFgs,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuFgsKey, cpuDurationFgs)
                    .setConsumedPower(cpuCachedKey, cpuPowerCached,
                            BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(cpuCachedKey, cpuDurationCached);
        }
    }

    public void assertBatteryUsageStats1(BatteryUsageStats batteryUsageStats,
            boolean includesUserBatteryConsumers) {
        assertBatteryUsageStats(batteryUsageStats, 30000, 20, 1000, 2000, 1234, 1000, 3000, 2000);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        assertThat(uidBatteryConsumers).hasSize(1);
        for (UidBatteryConsumer uidBatteryConsumer : uidBatteryConsumers) {
            if (uidBatteryConsumer.getUid() == APP_UID1) {
                assertUidBatteryConsumer(uidBatteryConsumer, 1200, "foo",
                        1000, 1500, 500, 300, BatteryConsumer.POWER_MODEL_POWER_PROFILE, 400,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE,
                        500, 600, 800,
                        true, 1777, 1888, 1999, 123, 1777, 7771, 1888, 8881, 1999, 9991, 123, 456);
            } else {
                fail("Unexpected UID " + uidBatteryConsumer.getUid());
            }
        }

        final List<UserBatteryConsumer> userBatteryConsumers =
                batteryUsageStats.getUserBatteryConsumers();
        if (includesUserBatteryConsumers) {
            assertThat(userBatteryConsumers).hasSize(1);
            for (UserBatteryConsumer userBatteryConsumer : userBatteryConsumers) {
                if (userBatteryConsumer.getUserId() == USER_ID) {
                    assertUserBatteryConsumer(userBatteryConsumer, 42, 10, 20, 30, 40);
                } else {
                    fail("Unexpected User ID " + userBatteryConsumer.getUserId());
                }
            }
        } else {
            assertThat(userBatteryConsumers).isEmpty();
        }

        assertAggregateBatteryConsumer(batteryUsageStats,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                10100, 10200, 10300, 10400);

        assertAggregateBatteryConsumer(batteryUsageStats,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                20100, 20200, 20300, 20400);
    }

    private void assertBatteryUsageStats(BatteryUsageStats batteryUsageStats, int consumedPower,
            int dischargePercentage, int dischagePowerLower, int dischargePowerUpper,
            int dischargeDuration, int statsStartTimestamp, int statsEndTimestamp,
            int statsDuration) {
        assertThat(batteryUsageStats.getConsumedPower()).isEqualTo(consumedPower);
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(dischargePercentage);
        assertThat(batteryUsageStats.getDischargedPowerRange().getLower()).isEqualTo(
                dischagePowerLower);
        assertThat(batteryUsageStats.getDischargedPowerRange().getUpper()).isEqualTo(
                dischargePowerUpper);
        assertThat(batteryUsageStats.getDischargeDurationMs()).isEqualTo(dischargeDuration);
        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(statsStartTimestamp);
        assertThat(batteryUsageStats.getStatsEndTimestamp()).isEqualTo(statsEndTimestamp);
        assertThat(batteryUsageStats.getStatsDuration()).isEqualTo(statsDuration);
    }

    private void assertUidBatteryConsumer(UidBatteryConsumer uidBatteryConsumer,
            double consumedPower, String packageWithHighestDrain, int timeInProcessStateForeground,
            int timeInProcessStateBackground, int timeInProcessStateForegroundService,
            int screenPower, int screenPowerModel, double cpuPower,
            int cpuPowerModel, double customComponentPower, int cpuDuration,
            int customComponentDuration, boolean processStateDataIncluded,
            double totalPowerForeground, double totalPowerBackground, double totalPowerFgs,
            double totalPowerCached, double cpuPowerForeground, int cpuDurationForeground,
            double cpuPowerBackground,
            int cpuDurationBackground, double cpuPowerFgs, int cpuDurationFgs,
            int cpuPowerCached, int cpuDurationCached) {
        assertThat(uidBatteryConsumer.getConsumedPower()).isEqualTo(consumedPower);
        assertThat(uidBatteryConsumer.getPackageWithHighestDrain()).isEqualTo(
                packageWithHighestDrain);
        assertThat(uidBatteryConsumer.getTimeInStateMs(
                UidBatteryConsumer.STATE_FOREGROUND)).isEqualTo(timeInProcessStateForeground);
        assertThat(uidBatteryConsumer.getTimeInStateMs(
                UidBatteryConsumer.STATE_BACKGROUND)).isEqualTo(
                        timeInProcessStateBackground + timeInProcessStateForegroundService);
        assertThat(uidBatteryConsumer.getTimeInProcessStateMs(
                PROCESS_STATE_FOREGROUND)).isEqualTo(timeInProcessStateForeground);
        assertThat(uidBatteryConsumer.getTimeInProcessStateMs(
                PROCESS_STATE_BACKGROUND)).isEqualTo(timeInProcessStateBackground);
        assertThat(uidBatteryConsumer.getTimeInProcessStateMs(
                PROCESS_STATE_FOREGROUND_SERVICE)).isEqualTo(timeInProcessStateForegroundService);
        assertThat(uidBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_SCREEN)).isEqualTo(screenPower);
        assertThat(uidBatteryConsumer.getPowerModel(
                BatteryConsumer.POWER_COMPONENT_SCREEN)).isEqualTo(screenPowerModel);
        assertThat(uidBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuPower);
        assertThat(uidBatteryConsumer.getPowerModel(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuPowerModel);
        assertThat(uidBatteryConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(customComponentPower);
        assertThat(uidBatteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuDuration);
        assertThat(uidBatteryConsumer.getUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(
                customComponentDuration);
        assertThat(uidBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
        assertThat(uidBatteryConsumer.getCustomPowerComponentName(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");

        if (processStateDataIncluded) {
            assertThat(uidBatteryConsumer.getConsumedPower(
                    new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY,
                            PROCESS_STATE_FOREGROUND)))
                    .isEqualTo(totalPowerForeground);
            assertThat(uidBatteryConsumer.getConsumedPower(
                    new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY,
                            PROCESS_STATE_BACKGROUND)))
                    .isEqualTo(totalPowerBackground);
            assertThat(uidBatteryConsumer.getConsumedPower(
                    new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY,
                            PROCESS_STATE_FOREGROUND_SERVICE)))
                    .isEqualTo(totalPowerFgs);
            assertThat(uidBatteryConsumer.getConsumedPower(
                    new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY,
                            PROCESS_STATE_CACHED)))
                    .isEqualTo(totalPowerCached);
        }

        final BatteryConsumer.Key cpuFgKey = uidBatteryConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU, BatteryConsumer.PROCESS_STATE_FOREGROUND);
        if (processStateDataIncluded) {
            assertThat(cpuFgKey).isNotNull();
            assertThat(uidBatteryConsumer.getConsumedPower(cpuFgKey))
                    .isEqualTo(cpuPowerForeground);
            assertThat(uidBatteryConsumer.getUsageDurationMillis(cpuFgKey))
                    .isEqualTo(cpuDurationForeground);
        } else {
            assertThat(cpuFgKey).isNull();
        }

        final BatteryConsumer.Key cpuBgKey = uidBatteryConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU, BatteryConsumer.PROCESS_STATE_BACKGROUND);
        if (processStateDataIncluded) {
            assertThat(cpuBgKey).isNotNull();
            assertThat(uidBatteryConsumer.getConsumedPower(cpuBgKey))
                    .isEqualTo(cpuPowerBackground);
            assertThat(uidBatteryConsumer.getUsageDurationMillis(cpuBgKey))
                    .isEqualTo(cpuDurationBackground);
        } else {
            assertThat(cpuBgKey).isNull();
        }

        final BatteryConsumer.Key cpuFgsKey = uidBatteryConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
        if (processStateDataIncluded) {
            assertThat(cpuFgsKey).isNotNull();
            assertThat(uidBatteryConsumer.getConsumedPower(cpuFgsKey))
                    .isEqualTo(cpuPowerFgs);
            assertThat(uidBatteryConsumer.getUsageDurationMillis(cpuFgsKey))
                    .isEqualTo(cpuDurationFgs);
        } else {
            assertThat(cpuFgsKey).isNotNull();
        }

        final BatteryConsumer.Key cachedKey = uidBatteryConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_CACHED);
        if (processStateDataIncluded) {
            assertThat(cachedKey).isNotNull();
            assertThat(uidBatteryConsumer.getConsumedPower(cachedKey))
                    .isEqualTo(cpuPowerCached);
            assertThat(uidBatteryConsumer.getUsageDurationMillis(cachedKey))
                    .isEqualTo(cpuDurationCached);
        } else {
            assertThat(cpuFgsKey).isNotNull();
        }
    }

    private void assertUserBatteryConsumer(UserBatteryConsumer userBatteryConsumer,
            int userId, int cpuPower, int customComponentPower,
            int cpuDuration, int customComponentDuration) {
        assertThat(userBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuPower);
        assertThat(userBatteryConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(customComponentPower);
        assertThat(userBatteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuDuration);
        assertThat(userBatteryConsumer.getUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(
                customComponentDuration);
        assertThat(userBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
        assertThat(userBatteryConsumer.getCustomPowerComponentName(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");
    }

    private void assertAggregateBatteryConsumer(BatteryUsageStats batteryUsageStats,
            int aggregateBatteryConsumerScopeAllApps, int cpuPower, int customComponentPower,
            int cpuDuration, int customComponentDuration) {
        final BatteryConsumer appsBatteryConsumer = batteryUsageStats.getAggregateBatteryConsumer(
                aggregateBatteryConsumerScopeAllApps);
        assertThat(appsBatteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuPower);
        assertThat(appsBatteryConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(customComponentPower);
        assertThat(appsBatteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_CPU)).isEqualTo(cpuDuration);
        assertThat(appsBatteryConsumer.getUsageDurationForCustomComponentMillis(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo(
                customComponentDuration);
        assertThat(appsBatteryConsumer.getCustomPowerComponentCount()).isEqualTo(1);
        assertThat(appsBatteryConsumer.getCustomPowerComponentName(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID)).isEqualTo("FOO");
    }
}
