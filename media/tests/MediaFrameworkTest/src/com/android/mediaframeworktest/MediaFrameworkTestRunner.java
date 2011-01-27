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

import com.android.mediaframeworktest.functional.CameraTest;
import com.android.mediaframeworktest.functional.MediaAudioTrackTest;
import com.android.mediaframeworktest.functional.MediaMetadataTest;
import com.android.mediaframeworktest.functional.MediaMimeTest;
import com.android.mediaframeworktest.functional.MediaPlayerApiTest;
import com.android.mediaframeworktest.functional.MediaRecorderTest;
import com.android.mediaframeworktest.functional.SimTonesTest;
import com.android.mediaframeworktest.functional.MediaPlayerInvokeTest;
import com.android.mediaframeworktest.functional.MediaAudioManagerTest;
import com.android.mediaframeworktest.functional.MediaAudioEffectTest;
import com.android.mediaframeworktest.functional.MediaBassBoostTest;
import com.android.mediaframeworktest.functional.MediaEnvReverbTest;
import com.android.mediaframeworktest.functional.MediaEqualizerTest;
import com.android.mediaframeworktest.functional.MediaPresetReverbTest;
import com.android.mediaframeworktest.functional.MediaVirtualizerTest;
import com.android.mediaframeworktest.functional.MediaVisualizerTest;
import junit.framework.TestSuite;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;


/**
 * Instrumentation Test Runner for all MediaPlayer tests.
 *
 * Running all tests:
 *
 * adb shell am instrument \
 *   -w com.android.smstests.MediaPlayerInstrumentationTestRunner
 */

public class MediaFrameworkTestRunner extends InstrumentationTestRunner {


    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(MediaPlayerApiTest.class);
        suite.addTestSuite(SimTonesTest.class);
        suite.addTestSuite(MediaMetadataTest.class);
        suite.addTestSuite(CameraTest.class);
        suite.addTestSuite(MediaRecorderTest.class);
        suite.addTestSuite(MediaAudioTrackTest.class);
        suite.addTestSuite(MediaMimeTest.class);
        suite.addTestSuite(MediaPlayerInvokeTest.class);
        suite.addTestSuite(MediaAudioManagerTest.class);
        suite.addTestSuite(MediaAudioEffectTest.class);
        suite.addTestSuite(MediaBassBoostTest.class);
        suite.addTestSuite(MediaEnvReverbTest.class);
        suite.addTestSuite(MediaEqualizerTest.class);
        suite.addTestSuite(MediaPresetReverbTest.class);
        suite.addTestSuite(MediaVirtualizerTest.class);
        suite.addTestSuite(MediaVisualizerTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaFrameworkTestRunner.class.getClassLoader();
    }
}
