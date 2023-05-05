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

import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_PERMISSION_RESTORE;
import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_PLATFORM_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.companion.AssociationInfo;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.companion.AssociationStore;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressLint("LongLogTag")
public class CompanionTransportManager {
    private static final String TAG = "CDM_CompanionTransportManager";
    private static final boolean DEBUG = false;

    private static final int SECURE_CHANNEL_AVAILABLE_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    private static final int NON_ANDROID = -1;

    private boolean mSecureTransportEnabled = true;

    private static boolean isRequest(int message) {
        return (message & 0xFF000000) == 0x63000000;
    }

    private static boolean isResponse(int message) {
        return (message & 0xFF000000) == 0x33000000;
    }

    private final Context mContext;
    private final AssociationStore mAssociationStore;

    /** Association id -> Transport */
    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();
    @NonNull
    private final RemoteCallbackList<IOnTransportsChangedListener> mTransportsListeners =
            new RemoteCallbackList<>();
    /** Message type -> IOnMessageReceivedListener */
    @NonNull
    private final SparseArray<IOnMessageReceivedListener> mMessageListeners = new SparseArray<>();


    @Nullable
    private Transport mTempTransport;

    public CompanionTransportManager(Context context, AssociationStore associationStore) {
        mContext = context;
        mAssociationStore = associationStore;
    }

    /**
     * Add a listener to receive callbacks when a message is received for the message type
     */
    public void addListener(int message, @NonNull IOnMessageReceivedListener listener) {
        mMessageListeners.put(message, listener);
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
        mTransportsListeners.register(listener);
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
        mTransportsListeners.broadcast(listener1 -> {
            // callback to the current listener with all the associations of the transports
            // immediately
            if (listener1 == listener) {
                try {
                    listener.onTransportsChanged(associations);
                } catch (RemoteException ignored) {
                }
            }
        });
    }

    /**
     * Remove the listener for receiving callbacks when any of the transports is changed
     */
    public void removeListener(IOnTransportsChangedListener listener) {
        mTransportsListeners.unregister(listener);
    }

    /**
     * Remove the listener to stop receiving calbacks when a message is received for the given type
     */
    public void removeListener(int messageType, IOnMessageReceivedListener listener) {
        mMessageListeners.remove(messageType);
    }

    /**
     * Send a message to remote devices through the transports
     */
    public void sendMessage(int message, byte[] data, int[] associationIds) {
        Slog.i(TAG, "Sending message 0x" + Integer.toHexString(message)
                + " data length " + data.length);
        synchronized (mTransports) {
            for (int i = 0; i < associationIds.length; i++) {
                if (mTransports.contains(associationIds[i])) {
                    try {
                        mTransports.get(associationIds[i]).sendMessage(message, data);
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to send message 0x" + Integer.toHexString(message)
                                + " data length " + data.length + " to association "
                                + associationIds[i]);
                    }
                }
            }
        }
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

            initializeTransport(associationId, fd);

            notifyOnTransportsChanged();
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

