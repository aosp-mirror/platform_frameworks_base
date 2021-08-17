/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.multiuser;

import static android.multiuser.BenchmarkResults.DECLARED_VALUE_IF_ERROR_MS;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;

public class BenchmarkResultsReporter implements TestRule {
    private final BenchmarkRunner mRunner;

    public BenchmarkResultsReporter(BenchmarkRunner benchmarkRunner) {
        mRunner = benchmarkRunner;
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                final String tag = description.getTestClass().getSimpleName();
                final String methodName = description.getMethodName();
                Throwable error = null;

                try {
                    base.evaluate();
                    error = mRunner.getErrorOrNull();
                } catch (Exception e) {
                    error = e;
                }

                if (error != null) {
                    Log.e(tag, "Test " + methodName + " failed.", error);
                    Log.d(tag, "Logcat displays the results ignoring the fact that it failed;\n"
                            + "however, fake results of " + DECLARED_VALUE_IF_ERROR_MS + "ms "
                            + "will be reported to the instrumentation caller to signify failure.");
                }

                final String summary = getSummaryString(methodName, mRunner.getStatsToLog());
                logSummary(tag, summary, mRunner.getAllDurations());

                Bundle stats;
                if (error == null) {
                    stats = mRunner.getStatsToReport();
                    stats.putString(Instrumentation.REPORT_KEY_STREAMRESULT, summary);
                } else {
                    stats = BenchmarkResults.getFailedStatsToReport();
                    final String failSummary = getSummaryString(methodName,
                            BenchmarkResults.getFailedStatsToLog());
                    stats.putString(Instrumentation.REPORT_KEY_STREAMRESULT, failSummary);
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, stats);

                if (error != null) {
                    throw error;
                }
            }
        };
    }

    /**
     * Prints, for example:
     *  UserLifecycleTests: (summary string)
     *  UserLifecycleTests: 1->101
     *  UserLifecycleTests: 2->102
     *  UserLifecycleTests: 3->103
     *  UserLifecycleTests: 4->102
     */
    private void logSummary(String tag, String summary, ArrayList<Long> durations) {
        final StringBuilder sb = new StringBuilder(summary);
        final int size = durations.size();
        for (int i = 0; i < size; ++i) {
            sb.append("\n").append(i+1).append("->").append(durations.get(i));
        }
        Log.d(tag, sb.toString());
    }

    /**
     * For example:
     *  testName
     *  Sigma (ms): 1
     *  Mean (ms): 2
     *  Median (ms): 3
     */
    private String getSummaryString(String testName, Bundle stats) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n\n").append(getKey(testName));
        for (String key : stats.keySet()) {
            sb.append("\n").append(key).append(": ").append(stats.get(key));
        }
        return sb.toString();
    }

    private String getKey(String testName) {
        return testName.replaceAll("Perf$", "");
    }
}
