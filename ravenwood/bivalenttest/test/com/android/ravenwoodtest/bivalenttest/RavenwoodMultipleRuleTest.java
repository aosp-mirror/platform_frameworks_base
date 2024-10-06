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
package com.android.ravenwoodtest.bivalenttest;

import android.platform.test.ravenwood.RavenwoodConfig;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Make sure having multiple RavenwoodRule's is detected.
 * (But only when running on ravenwod. Otherwise it'll be ignored.)
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodMultipleRuleTest {

    @Rule(order = Integer.MIN_VALUE)
    public final ExpectedException mExpectedException = ExpectedException.none();

    @Rule
    public final RavenwoodRule mRavenwood1 = new RavenwoodRule();

    @Rule
    public final RavenwoodRule mRavenwood2 = new RavenwoodRule();

    public RavenwoodMultipleRuleTest() {
        // We can't call it within the test method because the exception happens before
        // calling the method, so set it up here.
        if (RavenwoodConfig.isOnRavenwood()) {
            mExpectedException.expectMessage("Multiple nesting RavenwoodRule");
        }
    }

    @Test
    public void testMultipleRulesNotAllowed() {
        Assume.assumeTrue(RavenwoodConfig.isOnRavenwood());
    }
}
