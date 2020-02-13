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

import android.content.Intent;
import android.media.IMediaRoute2ProviderClient;
import android.media.RouteDiscoveryPreference;
import android.os.Bundle;

/**
 * {@hide}
 */
oneway interface IMediaRoute2Provider {
    void setClient(IMediaRoute2ProviderClient client);
    void requestCreateSession(String packageName, String routeId, long requestId,
            in @nullable Bundle sessionHints);
    void releaseSession(String sessionId);
    void updateDiscoveryPreference(in RouteDiscoveryPreference discoveryPreference);

    void selectRoute(String sessionId, String routeId);
    void deselectRoute(String sessionId, String routeId);
    void transferToRoute(String sessionId, String routeId);

    void notifyControlRequestSent(String id, in Intent request);
    void setRouteVolume(String routeId, int volume);
    void setSessionVolume(String sessionId, int volume);
}
