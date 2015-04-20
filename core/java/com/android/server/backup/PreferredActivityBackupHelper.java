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

public class PreferredActivityBackupHelper extends BlobBackupHelper {
    private static final String TAG = "PreferredBackup";
    private static final boolean DEBUG = false;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 2;

    // key under which the preferred-activity state blob is committed to backup
    private static final String KEY_PREFERRED = "preferred-activity";

    public PreferredActivityBackupHelper() {
        super(STATE_VERSION, KEY_PREFERRED);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (KEY_PREFERRED.equals(key)) {
            if (DEBUG) {
                Slog.v(TAG, "Checking whether to back up");
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                return pm.getPreferredActivityBackup(UserHandle.USER_OWNER);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to store backup payload", e);
                // fall through to report null state
            }
        } else {
            Slog.w(TAG, "Unexpected backup key " + key);
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (KEY_PREFERRED.equals(key)) {
            if (DEBUG) {
                Slog.v(TAG, "Restoring");
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                pm.restorePreferredActivities(payload, UserHandle.USER_OWNER);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to restore", e);
            }
        } else {
            Slog.w(TAG, "Unexpected restore key " + key);
        }
    }
}
