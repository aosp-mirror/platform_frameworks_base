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

package com.android.server.backup.internal;

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.getSetupCompleteSettingForUser;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.util.Slog;

import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.UserBackupManagerService;

/**
 * A {@link ContentObserver} for changes to the setting {@link Settings.Secure#USER_SETUP_COMPLETE}
 * for a particular user.
 */
public class SetupObserver extends ContentObserver {
    private final UserBackupManagerService mUserBackupManagerService;
    private final Context mContext;
    private final int mUserId;

    public SetupObserver(UserBackupManagerService userBackupManagerService, Handler handler) {
        super(handler);
        mUserBackupManagerService = userBackupManagerService;
        mContext = userBackupManagerService.getContext();
        mUserId = userBackupManagerService.getUserId();
    }

    /**
     * Callback that executes when the setting {@link Settings.Secure#USER_SETUP_COMPLETE} changes
     * for the user {@link #mUserId}. If the user is newly setup and backup is enabled, then we
     * schedule a key value and full backup job for the user. If the user was previously setup and
     * now the setting has changed to {@code false}, we don't reset the state as having gone through
     * setup is a non-reversible action.
     */
    public void onChange(boolean selfChange) {
        boolean previousSetupComplete = mUserBackupManagerService.isSetupComplete();
        boolean newSetupComplete = getSetupCompleteSettingForUser(mContext, mUserId);

        boolean resolvedSetupComplete = previousSetupComplete || newSetupComplete;
        mUserBackupManagerService.setSetupComplete(resolvedSetupComplete);
        if (MORE_DEBUG) {
            Slog.d(
                    TAG,
                    "Setup complete change: was="
                            + previousSetupComplete
                            + " new="
                            + newSetupComplete
                            + " resolved="
                            + resolvedSetupComplete);
        }

        synchronized (mUserBackupManagerService.getQueueLock()) {
            // Start backup if the user is newly setup and backup is enabled.
            if (resolvedSetupComplete
                    && !previousSetupComplete
                    && mUserBackupManagerService.isEnabled()) {
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Setup complete so starting backups");
                }
                KeyValueBackupJob.schedule(mUserBackupManagerService.getUserId(), mContext,
                        mUserBackupManagerService);
                mUserBackupManagerService.scheduleNextFullBackupJob(0);
            }
        }
    }
}
