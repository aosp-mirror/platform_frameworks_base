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

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.MORE_DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.RUN_BACKUP_ACTION;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_BACKUP;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.util.Slog;

import com.android.server.backup.RefactoredBackupManagerService;

public class RunBackupReceiver extends BroadcastReceiver {

    private RefactoredBackupManagerService backupManagerService;

    public RunBackupReceiver(RefactoredBackupManagerService backupManagerService) {
        this.backupManagerService = backupManagerService;
    }

    public void onReceive(Context context, Intent intent) {
        if (RUN_BACKUP_ACTION.equals(intent.getAction())) {
            synchronized (backupManagerService.getQueueLock()) {
                if (backupManagerService.getPendingInits().size() > 0) {
                    // If there are pending init operations, we process those
                    // and then settle into the usual periodic backup schedule.
                    if (MORE_DEBUG) {
                        Slog.v(TAG, "Init pending at scheduled backup");
                    }
                    try {
                        backupManagerService.getAlarmManager().cancel(
                                backupManagerService.getRunInitIntent());
                        backupManagerService.getRunInitIntent().send();
                    } catch (PendingIntent.CanceledException ce) {
                        Slog.e(TAG, "Run init intent cancelled");
                        // can't really do more than bail here
                    }
                } else {
                    // Don't run backups now if we're disabled or not yet
                    // fully set up.
                    if (backupManagerService.isEnabled() && backupManagerService.isProvisioned()) {
                        if (!backupManagerService.isBackupRunning()) {
                            if (DEBUG) {
                                Slog.v(TAG, "Running a backup pass");
                            }

                            // Acquire the wakelock and pass it to the backup thread.  it will
                            // be released once backup concludes.
                            backupManagerService.setBackupRunning(true);
                            backupManagerService.getWakelock().acquire();

                            Message msg = backupManagerService.getBackupHandler().obtainMessage(
                                    MSG_RUN_BACKUP);
                            backupManagerService.getBackupHandler().sendMessage(msg);
                        } else {
                            Slog.i(TAG, "Backup time but one already running");
                        }
                    } else {
                        Slog.w(TAG, "Backup pass but e=" + backupManagerService.isEnabled() + " p="
                                + backupManagerService.isProvisioned());
                    }
                }
            }
        }
    }
}
