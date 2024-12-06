/*
 * Copyright 2024 The Android Open Source Project
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
package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

/**
 * An interface used to deliver messages to an opened endpoint session.
 *
 * <p>This interface can be attached to an endpoint through {@link
 * HubEndpoint.Builder#setMessageCallback} method. Methods in this interface will only be called
 * when the endpoint is currently registered and has an open session. The endpoint will receive
 * session lifecycle callbacks through {@link IHubEndpointLifecycleCallback}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public interface IHubEndpointMessageCallback {
    /**
     * Callback interface for receiving messages for a particular endpoint session.
     *
     * @param session The session this message is sent through. Previously specified in a {@link
     *     IHubEndpointLifecycleCallback#onSessionOpened(HubEndpointSession)} call.
     * @param message The {@link HubMessage} object representing a message received by the endpoint
     *     that registered this callback interface. This message is constructed by the
     */
    void onMessageReceived(@NonNull HubEndpointSession session, @NonNull HubMessage message);
}
