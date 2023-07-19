/*
 * Copyright (C) 2021 The Calyx Institute
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

package com.android.server.backup;

import android.app.backup.BlobBackupHelper;
import android.content.Context;
import android.net.INetworkPolicyManager;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Slog;

public class NetworkPolicyBackupHelper extends BlobBackupHelper {
    private static final String TAG = "NetworkPolicyBackupHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Current version of the blob schema
    static final int BLOB_VERSION = 1;

    // Key under which the payload blob is stored
    static final String KEY_NETWORK_POLICY = "network_policy";

    private final int mUserId;

    public NetworkPolicyBackupHelper(int userId) {
        super(BLOB_VERSION, KEY_NETWORK_POLICY);
        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        byte[] newPayload = null;
        if (KEY_NETWORK_POLICY.equals(key)) {
            try {
                INetworkPolicyManager npm = INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
                newPayload = npm.getBackupPayload(mUserId);
            } catch (Exception e) {
                // Treat as no data
                Slog.e(TAG, "Couldn't communicate with network policy manager", e);
                newPayload = null;
            }
        }
        return newPayload;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (DEBUG) {
            Slog.v(TAG, "Got restore of " + key);
        }

        if (KEY_NETWORK_POLICY.equals(key)) {
            try {
                INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_POLICY_SERVICE))
                        .applyRestore(payload, mUserId);
            } catch (Exception e) {
                Slog.e(TAG, "Couldn't communicate with network policy manager", e);
            }
        }
    }
}
