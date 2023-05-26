/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.securechannel;

import static android.security.attestationverification.AttestationVerificationManager.RESULT_SUCCESS;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Build;
import android.util.Slog;

import com.google.security.cryptauth.lib.securegcm.BadHandleException;
import com.google.security.cryptauth.lib.securegcm.CryptoException;
import com.google.security.cryptauth.lib.securegcm.D2DConnectionContextV1;
import com.google.security.cryptauth.lib.securegcm.D2DHandshakeContext;
import com.google.security.cryptauth.lib.securegcm.D2DHandshakeContext.Role;
import com.google.security.cryptauth.lib.securegcm.HandshakeException;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/**
 * Data stream channel that establishes secure connection between two peer devices.
 */
public class SecureChannel {
    private static final String TAG = "CDM_SecureChannel";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;

    private static final int VERSION = 1;
    private static final int HEADER_LENGTH = 6;

    private final InputStream mInput;
    private final OutputStream mOutput;
    private final Callback mCallback;
    private final byte[] mPreSharedKey;
    private final AttestationVerifier mVerifier;

    private volatile boolean mStopped;
    private volatile boolean mInProgress;

    private Role mRole;
    private byte[] mClientInit;
    private D2DHandshakeContext mHandshakeContext;
    private D2DConnectionContextV1 mConnectionContext;

    private String mAlias;
    private int mVerificationResult;
    private boolean mPskVerified;


    /**
     * Create a new secure channel object. This secure channel allows secure messages to be
     * exchanged with unattested devices. The pre-shared key must have been distributed to both
     * participants of the channel in a secure way previously.
     *
     * @param in input stream from which data is received
     * @param out output stream from which data is sent out
     * @param callback subscription to received messages from the channel
     * @param preSharedKey pre-shared key to authenticate unattested participant
     */
    public SecureChannel(
            @NonNull final InputStream in,
            @NonNull final OutputStream out,
            @NonNull Callback callback,
            @NonNull byte[] preSharedKey
    ) {
        this(in, out, callback, preSharedKey, null);
    }

    /**
     * Create a new secure channel object. This secure channel allows secure messages to be
     * exchanged with Android devices that were authenticated and verified with an attestation key.
     *
     * @param in input stream from which data is received
     * @param out output stream from which data is sent out
     * @param callback subscription to received messages from the channel
     * @param context context for fetching the Attestation Verifier Framework system service
     */
    public SecureChannel(
            @NonNull final InputStream in,
            @NonNull final OutputStream out,
            @NonNull Callback callback,
            @NonNull Context context
    ) {
        this(in, out, callback, null, new AttestationVerifier(context));
    }

    public SecureChannel(
            final InputStream in,
            final OutputStream out,
            Callback callback,
            byte[] preSharedKey,
            AttestationVerifier verifier
    ) {
        this.mInput = in;
        this.mOutput = out;
        this.mCallback = callback;
        this.mPreSharedKey = preSharedKey;
        this.mVerifier = verifier;
    }

    /**
     * Start listening for incoming messages.
     */
    public void start() {
        if (DEBUG) {
            Slog.d(TAG, "Starting secure channel.");
        }
        new Thread(() -> {
            try {
                // 1. Wait for the next handshake message and process it.
                exchangeHandshake();

                // 2. Authenticate remote actor via attestation or pre-shared key.
                exchangeAuthentication();

                // 3. Notify secure channel is ready.
                mInProgress = false;
                mCallback.onSecureConnection();

                // Listen for secure messages.
                while (!mStopped) {
                    receiveSecureMessage();
                }
            } catch (Exception e) {
                if (mStopped) {
                    return;
                }
                // TODO: Handle different types errors.

                Slog.e(TAG, "Secure channel encountered an error.", e);
                close();
                mCallback.onError(e);
            }
        }).start();
    }

    /**
     * Stop listening to incoming messages.
     */
    public void stop() {
        if (DEBUG) {
            Slog.d(TAG, "Stopping secure channel.");
        }
        mStopped = true;
        mInProgress = false;
    }

