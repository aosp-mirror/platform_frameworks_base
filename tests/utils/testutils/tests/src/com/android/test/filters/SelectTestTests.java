/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.test.filters;

import static com.android.test.filters.SelectTest.OPTION_SELECT_TEST;
import static com.android.test.filters.SelectTest.OPTION_SELECT_TEST_VERBOSE;

import static org.hamcrest.collection.IsArrayContaining.hasItemInArray;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.ArraySet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;

public class SelectTestTests {

    private static final String PACKAGE_A = "packageA.";
    private static final String PACKAGE_B = "packageB.";
    private static final String PACKAGE_C = "packageC.";
    private static final String CLASS_A1 = PACKAGE_A + "Class1";
    private static final String CLASS_A2 = PACKAGE_A + "Class2";
    private static final String CLASS_B3 = PACKAGE_B + "Class3";
    private static final String CLASS_B4 = PACKAGE_B + "Class4";
    private static final String CLASS_C5 = PACKAGE_C + "Class5";
    private static final String CLASS_C6 = PACKAGE_C + "Class6";
    private static final String METHOD_A1K = CLASS_A1 + "#methodK";
    private static final String METHOD_A1L = CLASS_A1 + "#methodL";
    private static final String METHOD_A2M = CLASS_A2 + "#methodM";
    private static final String METHOD_A2N = CLASS_A2 + "#methodN";
    private static final String METHOD_B3P = CLASS_B3 + "#methodP";
    private static final String METHOD_B3Q = CLASS_B3 + "#methodQ";
    private static final String METHOD_B4R = CLASS_B4 + "#methodR";
    private static final String METHOD_B4S = CLASS_B4 + "#methodS";
    private static final String METHOD_C5W = CLASS_C5 + "#methodW";
    private static final String METHOD_C5X = CLASS_C5 + "#methodX";
    private static final String METHOD_C6Y = CLASS_C6 + "#methodY";
    private static final String METHOD_C6Z = CLASS_C6 + "#methodZ";

    private static final Set<Description> TEST_METHOD_A1K = methodTest(METHOD_A1K);
    private static final Set<Description> TEST_METHOD_A1L = methodTest(METHOD_A1L);
    private static final Set<Description> TEST_METHOD_A2M = methodTest(METHOD_A2M);
    private static final Set<Description> TEST_METHOD_A2N = methodTest(METHOD_A2N);
    private static final Set<Description> TEST_METHOD_B3P = methodTest(METHOD_B3P);
    private static final Set<Description> TEST_METHOD_B3Q = methodTest(METHOD_B3Q);
    private static final Set<Description> TEST_METHOD_B4R = methodTest(METHOD_B4R);
    private static final Set<Description> TEST_METHOD_B4S = methodTest(METHOD_B4S);
    private static final Set<Description> TEST_METHOD_C5W = methodTest(METHOD_C5W);
    private static final Set<Description> TEST_METHOD_C5X = methodTest(METHOD_C5X);
    private static final Set<Description> TEST_METHOD_C6Y = methodTest(METHOD_C6Y);
    private static final Set<Description> TEST_METHOD_C6Z = methodTest(METHOD_C6Z);
    private static final Set<Description> TEST_CLASS_A1 = merge(TEST_METHOD_A1K, TEST_METHOD_A1L);
    private static final Set<Description> TEST_CLASS_A2 = merge(TEST_METHOD_A2M, TEST_METHOD_A2N);
    private static final Set<Description> TEST_CLASS_B3 = merge(TEST_METHOD_B3P, TEST_METHOD_B3Q);
    private static final Set<Description> TEST_CLASS_B4 = merge(TEST_METHOD_B4R, TEST_METHOD_B4S);
    private static final Set<Description> TEST_CLASS_C5 = merge(TEST_METHOD_C5W, TEST_METHOD_C5X);
    private static final Set<Description> TEST_CLASS_C6 = merge(TEST_METHOD_C6Y, TEST_METHOD_C6Z);
    private static final Set<Description> TEST_PACKAGE_A = merge(TEST_CLASS_A1, TEST_CLASS_A2);
    private static final Set<Description> TEST_PACKAGE_B = merge(TEST_CLASS_B3, TEST_CLASS_B4);
    private static final Set<Description> TEST_PACKAGE_C = merge(TEST_CLASS_C5, TEST_CLASS_C6);
    private static final Set<Description> TEST_ALL =
            merge(TEST_PACKAGE_A, TEST_PACKAGE_B, TEST_PACKAGE_C);

