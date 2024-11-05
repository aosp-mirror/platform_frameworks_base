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

import static android.companion.CompanionDeviceManager.MESSAGE_REQUEST_PERMISSION_RESTORE;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.companion.AssociationInfo;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.companion.association.AssociationStore;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressLint("LongLogTag")
public class CompanionTransportManager {
    private static final String TAG = "CDM_CompanionTransportManager";

    private boolean mSecureTransportEnabled = true;

    private final Context mContext;
    private final AssociationStore mAssociationStore;

    /** Association id -> Transport */
    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();

    // Use mTransports to synchronize both mTransports and mTransportsListeners to avoid deadlock
    // between threads that access both
    @NonNull
    @GuardedBy("mTransports")
    private final RemoteCallbackList<IOnTransportsChangedListener> mTransportsListeners =
            new RemoteCallbackList<>();

    /** Message type -> IOnMessageReceivedListener */
    @GuardedBy("mMessageListeners")
    @NonNull
    private final SparseArray<Set<IOnMessageReceivedListener>> mMessageListeners =
            new SparseArray<>();

    public CompanionTransportManager(Context context, AssociationStore associationStore) {
        mContext = context;
        mAssociationStore = associationStore;
    }

    /**
     * Add a listener to receive callbacks when a message is received for the message type
     */
    public void addListener(int message, @NonNull IOnMessageReceivedListener listener) {
        synchronized (mMessageListeners) {
            if (!mMessageListeners.contains(message)) {
                mMessageListeners.put(message, new HashSet<IOnMessageReceivedListener>());
            }
            mMessageListeners.get(message).add(listener);
        }
        synchronized (mTransports) {
            for (int i = 0; i < mTransports.size(); i++) {
                mTransports.valueAt(i).addListener(message, listener);
            }
        }
    }

    /**
     * Add a listener to receive callbacks when any of the transports is changed
     */
    public void addListener(IOnTransportsChangedListener listener) {
        Slog.i(TAG, "Registering OnTransportsChangedListener");
        synchronized (mTransports) {
            mTransportsListeners.register(listener);
            mTransportsListeners.broadcast(listener1 -> {
                // callback to the current listener with all the associations of the transports
                // immediately
                if (listener1 == listener) {
                    try {
                        listener.onTransportsChanged(getAssociationsWithTransport());
                    } catch (RemoteException ignored) {
                    }
                }
            });
        }
    }

    /**
     * Remove the listener for receiving callbacks when any of the transports is changed
     */
    public void removeListener(IOnTransportsChangedListener listener) {
        synchronized (mTransports) {
            mTransportsListeners.unregister(listener);
        }
    }

    /**
     * Remove the listener to stop receiving calbacks when a message is received for the given type
     */
    public void removeListener(int messageType, IOnMessageReceivedListener listener) {
        synchronized (mMessageListeners) {
            if (!mMessageListeners.contains(messageType)) {
                return;
            }
            mMessageListeners.get(messageType).remove(listener);
        }
    }

    /**
     * Send a message to remote devices through the transports
     */
    public void sendMessage(int message, byte[] data, int[] associationIds) {
        Slog.d(TAG, "Sending message 0x" + Integer.toHexString(message)
                + " data length " + data.length);
        synchronized (mTransports) {
            for (int i = 0; i < associationIds.length; i++) {
                if (mTransports.contains(associationIds[i])) {
                    mTransports.get(associationIds[i]).sendMessage(message, data);
                }
            }
        }
    }

    /**
     * Attach transport.
     */
    public void attachSystemDataTransport(int associationId, ParcelFileDescriptor fd) {
        Slog.i(TAG, "Attaching transport for association id=[" + associationId + "]...");

        mAssociationStore.getAssociationWithCallerChecks(associationId);

        synchronized (mTransports) {
            if (mTransports.contains(associationId)) {
                detachSystemDataTransport(associationId);
            }

            // TODO: Implement new API to pass a PSK
            initializeTransport(associationId, fd, null);

            notifyOnTransportsChanged();
        }

        Slog.i(TAG, "Transport attached.");
    }

    /**
     * Detach transport.
     */
    public void detachSystemDataTransport(int associationId) {
        Slog.i(TAG, "Detaching transport for association id=[" + associationId + "]...");

        mAssociationStore.getAssociationWithCallerChecks(associationId);

        synchronized (mTransports) {
            final Transport transport = mTransports.removeReturnOld(associationId);
            if (transport == null) {
                return;
            }

            transport.stop();
            notifyOnTransportsChanged();
        }

        Slog.i(TAG, "Transport detached.");
    }

    private List<AssociationInfo> getAssociationsWithTransport() {
        List<AssociationInfo> associations = new ArrayList<>();
        synchronized (mTransports) {
            for (int i = 0; i < mTransports.size(); i++) {
                AssociationInfo association = mAssociationStore.getAssociationById(
                        mTransports.keyAt(i));
                if (association != null) {
                    associations.add(association);
                }
            }
        }
        return associations;
    }

