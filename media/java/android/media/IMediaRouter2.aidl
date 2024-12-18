/*
 * Copyright 2019 The Android Open Source Project
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

import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.UserHandle;

/**
 * @hide
 */
oneway interface IMediaRouter2 {
    void notifyRouterRegistered(in List<MediaRoute2Info> currentRoutes,
            in RoutingSessionInfo currentSystemSessionInfo);
    void notifyRoutesUpdated(in List<MediaRoute2Info> routes);
    void notifySessionCreated(int requestId, in @nullable RoutingSessionInfo sessionInfo);
    void notifySessionInfoChanged(in RoutingSessionInfo sessionInfo);
    void notifySessionReleased(in RoutingSessionInfo sessionInfo);
    /**
     * Gets hints of the new session for the given route.
     * Call MediaRouterService#requestCreateSessionWithRouter2 to pass the result.
     */
    void requestCreateSessionByManager(long uniqueRequestId, in RoutingSessionInfo oldSession,
        in MediaRoute2Info route);
}
