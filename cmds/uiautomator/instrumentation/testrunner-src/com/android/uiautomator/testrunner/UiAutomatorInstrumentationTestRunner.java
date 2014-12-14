/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.uiautomator.testrunner;

import android.test.AndroidTestRunner;
import android.test.InstrumentationTestRunner;

import com.android.uiautomator.core.Tracer;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestListener;

/**
 * Test runner for {@link UiAutomatorTestCase}s. Such tests are executed
 * on the device and have access to an applications context.
 */
public class UiAutomatorInstrumentationTestRunner extends InstrumentationTestRunner {

    @Override
    public void onStart() {
        // process runner arguments before test starts
        String traceType = getArguments().getString("traceOutputMode");
        if(traceType != null) {
            Tracer.Mode mode = Tracer.Mode.valueOf(Tracer.Mode.class, traceType);
            if (mode == Tracer.Mode.FILE || mode == Tracer.Mode.ALL) {
                String filename = getArguments().getString("traceLogFilename");
                if (filename == null) {
                    throw new RuntimeException("Name of log file not specified. " +
                            "Please specify it using traceLogFilename parameter");
                }
                Tracer.getInstance().setOutputFilename(filename);
            }
            Tracer.getInstance().setOutputMode(mode);
        }
        super.onStart();
    }

    @Override
    protected AndroidTestRunner getAndroidTestRunner() {
        AndroidTestRunner testRunner = super.getAndroidTestRunner();
        testRunner.addTestListener(new TestListener() {
            @Override
            public void startTest(Test test) {
                if (test instanceof UiAutomatorTestCase) {
                    ((UiAutomatorTestCase)test).initialize(getArguments());
                }
            }

            @Override
            public void endTest(Test test) {
            }

            @Override
            public void addFailure(Test test, AssertionFailedError e) {
            }

            @Override
            public void addError(Test test, Throwable t) {
            }
        });
        return testRunner;
    }
}
