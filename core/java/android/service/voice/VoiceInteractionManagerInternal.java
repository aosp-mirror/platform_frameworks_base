/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.service.voice;

import android.os.Bundle;
import android.os.IBinder;


/**
 * @hide
 * Private interface to the VoiceInteractionManagerService for use by ActivityManagerService.
 */
public abstract class VoiceInteractionManagerInternal {

    /**
     * Start a new voice interaction session when requested from within an activity
     * by Activity.startLocalVoiceInteraction()
     * @param callingActivity The binder token representing the calling activity.
     * @param options 
     */
    public abstract void startLocalVoiceInteraction(IBinder callingActivity, Bundle options);

    /**
     * Returns whether the currently selected voice interaction service supports local voice
     * interaction for launching from an Activity.
     */
    public abstract boolean supportsLocalVoiceInteraction();

    public abstract void stopLocalVoiceInteraction(IBinder callingActivity);
}