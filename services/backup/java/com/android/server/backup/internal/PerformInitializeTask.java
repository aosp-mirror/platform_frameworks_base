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

import android.app.AlarmManager;
import android.app.backup.BackupTransport;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.RefactoredBackupManagerService;
import java.io.File;
import java.util.HashSet;

public class PerformInitializeTask implements Runnable {

    private RefactoredBackupManagerService backupManagerService;
    HashSet<String> mQueue;

    PerformInitializeTask(RefactoredBackupManagerService backupManagerService,
        HashSet<String> transportNames) {
        this.backupManagerService = backupManagerService;
        mQueue = transportNames;
    }

    public void run() {
        try {
            for (String transportName : mQueue) {
                IBackupTransport transport =
                    backupManagerService.mTransportManager.getTransportBinder(transportName);
                if (transport == null) {
                    Slog.e(
                        RefactoredBackupManagerService.TAG, "Requested init for " + transportName + " but not found");
                    continue;
                }

                Slog.i(RefactoredBackupManagerService.TAG, "Initializing (wiping) backup transport storage: " + transportName);
                EventLog.writeEvent(EventLogTags.BACKUP_START, transport.transportDirName());
                long startRealtime = SystemClock.elapsedRealtime();
                int status = transport.initializeDevice();

                if (status == BackupTransport.TRANSPORT_OK) {
                    status = transport.finishBackup();
                }

                // Okay, the wipe really happened.  Clean up our local bookkeeping.
                if (status == BackupTransport.TRANSPORT_OK) {
                    Slog.i(RefactoredBackupManagerService.TAG, "Device init successful");
                    int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                    EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                    backupManagerService
                        .resetBackupState(new File(backupManagerService.mBaseStateDir, transport.transportDirName()));
                    EventLog.writeEvent(EventLogTags.BACKUP_SUCCESS, 0, millis);
                    synchronized (backupManagerService.mQueueLock) {
                        backupManagerService.recordInitPendingLocked(false, transportName);
                    }
                } else {
                    // If this didn't work, requeue this one and try again
                    // after a suitable interval
                    Slog.e(RefactoredBackupManagerService.TAG, "Transport error in initializeDevice()");
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                    synchronized (backupManagerService.mQueueLock) {
                        backupManagerService.recordInitPendingLocked(true, transportName);
                    }
                    // do this via another alarm to make sure of the wakelock states
                    long delay = transport.requestBackupTime();
                    Slog.w(RefactoredBackupManagerService.TAG, "Init failed on " + transportName + " resched in " + delay);
                    backupManagerService.mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delay, backupManagerService.mRunInitIntent);
                }
            }
        } catch (Exception e) {
            Slog.e(RefactoredBackupManagerService.TAG, "Unexpected error performing init", e);
        } finally {
            // Done; release the wakelock
            backupManagerService.mWakelock.release();
        }
    }
}
