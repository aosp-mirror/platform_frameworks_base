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

/**
 * Base class for NAN session events callbacks. Should be extended by
 * applications wanting notifications. The callbacks are registered when a
 * publish or subscribe session is created using
 * {@link WifiNanManager#publish(PublishConfig, WifiNanSessionCallback)} or
 * {@link WifiNanManager#subscribe(SubscribeConfig, WifiNanSessionCallback)} .
 * These are callbacks applying to a specific NAN session. Events corresponding
 * to the NAN link are delivered using {@link WifiNanEventCallback}.
 * <p>
 * A single callback is registered at session creation - it cannot be replaced.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSessionCallback {
    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates no resources to
     * execute the requested operation.
     */
    public static final int FAIL_REASON_NO_RESOURCES = 0;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates invalid argument in
     * the requested operation.
     */
    public static final int FAIL_REASON_INVALID_ARGS = 1;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates a message is
     * transmitted without a match (i.e. a discovery) occurring first.
     */
    public static final int FAIL_REASON_NO_MATCH_SESSION = 2;

    /**
     * Failure reason flag for {@link WifiNanSessionCallback} callbacks.
     * Indicates that a command has been issued to a session which is
     * terminated. Session termination may have been caused explicitly by the
     * user using the {@code WifiNanSession#terminate()} or implicitly as a
     * result of the original session reaching its lifetime or being terminated
     * due to an error.
     */
    public static final int FAIL_REASON_SESSION_TERMINATED = 3;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates an unspecified error
     * occurred during the operation.
     */
    public static final int FAIL_REASON_OTHER = 4;

    /**
     * Failure reason flag for
     * {@link WifiNanSessionCallback#onSessionTerminated(int)} callback.
     * Indicates that publish or subscribe session is done - i.e. all the
     * requested operations (per {@link PublishConfig} or
     * {@link SubscribeConfig}) have been executed.
     */
    public static final int TERMINATE_REASON_DONE = 0;

    /**
     * Failure reason flag for
     * {@link WifiNanSessionCallback#onSessionTerminated(int)} callback.
     * Indicates that publish or subscribe session is terminated due to a
     * failure.
     */
    public static final int TERMINATE_REASON_FAIL = 1;

    /**
     * Called when a publish operation is started successfully.
     *
     * @param session The {@link WifiNanPublishSession} used to control the
     *            discovery session.
     */
    public void onPublishStarted(WifiNanPublishSession session) {
        /* empty */
    }

    /**
     * Called when a subscribe operation is started successfully.
     *
     * @param session The {@link WifiNanSubscribeSession} used to control the
     *            discovery session.
     */
    public void onSubscribeStarted(WifiNanSubscribeSession session) {
        /* empty */
    }


    /**
     * Called when a session configuration (publish or subscribe setup or
     * update) fails.
     *
     * @param reason The failure reason using
     *            {@code WifiNanSessionCallback.FAIL_*} codes.
     */
    public void onSessionConfigFail(int reason) {
        /* empty */
    }

    /**
     * Called when a session (publish or subscribe) terminates.
     *
     * @param reason The termination reason using
     *            {@code WifiNanSessionCallback.TERMINATE_*} codes.
     */
    public void onSessionTerminated(int reason) {
        /* empty */
    }

    /**
     * Called when a discovery (publish or subscribe) operation results in a
     * match - i.e. when a peer is discovered.
     *
     * @param peerId The ID of the peer matching our discovery operation.
     * @param serviceSpecificInfo The service specific information (arbitrary
     *            byte array) provided by the peer as part of its discovery
     *            packet.
     * @param serviceSpecificInfoLength The length of the service specific
     *            information array.
     * @param matchFilter The filter (Tx on advertiser and Rx on listener) which
     *            resulted in this match.
     * @param matchFilterLength The length of the match filter array.
     */
    public void onMatch(int peerId, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
        /* empty */
    }

    /**
     * Called when a message is transmitted successfully - i.e. when we know
     * that it was received successfully (corresponding to an ACK being
     * received).
     * <p>
     * Note that either this callback or
     * {@link WifiNanSessionCallback#onMessageSendFail(int, int)} will be
     * received - never both.
     */
    public void onMessageSendSuccess(@SuppressWarnings("unused") int messageId) {
        /* empty */
    }

    /**
     * Called when a message transmission fails - i.e. when no ACK is received.
     * The hardware will usually attempt to re-transmit several times - this
     * event is received after all retries are exhausted. There is a possibility
     * that message was received by the destination successfully but the ACK was
     * lost
     * <p>
     * Note that either this callback or
     * {@link WifiNanSessionCallback#onMessageSendSuccess(int)} will be received
     * - never both
     *
     * @param reason The failure reason using
     *            {@code WifiNanSessionCallback.FAIL_*} codes.
     */
    public void onMessageSendFail(@SuppressWarnings("unused") int messageId, int reason) {
        /* empty */
    }

    /**
     * Called when a message is received from a discovery session peer.
     *
     * @param peerId The ID of the peer sending the message.
     * @param message A byte array containing the message.
     * @param messageLength The length of the byte array containing the relevant
     *            message bytes.
     */
    public void onMessageReceived(int peerId, byte[] message, int messageLength) {
        /* empty */
    }
}
