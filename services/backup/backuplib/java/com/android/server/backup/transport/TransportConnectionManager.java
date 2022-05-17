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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportUtils.Priority;

import java.io.PrintWriter;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Manages the creation and disposal of {@link TransportConnection}s. The only class that should use
 * this is {@link TransportManager}, all the other usages should go to {@link TransportManager}.
 */
public class TransportConnectionManager {
    private static final String TAG = "TransportConnectionManager";

    private final @UserIdInt int mUserId;
    private final Context mContext;
    private final TransportStats mTransportStats;
    private final Object mTransportClientsLock = new Object();
    private int mTransportClientsCreated = 0;
    private Map<TransportConnection, String> mTransportClientsCallerMap = new WeakHashMap<>();
    private final Function<ComponentName, Intent> mIntentFunction;

    /**
     * Return an {@link Intent} which resolves to the {@link IBackupTransport} for the {@link
     * ComponentName}.
     */
    private static Intent getRealTransportIntent(ComponentName transportComponent) {
        return new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(transportComponent);
    }

    public TransportConnectionManager(@UserIdInt int userId, Context context,
            TransportStats transportStats) {
        this(userId, context, transportStats, TransportConnectionManager::getRealTransportIntent);
    }

    private TransportConnectionManager(@UserIdInt int userId, Context context,
            TransportStats transportStats, Function<ComponentName, Intent> intentFunction) {
        mUserId = userId;
        mContext = context;
        mTransportStats = transportStats;
        mIntentFunction = intentFunction;
    }

    /**
     * Retrieves a {@link TransportConnection} for the transport identified by {@param
     * transportComponent}.
     *
     * @param transportComponent The {@link ComponentName} of the transport.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportConnection#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportConnection}.
     */
    public TransportConnection getTransportClient(ComponentName transportComponent, String caller) {
        return getTransportClient(transportComponent, null, caller);
    }

    /**
     * Retrieves a {@link TransportConnection} for the transport identified by {@param
     * transportComponent} whose binding intent will have the {@param extras} extras.
     *
     * @param transportComponent The {@link ComponentName} of the transport.
     * @param extras A {@link Bundle} of extras to pass to the binding intent.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportConnection#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportConnection}.
     */
    public TransportConnection getTransportClient(
            ComponentName transportComponent, @Nullable Bundle extras, String caller) {
        Intent bindIntent = mIntentFunction.apply(transportComponent);
        if (extras != null) {
            bindIntent.putExtras(extras);
        }
        return getTransportClient(transportComponent, caller, bindIntent);
    }

    private TransportConnection getTransportClient(
            ComponentName transportComponent, String caller, Intent bindIntent) {
        synchronized (mTransportClientsLock) {
            TransportConnection transportConnection =
                    new TransportConnection(
                            mUserId,
                            mContext,
                            mTransportStats,
                            bindIntent,
                            transportComponent,
                            Integer.toString(mTransportClientsCreated),
                            caller);
            mTransportClientsCallerMap.put(transportConnection, caller);
            mTransportClientsCreated++;
            TransportUtils.log(
                    Priority.DEBUG,
                    TAG,
                    formatMessage(null, caller, "Retrieving " + transportConnection));
            return transportConnection;
        }
    }

    /**
     * Disposes of the {@link TransportConnection}.
     *
     * @param transportConnection The {@link TransportConnection} to be disposed of.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportConnection#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     */
    public void disposeOfTransportClient(TransportConnection transportConnection, String caller) {
        transportConnection.unbind(caller);
        transportConnection.markAsDisposed();
        synchronized (mTransportClientsLock) {
            TransportUtils.log(
                    Priority.DEBUG,
                    TAG,
                    formatMessage(null, caller, "Disposing of " + transportConnection));
            mTransportClientsCallerMap.remove(transportConnection);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("Transport clients created: " + mTransportClientsCreated);
        synchronized (mTransportClientsLock) {
            pw.println("Current transport clients: " + mTransportClientsCallerMap.size());
            for (TransportConnection transportConnection : mTransportClientsCallerMap.keySet()) {
                String caller = mTransportClientsCallerMap.get(transportConnection);
                pw.println("    " + transportConnection + " [" + caller + "]");
                for (String logEntry : transportConnection.getLogBuffer()) {
                    pw.println("        " + logEntry);
                }
            }
        }
    }
}
