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
import android.content.pm.ParceledListSlice;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.ISessionControllerCallback;
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
    void sendCommand(String packageName, in ISessionControllerCallback caller,
            String command, in Bundle args, in ResultReceiver cb);
    boolean sendMediaButton(String packageName, in ISessionControllerCallback caller,
            in KeyEvent mediaButton);
    void registerCallback(String packageName, in ISessionControllerCallback cb);
    void unregisterCallback(in ISessionControllerCallback cb);
    String getPackageName();
    String getTag();
    Bundle getSessionInfo();
    PendingIntent getLaunchPendingIntent();
    long getFlags();
    MediaController.PlaybackInfo getVolumeAttributes();
    void adjustVolume(String packageName, String opPackageName,
            in ISessionControllerCallback caller, int direction, int flags);
    void setVolumeTo(String packageName, String opPackageName, in ISessionControllerCallback caller,
            int value, int flags);

    // These commands are for the TransportControls
    void prepare(String packageName, in ISessionControllerCallback caller);
    void prepareFromMediaId(String packageName, in ISessionControllerCallback caller,
            String mediaId, in Bundle extras);
    void prepareFromSearch(String packageName, in ISessionControllerCallback caller,
            String string, in Bundle extras);
    void prepareFromUri(String packageName, in ISessionControllerCallback caller,
            in Uri uri, in Bundle extras);
    void play(String packageName, in ISessionControllerCallback caller);
    void playFromMediaId(String packageName, in ISessionControllerCallback caller,
            String mediaId, in Bundle extras);
    void playFromSearch(String packageName, in ISessionControllerCallback caller,
            String string, in Bundle extras);
    void playFromUri(String packageName, in ISessionControllerCallback caller,
            in Uri uri, in Bundle extras);
    void skipToQueueItem(String packageName, in ISessionControllerCallback caller, long id);
    void pause(String packageName, in ISessionControllerCallback caller);
    void stop(String packageName, in ISessionControllerCallback caller);
    void next(String packageName, in ISessionControllerCallback caller);
    void previous(String packageName, in ISessionControllerCallback caller);
    void fastForward(String packageName, in ISessionControllerCallback caller);
    void rewind(String packageName, in ISessionControllerCallback caller);
    void seekTo(String packageName, in ISessionControllerCallback caller, long pos);
    void rate(String packageName, in ISessionControllerCallback caller, in Rating rating);
    void setPlaybackSpeed(String packageName, in ISessionControllerCallback caller, float speed);
    void sendCustomAction(String packageName, in ISessionControllerCallback caller,
            String action, in Bundle args);
    MediaMetadata getMetadata();
    PlaybackState getPlaybackState();
    ParceledListSlice getQueue();
    CharSequence getQueueTitle();
    Bundle getExtras();
    int getRatingType();
}
