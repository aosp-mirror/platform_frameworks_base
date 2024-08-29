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

import static org.junit.Assert.fail;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.RavenwoodTestRunnerInitializing;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for "RAVENWOOD_RUN_DISABLED_TESTS" with "REALLY_DISABLED" set.
 *
 * This test is only executed on Ravenwood.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodRunDisabledTestsReallyDisabledTest {
    private static final String TAG = "RavenwoodRunDisabledTestsTest";

    private static final CallTracker sCallTracker = new CallTracker();

    @RavenwoodTestRunnerInitializing
    public static void ravenwoodRunnerInitializing() {
        RavenwoodRule.private$ravenwood().overrideRunDisabledTest(true,
                "\\#testReallyDisabled$");
    }

    /**
     * This test gets to run with RAVENWOOD_RUN_DISABLED_TESTS set.
     */
    @Test
    @DisabledOnRavenwood
    public void testDisabledTestGetsToRun() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        sCallTracker.incrementMethodCallCount();

        fail("This test won't pass on Ravenwood.");
    }

    /**
     * This will still not be executed due to the "really disabled" pattern.
     */
    @Test
    @DisabledOnRavenwood
    public void testReallyDisabled() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        sCallTracker.incrementMethodCallCount();

        fail("This test won't pass on Ravenwood.");
    }

    @AfterClass
    public static void afterClass() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        Log.i(TAG, "afterClass called");

        sCallTracker.assertCallsOrDie(
                "testDisabledTestGetsToRun", 1,
                "testReallyDisabled", 0
        );
    }
}
