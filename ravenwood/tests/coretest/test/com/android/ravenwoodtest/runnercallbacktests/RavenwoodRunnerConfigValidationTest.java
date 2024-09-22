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

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.NoRavenizer;
import android.platform.test.ravenwood.RavenwoodConfig;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;


/**
 * Test for @Config field extraction and validation.
 */
@NoRavenizer // This class shouldn't be executed with RavenwoodAwareTestRunner.
public class RavenwoodRunnerConfigValidationTest extends RavenwoodRunnerTestBase {
    public abstract static class ConfigInBaseClass {
        static String PACKAGE_NAME = "com.ConfigInBaseClass";

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig = new RavenwoodConfig.Builder()
                .setPackageName(PACKAGE_NAME).build();
    }

    /**
     * Make sure a config in the base class is detected.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigInBaseClassTest
    testStarted: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigInBaseClassTest)
    testFinished: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigInBaseClassTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigInBaseClassTest
    testSuiteFinished: classes
    testRunFinished: 1,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class ConfigInBaseClassTest extends ConfigInBaseClass {
        @Test
        public void test() {
            assertThat(InstrumentationRegistry.getInstrumentation().getContext().getPackageName())
                    .isEqualTo(PACKAGE_NAME);
        }
    }

    /**
     * Make sure a config in the base class is detected.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigOverridingTest
    testStarted: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigOverridingTest)
    testFinished: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigOverridingTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigOverridingTest
    testSuiteFinished: classes
    testRunFinished: 1,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class ConfigOverridingTest extends ConfigInBaseClass {
        static String PACKAGE_NAME_OVERRIDE = "com.ConfigOverridingTest";

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig = new RavenwoodConfig.Builder()
                .setPackageName(PACKAGE_NAME_OVERRIDE).build();

        @Test
        public void test() {
            assertThat(InstrumentationRegistry.getInstrumentation().getContext().getPackageName())
                    .isEqualTo(PACKAGE_NAME_OVERRIDE);
        }
    }

    /**
     * Test to make sure that if a test has a config error, the failure would be reported from
     * each test method.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testMethod1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testFailure: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest.sConfig expected to be public static
    testFinished: testMethod1(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testStarted: testMethod2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testFailure: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest.sConfig expected to be public static
    testFinished: testMethod2(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testStarted: testMethod3(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testFailure: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest.sConfig expected to be public static
    testFinished: testMethod3(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ErrorMustBeReportedFromEachTest)
    testSuiteFinished: classes
    testRunFinished: 3,3,0,0
    """)
    // CHECKSTYLE:ON
    public static class ErrorMustBeReportedFromEachTest {
        @RavenwoodConfig.Config
        private static RavenwoodConfig sConfig = // Invalid because it's private.
                new RavenwoodConfig.Builder().build();

        @Test
        public void testMethod1() {
        }

        @Test
        public void testMethod2() {
        }

        @Test
        public void testMethod3() {
        }
    }

    /**
     * Invalid because there are two @Config's.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateConfigTest)
    testFailure: Class com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest.DuplicateConfigTest has multiple fields with @RavenwoodConfig.Config
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateConfigTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class DuplicateConfigTest {

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig1 =
                new RavenwoodConfig.Builder().build();

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig2 =
                new RavenwoodConfig.Builder().build();

        @Test
        public void testConfig() {
        }
    }

    /**
     * @Config's must be static.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonStaticConfigTest)
    testFailure: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonStaticConfigTest.sConfig expected to be public static
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonStaticConfigTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class NonStaticConfigTest {

        @RavenwoodConfig.Config
        public RavenwoodConfig sConfig =
                new RavenwoodConfig.Builder().build();

        @Test
        public void testConfig() {
        }
    }

    /**
     * @Config's must be public.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonPublicConfigTest)
    testFailure: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonPublicConfigTest.sConfig expected to be public static
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$NonPublicConfigTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class NonPublicConfigTest {

        @RavenwoodConfig.Config
        RavenwoodConfig sConfig =
                new RavenwoodConfig.Builder().build();

        @Test
        public void testConfig() {
        }
    }

    /**
     * @Config's must be of type RavenwoodConfig.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeConfigTest)
    testFailure: Field com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest.WrongTypeConfigTest.sConfig has @RavenwoodConfig.Config but type is not RavenwoodConfig
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeConfigTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class WrongTypeConfigTest {

        @RavenwoodConfig.Config
        public static Object sConfig =
                new RavenwoodConfig.Builder().build();

        @Test
        public void testConfig() {
        }

    }

    /**
     * @Rule must be of type RavenwoodRule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeRuleTest
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeRuleTest)
    testFailure: If you have a RavenwoodRule in your test, make sure the field type is RavenwoodRule so Ravenwood can detect it.
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeRuleTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WrongTypeRuleTest
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class WrongTypeRuleTest {

        @Rule
        public TestRule mRule = new RavenwoodRule.Builder().build();

        @Test
        public void testConfig() {
        }

    }

    /**
     * Config can't be used with a (instance) Rule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WithInstanceRuleTest)
    testFailure: RavenwoodConfig and RavenwoodRule cannot be used in the same class. Suggest migrating to RavenwoodConfig.
    testFinished: testConfig(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WithInstanceRuleTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class WithInstanceRuleTest {

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig =
                new RavenwoodConfig.Builder().build();

        @Rule
        public RavenwoodRule mRule = new RavenwoodRule.Builder().build();

        @Test
        public void testConfig() {
        }
    }

    /**
     * Config can't be used with a (static) Rule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WithStaticRuleTest)
    testFailure: Exception detected in constructor
    testFinished: Constructor(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$WithStaticRuleTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class WithStaticRuleTest {

        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig =
                new RavenwoodConfig.Builder().build();

        @Rule
        public static RavenwoodRule sRule = new RavenwoodRule.Builder().build();

        @Test
        public void testConfig() {
        }
    }

    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateRulesTest
    testStarted: testMultipleRulesNotAllowed(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateRulesTest)
    testFailure: Multiple nesting RavenwoodRule's are detected in the same class, which is not supported.
    testFinished: testMultipleRulesNotAllowed(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateRulesTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$DuplicateRulesTest
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class DuplicateRulesTest {

        @Rule
        public final RavenwoodRule mRavenwood1 = new RavenwoodRule();

        @Rule
        public final RavenwoodRule mRavenwood2 = new RavenwoodRule();

        @Test
        public void testMultipleRulesNotAllowed() {
        }
    }

    public static class RuleInBaseClass {
        static String PACKAGE_NAME = "com.RuleInBaseClass";
        @Rule
        public final RavenwoodRule mRavenwood1 = new RavenwoodRule.Builder()
                .setPackageName(PACKAGE_NAME).build();
    }

    /**
     * Make sure that RavenwoodRule in a base class takes effect.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleInBaseClassSuccessTest
    testStarted: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleInBaseClassSuccessTest)
    testFinished: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleInBaseClassSuccessTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleInBaseClassSuccessTest
    testSuiteFinished: classes
    testRunFinished: 1,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class RuleInBaseClassSuccessTest extends RuleInBaseClass {

        @Test
        public void testRuleInBaseClass() {
            assertThat(InstrumentationRegistry.getInstrumentation().getContext().getPackageName())
                    .isEqualTo(PACKAGE_NAME);
        }
    }

    /**
     * Make sure that having a config and a rule in a base class should fail.
     * RavenwoodRule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testStarted: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleInBaseClassTest)
    testFailure: RavenwoodConfig and RavenwoodRule cannot be used in the same class. Suggest migrating to RavenwoodConfig.
    testFinished: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleInBaseClassTest)
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class ConfigWithRuleInBaseClassTest extends RuleInBaseClass {
        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig = new RavenwoodConfig.Builder().build();

        @Test
        public void test() {
        }
    }

    /**
     * Same as {@link RuleInBaseClass}, but the type of the rule field is not {@link RavenwoodRule}.
     */
    public abstract static class RuleWithDifferentTypeInBaseClass {
        static String PACKAGE_NAME = "com.RuleWithDifferentTypeInBaseClass";
        @Rule
        public final TestRule mRavenwood1 = new RavenwoodRule.Builder()
                .setPackageName(PACKAGE_NAME).build();
    }

