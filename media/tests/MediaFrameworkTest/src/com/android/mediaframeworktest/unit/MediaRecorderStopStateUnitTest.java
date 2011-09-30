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

package com.android.mediaframeworktest.unit;

import android.media.MediaRecorder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

/**
 * Unit test class to test the set of valid and invalid states that
 * MediaRecorder.stop() method can be called.
 */          
public class MediaRecorderStopStateUnitTest extends AndroidTestCase implements MediaRecorderMethodUnderTest {
    private MediaRecorderStateUnitTestTemplate mTestTemplate = new MediaRecorderStateUnitTestTemplate();
    private static final String TAG = "MediaRecorderStopStateUnitTest";
    private static final int SLEEP_TIME_BEFORE_STOP = 1000;

    /**
     * 1. It is valid to call stop() in the following states:
     *    {Recording}.
     * 2. It is invalid to call stop() in the following states:
     *    {Initial, Initialized, DataSourceConfigured, Prepared, Error}
     *    
     * @param stateErrors the MediaRecorderStateErrors to check against.
     */
    public void checkStateErrors(MediaRecorderStateErrors stateErrors) {
        // Valid states.
        assertTrue(!stateErrors.errorInRecordingState);
        
        // Invalid states.
        assertTrue(stateErrors.errorInInitialState);
        assertTrue(stateErrors.errorInInitialStateAfterReset);
        assertTrue(stateErrors.errorInInitialStateAfterStop);
        assertTrue(stateErrors.errorInInitializedState);
        assertTrue(stateErrors.errorInErrorState);
        assertTrue(stateErrors.errorInDataSourceConfiguredState);
        assertTrue(stateErrors.errorInPreparedState);
    }

    public void invokeMethodUnderTest(MediaRecorder recorder) {
        // Wait for some time before stopping the media recorder.
        // This will fix the assertion caused by stopping it immediatedly
        // after it is started
        try {
            Thread.sleep(SLEEP_TIME_BEFORE_STOP);
        } catch(Exception e) {
            Log.v(TAG, "sleep was interrupted and terminated prematurely");
        }

        recorder.stop();
    }

    @MediumTest
    public void testStop() {
        mTestTemplate.runTestOnMethod(this);
    }
    
    @Override
    public String toString() {
        return "stop()";
    }
}
