/*
 * Copyright 2017 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.chre.flags.Flags;
import android.os.RemoteException;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class describing a client of the Context Hub Service.
 *
 * <p>Clients can send messages to nanoapps at a Context Hub through this object. The APIs supported
 * by this object are thread-safe and can be used without external synchronization.
 *
 * @hide
 */
@SystemApi
public class ContextHubClient implements Closeable {
    private static final String TAG = "ContextHubClient";

    /*
     * The proxy to the client interface at the service.
     */
    private IContextHubClient mClientProxy = null;

    /*
     * The Context Hub that this client is attached to.
     */
    private final ContextHubInfo mAttachedHub;

    private final CloseGuard mCloseGuard;

    private final AtomicBoolean mIsClosed = new AtomicBoolean(false);

    /*
     * True if this is a persistent client (i.e. does not have to close the connection when the
     * resource is freed from the system).
     */
    private final boolean mPersistent;

    private Integer mId = null;

    /* package */ ContextHubClient(ContextHubInfo hubInfo, boolean persistent) {
        mAttachedHub = hubInfo;
        mPersistent = persistent;
        if (mPersistent) {
            mCloseGuard = null;
        } else {
            mCloseGuard = CloseGuard.get();
            mCloseGuard.open("ContextHubClient.close");
        }
    }

