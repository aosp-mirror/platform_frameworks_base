/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.perftest;

//import com.android.perftest.RsBenchTest;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;

/**
 * Run the RenderScript Performance Test
 * adb shell am instrument -w com.android.perftest/.RsPerfTestRunner
 *
 * with specified iterations:
 * adb shell am instrument -e iterations <n> -w com.android.perftest/.RsPerfTestRunner
 *
 */
public class RsPerfTestRunner extends InstrumentationTestRunner {
    public int iterations = 10;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(RsBenchTest.class);
        return suite;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String strValue = (String)icicle.get("iterations");
        if (strValue != null) {
            int intValue = Integer.parseInt(strValue);
            if (iterations > 0) {
                iterations = intValue;
            }
        }
    }
}
