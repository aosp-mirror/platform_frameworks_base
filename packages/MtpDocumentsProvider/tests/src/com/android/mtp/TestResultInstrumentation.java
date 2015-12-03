/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestListener;

/**
 * Instrumentation that can show the test result in the TestResultActivity.
 * It's useful when it runs testcases with a real USB device and could not use USB port for ADB.
 */
public class TestResultInstrumentation extends InstrumentationTestRunner implements TestListener {
    private boolean mHasError = false;

    @Override
    public void onCreate(Bundle arguments) {
        if (arguments == null) {
            arguments = new Bundle();
        }
        final boolean includeRealDeviceTest =
                Boolean.parseBoolean(arguments.getString("realDeviceTest", "false"));
        if (!includeRealDeviceTest) {
            arguments.putString("notAnnotation", "com.android.mtp.RealDeviceTest");
        }
        super.onCreate(arguments);
        if (includeRealDeviceTest) {
            // Show the test result by using activity because we need to disconnect USB cable
            // from adb host while testing with real MTP device.
            addTestListener(this);
        }
    }

    @Override
    public void addError(Test test, Throwable t) {
        mHasError = true;
        show("ERROR", test, t);
    }

    @Override
    public void addFailure(Test test, AssertionFailedError t) {
        mHasError = true;
        show("FAIL", test, t);
    }

    @Override
    public void endTest(Test test) {
        if (!mHasError) {
            show("PASS", test, null);
        }
    }

    @Override
    public void startTest(Test test) {
        mHasError = false;
    }

    void show(String message) {
        TestResultActivity.show(getContext(), "    " + message);
    }

    private void show(String tag, Test test, Throwable t) {
        String message = "";
        if (t != null && t.getMessage() != null) {
            message = t.getMessage();
        }
        TestResultActivity.show(
                getContext(), String.format("[%s] %s %s", tag, test.toString(), message));
    }
}
