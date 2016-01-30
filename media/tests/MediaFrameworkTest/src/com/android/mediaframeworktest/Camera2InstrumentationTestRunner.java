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

package com.android.mediaframeworktest;

import com.android.mediaframeworktest.stress.Camera2CaptureRequestTest;
import com.android.mediaframeworktest.stress.Camera2RecordingTest;
import com.android.mediaframeworktest.stress.Camera2ReprocessCaptureTest;
import com.android.mediaframeworktest.stress.Camera2StillCaptureTest;

import junit.framework.TestSuite;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

/**
 * This is Camera2 framework test runner to execute the specified test classes if no target class
 * is defined in the meta-data or command line argument parameters.
 */
public class Camera2InstrumentationTestRunner extends InstrumentationTestRunner {

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        // Note the following test cases are compatible with Camera API2
        suite.addTestSuite(Camera2StillCaptureTest.class);
        suite.addTestSuite(Camera2RecordingTest.class);
        suite.addTestSuite(Camera2ReprocessCaptureTest.class);
        suite.addTestSuite(Camera2CaptureRequestTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return Camera2InstrumentationTestRunner.class.getClassLoader();
    }
}
