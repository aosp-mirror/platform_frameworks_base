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

import android.content.Context;
import android.hardware.contexthub.EndpointInfo;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubMessage;
import android.hardware.contexthub.HubServiceInfo;
import android.hardware.contexthub.IContextHubEndpoint;
import android.hardware.contexthub.IContextHubEndpointCallback;
import android.hardware.location.IContextHubTransactionCallback;

/**
 * A class that represents a broker for the endpoint registered by the client app. This class
 * manages direct IContextHubEndpoint/IContextHubEndpointCallback API/callback calls.
 *
 * @hide
 */
public class ContextHubEndpointBroker extends IContextHubEndpoint.Stub {
    private static final String TAG = "ContextHubEndpointBroker";

    /** The context of the service. */
    private final Context mContext;

    /** The proxy to talk to the Context Hub HAL. */
    private final IContextHubWrapper mContextHubProxy;

    /** The manager that registered this endpoint. */
    private final ContextHubEndpointManager mEndpointManager;

    /** Metadata about this endpoint (app-facing container). */
    private final HubEndpointInfo mEndpointInfo;

    /** Metadata about this endpoint (HAL-facing container). */
    private final EndpointInfo mHalEndpointInfo;

    /** The remote callback interface for this endpoint. */
    private final IContextHubEndpointCallback mContextHubEndpointCallback;

    /* package */ ContextHubEndpointBroker(
            Context context,
            IContextHubWrapper contextHubProxy,
            ContextHubEndpointManager endpointManager,
            EndpointInfo halEndpointInfo,
            IContextHubEndpointCallback callback) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mEndpointManager = endpointManager;
        mEndpointInfo = new HubEndpointInfo(halEndpointInfo);
        mHalEndpointInfo = halEndpointInfo;
        mContextHubEndpointCallback = callback;
    }

    @Override
    public HubEndpointInfo getAssignedHubEndpointInfo() {
        return mEndpointInfo;
    }

    @Override
    public int openSession(HubEndpointInfo destination, HubServiceInfo serviceInfo) {
        // TODO(b/378487799): Implement this
        return 0;
    }

    @Override
    public void closeSession(int sessionId, int reason) {
        // TODO(b/378487799): Implement this

    }

    @Override
    public void openSessionRequestComplete(int sessionId) {
        // TODO(b/378487799): Implement this

    }

    @Override
    public void unregister() {
        // TODO(b/378487799): Implement this

    }

    @Override
    public void sendMessage(
            int sessionId, HubMessage message, IContextHubTransactionCallback callback) {
        // TODO(b/381102453): Implement this
    }

    @Override
    public void sendMessageDeliveryStatus(int sessionId, int messageSeqNumber, byte errorCode) {
        // TODO(b/381102453): Implement this
    }
}
