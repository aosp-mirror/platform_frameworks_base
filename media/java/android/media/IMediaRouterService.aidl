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

import android.media.IMediaRouterClient;
import android.media.MediaRouterClientState;

/**
 * {@hide}
 */
interface IMediaRouterService {
    void registerClientAsUser(IMediaRouterClient client, String packageName, int userId);
    void unregisterClient(IMediaRouterClient client);

    MediaRouterClientState getState(IMediaRouterClient client);
    boolean isPlaybackActive(IMediaRouterClient client);

    void setDiscoveryRequest(IMediaRouterClient client, int routeTypes, boolean activeScan);
    void setSelectedRoute(IMediaRouterClient client, String routeId, boolean explicit);
    void requestSetVolume(IMediaRouterClient client, String routeId, int volume);
    void requestUpdateVolume(IMediaRouterClient client, String routeId, int direction);
}
