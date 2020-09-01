/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.perftests.utils;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Use this rule to make sure we report the status after the test success.
 *
 * <code>
 *
 * @Rule public PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();
 * @Test public void functionName() {
 *     ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
 *
 *     int[] src = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
 *     long elapsedTime = 0;
 *     while (state.keepRunning(elapsedTime)) {
 *         long startTime = System.nanoTime();
 *         int[] dest = new int[src.length];
 *         System.arraycopy(src, 0, dest, 0, src.length);
 *         elapsedTime = System.nanoTime() - startTime;
 *     }
 * }
 * </code>
 *
 * When test succeeded, the status report will use the key as
 * "functionName_*"
 */

public class PerfManualStatusReporter implements TestRule {
    private final ManualBenchmarkState mState;

    public PerfManualStatusReporter() {
        mState = new ManualBenchmarkState();
    }

    public ManualBenchmarkState getBenchmarkState() {
        return mState;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        mState.configure(description.getAnnotation(ManualBenchmarkState.ManualBenchmarkTest.class));

        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                base.evaluate();

                mState.sendFullStatusReport(getInstrumentation(), description.getMethodName());
            }
        };
    }
}
