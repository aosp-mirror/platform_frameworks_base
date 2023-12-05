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
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.MediaRoute2Info;
import android.media.MediaRouterClientState;
import android.media.RouteDiscoveryPreference;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.UserHandle;

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

    void setBluetoothA2dpOn(IMediaRouterClient client, boolean on);
    void setDiscoveryRequest(IMediaRouterClient client, int routeTypes, boolean activeScan);
    void setSelectedRoute(IMediaRouterClient client, String routeId, boolean explicit);
    void requestSetVolume(IMediaRouterClient client, String routeId, int volume);
    void requestUpdateVolume(IMediaRouterClient client, String routeId, int direction);

    // Note: When changing this file, match the order of methods below with
    // MediaRouterService.java for readability.

    // Methods for MediaRouter2
    List<MediaRoute2Info> getSystemRoutes();
    RoutingSessionInfo getSystemSessionInfo();

    void registerRouter2(IMediaRouter2 router, String packageName);
    void unregisterRouter2(IMediaRouter2 router);
    void setDiscoveryRequestWithRouter2(IMediaRouter2 router,
            in RouteDiscoveryPreference preference);
    void setRouteListingPreference(IMediaRouter2 router,
            in @nullable RouteListingPreference routeListingPreference);
    void setRouteVolumeWithRouter2(IMediaRouter2 router, in MediaRoute2Info route, int volume);

    void requestCreateSessionWithRouter2(IMediaRouter2 router, int requestId, long managerRequestId,
            in RoutingSessionInfo oldSession, in MediaRoute2Info route,
            in @nullable Bundle sessionHints);
    void selectRouteWithRouter2(IMediaRouter2 router, String sessionId, in MediaRoute2Info route);
    void deselectRouteWithRouter2(IMediaRouter2 router, String sessionId, in MediaRoute2Info route);
    void transferToRouteWithRouter2(IMediaRouter2 router, String sessionId,
            in MediaRoute2Info route);
    void setSessionVolumeWithRouter2(IMediaRouter2 router, String sessionId, int volume);
    void releaseSessionWithRouter2(IMediaRouter2 router, String sessionId);

    // Methods for MediaRouter2Manager
    List<RoutingSessionInfo> getRemoteSessions(IMediaRouter2Manager manager);
    RoutingSessionInfo getSystemSessionInfoForPackage(String packageName);
    void registerManager(IMediaRouter2Manager manager, String packageName);
    void registerProxyRouter(IMediaRouter2Manager manager, String callingPackageName, String targetPackageName, in UserHandle targetUser);
    void unregisterManager(IMediaRouter2Manager manager);
    void setRouteVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            in MediaRoute2Info route, int volume);
    void startScan(IMediaRouter2Manager manager);
    void stopScan(IMediaRouter2Manager manager);

    void requestCreateSessionWithManager(IMediaRouter2Manager manager, int requestId,
            in RoutingSessionInfo oldSession, in @nullable MediaRoute2Info route);
    void selectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, in MediaRoute2Info route);
    void deselectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, in MediaRoute2Info route);
    void transferToRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, in MediaRoute2Info route);
    void setSessionVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, int volume);
    void releaseSessionWithManager(IMediaRouter2Manager manager, int requestId, String sessionId);
    boolean showMediaOutputSwitcher(String packageName);
}
