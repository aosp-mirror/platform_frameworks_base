/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.internal;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.RUN_BACKUP_ACTION;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_BACKUP;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;

import com.android.server.backup.UserBackupManagerService;

/**
 * A {@link BroadcastReceiver} for the action {@link UserBackupManagerService#RUN_BACKUP_ACTION}
 * that runs an immediate backup operation if eligible.
 */
public class RunBackupReceiver extends BroadcastReceiver {
    private final UserBackupManagerService mUserBackupManagerService;

    public RunBackupReceiver(UserBackupManagerService userBackupManagerService) {
        mUserBackupManagerService = userBackupManagerService;
    }

    /**
     * Run a backup pass if we're eligible. We're eligible if the following conditions are met:
     *
     * <ul>
     *   <li>No transports are pending initialization (otherwise we kick off an initialization
     *       operation instead).
     *   <li>Backup is enabled for the user.
     *   <li>The user has completed setup.
     *   <li>No backup operation is currently running for the user.
     * </ul>
     */
    public void onReceive(Context context, Intent intent) {
        if (!RUN_BACKUP_ACTION.equals(intent.getAction())) {
            return;
        }

        synchronized (mUserBackupManagerService.getQueueLock()) {
            if (mUserBackupManagerService.getPendingInits().size() > 0) {
                // If there are pending init operations, we process those and then settle into the
                // usual periodic backup schedule.
                if (MORE_DEBUG) {
                    Slog.v(TAG, "Init pending at scheduled backup");
                }
                try {
                    PendingIntent runInitIntent = mUserBackupManagerService.getRunInitIntent();
                    mUserBackupManagerService.getAlarmManager().cancel(runInitIntent);
                    runInitIntent.send();
                } catch (PendingIntent.CanceledException ce) {
                    Slog.w(TAG, "Run init intent cancelled");
                }
            } else {
                // Don't run backups if we're disabled or not yet set up.
                if (!mUserBackupManagerService.isEnabled()
                        || !mUserBackupManagerService.isSetupComplete()) {
                    Slog.w(
                            TAG,
                            "Backup pass but enabled="
                                    + mUserBackupManagerService.isEnabled()
                                    + " setupComplete="
                                    + mUserBackupManagerService.isSetupComplete());
                    return;
                }

                // Don't run backups if one is already running.
                if (mUserBackupManagerService.isBackupRunning()) {
                    Slog.i(TAG, "Backup time but one already running");
                    return;
                }

                if (DEBUG) {
                    Slog.v(TAG, "Running a backup pass");
                }

                // Acquire the wakelock and pass it to the backup thread. It will be released once
                // backup concludes.
                mUserBackupManagerService.setBackupRunning(true);
                mUserBackupManagerService.getWakelock().acquire();

                Handler backupHandler = mUserBackupManagerService.getBackupHandler();
                Message message = backupHandler.obtainMessage(MSG_RUN_BACKUP);
                backupHandler.sendMessage(message);
            }
        }
    }
}
