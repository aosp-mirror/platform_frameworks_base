/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.frameworks.perftests.am.tests;


import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.HandlerThread;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.TraceMarkParser;
import android.perftests.utils.TraceMarkParser.TraceMarkLine;
import android.perftests.utils.TraceMarkParser.TraceMarkSlice;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.perftests.am.util.AtraceUtils;
import com.android.frameworks.perftests.am.util.TargetPackageUtils;
import com.android.frameworks.perftests.am.util.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * This benchmark test basically manipulates 3 test packages, let them bind to
 * each other, send broadcast to each other, etc. All of these actions essentially
 * triggers OomAdjuster to update the oom_adj scores and proc state of them.
 * In the meanwhile it'll also monitor the atrace output, extract duration between
 * the start and exit entries of the updateOomAdjLocked, include each of them
 * into the stats; when there are enough samples in the stats, the test will
 * stop and output the mean/stddev time spent on the updateOomAdjLocked.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class OomAdjPerfTest extends BasePerfTest {
    private static final String TAG = "OomAdjPerfTest";
    private static final boolean VERBOSE = true;

    private static final String STUB_PACKAGE1_NAME = "com.android.stubs.am1";
    private static final String STUB_PACKAGE2_NAME = "com.android.stubs.am2";
    private static final String STUB_PACKAGE3_NAME = "com.android.stubs.am3";

    private static final Uri STUB_PACKAGE1_URI = new Uri.Builder().scheme(
            ContentResolver.SCHEME_CONTENT).authority("com.android.stubs.am1.testapp").build();
    private static final Uri STUB_PACKAGE2_URI = new Uri.Builder().scheme(
            ContentResolver.SCHEME_CONTENT).authority("com.android.stubs.am2.testapp").build();
    private static final Uri STUB_PACKAGE3_URI = new Uri.Builder().scheme(
            ContentResolver.SCHEME_CONTENT).authority("com.android.stubs.am3.testapp").build();
    private static final long NANOS_PER_MICROSECOND = 1000L;

    private static final String ATRACE_CATEGORY = "am";
    private static final String ATRACE_OOMADJ_PREFIX = "updateOomAdj_";

    private TraceMarkParser mTraceMarkParser = new TraceMarkParser(this::shouldFilterTraceLine);
    private final ArrayList<Long> mDurations = new ArrayList<Long>();
    private Context mContext;
    private HandlerThread mHandlerThread;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mHandlerThread = new HandlerThread("command receiver");
        mHandlerThread.start();
        TargetPackageUtils.initCommandResultReceiver(mHandlerThread.getLooper());

        Utils.runShellCommand("cmd deviceidle whitelist +" + STUB_PACKAGE1_NAME);
        Utils.runShellCommand("cmd deviceidle whitelist +" + STUB_PACKAGE2_NAME);
        Utils.runShellCommand("cmd deviceidle whitelist +" + STUB_PACKAGE3_NAME);
        TargetPackageUtils.startStubPackage(mContext, STUB_PACKAGE1_NAME);
        TargetPackageUtils.startStubPackage(mContext, STUB_PACKAGE2_NAME);
        TargetPackageUtils.startStubPackage(mContext, STUB_PACKAGE3_NAME);
    }

    @After
    public void tearDown() {
        TargetPackageUtils.stopStubPackage(mContext, STUB_PACKAGE1_NAME);
        TargetPackageUtils.stopStubPackage(mContext, STUB_PACKAGE2_NAME);
        TargetPackageUtils.stopStubPackage(mContext, STUB_PACKAGE3_NAME);
        Utils.runShellCommand("cmd deviceidle whitelist -" + STUB_PACKAGE1_NAME);
        Utils.runShellCommand("cmd deviceidle whitelist -" + STUB_PACKAGE2_NAME);
        Utils.runShellCommand("cmd deviceidle whitelist -" + STUB_PACKAGE3_NAME);
        mHandlerThread.quitSafely();
    }

    @Test
    public void testOomAdj() {
        final AtraceUtils atraceUtils = AtraceUtils.getInstance(
                InstrumentationRegistry.getInstrumentation());
        final ManualBenchmarkState state = mPerfManualStatusReporter.getBenchmarkState();
        atraceUtils.startTrace(ATRACE_CATEGORY);
        while (state.keepRunning(mDurations)) {
            runCUJWithOomComputationOnce();

            // Now kick off the trace dump
            mDurations.clear();
            atraceUtils.performDump(mTraceMarkParser, this::handleTraceMarkSlices);
        }
        atraceUtils.stopTrace();
    }

    private boolean shouldFilterTraceLine(final TraceMarkLine line) {
        return line.name.startsWith(ATRACE_OOMADJ_PREFIX);
    }

    private void handleTraceMarkSlices(String key, List<TraceMarkSlice> slices) {
        for (TraceMarkSlice slice: slices) {
            mDurations.add(slice.getDurationInMicroseconds() * NANOS_PER_MICROSECOND);
        }
    }

    /**
     * This tries to mimic a user journey, involes multiple activity/service starts/stop,
     * the time spent on oom adj computation would be different between all these samples,
     * but with enough samples, we'll be able to know the overall distribution of the time
     * spent on it.
     */
    private void runCUJWithOomComputationOnce() {
        // Start activity from package1
        TargetPackageUtils.startActivity(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);
        // Start activity from package2
        TargetPackageUtils.startActivity(STUB_PACKAGE2_NAME, STUB_PACKAGE2_NAME);
        // Start activity from package3
        TargetPackageUtils.startActivity(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);

        // Stop activity in package1
        TargetPackageUtils.stopActivity(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);
        // Stop activity in package2
        TargetPackageUtils.stopActivity(STUB_PACKAGE2_NAME, STUB_PACKAGE2_NAME);
        // Stop activity in package3
        TargetPackageUtils.stopActivity(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);

        // Bind from package1 to package2
        TargetPackageUtils.bindService(STUB_PACKAGE1_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE);
        // Acquire content provider from package 1 to package3
        TargetPackageUtils.acquireProvider(STUB_PACKAGE1_NAME, STUB_PACKAGE3_NAME,
                STUB_PACKAGE3_URI);
        // Start activity from package1
        TargetPackageUtils.startActivity(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);
        // Bind from package2 to package3
        TargetPackageUtils.bindService(STUB_PACKAGE2_NAME, STUB_PACKAGE3_NAME,
                Context.BIND_AUTO_CREATE);
        // Unbind from package 1 to package 2
        TargetPackageUtils.unbindService(STUB_PACKAGE1_NAME, STUB_PACKAGE2_NAME, 0);
        // Stop activity in package1
        TargetPackageUtils.stopActivity(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);

        // Send broadcast to all of them
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE2_NAME, STUB_PACKAGE2_NAME);
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);

        // Bind from package1 to package2 again
        TargetPackageUtils.bindService(STUB_PACKAGE1_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE);
        // Create a cycle: package3 to package1
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE1_NAME,
                Context.BIND_AUTO_CREATE);

        // Send broadcast to all of them again
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE1_NAME, STUB_PACKAGE1_NAME);
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE2_NAME, STUB_PACKAGE2_NAME);
        TargetPackageUtils.sendBroadcast(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);
        // Start activity in package3
        TargetPackageUtils.startActivity(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);

        // Break the cycle: unbind from package3 to package1
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE1_NAME, 0);

        // Bind from package3 to package1 with waive priority
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE1_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY);
        // Release provider connection
        TargetPackageUtils.releaseProvider(STUB_PACKAGE1_NAME, STUB_PACKAGE3_NAME,
                STUB_PACKAGE3_URI);
        // Unbind from package1 to package2
        TargetPackageUtils.unbindService(STUB_PACKAGE1_NAME, STUB_PACKAGE2_NAME, 0);
        // Unbind from package2 to packagae3
        TargetPackageUtils.unbindService(STUB_PACKAGE2_NAME, STUB_PACKAGE3_NAME, 0);

        // Bind from package3 to package2 with BIND_ABOVE_CLIENT
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_ABOVE_CLIENT);
        // Unbind from package3 to packagae2
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME, 0);

        // Bind from package3 to package2 with BIND_ALLOW_OOM_MANAGEMENT
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_OOM_MANAGEMENT);
        // Unbind from package3 to packagae2
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME, 0);

        // Bind from package3 to package2 with BIND_IMPORTANT
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        // Unbind from package3 to packagae2
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME, 0);

        // Bind from package3 to package2 with BIND_NOT_FOREGROUND
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);
        // Unbind from package3 to packagae2
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME, 0);

        // Bind from package3 to package2 with BIND_NOT_PERCEPTIBLE
        TargetPackageUtils.bindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_PERCEPTIBLE);
        // Unbind from package3 to packagae2
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE2_NAME, 0);

        // Stop activity in package3
        TargetPackageUtils.stopActivity(STUB_PACKAGE3_NAME, STUB_PACKAGE3_NAME);
        // Unbind from package3 to package1
        TargetPackageUtils.unbindService(STUB_PACKAGE3_NAME, STUB_PACKAGE1_NAME, 0);
    }
}
