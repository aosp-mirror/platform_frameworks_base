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

package com.android.mediaframeworktest;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import com.android.mediaframeworktest.integration.CameraBinderTest;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all media framework integration tests.
 *
 * Running all tests:
 *
 * adb shell am instrument -w com.android.mediaframeworktest/.MediaFrameworkIntegrationTestRunner
 */

public class MediaFrameworkIntegrationTestRunner extends InstrumentationTestRunner {


    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(CameraBinderTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaFrameworkIntegrationTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
    }
}
