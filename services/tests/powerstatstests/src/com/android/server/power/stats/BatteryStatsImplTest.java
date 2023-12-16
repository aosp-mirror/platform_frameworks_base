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

package com.android.server.power.stats;

import static android.os.BatteryStats.Uid.NUM_PROCESS_STATE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryStats.Uid.PROCESS_STATE_CACHED;
import static android.os.BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_TOP;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.UidTraffic;
import android.content.Context;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BluetoothBatteryStats;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.WakeLockStats;
import android.os.WorkSource;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelSingleUidTimeReader;
import com.android.internal.os.LongArrayMultiStateCounter;
import com.android.internal.os.PowerProfile;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.LongSubject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.time.Instant;
import java.util.List;

@LargeTest
@RunWith(AndroidJUnit4.class)
@SuppressWarnings("GuardedBy")
public class BatteryStatsImplTest {
    @Mock
    private KernelCpuUidFreqTimeReader mKernelUidCpuFreqTimeReader;
    @Mock
    private KernelSingleUidTimeReader mKernelSingleUidTimeReader;
    @Mock
    private PowerProfile mPowerProfile;
    @Mock
    private KernelWakelockReader mKernelWakelockReader;
    private KernelWakelockStats mKernelWakelockStats = new KernelWakelockStats();

    private static final int NUM_CPU_FREQS = 5;

    private final CpuScalingPolicies mCpuScalingPolicies = new CpuScalingPolicies(
            new SparseArray<>() {{
                put(0, new int[1]);
            }},
            new SparseArray<>() {{
                put(0, new int[NUM_CPU_FREQS]);
            }});

