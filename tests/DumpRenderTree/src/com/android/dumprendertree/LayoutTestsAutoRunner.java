/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.dumprendertree;

import junit.framework.TestSuite;
import com.android.dumprendertree.LayoutTestsAutoTest;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;
import android.content.Intent;
import android.os.Bundle;


/**
 * Instrumentation Test Runner for all DumpRenderTree tests.
 * 
 * Running all tests:
 *
 * adb shell am instrument \
 *   -w com.android.dumprendertree.LayoutTestsAutoRunner
 */

public class LayoutTestsAutoRunner extends InstrumentationTestRunner {
    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(LayoutTestsAutoTest.class);
        suite.addTestSuite(LoadTestsAutoTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return LayoutTestsAutoRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        this.mTestPath = (String) icicle.get("path");
        String timeout_str = (String) icicle.get("timeout");
        if (timeout_str != null) {
            try {
                this.mTimeoutInMillis = Integer.parseInt(timeout_str);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        String r = (String)icicle.get("rebaseline");
        this.mRebaseline = (r != null && r.toLowerCase().equals("true"));
        super.onCreate(icicle);
    }
    
    public String mTestPath = null;
    public int mTimeoutInMillis = 0;
    public boolean mRebaseline = false;
}

