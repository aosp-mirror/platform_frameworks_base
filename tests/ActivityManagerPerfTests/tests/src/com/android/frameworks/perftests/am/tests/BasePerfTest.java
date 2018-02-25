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

package com.android.frameworks.perftests.am.tests;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.PerfManualStatusReporter;
import android.support.test.InstrumentationRegistry;

import com.android.frameworks.perftests.am.util.TargetPackageUtils;
import com.android.frameworks.perftests.am.util.TimeReceiver;

import org.junit.Before;
import org.junit.Rule;

import java.util.function.LongSupplier;

public class BasePerfTest {
    private static final String TAG = BasePerfTest.class.getSimpleName();

    private TimeReceiver mTimeReceiver;
    private ServiceConnection mAliveServiceConnection;

    @Rule
    public PerfManualStatusReporter mPerfManualStatusReporter = new PerfManualStatusReporter();

    protected Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mTimeReceiver = new TimeReceiver();
    }

    protected void addReceivedTimeNs(String type) {
        mTimeReceiver.addTimeForTypeToQueue(type, System.nanoTime());
    }

    protected Intent createServiceIntent() {
        final Intent intent = new Intent();
        intent.setClassName(TargetPackageUtils.PACKAGE_NAME,
                TargetPackageUtils.SERVICE_NAME);
        putTimeReceiverBinderExtra(intent);
        return intent;
    }

    protected Intent createBroadcastIntent(String action) {
        final Intent intent = new Intent(action);
        intent.addFlags(
                Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        putTimeReceiverBinderExtra(intent);
        return intent;
    }

    protected void putTimeReceiverBinderExtra(Intent intent) {
        intent.putExtras(mTimeReceiver.createReceiveTimeExtraBinder());
    }

    private void setUpIteration() {
        mTimeReceiver.clear();
    }

    private void tearDownIteration() {
        TargetPackageUtils.killTargetPackage(mContext, mAliveServiceConnection);
    }

    protected void startTargetPackage() {
        mAliveServiceConnection = TargetPackageUtils.startTargetPackage(mContext);
    }

    protected long getReceivedTimeNs(String type) {
        return mTimeReceiver.getReceivedTimeNs(type);
    }

    protected void runPerfFunction(LongSupplier func) {
        final ManualBenchmarkState benchmarkState = mPerfManualStatusReporter.getBenchmarkState();
        long elapsedTimeNs = 0;
        while (benchmarkState.keepRunning(elapsedTimeNs)) {
            setUpIteration();
            elapsedTimeNs = func.getAsLong();
            tearDownIteration();
        }
    }
}
