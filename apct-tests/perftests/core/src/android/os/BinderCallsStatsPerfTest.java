/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.os;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.os.CachedDeviceState;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Performance tests for {@link BinderCallsStats}
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BinderCallsStatsPerfTest {
    private static final int DEFAULT_BUCKET_SIZE = 1000;
    private static final int WORKSOURCE_UID = 1;
    static class FakeCpuTimeBinderCallsStats extends BinderCallsStats {
        private int mTimeMs;

        FakeCpuTimeBinderCallsStats() {
            super(new BinderCallsStats.Injector());
            setDeviceState(new CachedDeviceState(false, false).getReadonlyClient());
        }

        protected long getThreadTimeMicro() {
            return mTimeMs++;
        }

        protected long getElapsedRealtimeMicro() {
            return mTimeMs++;
        }
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private BinderCallsStats mBinderCallsStats;

    @Before
    public void setUp() {
        mBinderCallsStats = new BinderCallsStats(new BinderCallsStats.Injector());
        CachedDeviceState deviceState = new CachedDeviceState(false, false);
        mBinderCallsStats.setDeviceState(deviceState.getReadonlyClient());
    }

    @After
    public void tearDown() {
    }

    @Test
    public void timeCallSession() {
        mBinderCallsStats.setDetailedTracking(true);
        runScenario(DEFAULT_BUCKET_SIZE);
    }

    @Test
    public void timeCallSessionOnePercentSampling() {
        mBinderCallsStats.setDetailedTracking(false);
        mBinderCallsStats.setSamplingInterval(100);
        runScenario(DEFAULT_BUCKET_SIZE);
    }

    @Test
    public void timeCallSessionTrackingDisabled() {
        mBinderCallsStats.setDetailedTracking(false);
        runScenario(DEFAULT_BUCKET_SIZE);
    }

    @Test
    public void timeCallSession_1000_buckets_cpuNotRecorded() {
        mBinderCallsStats = new FakeCpuTimeBinderCallsStats();
        mBinderCallsStats.setSamplingInterval(1);
        runScenario(/* max bucket size */ 1000);
    }

    @Test
    public void timeCallSession_500_buckets_cpuNotRecorded() {
        mBinderCallsStats = new FakeCpuTimeBinderCallsStats();
        mBinderCallsStats.setSamplingInterval(1);
        runScenario(/* max bucket size */ 500);
    }

    @Test
    public void timeCallSession_100_buckets_cpuNotRecorded() {
        mBinderCallsStats = new FakeCpuTimeBinderCallsStats();
        mBinderCallsStats.setSamplingInterval(1);
        runScenario(/* max bucket size */ 100);
    }

    // There will be a warmup time of maxBucketSize to initialize the map of CallStat.
    private void runScenario(int maxBucketSize) {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Binder b = new Binder();
        while (state.keepRunning()) {
            for (int i = 0; i < 10000; i++) {
                CallSession s = mBinderCallsStats.callStarted(b, i % maxBucketSize, WORKSOURCE_UID);
                mBinderCallsStats.callEnded(s, 0, 0, WORKSOURCE_UID);
            }
        }
    }
}
