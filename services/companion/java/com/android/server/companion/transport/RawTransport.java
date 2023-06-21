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

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.nio.ByteBuffer;

class RawTransport extends Transport {
    private volatile boolean mStopped;

    RawTransport(int associationId, ParcelFileDescriptor fd, Context context) {
        super(associationId, fd, context);
    }

    @Override
    void start() {
        if (DEBUG) {
            Slog.d(TAG, "Starting raw transport.");
        }
        new Thread(() -> {
            try {
                while (!mStopped) {
                    receiveMessage();
                }
            } catch (IOException e) {
                if (!mStopped) {
                    Slog.w(TAG, "Trouble during transport", e);
                    close();
                }
            }
        }).start();
    }

    @Override
    void stop() {
        if (DEBUG) {
            Slog.d(TAG, "Stopping raw transport.");
        }
        mStopped = true;
    }

    @Override
    void close() {
        stop();

        if (DEBUG) {
            Slog.d(TAG, "Closing raw transport.");
        }
        IoUtils.closeQuietly(mRemoteIn);
        IoUtils.closeQuietly(mRemoteOut);

        super.close();
    }

    @Override
    protected void sendMessage(int message, int sequence, @NonNull byte[] data)
            throws IOException {
        if (DEBUG) {
            Slog.e(TAG, "Sending message 0x" + Integer.toHexString(message)
                    + " sequence " + sequence + " length " + data.length
                    + " to association " + mAssociationId);
        }

        synchronized (mRemoteOut) {
            final ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH)
                    .putInt(message)
                    .putInt(sequence)
                    .putInt(data.length);
            mRemoteOut.write(header.array());
            mRemoteOut.write(data);
            mRemoteOut.flush();
        }
    }

    private void receiveMessage() throws IOException {
        synchronized (mRemoteIn) {
            final byte[] headerBytes = new byte[HEADER_LENGTH];
            Streams.readFully(mRemoteIn, headerBytes);
            final ByteBuffer header = ByteBuffer.wrap(headerBytes);
            final int message = header.getInt();
            final int sequence = header.getInt();
            final int length = header.getInt();
            final byte[] data = new byte[length];
            Streams.readFully(mRemoteIn, data);

            handleMessage(message, sequence, data);
        }
    }
}
