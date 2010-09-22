/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.test.suitebuilder;

import java.util.List;

import junit.framework.TestCase;

/**
 * Unit tests for {@link TestGrouping}
 */
public class TestGroupingTest extends TestCase {

    private TestGrouping mGrouping;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mGrouping = new TestGrouping(TestGrouping.SORT_BY_SIMPLE_NAME);
    }

    /**
     * Verifies that TestCases with no public constructor are not loaded.
     * Relies on fixture classes in android.test.suitebuilder.examples.constructor
     */
    public void testGetTests_noPublicConstructor() {
        mGrouping.addPackagesRecursive("android.test.suitebuilder.examples.constructor");
        List<TestMethod> tests = mGrouping.getTests();
        // only the PublicConstructorTest's test method should be present
        assertEquals(1, tests.size());
        assertEquals("testPublicConstructor", tests.get(0).getName());
    }
}
