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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * JUnit filter to select tests.
 *
 * <p>This filter selects tests specified by package name, class name, and method name. With this
 * filter, the package and the class options of AndroidJUnitRunner can be superseded. Also the
 * restriction that prevents using the package and the class options can be mitigated.
 *
 * <p><b>Select out tests from Java packages:</b> this option supersedes {@code -e package} option.
 * <pre>
 * adb shell am instrument -w \
 *     -e filter com.android.test.filters.SelectTest \
 *     -e selectTest package1.,package2. \
 *     com.tests.pkg/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 * Note that the ending {@code .} in package name is mandatory.
 *
 * <p><b>Select out test classes:</b> this option supersedes {@code -e class} option.
 * <pre>
 * adb shell am instrument -w \
 *     -e filter com.android.test.filters.SelectTest      \
 *     -e selectTest package1.ClassA,package2.ClassB \
 *     com.tests.pkg/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 *
 * <p><b>Select out test methods from Java classes:</b>
 * <pre>
 * adb shell am instrument -w \
 *     -e filter com.android.test.filters.SelectTest                      \
 *     -e selectTest package1.ClassA#methodX,package2.ClassB#methodY \
 *     com.tests.pkg/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 *
 * Those options can be used simultaneously. For example
 * <pre>
 * adb shell am instrument -w \
 *     -e filter com.android.test.filters.SelectTest                        \
 *     -e selectTest package1.,package2.classA,package3.ClassB#methodZ \
 *     com.tests.pkg/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 * will select out all tests in package1, all tests in classA, and ClassB#methodZ test.
 *
 * <p>Note that when this option is specified with either {@code -e package} or {@code -e class}
 * option, filtering behaves as logically conjunction. Other options, such as {@code -e notPackage},
 * {@code -e notClass}, {@code -e annotation}, and {@code -e notAnnotation}, should work as expected
 * with this SelectTest option.
 *
 * <p>When specified with {@code -e selectTest_verbose true} option, {@link SelectTest} verbosely
 * logs to logcat while parsing {@code -e selectTest} option.
 */
public class SelectTest extends Filter {

    private static final String TAG = SelectTest.class.getSimpleName();

    @VisibleForTesting
    static final String OPTION_SELECT_TEST = "selectTest";
    @VisibleForTesting
    static final String OPTION_SELECT_TEST_VERBOSE = OPTION_SELECT_TEST + "_verbose";

    private static final String ARGUMENT_ITEM_SEPARATOR = ",";
    private static final String PACKAGE_NAME_SEPARATOR = ".";
    private static final String METHOD_SEPARATOR = "#";

    @Nullable
    private final PackageSet mPackageSet;

    /**
     * Construct {@link SelectTest} filter from instrumentation arguments in {@link Bundle}.
     *
     * @param testArgs instrumentation test arguments.
     */
    public SelectTest(@NonNull Bundle testArgs) {
        mPackageSet = parseSelectTest(testArgs);
    }

    @Override
    public boolean shouldRun(Description description) {
        if (mPackageSet == null) {
            // Accept all tests because this filter is disabled.
            return true;
        }
        String testClassName = description.getClassName();
        String testMethodName = description.getMethodName();
        return mPackageSet.accept(testClassName, testMethodName);
    }

    @Override
    public String describe() {
        return OPTION_SELECT_TEST + "=" + mPackageSet;
    }

    /**
     * Create {@link #OPTION_SELECT_TEST} argument and add it to {@code testArgs}.
     *
     * <p>This method is intended to be used at constructor of extended {@link Filter} class.
     *
     * @param testArgs instrumentation test arguments.
     * @param selectTests array of class name to be selected to run.
     * @return modified instrumentation test arguments. if {@link #OPTION_SELECT_TEST} argument
     *      already exists in {@code testArgs}, those are prepended before {@code selectTests}.
     */
    @NonNull
    protected static Bundle addSelectTest(
            @NonNull Bundle testArgs, @NonNull String... selectTests) {
        if (selectTests.length == 0) {
            return testArgs;
        }
        final List<String> selectedTestList = new ArrayList<>();
        final String selectTestArgs = testArgs.getString(OPTION_SELECT_TEST);
        if (selectTestArgs != null) {
            selectedTestList.addAll(Arrays.asList(selectTestArgs.split(ARGUMENT_ITEM_SEPARATOR)));
        }
        selectedTestList.addAll(Arrays.asList(selectTests));
        testArgs.putString(OPTION_SELECT_TEST, join(selectedTestList));
        return testArgs;
    }

    /**
     * Parse {@code -e selectTest} argument.
     * @param testArgs instrumentation test arguments.
     * @return {@link PackageSet} that will filter tests. Returns {@code null} when no
     *     {@code -e selectTest} option is specified, thus this filter gets disabled.
     */
    @Nullable
    private static PackageSet parseSelectTest(Bundle testArgs) {
        final String selectTestArgs = testArgs.getString(OPTION_SELECT_TEST);
        if (selectTestArgs == null) {
            Log.w(TAG, "Disabled because no " + OPTION_SELECT_TEST + " option specified");
            return null;
        }

        final boolean verbose = new Boolean(testArgs.getString(OPTION_SELECT_TEST_VERBOSE));
        final PackageSet packageSet = new PackageSet(verbose);
        for (String selectTestArg : selectTestArgs.split(ARGUMENT_ITEM_SEPARATOR)) {
            packageSet.add(selectTestArg);
        }
        return packageSet;
    }

