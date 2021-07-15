/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.backup.BlobBackupHelper;
import android.permission.PermissionManagerInternal;
import android.util.Slog;

import com.android.server.LocalServices;

public class PermissionBackupHelper extends BlobBackupHelper {
    private static final String TAG = "PermissionBackup";
    private static final boolean DEBUG = false;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 1;

    // key under which the permission-grant state blob is committed to backup
    private static final String KEY_PERMISSIONS = "permissions";

    private final @UserIdInt int mUserId;

    private final @NonNull PermissionManagerInternal mPermissionManager;

    public PermissionBackupHelper(int userId) {
        super(STATE_VERSION, KEY_PERMISSIONS);

        mUserId = userId;
        mPermissionManager = LocalServices.getService(PermissionManagerInternal.class);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (DEBUG) {
            Slog.d(TAG, "Handling backup of " + key);
        }
        try {
            switch (key) {
                case KEY_PERMISSIONS:
                    return mPermissionManager.backupRuntimePermissions(mUserId);

                default:
                    Slog.w(TAG, "Unexpected backup key " + key);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to store payload " + key, e);
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (DEBUG) {
            Slog.d(TAG, "Handling restore of " + key);
        }
        try {
            switch (key) {
                case KEY_PERMISSIONS:
                    mPermissionManager.restoreRuntimePermissions(payload, mUserId);
                    break;

                default:
                    Slog.w(TAG, "Unexpected restore key " + key);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore key " + key, e);
        }
    }
}
