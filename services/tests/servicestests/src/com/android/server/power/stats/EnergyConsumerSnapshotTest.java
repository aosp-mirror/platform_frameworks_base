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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryStats;
import android.util.SparseArray;
import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;

import com.android.server.power.stats.EnergyConsumerSnapshot.EnergyConsumerDeltaData;

import org.junit.Test;

/**
 * Test class for {@link EnergyConsumerSnapshot}.
 *
 * To run the tests, use
 * atest FrameworksServicesTests:com.android.server.power.stats.MeasuredEnergySnapshotTest
 */
@SmallTest
public final class EnergyConsumerSnapshotTest {
    private static final EnergyConsumer CONSUMER_DISPLAY = createEnergyConsumer(
            0, 0, EnergyConsumerType.DISPLAY, "Display");
    private static final  EnergyConsumer CONSUMER_OTHER_0 = createEnergyConsumer(
            47, 0, EnergyConsumerType.OTHER, "GPU");
    private static final  EnergyConsumer CONSUMER_OTHER_1 = createEnergyConsumer(
            1, 1, EnergyConsumerType.OTHER, "HPU");
    private static final  EnergyConsumer CONSUMER_OTHER_2 = createEnergyConsumer(
            436, 2, EnergyConsumerType.OTHER, "IPU\n&\005");

    private static final SparseArray<EnergyConsumer> ALL_ID_CONSUMER_MAP = createIdToConsumerMap(
            CONSUMER_DISPLAY, CONSUMER_OTHER_0, CONSUMER_OTHER_1, CONSUMER_OTHER_2);
    private static final SparseArray<EnergyConsumer> SOME_ID_CONSUMER_MAP = createIdToConsumerMap(
            CONSUMER_DISPLAY);

    private static final int VOLTAGE_0 = 4_000;
    private static final int VOLTAGE_1 = 3_500;
    private static final int VOLTAGE_2 = 3_100;
    private static final int VOLTAGE_3 = 3_000;
    private static final int VOLTAGE_4 = 2_800;

    // Elements in each results are purposefully out of order.
    private static final EnergyConsumerResult[] RESULTS_0 = new EnergyConsumerResult[]{
            createEnergyConsumerResult(CONSUMER_OTHER_0.id, 90_000, new int[]{47, 3},
                    new long[]{14_000, 13_000}),
            createEnergyConsumerResult(CONSUMER_DISPLAY.id, 14_000, null, null),
            createEnergyConsumerResult(CONSUMER_OTHER_1.id, 0, null, null),
            // No CONSUMER_OTHER_2
    };
    private static final EnergyConsumerResult[] RESULTS_1 = new EnergyConsumerResult[]{
            createEnergyConsumerResult(CONSUMER_DISPLAY.id, 24_000, null, null),
            createEnergyConsumerResult(CONSUMER_OTHER_0.id, 90_000, new int[]{47, 3},
                    new long[]{14_000, 13_000}),
            createEnergyConsumerResult(CONSUMER_OTHER_2.id, 12_000, new int[]{6},
                    new long[]{10_000}),
            createEnergyConsumerResult(CONSUMER_OTHER_1.id, 12_000_000, null, null),
    };
    private static final EnergyConsumerResult[] RESULTS_2 = new EnergyConsumerResult[]{
            createEnergyConsumerResult(CONSUMER_DISPLAY.id, 36_000, null, null),
            // No CONSUMER_OTHER_0
            // No CONSUMER_OTHER_1
            // No CONSUMER_OTHER_2
    };
    private static final EnergyConsumerResult[] RESULTS_3 = new EnergyConsumerResult[]{
            // No CONSUMER_DISPLAY
            createEnergyConsumerResult(CONSUMER_OTHER_2.id, 13_000, new int[]{6},
                    new long[]{10_000}),
            createEnergyConsumerResult(
                    CONSUMER_OTHER_0.id, 190_000, new int[]{2, 3, 47, 7},
                    new long[]{9_000, 18_000, 14_000, 6_000}),
            createEnergyConsumerResult(CONSUMER_OTHER_1.id, 12_000_000, null, null),
    };
    private static final EnergyConsumerResult[] RESULTS_4 = new EnergyConsumerResult[]{
            createEnergyConsumerResult(CONSUMER_DISPLAY.id, 43_000, null, null),
            createEnergyConsumerResult(
                    CONSUMER_OTHER_0.id, 290_000, new int[]{7, 47, 3, 2},
                    new long[]{6_000, 14_000, 18_000, 11_000}),
            // No CONSUMER_OTHER_1
            createEnergyConsumerResult(CONSUMER_OTHER_2.id, 165_000, new int[]{6, 47},
                    new long[]{10_000, 8_000}),
    };

