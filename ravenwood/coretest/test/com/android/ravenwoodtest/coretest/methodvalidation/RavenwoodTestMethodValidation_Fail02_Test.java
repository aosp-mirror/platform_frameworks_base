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
package com.android.ravenwoodtest.coretest.methodvalidation;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

/**
 * RavenwoodRule has a validator to ensure "test-looking" methods have valid JUnit annotations.
 * This class contains tests for this validator.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodTestMethodValidation_Fail02_Test {
    private ExpectedException mThrown = ExpectedException.none();
    private final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Rule
    public final RuleChain chain = RuleChain.outerRule(mThrown).around(mRavenwood);

    public RavenwoodTestMethodValidation_Fail02_Test() {
        mThrown.expectMessage("Method tearDown() doesn't have @After");
    }

    @SuppressWarnings("JUnit4TearDownNotRun")
    public void tearDown() {
    }

    @Test
    public void testEmpty() {
    }
}
