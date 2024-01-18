/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.transport;

import android.annotation.NonNull;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import com.android.server.companion.securechannel.AttestationVerifier;
import com.android.server.companion.securechannel.SecureChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

class SecureTransport extends Transport implements SecureChannel.Callback {
    private final SecureChannel mSecureChannel;

    private volatile boolean mShouldProcessRequests = false;

    private final BlockingQueue<byte[]> mRequestQueue = new ArrayBlockingQueue<>(500);

    SecureTransport(int associationId, ParcelFileDescriptor fd, Context context) {
        super(associationId, fd, context);
        mSecureChannel = new SecureChannel(mRemoteIn, mRemoteOut, this, context);
    }

    SecureTransport(int associationId, ParcelFileDescriptor fd, Context context,
            byte[] preSharedKey, AttestationVerifier verifier) {
        super(associationId, fd, context);
        mSecureChannel = new SecureChannel(mRemoteIn, mRemoteOut, this, preSharedKey, verifier);
    }

    @Override
    void start() {
        mSecureChannel.start();
    }

    @Override
    void stop() {
        mSecureChannel.stop();
        mShouldProcessRequests = false;
    }

    @Override
    void close() {
        mSecureChannel.close();
        mShouldProcessRequests = false;

        super.close();
    }

    @Override
    protected void sendMessage(int message, int sequence, @NonNull byte[] data)
            throws IOException {
        // Check if channel is secured; otherwise start securing
        if (!mShouldProcessRequests) {
            establishSecureConnection();
        }

        if (DEBUG) {
            Slog.d(TAG, "Queueing message 0x" + Integer.toHexString(message)
                    + " sequence " + sequence + " length " + data.length
                    + " to association " + mAssociationId);
        }

        // Queue up a message to send
        try {
            mRequestQueue.add(ByteBuffer.allocate(HEADER_LENGTH + data.length)
                    .putInt(message)
                    .putInt(sequence)
                    .putInt(data.length)
                    .put(data)
                    .array());
        } catch (IllegalStateException e) {
            // Request buffer can only be full if too many requests are being added or
            // the request processing thread is dead. Assume latter and detach the transport.
            Slog.w(TAG, "Failed to queue message 0x" + Integer.toHexString(message)
                    + " . Request buffer is full; detaching transport.", e);
            close();
        }
    }

    private void establishSecureConnection() {
        Slog.d(TAG, "Establishing secure connection.");
        try {
            mSecureChannel.establishSecureConnection();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initiate secure channel handshake.", e);
            close();
        }
    }

    @Override
    public void onSecureConnection() {
        mShouldProcessRequests = true;
        Slog.d(TAG, "Secure connection established.");

        // TODO: find a better way to handle incoming requests than a dedicated thread.
        new Thread(() -> {
            while (mShouldProcessRequests) {
                try {
                    byte[] request = mRequestQueue.take();
                    mSecureChannel.sendSecureMessage(request);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to send secure message.", e);
                    close();
                }
            }
        }).start();
    }

    @Override
    public void onSecureMessageReceived(byte[] data) {
        final ByteBuffer payload = ByteBuffer.wrap(data);
        final int message = payload.getInt();
        final int sequence = payload.getInt();
        final int length = payload.getInt();
        final byte[] content = new byte[length];
        payload.get(content);

        try {
            handleMessage(message, sequence, content);
        } catch (IOException error) {
            // IOException won't be thrown here because a separate thread is handling
            // the write operations inside onSecureConnection().
        }
    }

    @Override
    public void onError(Throwable error) {
        Slog.e(TAG, "Secure transport encountered an error.", error);

        // If the channel was stopped as a result of the error, then detach itself.
        if (mSecureChannel.isStopped()) {
            close();
        }
    }
}
