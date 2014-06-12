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
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.ISessionControllerCallback;
import android.media.session.MediaSessionInfo;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.KeyEvent;

/**
 * Interface to a MediaSession in the system.
 * @hide
 */
interface ISessionController {
    void sendCommand(String command, in Bundle extras, in ResultReceiver cb);
    boolean sendMediaButton(in KeyEvent mediaButton);
    void registerCallbackListener(in ISessionControllerCallback cb);
    void unregisterCallbackListener(in ISessionControllerCallback cb);
    boolean isTransportControlEnabled();
    void showRoutePicker();
    MediaSessionInfo getSessionInfo();
    long getFlags();

    // These commands are for the TransportController
    void play();
    void pause();
    void stop();
    void next();
    void previous();
    void fastForward();
    void rewind();
    void seekTo(long pos);
    void rate(in Rating rating);
    MediaMetadata getMetadata();
    PlaybackState getPlaybackState();
    int getRatingType();
}