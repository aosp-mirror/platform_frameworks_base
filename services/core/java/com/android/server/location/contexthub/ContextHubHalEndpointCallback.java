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
package com.android.server.location.contexthub;

import android.hardware.contexthub.EndpointId;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubMessage;
import android.hardware.contexthub.IEndpointCallback;
import android.hardware.contexthub.Message;
import android.hardware.contexthub.MessageDeliveryStatus;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;

/** IEndpointCallback implementation. */
public class ContextHubHalEndpointCallback
        extends android.hardware.contexthub.IEndpointCallback.Stub {
    private final IEndpointLifecycleCallback mEndpointLifecycleCallback;
    private final IEndpointSessionCallback mEndpointSessionCallback;

    // Use this thread in case where the execution requires to be on an async service thread.
    private final HandlerThread mHandlerThread =
            new HandlerThread("Context Hub endpoint callback", Process.THREAD_PRIORITY_BACKGROUND);
    private Handler mHandler;

    /** Interface for listening for endpoint start and stop events. */
    public interface IEndpointLifecycleCallback {
        /** Called when a batch of endpoints started. */
        void onEndpointStarted(HubEndpointInfo[] endpointInfos);

        /** Called when a batch of endpoints stopped. */
        void onEndpointStopped(HubEndpointInfo.HubEndpointIdentifier[] endpointIds, byte reason);
    }

    /** Interface for listening for endpoint session events. */
    public interface IEndpointSessionCallback {
        /** Called when an endpoint session open is requested by the HAL. */
        void onEndpointSessionOpenRequest(
                int sessionId,
                HubEndpointInfo.HubEndpointIdentifier destinationId,
                HubEndpointInfo.HubEndpointIdentifier initiatorId,
                String serviceDescriptor);

        /** Called when a endpoint close session is completed. */
        void onCloseEndpointSession(int sessionId, byte reason);

        /** Called when a requested endpoint open session is completed */
        void onEndpointSessionOpenComplete(int sessionId);

        /** Called when a message is received for the session */
        void onMessageReceived(int sessionId, HubMessage message);

        /** Called when a message delivery status is received for the session */
        void onMessageDeliveryStatusReceived(int sessionId, int sequenceNumber, byte errorCode);
    }

    ContextHubHalEndpointCallback(
            IEndpointLifecycleCallback endpointLifecycleCallback,
            IEndpointSessionCallback endpointSessionCallback) {
        mEndpointLifecycleCallback = endpointLifecycleCallback;
        mEndpointSessionCallback = endpointSessionCallback;

        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void onEndpointStarted(android.hardware.contexthub.EndpointInfo[] halEndpointInfos)
            throws RemoteException {
        if (halEndpointInfos.length == 0) {
            return;
        }
        HubEndpointInfo[] endpointInfos = new HubEndpointInfo[halEndpointInfos.length];
        for (int i = 0; i < halEndpointInfos.length; i++) {
            endpointInfos[i] = new HubEndpointInfo(halEndpointInfos[i]);
        }
        mHandler.post(() -> mEndpointLifecycleCallback.onEndpointStarted(endpointInfos));
    }

    @Override
    public void onEndpointStopped(EndpointId[] halEndpointIds, byte reason) throws RemoteException {
        HubEndpointInfo.HubEndpointIdentifier[] endpointIds =
                new HubEndpointInfo.HubEndpointIdentifier[halEndpointIds.length];
        for (int i = 0; i < halEndpointIds.length; i++) {
            endpointIds[i] = new HubEndpointInfo.HubEndpointIdentifier(halEndpointIds[i]);
        }
        mHandler.post(() -> mEndpointLifecycleCallback.onEndpointStopped(endpointIds, reason));
    }

    @Override
    public void onEndpointSessionOpenRequest(
            int sessionId, EndpointId destination, EndpointId initiator, String serviceDescriptor)
            throws RemoteException {
        HubEndpointInfo.HubEndpointIdentifier destinationId =
                new HubEndpointInfo.HubEndpointIdentifier(destination.hubId, destination.id);
        HubEndpointInfo.HubEndpointIdentifier initiatorId =
                new HubEndpointInfo.HubEndpointIdentifier(initiator.hubId, initiator.id);
        mHandler.post(
                () ->
                        mEndpointSessionCallback.onEndpointSessionOpenRequest(
                                sessionId, destinationId, initiatorId, serviceDescriptor));
    }

    @Override
    public void onCloseEndpointSession(int sessionId, byte reason) throws RemoteException {
        mHandler.post(() -> mEndpointSessionCallback.onCloseEndpointSession(sessionId, reason));
    }

    @Override
    public void onEndpointSessionOpenComplete(int sessionId) throws RemoteException {
        mHandler.post(() -> mEndpointSessionCallback.onEndpointSessionOpenComplete(sessionId));
    }

    @Override
    public void onMessageReceived(int sessionId, Message message) throws RemoteException {
        HubMessage hubMessage = ContextHubServiceUtil.createHubMessage(message);
        mHandler.post(() -> mEndpointSessionCallback.onMessageReceived(sessionId, hubMessage));
    }

    @Override
    public void onMessageDeliveryStatusReceived(
            int sessionId, MessageDeliveryStatus messageDeliveryStatus) throws RemoteException {
        mHandler.post(
                () ->
                        mEndpointSessionCallback.onMessageDeliveryStatusReceived(
                                sessionId,
                                messageDeliveryStatus.messageSequenceNumber,
                                messageDeliveryStatus.errorCode));
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        return IEndpointCallback.VERSION;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        return IEndpointCallback.HASH;
    }
}
