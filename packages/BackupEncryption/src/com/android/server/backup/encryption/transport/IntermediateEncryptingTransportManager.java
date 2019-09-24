/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.transport;

import static com.android.server.backup.encryption.BackupEncryptionService.TAG;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.transport.TransportClientManager;
import com.android.server.backup.transport.TransportStats;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles creation and cleanup of {@link IntermediateEncryptingTransport} instances.
 */
public class IntermediateEncryptingTransportManager {
    private static final String CALLER = "IntermediateEncryptingTransportManager";
    private final TransportClientManager mTransportClientManager;
    private final Object mTransportsLock = new Object();
    private final Map<ComponentName, IntermediateEncryptingTransport> mTransports = new HashMap<>();

    @VisibleForTesting
    IntermediateEncryptingTransportManager(TransportClientManager transportClientManager) {
        mTransportClientManager = transportClientManager;
    }

    public IntermediateEncryptingTransportManager(Context context) {
        this(new TransportClientManager(UserHandle.myUserId(), context, new TransportStats()));
    }

    /**
     * Extract the {@link ComponentName} corresponding to the real {@link IBackupTransport}, and
     * provide a {@link IntermediateEncryptingTransport} which is an implementation of {@link
     * IBackupTransport} that encrypts (or decrypts) the data when sending it (or receiving it) from
     * the real {@link IBackupTransport}.
     * @param intent {@link Intent} created with a call to {@link
     * TransportClientManager.getEncryptingTransportIntent(ComponentName)}.
     * @return
     */
    public IntermediateEncryptingTransport get(Intent intent) {
        Intent transportIntent = TransportClientManager.getRealTransportIntent(intent);
        Log.i(TAG, "get: intent:" + intent + " transportIntent:" + transportIntent);
        synchronized (mTransportsLock) {
            return mTransports.computeIfAbsent(transportIntent.getComponent(),
                    c -> create(transportIntent));
        }
    }

    /**
     * Create an instance of {@link IntermediateEncryptingTransport}.
     */
    private IntermediateEncryptingTransport create(Intent realTransportIntent) {
        return new IntermediateEncryptingTransport(mTransportClientManager.getTransportClient(
                realTransportIntent.getComponent(), realTransportIntent.getExtras(), CALLER));
    }

    /**
     * Cleanup the {@link IntermediateEncryptingTransport} which was created by a call to
     * {@link #get(Intent)} with this {@link Intent}.
     */
    public void cleanup(Intent intent) {
        Intent transportIntent = TransportClientManager.getRealTransportIntent(intent);
        Log.i(TAG, "cleanup: intent:" + intent + " transportIntent:" + transportIntent);

        IntermediateEncryptingTransport transport;
        synchronized (mTransportsLock) {
            transport = mTransports.remove(transportIntent.getComponent());
        }
        if (transport != null) {
            mTransportClientManager.disposeOfTransportClient(transport.getClient(), CALLER);
        } else {
            Log.i(TAG, "Could not find IntermediateEncryptingTransport");
        }
    }
}
