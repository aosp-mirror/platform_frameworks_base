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
import android.media.Rating;
import android.media.session.ControllerCallbackLink;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * @hide
 */
oneway interface ISessionCallback {
    void notifyCommand(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            String command, in Bundle args, in ResultReceiver cb);
    void notifyMediaButton(String packageName, int pid, int uid, in Intent mediaButtonIntent,
            int sequenceNumber, in ResultReceiver cb);
    void notifyMediaButtonFromController(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, in Intent mediaButtonIntent);

    // These callbacks are for the TransportPerformer
    void notifyPrepare(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyPrepareFromMediaId(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, String mediaId, in Bundle extras);
    void notifyPrepareFromSearch(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, String query, in Bundle extras);
    void notifyPrepareFromUri(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, in Uri uri, in Bundle extras);
    void notifyPlay(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyPlayFromMediaId(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, String mediaId, in Bundle extras);
    void notifyPlayFromSearch(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, String query, in Bundle extras);
    void notifyPlayFromUri(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            in Uri uri, in Bundle extras);
    void notifySkipToTrack(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            long id);
    void notifyPause(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyStop(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyNext(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyPrevious(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyFastForward(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifyRewind(String packageName, int pid, int uid, in ControllerCallbackLink caller);
    void notifySeekTo(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            long pos);
    void notifyRate(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            in Rating rating);
    void notifySetPlaybackSpeed(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, float speed);
    void notifyCustomAction(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            String action, in Bundle args);

    // These callbacks are for volume handling
    void notifyAdjustVolume(String packageName, int pid, int uid, in ControllerCallbackLink caller,
            int direction);
    void notifySetVolumeTo(String packageName, int pid, int uid,
            in ControllerCallbackLink caller, int value);
}
