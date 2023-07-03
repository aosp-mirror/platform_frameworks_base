/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.accounts.AccountManagerInternal;
import android.app.backup.BlobBackupHelper;
import android.util.Slog;

import com.android.server.LocalServices;

/**
 * Helper for handling backup of account manager specific state.
 */
public class AccountManagerBackupHelper extends BlobBackupHelper {
    private static final String TAG = "AccountsBackup";
    private static final boolean DEBUG = false;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 1;

    // key under which the account access grant state blob is committed to backup
    private static final String KEY_ACCOUNT_ACCESS_GRANTS = "account_access_grants";

    private final int mUserId;

    public AccountManagerBackupHelper(int userId) {
        super(STATE_VERSION, KEY_ACCOUNT_ACCESS_GRANTS);
        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        AccountManagerInternal am = LocalServices.getService(AccountManagerInternal.class);
        if (DEBUG) {
            Slog.d(TAG, "Handling backup of " + key);
        }
        try {
            switch (key) {
                case KEY_ACCOUNT_ACCESS_GRANTS: {
                    return am.backupAccountAccessPermissions(mUserId);
                }

                default: {
                    Slog.w(TAG, "Unexpected backup key " + key);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to store payload " + key, e);
        }

        return new byte[0];
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        AccountManagerInternal am = LocalServices.getService(AccountManagerInternal.class);
        if (DEBUG) {
            Slog.d(TAG, "Handling restore of " + key);
        }
        try {
            switch (key) {
                case KEY_ACCOUNT_ACCESS_GRANTS: {
                    am.restoreAccountAccessPermissions(payload, mUserId);
                } break;

                default: {
                    Slog.w(TAG, "Unexpected restore key " + key);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore key " + key, e);
        }
    }
}