    private SelectTestBuilder mBuilder;

    @Before
    public void setUp() {
        mBuilder = new SelectTestBuilder();
    }

    private static class SelectTestBuilder {
        private final Bundle mTestArgs = new Bundle();

        Filter build() {
            mTestArgs.putString(OPTION_SELECT_TEST_VERBOSE, Boolean.TRUE.toString());
            return new SelectTest(mTestArgs);
        }

        SelectTestBuilder withSelectTest(String... selectTestArgs) {
            putTestOption(OPTION_SELECT_TEST, selectTestArgs);
            return this;
        }

        private void putTestOption(String option, String... args) {
            if (args.length > 0) {
                StringJoiner joiner = new StringJoiner(",");
                for (String arg : args) {
                    joiner.add(arg);
                }
                mTestArgs.putString(option, joiner.toString());
            }
        }
    }

    private static Set<Description> methodTest(String testName) {
        int methodSep = testName.indexOf("#");
        String className = testName.substring(0, methodSep);
        String methodName = testName.substring(methodSep + 1);
        final Set<Description> tests = new ArraySet<>();
        tests.add(Description.createSuiteDescription(className));
        tests.add(Description.createTestDescription(className, methodName));
        return Collections.unmodifiableSet(tests);
    }

    @SafeVarargs
    private static Set<Description> merge(Set<Description>... testSpecs) {
        final Set<Description> merged = new LinkedHashSet<>();
        for (Set<Description> testSet : testSpecs) {
            merged.addAll(testSet);
        }
        return Collections.unmodifiableSet(merged);
    }

    @SafeVarargs
    private static void acceptTests(Filter filter, Set<Description>... testSpecs) {
        final Set<Description> accepts = merge(testSpecs);
        for (Description test : TEST_ALL) {
            if (accepts.contains(test)) {
                assertTrue("accept " + test, filter.shouldRun(test));
            } else {
                assertFalse("reject " + test, filter.shouldRun(test));
            }
        }
    }

    @Test
    public void testAddSelectTest() {
        final Bundle testArgs = new Bundle();

        final Bundle modifiedTestArgs =
                SelectTest.addSelectTest(testArgs, PACKAGE_A, CLASS_B3, METHOD_C5X);
        assertSame(testArgs, modifiedTestArgs);

        final String selectTestArgs = modifiedTestArgs.getString(OPTION_SELECT_TEST);
        assertNotNull(selectTestArgs);
        final String[] selectedTests = selectTestArgs.split(",");
        assertThat(selectedTests, hasItemInArray(PACKAGE_A));
        assertThat(selectedTests, hasItemInArray(CLASS_B3));
        assertThat(selectedTests, hasItemInArray(METHOD_C5X));
    }

    @Test
    public void testAddSelectTest_prependExistingTestArg() {
        final Bundle testArgs = new Bundle();
        testArgs.putString(OPTION_SELECT_TEST, new StringJoiner(",")
                .add(PACKAGE_A)
                .add(CLASS_B3)
                .add(METHOD_C5X)
                .toString());

        final Bundle modifiedTestArgs =
                SelectTest.addSelectTest(testArgs, PACKAGE_B, CLASS_B4, METHOD_C6Y);

        final String selectTestArgs = modifiedTestArgs.getString(OPTION_SELECT_TEST);
        assertNotNull(selectTestArgs);
        final String[] selectedTests = selectTestArgs.split(",");
        assertThat(selectedTests, hasItemInArray(PACKAGE_A));
        assertThat(selectedTests, hasItemInArray(CLASS_B3));
        assertThat(selectedTests, hasItemInArray(METHOD_C5X));
        assertThat(selectedTests, hasItemInArray(PACKAGE_B));
        assertThat(selectedTests, hasItemInArray(CLASS_B4));
        assertThat(selectedTests, hasItemInArray(METHOD_C6Y));
    }

