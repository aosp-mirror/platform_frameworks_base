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
package com.android.ravenwoodtest.bivalenttest.listenertests;

import static android.platform.test.ravenwood.RavenwoodRule.isOnRavenwood;

import static org.junit.Assume.assumeTrue;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

/**
 * Test that fails in assumption from a class rule.
 *
 * This is only used for manual tests. Make sure `atest` shows 4 test results with
 * "ASSUMPTION_FAILED".
 *
 * TODO(b/364948126) Improve the tests and automate it.
 */
@RunWith(ParameterizedAndroidJunit4.class)
public class RavenwoodClassRuleAssumptionFailureTest {
    public static final String TAG = "RavenwoodClassRuleFailureTest";

    @ClassRule
    public static final TestRule sClassRule = new TestRule() {
        @Override
        public Statement apply(Statement base, Description description) {
            if (!isOnRavenwood()) {
                return base; // Just run the test as-is on a real device.
            }

            assumeTrue(false);
            return null; // unreachable
        }
    };

    public RavenwoodClassRuleAssumptionFailureTest(String param) {
    }

    @Parameters
    public static List<String> getParams() {
        var params =  new ArrayList<String>();
        params.add("foo");
        params.add("bar");
        return params;
    }

    @Test
    public void test1() {
    }

    @Test
    public void test2() {
    }
}
