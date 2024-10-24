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

import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Order;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Scope;

import org.junit.runner.Description;
import org.junit.runners.model.TestClass;

/**
 * Provide hook points created by {@link RavenwoodAwareTestRunner}. This is a version
 * that's used on a device side test.
 *
 * All methods are no-op in real device tests.
 *
 * TODO: Use some kind of factory to provide different implementation for the device test
 * and the ravenwood test.
 */
public class RavenwoodAwareTestRunnerHook {
    private RavenwoodAwareTestRunnerHook() {
    }

    /**
     * Called before any code starts. Internally it will only initialize the environment once.
     */
    public static void performGlobalInitialization() {
    }

    /**
     * Called when a runner starts, before the inner runner gets a chance to run.
     */
    public static void onRunnerInitializing(RavenwoodAwareTestRunner runner, TestClass testClass) {
    }

    /**
     * Called when a whole test class is skipped.
     */
    public static void onClassSkipped(Description description) {
    }

    /**
     * Called before the inner runner starts.
     */
    public static void onBeforeInnerRunnerStart(
            RavenwoodAwareTestRunner runner, Description description) throws Throwable {
    }

    /**
     * Called after the inner runner finished.
     */
    public static void onAfterInnerRunnerFinished(
            RavenwoodAwareTestRunner runner, Description description) throws Throwable {
    }

    /**
     * Called before a test / class.
     *
     * Return false if it should be skipped.
     */
    public static boolean onBefore(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order) throws Throwable {
        return true;
    }

    public static void onRavenwoodRuleEnter(RavenwoodAwareTestRunner runner,
            Description description, RavenwoodRule rule) throws Throwable {
    }

    public static void onRavenwoodRuleExit(RavenwoodAwareTestRunner runner,
            Description description, RavenwoodRule rule) throws Throwable {
    }


    /**
     * Called after a test / class.
     *
     * Return false if the exception should be ignored.
     */
    public static boolean onAfter(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order, Throwable th) {
        return true;
    }

    public static boolean shouldRunClassOnRavenwood(Class<?> clazz) {
        return true;
    }
}
