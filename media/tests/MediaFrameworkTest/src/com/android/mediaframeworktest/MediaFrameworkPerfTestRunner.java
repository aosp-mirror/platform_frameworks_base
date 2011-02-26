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

package com.android.mediaframeworktest;

import com.android.mediaframeworktest.performance.MediaPlayerPerformance;
/*Video Editor performance Test cases*/
import com.android.mediaframeworktest.performance.VideoEditorPerformance;
import junit.framework.TestSuite;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;

/**
 * Instrumentation Test Runner for all MediaPlayer tests.
 *
 * Running all tests:
 *
 * adb shell am instrument \
 *   -w com.android.smstests.MediaPlayerInstrumentationTestRunner
 */

public class MediaFrameworkPerfTestRunner extends InstrumentationTestRunner {

    public static boolean mGetNativeHeapDump = false;


    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(MediaPlayerPerformance.class);
        /* Video Editor performance Test cases */
        suite.addTestSuite(VideoEditorPerformance.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaFrameworkTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String get_heap_dump = (String) icicle.get("get_heap_dump");
        if (get_heap_dump != null) {
            mGetNativeHeapDump = true;
        }
    }
}

