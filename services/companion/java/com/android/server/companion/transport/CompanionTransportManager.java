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

package com.android.server.companion.transport;

import static android.Manifest.permission.DELIVER_COMPANION_MESSAGES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

import libcore.io.IoUtils;
import libcore.io.Streams;
import libcore.util.EmptyArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("LongLogTag")
public class CompanionTransportManager {
    private static final String TAG = "CDM_CompanionTransportManager";
    // TODO: flip to false
    private static final boolean DEBUG = true;

    private static final int HEADER_LENGTH = 12;
    // TODO: refactor message processing to use streams to remove this limit
    private static final int MAX_PAYLOAD_LENGTH = 1_000_000;

    private static final int MESSAGE_REQUEST_PING = 0x63807378; // ?PIN
    private static final int MESSAGE_REQUEST_PERMISSION_RESTORE = 0x63826983; // ?RES

    private static final int MESSAGE_RESPONSE_SUCCESS = 0x33838567; // !SUC
    private static final int MESSAGE_RESPONSE_FAILURE = 0x33706573; // !FAI

    private static boolean isRequest(int message) {
        return (message & 0xFF000000) == 0x63000000;
    }

    private static boolean isResponse(int message) {
        return (message & 0xFF000000) == 0x33000000;
    }

    public interface Listener {
        void onRequestPermissionRestore(byte[] data);
    }

    private final Context mContext;

    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();

    @Nullable
    private Listener mListener;

