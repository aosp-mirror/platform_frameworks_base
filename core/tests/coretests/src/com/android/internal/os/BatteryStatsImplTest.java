/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.os.BatteryStats.STATS_SINCE_CHARGED;
import static android.os.BatteryStats.Uid.NUM_PROCESS_STATE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryStats.Uid.PROCESS_STATE_CACHED;
import static android.os.BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_TOP;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.os.BatteryStats;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class BatteryStatsImplTest {
    @Mock
    private KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader;
    @Mock
    private KernelSingleUidTimeReader mKernelSingleUidTimeReader;

    private MockBatteryStatsImpl mBatteryStatsImpl;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mKernelUidCpuFreqTimeReader.allUidTimesAvailable()).thenReturn(true);
        when(mKernelSingleUidTimeReader.singleUidCpuTimesAvailable()).thenReturn(true);
        mBatteryStatsImpl = new MockBatteryStatsImpl()
                .setKernelUidCpuFreqTimeReader(mKernelUidCpuFreqTimeReader)
                .setKernelSingleUidTimeReader(mKernelSingleUidTimeReader);
    }

    @Test
    public void testUpdateProcStateCpuTimes() {
        mBatteryStatsImpl.setOnBatteryInternal(true);
        mBatteryStatsImpl.updateTimeBasesLocked(false, Display.STATE_ON, 0, 0);

        final int[] testUids = {10032, 10048, 10145, 10139};
        final int[] testProcStates = {
                PROCESS_STATE_BACKGROUND,
                PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_STATE_TOP,
                PROCESS_STATE_CACHED
        };
        addPendingUids(testUids, testProcStates);
        final long[][] cpuTimes = {
                {349734983, 394982394832l, 909834, 348934, 9838},
                {7498, 1239890, 988, 13298, 98980},
                {989834, 384098, 98483, 23809, 4984},
                {4859048, 348903, 4578967, 5973894, 298549}
        };
        for (int i = 0; i < testUids.length; ++i) {
            when(mKernelSingleUidTimeReader.readDeltaMs(testUids[i])).thenReturn(cpuTimes[i]);

            // Verify there are no cpu times initially.
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
            }
        }

        mBatteryStatsImpl.updateProcStateCpuTimes(true, false);

        verifyNoPendingUids();
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    assertArrayEquals("Uid=" + testUids[i], cpuTimes[i],
                            u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                } else {
                    assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                }
                assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
            }
        }

        final long[][] delta1 = {
                {9589, 148934, 309894, 3098493, 98754},
                {21983, 94983, 4983, 9878493, 84854},
                {945894, 9089432, 19478, 3834, 7845},
                {843895, 43948, 949582, 99, 384}
        };
        for (int i = 0; i < testUids.length; ++i) {
            when(mKernelSingleUidTimeReader.readDeltaMs(testUids[i])).thenReturn(delta1[i]);
        }
        addPendingUids(testUids, testProcStates);

        mBatteryStatsImpl.updateProcStateCpuTimes(true, false);

        verifyNoPendingUids();
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j];
                    }
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes,
                            u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                } else {
                    assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                }
                assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
            }
        }

        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[][] delta2 = {
                {95932, 2943, 49834, 89034, 139},
                {349, 89605, 5896, 845, 98444},
                {678, 7498, 9843, 889, 4894},
                {488, 998, 8498, 394, 574}
        };
        for (int i = 0; i < testUids.length; ++i) {
            when(mKernelSingleUidTimeReader.readDeltaMs(testUids[i])).thenReturn(delta2[i]);
        }
        addPendingUids(testUids, testProcStates);

        mBatteryStatsImpl.updateProcStateCpuTimes(true, true);

        verifyNoPendingUids();
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j] + delta2[i][j];
                    }
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes,
                            u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                    assertArrayEquals("Uid=" + testUids[i], delta2[i],
                            u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                } else {
                    assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                    assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                }
            }
        }

        final long[][] delta3 = {
                {98545, 95768795, 76586, 548945, 57846},
                {788876, 586, 578459, 8776984, 9578923},
                {3049509483598l, 4597834, 377654, 94589035, 7854},
                {9493, 784, 99895, 8974893, 9879843}
        };
        for (int i = 0; i < testUids.length; ++i) {
            when(mKernelSingleUidTimeReader.readDeltaMs(testUids[i])).thenReturn(
                    delta3[i].clone());
        }
        addPendingUids(testUids, testProcStates);
        final int parentUid = testUids[1];
        final int childUid = 99099;
        addIsolatedUid(parentUid, childUid);
        final long[] isolatedUidCpuTimes = {495784, 398473, 4895, 4905, 30984093};
        when(mKernelSingleUidTimeReader.readDeltaMs(childUid)).thenReturn(isolatedUidCpuTimes);

        mBatteryStatsImpl.updateProcStateCpuTimes(true, true);

        verifyNoPendingUids();
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j] + delta2[i][j] + delta3[i][j]
                                + (testUids[i] == parentUid ? isolatedUidCpuTimes[j] : 0);
                    }
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes,
                            u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                    long[] expectedScreenOffTimes = delta2[i].clone();
                    for (int j = 0; j < expectedScreenOffTimes.length; ++j) {
                        expectedScreenOffTimes[j] += delta3[i][j]
                                + (testUids[i] == parentUid ? isolatedUidCpuTimes[j] : 0);
                    }
                    assertArrayEquals("Uid=" + testUids[i], expectedScreenOffTimes,
                            u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                } else {
                    assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                    assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                }
            }
        }
    }

    @Test
    public void testCopyFromAllUidsCpuTimes() {
        mBatteryStatsImpl.setOnBatteryInternal(false);
        mBatteryStatsImpl.updateTimeBasesLocked(false, Display.STATE_ON, 0, 0);

        final int[] testUids = {10032, 10048, 10145, 10139};
        final int[] testProcStates = {
                PROCESS_STATE_BACKGROUND,
                PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_STATE_TOP,
                PROCESS_STATE_CACHED
        };
        final int[] pendingUidIdx = {1, 2};
        updateProcessStates(testUids, testProcStates, pendingUidIdx);

        final SparseArray<long[]> allUidCpuTimes = new SparseArray<>();
        long[][] allCpuTimes = {
                {938483, 4985984, 439893},
                {499, 94904, 27694},
                {302949085, 39789473, 34792839},
                {9809485, 9083475, 347889834},
        };
        for (int i = 0; i < testUids.length; ++i) {
            allUidCpuTimes.put(testUids[i], allCpuTimes[i]);
        }
        when(mKernelUidCpuFreqTimeReader.getAllUidCpuFreqTimeMs()).thenReturn(allUidCpuTimes);
        long[][] expectedCpuTimes = {
                {843598745, 397843, 32749, 99854},
                {9834, 5885, 487589, 394},
                {203984, 439, 9859, 30948},
                {9389, 858, 239, 349}
        };
        for (int i = 0; i < testUids.length; ++i) {
            final int idx = i;
            final ArgumentMatcher<long[]> matcher = times -> Arrays.equals(times, allCpuTimes[idx]);
            when(mKernelSingleUidTimeReader.computeDelta(eq(testUids[i]), argThat(matcher)))
                    .thenReturn(expectedCpuTimes[i]);
        }

        mBatteryStatsImpl.copyFromAllUidsCpuTimes(true, false);

        verifyNoPendingUids();
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes[i],
                            u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                } else {
                    assertNull(u.getCpuFreqTimes(STATS_SINCE_CHARGED, procState));
                }
                assertNull(u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED, procState));
            }
        }
    }

    @Test
    public void testAddCpuTimes() {
        long[] timesA = null;
        long[] timesB = null;
        assertNull(mBatteryStatsImpl.addCpuTimes(timesA, timesB));

        timesA = new long[] {34, 23, 45, 24};
        assertArrayEquals(timesA, mBatteryStatsImpl.addCpuTimes(timesA, timesB));

        timesB = timesA;
        timesA = null;
        assertArrayEquals(timesB, mBatteryStatsImpl.addCpuTimes(timesA, timesB));

        final long[] expected = {434, 6784, 34987, 9984};
        timesA = new long[timesB.length];
        for (int i = 0; i < timesA.length; ++i) {
            timesA[i] = expected[i] - timesB[i];
        }
        assertArrayEquals(expected, mBatteryStatsImpl.addCpuTimes(timesA, timesB));
    }

    @Test
    public void testMulticastWakelockAcqRel() {
        final int testUid = 10032;
        final int acquireTimeMs = 1000;
        final int releaseTimeMs = 1005;
        final int currentTimeMs = 1011;

        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        // Create a Uid Object
        final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUid);
        assertNotNull(u);

        // Acquire and release the lock
        u.noteWifiMulticastEnabledLocked(acquireTimeMs);
        u.noteWifiMulticastDisabledLocked(releaseTimeMs);

        // Get the total acquisition time
        long totalTime = u.getWifiMulticastTime(currentTimeMs*1000,
                BatteryStats.STATS_SINCE_UNPLUGGED);
        assertEquals("Miscalculations of Multicast wakelock acquisition time",
                (releaseTimeMs - acquireTimeMs) * 1000, totalTime);
    }

    @Test
    public void testMulticastWakelockAcqNoRel() {
        final int testUid = 10032;
        final int acquireTimeMs = 1000;
        final int currentTimeMs = 1011;

        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        // Create a Uid Object
        final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUid);
        assertNotNull(u);

        // Acquire the lock
        u.noteWifiMulticastEnabledLocked(acquireTimeMs);

        // Get the total acquisition time
        long totalTime =  u.getWifiMulticastTime(currentTimeMs*1000,
                BatteryStats.STATS_SINCE_UNPLUGGED);
        assertEquals("Miscalculations of Multicast wakelock acquisition time",
                (currentTimeMs - acquireTimeMs) * 1000, totalTime);
    }

    @Test
    public void testMulticastWakelockAcqAcqRelRel() {
        final int testUid = 10032;
        final int acquireTimeMs_1 = 1000;
        final int acquireTimeMs_2 = 1002;

        final int releaseTimeMs_1 = 1005;
        final int releaseTimeMs_2 = 1009;
        final int currentTimeMs = 1011;

        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        // Create a Uid Object
        final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUid);
        assertNotNull(u);

        // Acquire and release the lock (twice in nested way)
        u.noteWifiMulticastEnabledLocked(acquireTimeMs_1);
        u.noteWifiMulticastEnabledLocked(acquireTimeMs_2);

        u.noteWifiMulticastDisabledLocked(releaseTimeMs_1);
        u.noteWifiMulticastDisabledLocked(releaseTimeMs_2);

        // Get the total acquisition time
        long totalTime =  u.getWifiMulticastTime(currentTimeMs*1000,
                BatteryStats.STATS_SINCE_UNPLUGGED);
        assertEquals("Miscalculations of Multicast wakelock acquisition time",
                (releaseTimeMs_2 - acquireTimeMs_1) * 1000, totalTime);
    }

    @Test
    public void testMulticastWakelockAcqRelAcqRel() {
        final int testUid = 10032;
        final int acquireTimeMs_1 = 1000;
        final int acquireTimeMs_2 = 1005;

        final int releaseTimeMs_1 = 1002;
        final int releaseTimeMs_2 = 1009;
        final int currentTimeMs = 1011;

        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        // Create a Uid Object
        final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUid);
        assertNotNull(u);

        // Acquire and release the lock (twice)
        u.noteWifiMulticastEnabledLocked(acquireTimeMs_1);
        u.noteWifiMulticastDisabledLocked(releaseTimeMs_1);

        u.noteWifiMulticastEnabledLocked(acquireTimeMs_2);
        u.noteWifiMulticastDisabledLocked(releaseTimeMs_2);

        // Get the total acquisition time
        long totalTime =  u.getWifiMulticastTime(currentTimeMs*1000,
                BatteryStats.STATS_SINCE_UNPLUGGED);
        assertEquals("Miscalculations of Multicast wakelock acquisition time",
                ((releaseTimeMs_1 - acquireTimeMs_1) + (releaseTimeMs_2 - acquireTimeMs_2))
                * 1000, totalTime);
    }

    private void addIsolatedUid(int parentUid, int childUid) {
        final BatteryStatsImpl.Uid u = mBatteryStatsImpl.getUidStatsLocked(parentUid);
        u.addIsolatedUid(childUid);
    }

    private void addPendingUids(int[] uids, int[] procStates) {
        final SparseIntArray pendingUids = mBatteryStatsImpl.getPendingUids();
        for (int i = 0; i < uids.length; ++i) {
            pendingUids.put(uids[i], procStates[i]);
        }
    }

    private void updateProcessStates(int[] uids, int[] procStates,
            int[] pendingUidsIdx) {
        final SparseIntArray pendingUids = mBatteryStatsImpl.getPendingUids();
        for (int i = 0; i < uids.length; ++i) {
            final BatteryStatsImpl.Uid u = mBatteryStatsImpl.getUidStatsLocked(uids[i]);
            if (ArrayUtils.contains(pendingUidsIdx, i)) {
                u.setProcessStateForTest(PROCESS_STATE_TOP);
                pendingUids.put(uids[i], procStates[i]);
            } else {
                u.setProcessStateForTest(procStates[i]);
            }
        }
    }

    private void verifyNoPendingUids() {
        final SparseIntArray pendingUids = mBatteryStatsImpl.getPendingUids();
        assertEquals("There shouldn't be any pending uids left: " + pendingUids,
                0, pendingUids.size());
    }
}