    /**
     * Stop listening to incoming messages and close the channel.
     */
    public void close() {
        stop();

        if (DEBUG) {
            Slog.d(TAG, "Closing secure channel.");
        }
        IoUtils.closeQuietly(mInput);
        IoUtils.closeQuietly(mOutput);
        KeyStoreUtils.cleanUp(mAlias);
    }

    /**
     * Start exchanging handshakes to create a secure layer asynchronously. When the handshake is
     * completed successfully, then the {@link Callback#onSecureConnection()} will trigger. Any
     * error that occurs during the handshake will be passed by {@link Callback#onError(Throwable)}.
     *
     * This method must only be called from one of the two participants.
     */
    public void establishSecureConnection() throws IOException, SecureChannelException {
        if (isSecured()) {
            Slog.d(TAG, "Channel is already secure.");
            return;
        }
        if (mInProgress) {
            Slog.w(TAG, "Channel has already started establishing secure connection.");
            return;
        }

        try {
            mInProgress = true;
            initiateHandshake();
        } catch (BadHandleException e) {
            throw new SecureChannelException("Failed to initiate handshake protocol.", e);
        }
    }

    /**
     * Send an encrypted, authenticated message via this channel.
     *
     * @param data data to be sent to the other side.
     * @throws IOException if the output stream fails to write given data.
     */
    public void sendSecureMessage(byte[] data) throws IOException {
        if (!isSecured()) {
            Slog.d(TAG, "Cannot send a message without a secure connection.");
            throw new IllegalStateException("Channel is not secured yet.");
        }

        // Encrypt constructed message
        try {
            sendMessage(MessageType.SECURE_MESSAGE, data);
        } catch (BadHandleException e) {
            throw new SecureChannelException("Failed to encrypt data.", e);
        }
    }

    private void receiveSecureMessage() throws IOException, CryptoException {
        // Check if channel is secured. Trigger error callback. Let user handle it.
        if (!isSecured()) {
            Slog.d(TAG, "Received a message without a secure connection. "
                    + "Message will be ignored.");
            mCallback.onError(new IllegalStateException("Connection is not secure."));
            return;
        }

        try {
            byte[] receivedMessage = readMessage(MessageType.SECURE_MESSAGE);
            mCallback.onSecureMessageReceived(receivedMessage);
        } catch (SecureChannelException e) {
            Slog.w(TAG, "Ignoring received message.", e);
        }
    }

    private byte[] readMessage(MessageType expected)
            throws IOException, SecureChannelException, CryptoException {
        if (DEBUG) {
            if (isSecured()) {
                Slog.d(TAG, "Waiting to receive next secure message.");
            } else {
                Slog.d(TAG, "Waiting to receive next " + expected + " message.");
            }
        }

        // TODO: Handle message timeout

        synchronized (mInput) {
            // Header is _not_ encrypted, but will be covered by MAC
            final byte[] headerBytes = new byte[HEADER_LENGTH];
            Streams.readFully(mInput, headerBytes);
            final ByteBuffer header = ByteBuffer.wrap(headerBytes);
            final int version = header.getInt();
            final short type = header.getShort();

            if (version != VERSION) {
                Streams.skipByReading(mInput, Long.MAX_VALUE);
                throw new SecureChannelException("Secure channel version mismatch. "
                        + "Currently on version " + VERSION + ". Skipping rest of data.");
            }

            if (type != expected.mValue) {
                Streams.skipByReading(mInput, Long.MAX_VALUE);
                throw new SecureChannelException(
                        "Unexpected message type. Expected " + expected.name()
                                + "; Found " + MessageType.from(type).name()
                                + ". Skipping rest of data.");
            }

            // Length of attached data is prepended as plaintext
            final byte[] lengthBytes = new byte[4];
            Streams.readFully(mInput, lengthBytes);
            final int length = ByteBuffer.wrap(lengthBytes).getInt();

            // Read data based on the length
            final byte[] data;
            try {
                data = new byte[length];
            } catch (OutOfMemoryError error) {
                throw new SecureChannelException("Payload is too large.", error);
            }

            Streams.readFully(mInput, data);
            if (!MessageType.shouldEncrypt(expected)) {
                return data;
            }

            return mConnectionContext.decodeMessageFromPeer(data, headerBytes);
        }
    }

