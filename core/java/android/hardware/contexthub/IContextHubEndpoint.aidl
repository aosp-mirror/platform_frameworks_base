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
import android.hardware.location.IContextHubTransactionCallback;

/**
 * @hide
 */
interface IContextHubEndpoint {
    /**
     * Retrieve the up-to-date EndpointInfo, with assigned endpoint id.
     */
    HubEndpointInfo getAssignedHubEndpointInfo();

    /**
     * Request system service to open a session with a specific destination.
     *
     * @param destination A valid HubEndpointInfo representing the destination.
     * @param serviceDescriptor An optional descriptor of the service to scope this session to.
     *
     * @throws IllegalArgumentException If the HubEndpointInfo is not valid.
     * @throws IllegalStateException If there are too many opened sessions.
     */
    int openSession(in HubEndpointInfo destination, in @nullable String serviceDescriptor);

    /**
     * Request system service to close a specific session
     *
     * @param sessionId An integer identifying the session, assigned by system service
     * @param reason An integer identifying the reason
     *
     * @throws IllegalStateException If the session wasn't opened.
     */
    void closeSession(int sessionId, int reason);

    /**
     * Callback when a session is opened. This callback is the status callback for a previous
     * IContextHubEndpointCallback.onSessionOpenRequest().
     *
     * @param sessionId The integer representing the communication session, previously set in
     *         onSessionOpenRequest().
     *
     * @throws IllegalStateException If the session wasn't opened.
     */
    void openSessionRequestComplete(int sessionId);

    /**
     * Unregister this endpoint from the HAL, invalidate the EndpointInfo previously assigned.
     */
    void unregister();

    /**
     * Send a message parcelable to system service for a specific session.
     *
     * @param sessionId The integer representing the communication session, previously set in
     *         IContextHubEndpoint.openSession(). This id is assigned by the HAL.
     * @param message The HubMessage parcelable that represents the message and its delivery options.
     * @param transactionCallback Nullable. If the hub message requires a reply, the transactionCallback
     *                            will be set to non-null.
     */
    void sendMessage(int sessionId, in HubMessage message,
                     in @nullable IContextHubTransactionCallback transactionCallback);

    /**
     * Send a message delivery status to system service for a specific message
     *
     * @param sessionId The integer representing the communication session, previously set in
     *         IContextHubEndpoint.openSession(). This id is assigned by the HAL.
     * @param messageSeqNumber The message sequence number, this should match a previously received HubMessage.
     * @param errorCode The message delivery status detail.
     */
    void sendMessageDeliveryStatus(int sessionId, int messageSeqNumber, byte errorCode);
}
