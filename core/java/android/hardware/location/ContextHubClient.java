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
     * Registers to receive persistent intents for a given nanoapp.
     *
     * This method should be used if the caller wants to receive notifications even after the
     * process exits. The client must have an open connection with the Context Hub Service (i.e. it
     * cannot have been closed through the {@link #close()} method). Only one PendingIntent can be
     * registered at a time for a single ContextHubClient. If registered successfully, intents will
     * be delivered regarding events for the specified nanoapp from the attached Context Hub. Any
     * unicast messages for this client will also be delivered. The intent will have an extra
     * {@link ContextHubManager.EXTRA_CONTEXT_HUB_INFO} of type {@link ContextHubInfo}, which
     * describes the Context Hub the intent event was for. The intent will also have an extra
     * {@link ContextHubManager.EXTRA_EVENT_TYPE} of type {@link ContextHubManager.Event}, which
     * will contain the type of the event. See {@link ContextHubManager.Event} for description of
     * each event type, along with event-specific extra fields.
     *
     * When the intent is received, this client can be recreated through
     * {@link ContextHubManager.createClient(PendingIntent, ContextHubInfo,
     * ContextHubClientCallback, Exectutor)}. When recreated, the client can be treated as the
     * same endpoint entity from a nanoapp's perspective, and can be continued to be used to send
     * messages even if the original process has exited.
     *
     * Intents will be delivered until it is unregistered through
     * {@link #unregisterIntent(PendingIntent)}. Note that the registration of this client will
     * continued to be maintained at the Context Hub Service until
     * {@link #unregisterIntent(PendingIntent)} is called for registered intents.
     *
     * See {@link ContextHubBroadcastReceiver} for a helper class to generate the
     * {@link PendingIntent} through a {@link BroadcastReceiver}, and maps an {@link Intent} to a
     * {@link ContextHubClientCallback}.
     *
     * @param intent    The PendingIntent to register for this client
     * @param nanoAppId the unique ID of the nanoapp to receive events for
     * @return true on success, false otherwise
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public boolean registerIntent(@NonNull PendingIntent intent, long nanoAppId) {
        // TODO: Implement this
        return false;
    }

    /**
     * Unregisters an intent previously registered via {@link #registerIntent(PendingIntent, long)}.
     * If this intent has not been registered for this client, this method returns false.
     *
     * @return true on success, false otherwise
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    public boolean unregisterIntent(@NonNull PendingIntent intent) {
        // TODO: Implement this
        return false;
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