            notifyOnTransportsChanged();
        }
    }

    private void notifyOnTransportsChanged() {
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
        mTransportsListeners.broadcast(listener -> {
            try {
                listener.onTransportsChanged(associations);
            } catch (RemoteException ignored) {
            }
        });
    }

    private void initializeTransport(int associationId, ParcelFileDescriptor fd) {
        Slog.i(TAG, "Initializing transport");
        if (!isSecureTransportEnabled()) {
            Transport transport = new RawTransport(associationId, fd, mContext);
            addMessageListenersToTransport(transport);
            transport.start();
            synchronized (mTransports) {
                mTransports.put(associationId, transport);
            }
            Slog.i(TAG, "RawTransport is created");
            return;
        }

        // Exchange platform info to decide which transport should be created
        mTempTransport = new RawTransport(associationId, fd, mContext);
        addMessageListenersToTransport(mTempTransport);
        IOnMessageReceivedListener listener = new IOnMessageReceivedListener() {
            @Override
            public void onMessageReceived(int associationId, byte[] data) throws RemoteException {
                synchronized (mTransports) {
                    onPlatformInfoReceived(associationId, data);
                }
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
        mTempTransport.addListener(MESSAGE_REQUEST_PLATFORM_INFO, listener);
        mTempTransport.start();

        int sdk = Build.VERSION.SDK_INT;
        String release = Build.VERSION.RELEASE;
        // data format: | SDK_INT (int) | release length (int) | release |
        final ByteBuffer data = ByteBuffer.allocate(4 + 4 + release.getBytes().length)
                .putInt(sdk)
                .putInt(release.getBytes().length)
                .put(release.getBytes());

        // TODO: it should check if preSharedKey is given
        try {
            mTempTransport.sendMessage(MESSAGE_REQUEST_PLATFORM_INFO, data.array());
        } catch (IOException e) {
            Slog.e(TAG, "Failed to exchange platform info");
        }
    }

    /**
     * Depending on the remote platform info to decide which transport should be created
     */
    private void onPlatformInfoReceived(int associationId, byte[] data) {
        if (mTempTransport.getAssociationId() != associationId) {
            return;
        }
        // TODO: it should check if preSharedKey is given

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int remoteSdk = buffer.getInt();
        byte[] remoteRelease = new byte[buffer.getInt()];
        buffer.get(remoteRelease);

        Slog.i(TAG, "Remote device SDK: " + remoteSdk + ", release:" + new String(remoteRelease));

        Transport transport = mTempTransport;
        mTempTransport.stop();

        int sdk = Build.VERSION.SDK_INT;
        String release = Build.VERSION.RELEASE;

        if (sdk < SECURE_CHANNEL_AVAILABLE_SDK || remoteSdk < SECURE_CHANNEL_AVAILABLE_SDK) {
            // If either device is Android T or below, use raw channel
            // TODO: depending on the release version, either
            //       1) using a RawTransport for old T versions
            //       2) or an Ukey2 handshaked transport for UKey2 backported T versions
            Slog.d(TAG, "Secure channel is not supported. Using raw transport");
            transport = new RawTransport(transport.getAssociationId(), transport.getFd(), mContext);
        } else if (Build.isDebuggable()) {
            // If device is debug build, use hardcoded test key for authentication
            Slog.d(TAG, "Creating an unauthenticated secure channel");
            final byte[] testKey = "CDM".getBytes(StandardCharsets.UTF_8);
            transport = new SecureTransport(transport.getAssociationId(), transport.getFd(),
                    mContext, testKey, null);
        } else if (sdk == NON_ANDROID || remoteSdk == NON_ANDROID) {
            // If either device is not Android, then use app-specific pre-shared key
            // TODO: pass in a real preSharedKey
            Slog.d(TAG, "Creating a PSK-authenticated secure channel");
            transport = new SecureTransport(transport.getAssociationId(), transport.getFd(),
                    mContext, new byte[0], null);
        } else {
            // If none of the above applies, then use secure channel with attestation verification
            Slog.d(TAG, "Creating a secure channel");
            transport = new SecureTransport(transport.getAssociationId(), transport.getFd(),
                    mContext);
        }
        addMessageListenersToTransport(transport);
        transport.start();
        synchronized (mTransports) {
            mTransports.put(transport.getAssociationId(), transport);
        }
        // Doesn't need to notifyTransportsChanged here, it'll be done in attachSystemDataTransport
    }

    public Future<?> requestPermissionRestore(int associationId, byte[] data) {
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport == null) {
                return CompletableFuture.failedFuture(new IOException("Missing transport"));
            }
            return transport.requestForResponse(MESSAGE_REQUEST_PERMISSION_RESTORE, data);
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
        boolean enabled = !Build.IS_DEBUGGABLE || mSecureTransportEnabled;

        return enabled;
    }

    private void addMessageListenersToTransport(Transport transport) {
        for (int i = 0; i < mMessageListeners.size(); i++) {
            transport.addListener(mMessageListeners.keyAt(i), mMessageListeners.valueAt(i));
        }
    }
}
