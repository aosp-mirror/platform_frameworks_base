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
import org.junit.runner.Runner;
import org.junit.runners.model.TestClass;

/**
 * Provide hook points created by {@link RavenwoodAwareTestRunner}.
 */
public class RavenwoodAwareTestRunnerHook {
    private RavenwoodAwareTestRunnerHook() {
    }

    /**
     * Called when a runner starts, befre the inner runner gets a chance to run.
     */
    public static void onRunnerInitializing(Runner runner, TestClass testClass) {
        // No-op on a real device.
    }

    public static boolean onBefore(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order) {
        // No-op on a real device.
        return true;
    }

    public static void onAfter(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order, Throwable th) {
        // No-op on a real device.
    }
}
