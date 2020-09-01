/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static junit.framework.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runners.model.Statement;

import java.io.IOException;

@Presubmit
public class SystemServicesTestRuleTest {
    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Test
    public void testRule_rethrows_unchecked_exceptions() throws Throwable {
        final SystemServicesTestRule mWmsRule = new SystemServicesTestRule();
        Statement statement = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                throw new RuntimeException("A failing test!");
            }
        };
        mExpectedException.expect(RuntimeException.class);
        mWmsRule.apply(statement, null /* Description*/).evaluate();
    }

    @Test
    public void testRule_rethrows_checked_exceptions() throws Throwable {
        final SystemServicesTestRule mWmsRule = new SystemServicesTestRule();
        Statement statement = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                throw new IOException("A failing test!");
            }
        };
        mExpectedException.expect(IOException.class);
        mWmsRule.apply(statement, null /* Description*/).evaluate();
    }

    @Test
    public void testRule_ranSuccessfully() throws Throwable {
        final boolean[] testRan = {false};
        final SystemServicesTestRule mWmsRule = new SystemServicesTestRule();
        Statement statement = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                testRan[0] = true;
            }
        };
        mWmsRule.apply(statement, null /* Description*/).evaluate();
        assertTrue(testRan[0]);
    }
}
