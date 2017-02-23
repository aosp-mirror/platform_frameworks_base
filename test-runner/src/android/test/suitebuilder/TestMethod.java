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

import junit.framework.TestCase;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Represents a test to be run. Can be constructed without instantiating the TestCase or even
 * loading the class.
 *
 * @deprecated New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class TestMethod {

    private final String enclosingClassname;
    private final String testMethodName;
    private final Class<? extends TestCase> enclosingClass;

    public TestMethod(Method method, Class<? extends TestCase> enclosingClass) {
        this(method.getName(), enclosingClass);
    }

    public TestMethod(String methodName, Class<? extends TestCase> enclosingClass) {
        this.enclosingClass = enclosingClass;
        this.enclosingClassname = enclosingClass.getName();
        this.testMethodName = methodName;
    }
    
    public TestMethod(TestCase testCase) {
        this(testCase.getName(), testCase.getClass());
    }

    public String getName() {
        return testMethodName;
    }

    public String getEnclosingClassname() {
        return enclosingClassname;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        try {
            return getEnclosingClass().getMethod(getName()).getAnnotation(annotationClass);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Class<? extends TestCase> getEnclosingClass() {
        return enclosingClass;
    }

    public TestCase createTest()
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return instantiateTest(enclosingClass, testMethodName);
    }

    @SuppressWarnings("unchecked")
    private TestCase instantiateTest(Class testCaseClass, String testName)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        Constructor[] constructors = testCaseClass.getConstructors();

        if (constructors.length == 0) {
            return instantiateTest(testCaseClass.getSuperclass(), testName);
        } else {
            for (Constructor constructor : constructors) {
                Class[] params = constructor.getParameterTypes();
                if (noargsConstructor(params)) {
                    TestCase test = ((Constructor<? extends TestCase>) constructor).newInstance();
                    // JUnit will run just the one test if you call
                    // {@link TestCase#setName(String)}
                    test.setName(testName);
                    return test;
                } else if (singleStringConstructor(params)) {
                    return ((Constructor<? extends TestCase>) constructor)
                            .newInstance(testName);
                }
            }
        }
        throw new RuntimeException("Unable to locate a constructor for "
                + testCaseClass.getName());
    }

    private boolean singleStringConstructor(Class[] params) {
        return (params.length == 1) && (params[0].equals(String.class));
    }

    private boolean noargsConstructor(Class[] params) {
        return params.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TestMethod that = (TestMethod) o;

        if (enclosingClassname != null
                ? !enclosingClassname.equals(that.enclosingClassname)
                : that.enclosingClassname != null) {
            return false;
        }
        if (testMethodName != null
                ? !testMethodName.equals(that.testMethodName)
                : that.testMethodName != null) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result;
        result = (enclosingClassname != null ? enclosingClassname.hashCode() : 0);
        result = 31 * result + (testMethodName != null ? testMethodName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return enclosingClassname + "." + testMethodName;
    }
}
