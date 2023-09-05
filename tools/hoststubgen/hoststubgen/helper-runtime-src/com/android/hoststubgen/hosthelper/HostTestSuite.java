/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.hosthelper;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

import java.util.regex.Pattern;

/**
 * A very simple Junit {@link TestSuite} builder that finds all classes that look like test classes.
 *
 * We use it to run ravenwood test jars from the command line.
 */
public class HostTestSuite {
    private static final String CLASS_NAME_REGEX_ENV = "HOST_TEST_CLASS_NAME_REGEX";

    /**
     * Called by junit, and return all test-looking classes as a suite.
     */
    public static TestSuite suite() {
        TestSuite suite = new TestSuite();

        final Pattern classNamePattern;
        final var filterRegex = System.getenv(CLASS_NAME_REGEX_ENV);
        if (filterRegex == null) {
            classNamePattern = Pattern.compile("");
        } else {
            classNamePattern = Pattern.compile(filterRegex);
        }
        try {
            // We use guava to list all classes.
            ClassPath cp = ClassPath.from(HostTestSuite.class.getClassLoader());

            for (var classInfo : cp.getAllClasses()) {
                Class<?> clazz = asTestClass(classInfo);
                if (clazz != null) {
                    if (classNamePattern.matcher(clazz.getSimpleName()).find()) {
                        System.out.println("Test class found " + clazz.getName());
                    } else {
                        System.out.println("Skipping test class (for $"
                                + CLASS_NAME_REGEX_ENV + "): " + clazz.getName());
                    }
                    suite.addTest(new JUnit4TestAdapter(clazz));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return suite;
    }

    /**
     * Decide whether a class looks like a test class or not, and if so, return it as a Class
     * instance.
     */
    private static Class<?> asTestClass(ClassInfo classInfo) {
        try {
            final Class<?> clazz = classInfo.load();

            // Does it extend junit.framework.TestCase?
            if (junit.framework.TestCase.class.isAssignableFrom(clazz)) {
                // Ignore classes in JUnit itself, or the android(x) test lib.
                if (classInfo.getName().startsWith("junit.")
                        || classInfo.getName().startsWith("org.junit.")
                        || classInfo.getName().startsWith("android.test.")
                        || classInfo.getName().startsWith("androidx.test.")) {
                    return null; // Ignore junit classes.
                }
                return clazz;
            }
            // Does it have any "@Test" method?
            for (var method : clazz.getMethods()) {
                for (var an : method.getAnnotations()) {
                    if (an.annotationType() == org.junit.Test.class) {
                        return clazz;
                    }
                }
            }
            return null;
        } catch (java.lang.NoClassDefFoundError e) {
            // Ignore unloadable classes.
            return null;
        }
    }
}