    /**
     * Make sure that RavenwoodRule in a base class takes effect, even if the field type is not
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest
    testStarted: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest)
    testFailure: If you have a RavenwoodRule in your test, make sure the field type is RavenwoodRule so Ravenwood can detect it.
    testFinished: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class RuleWithDifferentTypeInBaseClassSuccessTest extends RuleWithDifferentTypeInBaseClass {

        @Test
        public void testRuleInBaseClass() {
            assertThat(InstrumentationRegistry.getInstrumentation().getContext().getPackageName())
                    .isEqualTo(PACKAGE_NAME);
        }
    }

    /**
     * Make sure that having a config and a rule in a base class should fail, even if the field type is not
     * RavenwoodRule.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleWithDifferentTypeInBaseClassTest
    testStarted: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleWithDifferentTypeInBaseClassTest)
    testFailure: If you have a RavenwoodRule in your test, make sure the field type is RavenwoodRule so Ravenwood can detect it.
    testFinished: test(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleWithDifferentTypeInBaseClassTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRunnerConfigValidationTest$ConfigWithRuleWithDifferentTypeInBaseClassTest
    testSuiteFinished: classes
    testRunFinished: 1,1,0,0
    """)
    // CHECKSTYLE:ON
    public static class ConfigWithRuleWithDifferentTypeInBaseClassTest extends RuleWithDifferentTypeInBaseClass {
        @RavenwoodConfig.Config
        public static RavenwoodConfig sConfig = new RavenwoodConfig.Builder().build();

        @Test
        public void test() {
        }
    }
}
