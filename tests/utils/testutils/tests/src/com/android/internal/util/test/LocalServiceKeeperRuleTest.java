/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.util.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

@SmallTest
public class LocalServiceKeeperRuleTest {

    private final Description mDescription = Description.createSuiteDescription("Description");

    LocalServiceKeeperRule mRule = new LocalServiceKeeperRule();

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(TestService.class);
    }

    @Test
    public void testFailedIfCalledOutsideOfTheRule() {
        TestService service = new TestService() {};

        assertThrows(IllegalStateException.class,
                () -> mRule.overrideLocalService(TestService.class, service));
    }

    @Test
    public void testSetsLocalServiceIfNotPresent() throws Throwable {
        LocalServices.removeServiceForTest(TestService.class);
        TestService service = new TestService() {};

        runInRuleApply(() -> {
            mRule.overrideLocalService(TestService.class, service);
            assertEquals(service, LocalServices.getService(TestService.class));
        });
    }

    @Test
    public void testOverridesLocalServiceIfPresent() throws Throwable {
        TestService service = new TestService() {};
        LocalServices.addService(TestService.class, service);
        TestService overriddenService = new TestService() {};

        runInRuleApply(() -> {
            mRule.overrideLocalService(TestService.class, overriddenService);
            assertEquals(overriddenService, LocalServices.getService(TestService.class));
        });
    }

    @Test
    public void testDoesNotAllowToOverrideSameServiceTwice() throws Throwable {
        TestService service = new TestService() {};

        runInRuleApply(() -> {
            mRule.overrideLocalService(TestService.class, service);
            assertThrows(IllegalArgumentException.class,
                    () -> mRule.overrideLocalService(TestService.class, service));
        });
    }

    @Test
    public void testRestroresLocalServiceAfterTestIfPresent() throws Throwable {
        TestService expectedService = new TestService() {};
        LocalServices.addService(TestService.class, expectedService);
        TestService overriddenService = new TestService() {};

        runInRuleApply(() -> mRule.overrideLocalService(TestService.class, overriddenService));

        assertEquals(expectedService, LocalServices.getService(TestService.class));
    }

    @Test
    public void testRemovesLocalServiceAfterTestIfNotPresent() throws Throwable {
        LocalServices.removeServiceForTest(TestService.class);
        TestService service = new TestService() {};

        runInRuleApply(() -> mRule.overrideLocalService(TestService.class, service));

        assertNull(LocalServices.getService(TestService.class));
    }

    private void runInRuleApply(Runnable runnable) throws Throwable {
        Statement testStatement = new Statement() {
            @Override
            public void evaluate() {
                runnable.run();
            }
        };
        mRule.apply(testStatement, mDescription).evaluate();
    }

    interface TestService {
    }
}