    private final MockClock mMockClock = new MockClock();
    private MockBatteryStatsImpl mBatteryStatsImpl;
    private Handler mHandler;
    private PowerStatsStore mPowerStatsStore;
    private BatteryUsageStatsProvider mBatteryUsageStatsProvider;
    @Mock
    private PowerStatsExporter mPowerStatsExporter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mKernelUidCpuFreqTimeReader.isFastCpuTimesReader()).thenReturn(true);
        when(mKernelUidCpuFreqTimeReader.allUidTimesAvailable()).thenReturn(true);
        when(mKernelSingleUidTimeReader.singleUidCpuTimesAvailable()).thenReturn(true);
        when(mKernelWakelockReader.readKernelWakelockStats(
                any(KernelWakelockStats.class))).thenReturn(mKernelWakelockStats);
        HandlerThread bgThread = new HandlerThread("bg thread");
        bgThread.start();
        mHandler = new Handler(bgThread.getLooper());
        mBatteryStatsImpl = new MockBatteryStatsImpl(mMockClock, null, mHandler)
                .setPowerProfile(mPowerProfile)
                .setCpuScalingPolicies(mCpuScalingPolicies)
                .setKernelCpuUidFreqTimeReader(mKernelUidCpuFreqTimeReader)
                .setKernelSingleUidTimeReader(mKernelSingleUidTimeReader)
                .setKernelWakelockReader(mKernelWakelockReader);

        final Context context = InstrumentationRegistry.getContext();
        File systemDir = context.getCacheDir();
        mPowerStatsStore = new PowerStatsStore(systemDir, mHandler,
                new AggregatedPowerStatsConfig());
        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(context, mPowerStatsExporter,
                mPowerProfile, mBatteryStatsImpl.getCpuScalingPolicies(), mPowerStatsStore,
                mMockClock);
    }

    @Test
    public void testUpdateProcStateCpuTimes() {
        mBatteryStatsImpl.setOnBatteryInternal(true);
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        final int[] testUids = {10032, 10048, 10145, 10139};
        final int[] activityManagerProcStates = {
                ActivityManager.PROCESS_STATE_RECEIVER,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                ActivityManager.PROCESS_STATE_TOP,
                ActivityManager.PROCESS_STATE_CACHED_EMPTY
        };
        final int[] testProcStates = {
                PROCESS_STATE_BACKGROUND,
                PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_STATE_TOP,
                PROCESS_STATE_CACHED
        };

        // Initialize time-in-freq counters
        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;
        for (int i = 0; i < testUids.length; ++i) {
            mockKernelSingleUidTimeReader(testUids[i], new long[5]);
            mBatteryStatsImpl.noteUidProcessStateLocked(testUids[i], activityManagerProcStates[i]);
        }

        final long[] timeInFreqs = new long[NUM_CPU_FREQS];

        // Verify there are no cpu times initially.
        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStatsLocked(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                assertFalse(u.getCpuFreqTimes(timeInFreqs, procState));
                assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
            }
        }

        // Obtain initial CPU time-in-freq counts
        final long[][] cpuTimes = {
                {349734983, 394982394832L, 909834, 348934, 9838},
                {7498, 1239890, 988, 13298, 98980},
                {989834, 384098, 98483, 23809, 4984},
                {4859048, 348903, 4578967, 5973894, 298549}
        };

        mMockClock.realtime += 1000;
        mMockClock.uptime += 1000;

        for (int i = 0; i < testUids.length; ++i) {
            mockKernelSingleUidTimeReader(testUids[i], cpuTimes[i]);
            mBatteryStatsImpl.updateProcStateCpuTimesLocked(testUids[i],
                    mMockClock.realtime, mMockClock.uptime);
        }

        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                final boolean hasTimeInFreq = u.getCpuFreqTimes(timeInFreqs, procState);
                if (procState == testProcStates[i]) {
                    assertArrayEquals("Uid=" + testUids[i], cpuTimes[i], timeInFreqs);
                } else {
                    assertFalse(hasTimeInFreq);
                }
                assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
            }
        }

        // Accumulate CPU time-in-freq deltas
        final long[][] delta1 = {
                {9589, 148934, 309894, 3098493, 98754},
                {21983, 94983, 4983, 9878493, 84854},
                {945894, 9089432, 19478, 3834, 7845},
                {843895, 43948, 949582, 99, 384}
        };

        mMockClock.realtime += 1000;
        mMockClock.uptime += 1000;

        for (int i = 0; i < testUids.length; ++i) {
            long[] newCpuTimes = new long[cpuTimes[i].length];
            for (int j = 0; j < cpuTimes[i].length; j++) {
                newCpuTimes[j] = cpuTimes[i][j] + delta1[i][j];
            }
            mockKernelSingleUidTimeReader(testUids[i], newCpuTimes);
            mBatteryStatsImpl.updateProcStateCpuTimesLocked(testUids[i],
                    mMockClock.realtime, mMockClock.uptime);
        }

        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j];
                    }
                    assertTrue(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes, timeInFreqs);
                } else {
                    assertFalse(u.getCpuFreqTimes(timeInFreqs, procState));
                }
                assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
            }
        }

        // Validate the on-battery-screen-off counter
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, mMockClock.uptime * 1000,
                mMockClock.realtime * 1000);

        final long[][] delta2 = {
                {95932, 2943, 49834, 89034, 139},
                {349, 89605, 5896, 845, 98444},
                {678, 7498, 9843, 889, 4894},
                {488, 998, 8498, 394, 574}
        };

        mMockClock.realtime += 1000;
        mMockClock.uptime += 1000;

        for (int i = 0; i < testUids.length; ++i) {
            long[] newCpuTimes = new long[cpuTimes[i].length];
            for (int j = 0; j < cpuTimes[i].length; j++) {
                newCpuTimes[j] = cpuTimes[i][j] + delta1[i][j] + delta2[i][j];
            }
            mockKernelSingleUidTimeReader(testUids[i], newCpuTimes);
            mBatteryStatsImpl.updateProcStateCpuTimesLocked(testUids[i],
                    mMockClock.realtime, mMockClock.uptime);
        }

        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j] + delta2[i][j];
                    }
                    assertTrue(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes, timeInFreqs);
                    assertTrue(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], delta2[i], timeInFreqs);
                } else {
                    assertFalse(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
                }
            }
        }

        // Verify handling of isolated UIDs - their time-in-freq must be directly
        // added to that of the parent UID's.  The proc state of the isolated UID is
        // assumed to be the same as that of the parent UID, so there is no per-state
        // data for isolated UIDs.
        final long[][] delta3 = {
                {98545, 95768795, 76586, 548945, 57846},
                {788876, 586, 578459, 8776984, 9578923},
                {3049509483598L, 4597834, 377654, 94589035, 7854},
                {9493, 784, 99895, 8974893, 9879843}
        };

        mMockClock.realtime += 1000;
        mMockClock.uptime += 1000;

        final int parentUid = testUids[1];
        final int childUid = 99099;
        addIsolatedUid(parentUid, childUid);
        final long[] isolatedUidCpuTimes = {495784, 398473, 4895, 4905, 30984093};
        mockKernelSingleUidTimeReader(childUid, isolatedUidCpuTimes, isolatedUidCpuTimes);

        for (int i = 0; i < testUids.length; ++i) {
            long[] newCpuTimes = new long[cpuTimes[i].length];
            for (int j = 0; j < cpuTimes[i].length; j++) {
                newCpuTimes[j] = cpuTimes[i][j] + delta1[i][j] + delta2[i][j] + delta3[i][j];
            }
            mockKernelSingleUidTimeReader(testUids[i], newCpuTimes);
            mBatteryStatsImpl.updateProcStateCpuTimesLocked(testUids[i],
                    mMockClock.realtime, mMockClock.uptime);
        }

        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    long[] expectedCpuTimes = cpuTimes[i].clone();
                    for (int j = 0; j < expectedCpuTimes.length; ++j) {
                        expectedCpuTimes[j] += delta1[i][j] + delta2[i][j] + delta3[i][j]
                                + (testUids[i] == parentUid ? isolatedUidCpuTimes[j] : 0);
                    }
                    assertTrue(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes, timeInFreqs);
                    long[] expectedScreenOffTimes = delta2[i].clone();
                    for (int j = 0; j < expectedScreenOffTimes.length; ++j) {
                        expectedScreenOffTimes[j] += delta3[i][j]
                                + (testUids[i] == parentUid ? isolatedUidCpuTimes[j] : 0);
                    }
                    assertTrue(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], expectedScreenOffTimes, timeInFreqs);
                } else {
                    assertFalse(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
                }
            }
        }
    }

    @Test
    public void testUpdateCpuTimesForAllUids() {
        mBatteryStatsImpl.setOnBatteryInternal(false);
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;

        final int[] testUids = {10032, 10048, 10145, 10139};
        final int[] testProcStates = {
                PROCESS_STATE_BACKGROUND,
                PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_STATE_TOP,
                PROCESS_STATE_CACHED
        };

        for (int i = 0; i < testUids.length; ++i) {
            BatteryStatsImpl.Uid uid = mBatteryStatsImpl.getUidStatsLocked(testUids[i]);
            uid.setProcessStateForTest(testProcStates[i], mMockClock.elapsedRealtime());
            mockKernelSingleUidTimeReader(testUids[i], new long[NUM_CPU_FREQS]);
            mBatteryStatsImpl.updateProcStateCpuTimesLocked(testUids[i],
                    mMockClock.elapsedRealtime(), mMockClock.uptime);
        }

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
                {843598745, 397843, 32749, 99854, 23454},
                {9834, 5885, 487589, 394, 93933},
                {203984, 439, 9859, 30948, 49494},
                {9389, 858, 239, 349, 50505}
        };
        for (int i = 0; i < testUids.length; ++i) {
            mockKernelSingleUidTimeReader(testUids[i], expectedCpuTimes[i]);
        }

        mMockClock.realtime += 1000;
        mMockClock.uptime += 1000;

        mBatteryStatsImpl.updateCpuTimesForAllUids();

        final long[] timeInFreqs = new long[NUM_CPU_FREQS];

        for (int i = 0; i < testUids.length; ++i) {
            final BatteryStats.Uid u = mBatteryStatsImpl.getUidStats().get(testUids[i]);
            for (int procState = 0; procState < NUM_PROCESS_STATE; ++procState) {
                if (procState == testProcStates[i]) {
                    assertTrue(u.getCpuFreqTimes(timeInFreqs, procState));
                    assertArrayEquals("Uid=" + testUids[i], expectedCpuTimes[i], timeInFreqs);
                } else {
                    assertFalse(u.getCpuFreqTimes(timeInFreqs, procState));
                }
                assertFalse(u.getScreenOffCpuFreqTimes(timeInFreqs, procState));
            }
        }
    }

    private void mockKernelSingleUidTimeReader(int testUid, long[] cpuTimes) {
        doAnswer(invocation -> {
            LongArrayMultiStateCounter counter = invocation.getArgument(1);
            long timestampMs = invocation.getArgument(2);
            LongArrayMultiStateCounter.LongArrayContainer container =
                    new LongArrayMultiStateCounter.LongArrayContainer(NUM_CPU_FREQS);
            container.setValues(cpuTimes);
            counter.updateValues(container, timestampMs);
            return null;
        }).when(mKernelSingleUidTimeReader).addDelta(eq(testUid),
                any(LongArrayMultiStateCounter.class), anyLong());
    }

    private void mockKernelSingleUidTimeReader(int testUid, long[] cpuTimes, long[] delta) {
        doAnswer(invocation -> {
            LongArrayMultiStateCounter counter = invocation.getArgument(1);
            long timestampMs = invocation.getArgument(2);
            LongArrayMultiStateCounter.LongArrayContainer deltaContainer =
                    invocation.getArgument(3);

            LongArrayMultiStateCounter.LongArrayContainer container =
                    new LongArrayMultiStateCounter.LongArrayContainer(NUM_CPU_FREQS);
            container.setValues(cpuTimes);
            counter.updateValues(container, timestampMs);
            if (deltaContainer != null) {
                deltaContainer.setValues(delta);
            }
            return null;
        }).when(mKernelSingleUidTimeReader).addDelta(eq(testUid),
                any(LongArrayMultiStateCounter.class), anyLong(),
                any(LongArrayMultiStateCounter.LongArrayContainer.class));
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
        long totalTime = u.getWifiMulticastTime(currentTimeMs * 1000,
                BatteryStats.STATS_SINCE_CHARGED);
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
        long totalTime = u.getWifiMulticastTime(currentTimeMs * 1000,
                BatteryStats.STATS_SINCE_CHARGED);
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
        long totalTime = u.getWifiMulticastTime(currentTimeMs * 1000,
                BatteryStats.STATS_SINCE_CHARGED);
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
        long totalTime = u.getWifiMulticastTime(currentTimeMs * 1000,
                BatteryStats.STATS_SINCE_CHARGED);
        assertEquals("Miscalculations of Multicast wakelock acquisition time",
                ((releaseTimeMs_1 - acquireTimeMs_1) + (releaseTimeMs_2 - acquireTimeMs_2))
                        * 1000, totalTime);
    }

    private void addIsolatedUid(int parentUid, int childUid) {
        final BatteryStatsImpl.Uid u = mBatteryStatsImpl.getUidStatsLocked(parentUid);
        u.addIsolatedUid(childUid);
    }

    @Test
    public void testGetWakeLockStats() {
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        // First wakelock, acquired once, not currently held
        mMockClock.realtime = 1000;
        mBatteryStatsImpl.noteStartWakeLocked(10100, 100, null, "wakeLock1", null,
                BatteryStats.WAKE_TYPE_PARTIAL, false);

        mMockClock.realtime = 3000;
        mBatteryStatsImpl.noteStopWakeLocked(10100, 100, null, "wakeLock1", null,
                BatteryStats.WAKE_TYPE_PARTIAL);

        // Second wakelock, acquired twice, still held
        mMockClock.realtime = 4000;
        mBatteryStatsImpl.noteStartWakeLocked(10200, 101, null, "wakeLock2", null,
                BatteryStats.WAKE_TYPE_PARTIAL, false);

        mMockClock.realtime = 5000;
        mBatteryStatsImpl.noteStopWakeLocked(10200, 101, null, "wakeLock2", null,
                BatteryStats.WAKE_TYPE_PARTIAL);

        mMockClock.realtime = 6000;
        mBatteryStatsImpl.noteStartWakeLocked(10200, 101, null, "wakeLock2", null,
                BatteryStats.WAKE_TYPE_PARTIAL, false);

        mMockClock.realtime = 9000;

        List<WakeLockStats.WakeLock> wakeLockStats =
                mBatteryStatsImpl.getWakeLockStats().getWakeLocks();
        assertThat(wakeLockStats).hasSize(2);

        WakeLockStats.WakeLock wakeLock1 = wakeLockStats.stream()
                .filter(wl -> wl.uid == 10100 && wl.name.equals("wakeLock1")).findFirst().get();

        assertThat(wakeLock1.timesAcquired).isEqualTo(1);
        assertThat(wakeLock1.timeHeldMs).isEqualTo(0);  // Not currently held
        assertThat(wakeLock1.totalTimeHeldMs).isEqualTo(2000); // 3000-1000

        WakeLockStats.WakeLock wakeLock2 = wakeLockStats.stream()
                .filter(wl -> wl.uid == 10200 && wl.name.equals("wakeLock2")).findFirst().get();

        assertThat(wakeLock2.timesAcquired).isEqualTo(2);
        assertThat(wakeLock2.timeHeldMs).isEqualTo(3000);  // 9000-6000
        assertThat(wakeLock2.totalTimeHeldMs).isEqualTo(4000); // (5000-4000) + (9000-6000)
    }

    @Test
    public void kernelWakelocks() {
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        mKernelWakelockStats.put("lock1", new KernelWakelockStats.Entry(42, 1000, 314, 0));
        mKernelWakelockStats.put("lock2", new KernelWakelockStats.Entry(6, 2000, 0, 0));

        mMockClock.realtime = 5000;

        // The fist call makes a snapshot of the initial state of the wakelocks
        mBatteryStatsImpl.updateKernelWakelocksLocked(mMockClock.realtime * 1000);

        assertThat(mBatteryStatsImpl.getKernelWakelockStats()).hasSize(2);

        mMockClock.realtime += 2000;

        assertThatKernelWakelockTotalTime("lock1").isEqualTo(314);  // active
        assertThatKernelWakelockTotalTime("lock2").isEqualTo(0);        // inactive

        mKernelWakelockStats.put("lock1", new KernelWakelockStats.Entry(43, 1100, 414, 0));
        mKernelWakelockStats.put("lock2", new KernelWakelockStats.Entry(6, 2222, 0, 0));

        mMockClock.realtime += 3000;

        // Compute delta from the initial snapshot
        mBatteryStatsImpl.updateKernelWakelocksLocked(mMockClock.realtime * 1000);

        mMockClock.realtime += 4000;

        assertThatKernelWakelockTotalTime("lock1").isEqualTo(414);

        // Wake lock not active. Expect relative total time as reported by Kernel:
        // 2_222 - 2_000 = 222
        assertThatKernelWakelockTotalTime("lock2").isEqualTo(222);
    }

    private LongSubject assertThatKernelWakelockTotalTime(String name) {
        return assertWithMessage("Kernel wakelock " + name + " at " + mMockClock.realtime)
                .that(mBatteryStatsImpl.getKernelWakelockStats().get(name)
                        .getTotalTimeLocked(mMockClock.realtime * 1000, 0));
    }

    @Test
    public void testGetBluetoothBatteryStats() {
        when(mPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE)).thenReturn(3.0);
        mBatteryStatsImpl.setOnBatteryInternal(true);
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        final WorkSource ws = new WorkSource(10042);
        mBatteryStatsImpl.noteBluetoothScanStartedFromSourceLocked(ws, false, 1000, 1000);
        mBatteryStatsImpl.noteBluetoothScanStoppedFromSourceLocked(ws, false, 5000, 5000);
        mBatteryStatsImpl.noteBluetoothScanStartedFromSourceLocked(ws, true, 6000, 6000);
        mBatteryStatsImpl.noteBluetoothScanStoppedFromSourceLocked(ws, true, 9000, 9000);
        mBatteryStatsImpl.noteBluetoothScanResultsFromSourceLocked(ws, 42, 9000, 9000);

        BluetoothActivityEnergyInfo info = createBluetoothActivityEnergyInfo(
                /* timestamp= */ 1000,
                /* controllerTxTimeMs= */ 9000,
                /* controllerRxTimeMs= */ 8000,
                /* controllerIdleTimeMs= */ 12000,
                /* controllerEnergyUsed= */ 0,
                createUidTraffic(/* appUid= */ 10042, /* rxBytes= */ 3000, /* txBytes= */ 4000),
                createUidTraffic(/* appUid= */ 10043, /* rxBytes= */ 5000, /* txBytes= */ 8000));

        mBatteryStatsImpl.updateBluetoothStateLocked(info, -1, 1000, 1000);

        BluetoothBatteryStats stats =
                mBatteryStatsImpl.getBluetoothBatteryStats();
        assertThat(stats.getUidStats()).hasSize(2);

        final BluetoothBatteryStats.UidStats uidStats =
                stats.getUidStats().stream().filter(u -> u.uid == 10042).findFirst().get();
        assertThat(uidStats.scanTimeMs).isEqualTo(7000);  // 4000+3000
        assertThat(uidStats.unoptimizedScanTimeMs).isEqualTo(3000);
        assertThat(uidStats.scanResultCount).isEqualTo(42);
        assertThat(uidStats.rxTimeMs).isEqualTo(7375);  // Some scan time is treated as RX
        assertThat(uidStats.txTimeMs).isEqualTo(7666);  // Some scan time is treated as TX
    }

    /** A regression test for b/266128651 */
    @Test
    public void testGetNetworkActivityBytes_multipleUpdates() {
        when(mPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE)).thenReturn(3.0);
        mBatteryStatsImpl.setOnBatteryInternal(true);
        mBatteryStatsImpl.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);

        BluetoothActivityEnergyInfo info1 = createBluetoothActivityEnergyInfo(
                /* timestamp= */ 10000,
                /* controllerTxTimeMs= */ 9000,
                /* controllerRxTimeMs= */ 8000,
                /* controllerIdleTimeMs= */ 2000,
                /* controllerEnergyUsed= */ 0,
                createUidTraffic(/* appUid= */ 10042, /* rxBytes= */ 3000, /* txBytes= */ 4000),
                createUidTraffic(/* appUid= */ 10043, /* rxBytes= */ 5000, /* txBytes= */ 8000));

        mBatteryStatsImpl.updateBluetoothStateLocked(info1, -1, 1000, 1000);

        long totalRx1 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_RX_DATA, BatteryStats.STATS_SINCE_CHARGED);
        long totalTx1 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_TX_DATA, BatteryStats.STATS_SINCE_CHARGED);

        assertThat(totalRx1).isEqualTo(8000);  // 3000 + 5000
        assertThat(totalTx1).isEqualTo(12000);  // 4000 + 8000

        BluetoothActivityEnergyInfo info2 = createBluetoothActivityEnergyInfo(
                /* timestamp= */ 20000,
                /* controllerTxTimeMs= */ 19000,
                /* controllerRxTimeMs= */ 18000,
                /* controllerIdleTimeMs= */ 3000,
                /* controllerEnergyUsed= */ 0,
                createUidTraffic(/* appUid= */ 10043, /* rxBytes= */ 6000, /* txBytes= */ 9500),
                createUidTraffic(/* appUid= */ 10044, /* rxBytes= */ 7000, /* txBytes= */ 9000));

        mBatteryStatsImpl.updateBluetoothStateLocked(info2, -1, 2000, 2000);

        long totalRx2 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_RX_DATA, BatteryStats.STATS_SINCE_CHARGED);
        long totalTx2 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_TX_DATA, BatteryStats.STATS_SINCE_CHARGED);

        assertThat(totalRx2).isEqualTo(16000);  // 3000 + 6000 (updated) + 7000 (new)
        assertThat(totalTx2).isEqualTo(22500);  // 4000 + 9500 (updated) + 9000 (new)

        BluetoothActivityEnergyInfo info3 = createBluetoothActivityEnergyInfo(
                /* timestamp= */ 30000,
                /* controllerTxTimeMs= */ 20000,
                /* controllerRxTimeMs= */ 20000,
                /* controllerIdleTimeMs= */ 4000,
                /* controllerEnergyUsed= */ 0,
                createUidTraffic(/* appUid= */ 10043, /* rxBytes= */ 7000, /* txBytes= */ 9900),
                createUidTraffic(/* appUid= */ 10044, /* rxBytes= */ 8000, /* txBytes= */ 10000));

        mBatteryStatsImpl.updateBluetoothStateLocked(info3, -1, 2000, 2000);

        long totalRx3 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_RX_DATA, BatteryStats.STATS_SINCE_CHARGED);
        long totalTx3 = mBatteryStatsImpl.getNetworkActivityBytes(
                BatteryStats.NETWORK_BT_TX_DATA, BatteryStats.STATS_SINCE_CHARGED);

        assertThat(totalRx3).isEqualTo(18000);  // 3000 + 7000 (updated) + 8000 (updated)
        assertThat(totalTx3).isEqualTo(23900);  // 4000 + 9900 (updated) + 10000 (updated)
    }

    private UidTraffic createUidTraffic(int appUid, long rxBytes, long txBytes) {
        final Parcel parcel = Parcel.obtain();
        parcel.writeInt(appUid); // mAppUid
        parcel.writeLong(rxBytes); // mRxBytes
        parcel.writeLong(txBytes); // mTxBytes
        parcel.setDataPosition(0);
        UidTraffic uidTraffic = UidTraffic.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return uidTraffic;
    }

    private BluetoothActivityEnergyInfo createBluetoothActivityEnergyInfo(
            long timestamp,
            long controllerTxTimeMs,
            long controllerRxTimeMs,
            long controllerIdleTimeMs,
            long controllerEnergyUsed,
            UidTraffic... uidTraffic) {
        Parcel parcel = Parcel.obtain();
        parcel.writeLong(timestamp); // mTimestamp
        parcel.writeInt(
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE); // mBluetoothStackState
        parcel.writeLong(controllerTxTimeMs); // mControllerTxTimeMs;
        parcel.writeLong(controllerRxTimeMs); // mControllerRxTimeMs;
        parcel.writeLong(controllerIdleTimeMs); // mControllerIdleTimeMs;
        parcel.writeLong(controllerEnergyUsed); // mControllerEnergyUsed;
        parcel.writeTypedList(ImmutableList.copyOf(uidTraffic)); // mUidTraffic
        parcel.setDataPosition(0);

        BluetoothActivityEnergyInfo info =
                BluetoothActivityEnergyInfo.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return info;
    }

    @Test
    public void storeBatteryUsageStatsOnReset() {
        mBatteryStatsImpl.forceRecordAllHistory();

        mMockClock.currentTime = Instant.parse("2023-01-02T03:04:05.00Z").toEpochMilli();
        mMockClock.realtime = 7654321;

        synchronized (mBatteryStatsImpl) {
            mBatteryStatsImpl.setOnBatteryLocked(mMockClock.realtime, mMockClock.uptime, true,
                    BatteryManager.BATTERY_STATUS_DISCHARGING, 50, 0);
            // Will not save to PowerStatsStore because "saveBatteryUsageStatsOnReset" has not
            // been called yet.
            mBatteryStatsImpl.resetAllStatsAndHistoryLocked(
                    BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        assertThat(mPowerStatsStore.getTableOfContents()).isEmpty();

        mBatteryStatsImpl.saveBatteryUsageStatsOnReset(mBatteryUsageStatsProvider,
                mPowerStatsStore);

        synchronized (mBatteryStatsImpl) {
            mBatteryStatsImpl.noteFlashlightOnLocked(42, mMockClock.realtime, mMockClock.uptime);
        }

        mMockClock.realtime += 60000;
        mMockClock.currentTime += 60000;

        synchronized (mBatteryStatsImpl) {
            mBatteryStatsImpl.noteFlashlightOffLocked(42, mMockClock.realtime, mMockClock.uptime);
        }

        mMockClock.realtime += 60000;
        mMockClock.currentTime += 60000;

        // Battery stats reset should have the side-effect of saving accumulated battery usage stats
        synchronized (mBatteryStatsImpl) {
            mBatteryStatsImpl.resetAllStatsAndHistoryLocked(
                    BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        // Await completion
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();

        List<PowerStatsSpan.Metadata> contents = mPowerStatsStore.getTableOfContents();
        assertThat(contents).hasSize(1);

        PowerStatsSpan.Metadata metadata = contents.get(0);

        PowerStatsSpan span = mPowerStatsStore.loadPowerStatsSpan(metadata.getId(),
                BatteryUsageStatsSection.TYPE);
        assertThat(span).isNotNull();

        List<PowerStatsSpan.TimeFrame> timeFrames = span.getMetadata().getTimeFrames();
        assertThat(timeFrames).hasSize(1);
        assertThat(timeFrames.get(0).startMonotonicTime).isEqualTo(7654321);
        assertThat(timeFrames.get(0).duration).isEqualTo(120000);

        List<PowerStatsSpan.Section> sections = span.getSections();
        assertThat(sections).hasSize(1);

        PowerStatsSpan.Section section = sections.get(0);
        assertThat(section.getType()).isEqualTo(BatteryUsageStatsSection.TYPE);
        BatteryUsageStats bus = ((BatteryUsageStatsSection) section).getBatteryUsageStats();
        assertThat(bus.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isEqualTo(60000);
    }
}
