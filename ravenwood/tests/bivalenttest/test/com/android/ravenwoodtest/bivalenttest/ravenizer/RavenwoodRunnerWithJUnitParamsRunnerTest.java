/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalenttest.ravenizer;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Make sure ravenwood's test runner works with {@link AndroidJUnit4}.
 */
@RunWith(JUnitParamsRunner.class)
public class RavenwoodRunnerWithJUnitParamsRunnerTest  {
    public static final String TAG = "RavenwoodRunnerTest";

    private static final CallTracker sCallTracker = new CallTracker();

    @BeforeClass
    public static void beforeClass() {
        sCallTracker.incrementMethodCallCount();
    }

    @Before
    public void beforeTest() {
        sCallTracker.incrementMethodCallCount();
    }

    @After
    public void afterTest() {
        sCallTracker.incrementMethodCallCount();
    }

    @Test
    public void testWithNoParams() {
        sCallTracker.incrementMethodCallCount();
    }

    @Test
    @Parameters({"foo", "bar"})
    public void testWithParams(String arg) {
        sCallTracker.incrementMethodCallCount();
    }

    @AfterClass
    public static void afterClass() {
        Log.i(TAG, "afterClass called");

        sCallTracker.assertCallsOrDie(
                "beforeClass", 1,
                "beforeTest", 3,
                "afterTest", 3,
                "testWithNoParams", 1,
                "testWithParams", 2
        );
    }
}
