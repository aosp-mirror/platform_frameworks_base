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
import android.media.session.PlaybackState;
import android.media.IMediaSession2;

/**
 * Interface from MediaSession2 to MediaSession2Record.
 * <p>
 * Keep this interface oneway. Otherwise a malicious app may implement fake version of this,
 * and holds calls from session to make session owner(s) frozen.
 *
 * @hide
 */
oneway interface IMediaSession2Callback {
    void onPlaybackStateChanged(in PlaybackState state);

    /**
     * Called only when the controller is created with service's token.
     *
     * @param sessionBinder {@code null} if the connect is rejected or is disconnected. a session
     *     binder if the connect is accepted.
     * @param commands initially allowed commands.
     */
    // TODO(jaewan): Also need to pass flags for allowed actions for permission check.
    //               For example, a media can allow setRating only for whitelisted apps
    //               it's better for controller to know such information in advance.
    //               Follow-up TODO: Add similar functions to the session.
    // TODO(jaewan): Is term 'accepted/rejected' correct? For permission, 'grant' is used.
    void onConnectionChanged(IMediaSession2 sessionBinder, in Bundle commandGroup);

    void onCustomLayoutChanged(in List<Bundle> commandButtonlist);

    //////////////////////////////////////////////////////////////////////////////////////////////
    // Browser sepcific
    //////////////////////////////////////////////////////////////////////////////////////////////
    void onGetRootResult(in Bundle rootHints, String rootMediaId, in Bundle rootExtra);
}
