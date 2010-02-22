/*
 * Copyright (C) 2008 The Android Open Source Project
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

import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.List;

public class SmokeTestSuiteBuilderTest extends TestCase {

    public void testShouldOnlyIncludeSmokeTests() throws Exception {
        TestSuite testSuite = new SmokeTestSuiteBuilder(getClass())
                .includeAllPackagesUnderHere().build();

        List<String> testCaseNames = ListTestCaseNames.getTestCaseNames(testSuite);
        assertEquals("Unexpected number of smoke tests.", 1, testCaseNames.size());
        assertEquals("Unexpected test name", "testSmoke", testCaseNames.get(0));
    }
}
