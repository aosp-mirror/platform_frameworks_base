/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.TransportManager.SERVICE_ACTION_TRANSPORT_HOST;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.TransportManager;

/**
 * Manages the creation and disposal of {@link TransportClient}s. The only class that should use
 * this is {@link TransportManager}, all the other usages should go to {@link TransportManager}.
 *
 * <p>TODO(brufino): Implement pool of TransportClients
 */
public class TransportClientManager {
    private static final String TAG = "TransportClientManager";

    private final Context mContext;
    private final Object mTransportClientsLock = new Object();
    private int mTransportClientsCreated = 0;

    public TransportClientManager(Context context) {
        mContext = context;
    }

    /**
     * Retrieves a {@link TransportClient} for the transport identified by {@param
     * transportComponent}.
     *
     * @param transportComponent The {@link ComponentName} of the transport.
     * @param transportDirName The {@link String} returned by
     *     {@link IBackupTransport#transportDirName()} at registration.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient}.
     */
    public TransportClient getTransportClient(
            ComponentName transportComponent,
            String transportDirName,
            String caller) {
        Intent bindIntent =
                new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(transportComponent);
        synchronized (mTransportClientsLock) {
            TransportClient transportClient =
                    new TransportClient(
                            mContext,
                            bindIntent,
                            transportComponent,
                            transportDirName,
                            Integer.toString(mTransportClientsCreated));
            mTransportClientsCreated++;
            TransportUtils.log(Log.DEBUG, TAG, caller, "Retrieving " + transportClient);
            return transportClient;
        }
    }

    /**
     * Disposes of the {@link TransportClient}.
     *
     * @param transportClient The {@link TransportClient} to be disposed of.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     */
    public void disposeOfTransportClient(TransportClient transportClient, String caller) {
        TransportUtils.log(Log.DEBUG, TAG, caller, "Disposing of " + transportClient);
        transportClient.unbind(caller);
    }
}
