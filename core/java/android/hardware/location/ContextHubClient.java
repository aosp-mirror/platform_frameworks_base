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

import android.annotation.RequiresPermission;
import android.os.Handler;

import java.io.Closeable;

/**
 * A class describing a client of the Context Hub Service.
 *
 * Clients can send messages to nanoapps at a Context Hub through this object.
 *
 * @hide
 */
public class ContextHubClient implements Closeable {
    /*
     * The ContextHubClient interface associated with this client.
     */
    // TODO: Implement this interface and associate with ContextHubClient object
    // private final IContextHubClient mClientInterface;

    /*
     * The listening callback associated with this client.
     */
    private ContextHubClientCallback mCallback;

    /*
     * The Context Hub that this client is attached to.
     */
    private ContextHubInfo mAttachedHub;

    /*
     * The handler to invoke mCallback.
     */
    private Handler mCallbackHandler;

    ContextHubClient(ContextHubClientCallback callback, Handler handler, ContextHubInfo hubInfo) {
        mCallback = callback;
        mCallbackHandler = handler;
        mAttachedHub = hubInfo;
    }

    /**
     * Returns the hub that this client is attached to.
     *
     * @return the ContextHubInfo of the attached hub
     */
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
        throw new UnsupportedOperationException("TODO: Implement this");
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
     * @see NanoAppMessage
     * @see ContextHubTransaction.Result
     */
    @RequiresPermission(android.Manifest.permission.LOCATION_HARDWARE)
    @ContextHubTransaction.Result
    public int sendMessageToNanoApp(NanoAppMessage message) {
        throw new UnsupportedOperationException("TODO: Implement this");
    }
}