    public CompanionTransportManager(Context context) {
        mContext = context;
    }

    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * For the moment, we only offer transporting of system data to built-in
     * companion apps; future work will improve the security model to support
     * third-party companion apps.
     */
    private void enforceCallerCanTransportSystemData(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(DELIVER_COMPANION_MESSAGES, TAG);

        try {
            final ApplicationInfo info = mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, userId);
            final int instrumentationUid = LocalServices.getService(ActivityManagerInternal.class)
                    .getInstrumentationSourceUid(Binder.getCallingUid());
            if (!Build.isDebuggable() && !info.isSystemApp()
                    && instrumentationUid == android.os.Process.INVALID_UID) {
                throw new SecurityException("Transporting of system data currently only available "
                        + "to built-in companion apps or tests");
            }
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void attachSystemDataTransport(String packageName, int userId, int associationId,
            ParcelFileDescriptor fd) {
        enforceCallerCanTransportSystemData(packageName, userId);
        synchronized (mTransports) {
            if (mTransports.contains(associationId)) {
                detachSystemDataTransport(packageName, userId, associationId);
            }

            final Transport transport = new Transport(associationId, fd);
            transport.start();
            mTransports.put(associationId, transport);
        }
    }

    public void detachSystemDataTransport(String packageName, int userId, int associationId) {
        enforceCallerCanTransportSystemData(packageName, userId);
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport != null) {
                mTransports.delete(associationId);
                transport.stop();
            }
        }
    }

    public Future<?> requestPermissionRestore(int associationId, byte[] data) {
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport != null) {
                return transport.requestForResponse(MESSAGE_REQUEST_PERMISSION_RESTORE, data);
            } else {
                return CompletableFuture.failedFuture(new IOException("Missing transport"));
            }
        }
    }

    private class Transport {
        private final int mAssociationId;

        private final InputStream mRemoteIn;
        private final OutputStream mRemoteOut;

        private final AtomicInteger mNextSequence = new AtomicInteger();

        @GuardedBy("mPendingRequests")
        private final SparseArray<CompletableFuture<byte[]>> mPendingRequests = new SparseArray<>();

        private volatile boolean mStopped;

        public Transport(int associationId, ParcelFileDescriptor fd) {
            mAssociationId = associationId;
            mRemoteIn = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            mRemoteOut = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        }

        public void start() {
            new Thread(() -> {
                try {
                    while (!mStopped) {
                        receiveMessage();
                    }
                } catch (IOException e) {
                    if (!mStopped) {
                        Slog.w(TAG, "Trouble during transport", e);
                        stop();
                    }
                }
            }).start();
        }

        public void stop() {
            mStopped = true;

            IoUtils.closeQuietly(mRemoteIn);
            IoUtils.closeQuietly(mRemoteOut);
        }

        public Future<byte[]> requestForResponse(int message, byte[] data) {
            final int sequence = mNextSequence.incrementAndGet();
            final CompletableFuture<byte[]> pending = new CompletableFuture<>();
            synchronized (mPendingRequests) {
                mPendingRequests.put(sequence, pending);
            }
            try {
                sendMessage(message, sequence, data);
            } catch (IOException e) {
                synchronized (mPendingRequests) {
                    mPendingRequests.remove(sequence);
                }
                pending.completeExceptionally(e);
            }
            return pending;
        }

        private void sendMessage(int message, int sequence, @NonNull byte[] data)
                throws IOException {
            if (DEBUG) {
                Slog.d(TAG, "Sending message 0x" + Integer.toHexString(message)
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
            if (DEBUG) {
                Slog.d(TAG, "Waiting for next message...");
            }

            final byte[] headerBytes = new byte[HEADER_LENGTH];
            Streams.readFully(mRemoteIn, headerBytes);
            final ByteBuffer header = ByteBuffer.wrap(headerBytes);
            final int message = header.getInt();
            final int sequence = header.getInt();
            final int length = header.getInt();

            if (DEBUG) {
                Slog.d(TAG, "Received message 0x" + Integer.toHexString(message)
                        + " sequence " + sequence + " length " + length
                        + " from association " + mAssociationId);
            }
            if (length > MAX_PAYLOAD_LENGTH) {
                Slog.w(TAG, "Ignoring message 0x" + Integer.toHexString(message)
                        + " sequence " + sequence + " length " + length
                        + " from association " + mAssociationId + " beyond maximum length");
                Streams.skipByReading(mRemoteIn, length);
                return;
            }

            final byte[] data = new byte[length];
            Streams.readFully(mRemoteIn, data);

            if (isRequest(message)) {
                processRequest(message, sequence, data);
            } else if (isResponse(message)) {
                processResponse(message, sequence, data);
            } else {
                Slog.w(TAG, "Unknown message 0x" + Integer.toHexString(message));
            }
        }

        private void processRequest(int message, int sequence, byte[] data)
                throws IOException {
            switch (message) {
                case MESSAGE_REQUEST_PING: {
                    sendMessage(MESSAGE_RESPONSE_SUCCESS, sequence, data);
                    break;
                }
                case MESSAGE_REQUEST_PERMISSION_RESTORE: {
                    if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)
                            && !Build.isDebuggable()) {
                        Slog.w(TAG, "Restoring permissions only supported on watches");
                        sendMessage(MESSAGE_RESPONSE_FAILURE, sequence, EmptyArray.BYTE);
                        break;
                    }
                    try {
                        mListener.onRequestPermissionRestore(data);
                        sendMessage(MESSAGE_RESPONSE_SUCCESS, sequence, EmptyArray.BYTE);
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to restore permissions");
                        sendMessage(MESSAGE_RESPONSE_FAILURE, sequence, EmptyArray.BYTE);
                    }
                    break;
                }
                default: {
                    Slog.w(TAG, "Unknown request 0x" + Integer.toHexString(message));
                    sendMessage(MESSAGE_RESPONSE_FAILURE, sequence, EmptyArray.BYTE);
                    break;
                }
            }
        }

        private void processResponse(int message, int sequence, byte[] data) {
            final CompletableFuture<byte[]> future;
            synchronized (mPendingRequests) {
                future = mPendingRequests.removeReturnOld(sequence);
            }
            if (future == null) {
                Slog.w(TAG, "Ignoring unknown sequence " + sequence);
                return;
            }

            switch (message) {
                case MESSAGE_RESPONSE_SUCCESS: {
                    future.complete(data);
                    break;
                }
                case MESSAGE_RESPONSE_FAILURE: {
                    future.completeExceptionally(new RuntimeException("Remote failure"));
                    break;
                }
                default: {
                    Slog.w(TAG, "Ignoring unknown response 0x" + Integer.toHexString(message));
                }
            }
        }
    }
}
