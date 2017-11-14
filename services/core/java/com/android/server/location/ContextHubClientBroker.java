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
import android.os.RemoteException;
import android.util.Log;

/**
 * A broker for the ContextHubClient that handles messaging and life-cycle notification callbacks.
 *
 * @hide
 */
public class ContextHubClientBroker extends IContextHubClient.Stub {
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

    /* package */ ContextHubClientBroker(
            Context context, IContexthub contextHubProxy, int contextHubId, short hostEndPointId,
            IContextHubClientCallback callback) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mAttachedContextHubId = contextHubId;
        mHostEndPointId = hostEndPointId;
        mCallbackInterface = callback;
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
        ContextHubMsg messageToNanoApp =
                ContextHubServiceUtil.createHidlContextHubMessage(mHostEndPointId, message);

        int result;
        try {
            result = mContextHubProxy.sendMessageToHub(mAttachedContextHubId, messageToNanoApp);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in sendMessageToNanoApp (target hub ID = "
                    + mAttachedContextHubId + ")", e);
            result = Result.UNKNOWN_FAILURE;
        }
        return ContextHubServiceUtil.toTransactionResult(result);
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
        try {
            mCallbackInterface.onMessageFromNanoApp(message);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while sending message to client (host endpoint ID = "
                    + mHostEndPointId + ")", e);
        }
    }
}
