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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Make sure ravenwood's test runner works with {@link ParameterizedAndroidJunit4}.
 */
@RunWith(ParameterizedAndroidJunit4.class)
public class RavenwoodRunnerWithParameterizedAndroidJunit4Test {
    public static final String TAG = "RavenwoodRunnerTest";

    private static final CallTracker sCallTracker = new CallTracker();

    private final String mParam;

    private static int sNumInsantiation = 0;

    public RavenwoodRunnerWithParameterizedAndroidJunit4Test(String param) {
        mParam = param;
        sNumInsantiation++;
    }

    @BeforeClass
    public static void beforeClass() {
        // It seems like ParameterizedAndroidJunit4 calls the @BeforeTest / @AfterTest methods
        // one time too many.
        // With two parameters, this method should be called only twice, but it's actually
        // called three times.
        // So let's not check the number fo beforeClass calls.
    }

    @Before
    public void beforeTest() {
        sCallTracker.incrementMethodCallCount();
    }

    @After
    public void afterTest() {
        sCallTracker.incrementMethodCallCount();
    }

    @Parameters
    public static List<String> getParams() {
        var params =  new ArrayList<String>();
        params.add("foo");
        params.add("bar");
        return params;
    }

    @Test
    public void testWithParams() {
        sCallTracker.incrementMethodCallCount();
    }

    @AfterClass
    public static void afterClass() {
        Log.i(TAG, "afterClass called");

        sCallTracker.assertCallsOrDie(
                "beforeTest", sNumInsantiation,
                "afterTest", sNumInsantiation,
                "testWithParams", sNumInsantiation
        );
    }
}
