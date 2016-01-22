/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.test;

import android.util.Log;
import junit.framework.Test;
import junit.framework.TestListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prints the test progress to stdout. Android includes a default
 * implementation and calls these methods to print out test progress; you
 * probably will not need to create or extend this class or call its methods manually.
 * See the full {@link android.test} package description for information about
 * getting test results.
 *
 * {@hide} Not needed for 1.0 SDK.
 */
@Deprecated
public class TestPrinter implements TestRunner.Listener, TestListener {

    private String mTag;
    private boolean mOnlyFailures;
    private Set<String> mFailedTests = new HashSet<String>();


    public TestPrinter(String tag, boolean onlyFailures) {
        mTag = tag;
        mOnlyFailures = onlyFailures;
    }

    public void started(String className) {
        if (!mOnlyFailures) {
            Log.i(mTag, "started: " + className);
        }
    }

    public void finished(String className) {
        if (!mOnlyFailures) {
            Log.i(mTag, "finished: " + className);
        }
    }

    public void performance(String className,
            long itemTimeNS, int iterations,
            List<TestRunner.IntermediateTime> intermediates) {
        Log.i(mTag, "perf: " + className + " = " + itemTimeNS + "ns/op (done "
                + iterations + " times)");
        if (intermediates != null && intermediates.size() > 0) {
            int N = intermediates.size();
            for (int i = 0; i < N; i++) {
                TestRunner.IntermediateTime time = intermediates.get(i);
                Log.i(mTag, "  intermediate: " + time.name + " = "
                        + time.timeInNS + "ns");
            }
        }
    }

    public void passed(String className) {
        if (!mOnlyFailures) {
            Log.i(mTag, "passed: " + className);
        }
    }

    public void failed(String className, Throwable exception) {
        Log.i(mTag, "failed: " + className);
        Log.i(mTag, "----- begin exception -----");
        Log.i(mTag, "", exception);
        Log.i(mTag, "----- end exception -----");
    }

    private void failed(Test test, Throwable t) {
        mFailedTests.add(test.toString());
        failed(test.toString(), t);
    }

    public void addError(Test test, Throwable t) {
        failed(test, t);
    }

    public void addFailure(Test test, junit.framework.AssertionFailedError t) {
        failed(test, t);
    }

    public void endTest(Test test) {
        finished(test.toString());
        if (!mFailedTests.contains(test.toString())) {
            passed(test.toString());
        }
        mFailedTests.remove(test.toString());
    }

    public void startTest(Test test) {
        started(test.toString());
    }
}
