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

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

/**
 * A simple pass-through runner that just delegates to the inner runner without doing
 * anything special (no hooks, etc.).
 *
 * This is only used when a real device-side test has Ravenizer enabled.
 */
public class RavenwoodAwareTestRunner extends RavenwoodAwareTestRunnerBase {
    private static class NopRule implements TestRule {
        @Override
        public Statement apply(Statement base, Description description) {
            return base;
        }
    }

    public static final TestRule sImplicitClassOuterRule = new NopRule();
    public static final TestRule sImplicitClassInnerRule = sImplicitClassOuterRule;
    public static final TestRule sImplicitInstOuterRule = sImplicitClassOuterRule;
    public static final TestRule sImplicitInstInnerRule = sImplicitClassOuterRule;

    private final Runner mRealRunner;

    public RavenwoodAwareTestRunner(Class<?> clazz) {
        Log.v(TAG, "RavenwoodAwareTestRunner starting for " + clazz.getCanonicalName());
        mRealRunner = instantiateRealRunner(new TestClass(clazz));
    }

    @Override
    Runner getRealRunner() {
        return mRealRunner;
    }

    @Override
    public void run(RunNotifier notifier) {
        mRealRunner.run(notifier);
    }

    static void onRavenwoodRuleEnter(Description description, RavenwoodRule rule) {
    }

    static void onRavenwoodRuleExit(Description description, RavenwoodRule rule) {
    }
}
