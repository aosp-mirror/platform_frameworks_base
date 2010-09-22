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

import android.test.ClassPathPackageInfo;
import android.test.ClassPathPackageInfoSource;
import android.test.PackageInfoSources;
import android.util.Log;
import com.android.internal.util.Predicate;
import junit.framework.TestCase;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Represents a collection of test classes present on the classpath. You can add individual classes
 * or entire packages. By default sub-packages are included recursively, but methods are
 * provided to allow for arbitrary inclusion or exclusion of sub-packages. Typically a
 * {@link TestGrouping} will have only one root package, but this is not a requirement.
 * 
 * {@hide} Not needed for 1.0 SDK.
 */
public class TestGrouping {

    private static final String LOG_TAG = "TestGrouping";

    SortedSet<Class<? extends TestCase>> testCaseClasses;

    public static final Comparator<Class<? extends TestCase>> SORT_BY_SIMPLE_NAME
            = new SortBySimpleName();

    public static final Comparator<Class<? extends TestCase>> SORT_BY_FULLY_QUALIFIED_NAME
            = new SortByFullyQualifiedName();

    protected String firstIncludedPackage = null;
    private ClassLoader classLoader;

    public TestGrouping(Comparator<Class<? extends TestCase>> comparator) {
        testCaseClasses = new TreeSet<Class<? extends TestCase>>(comparator);
    }

    /**
     * @return A list of all tests in the package, including small, medium, large,
     *         flaky, and suppressed tests. Includes sub-packages recursively.
     */
    public List<TestMethod> getTests() {
        List<TestMethod> testMethods = new ArrayList<TestMethod>();
        for (Class<? extends TestCase> testCase : testCaseClasses) {
            for (Method testMethod : getTestMethods(testCase)) {
                testMethods.add(new TestMethod(testMethod, testCase));
            }
        }
        return testMethods;
    }

    protected List<Method> getTestMethods(Class<? extends TestCase> testCaseClass) {
        List<Method> methods = Arrays.asList(testCaseClass.getMethods());
        return select(methods, new TestMethodPredicate());
    }

    SortedSet<Class<? extends TestCase>> getTestCaseClasses() {
        return testCaseClasses;
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TestGrouping other = (TestGrouping) o;
        if (!this.testCaseClasses.equals(other.testCaseClasses)) {
            return false;
        }
        return this.testCaseClasses.comparator().equals(other.testCaseClasses.comparator());
    }

    public int hashCode() {
        return testCaseClasses.hashCode();
    }

    /**
     * Include all tests in the given packages and all their sub-packages, unless otherwise
     * specified. Each of the given packages must contain at least one test class, either directly
     * or in a sub-package.
     *
     * @param packageNames Names of packages to add.
     * @return The {@link TestGrouping} for method chaining.
     */
    public TestGrouping addPackagesRecursive(String... packageNames) {
        for (String packageName : packageNames) {
            List<Class<? extends TestCase>> addedClasses = testCaseClassesInPackage(packageName);
            if (addedClasses.isEmpty()) {
                Log.w(LOG_TAG, "Invalid Package: '" + packageName
                        + "' could not be found or has no tests");
            }
            testCaseClasses.addAll(addedClasses);
            if (firstIncludedPackage == null) {
                firstIncludedPackage = packageName;
            }
        }
        return this;
    }

    /**
     * Exclude all tests in the given packages and all their sub-packages, unless otherwise
     * specified.
     *
     * @param packageNames Names of packages to remove.
     * @return The {@link TestGrouping} for method chaining.
     */
    public TestGrouping removePackagesRecursive(String... packageNames) {
        for (String packageName : packageNames) {
            testCaseClasses.removeAll(testCaseClassesInPackage(packageName));
        }
        return this;
    }

    /**
     * @return The first package name passed to {@link #addPackagesRecursive(String[])}, or null
     *         if that method was never called.
     */
    public String getFirstIncludedPackage() {
        return firstIncludedPackage;
    }

    private List<Class<? extends TestCase>> testCaseClassesInPackage(String packageName) {
        ClassPathPackageInfoSource source = PackageInfoSources.forClassPath(classLoader);
        ClassPathPackageInfo packageInfo = source.getPackageInfo(packageName);

        return selectTestClasses(packageInfo.getTopLevelClassesRecursive());
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends TestCase>> selectTestClasses(Set<Class<?>> allClasses) {
        List<Class<? extends TestCase>> testClasses = new ArrayList<Class<? extends TestCase>>();
        for (Class<?> testClass : select(allClasses,
                new TestCasePredicate())) {
            testClasses.add((Class<? extends TestCase>) testClass);
        }
        return testClasses;
    }

    private <T> List<T> select(Collection<T> items, Predicate<T> predicate) {
        ArrayList<T> selectedItems = new ArrayList<T>();
        for (T item : items) {
            if (predicate.apply(item)) {
                selectedItems.add(item);
            }
        }
        return selectedItems;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Sort classes by their simple names (i.e. without the package prefix), using
     * their packages to sort classes with the same name.
     */
    private static class SortBySimpleName
            implements Comparator<Class<? extends TestCase>>, Serializable {

        public int compare(Class<? extends TestCase> class1,
                Class<? extends TestCase> class2) {
            int result = class1.getSimpleName().compareTo(class2.getSimpleName());
            if (result != 0) {
                return result;
            }
            return class1.getName().compareTo(class2.getName());
        }
    }

    /**
     * Sort classes by their fully qualified names (i.e. with the package
     * prefix).
     */
    private static class SortByFullyQualifiedName
            implements Comparator<Class<? extends TestCase>>, Serializable {

        public int compare(Class<? extends TestCase> class1,
                Class<? extends TestCase> class2) {
            return class1.getName().compareTo(class2.getName());
        }
    }

    private static class TestCasePredicate implements Predicate<Class<?>> {

        public boolean apply(Class aClass) {
            int modifiers = ((Class<?>) aClass).getModifiers();
            return TestCase.class.isAssignableFrom((Class<?>) aClass)
                    && Modifier.isPublic(modifiers)
                    && !Modifier.isAbstract(modifiers)
                    && hasValidConstructor((Class<?>) aClass);
        }

        @SuppressWarnings("unchecked")
        private boolean hasValidConstructor(java.lang.Class<?> aClass) {
            // The cast below is not necessary with the Java 5 compiler, but necessary with the Java 6 compiler,
            // where the return type of Class.getDeclaredConstructors() was changed
            // from Constructor<T>[] to Constructor<?>[]
            Constructor<? extends TestCase>[] constructors
                    = (Constructor<? extends TestCase>[]) aClass.getConstructors();
            for (Constructor<? extends TestCase> constructor : constructors) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    java.lang.Class[] parameterTypes = constructor.getParameterTypes();
                    if (parameterTypes.length == 0 ||
                            (parameterTypes.length == 1 && parameterTypes[0] == String.class)) {
                        return true;
                    }
                }
            }
            Log.i(LOG_TAG, String.format(
                    "TestCase class %s is missing a public constructor with no parameters " +
                    "or a single String parameter - skipping",
                    aClass.getName()));
            return false;
        }
    }

    private static class TestMethodPredicate implements Predicate<Method> {

        public boolean apply(Method method) {
            return ((method.getParameterTypes().length == 0) &&
                    (method.getName().startsWith("test")) &&
                    (method.getReturnType().getSimpleName().equals("void")));
        }
    }
}
