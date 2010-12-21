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

import android.content.Context;
import android.test.AndroidTestRunner;
import android.test.TestCaseUtil;
import android.util.Log;
import com.android.internal.util.Predicate;
import com.google.android.collect.Lists;
import static android.test.suitebuilder.TestGrouping.SORT_BY_FULLY_QUALIFIED_NAME;
import static android.test.suitebuilder.TestPredicates.REJECT_SUPPRESSED;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.lang.reflect.InvocationTargetException;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Build suites based on a combination of included packages, excluded packages,
 * and predicates that must be satisfied.
 */
public class TestSuiteBuilder {

    private Context context;
    private final TestGrouping testGrouping = new TestGrouping(SORT_BY_FULLY_QUALIFIED_NAME);
    private final Set<Predicate<TestMethod>> predicates = new HashSet<Predicate<TestMethod>>();
    private List<TestCase> testCases;
    private TestSuite rootSuite;
    private TestSuite suiteForCurrentClass;
    private String currentClassname;
    private String suiteName;

    /**
     * The given name is automatically prefixed with the package containing the tests to be run.
     * If more than one package is specified, the first is used.
     *
     * @param clazz Use the class from your .apk. Use the class name for the test suite name.
     *              Use the class' classloader in order to load classes for testing.
     *              This is needed when running in the emulator.
     */
    public TestSuiteBuilder(Class clazz) {
        this(clazz.getName(), clazz.getClassLoader());
    }

    public TestSuiteBuilder(String name, ClassLoader classLoader) {
        this.suiteName = name;
        this.testGrouping.setClassLoader(classLoader);
        this.testCases = Lists.newArrayList();
        addRequirements(REJECT_SUPPRESSED);
    }

    /** @hide pending API Council approval */
    public TestSuiteBuilder addTestClassByName(String testClassName, String testMethodName,
            Context context) {

        AndroidTestRunner atr = new AndroidTestRunner();
        atr.setContext(context);
        atr.setTestClassName(testClassName, testMethodName);

        this.testCases.addAll(atr.getTestCases());
        return this;
    }

    /** @hide pending API Council approval */
    public TestSuiteBuilder addTestSuite(TestSuite testSuite) {
        for (TestCase testCase : (List<TestCase>) TestCaseUtil.getTests(testSuite, true)) {
            this.testCases.add(testCase);
        }
        return this;
    }

    /**
     * Include all tests that satisfy the requirements in the given packages and all sub-packages,
     * unless otherwise specified.
     *
     * @param packageNames Names of packages to add.
     * @return The builder for method chaining.
     */
    public TestSuiteBuilder includePackages(String... packageNames) {
        testGrouping.addPackagesRecursive(packageNames);
        return this;
    }

    /**
     * Exclude all tests in the given packages and all sub-packages, unless otherwise specified.
     *
     * @param packageNames Names of packages to remove.
     * @return The builder for method chaining.
     */
    public TestSuiteBuilder excludePackages(String... packageNames) {
        testGrouping.removePackagesRecursive(packageNames);
        return this;
    }

    /**
     * Exclude tests that fail to satisfy all of the given predicates.
     *
     * @param predicates Predicates to add to the list of requirements.
     * @return The builder for method chaining.
     */
    public TestSuiteBuilder addRequirements(List<Predicate<TestMethod>> predicates) {
        this.predicates.addAll(predicates);
        return this;
    }

    /**
     * Include all junit tests that satisfy the requirements in the calling class' package and all
     * sub-packages.
     *
     * @return The builder for method chaining.
     */
    public final TestSuiteBuilder includeAllPackagesUnderHere() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();

        String callingClassName = null;
        String thisClassName = TestSuiteBuilder.class.getName();

        // We want to get the package of this method's calling class. This method's calling class
        // should be one level below this class in the stack trace.
        for (int i = 0; i < stackTraceElements.length; i++) {
            StackTraceElement element = stackTraceElements[i];
            if (thisClassName.equals(element.getClassName())
                    && "includeAllPackagesUnderHere".equals(element.getMethodName())) {
                // We've found this class in the call stack. The calling class must be the
                // next class in the stack.
                callingClassName = stackTraceElements[i + 1].getClassName();
                break;
            }
        }

