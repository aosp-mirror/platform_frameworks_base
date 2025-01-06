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

import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubMessage;
import android.hardware.contexthub.HubServiceInfo;

/**
  * @hide
 */
oneway interface IContextHubEndpointCallback {
    /**
     * Request from system service to open a session, requested by a specific initiator.
     *
     * @param sessionId An integer identifying the session, assigned by the initiator
     * @param initiator HubEndpointInfo representing the requester
     * @param serviceDescriptor Nullable string representing the service associated with this session
     */
    void onSessionOpenRequest(int sessionId, in HubEndpointInfo initiator, in @nullable String serviceDescriptor);

    /**
     * Request from system service to close a specific session
     *
     * @param sessionId An integer identifying the session
     * @param reason An integer identifying the reason
     */
    void onSessionClosed(int sessionId, int reason);

    /**
     * Notifies the system service that the session requested by IContextHubEndpoint.openSession
     * is ready to use.
     *
     * @param sessionId The integer representing the communication session, previously set in
     *         IContextHubEndpoint.openSession(). This id is assigned by the HAL.
     */
    void onSessionOpenComplete(int sessionId);

    /**
     * Message notification from system service for a specific session

     * @param sessionId The integer representing the communication session, previously set in
     *         IContextHubEndpoint.openSession(). This id is assigned by the HAL.
     * @param message The HubMessage parcelable that represents the message.
     */
    void onMessageReceived(int sessionId, in HubMessage message);
}
