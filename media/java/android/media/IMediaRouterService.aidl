/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Intent;
import android.media.IMediaRouter2Client;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.MediaRoute2Info;
import android.media.MediaRouterClientState;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;

/**
 * {@hide}
 */
interface IMediaRouterService {
    //TODO: Merge or remove methods when media router 2 is done.
    void registerClientAsUser(IMediaRouterClient client, String packageName, int userId);
    void unregisterClient(IMediaRouterClient client);

    void registerClientGroupId(IMediaRouterClient client, String groupId);

    MediaRouterClientState getState(IMediaRouterClient client);
    boolean isPlaybackActive(IMediaRouterClient client);

    void setDiscoveryRequest(IMediaRouterClient client, int routeTypes, boolean activeScan);
    void setSelectedRoute(IMediaRouterClient client, String routeId, boolean explicit);
    void requestSetVolume(IMediaRouterClient client, String routeId, int volume);
    void requestUpdateVolume(IMediaRouterClient client, String routeId, int direction);

    // Methods for media router 2
    List<MediaRoute2Info> getSystemRoutes();
    RoutingSessionInfo getSystemSessionInfo();
    void registerClient2(IMediaRouter2Client client, String packageName);
    void unregisterClient2(IMediaRouter2Client client);
    void setRouteVolume2(IMediaRouter2Client client, in MediaRoute2Info route, int volume);
    void setSessionVolume2(IMediaRouter2Client client, String sessionId, int volume);

    void requestCreateSession(IMediaRouter2Client client, in MediaRoute2Info route, int requestId,
            in @nullable Bundle sessionHints);
    void setDiscoveryRequest2(IMediaRouter2Client client, in RouteDiscoveryPreference preference);
    void selectRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void deselectRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void transferToRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void releaseSession(IMediaRouter2Client client, String sessionId);

    void registerManager(IMediaRouter2Manager manager, String packageName);
    void unregisterManager(IMediaRouter2Manager manager);

    void requestCreateClientSession(IMediaRouter2Manager manager, String packageName,
        in @nullable MediaRoute2Info route, int requestId);

    void setRouteVolume2Manager(IMediaRouter2Manager manager,
            in MediaRoute2Info route, int volume);
    void setSessionVolume2Manager(IMediaRouter2Manager manager,
            String sessionId, int volume);

    List<RoutingSessionInfo> getActiveSessions(IMediaRouter2Manager manager);
    void selectClientRoute(IMediaRouter2Manager manager,
            String sessionId, in MediaRoute2Info route);
    void deselectClientRoute(IMediaRouter2Manager manager,
            String sessionId, in MediaRoute2Info route);
    void transferToClientRoute(IMediaRouter2Manager manager,
            String sessionId, in MediaRoute2Info route);
    void releaseClientSession(IMediaRouter2Manager manager, String sessionId);

}
