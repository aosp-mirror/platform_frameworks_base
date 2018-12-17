/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Use this rule to make sure we report the status after the test success.
 *
 * <code>
 *
 * @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
 * @Test public void functionName() {
 *     ...
 *     BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
 *     while (state.keepRunning()) {
 *         // DO YOUR TEST HERE!
 *     }
 *     ...
 * }
 * </code>
 *
 * When test succeeded, the status report will use the key as
 * "functionName[optional subTestName]_*"
 *
 * Notice that optional subTestName can't be just numbers, that means each sub test needs to have a
 * name when using parameterization.
 */

public class PerfStatusReporter implements TestRule {
    private static final String TAG = "PerfStatusReporter";
    private final BenchmarkState mState = new BenchmarkState();

    public BenchmarkState getBenchmarkState() {
        return mState;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                String invokeMethodName = description.getMethodName();
                Log.i(TAG, "Running " + description.getClassName() + "#" + invokeMethodName);

                // validate and simplify the function name.
                // First, remove the "test" prefix which normally comes from CTS test.
                // Then make sure the [subTestName] is valid, not just numbers like [0].
                if (invokeMethodName.startsWith("test")) {
                    assertTrue("The test name " + invokeMethodName + " is too short",
                            invokeMethodName.length() > 5);
                    invokeMethodName = invokeMethodName.substring(4, 5).toLowerCase()
                            + invokeMethodName.substring(5);
                }

                int index = invokeMethodName.lastIndexOf('[');
                if (index > 0) {
                    boolean allDigits = true;
                    for (int i = index + 1; i < invokeMethodName.length() - 1; i++) {
                        if (!Character.isDigit(invokeMethodName.charAt(i))) {
                            allDigits = false;
                            break;
                        }
                    }
                    assertFalse("The name in [] can't contain only digits for " + invokeMethodName,
                            allDigits);
                }

                base.evaluate();

                mState.sendFullStatusReport(InstrumentationRegistry.getInstrumentation(),
                        invokeMethodName);
            }
        };
    }
}
