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
import android.hardware.contexthub.IContextHubEndpoint;
import android.hardware.contexthub.IContextHubEndpointCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class that manages registration/unregistration of clients and manages messages to/from clients.
 *
 * @hide
 */
/* package */ class ContextHubEndpointManager {
    private static final String TAG = "ContextHubEndpointManager";

    /** The hub ID of the Context Hub Service. */
    private static final long SERVICE_HUB_ID = 0x416e64726f696400L;

    /** The context of the service. */
    private final Context mContext;

    /** The proxy to talk to the Context Hub. */
    private final IContextHubWrapper mContextHubProxy;

    /** A map of endpoint IDs to brokers currently registered. */
    private final Map<Long, ContextHubEndpointBroker> mEndpointMap = new ConcurrentHashMap<>();

    /** Variables for managing endpoint ID creation */
    private final Object mEndpointLock = new Object();

    @GuardedBy("mEndpointLock")
    private long mNextEndpointId = 0;

    /* package */ ContextHubEndpointManager(Context context, IContextHubWrapper contextHubProxy) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
    }

    /**
     * Registers a new endpoint with the service.
     *
     * @param pendingEndpointInfo the object describing the endpoint being registered
     * @param callback the callback interface of the endpoint to register
     * @return the endpoint interface
     * @throws IllegalStateException if max number of endpoints have already registered
     */
    /* package */ IContextHubEndpoint registerEndpoint(
            HubEndpointInfo pendingEndpointInfo, IContextHubEndpointCallback callback)
            throws RemoteException {
        ContextHubEndpointBroker broker;
        long endpointId = getNewEndpointId();
        EndpointInfo halEndpointInfo =
                ContextHubServiceUtil.createHalEndpointInfo(
                        pendingEndpointInfo, endpointId, SERVICE_HUB_ID);
        try {
            mContextHubProxy.registerEndpoint(halEndpointInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while calling HAL registerEndpoint", e);
            throw e;
        }
        broker =
                new ContextHubEndpointBroker(
                        mContext,
                        mContextHubProxy,
                        this /* endpointManager */,
                        halEndpointInfo,
                        callback);
        mEndpointMap.put(endpointId, broker);

        // TODO(b/378487799): Add death recipient

        Log.d(TAG, "Registered endpoint with ID = " + endpointId);
        return IContextHubEndpoint.Stub.asInterface(broker);
    }

    /**
     * @return an available endpoint ID
     */
    private long getNewEndpointId() {
        synchronized (mEndpointLock) {
            if (mNextEndpointId == Long.MAX_VALUE) {
                throw new IllegalStateException("Too many endpoints connected");
            }
            return mNextEndpointId++;
        }
    }
}
