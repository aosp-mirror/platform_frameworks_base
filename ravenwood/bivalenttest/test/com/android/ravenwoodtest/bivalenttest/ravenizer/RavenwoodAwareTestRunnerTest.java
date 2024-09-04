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

import static org.junit.Assert.assertFalse;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.RavenwoodTestRunnerInitializing;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Make sure RavenwoodAwareTestRunnerTest properly delegates to the original runner,
 * and also run the special annotated methods.
 */
@RunWith(JUnitParamsRunner.class)
public class RavenwoodAwareTestRunnerTest {
    public static final String TAG = "RavenwoodAwareTestRunnerTest";

    private static final CallTracker sCallTracker = new CallTracker();

    private static int getExpectedRavenwoodRunnerInitializingNumCalls() {
        return RavenwoodRule.isOnRavenwood() ? 1 : 0;
    }

    @RavenwoodTestRunnerInitializing
    public static void ravenwoodRunnerInitializing() {
        // No other calls should have been made.
        sCallTracker.assertCalls();

        sCallTracker.incrementMethodCallCount();
    }

    @BeforeClass
    public static void beforeClass() {
        sCallTracker.assertCalls(
                "ravenwoodRunnerInitializing",
                getExpectedRavenwoodRunnerInitializingNumCalls()
        );
        sCallTracker.incrementMethodCallCount();
    }

    @Test
    public void test1() {
        sCallTracker.incrementMethodCallCount();
    }

    @Test
    @Parameters({"foo", "bar"})
    public void testWithParams(String arg) {
        sCallTracker.incrementMethodCallCount();
    }

    @Test
    @DisabledOnRavenwood
    public void testDeviceOnly() {
        assertFalse(RavenwoodRule.isOnRavenwood());
    }

    @AfterClass
    public static void afterClass() {
        Log.i(TAG, "afterClass called");

        sCallTracker.assertCallsOrDie(
                "ravenwoodRunnerInitializing",
                getExpectedRavenwoodRunnerInitializingNumCalls(),
                "beforeClass", 1,
                "test1", 1,
                "testWithParams", 2
        );
    }
}
