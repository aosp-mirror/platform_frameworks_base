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

class MediaPlayerStateErrors {
    public static final int MEDIA_PLAYER_ERROR = 100;
    public static enum MediaPlayerState {
        IDLE,
        IDLE_AFTER_RESET,
        INITIALIZED,
        PREPARED,
        PREPARED_AFTER_STOP,
        STARTED,
        STARTED_AFTER_PAUSE,
        PAUSED,
        STOPPED,
        PLAYBACK_COMPLETED,
        ERROR,
    }
    
    // Error occurs in the states below?
    public boolean errorInIdleState = false;
    public boolean errorInIdleStateAfterReset = false;
    public boolean errorInInitializedState = false;
    public boolean errorInPreparedState = false;
    public boolean errorInStartedState = false;
    public boolean errorInPausedState = false;
    public boolean errorInStartedStateAfterPause = false;
    public boolean errorInStoppedState = false;
    public boolean errorInPreparedStateAfterStop = false;
    public boolean errorInPlaybackCompletedState = false;
    public boolean errorInErrorState = false;
}