    private void sendMessage(MessageType messageType, final byte[] payload)
            throws IOException, BadHandleException {
        synchronized (mOutput) {
            final byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                    .putInt(VERSION)
                    .putShort(messageType.mValue)
                    .array();
            final byte[] data = MessageType.shouldEncrypt(messageType)
                    ? mConnectionContext.encodeMessageToPeer(payload, header)
                    : payload;
            mOutput.write(header);
            mOutput.write(ByteBuffer.allocate(4)
                    .putInt(data.length)
                    .array());
            mOutput.write(data);
            mOutput.flush();
        }
    }

    private void initiateHandshake() throws IOException, BadHandleException {
        if (mConnectionContext != null) {
            Slog.d(TAG, "Ukey2 handshake is already completed.");
            return;
        }

        mRole = Role.Initiator;
        mHandshakeContext = D2DHandshakeContext.forInitiator();
        mClientInit = mHandshakeContext.getNextHandshakeMessage();

        // Send Client Init
        if (DEBUG) {
            Slog.d(TAG, "Sending Ukey2 Client Init message");
        }
        sendMessage(MessageType.HANDSHAKE_INIT, constructHandshakeInitMessage(mClientInit));
    }

    // In an occasion where both participants try to initiate a handshake, resolve the conflict
    // with a dice roll simulated by the message byte content comparison.
    // The higher value wins! (a.k.a. gets to be the initiator)
    private byte[] handleHandshakeCollision(byte[] handshakeInitMessage)
            throws IOException, HandshakeException, BadHandleException, CryptoException {

        // First byte indicates message type; 0 = CLIENT INIT, 1 = SERVER INIT
        ByteBuffer buffer = ByteBuffer.wrap(handshakeInitMessage);
        boolean isClientInit = buffer.get() == 0;
        byte[] handshakeMessage = new byte[buffer.remaining()];
        buffer.get(handshakeMessage);

        // If received message is Server Init or current role is Responder, then there was
        // no collision. Return extracted handshake message.
        if (mHandshakeContext == null || !isClientInit) {
            return handshakeMessage;
        }

        Slog.w(TAG, "Detected a Ukey2 handshake role collision. Negotiating a role.");

        // if received message is "larger" than the sent message, then reset the handshake context.
        if (compareByteArray(mClientInit, handshakeMessage) < 0) {
            Slog.d(TAG, "Assigned: Responder");
            mHandshakeContext = null;
            return handshakeMessage;
        } else {
            Slog.d(TAG, "Assigned: Initiator; Discarding received Client Init");

            // Wait for another init message after discarding the client init
            ByteBuffer nextInitMessage = ByteBuffer.wrap(readMessage(MessageType.HANDSHAKE_INIT));

            // Throw if this message is a Client Init again; 0 = CLIENT INIT, 1 = SERVER INIT
            if (nextInitMessage.get() == 0) {
                // This should never happen!
                throw new HandshakeException("Failed to resolve Ukey2 handshake role collision.");
            }
            byte[] nextHandshakeMessage = new byte[nextInitMessage.remaining()];
            nextInitMessage.get(nextHandshakeMessage);

            return nextHandshakeMessage;
        }
    }

