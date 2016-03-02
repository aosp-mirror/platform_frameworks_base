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

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import com.android.mediaframeworktest.unit.*;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all media framework unit tests.
 *
 * Make sure that MediaFrameworkUnitTestRunner has been added to
 * AndroidManifest.xml file, and then "make -j4 mediaframeworktest; adb sync"
 * to build and upload mediaframeworktest to the phone or emulator.
 *
 * Example on running all unit tests for a single class:
 * adb shell am instrument -e class \
 * com.android.mediaframeworktest.unit.MediaMetadataRetrieverUnitTest \
 * -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 *
 * Example on running all unit tests for the media framework:
 * adb shell am instrument \
 * -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 */

public class MediaFrameworkUnitTestRunner extends InstrumentationTestRunner {

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        addMediaMetadataRetrieverStateUnitTests(suite);
        addMediaRecorderStateUnitTests(suite);
        addMediaPlayerStateUnitTests(suite);
        addMediaScannerUnitTests(suite);
        addCameraUnitTests(suite);
        addImageReaderTests(suite);
        addExifInterfaceTests(suite);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaFrameworkUnitTestRunner.class.getClassLoader();
    }

    private void addCameraUnitTests(TestSuite suite) {
        suite.addTestSuite(CameraUtilsUncheckedThrowTest.class);
        suite.addTestSuite(CameraUtilsTypeReferenceTest.class);
        suite.addTestSuite(CameraMetadataTest.class);
    }

    private void addImageReaderTests(TestSuite suite) {
        suite.addTestSuite(ImageReaderTest.class);
    }

    // Running all unit tests checking the state machine may be time-consuming.
    private void addMediaMetadataRetrieverStateUnitTests(TestSuite suite) {
        suite.addTestSuite(MediaMetadataRetrieverTest.class);
    }

    // Running all unit tests checking the state machine may be time-consuming.
    private void addMediaRecorderStateUnitTests(TestSuite suite) {
        suite.addTestSuite(MediaRecorderPrepareStateUnitTest.class);
        suite.addTestSuite(MediaRecorderResetStateUnitTest.class);
        suite.addTestSuite(MediaRecorderSetAudioEncoderStateUnitTest.class);
        suite.addTestSuite(MediaRecorderSetAudioSourceStateUnitTest.class);
        suite.addTestSuite(MediaRecorderSetOutputFileStateUnitTest.class);
        suite.addTestSuite(MediaRecorderSetOutputFormatStateUnitTest.class);
        suite.addTestSuite(MediaRecorderStartStateUnitTest.class);
        suite.addTestSuite(MediaRecorderStopStateUnitTest.class);
    }

    // Running all unit tests checking the state machine may be time-consuming.
    private void addMediaPlayerStateUnitTests(TestSuite suite) {
        suite.addTestSuite(MediaPlayerGetDurationStateUnitTest.class);
        suite.addTestSuite(MediaPlayerSeekToStateUnitTest.class);
        suite.addTestSuite(MediaPlayerGetCurrentPositionStateUnitTest.class);
        suite.addTestSuite(MediaPlayerGetVideoWidthStateUnitTest.class);
        suite.addTestSuite(MediaPlayerGetVideoHeightStateUnitTest.class);
        suite.addTestSuite(MediaPlayerIsPlayingStateUnitTest.class);
        suite.addTestSuite(MediaPlayerResetStateUnitTest.class);
        suite.addTestSuite(MediaPlayerPauseStateUnitTest.class);
        suite.addTestSuite(MediaPlayerStartStateUnitTest.class);
        suite.addTestSuite(MediaPlayerStopStateUnitTest.class);
        suite.addTestSuite(MediaPlayerSetLoopingStateUnitTest.class);
        suite.addTestSuite(MediaPlayerSetAudioStreamTypeStateUnitTest.class);
        suite.addTestSuite(MediaPlayerSetVolumeStateUnitTest.class);
        suite.addTestSuite(MediaPlayerMetadataParserTest.class);
    }

    private void addMediaScannerUnitTests(TestSuite suite) {
        suite.addTestSuite(MediaInserterTest.class);
    }

    private void addExifInterfaceTests(TestSuite suite) {
        suite.addTestSuite(ExifInterfaceTest.class);
    }
}
