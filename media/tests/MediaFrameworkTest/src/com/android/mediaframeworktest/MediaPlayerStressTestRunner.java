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

package com.android.mediaframeworktest;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import com.android.mediaframeworktest.stress.MediaPlayerStressTest;

import junit.framework.TestSuite;

public class MediaPlayerStressTestRunner extends InstrumentationTestRunner {

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(MediaPlayerStressTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaPlayerStressTestRunner.class.getClassLoader();
    }
}
