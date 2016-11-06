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

package android.net.wifi.aware;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for Aware session events callbacks. Should be extended by
 * applications wanting notifications. The callbacks are set when a
 * publish or subscribe session is created using
 * {@link WifiAwareSession#publish(android.os.Handler, PublishConfig,
 * WifiAwareDiscoverySessionCallback)}
 * or
 * {@link WifiAwareSession#subscribe(android.os.Handler, SubscribeConfig,
 * WifiAwareDiscoverySessionCallback)} .
 * <p>
 * A single callback is set at session creation - it cannot be replaced.
 *
 * @hide PROPOSED_AWARE_API
 */
public class WifiAwareDiscoverySessionCallback {
    /** @hide */
    @IntDef({
            TERMINATE_REASON_DONE, TERMINATE_REASON_FAIL })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionTerminateCodes {
    }

    /**
     * Indicates that publish or subscribe session is done - all the
     * requested operations (per {@link PublishConfig} or
     * {@link SubscribeConfig}) have been executed. Failure reason flag for
     * {@link WifiAwareDiscoverySessionCallback#onSessionTerminated(int)} callback.
     */
    public static final int TERMINATE_REASON_DONE = 100;

    /**
     * Indicates that publish or subscribe session is terminated due to a
     * failure.
     * Failure reason flag for
     * {@link WifiAwareDiscoverySessionCallback#onSessionTerminated(int)} callback.
     */
    public static final int TERMINATE_REASON_FAIL = 101;

    /**
     * Called when a publish operation is started successfully in response to a
     * {@link WifiAwareSession#publish(android.os.Handler, PublishConfig,
     * WifiAwareDiscoverySessionCallback)}
     * operation.
     *
     * @param session The {@link WifiAwarePublishDiscoverySession} used to control the
     *            discovery session.
     */
    public void onPublishStarted(@NonNull WifiAwarePublishDiscoverySession session) {
        /* empty */
    }

    /**
     * Called when a subscribe operation is started successfully in response to a
     * {@link WifiAwareSession#subscribe(android.os.Handler, SubscribeConfig,
     * WifiAwareDiscoverySessionCallback)}
     * operation.
     *
     * @param session The {@link WifiAwareSubscribeDiscoverySession} used to control the
     *            discovery session.
     */
    public void onSubscribeStarted(@NonNull WifiAwareSubscribeDiscoverySession session) {
        /* empty */
    }

    /**
     * Called when a publish or subscribe discovery session configuration update request
     * succeeds. Called in response to
     * {@link WifiAwarePublishDiscoverySession#updatePublish(PublishConfig)} or
     * {@link WifiAwareSubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}.
     */
    public void onSessionConfigUpdated() {
        /* empty */
    }

    /**
     * Called when a publish or subscribe discovery session cannot be created:
     * {@link WifiAwareSession#publish(android.os.Handler, PublishConfig,
     * WifiAwareDiscoverySessionCallback)}
     * or
     * {@link WifiAwareSession#subscribe(android.os.Handler, SubscribeConfig,
     * WifiAwareDiscoverySessionCallback)},
     * or when a configuration update fails:
     * {@link WifiAwarePublishDiscoverySession#updatePublish(PublishConfig)} or
     * {@link WifiAwareSubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}.
     * <p>
     *     For discovery session updates failure leaves the session running with its previous
     *     configuration - the discovery session is not terminated.
     */
    public void onSessionConfigFailed() {
        /* empty */
    }

    /**
     * Called when a discovery session (publish or subscribe) terminates. Termination may be due
     * to user-request (either directly through {@link WifiAwareDiscoveryBaseSession#destroy()} or
     * application-specified expiration, e.g. {@link PublishConfig.Builder#setPublishCount(int)}
     * or {@link SubscribeConfig.Builder#setTtlSec(int)}) or due to a failure.
     *
     * @param reason The termination reason using
     *            {@code WifiAwareDiscoverySessionCallback.TERMINATE_*} codes.
     */
    public void onSessionTerminated(@SessionTerminateCodes int reason) {
        /* empty */
    }

    /**
     * Called when a discovery (publish or subscribe) operation results in a
     * service discovery.
     *
     * @param peerHandle An opaque handle to the peer matching our discovery operation.
     * @param serviceSpecificInfo The service specific information (arbitrary
     *            byte array) provided by the peer as part of its discovery
     *            configuration.
     * @param matchFilter The filter (Tx on advertiser and Rx on listener) which
     *            resulted in this service discovery.
     */
    public void onServiceDiscovered(Object peerHandle, byte[] serviceSpecificInfo,
            byte[] matchFilter) {
        /* empty */
    }

    /**
     * Called in response to {@link WifiAwareDiscoveryBaseSession#sendMessage(Object, int, byte[])}
     * when a message is transmitted successfully - i.e. when it was received successfully by the
     * peer (corresponds to an ACK being received).
     * <p>
     * Note that either this callback or
     * {@link WifiAwareDiscoverySessionCallback#onMessageSendFailed(int)} will be
     * received - never both.
     *
     * @param messageId The arbitrary message ID specified when sending the message.
     */
    public void onMessageSent(@SuppressWarnings("unused") int messageId) {
        /* empty */
    }

    /**
     * Called when message transmission fails - when no ACK is received from the peer.
     * Retries when ACKs are not received are done by hardware, MAC, and in the Aware stack (using
     * the {@link WifiAwareDiscoveryBaseSession#sendMessage(Object, int, byte[], int)} method) -
     * this event is received after all retries are exhausted.
     * <p>
     * Note that either this callback or
     * {@link WifiAwareDiscoverySessionCallback#onMessageSent(int)} will be received
     * - never both.
     *
     * @param messageId The arbitrary message ID specified when sending the message.
     */
    public void onMessageSendFailed(@SuppressWarnings("unused") int messageId) {
        /* empty */
    }

    /**
     * Called when a message is received from a discovery session peer - in response to the
     * peer's {@link WifiAwareDiscoveryBaseSession#sendMessage(Object, int, byte[])} or
     * {@link WifiAwareDiscoveryBaseSession#sendMessage(Object, int, byte[], int)}.
     *
     * @param peerHandle An opaque handle to the peer matching our discovery operation.
     * @param message A byte array containing the message.
     */
    public void onMessageReceived(Object peerHandle, byte[] message) {
        /* empty */
    }
}
