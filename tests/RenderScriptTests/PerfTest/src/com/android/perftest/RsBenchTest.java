/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.perftest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.TouchUtils;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

/**
 * To run the test, please use command
 *
 * adb shell am instrument -w com.android.perftest/.RsPerfTestRunner
 *
 */
public class RsBenchTest extends ActivityInstrumentationTestCase2<RsBench> {
    private String TAG = "RsBenchTest";
    private int iterations = 0;
    private RsBench mAct;

    public RsBenchTest() {
        super(RsBench.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        Instrumentation mInst = getInstrumentation();
        RsPerfTestRunner mRunner = (RsPerfTestRunner) getInstrumentation();
        iterations = mRunner.iterations;
        Log.v(TAG, "Run benchmark for " + iterations + " iterations.");

        Uri data = Uri.fromParts("iterations", Integer.toString(iterations), null);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName("com.android.perftest", "com.android.perftest.RsBench");
        intent.setData(data);
        mAct = (RsBench) mInst.startActivitySync(intent);
        mInst.waitForIdleSync();

    }

    @Override
    public void tearDown() throws Exception {
        mAct.finish();
        super.tearDown();
    }

    /**
     * Run tests and wait until the test has been run for iterations.
     */
    @LargeTest
    public void testRsBench() {
        if (mAct.mView.testIsFinished()) {
            return;
        } else {
            fail("test didn't stop correctly");
        }
    }
}
