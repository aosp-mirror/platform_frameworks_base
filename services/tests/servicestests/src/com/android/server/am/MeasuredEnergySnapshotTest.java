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

package com.android.server.am;

import static com.android.server.am.MeasuredEnergySnapshot.UNAVAILABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.util.SparseArray;
import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;

import com.android.server.am.MeasuredEnergySnapshot.MeasuredEnergyDeltaData;

import org.junit.Test;

/**
 * Test class for {@link MeasuredEnergySnapshot}.
 *
 * To run the tests, use
 * atest FrameworksServicesTests:com.android.server.am.MeasuredEnergySnapshotTest
 */
@SmallTest
public final class MeasuredEnergySnapshotTest {
    private static final EnergyConsumer CONSUMER_DISPLAY = createEnergyConsumer(
            0, 0, EnergyConsumerType.DISPLAY, "Display");
    private static final  EnergyConsumer CONSUMER_OTHER_0 = createEnergyConsumer(
            47, 0, EnergyConsumerType.OTHER, "GPU");
    private static final  EnergyConsumer CONSUMER_OTHER_1 = createEnergyConsumer(
            1, 1, EnergyConsumerType.OTHER, "HPU");
    private static final  EnergyConsumer CONSUMER_OTHER_2 = createEnergyConsumer(
            436, 2, EnergyConsumerType.OTHER, "IPU");

    private static final SparseArray<EnergyConsumer> ALL_ID_CONSUMER_MAP = createIdToConsumerMap(
            CONSUMER_DISPLAY, CONSUMER_OTHER_0, CONSUMER_OTHER_1, CONSUMER_OTHER_2);
    private static final SparseArray<EnergyConsumer> SOME_ID_CONSUMER_MAP = createIdToConsumerMap(
            CONSUMER_DISPLAY);

    // Elements in each results are purposefully out of order.
    private static final  EnergyConsumerResult[] RESULTS_0 = new EnergyConsumerResult[] {
        createEnergyConsumerResult(CONSUMER_OTHER_0.id, 90, new int[] {47, 3}, new long[] {14, 13}),
        createEnergyConsumerResult(CONSUMER_DISPLAY.id, 14, null, null),
        createEnergyConsumerResult(CONSUMER_OTHER_1.id, 0, null, null),
        // No CONSUMER_OTHER_2
    };
    private static final  EnergyConsumerResult[] RESULTS_1 = new EnergyConsumerResult[] {
        createEnergyConsumerResult(CONSUMER_DISPLAY.id, 24, null, null),
        createEnergyConsumerResult(CONSUMER_OTHER_0.id, 90, new int[] {47, 3}, new long[] {14, 13}),
        createEnergyConsumerResult(CONSUMER_OTHER_2.id, 12, new int[] {6}, new long[] {10}),
        createEnergyConsumerResult(CONSUMER_OTHER_1.id, 12_000, null, null),
    };
    private static final  EnergyConsumerResult[] RESULTS_2 = new EnergyConsumerResult[] {
        createEnergyConsumerResult(CONSUMER_DISPLAY.id, 36, null, null),
        // No CONSUMER_OTHER_0
        // No CONSUMER_OTHER_1
        // No CONSUMER_OTHER_2
    };
    private static final  EnergyConsumerResult[] RESULTS_3 = new EnergyConsumerResult[] {
        // No CONSUMER_DISPLAY
        createEnergyConsumerResult(CONSUMER_OTHER_2.id, 13, new int[] {6}, new long[] {10}),
        createEnergyConsumerResult(
                CONSUMER_OTHER_0.id, 190, new int[] {2, 3, 47, 7}, new long[] {9, 18, 14, 6}),
        createEnergyConsumerResult(CONSUMER_OTHER_1.id, 12_000, null, null),
    };
    private static final  EnergyConsumerResult[] RESULTS_4 = new EnergyConsumerResult[] {
        createEnergyConsumerResult(CONSUMER_DISPLAY.id, 43, null, null),
        createEnergyConsumerResult(
                CONSUMER_OTHER_0.id, 290, new int[] {7, 47, 3, 2}, new long[] {6, 14, 18, 11}),
        // No CONSUMER_OTHER_1
        createEnergyConsumerResult(CONSUMER_OTHER_2.id, 165, new int[] {6, 47}, new long[] {10, 8}),
    };

    @Test
    public void testUpdateAndGetDelta_empty() {
        final MeasuredEnergySnapshot snapshot = new MeasuredEnergySnapshot(ALL_ID_CONSUMER_MAP);
        assertNull(snapshot.updateAndGetDelta(null));
        assertNull(snapshot.updateAndGetDelta(new EnergyConsumerResult[0]));
    }

