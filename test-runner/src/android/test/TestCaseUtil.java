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

import java.util.ArrayList;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import junit.runner.BaseTestRunner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
@Deprecated
public class TestCaseUtil {

    private TestCaseUtil() {
    }

    public static List<? extends Test> getTests(Test test, boolean flatten) {
        return getTests(test, flatten, new HashSet<Class<?>>());
    }

    private static List<? extends Test> getTests(Test test, boolean flatten,
            Set<Class<?>> seen) {
        List<Test> testCases = new ArrayList<>();
        if (test != null) {

            Test workingTest = null;
            /*
             * If we want to run a single TestCase method only, we must not
             * invoke the suite() method, because we will run all test methods
             * of the class then.
             */
            if (test instanceof TestCase &&
                    ((TestCase)test).getName() == null) {
                workingTest = invokeSuiteMethodIfPossible(test.getClass(),
                        seen);
            }
            if (workingTest == null) {
                workingTest = test;
            }

            if (workingTest instanceof TestSuite) {
                TestSuite testSuite = (TestSuite) workingTest;
                Enumeration enumeration = testSuite.tests();
                while (enumeration.hasMoreElements()) {
                    Test childTest = (Test) enumeration.nextElement();
                    if (flatten) {
                        testCases.addAll(getTests(childTest, flatten, seen));
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

    static Test invokeSuiteMethodIfPossible(Class testClass,
            Set<Class<?>> seen) {
        try {
            Method suiteMethod = testClass.getMethod(
                    BaseTestRunner.SUITE_METHODNAME, new Class[0]);
            /*
             * Additional check necessary: If a TestCase contains a suite()
             * method that returns a TestSuite including the TestCase itself,
             * we need to stop the recursion. We use a set of classes to
             * remember which classes' suite() methods were already invoked.
             */
            if (Modifier.isStatic(suiteMethod.getModifiers())
                    && !seen.contains(testClass)) {
                seen.add(testClass);
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

    static String getTestName(Test test) {
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
}
