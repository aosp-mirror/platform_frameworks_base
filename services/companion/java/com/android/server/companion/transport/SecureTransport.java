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

import com.android.server.companion.securechannel.SecureChannel;
import com.android.server.companion.transport.CompanionTransportManager.Listener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;

class SecureTransport extends Transport implements SecureChannel.Callback {
    private final SecureChannel mSecureChannel;

    private volatile boolean mShouldProcessRequests = false;

    private final BlockingQueue<byte[]> mRequestQueue = new ArrayBlockingQueue<>(100);

    SecureTransport(int associationId,
            ParcelFileDescriptor fd,
            Context context,
            Listener listener) {
        super(associationId, fd, context, listener);
        mSecureChannel = new SecureChannel(mRemoteIn, mRemoteOut, this, context);
    }

    @Override
    public void start() {
        mSecureChannel.start();
    }

    @Override
    public void stop() {
        mSecureChannel.stop();
        mShouldProcessRequests = false;
    }

    @Override
    public Future<byte[]> requestForResponse(int message, byte[] data) {
        // Check if channel is secured and start securing
        if (!mShouldProcessRequests) {
            Slog.d(TAG, "Establishing secure connection.");
            try {
                mSecureChannel.establishSecureConnection();
            } catch (Exception e) {
                Slog.w(TAG, "Failed to initiate secure channel handshake.", e);
                onError(e);
            }
        }

        return super.requestForResponse(message, data);
    }

    @Override
    protected void sendMessage(int message, int sequence, @NonNull byte[] data)
            throws IOException {
        if (DEBUG) {
            Slog.d(TAG, "Queueing message 0x" + Integer.toHexString(message)
                    + " sequence " + sequence + " length " + data.length
                    + " to association " + mAssociationId);
        }

        // Queue up a message to send
        mRequestQueue.add(ByteBuffer.allocate(HEADER_LENGTH + data.length)
                .putInt(message)
                .putInt(sequence)
                .putInt(data.length)
                .put(data)
                .array());
    }

    @Override
    public void onSecureConnection() {
        mShouldProcessRequests = true;
        Slog.d(TAG, "Secure connection established.");

        // TODO: find a better way to handle incoming requests than a dedicated thread.
        new Thread(() -> {
            try {
                while (mShouldProcessRequests) {
                    byte[] request = mRequestQueue.poll();
                    if (request != null) {
                        mSecureChannel.sendSecureMessage(request);
                    }
                }
            } catch (IOException e) {
                onError(e);
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
            onError(error);
        }
    }

    @Override
    public void onError(Throwable error) {
        mShouldProcessRequests = false;
        Slog.e(TAG, error.getMessage(), error);
    }
}
