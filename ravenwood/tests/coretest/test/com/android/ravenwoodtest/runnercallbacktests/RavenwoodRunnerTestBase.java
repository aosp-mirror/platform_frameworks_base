/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.runnercallbacktests;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.NoRavenizer;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner;
import android.util.Log;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiConsumer;


/**
 * Base class for tests to make sure {@link RavenwoodAwareTestRunner} produces expected callbacks
 * in various situations. (most of them are error situations.)
 *
 * Subclasses must contain test classes as static inner classes with an {@link Expected} annotation.
 * This class finds them using reflections and run them one by one directly using {@link JUnitCore},
 * and check the callbacks.
 *
 * Subclasses do no need to have any test methods.
 *
 * The {@link Expected} annotation must contain the expected result as a string.
 *
 * This test abuses the fact that atest + tradefed + junit won't run nested classes automatically.
 * (So atest won't show any results directly from the nested classes.)
 *
 * The actual test method is {@link #doTest}, which is executed for each target test class, using
 * junit-params.
 */
@RunWith(JUnitParamsRunner.class)
@NoRavenizer // This class shouldn't be executed with RavenwoodAwareTestRunner.
public abstract class RavenwoodRunnerTestBase {
    private static final String TAG = "RavenwoodRunnerTestBase";

    /**
     * Annotation to specify the expected result for a class.
     */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Expected {
        String value();
    }

    /**
     * Take a multiline string, strip all of them, remove empty lines, and return it.
     */
    private static String stripMultiLines(String resultString) {
        var list = new ArrayList<String>();
        for (var line : resultString.split("\n")) {
            var s = line.strip();
            if (s.length() > 0) {
                list.add(s);
            }
        }
        return String.join("\n", list);
    }

    /**
     * Extract the expected result from @Expected.
     */
    private String getExpectedResult(Class<?> testClazz) {
        var expect = testClazz.getAnnotation(Expected.class);
        return stripMultiLines(expect.value());
    }

    /**
     * List all the nested classrs with an {@link Expected} annotation in a given class.
     */
    public Class<?>[] getTestClasses() {
        var thisClass = this.getClass();
        var ret = Arrays.stream(thisClass.getNestMembers())
                .filter((c) -> c.getAnnotation(Expected.class) != null)
                .toArray(Class[]::new);

        assertThat(ret.length).isGreaterThan(0);

        return ret;
    }

    /**
     * This is the actual test method. We use junit-params to run this method for each target
     * test class, which are returned by {@link #getTestClasses}.
     *
     * It runs each test class, and compare the result collected with
     * {@link ResultCollectingListener} to expected results (as strings).
     */
    @Test
    @Parameters(method = "getTestClasses")
    public void doTest(Class<?> testClazz) {
        doTest(testClazz, getExpectedResult(testClazz));
    }

    /**
     * Run a given test class, and compare the result collected with
     * {@link ResultCollectingListener} to expected results (as a string).
     */
    private void doTest(Class<?> testClazz, String expectedResult) {
        Log.i(TAG, "Running test for " + testClazz);
        var junitCore = new JUnitCore();

        // Create a listener.
        var listener = new ResultCollectingListener();
        junitCore.addListener(listener);

        // Set a listener to critical errors. This will also prevent
        // {@link RavenwoodAwareTestRunner} from calling System.exit() when there's
        // a critical error.
        RavenwoodAwareTestRunner.private$ravenwood().setCriticalErrorHandler(
                listener.sCriticalErrorListener);

        try {
            // Run the test class.
            junitCore.run(testClazz);
        } finally {
            // Clear the critical error listener.
            RavenwoodAwareTestRunner.private$ravenwood().setCriticalErrorHandler(null);
        }

        // Check the result.
        assertWithMessage("Failure in test class: " + testClazz.getCanonicalName() + "]")
                .that(listener.getResult())
                .isEqualTo(expectedResult);
    }

    /**
     * A JUnit RunListener that collects all the callbacks as a single string.
     */
    private static class ResultCollectingListener extends RunListener {
        private final ArrayList<String> mResult = new ArrayList<>();

        public final BiConsumer<String, Throwable> sCriticalErrorListener = (message, th) -> {
            mResult.add("criticalError: " + message + ": " + th.getMessage());
        };

        @Override
        public void testRunStarted(Description description) throws Exception {
            mResult.add("testRunStarted: " + description);
        }

        @Override
        public void testRunFinished(Result result) throws Exception {
            mResult.add("testRunFinished: "
                    + result.getRunCount() + ","
                    + result.getFailureCount() + ","
                    + result.getAssumptionFailureCount() + ","
                    + result.getIgnoreCount());
        }

        @Override
        public void testSuiteStarted(Description description) throws Exception {
            mResult.add("testSuiteStarted: " + description);
        }

        @Override
        public void testSuiteFinished(Description description) throws Exception {
            mResult.add("testSuiteFinished: " + description);
        }

        @Override
        public void testStarted(Description description) throws Exception {
            mResult.add("testStarted: " + description);
        }

        @Override
        public void testFinished(Description description) throws Exception {
            mResult.add("testFinished: " + description);
        }

        @Override
        public void testFailure(Failure failure) throws Exception {
            mResult.add("testFailure: " + failure.getException().getMessage());
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            mResult.add("testAssumptionFailure: " + failure.getException().getMessage());
        }

        @Override
        public void testIgnored(Description description) throws Exception {
            mResult.add("testIgnored: " + description);
        }

        public String getResult() {
            return String.join("\n", mResult);
        }
    }
}
