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

import android.app.AppGlobals;
import android.app.backup.BlobBackupHelper;
import android.content.pm.IPackageManager;
import android.os.UserHandle;
import android.util.Slog;

public class PermissionBackupHelper extends BlobBackupHelper {
    private static final String TAG = "PermissionBackup";
    private static final boolean DEBUG = false;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 1;

    // key under which the permission-grant state blob is committed to backup
    private static final String KEY_PERMISSIONS = "permissions";

    public PermissionBackupHelper() {
        super(STATE_VERSION, KEY_PERMISSIONS);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        IPackageManager pm = AppGlobals.getPackageManager();
        if (DEBUG) {
            Slog.d(TAG, "Handling backup of " + key);
        }
        try {
            switch (key) {
                case KEY_PERMISSIONS:
                    return pm.getPermissionGrantBackup(UserHandle.USER_SYSTEM);

                default:
                    Slog.w(TAG, "Unexpected backup key " + key);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to store payload " + key);
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        IPackageManager pm = AppGlobals.getPackageManager();
        if (DEBUG) {
            Slog.d(TAG, "Handling restore of " + key);
        }
        try {
            switch (key) {
                case KEY_PERMISSIONS:
                    pm.restorePermissionGrants(payload, UserHandle.USER_SYSTEM);
                    break;

                default:
                    Slog.w(TAG, "Unexpected restore key " + key);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to restore key " + key);
        }
    }
}
