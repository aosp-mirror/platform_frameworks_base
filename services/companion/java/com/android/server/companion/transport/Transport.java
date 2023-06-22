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
import android.companion.IOnMessageReceivedListener;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import libcore.util.EmptyArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class represents the channel established between two devices.
 */
public abstract class Transport {
    protected static final String TAG = "CDM_CompanionTransport";
    protected static final boolean DEBUG = Build.IS_DEBUGGABLE;

    static final int MESSAGE_REQUEST_PING = 0x63807378; // ?PIN
    public static final int MESSAGE_REQUEST_CONTEXT_SYNC = 0x63678883; // ?CXS
    public static final int MESSAGE_REQUEST_PERMISSION_RESTORE = 0x63826983; // ?RES

    static final int MESSAGE_RESPONSE_SUCCESS = 0x33838567; // !SUC
    static final int MESSAGE_RESPONSE_FAILURE = 0x33706573; // !FAI

    protected static final int HEADER_LENGTH = 12;

    protected final int mAssociationId;
    protected final ParcelFileDescriptor mFd;
    protected final InputStream mRemoteIn;
    protected final OutputStream mRemoteOut;
    protected final Context mContext;

    /**
     * Message type -> Listener
     *
     * For now, the transport only supports 1 listener for each message type. If there's a need in
     * the future to allow multiple listeners to receive callbacks for the same message type, the
     * value of the map can be a list.
     */
    private final Map<Integer, IOnMessageReceivedListener> mListeners;

    private OnTransportClosedListener mOnTransportClosed;

    private static boolean isRequest(int message) {
        return (message & 0xFF000000) == 0x63000000;
    }

    private static boolean isResponse(int message) {
        return (message & 0xFF000000) == 0x33000000;
    }

    @GuardedBy("mPendingRequests")
    protected final SparseArray<CompletableFuture<byte[]>> mPendingRequests =
            new SparseArray<>();
    protected final AtomicInteger mNextSequence = new AtomicInteger();

    Transport(int associationId, ParcelFileDescriptor fd, Context context) {
        mAssociationId = associationId;
        mFd = fd;
        mRemoteIn = new ParcelFileDescriptor.AutoCloseInputStream(fd);
        mRemoteOut = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        mContext = context;
        mListeners = new HashMap<>();
    }

    /**
     * Add a listener when a message is received for the message type
     * @param message Message type
     * @param listener Execute when a message with the type is received
     */
    public void addListener(int message, IOnMessageReceivedListener listener) {
        mListeners.put(message, listener);
    }

    public int getAssociationId() {
        return mAssociationId;
    }

    protected ParcelFileDescriptor getFd() {
        return mFd;
    }

    /**
     * Start listening to messages.
     */
    abstract void start();

    /**
     * Soft stop listening to the incoming data without closing the streams.
     */
    abstract void stop();

    /**
     * Stop listening to the incoming data and close the streams. If a listener for closed event
     * is set, then trigger it to assist with its clean-up.
     */
    void close() {
        if (mOnTransportClosed != null) {
            mOnTransportClosed.onClosed(this);
        }
    }

    protected abstract void sendMessage(int message, int sequence, @NonNull byte[] data)
            throws IOException;

    public Future<byte[]> requestForResponse(int message, byte[] data) {
        if (DEBUG) Slog.d(TAG, "Requesting for response");
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

    protected final void handleMessage(int message, int sequence, @NonNull byte[] data)
            throws IOException {
        if (DEBUG) {
            Slog.d(TAG, "Received message 0x" + Integer.toHexString(message)
                    + " sequence " + sequence + " length " + data.length
                    + " from association " + mAssociationId);
        }

        if (isRequest(message)) {
            try {
                processRequest(message, sequence, data);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to respond to 0x" + Integer.toHexString(message), e);
            }
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
            case MESSAGE_REQUEST_CONTEXT_SYNC: {
                callback(message, data);
                sendMessage(MESSAGE_RESPONSE_SUCCESS, sequence, EmptyArray.BYTE);
                break;
            }
            case MESSAGE_REQUEST_PERMISSION_RESTORE: {
                try {
                    callback(message, data);
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

    private void callback(int message, byte[] data) {
        if (mListeners.containsKey(message)) {
            try {
                mListeners.get(message).onMessageReceived(getAssociationId(), data);
                Slog.i(TAG, "Message 0x" + Integer.toHexString(message)
                        + " is received from associationId " + mAssociationId
                        + ", sending data length " + data.length + " to the listener.");
            } catch (RemoteException ignored) {
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

    void setOnTransportClosedListener(OnTransportClosedListener callback) {
        this.mOnTransportClosed = callback;
    }

    // Interface to pass transport to the transport manager to assist with detachment.
    @FunctionalInterface
    interface OnTransportClosedListener {
        void onClosed(Transport transport);
    }
}