    @Test
    public void testFilterDisabled() {
        final Filter filter = mBuilder.build();
        acceptTests(filter, TEST_ALL);
    }

    @Test
    public void testSelectPackage() {
        final Filter filter = mBuilder.withSelectTest(PACKAGE_A, PACKAGE_B).build();
        acceptTests(filter, TEST_PACKAGE_A, TEST_PACKAGE_B);
    }

    @Test
    public void testSelectClass() {
        final Filter filter = mBuilder.withSelectTest(CLASS_A1, CLASS_A2, CLASS_B3).build();
        acceptTests(filter, TEST_CLASS_A1, TEST_CLASS_A2, TEST_CLASS_B3);
    }

    @Test
    public void testSelectMethod() {
        final Filter filter = mBuilder
                .withSelectTest(METHOD_A1K, METHOD_A2M, METHOD_A2N, METHOD_B3P).build();
        acceptTests(filter, TEST_METHOD_A1K, TEST_METHOD_A2M, TEST_METHOD_A2N, TEST_METHOD_B3P);
    }

    @Test
    public void testSelectClassAndPackage() {
        final Filter filter = mBuilder.withSelectTest(CLASS_A1, PACKAGE_B, CLASS_C5).build();
        acceptTests(filter, TEST_CLASS_A1, TEST_PACKAGE_B, TEST_CLASS_C5);
    }

    @Test
    public void testSelectMethodAndPackage() {
        final Filter filter = mBuilder.withSelectTest(METHOD_A1K, PACKAGE_B, METHOD_C5W).build();
        acceptTests(filter, TEST_METHOD_A1K, TEST_PACKAGE_B, TEST_METHOD_C5W);
    }

    @Test
    public void testSelectMethodAndClass() {
        final Filter filter = mBuilder.withSelectTest(METHOD_A1K, CLASS_C5, METHOD_B3P).build();
        acceptTests(filter, TEST_METHOD_A1K, TEST_CLASS_C5, TEST_METHOD_B3P);
    }

    @Test
    public void testSelectClassAndSamePackage() {
        final Filter filter = mBuilder.withSelectTest(
                CLASS_A1, PACKAGE_A, CLASS_B3, PACKAGE_C, CLASS_C5).build();
        acceptTests(filter, TEST_PACKAGE_A, TEST_CLASS_B3, TEST_PACKAGE_C);
    }

    @Test
    public void testSelectMethodAndSameClass() {
        final Filter filter = mBuilder.withSelectTest(
                METHOD_A1K, METHOD_A2M, CLASS_A1, CLASS_B3, METHOD_B3P, METHOD_B4R).build();
        acceptTests(filter, TEST_CLASS_A1, TEST_METHOD_A2M, TEST_CLASS_B3, TEST_METHOD_B4R);
    }

    @Test
    public void testSelectMethodAndSamePackage() {
        final Filter filter = mBuilder.withSelectTest(
                METHOD_A1K, METHOD_A1L, METHOD_A2M, PACKAGE_A,
                PACKAGE_C, METHOD_C5W, METHOD_C5X, METHOD_C6Y).build();
        acceptTests(filter, TEST_PACKAGE_A, TEST_PACKAGE_C);
    }

    @Test
    public void testSelectMethodAndClassAndPackage() {
        final Filter filter = mBuilder.withSelectTest(
                METHOD_A1K, CLASS_A1, METHOD_A1L, METHOD_A2M, PACKAGE_A,
                PACKAGE_B, METHOD_B3Q, CLASS_B3, METHOD_B4R, METHOD_B3P).build();
        acceptTests(filter, TEST_PACKAGE_A, TEST_PACKAGE_B);
    }
}
