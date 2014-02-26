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

import android.media.session.IMediaController;
import android.os.Bundle;

/**
 * Interface to a MediaSession in the system.
 * @hide
 */
interface IMediaSession {
    void sendEvent(in Bundle data);
    IMediaController getMediaSessionToken();
    void setPlaybackState(int state);
    void setMetadata(in Bundle metadata);
    void setRouteState(in Bundle routeState);
    void setRoute(in Bundle mediaRouteDescriptor);
    void destroy();
}