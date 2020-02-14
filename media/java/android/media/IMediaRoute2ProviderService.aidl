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
import android.media.IMediaRoute2ProviderServiceCallback;
import android.media.RouteDiscoveryPreference;
import android.os.Bundle;

/**
 * {@hide}
 */
oneway interface IMediaRoute2ProviderService {
    // Note: When changing this file, match the order of methods below with
    // MediaRoute2ProviderService#MediaRoute2ProviderServiceStub for readability.
    void setCallback(IMediaRoute2ProviderServiceCallback callback);
    void updateDiscoveryPreference(in RouteDiscoveryPreference discoveryPreference);
    void setRouteVolume(String routeId, int volume, long requestId);

    void requestCreateSession(String packageName, String routeId, long requestId,
            in @nullable Bundle sessionHints);
    void selectRoute(String sessionId, String routeId, long requestId);
    void deselectRoute(String sessionId, String routeId, long requestId);
    void transferToRoute(String sessionId, String routeId, long requestId);
    void setSessionVolume(String sessionId, int volume, long requestId);
    void releaseSession(String sessionId, long requestId);
}
