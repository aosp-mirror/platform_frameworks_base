/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CpuPowerStatsCollectorTest {
    private final MockClock mMockClock = new MockClock();
    private final HandlerThread mHandlerThread = new HandlerThread("test");
    private Handler mHandler;
    private CpuPowerStatsCollector mCollector;
    private PowerStats mCollectedStats;
    @Mock
    private PowerProfile mPowerProfile;
    @Mock
    private CpuPowerStatsCollector.KernelCpuStatsReader mMockKernelCpuStatsReader;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        when(mPowerProfile.getCpuPowerBracketCount()).thenReturn(2);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 0)).thenReturn(0);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 1)).thenReturn(1);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 2)).thenReturn(1);
        when(mMockKernelCpuStatsReader.nativeIsSupportedFeature()).thenReturn(true);
        mCollector = new CpuPowerStatsCollector(new CpuScalingPolicies(
                new SparseArray<>() {{
                        put(0, new int[]{0});
                }},
                new SparseArray<>() {{
                        put(0, new int[]{1, 12, 24});
                }}),
                mPowerProfile, mHandler, mMockKernelCpuStatsReader, 60_000, mMockClock);
        mCollector.addConsumer(stats -> mCollectedStats = stats);
        mCollector.setEnabled(true);
    }

    @Test
    public void collectStats() {
        mockKernelCpuStats(new long[]{1111, 2222, 3333},
                new SparseArray<>() {{
                    put(42, new long[]{100, 200});
                    put(99, new long[]{300, 600});
                }}, 0, 1234);

        mMockClock.uptime = 1000;
        mCollector.forceSchedule();
        waitForIdle();

        assertThat(mCollectedStats.durationMs).isEqualTo(1234);
        assertThat(mCollectedStats.stats).isEqualTo(new long[]{1111, 2222, 3333, 1000, 0});
        assertThat(mCollectedStats.uidStats.get(42)).isEqualTo(new long[]{100, 200, 0});
        assertThat(mCollectedStats.uidStats.get(99)).isEqualTo(new long[]{300, 600, 0});

        mockKernelCpuStats(new long[]{5555, 4444, 3333},
                new SparseArray<>() {{
                    put(42, new long[]{123, 234});
                    put(99, new long[]{345, 678});
                }}, 1234, 3421);

        mMockClock.uptime = 2000;
        mCollector.forceSchedule();
        waitForIdle();

        assertThat(mCollectedStats.durationMs).isEqualTo(3421 - 1234);
        assertThat(mCollectedStats.stats).isEqualTo(new long[]{4444, 2222, 0, 1000, 0});
        assertThat(mCollectedStats.uidStats.get(42)).isEqualTo(new long[]{23, 34, 0});
        assertThat(mCollectedStats.uidStats.get(99)).isEqualTo(new long[]{45, 78, 0});
    }

    private void mockKernelCpuStats(long[] deviceStats, SparseArray<long[]> uidToCpuStats,
            long expectedLastUpdateTimestampMs, long newLastUpdateTimestampMs) {
        when(mMockKernelCpuStatsReader.nativeReadCpuStats(
                any(CpuPowerStatsCollector.KernelCpuStatsCallback.class),
                any(int[].class), anyLong(), any(long[].class), any(long[].class)))
                .thenAnswer(invocation -> {
                    CpuPowerStatsCollector.KernelCpuStatsCallback callback =
                            invocation.getArgument(0);
                    int[] powerBucketIndexes = invocation.getArgument(1);
                    long lastTimestamp = invocation.getArgument(2);
                    long[] cpuTimeByScalingStep = invocation.getArgument(3);
                    long[] tempStats = invocation.getArgument(4);

                    assertThat(powerBucketIndexes).isEqualTo(new int[]{0, 1, 1});
                    assertThat(lastTimestamp / 1000000L).isEqualTo(expectedLastUpdateTimestampMs);
                    assertThat(tempStats).hasLength(2);

                    System.arraycopy(deviceStats, 0, cpuTimeByScalingStep, 0,
                            cpuTimeByScalingStep.length);

                    for (int i = 0; i < uidToCpuStats.size(); i++) {
                        int uid = uidToCpuStats.keyAt(i);
                        long[] cpuStats = uidToCpuStats.valueAt(i);
                        System.arraycopy(cpuStats, 0, tempStats, 0, tempStats.length);
                        callback.processUidStats(uid, tempStats);
                    }
                    return newLastUpdateTimestampMs * 1000000L; // Nanoseconds
                });
    }

    private void waitForIdle() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
