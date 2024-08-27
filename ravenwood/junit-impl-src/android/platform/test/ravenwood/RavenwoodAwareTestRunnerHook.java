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

import android.os.Bundle;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Order;
import android.platform.test.ravenwood.RavenwoodAwareTestRunner.Scope;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.ravenwood.common.RavenwoodCommonUtils;

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

    private static void log(String message) {
        RavenwoodCommonUtils.log(TAG, message);
    }

    public static void onRunnerInitializing(Runner runner, TestClass testClass) {
        log("onRunnerStart: testClass=" + testClass + " runner=" + runner);

        // TODO: Move the initialization code to a better place.

        // This will let AndroidJUnit4 use the original runner.
        System.setProperty("android.junit.runner",
                "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner");
        System.setProperty(RAVENWOOD_VERSION_JAVA_SYSPROP, "1");

        // This is needed to make AndroidJUnit4ClassRunner happy.
        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);
    }

    public static boolean onBefore(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order) {
        log("onBefore: description=" + description + ", " + scope + ", " + order);

        // Class-level annotations are checked by the runner already, so we only check
        // method-level annotations here.
        if (scope == Scope.Instance && order == Order.First) {
            if (!RavenwoodRule.shouldEnableOnRavenwood(description)) {
                return false;
            }
        }
        return true;
    }

    public static void onAfter(RavenwoodAwareTestRunner runner, Description description,
            Scope scope, Order order, Throwable th) {
        log("onAfter: description=" + description + ", " + scope + ", " + order + ", " + th);
    }
}
