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

package com.android.internal.power;

import static com.android.internal.power.MeasuredEnergyArray.NUMBER_SUBSYSTEMS;
import static com.android.internal.power.MeasuredEnergyArray.SUBSYSTEM_DISPLAY;
import static com.android.internal.power.MeasuredEnergyStats.ENERGY_BUCKET_SCREEN_DOZE;
import static com.android.internal.power.MeasuredEnergyStats.ENERGY_BUCKET_SCREEN_ON;
import static com.android.internal.power.MeasuredEnergyStats.NUMBER_ENERGY_BUCKETS;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.view.Display;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link MeasuredEnergyStats}.
 *
 * To run the tests, use
 * atest FrameworksCoreTests:com.android.internal.power.MeasuredEnergyStatsTest
 */
public class MeasuredEnergyStatsTest {
    private MeasuredEnergyStats mStats;
    private int[] mAllSubsystems = new int[NUMBER_SUBSYSTEMS];
    private long[] mCurrentSubsystemEnergyUJ = new long[NUMBER_SUBSYSTEMS];

    MeasuredEnergyArray mMeasuredEnergyArray = new MeasuredEnergyArray() {
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
            return NUMBER_SUBSYSTEMS;
        }
    };

    @Before
    public void setUp() {
        // Populate all supported subsystems indexes and arbitrary starting energy values.
        mAllSubsystems[SUBSYSTEM_DISPLAY] = SUBSYSTEM_DISPLAY;
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] = 111;

        mStats = new MeasuredEnergyStats(mMeasuredEnergyArray, Display.STATE_UNKNOWN);
    }

    @Test
    public void testReadWriteParcel() {
        // update with some arbitrary data
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 222;
        mStats.update(mMeasuredEnergyArray, Display.STATE_ON, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 321;
        mStats.update(mMeasuredEnergyArray, Display.STATE_DOZE, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 456;
        mStats.update(mMeasuredEnergyArray, Display.STATE_OFF, true);

        final Parcel parcel = Parcel.obtain();
        mStats.writeToParcel(parcel);

        parcel.setDataPosition(0);
        MeasuredEnergyStats stats = new MeasuredEnergyStats(parcel);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            assertEquals(mStats.getAccumulatedBucketEnergy(i), stats.getAccumulatedBucketEnergy(i));
        }
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        // update with some arbitrary data
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 222;
        mStats.update(mMeasuredEnergyArray, Display.STATE_ON, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 321;
        mStats.update(mMeasuredEnergyArray, Display.STATE_DOZE, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 456;
        mStats.update(mMeasuredEnergyArray, Display.STATE_OFF, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(mStats, parcel);

        parcel.setDataPosition(0);
        MeasuredEnergyStats stats = new MeasuredEnergyStats(mMeasuredEnergyArray,
                Display.STATE_UNKNOWN);
        MeasuredEnergyStats.readSummaryFromParcel(stats, parcel);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            assertEquals(mStats.getAccumulatedBucketEnergy(i), stats.getAccumulatedBucketEnergy(i));
        }
        parcel.recycle();
    }

    @Test
    public void testDisplayStateEnergyAttribution() {
        long expectedScreenOnEnergy = 0;
        long expectedScreenDozeEnergy = 0;

        // Display energy should be attributed to the previous screen state.
        mStats.update(mMeasuredEnergyArray, Display.STATE_UNKNOWN, true);

        incrementDisplayState(222, Display.STATE_ON, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenOnEnergy += 321;
        incrementDisplayState(321, Display.STATE_DOZE, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenDozeEnergy += 456;
        incrementDisplayState(456, Display.STATE_OFF, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        incrementDisplayState(1111, Display.STATE_DOZE_SUSPEND, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenDozeEnergy += 2345;
        incrementDisplayState(2345, Display.STATE_ON_SUSPEND, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenOnEnergy += 767;
        incrementDisplayState(767, Display.STATE_VR, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenOnEnergy += 999;
        incrementDisplayState(999, Display.STATE_UNKNOWN, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);
    }

    @Test
    public void testDisplayStateEnergyAttribution_notRunning() {
        long expectedScreenOnEnergy = 0;
        long expectedScreenDozeEnergy = 0;

        // Display energy should be attributed to the previous screen state.
        mStats.update(mMeasuredEnergyArray, Display.STATE_UNKNOWN, true);

        incrementDisplayState(222, Display.STATE_ON, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenOnEnergy += 321;
        incrementDisplayState(321, Display.STATE_DOZE, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        // Updates after this point should not result in energy accumulation.
        incrementDisplayState(456, Display.STATE_OFF, false, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        incrementDisplayState(1111, Display.STATE_DOZE_SUSPEND, false, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        incrementDisplayState(2345, Display.STATE_ON_SUSPEND, false, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        incrementDisplayState(767, Display.STATE_VR, false, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        // Resume energy accumulation.
        expectedScreenOnEnergy += 999;
        incrementDisplayState(999, Display.STATE_UNKNOWN, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);
    }

    @Test
    public void testReset() {
        // update with some arbitrary data.
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 222;
        mStats.update(mMeasuredEnergyArray, Display.STATE_ON, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 321;
        mStats.update(mMeasuredEnergyArray, Display.STATE_DOZE, true);
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += 456;
        mStats.update(mMeasuredEnergyArray, Display.STATE_OFF, true);

        mStats.reset();
        // All energy should be reset to 0
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            assertEquals(mStats.getAccumulatedBucketEnergy(i), 0);
        }

        // Increment all subsystem energy by some arbitrary amount and update
        for (int i = 0; i < NUMBER_SUBSYSTEMS; i++) {
            mCurrentSubsystemEnergyUJ[i] += 100 * i;
        }
        mStats.update(mMeasuredEnergyArray, Display.STATE_OFF, true);

        // All energy should still be 0 after the first post-reset update.
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            assertEquals(mStats.getAccumulatedBucketEnergy(i), 0);
        }

        // Energy accumulation should continue like normal.
        long expectedScreenOnEnergy = 0;
        long expectedScreenDozeEnergy = 0;
        incrementDisplayState(222, Display.STATE_ON, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenOnEnergy += 321;
        incrementDisplayState(321, Display.STATE_DOZE, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);

        expectedScreenDozeEnergy += 456;
        incrementDisplayState(456, Display.STATE_OFF, true, expectedScreenOnEnergy,
                expectedScreenDozeEnergy);
    }

    @Test
    public void testHasSubsystem() {
        for (int i = 0; i < NUMBER_SUBSYSTEMS; i++) {
            assertEquals(mStats.hasSubsystem(i), true);
        }
    }

    @Test
    public void testHasSubsystem_unavailable() {
        // Setup MeasuredEnergyStats with not available subsystems.
        int[] subsystems = new int[0];
        long[] energies = new long[0];
        MeasuredEnergyArray measuredEnergyArray = new MeasuredEnergyArray() {
            @Override
            public int getSubsystem(int index) {
                return subsystems[index];
            }

            @Override
            public long getEnergy(int index) {
                return energies[index];
            }

            @Override
            public int size() {
                return 0;
            }
        };
        MeasuredEnergyStats stats = new MeasuredEnergyStats(measuredEnergyArray,
                Display.STATE_UNKNOWN);

        for (int i = 0; i < NUMBER_SUBSYSTEMS; i++) {
            assertEquals(stats.hasSubsystem(i), false);
        }

        stats.reset();
        // a reset should not change the state of an unavailable subsystem.
        for (int i = 0; i < NUMBER_SUBSYSTEMS; i++) {
            assertEquals(stats.hasSubsystem(i), false);
        }
    }

    private void incrementDisplayState(long deltaEnergy, int nextState, boolean accumulate,
            long expectScreenEnergy, long expectedDozeEnergy) {
        mCurrentSubsystemEnergyUJ[SUBSYSTEM_DISPLAY] += deltaEnergy;
        mStats.update(mMeasuredEnergyArray, nextState, accumulate);
        assertEquals(expectScreenEnergy,
                mStats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_ON));
        assertEquals(expectedDozeEnergy,
                mStats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_DOZE));
    }
}
