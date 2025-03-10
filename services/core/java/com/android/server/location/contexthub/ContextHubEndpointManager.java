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
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class that manages registration/unregistration of clients and manages messages to/from clients.
 *
 * @hide
 */
/* package */ class ContextHubEndpointManager
        implements ContextHubHalEndpointCallback.IEndpointSessionCallback {
    private static final String TAG = "ContextHubEndpointManager";

    /** The hub ID of the Context Hub Service. */
    private static final long SERVICE_HUB_ID = 0x416e64726f696400L;

    /** The range of session IDs to use for endpoints */
    private static final int SERVICE_SESSION_RANGE = 1024;

    /** The length of the array that should be returned by HAL requestSessionIdRange */
    private static final int SERVICE_SESSION_RANGE_LENGTH = 2;

    /** The context of the service. */
    private final Context mContext;

    /** The proxy to talk to the Context Hub. */
    private final IContextHubWrapper mContextHubProxy;

    private final HubInfoRegistry mHubInfoRegistry;

    /** A map of endpoint IDs to brokers currently registered. */
    private final Map<Long, ContextHubEndpointBroker> mEndpointMap = new ConcurrentHashMap<>();

    /** Variables for managing endpoint ID creation */
    private final Object mEndpointLock = new Object();

    /**
     * The next available endpoint ID to register. Per EndpointId.aidl definition, dynamic
     * endpoint IDs must have the left-most bit as 1, and the values 0/-1 are invalid.
     */
    @GuardedBy("mEndpointLock")
    private long mNextEndpointId = -2;

    /** The minimum session ID reservable by endpoints (retrieved from HAL) */
    private final int mMinSessionId;

    /** The minimum session ID reservable by endpoints (retrieved from HAL) */
    private final int mMaxSessionId;

    /** Variables for managing session ID creation */
    private final Object mSessionIdLock = new Object();

    /** A set of session IDs that have been reserved by an endpoint. */
    @GuardedBy("mSessionIdLock")
    private final Set<Integer> mReservedSessionIds =
            Collections.newSetFromMap(new HashMap<Integer, Boolean>());

    @GuardedBy("mSessionIdLock")
    private int mNextSessionId = 0;

    /** Initialized to true if all initialization in the constructor succeeds. */
    private final boolean mSessionIdsValid;

    /* package */ ContextHubEndpointManager(
            Context context, IContextHubWrapper contextHubProxy, HubInfoRegistry hubInfoRegistry) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mHubInfoRegistry = hubInfoRegistry;
        int[] range = null;
        try {
            range = mContextHubProxy.requestSessionIdRange(SERVICE_SESSION_RANGE);
            if (range != null && range.length < SERVICE_SESSION_RANGE_LENGTH) {
                Log.e(TAG, "Invalid session ID range: range array size = " + range.length);
                range = null;
            }
        } catch (RemoteException | IllegalArgumentException | ServiceSpecificException e) {
            Log.e(TAG, "Exception while calling HAL requestSessionIdRange", e);
        }

        if (range == null) {
            mMinSessionId = -1;
            mMaxSessionId = -1;
            mSessionIdsValid = false;
        } else {
            mMinSessionId = range[0];
            mMaxSessionId = range[1];
            if (!isSessionIdRangeValid(mMinSessionId, mMaxSessionId)) {
                Log.e(
                        TAG,
                        "Invalid session ID range: max=" + mMaxSessionId + " min=" + mMinSessionId);
                mSessionIdsValid = false;
            } else {
                mNextSessionId = mMinSessionId;
                mSessionIdsValid = true;
            }
        }
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
        if (!mSessionIdsValid) {
            throw new IllegalStateException("ContextHubEndpointManager failed to initialize");
        }
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

        try {
            broker.attachDeathRecipient();
        } catch (RemoteException e) {
            // The client process has died, so we close the connection and return null
            Log.e(TAG, "Failed to attach death recipient to client", e);
            broker.unregister();
            return null;
        }

        Log.d(TAG, "Registered endpoint with ID = " + endpointId);
        return IContextHubEndpoint.Stub.asInterface(broker);
    }

    /**
     * Reserves an available session ID for an endpoint.
     *
     * @throws IllegalStateException if no session ID was available.
     * @return The reserved session ID.
     */
    /* package */ int reserveSessionId() {
        int id = -1;
        synchronized (mSessionIdLock) {
            final int maxCapacity = mMaxSessionId - mMinSessionId + 1;
            if (mReservedSessionIds.size() >= maxCapacity) {
                throw new IllegalStateException("Too many sessions reserved");
            }

            id = mNextSessionId;
            for (int i = mMinSessionId; i <= mMaxSessionId; i++) {
                if (!mReservedSessionIds.contains(id)) {
                    mNextSessionId = (id == mMaxSessionId) ? mMinSessionId : id + 1;
                    break;
                }

                id = (id == mMaxSessionId) ? mMinSessionId : id + 1;
            }

            mReservedSessionIds.add(id);
        }
        return id;
    }

    /** Returns a previously reserved session ID through {@link #reserveSessionId()}. */
    /* package */ void returnSessionId(int sessionId) {
        synchronized (mSessionIdLock) {
            mReservedSessionIds.remove(sessionId);
        }
    }

    /**
     * Unregisters an endpoint given its ID.
     *
     * @param endpointId The ID of the endpoint to unregister.
     */
    /* package */ void unregisterEndpoint(long endpointId) {
        mEndpointMap.remove(endpointId);
    }

    @Override
    public void onEndpointSessionOpenRequest(
            int sessionId,
            HubEndpointInfo.HubEndpointIdentifier destination,
            HubEndpointInfo.HubEndpointIdentifier initiator,
            String serviceDescriptor) {
        if (destination.getHub() != SERVICE_HUB_ID) {
            Log.e(
                    TAG,
                    "onEndpointSessionOpenRequest: invalid destination hub ID: "
                            + destination.getHub());
            return;
        }
        ContextHubEndpointBroker broker = mEndpointMap.get(destination.getEndpoint());
        if (broker == null) {
            Log.e(
                    TAG,
                    "onEndpointSessionOpenRequest: unknown destination endpoint ID: "
                            + destination.getEndpoint());
            return;
        }
        HubEndpointInfo initiatorInfo = mHubInfoRegistry.getEndpointInfo(initiator);
        if (initiatorInfo == null) {
            Log.e(
                    TAG,
                    "onEndpointSessionOpenRequest: unknown initiator endpoint ID: "
                            + initiator.getEndpoint());
            return;
        }
        broker.onEndpointSessionOpenRequest(sessionId, initiatorInfo, serviceDescriptor);
    }

    @Override
    public void onCloseEndpointSession(int sessionId, byte reason) {
        boolean callbackInvoked = false;
        for (ContextHubEndpointBroker broker : mEndpointMap.values()) {
            if (broker.hasSessionId(sessionId)) {
                broker.onCloseEndpointSession(sessionId, reason);
                callbackInvoked = true;
                break;
            }
        }

        if (!callbackInvoked) {
            Log.w(TAG, "onCloseEndpointSession: unknown session ID " + sessionId);
        }
    }

    @Override
    public void onEndpointSessionOpenComplete(int sessionId) {
        boolean callbackInvoked = false;
        for (ContextHubEndpointBroker broker : mEndpointMap.values()) {
            if (broker.hasSessionId(sessionId)) {
                broker.onEndpointSessionOpenComplete(sessionId);
                callbackInvoked = true;
                break;
            }
        }

        if (!callbackInvoked) {
            Log.w(TAG, "onEndpointSessionOpenComplete: unknown session ID " + sessionId);
        }
    }

    /** @return an available endpoint ID */
    private long getNewEndpointId() {
        synchronized (mEndpointLock) {
            if (mNextEndpointId >= 0) {
                throw new IllegalStateException("Too many endpoints connected");
            }
            return mNextEndpointId--;
        }
    }

    /**
     * @return true if the provided session ID range is valid
     */
    private boolean isSessionIdRangeValid(int minId, int maxId) {
        return (minId <= maxId) && (minId >= 0) && (maxId >= 0);
    }
}
