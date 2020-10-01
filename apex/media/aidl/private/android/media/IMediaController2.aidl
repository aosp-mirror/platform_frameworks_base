/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.os.Bundle;
import android.os.ResultReceiver;
import android.media.Session2Command;

/**
 * Interface from MediaSession2 to MediaController2.
 * <p>
 * Keep this interface oneway. Otherwise a malicious app may implement fake version of this,
 * and holds calls from session to make session owner(s) frozen.
 * @hide
 */
 // Code for AML only
oneway interface IMediaController2 {
    void notifyConnected(int seq, in Bundle connectionResult) = 0;
    void notifyDisconnected(int seq) = 1;
    void notifyPlaybackActiveChanged(int seq, boolean playbackActive) = 2;
    void sendSessionCommand(int seq, in Session2Command command, in Bundle args,
            in ResultReceiver resultReceiver) = 3;
    void cancelSessionCommand(int seq) = 4;
    // Next Id : 5
}
