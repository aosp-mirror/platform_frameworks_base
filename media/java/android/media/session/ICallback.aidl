/* Copyright (C) 2016 The Android Open Source Project
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
import android.content.ComponentName;
import android.media.session.MediaSession;
import android.view.KeyEvent;

/**
 * @hide
 */
oneway interface ICallback {
    void onMediaKeyEventDispatchedToMediaSession(in KeyEvent event,
            in MediaSession.Token sessionToken);
    void onMediaKeyEventDispatchedToMediaButtonReceiver(in KeyEvent event,
            in ComponentName mediaButtonReceiver);

    void onAddressedPlayerChangedToMediaSession(in MediaSession.Token sessionToken);
    void onAddressedPlayerChangedToMediaButtonReceiver(in ComponentName mediaButtonReceiver);
}