    private void notifyOnTransportsChanged() {
        synchronized (mTransports) {
            mTransportsListeners.broadcast(listener -> {
                try {
                    listener.onTransportsChanged(getAssociationsWithTransport());
                } catch (RemoteException ignored) {
                }
            });
        }
    }

    private void initializeTransport(int associationId,
                                     ParcelFileDescriptor fd,
                                     byte[] preSharedKey) {
        Slog.i(TAG, "Initializing transport");
        Transport transport;
        if (!isSecureTransportEnabled()) {
            // If secure transport is explicitly disabled for testing, use raw transport
            Slog.i(TAG, "Secure channel is disabled. Creating raw transport");
            transport = new RawTransport(associationId, fd, mContext);
        } else if (Build.isDebuggable()) {
            // If device is debug build, use hardcoded test key for authentication
            Slog.d(TAG, "Creating an unauthenticated secure channel");
            final byte[] testKey = "CDM".getBytes(StandardCharsets.UTF_8);
            transport = new SecureTransport(associationId, fd, mContext, testKey, null);
        } else if (preSharedKey != null) {
            // If either device is not Android, then use app-specific pre-shared key
            Slog.d(TAG, "Creating a PSK-authenticated secure channel");
            transport = new SecureTransport(associationId, fd, mContext, preSharedKey, null);
        } else {
            // If none of the above applies, then use secure channel with attestation verification
            Slog.d(TAG, "Creating a secure channel");
            transport = new SecureTransport(associationId, fd, mContext);
        }

        addMessageListenersToTransport(transport);
        transport.setOnTransportClosedListener(this::detachSystemDataTransport);
        transport.start();
        synchronized (mTransports) {
            mTransports.put(associationId, transport);
        }

    }

    public Future<?> requestPermissionRestore(int associationId, byte[] data) {
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport == null) {
                return CompletableFuture.failedFuture(new IOException("Missing transport"));
            }
            return transport.sendMessage(MESSAGE_REQUEST_PERMISSION_RESTORE, data);
        }
    }

    /**
     * Dumps current list of active transports.
     */
    public void dump(@NonNull PrintWriter out) {
        synchronized (mTransports) {
            out.append("System Data Transports: ");
            if (mTransports.size() == 0) {
                out.append("<empty>\n");
            } else {
                out.append("\n");
                for (int i = 0; i < mTransports.size(); i++) {
                    final int associationId = mTransports.keyAt(i);
                    final Transport transport = mTransports.get(associationId);
                    out.append("  ").append(transport.toString()).append('\n');
                }
            }
        }
    }

    /**
     * @hide
     */
    public void enableSecureTransport(boolean enabled) {
        this.mSecureTransportEnabled = enabled;
    }

    /**
     * For testing purpose only.
     *
     * Create an emulated RawTransport and notify onTransportChanged listeners.
     */
    public EmulatedTransport createEmulatedTransport(int associationId) {
        synchronized (mTransports) {
            FileDescriptor fd = new FileDescriptor();
            ParcelFileDescriptor pfd = new ParcelFileDescriptor(fd);
            EmulatedTransport transport = new EmulatedTransport(associationId, pfd, mContext);
            addMessageListenersToTransport(transport);
            mTransports.put(associationId, transport);
            notifyOnTransportsChanged();
            return transport;
        }
    }

    /**
     * For testing purposes only.
     *
     * Emulates a transport for incoming messages but black-holes all messages sent back through it.
     */
    public static class EmulatedTransport extends RawTransport {

        EmulatedTransport(int associationId, ParcelFileDescriptor fd, Context context) {
            super(associationId, fd, context);
        }

        /** Process an incoming message for testing purposes. */
        public void processMessage(int messageType, int sequence, byte[] data) throws IOException {
            handleMessage(messageType, sequence, data);
        }

        @Override
        protected void sendMessage(int messageType, int sequence, @NonNull byte[] data)
                throws IOException {
            Slog.e(TAG, "Black-holing emulated message type 0x" + Integer.toHexString(messageType)
                    + " sequence " + sequence + " length " + data.length
                    + " to association " + mAssociationId);
        }
    }

    private boolean isSecureTransportEnabled() {
        return mSecureTransportEnabled;
    }

    private void addMessageListenersToTransport(Transport transport) {
        synchronized (mMessageListeners) {
            for (int i = 0; i < mMessageListeners.size(); i++) {
                for (IOnMessageReceivedListener listener : mMessageListeners.valueAt(i)) {
                    transport.addListener(mMessageListeners.keyAt(i), listener);
                }
            }
        }
    }

    void detachSystemDataTransport(Transport transport) {
        int associationId = transport.mAssociationId;
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (association != null) {
            detachSystemDataTransport(
                    association.getId());
        }
    }
}
