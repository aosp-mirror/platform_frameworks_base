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

package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.ContextHubTransactionHelper;
import android.hardware.location.IContextHubTransactionCallback;
import android.util.CloseGuard;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An object representing a communication session between two different hub endpoints.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubEndpointSession implements AutoCloseable {
    private final CloseGuard mCloseGuard = new CloseGuard();

    private final int mId;

    @NonNull private final HubEndpoint mHubEndpoint;
    @NonNull private final HubEndpointInfo mInitiator;
    @NonNull private final HubEndpointInfo mDestination;
    @Nullable private final String mServiceDescriptor;

    private final AtomicBoolean mIsClosed = new AtomicBoolean(true);

    /** @hide */
    HubEndpointSession(
            int id,
            @NonNull HubEndpoint hubEndpoint,
            @NonNull HubEndpointInfo destination,
            @NonNull HubEndpointInfo initiator,
            @Nullable String serviceDescriptor) {
        mId = id;
        mHubEndpoint = hubEndpoint;
        mDestination = destination;
        mInitiator = initiator;
        mServiceDescriptor = serviceDescriptor;
    }

    /**
     * Send a message to the peer endpoint in this session.
     *
     * @param message The message object constructed with {@link HubMessage#createMessage}.
     * @return For messages that does not require a response, the transaction will immediately
     *     complete. For messages that requires a response, the transaction will complete after
     *     receiving the response for the message.
     * @throws SecurityException if the application doesn't have the right permissions to send this
     *     message.
     */
    @NonNull
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public ContextHubTransaction<Void> sendMessage(@NonNull HubMessage message) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("Session is already closed.");
        }

        boolean isResponseRequired = message.getDeliveryParams().isResponseRequired();
        ContextHubTransaction<Void> ret =
                new ContextHubTransaction<>(
                        isResponseRequired
                                ? ContextHubTransaction.TYPE_HUB_MESSAGE_REQUIRES_RESPONSE
                                : ContextHubTransaction.TYPE_HUB_MESSAGE_DEFAULT);
        if (!isResponseRequired) {
            // If the message doesn't require acknowledgement, respond with success immediately
            // TODO(b/379162322): Improve handling of synchronous failures.
            mHubEndpoint.sendMessage(this, message, null);
            ret.setResponse(
                    new ContextHubTransaction.Response<>(
                            ContextHubTransaction.RESULT_SUCCESS, null));
        } else {
            IContextHubTransactionCallback callback =
                    ContextHubTransactionHelper.createTransactionCallback(ret);
            // Sequence number will be assigned at the service
            mHubEndpoint.sendMessage(this, message, callback);
        }
        return ret;
    }

    /** @hide */
    public int getId() {
        return mId;
    }

    /** @hide */
    public void setOpened() {
        mIsClosed.set(false);
        mCloseGuard.open("close");
    }

    /** @hide */
    public void setClosed() {
        mIsClosed.set(true);
        mCloseGuard.close();
    }

    /**
     * Closes the connection for this session between an endpoint and the Context Hub Service.
     *
     * <p>When this function is invoked, the messaging associated with this session is invalidated.
     * All futures messages targeted for this client are dropped.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void close() {
        if (!mIsClosed.getAndSet(true)) {
            mCloseGuard.close();
            mHubEndpoint.closeSession(this);
        }
    }

    /**
     * Get the service descriptor associated with this session. Null value indicates that there is
     * no service associated to this session.
     *
     * <p>For hub initiated sessions, the object was previously used in as an argument for open
     * request in {@link IHubEndpointLifecycleCallback#onSessionOpenRequest}.
     *
     * <p>For app initiated sessions, the object was previously used in an open request in {@link
     * android.hardware.location.ContextHubManager#openSession}
     */
    @Nullable
    public String getServiceDescriptor() {
        return mServiceDescriptor;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Session [");
        stringBuilder.append(mId);
        stringBuilder.append("]: [");
        stringBuilder.append(mInitiator);
        stringBuilder.append("]->[");
        stringBuilder.append(mDestination);
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    /** @hide */
    protected void finalize() throws Throwable {
        try {
            // Note that guard could be null if the constructor threw.
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        } finally {
            super.finalize();
        }
    }
}
