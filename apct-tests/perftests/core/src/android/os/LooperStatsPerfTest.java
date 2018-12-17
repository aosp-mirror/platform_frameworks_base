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
 * limitations under the License.
 */

package android.os;


import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.CachedDeviceState;
import com.android.internal.os.LooperStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Performance tests for {@link LooperStats}.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LooperStatsPerfTest {
    private static final int DISTINCT_MESSAGE_COUNT = 1000;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private LooperStats mStats;
    private CachedDeviceState mDeviceState;
    private HandlerThread mThread;
    private Message[] mMessages = new Message[DISTINCT_MESSAGE_COUNT];

    @Before
    public void setUp() {
        mStats = new LooperStats(1, DISTINCT_MESSAGE_COUNT - 1);
        mDeviceState = new CachedDeviceState(false, false);
        mStats.setDeviceState(mDeviceState.getReadonlyClient());
        // The tests are all single-threaded. HandlerThread is created to allow creating Handlers.
        mThread = new HandlerThread("UnusedThread");
        mThread.start();
        for (int i = 0; i < DISTINCT_MESSAGE_COUNT; i++) {
            mMessages[i] = mThread.getThreadHandler().obtainMessage(i);
        }
    }

    @After
    public void tearDown() {
        mThread.quit();
    }

    @Test
    public void timeHundredPercentSampling() {
        mStats.setSamplingInterval(1);
        runScenario();
    }

    @Test
    public void timeOnePercentSampling() {
        mStats.setSamplingInterval(100);
        runScenario();
    }

    @Test
    public void timeCollectionDisabled() {
        // We do not collect data on charger.
        mDeviceState.setCharging(true);
        runScenario();
    }

    private void runScenario() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < DISTINCT_MESSAGE_COUNT; i++) {
                Object token = mStats.messageDispatchStarting();
                mStats.messageDispatched(token, mMessages[i]);
            }
        }
    }
}
