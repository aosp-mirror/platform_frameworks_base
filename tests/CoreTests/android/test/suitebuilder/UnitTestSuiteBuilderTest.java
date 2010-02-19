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

import android.test.suitebuilder.examples.instrumentation.InstrumentationTest;

import junit.framework.Assert;
import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;

import java.util.HashSet;
import java.util.Set;

public class UnitTestSuiteBuilderTest extends TestCase {

    private UnitTestSuiteBuilder unitTestSuiteBuilder;

    protected void setUp() throws Exception {
        super.setUp();
        unitTestSuiteBuilder = new UnitTestSuiteBuilder(getClass());
    }

    public void testShouldExcludeIntrumentationTests() throws Exception {
        unitTestSuiteBuilder.includePackages(packageFor(InstrumentationTest.class));

        TestSuite testSuite = unitTestSuiteBuilder.build();
        Assert.assertEquals(0, ListTestCaseNames.getTestCaseNames(testSuite).size());

        SuiteExecutionRecorder recorder = runSuite(unitTestSuiteBuilder);

        assertFalse(recorder.saw("InstrumentationTest.testInstrumentation"));
        assertTrue(recorder.testsSeen.isEmpty());
    }

    private static String packageFor(Class clazz) {
        String className = clazz.getName();
        return className.substring(0, className.lastIndexOf('.'));
    }

    private SuiteExecutionRecorder runSuite(TestSuiteBuilder builder) {
        TestSuite suite = builder.build();
        SuiteExecutionRecorder recorder = new SuiteExecutionRecorder();
        TestResult result = new TestResult();
        result.addListener(recorder);
        suite.run(result);
        return recorder;
    }

    private class SuiteExecutionRecorder implements TestListener {

        private Set<String> failures = new HashSet<String>();
        private Set<String> errors = new HashSet<String>();
        private Set<String> testsSeen = new HashSet<String>();

        public void addError(Test test, Throwable t) {
            errors.add(testName(test));
        }

        public void addFailure(Test test, AssertionFailedError t) {
            failures.add(testName(test));
        }

        public void endTest(Test test) {
        }

        public void startTest(Test test) {
            testsSeen.add(testName(test));
        }

        public boolean saw(String testName) {
            return testsSeen.contains(testName);
        }

        public boolean failed(String testName) {
            return failures.contains(testName);
        }

        public boolean errored(String testName) {
            return errors.contains(testName);
        }

        public boolean passed(String testName) {
            return saw(testName) && !failed(testName) && !errored(testName);
        }

        private String testName(Test test) {
            TestCase testCase = (TestCase) test;
            return testCase.getClass().getSimpleName() + "." + testCase.getName();
        }
    }
}
