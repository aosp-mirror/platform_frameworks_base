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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.os.RemoteException;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class describing a client of the Context Hub Service.
 *
 * Clients can send messages to nanoapps at a Context Hub through this object. The APIs supported
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

    /* package */ ContextHubClient(ContextHubInfo hubInfo, boolean persistent) {
        mAttachedHub = hubInfo;
        mPersistent = persistent;
        if (mPersistent) {
            mCloseGuard = null;
        } else {
            mCloseGuard = CloseGuard.get();
            mCloseGuard.open("close");
        }
    }

    /**
     * Sets the proxy interface of the client at the service. This method should always be called
     * by the ContextHubManager after the client is registered at the service, and should only be
     * called once.
     *
     * @param clientProxy the proxy of the client at the service
     */
    /* package */ void setClientProxy(IContextHubClient clientProxy) {
        Objects.requireNonNull(clientProxy, "IContextHubClient cannot be null");
        if (mClientProxy != null) {
            throw new IllegalStateException("Cannot change client proxy multiple times");
        }

        mClientProxy = clientProxy;
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
     * Closes the connection for this client and the Context Hub Service.
     *
     * When this function is invoked, the messaging associated with this client is invalidated.
     * All futures messages targeted for this client are dropped at the service, and the
     * ContextHubClient is unregistered from the service.
     *
     * If this object has a PendingIntent, i.e. the object was generated via
     * {@link ContextHubManager.createClient(PendingIntent, ContextHubInfo, long)}, then the
     * Intent events corresponding to the PendingIntent will no longer be triggered.
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
     *
     * @param message the message object to send
     *
     * @return the result of sending the message defined as in ContextHubTransaction.Result
     *
     * @throws NullPointerException if NanoAppMessage is null
     *
     * @see NanoAppMessage
     * @see ContextHubTransaction.Result
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.LOCATION_HARDWARE,
            android.Manifest.permission.ACCESS_CONTEXT_HUB
    })
    @ContextHubTransaction.Result
    public int sendMessageToNanoApp(@NonNull NanoAppMessage message) {
        Objects.requireNonNull(message, "NanoAppMessage cannot be null");

        int maxPayloadBytes = mAttachedHub.getMaxPacketLengthBytes();
        byte[] payload = message.getMessageBody();
        if (payload != null && payload.length > maxPayloadBytes) {
            Log.e(TAG, "Message (" + payload.length + " bytes) exceeds max payload length ("
                    + maxPayloadBytes + " bytes)");
            return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
        }

        try {
            return mClientProxy.sendMessageToNanoApp(message);
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
}
