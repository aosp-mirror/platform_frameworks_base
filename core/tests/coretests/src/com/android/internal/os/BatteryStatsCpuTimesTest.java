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
import static android.os.BatteryStats.WAKE_TYPE_PARTIAL;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.FIRST_ISOLATED_UID;

import static com.android.internal.os.BatteryStatsImpl.WAKE_LOCK_WEIGHT;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.os.BatteryStats;
import android.os.UserHandle;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseLongArray;
import android.view.Display;

import com.android.internal.util.ArrayUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * To run the tests, use
 *
 * runtest -c com.android.internal.os.BatteryStatsCpuTimesTest frameworks-core
 *
 * or
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 * ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.BatteryStatsCpuTimesTest -w \
 * com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 *
 * or
 *
 * bit FrameworksCoreTests:com.android.internal.os.BatteryStatsCpuTimesTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryStatsCpuTimesTest {
    @Mock
    KernelUidCpuTimeReader mKernelUidCpuTimeReader;
    @Mock
    KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader;
    @Mock
    KernelUidCpuActiveTimeReader mKernelUidCpuActiveTimeReader;
    @Mock
    KernelUidCpuClusterTimeReader mKernelUidCpuClusterTimeReader;
    @Mock
    BatteryStatsImpl.UserInfoProvider mUserInfoProvider;
    @Mock
    PowerProfile mPowerProfile;

    private MockClocks mClocks;
    private MockBatteryStatsImpl mBatteryStatsImpl;
    private KernelCpuSpeedReader[] mKernelCpuSpeedReaders;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mClocks = new MockClocks();
        mBatteryStatsImpl = new MockBatteryStatsImpl(mClocks)
                .setKernelUidCpuTimeReader(mKernelUidCpuTimeReader)
                .setKernelUidCpuFreqTimeReader(mKernelUidCpuFreqTimeReader)
                .setKernelUidCpuActiveTimeReader(mKernelUidCpuActiveTimeReader)
                .setKernelUidCpuClusterTimeReader(mKernelUidCpuClusterTimeReader)
                .setUserInfoProvider(mUserInfoProvider);
    }

    @Test
    public void testUpdateCpuTimeLocked() {
        // PRECONDITIONS
        mBatteryStatsImpl.setPowerProfile(mPowerProfile);
        mBatteryStatsImpl.setOnBatteryInternal(false);
        final int numClusters = 3;
        initKernelCpuSpeedReaders(numClusters);
        final long[] freqs = {1, 12, 123, 12, 1234};
        when(mKernelUidCpuFreqTimeReader.readFreqs(mPowerProfile)).thenReturn(freqs);

        // RUN
        mBatteryStatsImpl.updateCpuTimeLocked(false, false);

        // VERIFY
        assertArrayEquals("Unexpected cpu freqs", freqs, mBatteryStatsImpl.getCpuFreqs());
        verify(mKernelUidCpuTimeReader).readDelta(null);
        verify(mKernelUidCpuFreqTimeReader).readDelta(null);
        for (int i = 0; i < numClusters; ++i) {
            verify(mKernelCpuSpeedReaders[i]).readDelta();
        }

        // Prepare for next test
        Mockito.reset(mUserInfoProvider, mKernelUidCpuFreqTimeReader, mKernelUidCpuTimeReader);
        for (int i = 0; i < numClusters; ++i) {
            Mockito.reset(mKernelCpuSpeedReaders[i]);
        }

        // PRECONDITIONS
        mBatteryStatsImpl.setOnBatteryInternal(true);

        // RUN
        mBatteryStatsImpl.updateCpuTimeLocked(true, false);

        // VERIFY
        verify(mUserInfoProvider).refreshUserIds();
        verify(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));
        // perClusterTimesAvailable is called twice, once in updateCpuTimeLocked() and the other
        // in readKernelUidCpuFreqTimesLocked.
        verify(mKernelUidCpuFreqTimeReader, times(2)).perClusterTimesAvailable();
        verify(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));
        verify(mKernelUidCpuActiveTimeReader).readDelta(
                any(KernelUidCpuActiveTimeReader.Callback.class));
        verify(mKernelUidCpuClusterTimeReader).readDelta(
                any(KernelUidCpuClusterTimeReader.Callback.class));
        verifyNoMoreInteractions(mKernelUidCpuFreqTimeReader);
        for (int i = 0; i < numClusters; ++i) {
            verify(mKernelCpuSpeedReaders[i]).readDelta();
        }
    }

    @Test
    public void testMarkPartialTimersAsEligible() {
        // PRECONDITIONS
        final ArrayList<BatteryStatsImpl.StopwatchTimer> partialTimers = getPartialTimers(
                10032, 10042, 10052);
        final ArrayList<BatteryStatsImpl.StopwatchTimer> lastPartialTimers
                = new ArrayList<>(partialTimers);
        mBatteryStatsImpl.setPartialTimers(partialTimers);
        mBatteryStatsImpl.setLastPartialTimers(lastPartialTimers);
        final boolean[] inList = {false, true, false};
        for (int i = 0; i < partialTimers.size(); ++i) {
            partialTimers.get(i).mInList = inList[i];
        }

        // RUN
        mBatteryStatsImpl.markPartialTimersAsEligible();

        // VERIFY
        assertTrue(ArrayUtils.referenceEquals(partialTimers, lastPartialTimers));
        for (int i = 0; i < partialTimers.size(); ++i) {
            assertTrue("Timer id=" + i, partialTimers.get(i).mInList);
        }

        // PRECONDITIONS
        partialTimers.addAll(getPartialTimers(10077, 10099));
        partialTimers.remove(1 /* index */);

        // RUN
        mBatteryStatsImpl.markPartialTimersAsEligible();

        // VERIFY
        assertTrue(ArrayUtils.referenceEquals(partialTimers, lastPartialTimers));
        for (int i = 0; i < partialTimers.size(); ++i) {
            assertTrue("Timer id=" + i, partialTimers.get(i).mInList);
        }
    }

    @Test
    public void testUpdateClusterSpeedTimes() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        final long[][] clusterSpeedTimesMs = {{20, 30}, {40, 50, 60}};
        initKernelCpuSpeedReaders(clusterSpeedTimesMs.length);
        for (int i = 0; i < clusterSpeedTimesMs.length; ++i) {
            when(mKernelCpuSpeedReaders[i].readDelta()).thenReturn(clusterSpeedTimesMs[i]);
        }
        when(mPowerProfile.getNumCpuClusters()).thenReturn(clusterSpeedTimesMs.length);
        for (int i = 0; i < clusterSpeedTimesMs.length; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i))
                    .thenReturn(clusterSpeedTimesMs[i].length);
        }
        final SparseLongArray updatedUids = new SparseLongArray();
        final int[] testUids = {10012, 10014, 10016};
        final int[] cpuTimeUs = {89, 31, 43};
        for (int i = 0; i < testUids.length; ++i) {
            updatedUids.put(testUids[i], cpuTimeUs[i]);
        }

        // RUN
        mBatteryStatsImpl.updateClusterSpeedTimes(updatedUids, true);

        // VERIFY
        int totalClustersTimeMs = 0;
        for (int i = 0; i < clusterSpeedTimesMs.length; ++i) {
            for (int j = 0; j < clusterSpeedTimesMs[i].length; ++j) {
                totalClustersTimeMs += clusterSpeedTimesMs[i][j];
            }
        }
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            for (int cluster = 0; cluster < clusterSpeedTimesMs.length; ++cluster) {
                for (int speed = 0; speed < clusterSpeedTimesMs[cluster].length; ++speed) {
                    assertEquals("Uid=" + testUids[i] + ", cluster=" + cluster + ", speed=" + speed,
                            cpuTimeUs[i] * clusterSpeedTimesMs[cluster][speed]
                                    / totalClustersTimeMs,
                            u.getTimeAtCpuSpeed(cluster, speed, STATS_SINCE_CHARGED));
                }
            }
        }
    }

    @Test
    public void testReadKernelUidCpuTimesLocked() {
        //PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesUs = {
                {12, 34}, {34897394, 3123983}, {79775429834l, 8430434903489l}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], uidTimesUs[i][0], uidTimesUs[i][1]);
            }
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        final SparseLongArray updatedUids = new SparseLongArray();
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(null, updatedUids, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    uidTimesUs[i][0], u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    uidTimesUs[i][1], u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));

            assertEquals("Unexpected entry in updated uids for uid=" + testUids[i],
                    uidTimesUs[i][0] + uidTimesUs[i][1], updatedUids.get(testUids[i]));
            updatedUids.delete(testUids[i]);
        }
        assertEquals("Updated uids: " + updatedUids, 0, updatedUids.size());

        // Repeat the test with a null updatedUids

        // PRECONDITIONS
        final long[][] deltasUs = {
                {9379, 3332409833484l}, {493247, 723234}, {3247819, 123348}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], deltasUs[i][0], deltasUs[i][1]);
            }
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(null, null, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    uidTimesUs[i][0] + deltasUs[i][0], u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    uidTimesUs[i][1] + deltasUs[i][1], u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
        }
    }

    @Test
    public void testReadKernelUidCpuTimesLocked_isolatedUid() {
        //PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int isolatedAppId = FIRST_ISOLATED_UID + 27;
        final int isolatedUid = UserHandle.getUid(testUserId, isolatedAppId);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                isolatedAppId,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesUs = {
                {12, 34}, {34897394, 3123983}, {79775429834l, 8430434903489l}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], uidTimesUs[i][0], uidTimesUs[i][1]);
            }
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(null, null, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            if (UserHandle.isIsolated(testUids[i])) {
                assertNull("There shouldn't be an entry for isolated uid=" + testUids[i], u);
                continue;
            }
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    uidTimesUs[i][0], u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    uidTimesUs[i][1], u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
        }
        verify(mKernelUidCpuTimeReader).removeUid(isolatedUid);

        // Add an isolated uid mapping and repeat the test.

        // PRECONDITIONS
        final int ownerUid = UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 42);
        mBatteryStatsImpl.addIsolatedUidLocked(isolatedUid, ownerUid);
        final long[][] deltasUs = {
                {9379, 3332409833484l}, {493247, 723234}, {3247819, 123348}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], deltasUs[i][0], deltasUs[i][1]);
            }
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(null, null, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            final long expectedUserTimeUs;
            final long expectedSystemTimeUs;
            if (UserHandle.isIsolated(testUids[i])) {
                assertNull("There shouldn't be an entry for isolated uid=" + testUids[i], u);
                // Since we added a mapping, an entry should've been created for owner uid.
                u = mBatteryStatsImpl.getUidStats().get(ownerUid);
                expectedUserTimeUs = deltasUs[i][0];
                expectedSystemTimeUs = deltasUs[i][1];
                assertNotNull("No entry for owner uid=" + ownerUid, u);
            } else {
                assertNotNull("No entry for uid=" + testUids[i], u);
                expectedUserTimeUs = uidTimesUs[i][0] + deltasUs[i][0];
                expectedSystemTimeUs = uidTimesUs[i][1] + deltasUs[i][1];
            }
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    expectedUserTimeUs, u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    expectedSystemTimeUs, u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
        }
    }

    @Test
    public void testReadKernelUidCpuTimesLocked_invalidUid() {
        //PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        final int testUserId = 11;
        final int invalidUserId = 15;
        final int invalidUid = UserHandle.getUid(invalidUserId, FIRST_APPLICATION_UID + 99);
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        when(mUserInfoProvider.exists(invalidUserId)).thenReturn(false);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesUs = {
                {12, 34}, {34897394, 3123983}, {79775429834l, 8430434903489l}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], uidTimesUs[i][0], uidTimesUs[i][1]);
            }
            // And one for the invalid uid
            callback.onUidCpuTime(invalidUid, 3879, 239);
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(null, null, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    uidTimesUs[i][0], u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    uidTimesUs[i][1], u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
        }
        assertNull("There shouldn't be an entry for invalid uid=" + invalidUid,
                mBatteryStatsImpl.getUidStats().get(invalidUid));
        verify(mKernelUidCpuTimeReader).removeUid(invalidUid);
    }

    @Test
    public void testReadKernelUidCpuTimesLocked_withPartialTimers() {
        //PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final int[] partialTimerUids = {
                UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 48),
                UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 10)
        };
        final ArrayList<BatteryStatsImpl.StopwatchTimer> partialTimers
                = getPartialTimers(partialTimerUids);
        final long[][] uidTimesUs = {
                {12, 34}, {3394, 3123}, {7977, 80434}
        };
        doAnswer(invocation -> {
            final KernelUidCpuTimeReader.Callback callback =
                    (KernelUidCpuTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuTime(testUids[i], uidTimesUs[i][0], uidTimesUs[i][1]);
            }
            return null;
        }).when(mKernelUidCpuTimeReader).readDelta(any(KernelUidCpuTimeReader.Callback.class));

        // RUN
        final SparseLongArray updatedUids = new SparseLongArray();
        mBatteryStatsImpl.readKernelUidCpuTimesLocked(partialTimers, updatedUids, true);

        // VERIFY
        long totalUserTimeUs = 0;
        long totalSystemTimeUs = 0;
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            final long expectedUserTimeUs = uidTimesUs[i][0] * WAKE_LOCK_WEIGHT / 100;
            final long expectedSystemTimeUs = uidTimesUs[i][1] * WAKE_LOCK_WEIGHT / 100;
            assertEquals("Unexpected user cpu time for uid=" + testUids[i],
                    expectedUserTimeUs, u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for uid=" + testUids[i],
                    expectedSystemTimeUs, u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected entry in updated uids for uid=" + testUids[i],
                    expectedUserTimeUs + expectedSystemTimeUs, updatedUids.get(testUids[i]));
            updatedUids.delete(testUids[i]);
            totalUserTimeUs += uidTimesUs[i][0];
            totalSystemTimeUs += uidTimesUs[i][1];
        }

        totalUserTimeUs = totalUserTimeUs * (100 - WAKE_LOCK_WEIGHT) / 100;
        totalSystemTimeUs = totalSystemTimeUs * (100 - WAKE_LOCK_WEIGHT) / 100;
        for (int i = 0; i < partialTimerUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(partialTimerUids[i]);
            assertNotNull("No entry for partial timer uid=" + partialTimerUids[i], u);
            final long expectedUserTimeUs = totalUserTimeUs / (partialTimerUids.length - i);
            final long expectedSystemTimeUs = totalSystemTimeUs / (partialTimerUids.length - i);
            assertEquals("Unexpected user cpu time for partial timer uid=" + partialTimerUids[i],
                    expectedUserTimeUs, u.getUserCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for partial timer uid=" + partialTimerUids[i],
                    expectedSystemTimeUs, u.getSystemCpuTimeUs(STATS_SINCE_CHARGED));
            assertEquals("Unexpected entry in updated uids for partial timer uid="
                            + partialTimerUids[i],
                    expectedUserTimeUs + expectedSystemTimeUs,
                    updatedUids.get(partialTimerUids[i]));
            updatedUids.delete(partialTimerUids[i]);
            totalUserTimeUs -= expectedUserTimeUs;
            totalSystemTimeUs -= expectedSystemTimeUs;

            final BatteryStats.Uid.Proc proc = u.getProcessStats().get("*wakelock*");
            assertEquals("Unexpected user cpu time for *wakelock* in uid=" + partialTimerUids[i],
                    expectedUserTimeUs / 1000, proc.getUserTime(STATS_SINCE_CHARGED));
            assertEquals("Unexpected system cpu time for *wakelock* in uid=" + partialTimerUids[i],
                    expectedSystemTimeUs / 1000, proc.getSystemTime(STATS_SINCE_CHARGED));
        }
        assertEquals(0, totalUserTimeUs);
        assertEquals(0, totalSystemTimeUs);
        assertEquals("Updated uids: " + updatedUids, 0, updatedUids.size());
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }

        // Repeat the test when the screen is off.

        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[][] deltasMs = {
                {3, 12, 55, 100, 32},
                {3248327490475l, 232349349845043l, 123, 2398, 0},
                {43, 3345, 2143, 123, 4554}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    sum(uidTimesMs[i], deltasMs[i]), u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertArrayEquals("Unexpected screen-off cpu times for uid=" + testUids[i],
                    deltasMs[i], u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked_perClusterTimesAvailable() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[] freqs = {1, 12, 123, 12, 1234};
        // Derived from freqs above, 2 clusters with {3, 2} freqs in each of them.
        final int[] clusterFreqs = {3, 2};
        when(mPowerProfile.getNumCpuClusters()).thenReturn(clusterFreqs.length);
        for (int i = 0; i < clusterFreqs.length; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i))
                    .thenReturn(clusterFreqs[i]);
        }
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));
        when(mKernelUidCpuFreqTimeReader.perClusterTimesAvailable()).thenReturn(true);

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));

            int idx = 0;
            for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
                for (int speed = 0; speed < clusterFreqs[cluster]; ++speed) {
                    assertEquals("Unexpected time at cluster=" + cluster + ", speed=" + speed,
                            uidTimesMs[i][idx] * 1000,
                            u.getTimeAtCpuSpeed(cluster, speed, STATS_SINCE_CHARGED));
                    idx++;
                }
            }
        }

        // Repeat the test when the screen is off.

        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[][] deltasMs = {
                {3, 12, 55, 100, 32},
                {3248327490475l, 232349349845043l, 123, 2398, 0},
                {43, 3345, 2143, 123, 4554}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    sum(uidTimesMs[i], deltasMs[i]), u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertArrayEquals("Unexpected screen-off cpu times for uid=" + testUids[i],
                    deltasMs[i], u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));

            int idx = 0;
            for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
                for (int speed = 0; speed < clusterFreqs[cluster]; ++speed) {
                    assertEquals("Unexpected time at cluster=" + cluster + ", speed=" + speed,
                            (uidTimesMs[i][idx] + deltasMs[i][idx]) * 1000,
                            u.getTimeAtCpuSpeed(cluster, speed, STATS_SINCE_CHARGED));
                    idx++;
                }
            }
        }
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked_partialTimers() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final int[] partialTimerUids = {
                UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 48),
                UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 10)
        };
        final ArrayList<BatteryStatsImpl.StopwatchTimer> partialTimers
                = getPartialTimers(partialTimerUids);
        final long[] freqs = {1, 12, 123, 12, 1234};
        // Derived from freqs above, 2 clusters with {3, 2} freqs in each of them.
        final int[] clusterFreqs = {3, 2};
        when(mPowerProfile.getNumCpuClusters()).thenReturn(clusterFreqs.length);
        for (int i = 0; i < clusterFreqs.length; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i))
                    .thenReturn(clusterFreqs[i]);
        }
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));
        when(mKernelUidCpuFreqTimeReader.perClusterTimesAvailable()).thenReturn(true);

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(partialTimers, true, false);

        // VERIFY
        final long[][] expectedWakeLockUidTimesUs = new long[clusterFreqs.length][];
        for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
            expectedWakeLockUidTimesUs[cluster] = new long[clusterFreqs[cluster]];
        }
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));

            int idx = 0;
            for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
                for (int speed = 0; speed < clusterFreqs[cluster]; ++speed) {
                    final long expectedTimeUs =
                            (uidTimesMs[i][idx] * 1000 * WAKE_LOCK_WEIGHT) / 100;
                    expectedWakeLockUidTimesUs[cluster][speed] += expectedTimeUs;
                    assertEquals("Unexpected time for uid= " + testUids[i]
                                    + " at cluster=" + cluster + ", speed=" + speed,
                            expectedTimeUs,
                            u.getTimeAtCpuSpeed(cluster, speed, STATS_SINCE_CHARGED));
                    idx++;
                }
            }
        }
        for (int i = 0; i < partialTimerUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(partialTimerUids[i]);
            assertNotNull("No entry for partial timer uid=" + partialTimerUids[i], u);

            assertNull("Unexpected cpu times for partial timer uid=" + partialTimerUids[i],
                    u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for partial timer uid="
                            + partialTimerUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));

            for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
                for (int speed = 0; speed < clusterFreqs[cluster]; ++speed) {
                    final long expectedTimeUs = expectedWakeLockUidTimesUs[cluster][speed]
                            / (partialTimerUids.length - i);
                    assertEquals("Unexpected time for partial timer uid= " + partialTimerUids[i]
                                    + " at cluster=" + cluster + ", speed=" + speed,
                            expectedTimeUs,
                            u.getTimeAtCpuSpeed(cluster, speed, STATS_SINCE_CHARGED));
                    expectedWakeLockUidTimesUs[cluster][speed] -= expectedTimeUs;
                }
            }
        }
        for (int cluster = 0; cluster < clusterFreqs.length; ++cluster) {
            for (int speed = 0; speed < clusterFreqs[cluster]; ++speed) {
                assertEquals("There shouldn't be any left-overs: "
                                + Arrays.deepToString(expectedWakeLockUidTimesUs),
                        0, expectedWakeLockUidTimesUs[cluster][speed]);
            }
        }
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked_freqsChanged() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }

        // Repeat the test with the freqs from proc file changed.

        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[][] deltasMs = {
                {3, 12, 55, 100, 32, 34984, 27983},
                {3248327490475l, 232349349845043l, 123, 2398, 0, 398, 0},
                {43, 3345, 2143, 123, 4554, 9374983794839l, 979875}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    deltasMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertArrayEquals("Unexpected screen-off cpu times for uid=" + testUids[i],
                    deltasMs[i], u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked_isolatedUid() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int isolatedAppId = FIRST_ISOLATED_UID + 27;
        final int isolatedUid = UserHandle.getUid(testUserId, isolatedAppId);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                isolatedAppId,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            if (UserHandle.isIsolated(testUids[i])) {
                assertNull("There shouldn't be an entry for isolated uid=" + testUids[i], u);
                continue;
            }
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }
        verify(mKernelUidCpuFreqTimeReader).removeUid(isolatedUid);


        // Add an isolated uid mapping and repeat the test.

        // PRECONDITIONS
        final int ownerUid = UserHandle.getUid(testUserId, FIRST_APPLICATION_UID + 42);
        mBatteryStatsImpl.addIsolatedUidLocked(isolatedUid, ownerUid);
        final long[][] deltasMs = {
                {3, 12, 55, 100, 32},
                {32483274, 232349349, 123, 2398, 0},
                {43, 3345, 2143, 123, 4554}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            final long[] expectedTimes;
            if (UserHandle.isIsolated(testUids[i])) {
                assertNull("There shouldn't be an entry for isolated uid=" + testUids[i], u);
                // Since we added a mapping, an entry should've been created for owner uid.
                u = mBatteryStatsImpl.getUidStats().get(ownerUid);
                expectedTimes = deltasMs[i];
                assertNotNull("No entry for owner uid=" + ownerUid, u);
            } else {
                assertNotNull("No entry for uid=" + testUids[i], u);
                expectedTimes = sum(uidTimesMs[i], deltasMs[i]);
            }

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    expectedTimes, u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }
    }

    @Test
    public void testReadKernelUidCpuFreqTimesLocked_invalidUid() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        final int invalidUserId = 15;
        final int invalidUid = UserHandle.getUid(invalidUserId, FIRST_APPLICATION_UID + 99);
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        when(mUserInfoProvider.exists(invalidUserId)).thenReturn(false);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4, 10, 5, 9, 4},
                {5, 1, 12, 2, 10},
                {8, 25, 3, 0, 42}
        };
        doAnswer(invocation -> {
            final KernelUidCpuFreqTimeReader.Callback callback =
                    (KernelUidCpuFreqTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuFreqTime(testUids[i], uidTimesMs[i]);
            }
            // And one for the invalid uid
            callback.onUidCpuFreqTime(invalidUid, new long[]{12, 839, 32, 34, 21});
            return null;
        }).when(mKernelUidCpuFreqTimeReader).readDelta(
                any(KernelUidCpuFreqTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuFreqTimesLocked(null, true, false);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);

            assertArrayEquals("Unexpected cpu times for uid=" + testUids[i],
                    uidTimesMs[i], u.getCpuFreqTimes(STATS_SINCE_CHARGED));
            assertNull("Unexpected screen-off cpu times for uid=" + testUids[i],
                    u.getScreenOffCpuFreqTimes(STATS_SINCE_CHARGED));
        }
        assertNull("There shouldn't be an entry for invalid uid=" + invalidUid,
                mBatteryStatsImpl.getUidStats().get(invalidUid));
        verify(mKernelUidCpuFreqTimeReader).removeUid(invalidUid);
    }

    @Test
    public void testReadKernelUidCpuActiveTimesLocked() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[] uidTimesMs = {8000, 25000, 3000, 0, 42000};
        doAnswer(invocation -> {
            final KernelUidCpuActiveTimeReader.Callback callback =
                    (KernelUidCpuActiveTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuActiveTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuActiveTimeReader).readDelta(
                any(KernelUidCpuActiveTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuActiveTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected cpu active time for uid=" + testUids[i], uidTimesMs[i],
                    u.getCpuActiveTime());
        }

        // Repeat the test when the screen is off.

        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[] deltasMs = {43000, 3345000, 2143000, 123000, 4554000};
        doAnswer(invocation -> {
            final KernelUidCpuActiveTimeReader.Callback callback =
                    (KernelUidCpuActiveTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuActiveTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuActiveTimeReader).readDelta(
                any(KernelUidCpuActiveTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuActiveTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected cpu active time for uid=" + testUids[i],
                    uidTimesMs[i] + deltasMs[i], u.getCpuActiveTime());
        }
    }

    @Test
    public void testReadKernelUidCpuActiveTimesLocked_invalidUid() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        final int invalidUserId = 15;
        final int invalidUid = UserHandle.getUid(invalidUserId, FIRST_APPLICATION_UID + 99);
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        when(mUserInfoProvider.exists(invalidUserId)).thenReturn(false);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[] uidTimesMs = {8000, 25000, 3000, 0, 42000};
        doAnswer(invocation -> {
            final KernelUidCpuActiveTimeReader.Callback callback =
                    (KernelUidCpuActiveTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuActiveTime(testUids[i], uidTimesMs[i]);
            }
            // And one for the invalid uid
            callback.onUidCpuActiveTime(invalidUid, 1200L);
            return null;
        }).when(mKernelUidCpuActiveTimeReader).readDelta(
                any(KernelUidCpuActiveTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuActiveTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertEquals("Unexpected cpu active time for uid=" + testUids[i], uidTimesMs[i],
                    u.getCpuActiveTime());
        }
        assertNull("There shouldn't be an entry for invalid uid=" + invalidUid,
                mBatteryStatsImpl.getUidStats().get(invalidUid));
        verify(mKernelUidCpuActiveTimeReader).removeUid(invalidUid);
    }

    @Test
    public void testReadKernelUidCpuClusterTimesLocked() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4000, 10000},
                {5000, 1000},
                {8000, 0}
        };
        doAnswer(invocation -> {
            final KernelUidCpuClusterTimeReader.Callback callback =
                    (KernelUidCpuClusterTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuPolicyTime(testUids[i], uidTimesMs[i]);
            }
            return null;
        }).when(mKernelUidCpuClusterTimeReader).readDelta(
                any(KernelUidCpuClusterTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuClusterTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertArrayEquals("Unexpected cpu cluster time for uid=" + testUids[i], uidTimesMs[i],
                    u.getCpuClusterTimes());
        }

        // Repeat the test when the screen is off.

        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        final long[][] deltasMs = {
                {3000, 12000},
                {3248327490475L, 0},
                {43000, 3345000}
        };
        doAnswer(invocation -> {
            final KernelUidCpuClusterTimeReader.Callback callback =
                    (KernelUidCpuClusterTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuPolicyTime(testUids[i], deltasMs[i]);
            }
            return null;
        }).when(mKernelUidCpuClusterTimeReader).readDelta(
                any(KernelUidCpuClusterTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuClusterTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertArrayEquals("Unexpected cpu cluster time for uid=" + testUids[i], sum(uidTimesMs[i], deltasMs[i]),
                    u.getCpuClusterTimes());
        }
    }

    @Test
    public void testReadKernelUidCpuClusterTimesLocked_invalidUid() {
        // PRECONDITIONS
        updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int testUserId = 11;
        final int invalidUserId = 15;
        final int invalidUid = UserHandle.getUid(invalidUserId, FIRST_APPLICATION_UID + 99);
        when(mUserInfoProvider.exists(testUserId)).thenReturn(true);
        when(mUserInfoProvider.exists(invalidUserId)).thenReturn(false);
        final int[] testUids = getUids(testUserId, new int[]{
                FIRST_APPLICATION_UID + 22,
                FIRST_APPLICATION_UID + 27,
                FIRST_APPLICATION_UID + 33
        });
        final long[][] uidTimesMs = {
                {4000, 10000},
                {5000, 1000},
                {8000, 0}
        };
        doAnswer(invocation -> {
            final KernelUidCpuClusterTimeReader.Callback callback =
                    (KernelUidCpuClusterTimeReader.Callback) invocation.getArguments()[0];
            for (int i = 0; i < testUids.length; ++i) {
                callback.onUidCpuPolicyTime(testUids[i], uidTimesMs[i]);
            }
            // And one for the invalid uid
            callback.onUidCpuPolicyTime(invalidUid, new long[] {400, 1000});
            return null;
        }).when(mKernelUidCpuClusterTimeReader).readDelta(
                any(KernelUidCpuClusterTimeReader.Callback.class));

        // RUN
        mBatteryStatsImpl.readKernelUidCpuClusterTimesLocked(true);

        // VERIFY
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            assertNotNull("No entry for uid=" + testUids[i], u);
            assertArrayEquals("Unexpected cpu cluster time for uid=" + testUids[i], uidTimesMs[i],
                    u.getCpuClusterTimes());
        }
        assertNull("There shouldn't be an entry for invalid uid=" + invalidUid,
                mBatteryStatsImpl.getUidStats().get(invalidUid));
        verify(mKernelUidCpuClusterTimeReader).removeUid(invalidUid);
    }

    @Test
    public void testRemoveUidCpuTimes() {
        mClocks.realtime = mClocks.uptime = 0;
        mBatteryStatsImpl.getPendingRemovedUids().add(
                mBatteryStatsImpl.new UidToRemove(1, mClocks.elapsedRealtime()));
        mBatteryStatsImpl.getPendingRemovedUids().add(
                mBatteryStatsImpl.new UidToRemove(5, 10, mClocks.elapsedRealtime()));
        mBatteryStatsImpl.clearPendingRemovedUids();
        assertEquals(2, mBatteryStatsImpl.getPendingRemovedUids().size());

        mClocks.realtime = mClocks.uptime = 100_000;
        mBatteryStatsImpl.clearPendingRemovedUids();
        assertEquals(2, mBatteryStatsImpl.getPendingRemovedUids().size());

        mClocks.realtime = mClocks.uptime = 200_000;
        mBatteryStatsImpl.getPendingRemovedUids().add(
                mBatteryStatsImpl.new UidToRemove(100, mClocks.elapsedRealtime()));
        mBatteryStatsImpl.clearPendingRemovedUids();
        assertEquals(3, mBatteryStatsImpl.getPendingRemovedUids().size());

        mClocks.realtime = mClocks.uptime = 400_000;
        mBatteryStatsImpl.clearPendingRemovedUids();
        assertEquals(1, mBatteryStatsImpl.getPendingRemovedUids().size());
        verify(mKernelUidCpuActiveTimeReader).removeUid(1);
        verify(mKernelUidCpuActiveTimeReader).removeUidsInRange(5, 10);
        verify(mKernelUidCpuClusterTimeReader).removeUid(1);
        verify(mKernelUidCpuClusterTimeReader).removeUidsInRange(5, 10);
        verify(mKernelUidCpuFreqTimeReader).removeUid(1);
        verify(mKernelUidCpuFreqTimeReader).removeUidsInRange(5, 10);
        verify(mKernelUidCpuTimeReader).removeUid(1);
        verify(mKernelUidCpuTimeReader).removeUidsInRange(5, 10);

        mClocks.realtime = mClocks.uptime = 800_000;
        mBatteryStatsImpl.clearPendingRemovedUids();
        assertEquals(0, mBatteryStatsImpl.getPendingRemovedUids().size());
        verify(mKernelUidCpuActiveTimeReader).removeUid(100);
        verify(mKernelUidCpuClusterTimeReader).removeUid(100);
        verify(mKernelUidCpuFreqTimeReader).removeUid(100);
        verify(mKernelUidCpuTimeReader).removeUid(100);

        verifyNoMoreInteractions(mKernelUidCpuActiveTimeReader, mKernelUidCpuClusterTimeReader,
                mKernelUidCpuFreqTimeReader, mKernelUidCpuTimeReader);
    }

    private void updateTimeBasesLocked(boolean unplugged, int screenState,
            long upTime, long realTime) {
        // Set PowerProfile=null before calling updateTimeBasesLocked to avoid execution of
        // BatteryStatsImpl.updateCpuTimeLocked
        mBatteryStatsImpl.setPowerProfile(null);
        mBatteryStatsImpl.updateTimeBasesLocked(unplugged, screenState, upTime, realTime);
        mBatteryStatsImpl.setPowerProfile(mPowerProfile);
    }

    private void initKernelCpuSpeedReaders(int count) {
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[count];
        for (int i = 0; i < count; ++i) {
            mKernelCpuSpeedReaders[i] = Mockito.mock(KernelCpuSpeedReader.class);
        }
        mBatteryStatsImpl.setKernelCpuSpeedReaders(mKernelCpuSpeedReaders);
    }

    private ArrayList<BatteryStatsImpl.StopwatchTimer> getPartialTimers(int... uids) {
        final ArrayList<BatteryStatsImpl.StopwatchTimer> partialTimers = new ArrayList<>();
        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        for (int uid : uids) {
            final BatteryStatsImpl.Uid u = mBatteryStatsImpl.getUidStatsLocked(uid);
            final BatteryStatsImpl.StopwatchTimer timer = new BatteryStatsImpl.StopwatchTimer(
                    mClocks, u, WAKE_TYPE_PARTIAL, null, timeBase);
            partialTimers.add(timer);
        }
        return partialTimers;
    }

    private long[] sum(long[] a, long[] b) {
        assertEquals("Arrays a: " + Arrays.toString(a) + ", b: " + Arrays.toString(b),
                a.length, b.length);
        final long[] result = new long[a.length];
        for (int i = 0; i < a.length; ++i) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    private int[] getUids(int userId, int[] appIds) {
        final int[] uids = new int[appIds.length];
        for (int i = appIds.length - 1; i >= 0; --i) {
            uids[i] = UserHandle.getUid(userId, appIds[i]);
        }
        return uids;
    }
}