    @Test
    public void testUpdateAndGetDelta() {
        final MeasuredEnergySnapshot snapshot = new MeasuredEnergySnapshot(ALL_ID_CONSUMER_MAP);

        // results0
        MeasuredEnergyDeltaData delta = snapshot.updateAndGetDelta(RESULTS_0);
        if (delta != null) { // null is fine here. If non-null, it better be uninteresting though.
            assertEquals(UNAVAILABLE, delta.displayEnergyUJ);
            assertNull(delta.otherTotalEnergyUJ);
            assertNull(delta.otherUidEnergiesUJ);
        }

        // results1
        delta = snapshot.updateAndGetDelta(RESULTS_1);
        assertNotNull(delta);
        assertEquals(24 - 14, delta.displayEnergyUJ);

        assertNotNull(delta.otherTotalEnergyUJ);
        assertEquals(90 - 90, delta.otherTotalEnergyUJ[0]);
        assertEquals(12_000 - 0, delta.otherTotalEnergyUJ[1]);
        assertEquals(0, delta.otherTotalEnergyUJ[2]); // First good pull. Treat delta as 0.

        assertNotNull(delta.otherUidEnergiesUJ);
        assertNullOrEmpty(delta.otherUidEnergiesUJ[0]); // No change in uid energies
        assertNullOrEmpty(delta.otherUidEnergiesUJ[1]);
        assertNullOrEmpty(delta.otherUidEnergiesUJ[2]);

        // results2
        delta = snapshot.updateAndGetDelta(RESULTS_2);
        assertNotNull(delta);
        assertEquals(36 - 24, delta.displayEnergyUJ);
        assertNull(delta.otherUidEnergiesUJ);
        assertNull(delta.otherTotalEnergyUJ);

        // results3
        delta = snapshot.updateAndGetDelta(RESULTS_3);
        assertNotNull(delta);
        assertEquals(UNAVAILABLE, delta.displayEnergyUJ);

        assertNotNull(delta.otherTotalEnergyUJ);
        assertEquals(190 - 90, delta.otherTotalEnergyUJ[0]);
        assertEquals(12_000 - 12_000, delta.otherTotalEnergyUJ[1]);
        assertEquals(13 - 12, delta.otherTotalEnergyUJ[2]);

        assertNotNull(delta.otherUidEnergiesUJ);
        assertEquals(3, delta.otherUidEnergiesUJ[0].size());
        assertEquals(9 - 0, delta.otherUidEnergiesUJ[0].get(2));
        assertEquals(18 - 13, delta.otherUidEnergiesUJ[0].get(3));
        assertEquals(6 - 0, delta.otherUidEnergiesUJ[0].get(7));
        assertNullOrEmpty(delta.otherUidEnergiesUJ[1]);
        assertNullOrEmpty(delta.otherUidEnergiesUJ[2]);

        // results4
        delta = snapshot.updateAndGetDelta(RESULTS_4);
        assertNotNull(delta);
        assertEquals(43 - 36, delta.displayEnergyUJ);

        assertNotNull(delta.otherTotalEnergyUJ);
        assertEquals(290 - 190, delta.otherTotalEnergyUJ[0]);
        assertEquals(0, delta.otherTotalEnergyUJ[1]); // Not present (e.g. missing data)
        assertEquals(165 - 13, delta.otherTotalEnergyUJ[2]);

        assertNotNull(delta.otherUidEnergiesUJ);
        assertEquals(1, delta.otherUidEnergiesUJ[0].size());
        assertEquals(11 - 9, delta.otherUidEnergiesUJ[0].get(2));
        assertNullOrEmpty(delta.otherUidEnergiesUJ[1]); // Not present
        assertEquals(1, delta.otherUidEnergiesUJ[2].size());
        assertEquals(8, delta.otherUidEnergiesUJ[2].get(47));
    }

    /** Test updateAndGetDelta() when the results have consumers absent from idToConsumerMap. */
    @Test
    public void testUpdateAndGetDelta_some() {
        final MeasuredEnergySnapshot snapshot = new MeasuredEnergySnapshot(SOME_ID_CONSUMER_MAP);

        // results0
        MeasuredEnergyDeltaData delta = snapshot.updateAndGetDelta(RESULTS_0);
        if (delta != null) { // null is fine here. If non-null, it better be uninteresting though.
            assertEquals(UNAVAILABLE, delta.displayEnergyUJ);
            assertNull(delta.otherTotalEnergyUJ);
            assertNull(delta.otherUidEnergiesUJ);
        }

        // results1
        delta = snapshot.updateAndGetDelta(RESULTS_1);
        assertNotNull(delta);
        assertEquals(24 - 14, delta.displayEnergyUJ);
        assertNull(delta.otherTotalEnergyUJ); // Although in the results, they're not in the idMap
        assertNull(delta.otherUidEnergiesUJ);
    }

    @Test
    public void testGetNumOtherOrdinals() {
        final MeasuredEnergySnapshot snapshot = new MeasuredEnergySnapshot(ALL_ID_CONSUMER_MAP);
        assertEquals(3, snapshot.getNumOtherOrdinals());
    }

    @Test
    public void testGetNumOtherOrdinals_none() {
        final MeasuredEnergySnapshot snapshot = new MeasuredEnergySnapshot(SOME_ID_CONSUMER_MAP);
        assertEquals(0, snapshot.getNumOtherOrdinals());
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

    private void assertNullOrEmpty(SparseLongArray a) {
        if (a != null) assertEquals("Array should be null or empty", 0, a.size());
    }
}
