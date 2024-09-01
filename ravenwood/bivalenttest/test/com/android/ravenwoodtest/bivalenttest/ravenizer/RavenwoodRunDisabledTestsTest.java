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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Test for "RAVENWOOD_RUN_DISABLED_TESTS". (with no "REALLY_DISABLED" set.)
 *
 * This test is only executed on Ravenwood.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodRunDisabledTestsTest {
    private static final String TAG = "RavenwoodRunDisabledTestsTest";

    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    private static final CallTracker sCallTracker = new CallTracker();

    @RavenwoodTestRunnerInitializing
    public static void ravenwoodRunnerInitializing() {
        RavenwoodRule.private$ravenwood().overrideRunDisabledTest(true, null);
    }

    @Test
    @DisabledOnRavenwood
    public void testDisabledTestGetsToRun() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        sCallTracker.incrementMethodCallCount();

        fail("This test won't pass on Ravenwood.");
    }

    @Test
    @DisabledOnRavenwood
    public void testDisabledButPass() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        sCallTracker.incrementMethodCallCount();

        // When a @DisabledOnRavenwood actually passed, the runner should make fail().
        mExpectedException.expectMessage("it actually passed under Ravenwood");
    }

    @AfterClass
    public static void afterClass() {
        if (!RavenwoodRule.isOnRavenwood()) {
            return;
        }
        Log.i(TAG, "afterClass called");

        sCallTracker.assertCallsOrDie(
                "testDisabledTestGetsToRun", 1,
                "testDisabledButPass", 1
        );
    }
}