    /**
     * Sets the proxy interface of the client at the service. This method should always be called by
     * the ContextHubManager after the client is registered at the service, and should only be
     * called once.
     *
     * @param clientProxy the proxy of the client at the service
     */
    /* package */ synchronized void setClientProxy(IContextHubClient clientProxy) {
        Objects.requireNonNull(clientProxy, "IContextHubClient cannot be null");
        if (mClientProxy != null) {
            throw new IllegalStateException("Cannot change client proxy multiple times");
        }

        mClientProxy = clientProxy;
        try {
            mId = mClientProxy.getId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        this.notifyAll();
    }

    /**
     * Returns the hub that this client is attached to.
     *
     * @return the ContextHubInfo of the attached hub
     */
    @NonNull
    public ContextHubInfo getAttachedHub() {
        return mAttachedHub;
    }

    /**
     * Returns the system-wide unique identifier for this ContextHubClient.
     *
     * <p>This value can be used as an identifier for the messaging channel between a
     * ContextHubClient and the Context Hub. This may be used as a routing mechanism between various
     * ContextHubClient objects within an application.
     *
     * <p>The value returned by this method will remain the same if it is associated with the same
     * client reference at the ContextHubService (for instance, the ID of a PendingIntent
     * ContextHubClient will remain the same even if the local object has been regenerated with the
     * equivalent PendingIntent). If the ContextHubClient is newly generated (e.g. any regeneration
     * of a callback client, or generation of a non-equal PendingIntent client), the ID will not be
     * the same.
     *
     * @return The ID of this ContextHubClient, in the range [0, 65535].
     */
    @IntRange(from = 0, to = 65535)
    public int getId() {
        if (mId == null) {
            throw new IllegalStateException("ID was not set");
        }
        return (0x0000FFFF & mId);
    }

    /**
     * Closes the connection for this client and the Context Hub Service.
     *
     * <p>When this function is invoked, the messaging associated with this client is invalidated.
     * All futures messages targeted for this client are dropped at the service, and the
     * ContextHubClient is unregistered from the service.
     *
     * <p>If this object has a PendingIntent, i.e. the object was generated via {@link
     * ContextHubManager#createClient(ContextHubInfo, PendingIntent, long)}, then the Intent events
     * corresponding to the PendingIntent will no longer be triggered.
     */
    public void close() {
        if (!mIsClosed.getAndSet(true)) {
            if (mCloseGuard != null) {
                mCloseGuard.close();
            }
            try {
                mClientProxy.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sends a message to a nanoapp through the Context Hub Service.
     *
     * This function returns RESULT_SUCCESS if the message has reached the HAL, but
     * does not guarantee delivery of the message to the target nanoapp.
     * <p>
     * Before sending the first message to your nanoapp, it's recommended that the following
     * operations should be performed:
     * 1) Invoke {@link ContextHubManager#queryNanoApps(ContextHubInfo)} to verify the nanoapp is
     *    present.
     * 2) Validate that you have the permissions to communicate with the nanoapp by looking at
     *    {@link NanoAppState#getNanoAppPermissions}.
     * 3) If you don't have permissions, send an idempotent message to the nanoapp ensuring any
     *    work your app previously may have asked it to do is stopped. This is useful if your app
     *    restarts due to permission changes and no longer has the permissions when it is started
     *    again.
     * 4) If you have valid permissions, send a message to your nanoapp to resubscribe so that it's
     *    aware you have restarted or so you can initially subscribe if this is the first time you
     *    have sent it a message.
     *
     * @param message the message object to send
     * @return the result of sending the message defined as in ContextHubTransaction.Result
     * @throws NullPointerException if NanoAppMessage is null
     * @throws SecurityException if this client doesn't have permissions to send a message to the
     *     nanoapp.
     * @see NanoAppMessage
     * @see ContextHubTransaction.Result
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @ContextHubTransaction.Result
    public int sendMessageToNanoApp(@NonNull NanoAppMessage message) {
        return doSendMessageToNanoApp(message, null);
    }

    /**
     * Sends a reliable message to a nanoapp.
     *
     * This method is similar to {@link ContextHubClient#sendMessageToNanoApp} with the
     * difference that it expects the message to be acknowledged by CHRE.
     *
     * The transaction succeeds after we received an ACK from CHRE without error.
     * In all other cases the transaction will fail.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public ContextHubTransaction<Void> sendReliableMessageToNanoApp(
            @NonNull NanoAppMessage message) {
        ContextHubTransaction<Void> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_RELIABLE_MESSAGE);

        if (!Flags.reliableMessageImplementation() ||
            !mAttachedHub.supportsReliableMessages() ||
            message.isBroadcastMessage()) {
            transaction.setResponse(new ContextHubTransaction.Response<Void>(
                    ContextHubTransaction.RESULT_FAILED_NOT_SUPPORTED, null));
            return transaction;
        }

        IContextHubTransactionCallback callback =
                ContextHubTransactionHelper.createTransactionCallback(transaction);

        @ContextHubTransaction.Result int result = doSendMessageToNanoApp(message, callback);
        if (result != ContextHubTransaction.RESULT_SUCCESS) {
            transaction.setResponse(new ContextHubTransaction.Response<Void>(result, null));
        }

        return transaction;
    }

    /**
     * Sends a message to a nanoapp.
     *
     * @param message The message to send.
     * @param transactionCallback The callback to use when the message is reliable. null for regular
     *         messages.
     * @return A {@link ContextHubTransaction.Result} error code.
     */
    @ContextHubTransaction.Result
    private int doSendMessageToNanoApp(@NonNull NanoAppMessage message,
            @Nullable IContextHubTransactionCallback transactionCallback) {
        Objects.requireNonNull(message, "NanoAppMessage cannot be null");

        int maxPayloadBytes = mAttachedHub.getMaxPacketLengthBytes();

        byte[] payload = message.getMessageBody();
        if (payload != null && payload.length > maxPayloadBytes) {
            Log.e(TAG,
                    "Message (%d bytes) exceeds max payload length (%d bytes)".formatted(
                            payload.length, maxPayloadBytes));
            return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
        }

        try {
            if (transactionCallback == null) {
                return mClientProxy.sendMessageToNanoApp(message);
            }
            return mClientProxy.sendReliableMessageToNanoApp(message, transactionCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            if (!mPersistent) {
                close();
            }
        } finally {
            super.finalize();
        }
    }

    /** @hide */
    public synchronized void callbackFinished() {
        try {
            waitForClientProxy();
            mClientProxy.callbackFinished();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public synchronized void reliableMessageCallbackFinished(int messageSequenceNumber,
            byte errorCode) {
        try {
            waitForClientProxy();
            mClientProxy.reliableMessageCallbackFinished(messageSequenceNumber, errorCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    private void waitForClientProxy() {
        while (mClientProxy == null) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
