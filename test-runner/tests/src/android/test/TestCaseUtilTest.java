/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.List;

public class TestCaseUtilTest extends TestCase {

    public void testGetTestCaseNamesForTestSuiteWithSuiteMethod() throws Exception {
        TestSuite testSuite = new TwoTestsInTestSuite();

        List<String> testCaseNames = TestCaseUtil.getTestCaseNames(testSuite, false);

        assertEquals(2, testCaseNames.size());
        assertTrue(testCaseNames.get(0).endsWith("OneTestTestCase"));
        assertTrue(testCaseNames.get(1).endsWith("OneTestTestSuite"));
    }
    
    public void testGetTestCaseNamesForTestCaseWithSuiteMethod() throws Exception {
        TestCase testCase = new OneTestTestCaseWithSuite();

        List<String> testCaseNames = TestCaseUtil.getTestCaseNames(testCase, false);

        assertEquals(1, testCaseNames.size());
        assertTrue(testCaseNames.get(0).endsWith("testOne"));
    }

    public void testCreateTestForTestCase() throws Exception {
        Test test = TestCaseUtil.createTestSuite(OneTestTestCase.class);
        assertEquals(1, test.countTestCases());
    }
    
    public void testCreateTestForTestSuiteWithSuiteMethod() throws Exception {
        Test test = TestCaseUtil.createTestSuite(TwoTestsInTestSuite.class);
        assertEquals(2, test.countTestCases());
    }

    public void testCreateTestForTestCaseWithSuiteMethod() throws Exception {
        Test test = TestCaseUtil.createTestSuite(OneTestTestCaseWithSuite.class);
        assertEquals(1, test.countTestCases());
    }
    
    public void testReturnEmptyStringForTestSuiteWithNoName() throws Exception {
        assertEquals("", TestCaseUtil.getTestName(new TestSuite()));
    }

    public static class OneTestTestCase extends TestCase {
        public void testOne() throws Exception {
        }
    }

    public static class OneTestTestCaseWithSuite extends TestCase {
        public static Test suite()  {
            TestCase testCase = new OneTestTestCase();
            testCase.setName("testOne");
            return testCase;
        }

        public void testOne() throws Exception {
        }

        public void testTwo() throws Exception {
        }
    }

    public static class OneTestTestSuite {
        public static Test suite() {
            TestSuite suite = new TestSuite(OneTestTestSuite.class.getName());
            suite.addTestSuite(OneTestTestCase.class);
            return suite;
        }
    }

    public static class TwoTestsInTestSuite extends TestSuite {
        public static Test suite() {
            TestSuite suite = new TestSuite(TwoTestsInTestSuite.class.getName());
            suite.addTestSuite(OneTestTestCase.class);
            suite.addTest(OneTestTestSuite.suite());
            return suite;
        }
    }
}
