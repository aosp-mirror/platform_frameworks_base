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
import static com.android.server.backup.transport.TransportUtils.formatMessage;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportUtils.Priority;
import java.io.PrintWriter;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Manages the creation and disposal of {@link TransportClient}s. The only class that should use
 * this is {@link TransportManager}, all the other usages should go to {@link TransportManager}.
 */
public class TransportClientManager {
    private static final String TAG = "TransportClientManager";

    private final Context mContext;
    private final TransportStats mTransportStats;
    private final Object mTransportClientsLock = new Object();
    private int mTransportClientsCreated = 0;
    private Map<TransportClient, String> mTransportClientsCallerMap = new WeakHashMap<>();

    public TransportClientManager(Context context, TransportStats transportStats) {
        mContext = context;
        mTransportStats = transportStats;
    }

    /**
     * Retrieves a {@link TransportClient} for the transport identified by {@param
     * transportComponent}.
     *
     * @param transportComponent The {@link ComponentName} of the transport.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient}.
     */
    public TransportClient getTransportClient(ComponentName transportComponent, String caller) {
        Intent bindIntent =
                new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(transportComponent);

        return getTransportClient(transportComponent, caller, bindIntent);
    }

    /**
     * Retrieves a {@link TransportClient} for the transport identified by {@param
     * transportComponent} whose binding intent will have the {@param extras} extras.
     *
     * @param transportComponent The {@link ComponentName} of the transport.
     * @param extras A {@link Bundle} of extras to pass to the binding intent.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient}.
     */
    public TransportClient getTransportClient(
            ComponentName transportComponent, Bundle extras, String caller) {
        Intent bindIntent =
                new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(transportComponent);
        bindIntent.putExtras(extras);

        return getTransportClient(transportComponent, caller, bindIntent);
    }

    private TransportClient getTransportClient(
            ComponentName transportComponent, String caller, Intent bindIntent) {
        synchronized (mTransportClientsLock) {
            TransportClient transportClient =
                    new TransportClient(
                            mContext,
                            mTransportStats,
                            bindIntent,
                            transportComponent,
                            Integer.toString(mTransportClientsCreated),
                            caller);
            mTransportClientsCallerMap.put(transportClient, caller);
            mTransportClientsCreated++;
            TransportUtils.log(
                    Priority.DEBUG,
                    TAG,
                    formatMessage(null, caller, "Retrieving " + transportClient));
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
        transportClient.unbind(caller);
        transportClient.markAsDisposed();
        synchronized (mTransportClientsLock) {
            TransportUtils.log(
                    Priority.DEBUG,
                    TAG,
                    formatMessage(null, caller, "Disposing of " + transportClient));
            mTransportClientsCallerMap.remove(transportClient);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("Transport clients created: " + mTransportClientsCreated);
        synchronized (mTransportClientsLock) {
            pw.println("Current transport clients: " + mTransportClientsCallerMap.size());
            for (TransportClient transportClient : mTransportClientsCallerMap.keySet()) {
                String caller = mTransportClientsCallerMap.get(transportClient);
                pw.println("    " + transportClient + " [" + caller + "]");
                for (String logEntry : transportClient.getLogBuffer()) {
                    pw.println("        " + logEntry);
                }
            }
        }
    }
}