    @Test
    public void testUpdateAndGetDelta_empty() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(ALL_ID_CONSUMER_MAP);
        assertNull(snapshot.updateAndGetDelta(null, VOLTAGE_0));
        assertNull(snapshot.updateAndGetDelta(new EnergyConsumerResult[0], VOLTAGE_0));
    }

    @Test
    public void testUpdateAndGetDelta() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(ALL_ID_CONSUMER_MAP);

        // results0
        EnergyConsumerDeltaData delta = snapshot.updateAndGetDelta(RESULTS_0, VOLTAGE_0);
        if (delta != null) { // null is fine here. If non-null, it better be uninteresting though.
            assertNull(delta.displayChargeUC);
            assertNull(delta.otherTotalChargeUC);
            assertNull(delta.otherUidChargesUC);
        }

        // results1
        delta = snapshot.updateAndGetDelta(RESULTS_1, VOLTAGE_1);
        assertNotNull(delta);
        long expectedChargeUC;
        expectedChargeUC = calculateChargeConsumedUC(14_000, VOLTAGE_0, 24_000, VOLTAGE_1);
        assertEquals(expectedChargeUC, delta.displayChargeUC[0]);

        assertNotNull(delta.otherTotalChargeUC);

        expectedChargeUC = calculateChargeConsumedUC(90_000, VOLTAGE_0, 90_000, VOLTAGE_1);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[0]);
        expectedChargeUC = calculateChargeConsumedUC(0, VOLTAGE_0, 12_000_000, VOLTAGE_1);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[1]);
        assertEquals(0, delta.otherTotalChargeUC[2]); // First good pull. Treat delta as 0.

        assertNotNull(delta.otherUidChargesUC);
        assertNullOrEmpty(delta.otherUidChargesUC[0]); // No change in uid energies
        assertNullOrEmpty(delta.otherUidChargesUC[1]);
        assertNullOrEmpty(delta.otherUidChargesUC[2]);

        // results2
        delta = snapshot.updateAndGetDelta(RESULTS_2, VOLTAGE_2);
        assertNotNull(delta);
        expectedChargeUC = calculateChargeConsumedUC(24_000, VOLTAGE_1, 36_000, VOLTAGE_2);
        assertEquals(expectedChargeUC, delta.displayChargeUC[0]);
        assertNull(delta.otherUidChargesUC);
        assertNull(delta.otherTotalChargeUC);

        // results3
        delta = snapshot.updateAndGetDelta(RESULTS_3, VOLTAGE_3);
        assertNotNull(delta);
        assertNull(delta.displayChargeUC);

        assertNotNull(delta.otherTotalChargeUC);

        expectedChargeUC = calculateChargeConsumedUC(90_000, VOLTAGE_1, 190_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[0]);
        expectedChargeUC = calculateChargeConsumedUC(12_000_000, VOLTAGE_1, 12_000_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[1]);
        expectedChargeUC = calculateChargeConsumedUC(12_000, VOLTAGE_1, 13_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[2]);

        assertNotNull(delta.otherUidChargesUC);

        assertEquals(3, delta.otherUidChargesUC[0].size());
        expectedChargeUC = calculateChargeConsumedUC(0, VOLTAGE_1, 9_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherUidChargesUC[0].get(2));
        expectedChargeUC = calculateChargeConsumedUC(13_000, VOLTAGE_1, 18_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherUidChargesUC[0].get(3));
        expectedChargeUC = calculateChargeConsumedUC(0, VOLTAGE_1, 6_000, VOLTAGE_3);
        assertEquals(expectedChargeUC, delta.otherUidChargesUC[0].get(7));
        assertNullOrEmpty(delta.otherUidChargesUC[1]);
        assertNullOrEmpty(delta.otherUidChargesUC[2]);

        // results4
        delta = snapshot.updateAndGetDelta(RESULTS_4, VOLTAGE_4);
        assertNotNull(delta);
        expectedChargeUC = calculateChargeConsumedUC(36_000, VOLTAGE_2, 43_000, VOLTAGE_4);
        assertEquals(expectedChargeUC, delta.displayChargeUC[0]);

        assertNotNull(delta.otherTotalChargeUC);
        expectedChargeUC = calculateChargeConsumedUC(190_000, VOLTAGE_3, 290_000, VOLTAGE_4);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[0]);
        assertEquals(0, delta.otherTotalChargeUC[1]); // Not present (e.g. missing data)
        expectedChargeUC = calculateChargeConsumedUC(13_000, VOLTAGE_3, 165_000, VOLTAGE_4);
        assertEquals(expectedChargeUC, delta.otherTotalChargeUC[2]);

        assertNotNull(delta.otherUidChargesUC);
        assertEquals(1, delta.otherUidChargesUC[0].size());
        expectedChargeUC = calculateChargeConsumedUC(9_000, VOLTAGE_3, 11_000, VOLTAGE_4);
        assertEquals(expectedChargeUC, delta.otherUidChargesUC[0].get(2));
        assertNullOrEmpty(delta.otherUidChargesUC[1]); // Not present
        assertEquals(1, delta.otherUidChargesUC[2].size());
        expectedChargeUC = calculateChargeConsumedUC(0, VOLTAGE_3, 8_000, VOLTAGE_4);
        assertEquals(expectedChargeUC, delta.otherUidChargesUC[2].get(47));
    }

    /** Test updateAndGetDelta() when the results have consumers absent from idToConsumerMap. */
    @Test
    public void testUpdateAndGetDelta_some() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(SOME_ID_CONSUMER_MAP);

        // results0
        EnergyConsumerDeltaData delta = snapshot.updateAndGetDelta(RESULTS_0, VOLTAGE_0);
        if (delta != null) { // null is fine here. If non-null, it better be uninteresting though.
            assertNull(delta.displayChargeUC);
            assertNull(delta.otherTotalChargeUC);
            assertNull(delta.otherUidChargesUC);
        }

        // results1
        delta = snapshot.updateAndGetDelta(RESULTS_1, VOLTAGE_1);
        assertNotNull(delta);
        final long expectedChargeUC =
                calculateChargeConsumedUC(14_000, VOLTAGE_0, 24_000, VOLTAGE_1);
        assertEquals(expectedChargeUC, delta.displayChargeUC[0]);
        assertNull(delta.otherTotalChargeUC); // Although in the results, they're not in the idMap
        assertNull(delta.otherUidChargesUC);
    }

    @Test
    public void testGetOtherOrdinalNames() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(ALL_ID_CONSUMER_MAP);
        assertThat(snapshot.getOtherOrdinalNames()).asList()
                .containsExactly("GPU", "HPU", "IPU &_");
    }

    @Test
    public void testGetOtherOrdinalNames_none() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(SOME_ID_CONSUMER_MAP);
        assertEquals(0, snapshot.getOtherOrdinalNames().length);
    }

    @Test
    public void getMeasuredEnergyDetails() {
        final EnergyConsumerSnapshot snapshot = new EnergyConsumerSnapshot(ALL_ID_CONSUMER_MAP);
        snapshot.updateAndGetDelta(RESULTS_0, VOLTAGE_0);
        EnergyConsumerDeltaData delta = snapshot.updateAndGetDelta(RESULTS_1, VOLTAGE_1);
        BatteryStats.EnergyConsumerDetails details = snapshot.getEnergyConsumerDetails(delta);
        assertThat(details.consumers).hasLength(4);
        assertThat(details.chargeUC).isEqualTo(new long[]{2667, 3200000, 0, 0});
        assertThat(details.toString()).isEqualTo("DISPLAY=2667 HPU=3200000 GPU=0 IPU &_=0");
    }

    @Test
    public void testUpdateAndGetDelta_updatesCameraCharge() {
        EnergyConsumer cameraConsumer =
                createEnergyConsumer(7, 0, EnergyConsumerType.CAMERA, "CAMERA");
        final EnergyConsumerSnapshot snapshot =
                new EnergyConsumerSnapshot(createIdToConsumerMap(cameraConsumer));

        // An initial result with only one energy consumer
        EnergyConsumerResult[] result0 = new EnergyConsumerResult[]{
                createEnergyConsumerResult(cameraConsumer.id, 60_000, null, null),
        };
        snapshot.updateAndGetDelta(result0, VOLTAGE_1);

        // A subsequent result
        EnergyConsumerResult[] result1 = new EnergyConsumerResult[]{
                createEnergyConsumerResult(cameraConsumer.id, 90_000, null, null),
        };
        EnergyConsumerDeltaData delta = snapshot.updateAndGetDelta(result1, VOLTAGE_1);

        // Verify that the delta between the two results is reported.
        BatteryStats.EnergyConsumerDetails details = snapshot.getEnergyConsumerDetails(delta);
        assertThat(details.consumers).hasLength(1);
        long expectedDeltaUC = calculateChargeConsumedUC(60_000, VOLTAGE_1, 90_000, VOLTAGE_1);
        assertThat(details.chargeUC[0]).isEqualTo(expectedDeltaUC);
    }

    private static EnergyConsumer createEnergyConsumer(int id, int ord, byte type, String name) {
        final EnergyConsumer ec = new EnergyConsumer();
        ec.id = id;
        ec.ordinal = ord;
        ec.type = type;
        ec.name = name;
        return ec;
    }

    private static SparseArray<EnergyConsumer> createIdToConsumerMap(EnergyConsumer ... ecs) {
        final SparseArray<EnergyConsumer> map = new SparseArray<>();
        for (EnergyConsumer ec : ecs) {
            map.put(ec.id, ec);
        }
        return map;
    }

    private static EnergyConsumerResult createEnergyConsumerResult(
            int id, long energyUWs, int[] uids, long[] uidEnergies) {
        final EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.id = id;
        ecr.energyUWs = energyUWs;
        if (uids != null) {
            ecr.attribution = new EnergyConsumerAttribution[uids.length];
            for (int i = 0; i < uids.length; i++) {
                ecr.attribution[i] = new EnergyConsumerAttribution();
                ecr.attribution[i].uid = uids[i];
                ecr.attribution[i].energyUWs = uidEnergies[i];
            }
        }
        return ecr;
    }

    private static long calculateChargeConsumedUC(long energyUWs0, long voltageMv0, long energyUWs1,
            long voltageMv1) {
        final long deltaEnergyUWs = energyUWs1 - energyUWs0;
        final long avgVoltageMv = (voltageMv1 + voltageMv0 + 1) / 2;

        // Charge uC = Energy uWs * (1000 mV/V) / (voltage mV) + 0.5 (for rounding)
        return (deltaEnergyUWs * 1000 + (avgVoltageMv / 2)) / avgVoltageMv;
    }

    private void assertNullOrEmpty(SparseLongArray a) {
        if (a != null) assertEquals("Array should be null or empty", 0, a.size());
    }
}
