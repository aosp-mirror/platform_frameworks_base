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

import com.google.android.collect.Lists;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.List;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public class TestCaseUtil {

    private TestCaseUtil() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> getTestCaseNames(Test test, boolean flatten) {
        List<Test> tests = (List<Test>) getTests(test, flatten);
        List<String> testCaseNames = Lists.newArrayList();
        for (Test aTest : tests) {
            testCaseNames.add(getTestName(aTest));
        }
        return testCaseNames;
    }

    public static List<? extends Test> getTests(Test test, boolean flatten) {
        List<Test> testCases = Lists.newArrayList();
        if (test != null) {

            Test workingTest = invokeSuiteMethodIfPossible(test.getClass());
            if (workingTest == null) {
                workingTest = test;
            }

            if (workingTest instanceof TestSuite) {
                TestSuite testSuite = (TestSuite) workingTest;
                Enumeration enumeration = testSuite.tests();
                while (enumeration.hasMoreElements()) {
                    Test childTest = (Test) enumeration.nextElement();
                    if (flatten) {
                        testCases.addAll(getTests(childTest, flatten));
                    } else {
                        testCases.add(childTest);
                    }
                }
            } else {
                testCases.add(workingTest);
            }
        }
        return testCases;
    }

    private static Test invokeSuiteMethodIfPossible(Class testClass) {
        try {
            Method suiteMethod = testClass.getMethod(
                    BaseTestRunner.SUITE_METHODNAME, new Class[0]);
            if (Modifier.isStatic(suiteMethod.getModifiers())) {
                try {
                    return (Test) suiteMethod.invoke(null, (Object[]) null);
                } catch (InvocationTargetException e) {
                    // do nothing
                } catch (IllegalAccessException e) {
                    // do nothing
                }
            }
        } catch (NoSuchMethodException e) {
            // do nothing
        }
        return null;
    }

    public static String getTestName(Test test) {
        if (test instanceof TestCase) {
            TestCase testCase = (TestCase) test;
            return testCase.getName();
        } else if (test instanceof TestSuite) {
            TestSuite testSuite = (TestSuite) test;
            String name = testSuite.getName();
            if (name != null) {
                int index = name.lastIndexOf(".");
                if (index > -1) {
                    return name.substring(index + 1);
                } else {
                    return name;
                }
            }
        }
        return "";
    }

    public static Test getTestAtIndex(TestSuite testSuite, int position) {
        int index = 0;
        Enumeration enumeration = testSuite.tests();
        while (enumeration.hasMoreElements()) {
            Test test = (Test) enumeration.nextElement();
            if (index == position) {
                return test;
            }
            index++;
        }
        return null;
    }

    public static TestSuite createTestSuite(Class<? extends Test> testClass)
            throws InstantiationException, IllegalAccessException {

        Test test = invokeSuiteMethodIfPossible(testClass);
        if (test == null) {
            return new TestSuite(testClass);

        } else if (TestCase.class.isAssignableFrom(test.getClass())) {
            TestSuite testSuite = new TestSuite(test.getClass().getName());
            testSuite.addTest(test);
            return testSuite;
        }

        return (TestSuite) test;
    }
}
