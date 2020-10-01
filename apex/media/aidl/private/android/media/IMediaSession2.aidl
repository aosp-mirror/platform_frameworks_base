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
import android.media.Controller2Link;
import android.media.Session2Command;

/**
 * Interface from MediaController2 to MediaSession2.
 * <p>
 * Keep this interface oneway. Otherwise a malicious app may implement fake version of this,
 * and holds calls from session to make session owner(s) frozen.
 * @hide
 */
 // Code for AML only
oneway interface IMediaSession2 {
    void connect(in Controller2Link caller, int seq, in Bundle connectionRequest) = 0;
    void disconnect(in Controller2Link caller, int seq) = 1;
    void sendSessionCommand(in Controller2Link caller, int seq, in Session2Command sessionCommand,
            in Bundle args, in ResultReceiver resultReceiver) = 2;
    void cancelSessionCommand(in Controller2Link caller, int seq) = 3;
    // Next Id : 4
}
