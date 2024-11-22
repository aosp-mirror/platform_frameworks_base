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

import android.util.Log;

import org.junit.runner.Description;

/**
 * Used to store various states associated with the current test runner that's inly needed
 * in junit-impl.
 *
 * We don't want to put it in junit-src to avoid having to recompile all the downstream
 * dependencies after changing this class.
 *
 * All members must be called from the runner's main thread.
 */
public final class RavenwoodRunnerState {
    private static final String TAG = "RavenwoodRunnerState";

    private final RavenwoodAwareTestRunner mRunner;

    /**
     * Ctor.
     */
    public RavenwoodRunnerState(RavenwoodAwareTestRunner runner) {
        mRunner = runner;
    }

    private Description mMethodDescription;

    public void enterTestRunner() {
        Log.i(TAG, "enterTestRunner: " + mRunner);
        RavenwoodRuntimeEnvironmentController.initForRunner();
    }

    public void enterTestClass() {
        Log.i(TAG, "enterTestClass: " + mRunner.mTestJavaClass.getName());
    }

    public void exitTestClass() {
        Log.i(TAG, "exitTestClass: " + mRunner.mTestJavaClass.getName());
        RavenwoodRuntimeEnvironmentController.exitTestClass();
    }

    public void enterTestMethod(Description description) {
        mMethodDescription = description;
        RavenwoodRuntimeEnvironmentController.initForMethod();
    }

    public void exitTestMethod() {
        mMethodDescription = null;
    }

    public void enterRavenwoodRule(RavenwoodRule rule) {
        RavenwoodRuntimeEnvironmentController.setSystemProperties(rule.mSystemProperties);
    }

    public void exitRavenwoodRule(RavenwoodRule rule) {
    }
}
