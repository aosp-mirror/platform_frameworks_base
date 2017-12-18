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

package com.android.server.location;

import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.NanoAppMessage;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that acts as a broker for the ContextHubClient, which handles messaging and life-cycle
 * notification callbacks. This class implements the IContextHubClient object, and the implemented
 * APIs must be thread-safe.
 *
 * @hide
 */
public class ContextHubClientBroker extends IContextHubClient.Stub
        implements IBinder.DeathRecipient {
    private static final String TAG = "ContextHubClientBroker";

    /*
     * The context of the service.
     */
    private final Context mContext;

    /*
     * The proxy to talk to the Context Hub HAL.
     */
    private final IContexthub mContextHubProxy;

    /*
     * The manager that registered this client.
     */
    private final ContextHubClientManager mClientManager;

    /*
     * The ID of the hub that this client is attached to.
     */
    private final int mAttachedContextHubId;

    /*
     * The host end point ID of this client.
     */
    private final short mHostEndPointId;

    /*
     * The remote callback interface for this client.
     */
    private final IContextHubClientCallback mCallbackInterface;

    /*
     * false if the connection has been closed by the client, true otherwise.
     */
    private final AtomicBoolean mConnectionOpen = new AtomicBoolean(true);

    /* package */ ContextHubClientBroker(
            Context context, IContexthub contextHubProxy, ContextHubClientManager clientManager,
            int contextHubId, short hostEndPointId, IContextHubClientCallback callback) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mAttachedContextHubId = contextHubId;
        mHostEndPointId = hostEndPointId;
        mCallbackInterface = callback;
    }

    /**
     * Attaches a death recipient for this client
     *
     * @throws RemoteException if the client has already died
     */
    /* package */ void attachDeathRecipient() throws RemoteException {
        mCallbackInterface.asBinder().linkToDeath(this, 0 /* flags */);
    }

    /**
     * Sends from this client to a nanoapp.
     *
     * @param message the message to send
     * @return the error code of sending the message
     */
    @ContextHubTransaction.Result
    @Override
    public int sendMessageToNanoApp(NanoAppMessage message) {
        ContextHubServiceUtil.checkPermissions(mContext);

        int result;
        if (mConnectionOpen.get()) {
            ContextHubMsg messageToNanoApp = ContextHubServiceUtil.createHidlContextHubMessage(
                    mHostEndPointId, message);

            try {
                result = mContextHubProxy.sendMessageToHub(mAttachedContextHubId, messageToNanoApp);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in sendMessageToNanoApp (target hub ID = "
                        + mAttachedContextHubId + ")", e);
                result = Result.UNKNOWN_FAILURE;
            }
        } else {
            Log.e(TAG, "Failed to send message to nanoapp: client connection is closed");
            result = Result.UNKNOWN_FAILURE;
        }

        return ContextHubServiceUtil.toTransactionResult(result);
    }

    /**
     * Closes the connection for this client with the service.
     */
    @Override
    public void close() {
        if (mConnectionOpen.getAndSet(false)) {
            mClientManager.unregisterClient(mHostEndPointId);
        }
    }

    /**
     * Invoked when the underlying binder of this broker has died at the client process.
     */
    public void binderDied() {
        try {
            IContextHubClient.Stub.asInterface(this).close();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while closing client on death", e);
        }
    }

    /**
     * @return the ID of the context hub this client is attached to
     */
    /* package */ int getAttachedContextHubId() {
        return mAttachedContextHubId;
    }

    /**
     * @return the host endpoint ID of this client
     */
    /* package */ short getHostEndPointId() {
        return mHostEndPointId;
    }

    /**
     * Sends a message to the client associated with this object.
     *
     * @param message the message that came from a nanoapp
     */
    /* package */ void sendMessageToClient(NanoAppMessage message) {
        if (mConnectionOpen.get()) {
            try {
                mCallbackInterface.onMessageFromNanoApp(message);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while sending message to client (host endpoint ID = "
                        + mHostEndPointId + ")", e);
            }
        }
    }

    /**
     * Notifies the client of a nanoapp load event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was loaded.
     */
    /* package */ void onNanoAppLoaded(long nanoAppId) {
        if (mConnectionOpen.get()) {
            try {
                mCallbackInterface.onNanoAppLoaded(nanoAppId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppLoaded on client"
                        + " (host endpoint ID = " + mHostEndPointId + ")", e);
            }
        }
    }

    /**
     * Notifies the client of a nanoapp unload event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was unloaded.
     */
    /* package */ void onNanoAppUnloaded(long nanoAppId) {
        if (mConnectionOpen.get()) {
            try {
                mCallbackInterface.onNanoAppUnloaded(nanoAppId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppUnloaded on client"
                        + " (host endpoint ID = " + mHostEndPointId + ")", e);
            }
        }
    }

    /**
     * Notifies the client of a hub reset event if the connection is open.
     */
    /* package */ void onHubReset() {
        if (mConnectionOpen.get()) {
            try {
                mCallbackInterface.onHubReset();
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onHubReset on client" +
                        " (host endpoint ID = " + mHostEndPointId + ")", e);
            }
        }
    }

    /**
     * Notifies the client of a nanoapp abort event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that aborted
     * @param abortCode the nanoapp specific abort code
     */
    /* package */ void onNanoAppAborted(long nanoAppId, int abortCode) {
        if (mConnectionOpen.get()) {
            try {
                mCallbackInterface.onNanoAppAborted(nanoAppId, abortCode);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppAborted on client"
                        + " (host endpoint ID = " + mHostEndPointId + ")", e);
            }
        }
    }
}
