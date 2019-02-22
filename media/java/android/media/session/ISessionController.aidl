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

import android.app.PendingIntent;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.MediaParceledListSlice;
import android.media.Rating;
import android.media.session.ControllerCallbackLink;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.KeyEvent;

import java.util.List;

/**
 * Interface to MediaSessionRecord in the system.
 * @hide
 */
interface ISessionController {
    void sendCommand(String packageName, in ControllerCallbackLink caller,
            String command, in Bundle args, in ResultReceiver cb);
    boolean sendMediaButton(String packageName, in ControllerCallbackLink caller,
            in KeyEvent mediaButton);
    void registerCallback(String packageName, in ControllerCallbackLink cb);
    void unregisterCallback(in ControllerCallbackLink cb);
    String getPackageName();
    String getTag();
    Bundle getSessionInfo();
    PendingIntent getLaunchPendingIntent();
    long getFlags();
    MediaController.PlaybackInfo getVolumeAttributes();
    void adjustVolume(String packageName, String opPackageName,
            in ControllerCallbackLink caller, int direction, int flags);
    void setVolumeTo(String packageName, String opPackageName, in ControllerCallbackLink caller,
            int value, int flags);

    // These commands are for the TransportControls
    void prepare(String packageName, in ControllerCallbackLink caller);
    void prepareFromMediaId(String packageName, in ControllerCallbackLink caller,
            String mediaId, in Bundle extras);
    void prepareFromSearch(String packageName, in ControllerCallbackLink caller,
            String string, in Bundle extras);
    void prepareFromUri(String packageName, in ControllerCallbackLink caller,
            in Uri uri, in Bundle extras);
    void play(String packageName, in ControllerCallbackLink caller);
    void playFromMediaId(String packageName, in ControllerCallbackLink caller,
            String mediaId, in Bundle extras);
    void playFromSearch(String packageName, in ControllerCallbackLink caller,
            String string, in Bundle extras);
    void playFromUri(String packageName, in ControllerCallbackLink caller,
            in Uri uri, in Bundle extras);
    void skipToQueueItem(String packageName, in ControllerCallbackLink caller, long id);
    void pause(String packageName, in ControllerCallbackLink caller);
    void stop(String packageName, in ControllerCallbackLink caller);
    void next(String packageName, in ControllerCallbackLink caller);
    void previous(String packageName, in ControllerCallbackLink caller);
    void fastForward(String packageName, in ControllerCallbackLink caller);
    void rewind(String packageName, in ControllerCallbackLink caller);
    void seekTo(String packageName, in ControllerCallbackLink caller, long pos);
    void rate(String packageName, in ControllerCallbackLink caller, in Rating rating);
    void setPlaybackSpeed(String packageName, in ControllerCallbackLink caller, float speed);
    void sendCustomAction(String packageName, in ControllerCallbackLink caller,
            String action, in Bundle args);
    MediaMetadata getMetadata();
    PlaybackState getPlaybackState();
    MediaParceledListSlice getQueue();
    CharSequence getQueueTitle();
    Bundle getExtras();
    int getRatingType();
}