        String packageName = parsePackageNameFromClassName(callingClassName);
        return includePackages(packageName);
    }

    /**
     * Override the default name for the suite being built. This should generally be called if you
     * call {@link #addRequirements(com.android.internal.util.Predicate[])} to make it clear which
     * tests will be included. The name you specify is automatically prefixed with the package
     * containing the tests to be run. If more than one package is specified, the first is used.
     *
     * @param newSuiteName Prefix of name to give the suite being built.
     * @return The builder for method chaining.
     */
    public TestSuiteBuilder named(String newSuiteName) {
        suiteName = newSuiteName;
        return this;
    }

    /**
     * Call this method once you've configured your builder as desired.
     *
     * @return The suite containing the requested tests.
     */
    public final TestSuite build() {
        rootSuite = new TestSuite(getSuiteName());

        // Keep track of current class so we know when to create a new sub-suite.
        currentClassname = null;
        try {
            for (TestMethod test : testGrouping.getTests()) {
                if (satisfiesAllPredicates(test)) {
                    addTest(test);
                }
            }
            if (testCases.size() > 0) {
                for (TestCase testCase : testCases) {
                    if (satisfiesAllPredicates(new TestMethod(testCase))) {
                        addTest(testCase);
                    }
                }
            }
        } catch (Exception exception) {
            Log.i("TestSuiteBuilder", "Failed to create test.", exception);
            TestSuite suite = new TestSuite(getSuiteName());
            suite.addTest(new FailedToCreateTests(exception));
            return suite;
        }
        return rootSuite;
    }

    /**
     * Subclasses use this method to determine the name of the suite.
     *
     * @return The package and suite name combined.
     */
    protected String getSuiteName() {
        return suiteName;
    }

    /**
     * Exclude tests that fail to satisfy all of the given predicates. If you call this method, you
     * probably also want to call {@link #named(String)} to override the default suite name.
     *
     * @param predicates Predicates to add to the list of requirements.
     * @return The builder for method chaining.
     */
    public final TestSuiteBuilder addRequirements(Predicate<TestMethod>... predicates) {
        ArrayList<Predicate<TestMethod>> list = new ArrayList<Predicate<TestMethod>>();
        Collections.addAll(list, predicates);
        return addRequirements(list);
    }

    /**
     * A special {@link junit.framework.TestCase} used to indicate a failure during the build()
     * step.
     */
    public static class FailedToCreateTests extends TestCase {
        private final Exception exception;

        public FailedToCreateTests(Exception exception) {
            super("testSuiteConstructionFailed");
            this.exception = exception;
        }

        public void testSuiteConstructionFailed() {
            throw new RuntimeException("Exception during suite construction", exception);
        }
    }

    /**
     * @return the test package that represents the packages that were included for our test suite.
     *
     * {@hide} Not needed for 1.0 SDK.
     */
    protected TestGrouping getTestGrouping() {
        return testGrouping;
    }

    private boolean satisfiesAllPredicates(TestMethod test) {
        for (Predicate<TestMethod> predicate : predicates) {
            if (!predicate.apply(test)) {
                return false;
            }
        }
        return true;
    }

    private void addTest(TestMethod testMethod) throws Exception {
        addSuiteIfNecessary(testMethod.getEnclosingClassname());
        suiteForCurrentClass.addTest(testMethod.createTest());
    }

    private void addTest(Test test) {
        addSuiteIfNecessary(test.getClass().getName());
        suiteForCurrentClass.addTest(test);
    }

    private void addSuiteIfNecessary(String parentClassname) {
        if (!parentClassname.equals(currentClassname)) {
            currentClassname = parentClassname;
            suiteForCurrentClass = new TestSuite(parentClassname);
            rootSuite.addTest(suiteForCurrentClass);
        }
    }

    private static String parsePackageNameFromClassName(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }
}
