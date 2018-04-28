/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.support.test.internal.runner.ClassPathScanner;
import android.support.test.internal.runner.ClassPathScanner.ChainedClassNameFilter;
import android.support.test.internal.runner.ClassPathScanner.ExternalClassNameFilter;
import android.testing.AndroidTestingRunner;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * This is named AAAPlusPlusVerifySysuiRequiredTestPropertiesTest for two reasons.
 * a) Its so awesome it deserves an AAA++
 * b) It should run first to draw attention to itself.
 *
 * For trues though: this test verifies that all the sysui tests extend the right classes.
 * This matters because including tests with different context implementations in the same
 * test suite causes errors, such as the incorrect settings provider being cached.
 * For an example, see {@link com.android.systemui.DependencyTest}.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class AAAPlusPlusVerifySysuiRequiredTestPropertiesTest extends SysuiTestCase {

    private static final String TAG = "AAA++VerifyTest";

    private static final Class[] BASE_CLS_WHITELIST = {
            SysuiTestCase.class,
            SysuiBaseFragmentTest.class,
    };

    private static final Class[] SUPPORTED_SIZES = {
            SmallTest.class,
            MediumTest.class,
            LargeTest.class,
            android.test.suitebuilder.annotation.SmallTest.class,
            android.test.suitebuilder.annotation.MediumTest.class,
            android.test.suitebuilder.annotation.LargeTest.class,
    };

    @Test
    public void testAllClassInheritance() throws Throwable {
        ArrayList<String> fails = new ArrayList<>();
        for (String className : getClassNamesFromClassPath()) {
            Class<?> cls = Class.forName(className, false, this.getClass().getClassLoader());
            if (!isTestClass(cls)) continue;

            boolean hasParent = false;
            for (Class<?> parent : BASE_CLS_WHITELIST) {
                if (parent.isAssignableFrom(cls)) {
                    hasParent = true;
                    break;
                }
            }
            boolean hasSize = hasSize(cls);
            if (!hasSize) {
                fails.add(cls.getName() + " does not have size annotation, such as @SmallTest");
            }
            if (!hasParent) {
                fails.add(cls.getName() + " does not extend any of " + getClsStr());
            }
        }

        assertThat("All sysui test classes must have size and extend one of " + getClsStr(),
                fails, is(empty()));
    }

    private boolean hasSize(Class<?> cls) {
        for (int i = 0; i < SUPPORTED_SIZES.length; i++) {
            if (cls.getDeclaredAnnotation(SUPPORTED_SIZES[i]) != null) return true;
        }
        return false;
    }

    private Collection<String> getClassNamesFromClassPath() {
        ClassPathScanner scanner = new ClassPathScanner(mContext.getPackageCodePath());

        ChainedClassNameFilter filter = new ChainedClassNameFilter();

        filter.add(new ExternalClassNameFilter());
        filter.add(s -> s.startsWith("com.android.systemui")
                || s.startsWith("com.android.keyguard"));
        try {
            return scanner.getClassPathEntries(filter);
        } catch (IOException e) {
            Log.e(TAG, "Failed to scan classes", e);
        }
        return Collections.emptyList();
    }

    private String getClsStr() {
        return TextUtils.join(",", Arrays.asList(BASE_CLS_WHITELIST)
                .stream().map(cls -> cls.getSimpleName()).toArray());
    }

    /**
     * Determines if given class is a valid test class.
     *
     * @param loadedClass
     * @return <code>true</code> if loadedClass is a test
     */
    private boolean isTestClass(Class<?> loadedClass) {
        try {
            if (Modifier.isAbstract(loadedClass.getModifiers())) {
                logDebug(String.format("Skipping abstract class %s: not a test",
                        loadedClass.getName()));
                return false;
            }
            // TODO: try to find upstream junit calls to replace these checks
            if (junit.framework.Test.class.isAssignableFrom(loadedClass)) {
                // ensure that if a TestCase, it has at least one test method otherwise
                // TestSuite will throw error
                if (junit.framework.TestCase.class.isAssignableFrom(loadedClass)) {
                    return hasJUnit3TestMethod(loadedClass);
                }
                return true;
            }
            // TODO: look for a 'suite' method?
            if (loadedClass.isAnnotationPresent(org.junit.runner.RunWith.class)) {
                return true;
            }
            for (Method testMethod : loadedClass.getMethods()) {
                if (testMethod.isAnnotationPresent(org.junit.Test.class)) {
                    return true;
                }
            }
            logDebug(String.format("Skipping class %s: not a test", loadedClass.getName()));
            return false;
        } catch (Exception e) {
            // Defensively catch exceptions - Will throw runtime exception if it cannot load methods.
            // For earlier versions of Android (Pre-ICS), Dalvik might try to initialize a class
            // during getMethods(), fail to do so, hide the error and throw a NoSuchMethodException.
            // Since the java.lang.Class.getMethods does not declare such an exception, resort to a
            // generic catch all.
            // For ICS+, Dalvik will throw a NoClassDefFoundException.
            Log.w(TAG, String.format("%s in isTestClass for %s", e.toString(),
                    loadedClass.getName()));
            return false;
        } catch (Error e) {
            // defensively catch Errors too
            Log.w(TAG, String.format("%s in isTestClass for %s", e.toString(),
                    loadedClass.getName()));
            return false;
        }
    }

    private boolean hasJUnit3TestMethod(Class<?> loadedClass) {
        for (Method testMethod : loadedClass.getMethods()) {
            if (isPublicTestMethod(testMethod)) {
                return true;
            }
        }
        return false;
    }

    // copied from junit.framework.TestSuite
    private boolean isPublicTestMethod(Method m) {
        return isTestMethod(m) && Modifier.isPublic(m.getModifiers());
    }

    // copied from junit.framework.TestSuite
    private boolean isTestMethod(Method m) {
        return m.getParameterTypes().length == 0 && m.getName().startsWith("test")
                && m.getReturnType().equals(Void.TYPE);
    }

    /**
     * Utility method for logging debug messages. Only actually logs a message if TAG is marked
     * as loggable to limit log spam during normal use.
     */
    private void logDebug(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, msg);
        }
    }
}
