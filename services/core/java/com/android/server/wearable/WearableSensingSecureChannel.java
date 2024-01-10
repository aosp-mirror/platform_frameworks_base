/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wearable;

import android.annotation.NonNull;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A wrapper that manages a CompanionDeviceManager secure channel for wearable sensing.
 *
 * <p>This wrapper accepts a connection to a wearable from the caller. It then attaches the
 * connection to the CompanionDeviceManager via {@link
 * CompanionDeviceManager#attachSystemDataTransport(int, InputStream, OutputStream)}, which will
 * create an encrypted channel using the provided connection as the raw underlying connection. The
 * wearable device is expected to attach its side of the raw connection to its
 * CompanionDeviceManager via the same method so that the two CompanionDeviceManagers on the two
 * devices can perform attestation and set up the encrypted channel. Attestation requirements are
 * listed in {@link com.android.server.security.AttestationVerificationPeerDeviceVerifier}.
 *
 * <p>When the encrypted channel is available, it will be provided to the caller via the
 * SecureTransportListener.
 */
final class WearableSensingSecureChannel {

    /** A listener for secure transport and its error signal. */
    interface SecureTransportListener {

        /** Called when the secure transport is available. */
        void onSecureTransportAvailable(ParcelFileDescriptor secureTransport);

        /**
         * Called when there is a non-recoverable error. The secure channel will be automatically
         * closed.
         */
        void onError();
    }

    private static final String TAG = WearableSensingSecureChannel.class.getSimpleName();
    private static final String CDM_ASSOCIATION_DISPLAY_NAME = "PlaceholderDisplayNameFromWSM";
    // The batch size of reading from the ParcelFileDescriptor returned to mSecureTransportListener
    private static final int READ_BUFFER_SIZE = 8192;

    private final Object mLock = new Object();
    // CompanionDeviceManager (CDM) can continue to call these ExecutorServices even after the
    // corresponding cleanup methods in CDM have been called (e.g.
    // removeOnTransportsChangedListener). Since we shut down these ExecutorServices after
    // clean up, we use SoftShutdownExecutor to suppress RejectedExecutionExceptions.
    private final SoftShutdownExecutor mMessageFromWearableExecutor =
            new SoftShutdownExecutor(Executors.newSingleThreadExecutor());
    private final SoftShutdownExecutor mMessageToWearableExecutor =
            new SoftShutdownExecutor(Executors.newSingleThreadExecutor());
    private final SoftShutdownExecutor mLightWeightExecutor =
            new SoftShutdownExecutor(Executors.newSingleThreadExecutor());
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final ParcelFileDescriptor mUnderlyingTransport;
    private final SecureTransportListener mSecureTransportListener;
    private final AtomicBoolean mTransportAvailable = new AtomicBoolean(false);
    private final Consumer<List<AssociationInfo>> mOnTransportsChangedListener =
            this::onTransportsChanged;
    private final BiConsumer<Integer, byte[]> mOnMessageReceivedListener = this::onMessageReceived;
    private final ParcelFileDescriptor mRemoteFd; // To be returned to mSecureTransportListener
    // read input received from the ParcelFileDescriptor returned to mSecureTransportListener
    private final InputStream mLocalIn;
    // send output to the ParcelFileDescriptor returned to mSecureTransportListener
    private final OutputStream mLocalOut;

    @GuardedBy("mLock")
    private boolean mClosed = false;

    private Integer mAssociationId = null;

    /**
     * Creates a WearableSensingSecureChannel. When the secure transport is ready,
     * secureTransportListener will be notified.
     *
     * @param companionDeviceManager The CompanionDeviceManager system service.
     * @param underlyingTransport The underlying transport to create the secure channel on.
     * @param secureTransportListener The listener to receive the secure transport when it is ready.
     * @throws IOException if it cannot create a {@link ParcelFileDescriptor} socket pair.
     */
    static WearableSensingSecureChannel create(
            @NonNull CompanionDeviceManager companionDeviceManager,
            @NonNull ParcelFileDescriptor underlyingTransport,
            @NonNull SecureTransportListener secureTransportListener)
            throws IOException {
        Objects.requireNonNull(companionDeviceManager);
        Objects.requireNonNull(underlyingTransport);
        Objects.requireNonNull(secureTransportListener);
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        WearableSensingSecureChannel channel =
                new WearableSensingSecureChannel(
                        companionDeviceManager,
                        underlyingTransport,
                        secureTransportListener,
                        pair[0],
                        pair[1]);
        channel.initialize();
        return channel;
    }

    private WearableSensingSecureChannel(
            CompanionDeviceManager companionDeviceManager,
            ParcelFileDescriptor underlyingTransport,
            SecureTransportListener secureTransportListener,
            ParcelFileDescriptor remoteFd,
            ParcelFileDescriptor localFd) {
        mCompanionDeviceManager = companionDeviceManager;
        mUnderlyingTransport = underlyingTransport;
        mSecureTransportListener = secureTransportListener;
        mRemoteFd = remoteFd;
        mLocalIn = new AutoCloseInputStream(localFd);
        mLocalOut = new AutoCloseOutputStream(localFd);
    }

    private void initialize() {
        final long originalCallingIdentity = Binder.clearCallingIdentity();
        try {
            Slog.d(TAG, "Requesting CDM association.");
            mCompanionDeviceManager.associate(
                    new AssociationRequest.Builder()
                            .setDisplayName(CDM_ASSOCIATION_DISPLAY_NAME)
                            .setSelfManaged(true)
                            .build(),
                    mLightWeightExecutor,
                    new CompanionDeviceManager.Callback() {
                        @Override
                        public void onAssociationCreated(AssociationInfo associationInfo) {
                            WearableSensingSecureChannel.this.onAssociationCreated(
                                    associationInfo.getId());
                        }

                        @Override
                        public void onFailure(CharSequence error) {
                            Slog.e(
                                    TAG,
                                    "Failed to create CompanionDeviceManager association: "
                                            + error);
                            onError();
                        }
                    });
        } finally {
            Binder.restoreCallingIdentity(originalCallingIdentity);
        }
    }

    private void onAssociationCreated(int associationId) {
        Slog.i(TAG, "CDM association created.");
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mAssociationId = associationId;
            mCompanionDeviceManager.addOnMessageReceivedListener(
                    mMessageFromWearableExecutor,
                    CompanionDeviceManager.MESSAGE_ONEWAY_FROM_WEARABLE,
                    mOnMessageReceivedListener);
            mCompanionDeviceManager.addOnTransportsChangedListener(
                    mLightWeightExecutor, mOnTransportsChangedListener);
            mCompanionDeviceManager.attachSystemDataTransport(
                    associationId,
                    new AutoCloseInputStream(mUnderlyingTransport),
                    new AutoCloseOutputStream(mUnderlyingTransport));
        }
    }

    private void onTransportsChanged(List<AssociationInfo> associationInfos) {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            if (mAssociationId == null) {
                Slog.e(TAG, "mAssociationId is null when transport changed");
                return;
            }
        }
        // Do not call onTransportAvailable() or onError() when holding the lock because it can
        // cause a deadlock if the callback holds another lock.
        boolean transportAvailable =
                associationInfos.stream().anyMatch(info -> info.getId() == mAssociationId);
        if (transportAvailable && mTransportAvailable.compareAndSet(false, true)) {
            onTransportAvailable();
        } else if (!transportAvailable && mTransportAvailable.compareAndSet(true, false)) {
            Slog.i(TAG, "CDM transport is detached. This is not recoverable.");
            onError();
        }
    }

    private void onTransportAvailable() {
        // Start sending data received from the remote stream to the wearable.
        Slog.i(TAG, "Transport available");
        mMessageToWearableExecutor.execute(
                () -> {
                    int[] associationIdsToSendMessageTo = new int[] {mAssociationId};
                    byte[] buffer = new byte[READ_BUFFER_SIZE];
                    int readLen;
                    try {
                        while ((readLen = mLocalIn.read(buffer)) != -1) {
                            byte[] data = new byte[readLen];
                            System.arraycopy(buffer, 0, data, 0, readLen);
                            Slog.v(TAG, "Sending message to wearable");
                            mCompanionDeviceManager.sendMessage(
                                    CompanionDeviceManager.MESSAGE_ONEWAY_TO_WEARABLE,
                                    data,
                                    associationIdsToSendMessageTo);
                        }
                    } catch (IOException e) {
                        Slog.i(TAG, "IOException while reading from remote stream.");
                        onError();
                        return;
                    }
                    Slog.i(
                            TAG,
                            "Reached EOF when reading from remote stream. Reporting this as an"
                                    + " error.");
                    onError();
                });
        mSecureTransportListener.onSecureTransportAvailable(mRemoteFd);
    }

    private void onMessageReceived(int associationIdForMessage, byte[] data) {
        if (associationIdForMessage == mAssociationId) {
            Slog.v(TAG, "Received message from wearable.");
            try {
                mLocalOut.write(data);
                mLocalOut.flush();
            } catch (IOException e) {
                Slog.i(
                        TAG,
                        "IOException when writing to remote stream. Closing the secure channel.");
                onError();
            }
        } else {
            Slog.v(
                    TAG,
                    "Received CDM message of type MESSAGE_ONEWAY_FROM_WEARABLE, but it is for"
                        + " another association. Ignoring the message.");
        }
    }

    private void onError() {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
        }
        mSecureTransportListener.onError();
        close();
    }

    /** Closes this secure channel and releases all resources. */
    void close() {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            Slog.i(TAG, "Closing WearableSensingSecureChannel.");
            mClosed = true;
            if (mAssociationId != null) {
                final long originalCallingIdentity = Binder.clearCallingIdentity();
                try {
                    mCompanionDeviceManager.removeOnTransportsChangedListener(
                            mOnTransportsChangedListener);
                    mCompanionDeviceManager.removeOnMessageReceivedListener(
                            CompanionDeviceManager.MESSAGE_ONEWAY_FROM_WEARABLE,
                            mOnMessageReceivedListener);
                    mCompanionDeviceManager.detachSystemDataTransport(mAssociationId);
                    mCompanionDeviceManager.disassociate(mAssociationId);
                } finally {
                    Binder.restoreCallingIdentity(originalCallingIdentity);
                }
            }
            try {
                mLocalIn.close();
            } catch (IOException ex) {
                Slog.e(TAG, "Encountered IOException when closing local input stream.", ex);
            }
            try {
                mLocalOut.close();
            } catch (IOException ex) {
                Slog.e(TAG, "Encountered IOException when closing local output stream.", ex);
            }
            mMessageFromWearableExecutor.shutdown();
            mMessageToWearableExecutor.shutdown();
            mLightWeightExecutor.shutdown();
        }
    }

    /**
     * An executor that can be shutdown. Unlike an ExecutorService, it will not throw a
     * RejectedExecutionException if {@link #execute(Runnable)} is called after shutdown.
     */
    private static class SoftShutdownExecutor implements Executor {

        private final ExecutorService mExecutorService;

        SoftShutdownExecutor(ExecutorService executorService) {
            mExecutorService = executorService;
        }

        @Override
        public void execute(Runnable runnable) {
            try {
                mExecutorService.execute(runnable);
            } catch (RejectedExecutionException ex) {
                Slog.d(TAG, "Received new runnable after shutdown. Ignoring.");
            }
        }

        /** Shutdown the underlying ExecutorService. */
        void shutdown() {
            mExecutorService.shutdown();
        }
    }
}
