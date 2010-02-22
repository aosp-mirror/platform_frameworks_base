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

import com.android.internal.util.Predicate;
import static android.test.suitebuilder.ListTestCaseNames.getTestCaseNames;
import android.test.suitebuilder.examples.OuterTest;
import android.test.suitebuilder.examples.suppress.SuppressedTest;
import android.test.suitebuilder.examples.error.ErrorTest;
import android.test.suitebuilder.examples.error.FailingTest;
import android.test.suitebuilder.examples.nested.Level1Test;
import android.test.suitebuilder.examples.nested.nested.Level2Test;
import android.test.suitebuilder.examples.simple.SimpleTest;
import android.test.suitebuilder.examples.subclass.SubclassTest;
import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestResult;
import junit.framework.TestSuite;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class TestSuiteBuilderTest extends TestCase {

    private TestSuiteBuilder testSuiteBuilder;

    protected void setUp() throws Exception {
        super.setUp();
        testSuiteBuilder = new TestSuiteBuilder(getClass());
    }

    public void testShouldRunSimpleTests() throws Exception {
        testSuiteBuilder.includePackages(packageFor(SimpleTest.class));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertTrue(recorder.passed("SimpleTest.testSimpleOne"));
        assertTrue(recorder.passed("SimpleTest.testSimpleTwo"));
        assertTrue(recorder.passed("AnotherSimpleTest.testAnotherOne"));
    }

    public void testShouldOnlyIncludeTestsThatSatisfyAllPredicates() throws Exception {
        testSuiteBuilder.includePackages(packageFor(SimpleTest.class))
                .addRequirements(testsWhoseNameContains("test"))
                .addRequirements(testsWhoseNameContains("Simple"))
                .addRequirements(testsWhoseNameContains("Two"));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertTrue(recorder.passed("SimpleTest.testSimpleTwo"));
    }

    public void testShouldAddFailingTestsToSuite() throws Exception {
        testSuiteBuilder.includePackages(packageFor(FailingTest.class));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertTrue(recorder.failed("FailingTest.testFailOne"));
        assertTrue(recorder.failed("FailingTest.testFailTwo"));
    }

    public void testShouldAddTestsWithErrorsToSuite() throws Exception {
        testSuiteBuilder.includePackages(packageFor(ErrorTest.class));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertTrue(recorder.errored("ErrorTest.testErrorOne"));
        assertTrue(recorder.errored("ErrorTest.testErrorTwo"));
    }

    public void testShouldRunTestsInheritedFromSuperclass() throws Exception {
        testSuiteBuilder.includePackages(packageFor(SubclassTest.class));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertEquals(2, getTestCaseNames(testSuiteBuilder.build()).size());

        assertTrue(recorder.passed("SubclassTest.testSubclass"));
        assertTrue(recorder.passed("SubclassTest.testSuperclass"));
        assertFalse(recorder.saw("SuperclassTest.testSuperclass"));
    }

    public void testShouldIncludeTestsInSubPackagesRecursively() throws Exception {
        testSuiteBuilder.includePackages(packageFor(Level1Test.class));

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertTrue(recorder.passed("Level1Test.testLevel1"));
        assertTrue(recorder.passed("Level2Test.testLevel2"));
    }

    public void testExcludePackage() throws Exception {
        testSuiteBuilder.includePackages(packageFor(SimpleTest.class),
                packageFor(Level1Test.class)).excludePackages(packageFor(Level2Test.class));

        TestSuite testSuite = testSuiteBuilder.build();
        assertContentsInOrder(getTestCaseNames(testSuite),
                "testLevel1", "testAnotherOne", "testSimpleOne", "testSimpleTwo");
    }

    public void testShouldExcludeSuppressedTests() throws Exception {
        testSuiteBuilder.includePackages(packageFor(SuppressedTest.class));
        testSuiteBuilder.build();

        SuiteExecutionRecorder recorder = runSuite(testSuiteBuilder);

        assertEquals(1, recorder.testsSeen.size());
        assertTrue(recorder.passed("PartiallySuppressedTest.testUnSuppressedMethod"));
    }

    /**
     * This test calls {@link OuterTest#buildTestsUnderHereRecursively()} to control
     * the packages under test. The call to {@link TestSuiteBuilder#includeAllPackagesUnderHere()}
     * is made from there so that only return the example tests.
     */
    public void testIncludeAllPackagesUnderHere() throws Exception {

        TestSuite testSuite = new OuterTest().buildTestsUnderHereRecursively();
        assertContentsInOrder(getTestCaseNames(testSuite),
                "testOuter", "testErrorOne", "testErrorTwo", "testFailOne", "testFailTwo",
                "testInstrumentation", "testLevel1", "testLevel2", "testAnotherOne",
                "testSimpleOne", "testSimpleTwo", "testNonSmoke", "testSmoke", "testSubclass",
                "testSuperclass", "testUnSuppressedMethod");
    }

    private void assertContentsInOrder(List<String> actual, String... source) {
        String[] clonedSource = source.clone();
        assertEquals("Unexpected number of items.", clonedSource.length, actual.size());
        for (int i = 0; i < actual.size(); i++) {
            String actualItem = actual.get(i);
            String sourceItem = clonedSource[i];
            assertEquals("Unexpected item. Index: " + i, sourceItem, actualItem);
        }
    }

    private static String packageFor(Class clazz) {
        String className = clazz.getName();
        return className.substring(0, className.lastIndexOf('.'));
    }

    private Predicate<TestMethod> testsWhoseNameContains(final String string) {
        return new Predicate<TestMethod>() {
            public boolean apply(TestMethod testMethod) {
                return testMethod.getName().contains(string);
            }
        };
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
