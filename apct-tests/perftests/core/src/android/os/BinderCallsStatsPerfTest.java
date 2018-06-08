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
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderCallsStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNull;


/**
 * Performance tests for {@link BinderCallsStats}
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BinderCallsStatsPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private BinderCallsStats mBinderCallsStats;

    @Before
    public void setUp() {
        mBinderCallsStats = new BinderCallsStats(true);
    }

    @After
    public void tearDown() {
    }

    @Test
    public void timeCallSession() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Binder b = new Binder();
        int i = 0;
        while (state.keepRunning()) {
            BinderCallsStats.CallSession s = mBinderCallsStats.callStarted(b, i % 100);
            mBinderCallsStats.callEnded(s);
            i++;
        }
    }

    @Test
    public void timeCallSessionTrackingDisabled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Binder b = new Binder();
        mBinderCallsStats = new BinderCallsStats(false);
        while (state.keepRunning()) {
            BinderCallsStats.CallSession s = mBinderCallsStats.callStarted(b, 0);
            mBinderCallsStats.callEnded(s);
        }
    }

}
