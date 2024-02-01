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
package com.android.platform.test.ravenwood.coretest;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

/**
 * Test for the test runner validator in RavenwoodRule.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodTestRunnerValidationTest {
    // Note the following rules don't have a @Rule, because they need to be applied in a specific
    // order. So we use a RuleChain instead.
    private ExpectedException mThrown = ExpectedException.none();
    private final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Rule
    public final RuleChain chain = RuleChain.outerRule(mThrown).around(mRavenwood);

    public RavenwoodTestRunnerValidationTest() {
        Assume.assumeTrue(mRavenwood._ravenwood_private$isOptionalValidationEnabled());
        // Because RavenwoodRule will throw this error before executing the test method,
        // we can't do it in the test method itself.
        // So instead, we initialize it here.
        mThrown.expectMessage("Switch to androidx.test.ext.junit.runners.AndroidJUnit4");
    }

    @Test
    public void testValidateTestRunner() {
    }
}
