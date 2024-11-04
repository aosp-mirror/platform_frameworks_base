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
package com.android.server.power.stats;

import static android.os.BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.nano.BatteryUsageStatsAtomsProto;
import android.os.nano.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.StatsEvent;

import androidx.test.filters.SmallTest;

import com.android.server.am.BatteryStatsService;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
public class BatteryUsageStatsAtomTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static final boolean DEBUG = false;
    private static final int UID_0 = 1000;
    private static final int UID_1 = 2000;
    private static final int UID_2 = 3000;
    private static final int UID_3 = 4000;

    @Test
    public void testAtom_BatteryUsageStatsPerUid() throws Exception {
        final BatteryUsageStats bus = buildBatteryUsageStats();
        BatteryStatsService.FrameworkStatsLogger statsLogger =
                mock(BatteryStatsService.FrameworkStatsLogger.class);

        List<StatsEvent> actual = new ArrayList<>();
        new BatteryStatsService.StatsPerUidLogger(statsLogger).logStats(bus, actual);

        bus.close();

        if (DEBUG) {
            System.out.println(mockingDetails(statsLogger).printInvocations());
        }

        // Device-wide totals
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                Process.INVALID_UID,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "cpu",
                30000.0f,
                20100.0f,
                20300L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                Process.INVALID_UID,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "camera",
                30000.0f,
                20150.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                Process.INVALID_UID,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "CustomConsumer1",
                30000.0f,
                20200.0f,
                20400L
        );

        // Per-proc state estimates for UID_0
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "screen",
                1650.0f,
                300.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "cpu",
                1650.0f,
                400.0f,
                600L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_FOREGROUND,
                1000L,
                "cpu",
                1650.0f,
                9100.0f,
                8100L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_BACKGROUND,
                2000L,
                "cpu",
                1650.0f,
                9200.0f,
                8200L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                0L,
                "cpu",
                1650.0f,
                9300.0f,
                8400L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_CACHED,
                0L,
                "cpu",
                1650.0f,
                9400.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "CustomConsumer1",
                1650.0f,
                450.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_FOREGROUND,
                1000L,
                "CustomConsumer1",
                1650.0f,
                100.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_BACKGROUND,
                2000L,
                "CustomConsumer1",
                1650.0f,
                350.0f,
                0L
        );
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_0,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0,
                "CustomConsumer2",
                1650.0f,
                500.0f,
                800L
        );

        // Nothing for UID_1, because its power consumption is 0

        // Only "screen" is populated for UID_2
        verify(statsLogger).buildStatsEvent(
                1000L,
                20000L,
                10000L,
                20,
                1234L,
                UID_2,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED,
                0L,
                "screen",
                766.0f,
                766.0f,
                0L
        );

        verifyNoMoreInteractions(statsLogger);
    }

    @Test
    public void testAtom_BatteryUsageStatsAtomsProto() throws Exception {
        final BatteryUsageStats bus = buildBatteryUsageStats();
        final byte[] bytes = bus.getStatsProto();
        BatteryUsageStatsAtomsProto proto;
        try {
            proto = BatteryUsageStatsAtomsProto.parseFrom(bytes);
        } catch (InvalidProtocolBufferNanoException e) {
            fail("Invalid proto: " + e);
            return;
        }

        assertEquals(bus.getStatsStartTimestamp(), proto.sessionStartMillis);
        assertEquals(bus.getStatsEndTimestamp(), proto.sessionEndMillis);
        assertEquals(10000, proto.sessionDurationMillis);
        assertEquals(bus.getDischargePercentage(), proto.sessionDischargePercentage);
        assertEquals(bus.getDischargeDurationMs(), proto.dischargeDurationMillis);

        assertEquals(3, proto.deviceBatteryConsumer.powerComponents.length); // Only 3 are non-empty

        final AggregateBatteryConsumer abc = bus.getAggregateBatteryConsumer(
                AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        assertSameBatteryConsumer("For deviceBatteryConsumer",
                bus.getAggregateBatteryConsumer(AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE),
                proto.deviceBatteryConsumer);

        for (int i = 0; i < BatteryConsumer.POWER_COMPONENT_COUNT; i++) {
            assertPowerComponentModel(i, abc.getPowerModel(i), proto);
        }

        // Now for the UidBatteryConsumers.
        final List<android.os.UidBatteryConsumer> uidConsumers = bus.getUidBatteryConsumers();
        uidConsumers.sort((a, b) -> a.getUid() - b.getUid());

        final BatteryUsageStatsAtomsProto.UidBatteryConsumer[] uidConsumersProto =
                proto.uidBatteryConsumers;
        Arrays.sort(uidConsumersProto, (a, b) -> a.uid - b.uid);

        // UID_0 - After sorting, UID_0 should be in position 0 for both data structures
        assertEquals(UID_0, bus.getUidBatteryConsumers().get(0).getUid());
        assertEquals(UID_0, proto.uidBatteryConsumers[0].uid);
        assertSameUidBatteryConsumer(
                bus.getUidBatteryConsumers().get(0),
                proto.uidBatteryConsumers[0],
                false);

        // UID_1 - After sorting, UID_1 should be in position 1 for both data structures
        assertEquals(UID_1, bus.getUidBatteryConsumers().get(1).getUid());
        assertEquals(UID_1, proto.uidBatteryConsumers[1].uid);
        assertSameUidBatteryConsumer(
                bus.getUidBatteryConsumers().get(1),
                proto.uidBatteryConsumers[1],
                true);

        // UID_2 - After sorting, UID_2 should be in position 2 for both data structures
        assertEquals(UID_2, bus.getUidBatteryConsumers().get(2).getUid());
        assertEquals(UID_2, proto.uidBatteryConsumers[2].uid);
        assertSameUidBatteryConsumer(
                bus.getUidBatteryConsumers().get(2),
                proto.uidBatteryConsumers[2],
                false);

        // UID_3 - Should be none, since no interesting data (done last for debugging convenience).
        assertEquals(3, proto.uidBatteryConsumers.length);
        bus.close();
    }

    private void assertSameBatteryConsumer(String message, BatteryConsumer consumer,
            android.os.nano.BatteryUsageStatsAtomsProto.BatteryConsumerData consumerProto) {
        assertNotNull(message, consumerProto);
        assertEquals(
                convertMahToDc(consumer.getConsumedPower()),
                consumerProto.totalConsumedPowerDeciCoulombs);

        for (PowerComponentUsage componentProto : consumerProto.powerComponents) {
            final int componentId = componentProto.component;
            assertEquals(message + " for component " + componentId,
                    convertMahToDc(consumer.getConsumedPower(componentId)),
                    componentProto.powerDeciCoulombs);
            assertEquals(message + " for component " + componentId,
                    consumer.getUsageDurationMillis(componentId),
                    componentProto.durationMillis);
        }

        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final BatteryConsumer.Key[] keys = consumer.getKeys(componentId);
            if (keys == null || keys.length <= 1) {
                continue;
            }

            for (BatteryConsumer.Key key : keys) {
                if (key.processState == 0) {
                    continue;
                }

                BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        sliceProto = null;
                for (BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        slice : consumerProto.slices) {
                    if (slice.powerComponent.component == componentId
                            && slice.processState == key.processState) {
                        sliceProto = slice;
                        break;
                    }
                }

                final long expectedPowerDc = convertMahToDc(consumer.getConsumedPower(key));
                final long expectedUsageDurationMillis = consumer.getUsageDurationMillis(key);
                if (expectedPowerDc == 0 && expectedUsageDurationMillis == 0) {
                    assertThat(sliceProto).isNull();
                } else {
                    assertThat(sliceProto).isNotNull();
                    assertThat(sliceProto.powerComponent.powerDeciCoulombs)
                            .isEqualTo(expectedPowerDc);
                    assertThat(sliceProto.powerComponent.durationMillis)
                            .isEqualTo(expectedUsageDurationMillis);
                }
            }
        }
    }

    private static final int[] UID_USAGE_TIME_PROCESS_STATES = {
            BatteryConsumer.PROCESS_STATE_FOREGROUND,
            BatteryConsumer.PROCESS_STATE_BACKGROUND,
            BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE
    };

    private void assertSameUidBatteryConsumer(
            android.os.UidBatteryConsumer uidConsumer,
            BatteryUsageStatsAtomsProto.UidBatteryConsumer uidConsumerProto,
            boolean expectNullBatteryConsumerData) {

        final int uid = uidConsumerProto.uid;
        assertEquals("Uid consumers had mismatched uids", uid, uidConsumer.getUid());

        assertEquals("For uid " + uid,
                uidConsumer.getTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_FOREGROUND),
                uidConsumerProto.timeInForegroundMillis);
        assertEquals("For uid " + uid,
                uidConsumer.getTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_BACKGROUND),
                uidConsumerProto.timeInBackgroundMillis);
        for (int processState : UID_USAGE_TIME_PROCESS_STATES) {
            final long timeInStateMillis = uidConsumer.getTimeInProcessStateMs(processState);
            if (timeInStateMillis <= 0) {
                continue;
            }
            assertEquals("For uid " + uid + ", process state " + processState,
                    timeInStateMillis,
                    Arrays.stream(uidConsumerProto.timeInState)
                            .filter(timeInState -> timeInState.processState == processState)
                            .findFirst()
                            .orElseThrow()
                            .timeInStateMillis);
        }

        if (expectNullBatteryConsumerData) {
            assertNull("For uid " + uid, uidConsumerProto.batteryConsumerData);
        } else {
            assertSameBatteryConsumer("For uid " + uid,
                    uidConsumer,
                    uidConsumerProto.batteryConsumerData);
        }
    }

    /**
     * Validates the PowerComponentModel object that matches powerComponent.
     */
    private void assertPowerComponentModel(int powerComponent,
            @BatteryConsumer.PowerModel int powerModel, BatteryUsageStatsAtomsProto proto) {
        boolean found = false;
        for (BatteryUsageStatsAtomsProto.PowerComponentModel powerComponentModel :
                proto.componentModels) {
            if (powerComponentModel.component == powerComponent) {
                if (found) {
                    fail("Power component " + BatteryConsumer.powerComponentIdToString(
                            powerComponent) + " found multiple times in the proto");
                }
                found = true;
                final int expectedPowerModel = BatteryConsumer.powerModelToProtoEnum(powerModel);
                assertEquals(expectedPowerModel, powerComponentModel.powerModel);
            }
        }
        if (!found) {
            final int model = BatteryConsumer.powerModelToProtoEnum(powerModel);
            assertEquals(
                    "Power component " + BatteryConsumer.powerComponentIdToString(powerComponent)
                            + " was not found in the proto but has a defined power model.",
                    BatteryUsageStatsAtomsProto.PowerComponentModel.UNDEFINED,
                    model);
        }
    }

    /** Converts charge from milliamp hours (mAh) to decicoulombs (dC). */
    private long convertMahToDc(double powerMah) {
        return (long) (powerMah * 36 + 0.5);
    }

    private BatteryUsageStats buildBatteryUsageStats() {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"CustomConsumer1", "CustomConsumer2"},
                        /* includePowerModels */ true,
                        /* includeProcessStats */ true,
                        /* includeScreenStateData */ false,
                        /* includePowerStateData */ false,
                        /* minConsumedPowerThreshold */ 0)
                        .setDischargePercentage(20)
                        .setDischargedPowerRange(1000, 2000)
                        .setDischargeDurationMs(1234)
                        .setStatsStartTimestamp(1000)
                        .setStatsEndTimestamp(20000)
                        .setStatsDuration(10000);
        final UidBatteryConsumer.Builder uidBuilder = builder
                .getOrCreateUidBatteryConsumerBuilder(UID_0)
                .setPackageWithHighestDrain("myPackage0")
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_FOREGROUND, 1000)
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_BACKGROUND, 2000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_SCREEN, 300)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 400)
                .setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 450)
                .setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1, 500)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 600)
                .setUsageDurationMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1, 800);

        final BatteryConsumer.Key keyFg = uidBuilder.getKey(BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key keyBg = uidBuilder.getKey(BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key keyFgs = uidBuilder.getKey(BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
        final BatteryConsumer.Key keyCached = uidBuilder.getKey(BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_CACHED);

        uidBuilder.setConsumedPower(keyFg, 9100, BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                .setUsageDurationMillis(keyFg, 8100)
                .setConsumedPower(keyBg, 9200, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION)
                .setUsageDurationMillis(keyBg, 8200)
                .setConsumedPower(keyFgs, 9300, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION)
                .setUsageDurationMillis(keyFgs, 8300)
                .setConsumedPower(keyCached, 9400, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION)
                .setUsageDurationMillis(keyFgs, 8400);

        final BatteryConsumer.Key keyCustomFg = uidBuilder.getKey(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key keyCustomBg = uidBuilder.getKey(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        uidBuilder.setConsumedPower(
                keyCustomFg, 100, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        uidBuilder.setConsumedPower(
                keyCustomBg, 350, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        builder.getOrCreateUidBatteryConsumerBuilder(UID_1)
                .setPackageWithHighestDrain("myPackage1")
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_FOREGROUND, 1234);

        builder.getOrCreateUidBatteryConsumerBuilder(UID_2)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN,
                        766);

        builder.getOrCreateUidBatteryConsumerBuilder(UID_3);

        builder.getAggregateBatteryConsumerBuilder(AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(30000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20100,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_AUDIO, 0,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE) // Empty
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CAMERA, 20150,
                        BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION)
                .setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20200)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20300)
                .setUsageDurationMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20400);

        // Not used; just to make sure extraneous data doesn't mess things up.
        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10100,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                .setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200);

        return builder.build();
    }

    @Test
    public void testLargeAtomTruncated() throws Exception {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[0], true, false, false, false, 0);
        // If not truncated, this BatteryUsageStats object would generate a proto buffer
        // significantly larger than 50 Kb
        for (int i = 0; i < 3000; i++) {
            builder.getOrCreateUidBatteryConsumerBuilder(i)
                    .setTimeInProcessStateMs(
                            BatteryConsumer.PROCESS_STATE_FOREGROUND, 1 * 60 * 1000)
                    .setTimeInProcessStateMs(
                            BatteryConsumer.PROCESS_STATE_BACKGROUND, 2 * 60 * 1000)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, 30)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 40);
        }

        // Add a UID with much larger battery footprint
        final int largeConsumerUid = 3001;
        builder.getOrCreateUidBatteryConsumerBuilder(largeConsumerUid)
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_FOREGROUND, 10 * 60 * 1000)
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_BACKGROUND, 20 * 60 * 1000)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, 300)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 400);

        // Add a UID with much larger usage duration
        final int highUsageUid = 3002;
        builder.getOrCreateUidBatteryConsumerBuilder(highUsageUid)
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_FOREGROUND, 60 * 60 * 1000)
                .setTimeInProcessStateMs(BatteryConsumer.PROCESS_STATE_BACKGROUND, 120 * 60 * 1000)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, 3)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 4);

        BatteryUsageStats batteryUsageStats = builder.build();
        final byte[] bytes = batteryUsageStats.getStatsProto();
        assertThat(bytes.length).isGreaterThan(20000);
        assertThat(bytes.length).isLessThan(50000);

        batteryUsageStats.close();

        BatteryUsageStatsAtomsProto proto;
        try {
            proto = BatteryUsageStatsAtomsProto.parseFrom(bytes);
        } catch (InvalidProtocolBufferNanoException e) {
            fail("Invalid proto: " + e);
            return;
        }

        boolean largeConsumerIncluded = false;
        boolean highUsageAppIncluded = false;
        for (int i = 0; i < proto.uidBatteryConsumers.length; i++) {
            if (proto.uidBatteryConsumers[i].uid == largeConsumerUid) {
                largeConsumerIncluded = true;
                BatteryUsageStatsAtomsProto.BatteryConsumerData consumerData =
                        proto.uidBatteryConsumers[i].batteryConsumerData;
                assertThat(consumerData.totalConsumedPowerDeciCoulombs / 36)
                        .isEqualTo(300 + 400);
            } else if (proto.uidBatteryConsumers[i].uid == highUsageUid) {
                highUsageAppIncluded = true;
                BatteryUsageStatsAtomsProto.BatteryConsumerData consumerData =
                        proto.uidBatteryConsumers[i].batteryConsumerData;
                assertThat(consumerData.totalConsumedPowerDeciCoulombs / 36)
                        .isEqualTo(3 + 4);
            }
        }

        assertThat(largeConsumerIncluded).isTrue();
        assertThat(highUsageAppIncluded).isTrue();
    }
}
