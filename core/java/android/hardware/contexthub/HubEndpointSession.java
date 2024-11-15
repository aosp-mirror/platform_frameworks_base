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
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.util.CloseGuard;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An object representing a communication session between two different hub endpoints.
 *
 * <p>A published enpoint can receive
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubEndpointSession implements AutoCloseable {
    private final CloseGuard mCloseGuard = new CloseGuard();

    private final int mId;

    // TODO(b/377717509): Implement Message sending API & interface
    @NonNull private final HubEndpoint mHubEndpoint;
    @NonNull private final HubEndpointInfo mInitiator;
    @NonNull private final HubEndpointInfo mDestination;

    private final AtomicBoolean mIsClosed = new AtomicBoolean(true);

    /** @hide */
    HubEndpointSession(
            int id,
            @NonNull HubEndpoint hubEndpoint,
            @NonNull HubEndpointInfo destination,
            @NonNull HubEndpointInfo initiator) {
        mId = id;
        mHubEndpoint = hubEndpoint;
        mDestination = destination;
        mInitiator = initiator;
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
    public void close() {
        if (!mIsClosed.getAndSet(true)) {
            mCloseGuard.close();
            mHubEndpoint.closeSession(this);
        }
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
