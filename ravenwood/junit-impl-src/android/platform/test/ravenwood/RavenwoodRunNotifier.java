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

import org.junit.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.model.MultipleFailureException;

import java.util.ArrayList;
import java.util.Stack;

/**
 * A run notifier that wraps another notifier and provides the following features:
 * - Handle a failure that happened before testStarted and testEnded (typically that means
 *   it's from @BeforeClass or @AfterClass, or a @ClassRule) and deliver it as if
 *   individual tests in the class reported it. This is for b/364395552.
 *
 * - Logging.
 */
class RavenwoodRunNotifier extends RunNotifier {
    private final RunNotifier mRealNotifier;

    private final Stack<Description> mSuiteStack = new Stack<>();
    private Description mCurrentSuite = null;
    private final ArrayList<Throwable> mOutOfTestFailures = new ArrayList<>();

    private boolean mBeforeTest = true;
    private boolean mAfterTest = false;

    RavenwoodRunNotifier(RunNotifier realNotifier) {
        mRealNotifier = realNotifier;
    }

    private boolean isInTest() {
        return !mBeforeTest && !mAfterTest;
    }

    @Override
    public void addListener(RunListener listener) {
        mRealNotifier.addListener(listener);
    }

    @Override
    public void removeListener(RunListener listener) {
        mRealNotifier.removeListener(listener);
    }

    @Override
    public void addFirstListener(RunListener listener) {
        mRealNotifier.addFirstListener(listener);
    }

    @Override
    public void fireTestRunStarted(Description description) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testRunStarted: " + description);
        mRealNotifier.fireTestRunStarted(description);
    }

    @Override
    public void fireTestRunFinished(Result result) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testRunFinished: "
                + result.getRunCount() + ","
                + result.getFailureCount() + ","
                + result.getAssumptionFailureCount() + ","
                + result.getIgnoreCount());
        mRealNotifier.fireTestRunFinished(result);
    }

    @Override
    public void fireTestSuiteStarted(Description description) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testSuiteStarted: " + description);
        mRealNotifier.fireTestSuiteStarted(description);

        mBeforeTest = true;
        mAfterTest = false;

        // Keep track of the current suite, needed if the outer test is a Suite,
        // in which case its children are test classes. (not test methods)
        mCurrentSuite = description;
        mSuiteStack.push(description);

        mOutOfTestFailures.clear();
    }

    @Override
    public void fireTestSuiteFinished(Description description) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testSuiteFinished: " + description);
        mRealNotifier.fireTestSuiteFinished(description);

        maybeHandleOutOfTestFailures();

        mBeforeTest = true;
        mAfterTest = false;

        // Restore the upper suite.
        mSuiteStack.pop();
        mCurrentSuite = mSuiteStack.size() == 0 ? null : mSuiteStack.peek();
    }

    @Override
    public void fireTestStarted(Description description) throws StoppedByUserException {
        Log.i(RavenwoodAwareTestRunner.TAG, "testStarted: " + description);
        mRealNotifier.fireTestStarted(description);

        mAfterTest = false;
        mBeforeTest = false;
    }

    @Override
    public void fireTestFailure(Failure failure) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testFailure: " + failure);

        if (isInTest()) {
            mRealNotifier.fireTestFailure(failure);
        } else {
            mOutOfTestFailures.add(failure.getException());
        }
    }

    @Override
    public void fireTestAssumptionFailed(Failure failure) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testAssumptionFailed: " + failure);

        if (isInTest()) {
            mRealNotifier.fireTestAssumptionFailed(failure);
        } else {
            mOutOfTestFailures.add(failure.getException());
        }
    }

    @Override
    public void fireTestIgnored(Description description) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testIgnored: " + description);
        mRealNotifier.fireTestIgnored(description);
    }

    @Override
    public void fireTestFinished(Description description) {
        Log.i(RavenwoodAwareTestRunner.TAG, "testFinished: " + description);
        mRealNotifier.fireTestFinished(description);

        mAfterTest = true;
    }

    @Override
    public void pleaseStop() {
        Log.w(RavenwoodAwareTestRunner.TAG, "pleaseStop:");
        mRealNotifier.pleaseStop();
    }

    /**
     * At the end of each Suite, we handle failures happened out of test methods.
     * (typically in @BeforeClass or @AfterClasses)
     *
     * This is to work around b/364395552.
     */
    private boolean maybeHandleOutOfTestFailures() {
        if (mOutOfTestFailures.size() == 0) {
            return false;
        }
        Throwable th;
        if (mOutOfTestFailures.size() == 1) {
            th = mOutOfTestFailures.get(0);
        } else {
            th = new MultipleFailureException(mOutOfTestFailures);
        }
        if (mBeforeTest) {
            reportBeforeTestFailure(mCurrentSuite, th);
            return true;
        }
        if (mAfterTest) {
            reportAfterTestFailure(th);
            return true;
        }
        return false;
    }

    public void reportBeforeTestFailure(Description suiteDesc, Throwable th) {
        // If a failure happens befere running any tests, we'll need to pretend
        // as if each test in the suite reported the failure, to work around b/364395552.
        for (var child : suiteDesc.getChildren()) {
            if (child.isSuite()) {
                // If the chiil is still a "parent" -- a test class or a test suite
                // -- propagate to its children.
                mRealNotifier.fireTestSuiteStarted(child);
                reportBeforeTestFailure(child, th);
                mRealNotifier.fireTestSuiteFinished(child);
            } else {
                mRealNotifier.fireTestStarted(child);
                Failure f = new Failure(child, th);
                if (th instanceof AssumptionViolatedException) {
                    mRealNotifier.fireTestAssumptionFailed(f);
                } else {
                    mRealNotifier.fireTestFailure(f);
                }
                mRealNotifier.fireTestFinished(child);
            }
        }
    }

    public void reportAfterTestFailure(Throwable th) {
        // Unfortunately, there's no good way to report it, so kill the own process.
        RavenwoodAwareTestRunner.onCriticalError(
                "Failures detected in @AfterClass, which would be swallowed by tradefed",
                th);
    }
}
