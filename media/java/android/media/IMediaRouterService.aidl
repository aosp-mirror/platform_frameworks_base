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
import android.media.RouteDiscoveryRequest;
import android.media.RouteSessionInfo;

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
    void registerClient2(IMediaRouter2Client client, String packageName);
    void unregisterClient2(IMediaRouter2Client client);
    void sendControlRequest(IMediaRouter2Client client, in MediaRoute2Info route, in Intent request);
    void requestSetVolume2(IMediaRouter2Client client, in MediaRoute2Info route, int volume);
    void requestUpdateVolume2(IMediaRouter2Client client, in MediaRoute2Info route, int direction);

    void requestCreateSession(IMediaRouter2Client client, in MediaRoute2Info route,
            String routeType, int requestId);
    void setDiscoveryRequest2(IMediaRouter2Client client, in RouteDiscoveryRequest request);
    void selectRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void deselectRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void transferToRoute(IMediaRouter2Client client, String sessionId, in MediaRoute2Info route);
    void releaseSession(IMediaRouter2Client client, String sessionId);

    void registerManager(IMediaRouter2Manager manager, String packageName);
    void unregisterManager(IMediaRouter2Manager manager);

    void requestCreateClientSession(IMediaRouter2Manager manager, String packageName,
        in @nullable MediaRoute2Info route, int requestId);

    void requestSetVolume2Manager(IMediaRouter2Manager manager,
            in MediaRoute2Info route, int volume);
    void requestUpdateVolume2Manager(IMediaRouter2Manager manager,
            in MediaRoute2Info route, int direction);

    List<RouteSessionInfo> getActiveSessions(IMediaRouter2Manager manager);
}
