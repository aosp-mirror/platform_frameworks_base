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

package android.media.routing;

import android.media.routing.IMediaRouteClientCallback;
import android.media.routing.MediaRouteSelector;
import android.os.Bundle;

/**
 * Interface to an app's MediaRouteService.
 * @hide
 */
oneway interface IMediaRouteService {
    void registerClient(int clientUid, String clientPackageName,
            in IMediaRouteClientCallback callback);

    void unregisterClient(in IMediaRouteClientCallback callback);

    void startDiscovery(in IMediaRouteClientCallback callback, int seq,
            in List<MediaRouteSelector> selectors, int flags);

    void stopDiscovery(in IMediaRouteClientCallback callback);

    void connect(in IMediaRouteClientCallback callback, int seq,
            String destinationId, String routeId, int flags, in Bundle extras);

    void disconnect(in IMediaRouteClientCallback callback);

    void pauseStream(in IMediaRouteClientCallback callback);

    void resumeStream(in IMediaRouteClientCallback callback);
}

