/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.UserIdInt;
import android.app.backup.BlobBackupHelper;
import android.companion.ICompanionDeviceManager;
import android.content.Context;
import android.os.ServiceManager;
import android.util.Slog;

/**
 * CDM backup and restore helper.
 */
public class CompanionBackupHelper extends BlobBackupHelper {

    private static final String TAG = "CompanionBackupHelper";

    // current schema of the backup state blob
    private static final int BLOB_VERSION = 1;

    // key under which the CDM data blob is committed to back up
    private static final String KEY_COMPANION = "companion";

    @UserIdInt
    private final int mUserId;

    public CompanionBackupHelper(int userId) {
        super(BLOB_VERSION, KEY_COMPANION);

        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        byte[] payload = null;
        if (KEY_COMPANION.equals(key)) {
            try {
                ICompanionDeviceManager cdm = ICompanionDeviceManager.Stub.asInterface(
                        ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE));
                payload = cdm.getBackupPayload(mUserId);
            } catch (Exception e) {
                Slog.e(TAG, "Error getting backup from CompanionDeviceManager.", e);
            }
        }
        return payload;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        Slog.i(TAG, "Got companion backup data.");
        if (KEY_COMPANION.equals(key)) {
            try {
                ICompanionDeviceManager cdm = ICompanionDeviceManager.Stub.asInterface(
                        ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE));
                cdm.applyRestoredPayload(payload, mUserId);
            } catch (Exception e) {
                Slog.e(TAG, "Error applying restored payload to CompanionDeviceManager.", e);
            }
        }
    }
}
