/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for NAN session events callbacks. Should be extended by
 * applications wanting notifications. The callbacks are set when a
 * publish or subscribe session is created using
 * {@link WifiNanSession#publish(PublishConfig, WifiNanDiscoverySessionCallback)} or
 * {@link WifiNanSession#subscribe(SubscribeConfig, WifiNanDiscoverySessionCallback)} .
 * <p>
 * A single callback is set at session creation - it cannot be replaced.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanDiscoverySessionCallback {
    /** @hide */
    @IntDef({
            REASON_NO_RESOURCES, REASON_INVALID_ARGS, REASON_NO_MATCH_SESSION,
            REASON_TX_FAIL, REASON_OTHER })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionReasonCodes {
    }

    /** @hide */
    @IntDef({
            TERMINATE_REASON_DONE, TERMINATE_REASON_FAIL })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionTerminateCodes {
    }

    /**
     * Indicates no resources to execute the requested operation.
     * Failure reason flag for {@link WifiNanDiscoverySessionCallback} callbacks.
     */
    public static final int REASON_NO_RESOURCES = 0;

    /**
     * Indicates invalid argument in the requested operation.
     * Failure reason flag for {@link WifiNanDiscoverySessionCallback} callbacks.
     */
    public static final int REASON_INVALID_ARGS = 1;

    /**
     * Indicates a message is transmitted without a match (a discovery) or received message
     * from peer occurring first.
     * Failure reason flag for {@link WifiNanDiscoverySessionCallback} callbacks.
     */
    public static final int REASON_NO_MATCH_SESSION = 2;

    /**
     * Indicates transmission failure: this may be due to local transmission
     * failure or to no ACK received - remote device didn't receive the
     * sent message. Failure reason flag for
     * {@link WifiNanDiscoverySessionCallback#onMessageSendFailed(int, int)} callback.
     */
    public static final int REASON_TX_FAIL = 3;

    /**
     * Indicates an unspecified error occurred during the operation.
     * Failure reason flag for {@link WifiNanDiscoverySessionCallback} callbacks.
     */
    public static final int REASON_OTHER = 4;

    /**
     * Indicates that publish or subscribe session is done - all the
     * requested operations (per {@link PublishConfig} or
     * {@link SubscribeConfig}) have been executed. Failure reason flag for
     * {@link WifiNanDiscoverySessionCallback#onSessionTerminated(int)} callback.
     */
    public static final int TERMINATE_REASON_DONE = 100;

    /**
     * Indicates that publish or subscribe session is terminated due to a
     * failure.
     * Failure reason flag for
     * {@link WifiNanDiscoverySessionCallback#onSessionTerminated(int)} callback.
     */
    public static final int TERMINATE_REASON_FAIL = 101;

    /**
     * Called when a publish operation is started successfully in response to a
     * {@link WifiNanSession#publish(PublishConfig, WifiNanDiscoverySessionCallback)} operation.
     *
     * @param session The {@link WifiNanPublishDiscoverySession} used to control the
     *            discovery session.
     */
    public void onPublishStarted(@NonNull WifiNanPublishDiscoverySession session) {
        /* empty */
    }

    /**
     * Called when a subscribe operation is started successfully in response to a
     * {@link WifiNanSession#subscribe(SubscribeConfig, WifiNanDiscoverySessionCallback)} operation.
     *
     * @param session The {@link WifiNanSubscribeDiscoverySession} used to control the
     *            discovery session.
     */
    public void onSubscribeStarted(@NonNull WifiNanSubscribeDiscoverySession session) {
        /* empty */
    }

    /**
     * Called when a publish or subscribe discovery session configuration update request
     * succeeds. Called in response to
     * {@link WifiNanPublishDiscoverySession#updatePublish(PublishConfig)} or
     * {@link WifiNanSubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}.
     */
    public void onSessionConfigUpdated() {
        /* empty */
    }

    /**
     * Called when a publish or subscribe discovery session cannot be created:
     * {@link WifiNanSession#publish(PublishConfig, WifiNanDiscoverySessionCallback)} or
     * {@link WifiNanSession#subscribe(SubscribeConfig, WifiNanDiscoverySessionCallback)},
     * or when a configuration update fails:
     * {@link WifiNanPublishDiscoverySession#updatePublish(PublishConfig)} or
     * {@link WifiNanSubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}.
     * <p>
     *     For discovery session updates failure leaves the session running with its previous
     *     configuration - the discovery session is not terminated.
     *
     * @param reason The failure reason using
     *            {@code WifiNanDiscoverySessionCallback.REASON_*} codes.
     */
    public void onSessionConfigFailed(@SessionReasonCodes int reason) {
        /* empty */
    }

    /**
     * Called when a discovery session (publish or subscribe) terminates. Termination may be due
     * to user-request (either directly through {@link WifiNanDiscoveryBaseSession#destroy()} or
     * application-specified expiration, e.g. {@link PublishConfig.Builder#setPublishCount(int)}
     * or {@link SubscribeConfig.Builder#setTtlSec(int)}) or due to a failure.
     *
     * @param reason The termination reason using
     *            {@code WifiNanDiscoverySessionCallback.TERMINATE_*} codes.
     */
    public void onSessionTerminated(@SessionTerminateCodes int reason) {
        /* empty */
    }

    /**
     * Called when a discovery (publish or subscribe) operation results in a
     * service discovery.
     *
     * @param peerId The ID of the peer matching our discovery operation.
     * @param serviceSpecificInfo The service specific information (arbitrary
     *            byte array) provided by the peer as part of its discovery
     *            configuration.
     * @param matchFilter The filter (Tx on advertiser and Rx on listener) which
     *            resulted in this service discovery.
     */
    public void onServiceDiscovered(int peerId, byte[] serviceSpecificInfo, byte[] matchFilter) {
        /* empty */
    }

    /**
     * Called in response to {@link WifiNanDiscoveryBaseSession#sendMessage(int, byte[], int)}
     * when a message is transmitted successfully - i.e. when it was received successfully by the
     * peer (corresponds to an ACK being received).
     * <p>
     * Note that either this callback or
     * {@link WifiNanDiscoverySessionCallback#onMessageSendFailed(int, int)} will be
     * received - never both.
     *
     * @param messageId The arbitrary message ID specified when sending the message.
     */
    public void onMessageSent(@SuppressWarnings("unused") int messageId) {
        /* empty */
    }

    /**
     * Called when message transmission fails - when no ACK is received from the peer.
     * Retries when ACKs are not received are done by hardware, MAC, and in the NAN stack (using
     * the {@link WifiNanDiscoveryBaseSession#sendMessage(int, byte[], int, int)} method) - this
     * event is received after all retries are exhausted.
     * <p>
     * Note that either this callback or
     * {@link WifiNanDiscoverySessionCallback#onMessageSent(int)} will be received
     * - never both.
     *
     * @param messageId The arbitrary message ID specified when sending the message.
     * @param reason The failure reason using
     *            {@code WifiNanDiscoverySessionCallback.REASON_*} codes.
     */
    public void onMessageSendFailed(@SuppressWarnings("unused") int messageId,
            @SessionReasonCodes int reason) {
        /* empty */
    }

    /**
     * Called when a message is received from a discovery session peer - in response to the
     * peer's {@link WifiNanDiscoveryBaseSession#sendMessage(int, byte[], int)} or
     * {@link WifiNanDiscoveryBaseSession#sendMessage(int, byte[], int, int)}.
     *
     * @param peerId The ID of the peer sending the message.
     * @param message A byte array containing the message.
     */
    public void onMessageReceived(int peerId, byte[] message) {
        /* empty */
    }
}
