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

import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.transport.DelegatingTransport;
import com.android.server.backup.transport.TransportClient;

/**
 * This is an implementation of {@link IBackupTransport} that encrypts (or decrypts) the data when
 * sending it (or receiving it) from the {@link IBackupTransport} returned by {@link
 * TransportClient.connect(String)}.
 */
public class IntermediateEncryptingTransport extends DelegatingTransport {
    private final TransportClient mTransportClient;
    private final Object mConnectLock = new Object();
    private volatile IBackupTransport mRealTransport;

    @VisibleForTesting
    IntermediateEncryptingTransport(TransportClient transportClient) {
        mTransportClient = transportClient;
    }

    @Override
    protected IBackupTransport getDelegate() throws RemoteException {
        if (mRealTransport == null) {
            connect();
        }
        return mRealTransport;
    }

    private void connect() throws RemoteException {
        Log.i(TAG, "connecting " + mTransportClient);
        synchronized (mConnectLock) {
            if (mRealTransport == null) {
                mRealTransport = mTransportClient.connect("IntermediateEncryptingTransport");
                if (mRealTransport == null) {
                    throw new RemoteException("Could not connect: " + mTransportClient);
                }
            }
        }
    }

    @VisibleForTesting
    TransportClient getClient() {
        return mTransportClient;
    }
}
