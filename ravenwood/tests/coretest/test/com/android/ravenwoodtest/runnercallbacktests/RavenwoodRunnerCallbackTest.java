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
package com.android.ravenwoodtest.runnercallbacktests;

import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.NoRavenizer;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;


/**
 * Tests to make sure {@link RavenwoodAwareTestRunner} produces expected callbacks in various
 * error situations in places such as @BeforeClass / @AfterClass / Constructors, which are
 * out of test method bodies.
 */
@NoRavenizer // This class shouldn't be executed with RavenwoodAwareTestRunner.
public class RavenwoodRunnerCallbackTest extends RavenwoodRunnerTestBase {

    /**
     * Throws an exception in @AfterClass. This should produce a critical error.
     */
    @RunWith(BlockJUnit4ClassRunner.class)
    // CHECKSTYLE:OFF Generated code
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest
    testStarted: test1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest)
    testFinished: test1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest)
    testStarted: test2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest)
    testFinished: test2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$AfterClassFailureTest
    criticalError: Failures detected in @AfterClass, which would be swallowed by tradefed: FAILURE
    testSuiteFinished: classes
    testRunFinished: 2,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class AfterClassFailureTest {
        public AfterClassFailureTest() {
        }

        @AfterClass
        public static void afterClass() {
            throw new RuntimeException("FAILURE");
        }

        @Test
        public void test1() {
        }

        @Test
        public void test2() {
        }

    }

    /**
     * Assumption failure in @BeforeClass.
     */
    @RunWith(ParameterizedAndroidJunit4.class)
    // Because the test uses ParameterizedAndroidJunit4 with two parameters,
    // the whole class is executed twice.
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest
    testSuiteStarted: [0]
    testStarted: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testStarted: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testSuiteFinished: [0]
    testSuiteStarted: [1]
    testStarted: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testStarted: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassAssumptionFailureTest)
    testSuiteFinished: [1]
    testSuiteFinished: classes
    testRunFinished: 4,0,4,0
    """)
    // CHECKSTYLE:ON
    public static class BeforeClassAssumptionFailureTest {
        public BeforeClassAssumptionFailureTest(String param) {
        }

        @BeforeClass
        public static void beforeClass() {
            Assume.assumeTrue(false);
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

    /**
     * General exception in @BeforeClass.
     */
    @RunWith(ParameterizedAndroidJunit4.class)
    // Because the test uses ParameterizedAndroidJunit4 with two parameters,
    // the whole class is executed twice.
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest
    testSuiteStarted: [0]
    testStarted: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testFailure: FAILURE
    testFinished: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testStarted: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testFailure: FAILURE
    testFinished: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testSuiteFinished: [0]
    testSuiteStarted: [1]
    testStarted: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testFailure: FAILURE
    testFinished: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testStarted: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testFailure: FAILURE
    testFinished: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BeforeClassExceptionTest)
    testSuiteFinished: [1]
    testSuiteFinished: classes
    testRunFinished: 4,4,0,0
    """)
    // CHECKSTYLE:ON
    public static class BeforeClassExceptionTest {
        public BeforeClassExceptionTest(String param) {
        }

        @BeforeClass
        public static void beforeClass() {
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

    /**
     * Assumption failure from a @ClassRule.
     */
    @RunWith(ParameterizedAndroidJunit4.class)
    // Because the test uses ParameterizedAndroidJunit4 with two parameters,
    // the whole class is executed twice.
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest
    testSuiteStarted: [0]
    testStarted: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testStarted: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testSuiteFinished: [0]
    testSuiteStarted: [1]
    testStarted: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testStarted: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleAssumptionFailureTest)
    testSuiteFinished: [1]
    testSuiteFinished: classes
    testRunFinished: 4,0,4,0
    """)
    // CHECKSTYLE:ON
    public static class ClassRuleAssumptionFailureTest {
        @ClassRule
        public static final TestRule sClassRule = new TestRule() {
            @Override
            public Statement apply(Statement base, Description description) {
                assumeTrue(false);
                return null; // unreachable
            }
        };

        public ClassRuleAssumptionFailureTest(String param) {
        }

        @Parameters
        public static List<String> getParams() {
            var params = new ArrayList<String>();
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

    /**
     * General exception from a @ClassRule.
     */
    @RunWith(ParameterizedAndroidJunit4.class)
    // Because the test uses ParameterizedAndroidJunit4 with two parameters,
    // the whole class is executed twice.
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest
    testSuiteStarted: [0]
    testStarted: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testStarted: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[0](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testSuiteFinished: [0]
    testSuiteStarted: [1]
    testStarted: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test1[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testStarted: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testAssumptionFailure: got: <false>, expected: is <true>
    testFinished: test2[1](com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassRuleExceptionTest)
    testSuiteFinished: [1]
    testSuiteFinished: classes
    testRunFinished: 4,0,4,0
    """)
    // CHECKSTYLE:ON
    public static class ClassRuleExceptionTest {
        @ClassRule
        public static final TestRule sClassRule = new TestRule() {
            @Override
            public Statement apply(Statement base, Description description) {
                assumeTrue(false);
                return null; // unreachable
            }
        };

        public ClassRuleExceptionTest(String param) {
        }

        @Parameters
        public static List<String> getParams() {
            var params = new ArrayList<String>();
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

    /**
     * General exception from a @ClassRule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ExceptionFromInnerRunnerConstructorTest)
    testFailure: Exception detected in constructor
    testFinished: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ExceptionFromInnerRunnerConstructorTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class ExceptionFromInnerRunnerConstructorTest {
        public ExceptionFromInnerRunnerConstructorTest(String arg1, String arg2) {
        }

        @Test
        public void test1() {
        }

        @Test
        public void test2() {
        }
    }

    /**
     * The test class is unloadable, but has a @DisabledOnRavenwood.
     */
    @RunWith(AndroidJUnit4.class)
    @DisabledOnRavenwood
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: ClassUnloadbleAndDisabledTest(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndDisabledTest)
    testIgnored: ClassUnloadbleAndDisabledTest(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndDisabledTest)
    testSuiteFinished: ClassUnloadbleAndDisabledTest(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndDisabledTest)
    testSuiteFinished: classes
    testRunFinished: 0,0,0,1
    """)
    // CHECKSTYLE:ON
    public static class ClassUnloadbleAndDisabledTest {
        static {
            Assert.fail("Class unloadable!");
        }

        @Test
        public void test1() {
        }

        @Test
        public void test2() {
        }
    }

    /**
     * The test class is unloadable, but has a @DisabledOnRavenwood.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest
    testStarted: test1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest)
    testFailure: Class unloadable!
    testFinished: test1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest)
    testStarted: test2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest)
    testFailure: Class unloadable!
    testFinished: test2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$ClassUnloadbleAndEnabledTest)
    testSuiteFinished: classes
    testRunFinished: 2,2,0,0
    """)
    // CHECKSTYLE:ON
    public static class ClassUnloadbleAndEnabledTest {
        static {
            Assert.fail("Class unloadable!");
        }

        @Test
        public void test1() {
        }

        @Test
        public void test2() {
        }
    }

    public static class BrokenTestRunner extends BlockJUnit4ClassRunner {
        public BrokenTestRunner(Class<?> testClass) throws InitializationError {
            super(testClass);

            if (true)  {
                throw new RuntimeException("This is a broken test runner!");
            }
        }
    }

    /**
     * The test runner throws an exception from the ctor.
     */
    @RunWith(BrokenTestRunner.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BrokenRunnerTest)
    testFailure: Exception detected in constructor
    testFinished: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerCallbackTest$BrokenRunnerTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class BrokenRunnerTest {
        @Test
        public void test1() {
        }

        @Test
        public void test2() {
        }
    }
}
