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

import android.media.Rating;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * @hide
 */
oneway interface ISessionCallback {
    void onCommand(String command, in Bundle args, in ResultReceiver cb);
    void onMediaButton(in Intent mediaButtonIntent, int sequenceNumber, in ResultReceiver cb);

    // These callbacks are for the TransportPerformer
    void onPlay();
    void onPlayFromMediaId(String uri, in Bundle extras);
    void onPlayFromSearch(String query, in Bundle extras);
    void onSkipToTrack(long id);
    void onPause();
    void onStop();
    void onNext();
    void onPrevious();
    void onFastForward();
    void onRewind();
    void onSeekTo(long pos);
    void onRate(in Rating rating);
    void onCustomAction(String action, in Bundle args);

    // These callbacks are for volume handling
    void onAdjustVolume(int direction);
    void onSetVolumeTo(int value);
}
