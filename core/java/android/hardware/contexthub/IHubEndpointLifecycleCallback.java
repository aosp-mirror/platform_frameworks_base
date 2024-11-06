/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for listening to lifecycle events of a hub endpoint.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public interface IHubEndpointLifecycleCallback {
    /** Unknown reason. */
    int REASON_UNSPECIFIED = 0;

    /** The peer rejected the request to open this endpoint session. */
    int REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED = 3;

    /** The peer closed this endpoint session. */
    int REASON_CLOSE_ENDPOINT_SESSION_REQUESTED = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        REASON_UNSPECIFIED,
        REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED,
        REASON_CLOSE_ENDPOINT_SESSION_REQUESTED,
    })
    @interface EndpointLifecycleReason {}

    /**
     * Called when a communication session is opened and ready to be used.
     *
     * @param session The {@link HubEndpointSession} object that can be used for communication
     */
    void onSessionOpened(@NonNull HubEndpointSession session);

    /**
     * Called when a communication session is requested to be closed, or the peer endpoint rejected
     * the session open request.
     *
     * @param session The {@link HubEndpointSession} object that is now closed and shouldn't be
     *     used.
     * @param reason The reason why this session was closed.
     */
    void onSessionClosed(@NonNull HubEndpointSession session, @EndpointLifecycleReason int reason);
}
