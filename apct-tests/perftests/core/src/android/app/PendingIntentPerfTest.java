/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.app;

import android.content.Context;
import android.content.Intent;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

// Due to b/71353150, you might get "java.lang.AssertionError: Binder ProxyMap has too many
// entries", but it's flaky. Adding "Runtime.getRuntime().gc()" between each iteration solves
// the problem, but it doesn't seem like it's currently needed.
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PendingIntentPerfTest {

    private Context mContext;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Intent mIntent;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mIntent = PerfTestActivity.createLaunchIntent(mContext);
    }

    /**
     * Benchmark time to create a PendingIntent.
     */
    @Test
    public void create() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            state.resumeTiming();

            final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, mIntent,
                    PendingIntent.FLAG_MUTABLE_UNAUDITED);

            state.pauseTiming();
            pendingIntent.cancel();
            state.resumeTiming();
        }
    }

    /**
     * Benchmark time to create a PendingIntent with FLAG_CANCEL_CURRENT, already having an active
     * PendingIntent.
     */
    @Test
    public void createWithCancelFlag() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PendingIntent previousPendingIntent = PendingIntent.getActivity(mContext, 0,
                    mIntent, PendingIntent.FLAG_MUTABLE_UNAUDITED);
            state.resumeTiming();

            final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, mIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);

            state.pauseTiming();
            pendingIntent.cancel();
            state.resumeTiming();
        }
    }

    /**
     * Benchmark time to create a PendingIntent with FLAG_UPDATE_CURRENT, already having an active
     * PendingIntent.
     */
    @Test
    public void createWithUpdateFlag() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PendingIntent previousPendingIntent = PendingIntent.getActivity(mContext, 0,
                    mIntent, PendingIntent.FLAG_MUTABLE_UNAUDITED);
            state.resumeTiming();

            final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, mIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);

            state.pauseTiming();
            previousPendingIntent.cancel();
            pendingIntent.cancel();
            state.resumeTiming();
        }
    }

    /**
     * Benchmark time to cancel a PendingIntent.
     */
    @Test
    public void cancel() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0,
                    mIntent, PendingIntent.FLAG_MUTABLE_UNAUDITED);
            state.resumeTiming();

            pendingIntent.cancel();
        }
    }
}

