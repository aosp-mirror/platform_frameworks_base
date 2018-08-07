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
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.Closeable;
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
    /*
     * The proxy to the client interface at the service.
     */
    private IContextHubClient mClientProxy = null;

    /*
     * The Context Hub that this client is attached to.
     */
    private final ContextHubInfo mAttachedHub;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final AtomicBoolean mIsClosed = new AtomicBoolean(false);

    /* package */ ContextHubClient(ContextHubInfo hubInfo) {
        mAttachedHub = hubInfo;
        mCloseGuard.open("close");
    }

    /**
     * Sets the proxy interface of the client at the service. This method should always be called
     * by the ContextHubManager after the client is registered at the service, and should only be
     * called once.
     *
     * @param clientProxy the proxy of the client at the service
     */
    /* package */ void setClientProxy(IContextHubClient clientProxy) {
        Preconditions.checkNotNull(clientProxy, "IContextHubClient cannot be null");
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
     * All futures messages targeted for this client are dropped at the service.
     */
    public void close() {
        if (!mIsClosed.getAndSet(true)) {
            mCloseGuard.close();
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
     * This function returns TRANSACTION_SUCCESS if the message has reached the HAL, but
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
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    @ContextHubTransaction.Result
    public int sendMessageToNanoApp(@NonNull NanoAppMessage message) {
        Preconditions.checkNotNull(message, "NanoAppMessage cannot be null");

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
            close();
        } finally {
            super.finalize();
        }
    }
}
