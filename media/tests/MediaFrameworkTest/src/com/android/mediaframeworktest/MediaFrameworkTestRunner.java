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

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import com.android.mediaframeworktest.functional.CameraTest;
import com.android.mediaframeworktest.functional.MediaMetadataTest;
import com.android.mediaframeworktest.functional.MediaMimeTest;
import com.android.mediaframeworktest.functional.MediaPlayerInvokeTest;
import com.android.mediaframeworktest.functional.audio.MediaAudioEffectTest;
import com.android.mediaframeworktest.functional.audio.MediaAudioManagerTest;
import com.android.mediaframeworktest.functional.audio.MediaAudioTrackTest;
import com.android.mediaframeworktest.functional.audio.MediaBassBoostTest;
import com.android.mediaframeworktest.functional.audio.MediaEnvReverbTest;
import com.android.mediaframeworktest.functional.audio.MediaEqualizerTest;
import com.android.mediaframeworktest.functional.audio.MediaPresetReverbTest;
import com.android.mediaframeworktest.functional.audio.MediaVirtualizerTest;
import com.android.mediaframeworktest.functional.audio.MediaVisualizerTest;
import com.android.mediaframeworktest.functional.audio.SimTonesTest;
import com.android.mediaframeworktest.functional.mediaplayback.MediaPlayerApiTest;
import com.android.mediaframeworktest.functional.mediarecorder.MediaRecorderTest;

import junit.framework.TestSuite;


/**
 * Instrumentation Test Runner for all MediaPlayer tests.
 *
 * Running all tests:
 *
 * adb shell am instrument \
 *  -w com.android.mediaframeworktest/.MediaFrameworkTestRunner
 */

public class MediaFrameworkTestRunner extends InstrumentationTestRunner {

    public static int mMinCameraFps = 0;

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

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        String minCameraFps = (String) icicle.get("min_camera_fps");
        System.out.print("min_camera_" + minCameraFps);

        if (minCameraFps != null ) {
            mMinCameraFps = Integer.parseInt(minCameraFps);
        }
    }
}
