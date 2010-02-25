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

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;


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
        
        String delay_str = (String) icicle.get("delay");
        if(delay_str != null) {
            try {
                this.mDelay = Integer.parseInt(delay_str);
            } catch (Exception e) {
            }
        }

        String r = (String)icicle.get("rebaseline");
        this.mRebaseline = (r != null && r.toLowerCase().equals("true"));

        String logtime = (String) icicle.get("logtime");
        this.mLogtime = (logtime != null
                && logtime.toLowerCase().equals("true"));

        String drawTime = (String) icicle.get("drawtime");
        this.mGetDrawTime = (drawTime != null
                && drawTime.toLowerCase().equals("true"));

        mSaveImagePath = (String) icicle.get("saveimage");

        mJsEngine = (String) icicle.get("jsengine");

        super.onCreate(icicle);
    }
    
    public String mTestPath;
    public String mSaveImagePath;
    public int mTimeoutInMillis;
    public int mDelay;
    public boolean mRebaseline;
    public boolean mLogtime;
    public boolean mGetDrawTime;
    public String mJsEngine;
}
