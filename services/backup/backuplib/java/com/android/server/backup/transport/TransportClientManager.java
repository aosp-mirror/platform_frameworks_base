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
 * Manages the creation and disposal of {@link TransportClient}s. The only class that should use
 * this is {@link TransportManager}, all the other usages should go to {@link TransportManager}.
 */
public class TransportClientManager {
    private static final String TAG = "TransportClientManager";
    private static final String SERVICE_ACTION_ENCRYPTING_TRANSPORT =
            "android.encryption.BACKUP_ENCRYPTION";
    private static final ComponentName ENCRYPTING_TRANSPORT = new ComponentName(
            "com.android.server.backup.encryption",
            "com.android.server.backup.encryption.BackupEncryptionService");
    private static final String ENCRYPTING_TRANSPORT_REAL_TRANSPORT_KEY = "transport";

    private final @UserIdInt int mUserId;
    private final Context mContext;
    private final TransportStats mTransportStats;
    private final Object mTransportClientsLock = new Object();
    private int mTransportClientsCreated = 0;
    private Map<TransportClient, String> mTransportClientsCallerMap = new WeakHashMap<>();
    private final Function<ComponentName, Intent> mIntentFunction;

    /**
     * Return an {@link Intent} which resolves to an intermediate {@link IBackupTransport} that
     * encrypts (or decrypts) the data when sending it (or receiving it) from the {@link
     * IBackupTransport} for the given {@link ComponentName}.
     */
    public static Intent getEncryptingTransportIntent(ComponentName tranportComponent) {
        return new Intent(SERVICE_ACTION_ENCRYPTING_TRANSPORT)
                .setComponent(ENCRYPTING_TRANSPORT)
                .putExtra(ENCRYPTING_TRANSPORT_REAL_TRANSPORT_KEY, tranportComponent);
    }

    /**
     * Return an {@link Intent} which resolves to the {@link IBackupTransport} for the {@link
     * ComponentName}.
     */
    private static Intent getRealTransportIntent(ComponentName transportComponent) {
        return new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(transportComponent);
    }

    /**
     * Given a {@link Intent} originally created by {@link
     * #getEncryptingTransportIntent(ComponentName)}, returns the {@link Intent} which resolves to
     * the {@link IBackupTransport} for that {@link ComponentName}.
     */
    public static Intent getRealTransportIntent(Intent encryptingTransportIntent) {
        ComponentName transportComponent = encryptingTransportIntent.getParcelableExtra(
                ENCRYPTING_TRANSPORT_REAL_TRANSPORT_KEY);
        Intent intent = getRealTransportIntent(transportComponent)
                .putExtras(encryptingTransportIntent.getExtras());
        intent.removeExtra(ENCRYPTING_TRANSPORT_REAL_TRANSPORT_KEY);
        return intent;
    }

    /**
     * Create a {@link TransportClientManager} such that {@link #getTransportClient(ComponentName,
     * Bundle, String)} returns a {@link TransportClient} which connects to an intermediate {@link
     * IBackupTransport} that encrypts (or decrypts) the data when sending it (or receiving it) from
     * the {@link IBackupTransport} for the given {@link ComponentName}.
     */
    public static TransportClientManager createEncryptingClientManager(@UserIdInt int userId,
            Context context, TransportStats transportStats) {
        return new TransportClientManager(userId, context, transportStats,
                TransportClientManager::getEncryptingTransportIntent);
    }

    public TransportClientManager(@UserIdInt int userId, Context context,
            TransportStats transportStats) {
        this(userId, context, transportStats, TransportClientManager::getRealTransportIntent);
    }

    private TransportClientManager(@UserIdInt int userId, Context context,
            TransportStats transportStats, Function<ComponentName, Intent> intentFunction) {
        mUserId = userId;
        mContext = context;
        mTransportStats = transportStats;
        mIntentFunction = intentFunction;
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
        return getTransportClient(transportComponent, null, caller);
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
            ComponentName transportComponent, @Nullable Bundle extras, String caller) {
        Intent bindIntent = mIntentFunction.apply(transportComponent);
        if (extras != null) {
            bindIntent.putExtras(extras);
        }
        return getTransportClient(transportComponent, caller, bindIntent);
    }

    private TransportClient getTransportClient(
            ComponentName transportComponent, String caller, Intent bindIntent) {
        synchronized (mTransportClientsLock) {
            TransportClient transportClient =
                    new TransportClient(
                            mUserId,
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
