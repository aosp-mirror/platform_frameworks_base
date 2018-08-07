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
import static com.android.server.backup.BackupManagerService.RUN_INITIALIZE_ACTION;
import static com.android.server.backup.BackupManagerService.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.backup.BackupManagerService;

public class RunInitializeReceiver extends BroadcastReceiver {
    private final BackupManagerService mBackupManagerService;

    public RunInitializeReceiver(BackupManagerService backupManagerService) {
        mBackupManagerService = backupManagerService;
    }

    public void onReceive(Context context, Intent intent) {
        if (RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
            synchronized (mBackupManagerService.getQueueLock()) {
                final ArraySet<String> pendingInits = mBackupManagerService.getPendingInits();
                if (DEBUG) {
                    Slog.v(TAG, "Running a device init; " + pendingInits.size() + " pending");
                }

                if (pendingInits.size() > 0) {
                    final String[] transports =
                            pendingInits.toArray(new String[pendingInits.size()]);

                    mBackupManagerService.clearPendingInits();

                    PowerManager.WakeLock wakelock = mBackupManagerService.getWakelock();
                    wakelock.acquire();
                    OnTaskFinishedListener listener = caller -> wakelock.release();

                    Runnable task =
                            new PerformInitializeTask(
                                    mBackupManagerService, transports, null, listener);
                    mBackupManagerService.getBackupHandler().post(task);
                }
            }
        }
    }
}