    private static String getPackageName(String selectTestArg) {
        int endPackagePos = selectTestArg.lastIndexOf(PACKAGE_NAME_SEPARATOR);
        return (endPackagePos < 0) ? "" : selectTestArg.substring(0, endPackagePos);
    }

    @Nullable
    private static String getClassName(String selectTestArg) {
        if (selectTestArg.endsWith(PACKAGE_NAME_SEPARATOR)) {
            return null;
        }
        int methodSepPos = selectTestArg.indexOf(METHOD_SEPARATOR);
        return (methodSepPos < 0) ? selectTestArg : selectTestArg.substring(0, methodSepPos);
    }

    @Nullable
    private static String getMethodName(String selectTestArg) {
        int methodSepPos = selectTestArg.indexOf(METHOD_SEPARATOR);
        return (methodSepPos < 0) ? null : selectTestArg.substring(methodSepPos + 1);
    }

    /** Package level filter */
    private static class PackageSet {
        private final boolean mVerbose;
        /**
         * Java package name to {@link ClassSet} map. To represent package filtering, a map value
         * can be {@code null}.
         */
        private final Map<String, ClassSet> mClassSetMap = new LinkedHashMap<>();

        PackageSet(boolean verbose) {
            mVerbose = verbose;
        }

        void add(final String selectTestArg) {
            final String packageName = getPackageName(selectTestArg);
            final String className = getClassName(selectTestArg);

            if (className == null) {
                ClassSet classSet = mClassSetMap.put(packageName, null); // package filtering.
                if (mVerbose) {
                    logging("Select package " + selectTestArg, classSet != null,
                            "; supersede " + classSet);
                }
                return;
            }

            ClassSet classSet = mClassSetMap.get(packageName);
            if (classSet == null) {
                if (mClassSetMap.containsKey(packageName)) {
                    if (mVerbose) {
                        logging("Select package " + packageName + PACKAGE_NAME_SEPARATOR, true,
                                " ignore " + selectTestArg);
                    }
                    return;
                }
                classSet = new ClassSet(mVerbose);
                mClassSetMap.put(packageName, classSet);
            }
            classSet.add(selectTestArg);
        }

        boolean accept(String className, @Nullable String methodName) {
            String packageName = getPackageName(className);
            if (!mClassSetMap.containsKey(packageName)) {
                return false;
            }
            ClassSet classSet = mClassSetMap.get(packageName);
            return classSet == null || classSet.accept(className, methodName);
        }

        @Override
        public String toString() {
            StringJoiner joiner = new StringJoiner(ARGUMENT_ITEM_SEPARATOR);
            for (String packageName : mClassSetMap.keySet()) {
                ClassSet classSet = mClassSetMap.get(packageName);
                joiner.add(classSet == null
                        ? packageName + PACKAGE_NAME_SEPARATOR : classSet.toString());
            }
            return joiner.toString();
        }
    }

    /** Class level filter */
    private static class ClassSet {
        private final boolean mVerbose;
        /**
         * Java class name to set of method names map. To represent class filtering, a map value
         * can be {@code null}.
         */
        private final Map<String, Set<String>> mMethodSetMap = new LinkedHashMap<>();

        ClassSet(boolean verbose) {
            mVerbose = verbose;
        }

        void add(String selectTestArg) {
            final String className = getClassName(selectTestArg);
            final String methodName = getMethodName(selectTestArg);

            if (methodName == null) {
                Set<String> methodSet = mMethodSetMap.put(className, null); // class filtering.
                if (mVerbose) {
                    logging("Select class " + selectTestArg, methodSet != null,
                            "; supersede " + toString(className, methodSet));
                }
                return;
            }

            Set<String> methodSet = mMethodSetMap.get(className);
            if (methodSet == null) {
                if (mMethodSetMap.containsKey(className)) {
                    if (mVerbose) {
                        logging("Select class " + className, true, "; ignore " + selectTestArg);
                    }
                    return;
                }
                methodSet = new LinkedHashSet<>();
                mMethodSetMap.put(className, methodSet);
            }

            methodSet.add(methodName);
            if (mVerbose) {
                logging("Select method " + selectTestArg, false, null);
            }
        }

        boolean accept(String className, @Nullable String methodName) {
            if (!mMethodSetMap.containsKey(className)) {
                return false;
            }
            Set<String> methodSet = mMethodSetMap.get(className);
            return methodName == null || methodSet == null || methodSet.contains(methodName);
        }

        @Override
        public String toString() {
            StringJoiner joiner = new StringJoiner(ARGUMENT_ITEM_SEPARATOR);
            for (String className : mMethodSetMap.keySet()) {
                joiner.add(toString(className, mMethodSetMap.get(className)));
            }
            return joiner.toString();
        }

        private static String toString(String className, @Nullable Set<String> methodSet) {
            if (methodSet == null) {
                return className;
            }
            StringJoiner joiner = new StringJoiner(ARGUMENT_ITEM_SEPARATOR);
            for (String methodName : methodSet) {
                joiner.add(className + METHOD_SEPARATOR + methodName);
            }
            return joiner.toString();
        }
    }

    private static void logging(String infoLog, boolean isWarning, String warningLog) {
        if (isWarning) {
            Log.w(TAG, infoLog + warningLog);
        } else {
            Log.i(TAG, infoLog);
        }
    }

    private static String join(Collection<String> list) {
        StringJoiner joiner = new StringJoiner(ARGUMENT_ITEM_SEPARATOR);
        for (String text : list) {
            joiner.add(text);
        }
        return joiner.toString();
    }
}
