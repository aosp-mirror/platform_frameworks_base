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

package com.android.server.backup.encryption;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.encryption.transport.IntermediateEncryptingTransport;
import com.android.server.backup.encryption.transport.IntermediateEncryptingTransportManager;

/**
 * This service provides encryption of backup data. For an intent used to bind to this service, it
 * provides an {@link IntermediateEncryptingTransport} which is an implementation of {@link
 * IBackupTransport} that encrypts (or decrypts) the data when sending it (or receiving it) from the
 * real {@link IBackupTransport}.
 */
public class BackupEncryptionService extends Service {
    public static final String TAG = "BackupEncryption";
    private static IntermediateEncryptingTransportManager sTransportManager = null;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate:" + this);
        if (sTransportManager == null) {
            Log.i(TAG, "Creating IntermediateEncryptingTransportManager");
            sTransportManager = new IntermediateEncryptingTransportManager(this);
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy:" + this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO (b141536117): Check connection with TransportClient.connect and return null on fail.
        return sTransportManager.get(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sTransportManager.cleanup(intent);
        return false;
    }
}
