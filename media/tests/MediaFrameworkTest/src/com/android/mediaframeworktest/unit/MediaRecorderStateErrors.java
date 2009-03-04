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

class MediaRecorderStateErrors {
    public static enum MediaRecorderState {
        INITIAL,
        INITIAL_AFTER_RESET,
        INITIAL_AFTER_STOP,
        INITIALIZED,
        DATASOURCECONFIGURED,
        PREPARED,
        RECORDING,
        ERROR,
    }
    
    // Error occurs in the states below?
    public boolean errorInInitialState = false;
    public boolean errorInInitialStateAfterReset = false;
    public boolean errorInInitialStateAfterStop = false;
    public boolean errorInInitializedState = false;
    public boolean errorInDataSourceConfiguredState = false;
    public boolean errorInPreparedState = false;
    public boolean errorInRecordingState = false;
    public boolean errorInErrorState = false;
}
