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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;

import com.android.internal.power.MeasuredEnergyArray;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link MeasuredEnergySnapshot}.
 *
 * To run the tests, use
 * atest FrameworksServicesTests:com.android.server.am.MeasuredEnergySnapshotTest
 */
@SmallTest
public final class MeasuredEnergySnapshotTest {
    private static final int NUMBER_SUBSYSTEMS = 3;
    private static final int SUBSYSTEM_DISPLAY = 0;
    private static final int SUBSYSTEM_NEVER_USED = 1;
    private static final int SUBSYSTEM_CATAPULT = 2;

    private MeasuredEnergySnapshot mSnapshot;

    // Basic MeasuredEnergyArray that supports all the subsystems. Out of order on purpose.
    private final int[] mAllSubsystems =
            {SUBSYSTEM_DISPLAY, SUBSYSTEM_CATAPULT, SUBSYSTEM_NEVER_USED};
    // E.g. mAllSubsystems[mSubsystemIndices[SUBSYSTEM_CATAPULT]]=SUBSYSTEM_CATAPULT
    private final int[] mSubsystemIndices = {0, 2, 1};
    private final long[] mCurrentSubsystemEnergyUJ = {111, 0, 0};
    private final MeasuredEnergyArray mOmniEnergyArray = new MeasuredEnergyArray() {
        @Override
        public int getSubsystem(int index) {
            return mAllSubsystems[index];
        }

        @Override
        public long getEnergy(int index) {
            return mCurrentSubsystemEnergyUJ[index];
        }

        @Override
        public int size() {
            return mAllSubsystems.length;
        }
    };
    private final MeasuredEnergyArray mJustDisplayEnergyArray = new MeasuredEnergyArray() {
        @Override
        public int getSubsystem(int index) {
            return mAllSubsystems[0];
        }

        @Override
        public long getEnergy(int index) {
            return mCurrentSubsystemEnergyUJ[0];
        }

        @Override
        public int size() {
            return 1;
        }
    };

    @Before
    public void setUp() {
        mSnapshot = new MeasuredEnergySnapshot(NUMBER_SUBSYSTEMS, mOmniEnergyArray);
    }

    @Test
    public void testUpdateAndGetDelta() {
        SparseLongArray result;

        // Increment DISPLAY by 15
        incrementEnergyOfSubsystem(SUBSYSTEM_DISPLAY, 15);
        result = mSnapshot.updateAndGetDelta(mOmniEnergyArray);
        assertEquals(1, result.size());
        assertEquals(15, result.get(SUBSYSTEM_DISPLAY));

        // Increment DISPLAY by 7
        // Increment CATAPULT by 5. But do NOT include (pull) it in the passed in energy array.
        incrementEnergyOfSubsystem(SUBSYSTEM_DISPLAY, 7);
        incrementEnergyOfSubsystem(SUBSYSTEM_CATAPULT, 5);
        result = mSnapshot.updateAndGetDelta(mJustDisplayEnergyArray); // Just pull display.
        assertEquals(1, result.size());
        assertEquals(7, result.get(SUBSYSTEM_DISPLAY));

        // Increment CATAPULT by 64 (in addition to the previous increase of 5)
        incrementEnergyOfSubsystem(SUBSYSTEM_CATAPULT, 64);
        result = mSnapshot.updateAndGetDelta(mOmniEnergyArray);
        assertEquals(1, result.size());
        assertEquals(5 + 64, result.get(SUBSYSTEM_CATAPULT));

        // Do nothing
        result = mSnapshot.updateAndGetDelta(mOmniEnergyArray);
        assertEquals("0 results should not appear at all", 0, result.size());

        // Increment DISPLAY by 42
        incrementEnergyOfSubsystem(SUBSYSTEM_DISPLAY, 42);
        result = mSnapshot.updateAndGetDelta(mOmniEnergyArray);
        assertEquals(1, result.size());
        assertEquals(42, result.get(SUBSYSTEM_DISPLAY));

        // Increment DISPLAY by 106 and CATAPULT by 13
        incrementEnergyOfSubsystem(SUBSYSTEM_DISPLAY, 106);
        incrementEnergyOfSubsystem(SUBSYSTEM_CATAPULT, 13);
        result = mSnapshot.updateAndGetDelta(mOmniEnergyArray);
        assertEquals(2, result.size());
        assertEquals(106, result.get(SUBSYSTEM_DISPLAY));
        assertEquals(13, result.get(SUBSYSTEM_CATAPULT));
    }

    private void incrementEnergyOfSubsystem(int subsystem, long energy) {
        mCurrentSubsystemEnergyUJ[mSubsystemIndices[subsystem]] += energy;
    }

    @Test
    public void testUpdateAndGetDelta_null() {
        assertNull(mSnapshot.updateAndGetDelta(null));
    }

    @Test
    public void testHasSubsystem() {
        // Setup MeasuredEnergySnapshot which reported some of the subsystems.
        final int[] subsystems = {SUBSYSTEM_DISPLAY, SUBSYSTEM_CATAPULT};
        MeasuredEnergyArray measuredEnergyArray = new MeasuredEnergyArray() {
            @Override
            public int getSubsystem(int index) {
                return subsystems[index];
            }

            @Override
            public long getEnergy(int index) {
                return 0; // Irrelevant for this test.
            }

            @Override
            public int size() {
                return subsystems.length;
            }
        };
        final MeasuredEnergySnapshot snapshot =
                new MeasuredEnergySnapshot(NUMBER_SUBSYSTEMS, measuredEnergyArray);

        assertTrue(snapshot.hasSubsystem(SUBSYSTEM_DISPLAY));
        assertTrue(snapshot.hasSubsystem(SUBSYSTEM_CATAPULT));
        assertFalse(snapshot.hasSubsystem(SUBSYSTEM_NEVER_USED));
    }
}
