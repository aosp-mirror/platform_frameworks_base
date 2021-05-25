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
package com.android.internal.os;

import static android.os.BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.nano.BatteryUsageStatsAtomsProto;
import android.os.nano.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage;

import androidx.test.filters.SmallTest;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;



@SmallTest
public class BatteryUsageStatsPulledTest {

    private static final int UID_0 = 1000;
    private static final int UID_1 = 2000;
    private static final int UID_2 = 3000;
    private static final int UID_3 = 4000;

    @Test
    public void testGetStatsProto() {
        final long sessionEndTimestampMs = 1050;
        final BatteryUsageStats bus = buildBatteryUsageStats();
        final byte[] bytes = bus.getStatsProto(sessionEndTimestampMs);
        BatteryUsageStatsAtomsProto proto;
        try {
            proto = BatteryUsageStatsAtomsProto.parseFrom(bytes);
        } catch (InvalidProtocolBufferNanoException e) {
            fail("Invalid proto: " + e);
            return;
        }

        assertEquals(bus.getStatsStartTimestamp(), proto.sessionStartMillis);
        assertEquals(sessionEndTimestampMs, proto.sessionEndMillis);
        assertEquals(
                sessionEndTimestampMs - bus.getStatsStartTimestamp(),
                proto.sessionDurationMillis);
        assertEquals(bus.getDischargePercentage(), proto.sessionDischargePercentage);

        assertEquals(3, proto.deviceBatteryConsumer.powerComponents.length); // Only 3 are non-empty
        assertSameBatteryConsumer("For deviceBatteryConsumer",
                bus.getAggregateBatteryConsumer(AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE),
                proto.deviceBatteryConsumer);

        // Now for the UidBatteryConsumers.
        final List<android.os.UidBatteryConsumer> uidConsumers = bus.getUidBatteryConsumers();
        uidConsumers.sort((a, b) -> a.getUid() - b.getUid());

        final BatteryUsageStatsAtomsProto.UidBatteryConsumer[] uidConsumersProto
                = proto.uidBatteryConsumers;
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
    }

    private void assertSameBatteryConsumer(String message, BatteryConsumer consumer,
            android.os.nano.BatteryUsageStatsAtomsProto.BatteryConsumerData consumerProto) {
        assertNotNull(message, consumerProto);
        assertEquals(
                convertMahToDc(consumer.getConsumedPower()),
                consumerProto.totalConsumedPowerDeciCoulombs);

        for (PowerComponentUsage componentProto : consumerProto.powerComponents) {
            final int componentId = componentProto.component;
            if (componentId < BatteryConsumer.POWER_COMPONENT_COUNT) {
                assertEquals(message + " for component " + componentId,
                        convertMahToDc(consumer.getConsumedPower(componentId)),
                        componentProto.powerDeciCoulombs);
                assertEquals(message + " for component " + componentId,
                        consumer.getUsageDurationMillis(componentId),
                        componentProto.durationMillis);
            } else {
                assertEquals(message + " for custom component " + componentId,
                        convertMahToDc(consumer.getConsumedPowerForCustomComponent(componentId)),
                        componentProto.powerDeciCoulombs);
                assertEquals(message + " for custom component " + componentId,
                        consumer.getUsageDurationForCustomComponentMillis(componentId),
                        componentProto.durationMillis);
            }
        }
    }

    private void assertSameUidBatteryConsumer(
            android.os.UidBatteryConsumer uidConsumer,
            BatteryUsageStatsAtomsProto.UidBatteryConsumer uidConsumerProto,
            boolean expectNullBatteryConsumerData) {

        final int uid = uidConsumerProto.uid;
        assertEquals("Uid consumers had mismatched uids", uid, uidConsumer.getUid());

        assertEquals("For uid " + uid,
                uidConsumer.getTimeInStateMs(android.os.UidBatteryConsumer.STATE_FOREGROUND),
                uidConsumerProto.timeInForegroundMillis);
        assertEquals("For uid " + uid,
                uidConsumer.getTimeInStateMs(android.os.UidBatteryConsumer.STATE_BACKGROUND),
                uidConsumerProto.timeInBackgroundMillis);
        if (expectNullBatteryConsumerData) {
            assertNull("For uid " + uid, uidConsumerProto.batteryConsumerData);
        } else {
            assertSameBatteryConsumer("For uid " + uid,
                    uidConsumer,
                    uidConsumerProto.batteryConsumerData);
        }
    }

    /** Converts charge from milliamp hours (mAh) to decicoulombs (dC). */
    private long convertMahToDc(double powerMah) {
        return (long) (powerMah * 36 + 0.5);
    }

    private BatteryUsageStats buildBatteryUsageStats() {
        final BatteryStatsImpl batteryStats = new BatteryStatsImpl();
        final BatteryStatsImpl.Uid batteryStatsUid0 = batteryStats.getUidStatsLocked(UID_0);
        final BatteryStatsImpl.Uid batteryStatsUid1 = batteryStats.getUidStatsLocked(UID_1);
        final BatteryStatsImpl.Uid batteryStatsUid2 = batteryStats.getUidStatsLocked(UID_2);
        final BatteryStatsImpl.Uid batteryStatsUid3 = batteryStats.getUidStatsLocked(UID_3);

        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"CustomConsumer1", "CustomConsumer2"})
                        .setDischargePercentage(20)
                        .setDischargedPowerRange(1000, 2000)
                        .setStatsStartTimestamp(1000);
        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid0)
                .setPackageWithHighestDrain("myPackage0")
                .setTimeInStateMs(android.os.UidBatteryConsumer.STATE_FOREGROUND, 1000)
                .setTimeInStateMs(android.os.UidBatteryConsumer.STATE_BACKGROUND, 2000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_SCREEN, 300)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 400)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 450)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1, 500)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 600)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1, 800);

        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid1)
                .setPackageWithHighestDrain("myPackage1")
                .setTimeInStateMs(android.os.UidBatteryConsumer.STATE_FOREGROUND, 1234);

        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid2)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN,
                        766);

        builder.getOrCreateUidBatteryConsumerBuilder(batteryStatsUid3);

        builder.getAggregateBatteryConsumerBuilder(AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(30000)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20100)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_AUDIO, 0) // Empty
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CAMERA, 20150)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20200)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 20300)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 20400);

        // Not used; just to make sure extraneous data doesn't mess things up.
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10100)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200);

        return builder.build();
    }
}
