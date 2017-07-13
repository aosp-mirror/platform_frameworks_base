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

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

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
                base.evaluate();
                final Bundle stats = mRunner.getStatsToReport();
                final String summary = getSummaryString(description.getMethodName(),
                        mRunner.getStatsToLog());
                logSummary(description.getTestClass().getSimpleName(), summary,
                        mRunner.getAllDurations());
                stats.putString(Instrumentation.REPORT_KEY_STREAMRESULT, summary);
                InstrumentationRegistry.getInstrumentation().sendStatus(
                        Activity.RESULT_OK, stats);
            }
        };
    }

    private void logSummary(String tag, String summary, ArrayList<Long> durations) {
        final StringBuilder sb = new StringBuilder(summary);
        final int size = durations.size();
        for (int i = 0; i < size; ++i) {
            sb.append("\n").append(i).append("->").append(durations.get(i));
        }
        Log.d(tag, sb.toString());
    }

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
