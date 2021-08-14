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

import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.os.Bundle;

/**
 * @hide
 */
oneway interface IMediaRoute2ProviderServiceCallback {
    // TODO: Change it to updateRoutes?
    void notifyProviderUpdated(in MediaRoute2ProviderInfo providerInfo);
    void notifySessionCreated(long requestId, in RoutingSessionInfo sessionInfo);
    void notifySessionsUpdated(in List<RoutingSessionInfo> sessionInfo);
    void notifySessionReleased(in RoutingSessionInfo sessionInfo);
    void notifyRequestFailed(long requestId, int reason);
}
