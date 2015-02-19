/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.testrunner;

import junit.framework.TestCase;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A convenient class that encapsulates functions for adding test classes
 *
 * @hide
 */
public class TestCaseCollector {

    private ClassLoader mClassLoader;
    private List<TestCase> mTestCases;
    private TestCaseFilter mFilter;

    public TestCaseCollector(ClassLoader classLoader, TestCaseFilter filter) {
        mClassLoader = classLoader;
        mTestCases = new ArrayList<TestCase>();
        mFilter = filter;
    }

    /**
     * Adds classes to test by providing a list of class names in string
     *
     * The class name may be in "<class name>#<method name>" format
     *
     * @param classNames class must be subclass of {@link UiAutomatorTestCase}
     * @throws ClassNotFoundException
     */
    public void addTestClasses(List<String> classNames) throws ClassNotFoundException {
        for (String className : classNames) {
            addTestClass(className);
        }
    }

    /**
     * Adds class to test by providing class name in string.
     *
     * The class name may be in "<class name>#<method name>" format
     *
     * @param className classes must be subclass of {@link UiAutomatorTestCase}
     * @throws ClassNotFoundException
     */
    public void addTestClass(String className) throws ClassNotFoundException {
        int hashPos = className.indexOf('#');
        String methodName = null;
        if (hashPos != -1) {
            methodName = className.substring(hashPos + 1);
            className = className.substring(0, hashPos);
        }
        addTestClass(className, methodName);
    }

    /**
     * Adds class to test by providing class name and method name in separate strings
     *
     * @param className class must be subclass of {@link UiAutomatorTestCase}
     * @param methodName may be null, in which case all "public void testNNN(void)" functions
     *                   will be added
     * @throws ClassNotFoundException
     */
    public void addTestClass(String className, String methodName) throws ClassNotFoundException {
        Class<?> clazz = mClassLoader.loadClass(className);
        if (methodName != null) {
            addSingleTestMethod(clazz, methodName);
        } else {
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (mFilter.accept(method)) {
                    addSingleTestMethod(clazz, method.getName());
                }
            }
        }
    }

    /**
     * Gets the list of added test cases so far
     * @return a list of {@link TestCase}
     */
    public List<TestCase> getTestCases() {
        return Collections.unmodifiableList(mTestCases);
    }

    protected void addSingleTestMethod(Class<?> clazz, String method) {
        if (!(mFilter.accept(clazz))) {
            throw new RuntimeException("Test class must be derived from UiAutomatorTestCase");
        }
        try {
            TestCase testCase = (TestCase) clazz.newInstance();
            testCase.setName(method);
            mTestCases.add(testCase);
        } catch (InstantiationException e) {
            mTestCases.add(error(clazz, "InstantiationException: could not instantiate " +
                    "test class. Class: " + clazz.getName()));
        } catch (IllegalAccessException e) {
            mTestCases.add(error(clazz, "IllegalAccessException: could not instantiate " +
                    "test class. Class: " + clazz.getName()));
        }
    }

    private UiAutomatorTestCase error(Class<?> clazz, final String message) {
        UiAutomatorTestCase warning = new UiAutomatorTestCase() {
            protected void runTest() {
                fail(message);
            }
        };

        warning.setName(clazz.getName());
        return warning;
    }

    /**
     * Determine if a class and its method should be accepted into test suite
     *
     */
    public interface TestCaseFilter {

        /**
         * Determine that based on the method signature, if it can be accepted
         * @param method
         */
        public boolean accept(Method method);

        /**
         * Determine that based on the class type, if it can be accepted
         * @param clazz
         * @return
         */
        public boolean accept(Class<?> clazz);
    }
}
