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

import static org.junit.Assert.fail;

import android.os.Bundle;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Order;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Scope;
import android.platform.test.ravenwood.RavenwoodTestStats.Result;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runners.model.TestClass;

/**
 * Provide hook points created by {@link RavenwoodAwareTestRunner}.
 *
 * States are associated with each {@link RavenwoodAwareTestRunner} are stored in
 * {@link RavenwoodRunnerState}, rather than as members of {@link RavenwoodAwareTestRunner}.
 * See its javadoc for the reasons.
 *
 * All methods in this class must be called from the test main thread.
 */
public class RavenwoodAwareTestRunnerHook {
    private static final String TAG = RavenwoodAwareTestRunner.TAG;

    private RavenwoodAwareTestRunnerHook() {
    }

    /**
     * Called before any code starts. Internally it will only initialize the environment once.
     */
    public static void performGlobalInitialization() {
        RavenwoodRuntimeEnvironmentController.globalInitOnce();
    }

    /**
     * Called when a runner starts, before the inner runner gets a chance to run.
     */
    public static void onRunnerInitializing(RavenwoodAwareTestRunner runner, TestClass testClass) {
        Log.i(TAG, "onRunnerInitializing: testClass=" + testClass.getJavaClass()
                + " runner=" + runner);

        // This is needed to make AndroidJUnit4ClassRunner happy.
        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);
    }

    /**
     * Called when a whole test class is skipped.
     */
    public static void onClassSkipped(Description description) {
        Log.i(TAG, "onClassSkipped: description=" + description);
        RavenwoodTestStats.getInstance().onClassSkipped(description);
    }

    /**
     * Called before the inner runner starts.
     */
    public static void onBeforeInnerRunnerStart(
            RavenwoodAwareTestRunner runner, Description description) throws Throwable {
        Log.v(TAG, "onBeforeInnerRunnerStart: description=" + description);

        // Prepare the environment before the inner runner starts.
        runner.mState.enterTestClass(description);
    }

    /**
     * Called after the inner runner finished.
     */
    public static void onAfterInnerRunnerFinished(
            RavenwoodAwareTestRunner runner, Description description) throws Throwable {
        Log.v(TAG, "onAfterInnerRunnerFinished: description=" + description);

        RavenwoodTestStats.getInstance().onClassFinished(description);
        runner.mState.exitTestClass();
    }

    /**
     * Called before a test / class.
     *
     * Return false if it should be skipped.
     */
    public static boolean onBefore(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order) throws Throwable {
        Log.v(TAG, "onBefore: description=" + description + ", " + scope + ", " + order);

        if (scope == Scope.Instance && order == Order.Outer) {
            // Start of a test method.
            runner.mState.enterTestMethod(description);
        }

        final var classDescription = runner.mState.getClassDescription();

        // Class-level annotations are checked by the runner already, so we only check
        // method-level annotations here.
        if (scope == Scope.Instance && order == Order.Outer) {
            if (!RavenwoodEnablementChecker.shouldEnableOnRavenwood(
                    description, true)) {
                RavenwoodTestStats.getInstance().onTestFinished(
                        classDescription, description, Result.Skipped);
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
        Log.v(TAG, "onAfter: description=" + description + ", " + scope + ", " + order + ", " + th);

        final var classDescription = runner.mState.getClassDescription();

        if (scope == Scope.Instance && order == Order.Outer) {
            // End of a test method.
            runner.mState.exitTestMethod();
            RavenwoodTestStats.getInstance().onTestFinished(classDescription, description,
                    th == null ? Result.Passed : Result.Failed);
        }

        // If RUN_DISABLED_TESTS is set, and the method did _not_ throw, make it an error.
        if (RavenwoodRule.private$ravenwood().isRunningDisabledTests()
                && scope == Scope.Instance && order == Order.Outer) {

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

    /**
     * Called by RavenwoodRule.
     */
    public static void onRavenwoodRuleEnter(RavenwoodAwareTestRunner runner,
            Description description, RavenwoodRule rule) throws Throwable {
        Log.v(TAG, "onRavenwoodRuleEnter: description=" + description);

        runner.mState.enterRavenwoodRule(rule);
    }


    /**
     * Called by RavenwoodRule.
     */
    public static void onRavenwoodRuleExit(RavenwoodAwareTestRunner runner,
            Description description, RavenwoodRule rule) throws Throwable {
        Log.v(TAG, "onRavenwoodRuleExit: description=" + description);

        runner.mState.exitRavenwoodRule(rule);
    }
}
