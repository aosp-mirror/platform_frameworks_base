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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERSION_JAVA_SYSPROP;

import static org.junit.Assert.fail;

import android.os.Bundle;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Order;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Scope;
import android.platform.test.ravenwood.RavenwoodTestStats.Result;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runners.model.TestClass;

/**
 * Provide hook points created by {@link RavenwoodAwareTestRunner}.
 */
public class RavenwoodAwareTestRunnerHook {
    private static final String TAG = "RavenwoodAwareTestRunnerHook";

    private RavenwoodAwareTestRunnerHook() {
    }

    private static RavenwoodTestStats sStats; // lazy initialization.
    private static Description sCurrentClassDescription;

    private static RavenwoodTestStats getStats() {
        if (sStats == null) {
            // We don't want to throw in the static initializer, because tradefed may not report
            // it properly, so we initialize it here.
            sStats = new RavenwoodTestStats();
        }
        return sStats;
    }

    /**
     * Called when a runner starts, before the inner runner gets a chance to run.
     */
    public static void onRunnerInitializing(Runner runner, TestClass testClass) {
        // This log call also ensures the framework JNI is loaded.
        Log.i(TAG, "onRunnerInitializing: testClass=" + testClass + " runner=" + runner);

        // TODO: Move the initialization code to a better place.

        // This will let AndroidJUnit4 use the original runner.
        System.setProperty("android.junit.runner",
                "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner");
        System.setProperty(RAVENWOOD_VERSION_JAVA_SYSPROP, "1");


        // This is needed to make AndroidJUnit4ClassRunner happy.
        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);
    }

    /**
     * Called when a whole test class is skipped.
     */
    public static void onClassSkipped(Description description) {
        Log.i(TAG, "onClassSkipped: description=" + description);
        getStats().onClassSkipped(description);
    }

    /**
     * Called before a test / class.
     *
     * Return false if it should be skipped.
     */
    public static boolean onBefore(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order) {
        Log.i(TAG, "onBefore: description=" + description + ", " + scope + ", " + order);

        if (scope == Scope.Class && order == Order.First) {
            // Keep track of the current class.
            sCurrentClassDescription = description;
        }

        // Class-level annotations are checked by the runner already, so we only check
        // method-level annotations here.
        if (scope == Scope.Instance && order == Order.First) {
            if (!RavenwoodEnablementChecker.shouldEnableOnRavenwood(
                    description, true)) {
                getStats().onTestFinished(sCurrentClassDescription, description, Result.Skipped);
                return false;
            }
        }
        return true;
    }

    /**
     * Called after a test / class.
     *
     * Return false if the exception should be ignored.
     */
    public static boolean onAfter(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order, Throwable th) {
        Log.i(TAG, "onAfter: description=" + description + ", " + scope + ", " + order + ", " + th);

        if (scope == Scope.Instance && order == Order.First) {
            getStats().onTestFinished(sCurrentClassDescription, description,
                    th == null ? Result.Passed : Result.Failed);

        } else if (scope == Scope.Class && order == Order.Last) {
            getStats().onClassFinished(sCurrentClassDescription);
        }

        // If RUN_DISABLED_TESTS is set, and the method did _not_ throw, make it an error.
        if (RavenwoodRule.private$ravenwood().isRunningDisabledTests()
                && scope == Scope.Instance && order == Order.First) {

            boolean isTestEnabled = RavenwoodEnablementChecker.shouldEnableOnRavenwood(
                    description, false);
            if (th == null) {
                // Test passed. Is the test method supposed to be enabled?
                if (isTestEnabled) {
                    // Enabled and didn't throw, okay.
                    return true;
                } else {
                    // Disabled and didn't throw. We should report it.
                    fail("Test wasn't included under Ravenwood, but it actually "
                            + "passed under Ravenwood; consider updating annotations");
                    return true; // unreachable.
                }
            } else {
                // Test failed.
                if (isTestEnabled) {
                    // Enabled but failed. We should throw the exception.
                    return true;
                } else {
                    // Disabled and failed. Expected. Don't throw.
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Called by {@link RavenwoodAwareTestRunner} to see if it should run a test class or not.
     */
    public static boolean shouldRunClassOnRavenwood(Class<?> clazz) {
        return RavenwoodEnablementChecker.shouldRunClassOnRavenwood(clazz, true);
    }
}
