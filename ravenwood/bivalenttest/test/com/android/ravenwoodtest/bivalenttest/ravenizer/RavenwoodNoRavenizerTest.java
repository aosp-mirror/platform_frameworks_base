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

import android.platform.test.annotations.NoRavenizer;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.RavenwoodTestRunnerInitializing;

import org.junit.Test;

/**
 * Test for {@link android.platform.test.annotations.NoRavenizer}
 */
@NoRavenizer
public class RavenwoodNoRavenizerTest {
    public static final String TAG = "RavenwoodNoRavenizerTest";

    private static final CallTracker sCallTracker = new CallTracker();

    /**
     * With @NoRavenizer, this method shouldn't be called.
     */
    @RavenwoodTestRunnerInitializing
    public static void ravenwoodRunnerInitializing() {
        sCallTracker.incrementMethodCallCount();
    }

    /**
     * Make sure ravenwoodRunnerInitializing() wasn't called.
     */
    @Test
    public void testNotRavenized() {
        sCallTracker.assertCalls(
                "ravenwoodRunnerInitializing", 0
        );
    }
}
