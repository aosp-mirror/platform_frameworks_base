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

import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.app.AlarmManager;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.RefactoredBackupManagerService;

import java.io.File;

public class PerformInitializeTask implements Runnable {

    private RefactoredBackupManagerService backupManagerService;
    String[] mQueue;
    IBackupObserver mObserver;

    public PerformInitializeTask(RefactoredBackupManagerService backupManagerService,
            String[] transportNames, IBackupObserver observer) {
        this.backupManagerService = backupManagerService;
        mQueue = transportNames;
        mObserver = observer;
    }

    private void notifyResult(String target, int status) {
        try {
            if (mObserver != null) {
                mObserver.onResult(target, status);
            }
        } catch (RemoteException ignored) {
            mObserver = null;       // don't try again
        }
    }

    private void notifyFinished(int status) {
        try {
            if (mObserver != null) {
                mObserver.backupFinished(status);
            }
        } catch (RemoteException ignored) {
            mObserver = null;
        }
    }

    public void run() {
        // mWakelock is *acquired* when execution begins here
        int result = BackupTransport.TRANSPORT_OK;
        try {
            for (String transportName : mQueue) {
                IBackupTransport transport =
                        backupManagerService.getTransportManager().getTransportBinder(
                                transportName);
                if (transport == null) {
                    Slog.e(TAG, "Requested init for " + transportName + " but not found");
                    continue;
                }

                Slog.i(TAG, "Initializing (wiping) backup transport storage: " + transportName);
                EventLog.writeEvent(EventLogTags.BACKUP_START, transport.transportDirName());
                long startRealtime = SystemClock.elapsedRealtime();
                int status = transport.initializeDevice();

                if (status == BackupTransport.TRANSPORT_OK) {
                    status = transport.finishBackup();
                }

                // Okay, the wipe really happened.  Clean up our local bookkeeping.
                if (status == BackupTransport.TRANSPORT_OK) {
                    Slog.i(TAG, "Device init successful");
                    int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                    EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                    backupManagerService
                            .resetBackupState(new File(backupManagerService.getBaseStateDir(),
                                    transport.transportDirName()));
                    EventLog.writeEvent(EventLogTags.BACKUP_SUCCESS, 0, millis);
                    synchronized (backupManagerService.getQueueLock()) {
                        backupManagerService.recordInitPendingLocked(false, transportName);
                    }
                    notifyResult(transportName, BackupTransport.TRANSPORT_OK);
                } else {
                    // If this didn't work, requeue this one and try again
                    // after a suitable interval
                    Slog.e(TAG, "Transport error in initializeDevice()");
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                    synchronized (backupManagerService.getQueueLock()) {
                        backupManagerService.recordInitPendingLocked(true, transportName);
                    }
                    notifyResult(transportName, status);
                    result = status;

                    // do this via another alarm to make sure of the wakelock states
                    long delay = transport.requestBackupTime();
                    Slog.w(TAG, "Init failed on " + transportName + " resched in " + delay);
                    backupManagerService.getAlarmManager().set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + delay,
                            backupManagerService.getRunInitIntent());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unexpected error performing init", e);
            result = BackupTransport.TRANSPORT_ERROR;
        } finally {
            // Done; release the wakelock
            notifyFinished(result);
            backupManagerService.getWakelock().release();
        }
    }
}
