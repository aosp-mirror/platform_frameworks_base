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
    void setRouteVolume(long requestId, String routeId, int volume);

    void requestCreateSession(long requestId, String packageName, String routeId,
            in @nullable Bundle sessionHints);
    void selectRoute(long requestId, String sessionId, String routeId);
    void deselectRoute(long requestId, String sessionId, String routeId);
    void transferToRoute(long requestId, String sessionId, String routeId);
    void setSessionVolume(long requestId, String sessionId, int volume);
    void releaseSession(long requestId, String sessionId);
}
