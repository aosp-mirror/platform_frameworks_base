/* Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

import android.content.Intent;
import android.media.session.IMediaControllerCallback;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;

/**
 * Interface to a MediaSession in the system.
 * @hide
 */
interface IMediaController {
    void sendCommand(String command, in Bundle extras);
    void sendMediaButton(in KeyEvent mediaButton);
    void registerCallbackListener(in IMediaControllerCallback cb);
    void unregisterCallbackListener(in IMediaControllerCallback cb);
    int getPlaybackState();
}