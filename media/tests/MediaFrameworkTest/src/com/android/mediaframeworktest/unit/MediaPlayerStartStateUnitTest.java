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

import android.media.MediaPlayer;
import android.test.AndroidTestCase;

import androidx.test.filters.LargeTest;

/**
 * Unit test class to test the set of valid and invalid states that
 * MediaPlayer.start() method can be called.
 */
public class MediaPlayerStartStateUnitTest extends AndroidTestCase implements MediaPlayerMethodUnderTest {
    private MediaPlayerStateUnitTestTemplate mTestTemplate = new MediaPlayerStateUnitTestTemplate();

    /**
     * 1. It is valid to call start() in the following states:
     *    {Prepared, Started, Paused, PlaybackComplted}.
     * 2. It is invalid to call start() in the following states:
     *    {Idle, Initialized, Stopped, Error}
     *    
     * @param stateErrors the MediaPlayerStateErrors to check against.
     */
    public void checkStateErrors(MediaPlayerStateErrors stateErrors) {
        // Valid states.
        assertTrue(!stateErrors.errorInPreparedState);
        assertTrue(!stateErrors.errorInPreparedStateAfterStop);
        assertTrue(!stateErrors.errorInStartedState);
        assertTrue(!stateErrors.errorInStartedStateAfterPause);
        assertTrue(!stateErrors.errorInPausedState);
        assertTrue(!stateErrors.errorInPlaybackCompletedState);
        
        // Invalid states.
        assertTrue(stateErrors.errorInIdleState);
        assertTrue(stateErrors.errorInErrorState);
        assertTrue(stateErrors.errorInIdleStateAfterReset);
        assertTrue(stateErrors.errorInInitializedState);
        assertTrue(stateErrors.errorInStoppedState);
    }

    public void invokeMethodUnderTest(MediaPlayer player) {
        player.start();
    }

    @LargeTest
    public void testStart() {
        mTestTemplate.runTestOnMethod(this);
    }

    @Override
    public String toString() {
        return "start()";
    }

}