    private void exchangeHandshake()
            throws IOException, HandshakeException, BadHandleException, CryptoException {
        if (mConnectionContext != null) {
            Slog.d(TAG, "Ukey2 handshake is already completed.");
            return;
        }

        // Waiting for message
        byte[] handshakeInitMessage = readMessage(MessageType.HANDSHAKE_INIT);

        // Mark "in-progress" upon receiving the first message
        mInProgress = true;

        // Handle a potential collision where both devices tried to initiate a connection
        byte[] handshakeMessage = handleHandshakeCollision(handshakeInitMessage);

        // Proceed with the rest of Ukey2 handshake
        if (mHandshakeContext == null) { // Server-side logic
            mRole = Role.Responder;
            mHandshakeContext = D2DHandshakeContext.forResponder();

            // Receive Client Init
            if (DEBUG) {
                Slog.d(TAG, "Receiving Ukey2 Client Init message");
            }
            mHandshakeContext.parseHandshakeMessage(handshakeMessage);

            // Send Server Init
            if (DEBUG) {
                Slog.d(TAG, "Sending Ukey2 Server Init message");
            }
            sendMessage(MessageType.HANDSHAKE_INIT,
                    constructHandshakeInitMessage(mHandshakeContext.getNextHandshakeMessage()));

            // Receive Client Finish
            if (DEBUG) {
                Slog.d(TAG, "Receiving Ukey2 Client Finish message");
            }
            mHandshakeContext.parseHandshakeMessage(readMessage(MessageType.HANDSHAKE_FINISH));
        } else { // Client-side logic

            // Receive Server Init
            if (DEBUG) {
                Slog.d(TAG, "Receiving Ukey2 Server Init message");
            }
            mHandshakeContext.parseHandshakeMessage(handshakeMessage);

            // Send Client Finish
            if (DEBUG) {
                Slog.d(TAG, "Sending Ukey2 Client Finish message");
            }
            sendMessage(MessageType.HANDSHAKE_FINISH, mHandshakeContext.getNextHandshakeMessage());
        }

        // Convert secrets to connection context
        if (mHandshakeContext.isHandshakeComplete()) {
            if (DEBUG) {
                Slog.d(TAG, "Ukey2 Handshake completed successfully");
            }
            mConnectionContext = mHandshakeContext.toConnectionContext();
        } else {
            Slog.e(TAG, "Failed to complete Ukey2 Handshake");
            throw new IllegalStateException("Ukey2 Handshake did not complete as expected.");
        }
    }

    private void exchangeAuthentication()
            throws IOException, GeneralSecurityException, BadHandleException, CryptoException {
        if (mPreSharedKey != null) {
            exchangePreSharedKey();
        }
        if (mVerifier != null) {
            exchangeAttestation();
        }
    }

    private void exchangePreSharedKey()
            throws IOException, GeneralSecurityException, BadHandleException, CryptoException {

        // Exchange hashed pre-shared keys
        if (DEBUG) {
            Slog.d(TAG, "Exchanging pre-shared keys.");
        }
        sendMessage(MessageType.PRE_SHARED_KEY, constructToken(mRole, mPreSharedKey));
        byte[] receivedAuthToken = readMessage(MessageType.PRE_SHARED_KEY);
        byte[] expectedAuthToken = constructToken(mRole == Role.Initiator
                ? Role.Responder
                : Role.Initiator,
                mPreSharedKey);
        mPskVerified = Arrays.equals(receivedAuthToken, expectedAuthToken);

        if (!mPskVerified) {
            throw new SecureChannelException("Failed to verify the hash of pre-shared key.");
        }

        if (DEBUG) {
            Slog.d(TAG, "The pre-shared key was successfully authenticated.");
        }
    }

