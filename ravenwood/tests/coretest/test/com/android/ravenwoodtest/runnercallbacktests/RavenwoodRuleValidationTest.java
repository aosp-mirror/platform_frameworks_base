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

import android.os.SystemProperties;
import android.platform.test.annotations.NoRavenizer;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Test for RavenwoodRule.
 */
@NoRavenizer // This class shouldn't be executed with RavenwoodAwareTestRunner.
public class RavenwoodRuleValidationTest extends RavenwoodRunnerTestBase {

    public static class RuleInBaseClass {
        static String PROPERTY_KEY = "debug.ravenwood.prop.in.base";
        static String PROPERTY_VAL = "ravenwood";
        @Rule
        public final RavenwoodRule mRavenwood1 = new RavenwoodRule.Builder()
                .setSystemPropertyImmutable(PROPERTY_KEY, PROPERTY_VAL).build();
    }

    /**
     * Make sure that RavenwoodRule in a base class takes effect.
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleInBaseClassSuccessTest
    testStarted: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleInBaseClassSuccessTest)
    testFinished: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleInBaseClassSuccessTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleInBaseClassSuccessTest
    testSuiteFinished: classes
    testRunFinished: 1,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class RuleInBaseClassSuccessTest extends RuleInBaseClass {

        @Test
        public void testRuleInBaseClass() {
            assertThat(SystemProperties.get(PROPERTY_KEY)).isEqualTo(PROPERTY_VAL);
        }
    }

    /**
     * Same as {@link RuleInBaseClass}, but the type of the rule field is not {@link RavenwoodRule}.
     */
    public abstract static class RuleWithDifferentTypeInBaseClass {
        static String PROPERTY_KEY = "debug.ravenwood.prop.in.base.different.type";
        static String PROPERTY_VAL = "ravenwood";
        @Rule
        public final TestRule mRavenwood1 = new RavenwoodRule.Builder()
                .setSystemPropertyImmutable(PROPERTY_KEY, PROPERTY_VAL).build();
    }

    /**
     * Make sure that RavenwoodRule in a base class takes effect, even if the field type is not
     */
    @RunWith(AndroidJUnit4.class)
    // CHECKSTYLE:OFF
    @Expected("""
    testRunStarted: classes
    testSuiteStarted: classes
    testSuiteStarted: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest
    testStarted: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest)
    testFinished: testRuleInBaseClass(com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest)
    testSuiteFinished: com.android.ravenwoodtest.runnercallbacktests.RavenwoodRuleValidationTest$RuleWithDifferentTypeInBaseClassSuccessTest
    testSuiteFinished: classes
    testRunFinished: 1,0,0,0
    """)
    // CHECKSTYLE:ON
    public static class RuleWithDifferentTypeInBaseClassSuccessTest extends RuleWithDifferentTypeInBaseClass {

        @Test
        public void testRuleInBaseClass() {
            assertThat(SystemProperties.get(PROPERTY_KEY)).isEqualTo(PROPERTY_VAL);
        }
    }
}
