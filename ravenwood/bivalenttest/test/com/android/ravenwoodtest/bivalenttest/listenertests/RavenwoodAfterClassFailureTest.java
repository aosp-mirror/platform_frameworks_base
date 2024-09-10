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

import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

/**
 * Test that throws from @AfterClass.
 *
 * Tradefed would ignore it, so instead RavenwoodAwareTestRunner would detect it and kill
 * the self (test) process.
 *
 * Unfortunately, this behavior can't easily be tested from within this class, so for now
 * it's only used for a manual test, which you can run by removing the @Ignore.
 *
 * TODO(b/364948126) Improve the tests and automate it.
 */
@Ignore
@RunWith(ParameterizedAndroidJunit4.class)
public class RavenwoodAfterClassFailureTest {
    public RavenwoodAfterClassFailureTest(String param) {
    }

    @AfterClass
    public static void afterClass() {
        if (!isOnRavenwood()) return; // Don't do anything on real device.

        throw new RuntimeException("FAILURE");
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