    private void exchangeAttestation()
            throws IOException, GeneralSecurityException, BadHandleException, CryptoException {
        if (mVerificationResult == RESULT_SUCCESS) {
            Slog.d(TAG, "Remote attestation was already verified.");
            return;
        }

        // Send local attestation
        if (DEBUG) {
            Slog.d(TAG, "Exchanging device attestation.");
        }
        if (mAlias == null) {
            mAlias = generateAlias();
        }
        byte[] localChallenge = constructToken(mRole, mConnectionContext.getSessionUnique());
        KeyStoreUtils.generateAttestationKeyPair(mAlias, localChallenge);
        byte[] localAttestation = KeyStoreUtils.getEncodedCertificateChain(mAlias);
        sendMessage(MessageType.ATTESTATION, localAttestation);
        byte[] remoteAttestation = readMessage(MessageType.ATTESTATION);

        // Verifying remote attestation with public key local binding param
        byte[] expectedChallenge = constructToken(mRole == Role.Initiator
                ? Role.Responder
                : Role.Initiator,
                mConnectionContext.getSessionUnique());
        mVerificationResult = mVerifier.verifyAttestation(remoteAttestation, expectedChallenge);

        // Exchange attestation verification result and finish
        byte[] verificationResult = ByteBuffer.allocate(4)
                .putInt(mVerificationResult)
                .array();
        sendMessage(MessageType.AVF_RESULT, verificationResult);
        byte[] remoteVerificationResult = readMessage(MessageType.AVF_RESULT);

        if (ByteBuffer.wrap(remoteVerificationResult).getInt() != RESULT_SUCCESS) {
            throw new SecureChannelException("Remote device failed to verify local attestation.");
        }

        if (mVerificationResult != RESULT_SUCCESS) {
            throw new SecureChannelException("Failed to verify remote attestation.");
        }

        if (DEBUG) {
            Slog.d(TAG, "Remote attestation was successfully verified.");
        }
    }

    private boolean isSecured() {
        // Is ukey-2 encrypted
        if (mConnectionContext == null) {
            return false;
        }
        // Is authenticated
        return mPskVerified || mVerificationResult == RESULT_SUCCESS;
    }

    // First byte indicates message type; 0 = CLIENT INIT, 1 = SERVER INIT
    // This information is needed to help resolve potential role collision.
    private byte[] constructHandshakeInitMessage(byte[] message) {
        return ByteBuffer.allocate(1 + message.length)
                .put((byte) (Role.Initiator.equals(mRole) ? 0 : 1))
                .put(message)
                .array();
    }

    private byte[] constructToken(D2DHandshakeContext.Role role, byte[] authValue)
            throws GeneralSecurityException {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] roleUtf8 = role.name().getBytes(StandardCharsets.UTF_8);
        int tokenLength = roleUtf8.length + authValue.length;
        return hash.digest(ByteBuffer.allocate(tokenLength)
                .put(roleUtf8)
                .put(authValue)
                .array());
    }

    // Arbitrary comparator
    private int compareByteArray(byte[] a, byte[] b) {
        if (a == b) {
            return 0;
        }
        if (a.length != b.length) {
            return a.length - b.length;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return a[i] - b[i];
            }
        }
        return 0;
    }

    private String generateAlias() {
        String alias;
        do {
            alias = "secure-channel-" + UUID.randomUUID();
        } while (KeyStoreUtils.aliasExists(alias));
        return alias;
    }

    private enum MessageType {
        HANDSHAKE_INIT(0x4849),   // HI
        HANDSHAKE_FINISH(0x4846), // HF
        PRE_SHARED_KEY(0x504b),   // PK
        ATTESTATION(0x4154),      // AT
        AVF_RESULT(0x5652),       // VR
        SECURE_MESSAGE(0x534d),   // SM
        UNKNOWN(0);               // X

        private final short mValue;

        MessageType(int value) {
            this.mValue = (short) value;
        }

        static MessageType from(short value) {
            for (MessageType messageType : values()) {
                if (value == messageType.mValue) {
                    return messageType;
                }
            }
            return UNKNOWN;
        }

        // Encrypt every message besides Ukey2 handshake messages
        private static boolean shouldEncrypt(MessageType type) {
            return type != HANDSHAKE_INIT && type != HANDSHAKE_FINISH;
        }
    }

    /**
     * Callback that passes securely received message to the subscribed user.
     */
    public interface Callback {
        /**
         * Triggered after {@link SecureChannel#establishSecureConnection()} finishes exchanging
         * every required handshakes to fully establish a secure connection.
         */
        void onSecureConnection();

        /**
         * Callback that passes securely received and decrypted data to the subscribed user.
         *
         * @param data securely received plaintext data.
         */
        void onSecureMessageReceived(byte[] data);

        /**
         * Callback that passes error that occurred during handshakes or while listening to
         * messages in the secure channel.
         *
         * @param error
         */
        void onError(Throwable error);
    }
}
