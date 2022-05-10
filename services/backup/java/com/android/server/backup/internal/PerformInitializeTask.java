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

import static com.android.server.backup.BackupManagerService.TAG;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Attempts to call {@link BackupTransport#initializeDevice()} followed by
 * {@link BackupTransport#finishBackup()} for the transport names passed in with the intent of
 * wiping backup data from the transport.
 *
 * If the transport returns error, it will record the operation as pending and schedule it to run in
 * a future time according to {@link BackupTransport#requestBackupTime()}. The result status
 * reported to observers will be the last unsuccessful status reported by the transports. If every
 * operation was successful then it's {@link BackupTransport#TRANSPORT_OK}.
 */
public class PerformInitializeTask implements Runnable {
    private final UserBackupManagerService mBackupManagerService;
    private final TransportManager mTransportManager;
    private final String[] mQueue;
    private final File mBaseStateDir;
    private final OnTaskFinishedListener mListener;
    @Nullable private IBackupObserver mObserver;

    public PerformInitializeTask(
            UserBackupManagerService backupManagerService,
            String[] transportNames,
            @Nullable IBackupObserver observer,
            OnTaskFinishedListener listener) {
        this(
                backupManagerService,
                backupManagerService.getTransportManager(),
                transportNames,
                observer,
                listener,
                backupManagerService.getBaseStateDir());
    }

    @VisibleForTesting
    PerformInitializeTask(
            UserBackupManagerService backupManagerService,
            TransportManager transportManager,
            String[] transportNames,
            @Nullable IBackupObserver observer,
            OnTaskFinishedListener listener,
            File baseStateDir) {
        mBackupManagerService = backupManagerService;
        mTransportManager = transportManager;
        mQueue = transportNames;
        mObserver = observer;
        mListener = listener;
        mBaseStateDir = baseStateDir;
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
        String callerLogString = "PerformInitializeTask.run()";
        List<TransportConnection> transportClientsToDisposeOf = new ArrayList<>(mQueue.length);
        int result = BackupTransport.TRANSPORT_OK;
        try {
            for (String transportName : mQueue) {
                TransportConnection transportConnection =
                        mTransportManager.getTransportClient(transportName, callerLogString);
                if (transportConnection == null) {
                    Slog.e(TAG, "Requested init for " + transportName + " but not found");
                    continue;
                }
                transportClientsToDisposeOf.add(transportConnection);

                Slog.i(TAG, "Initializing (wiping) backup transport storage: " + transportName);
                String transportDirName =
                        mTransportManager.getTransportDirName(
                                transportConnection.getTransportComponent());
                EventLog.writeEvent(EventLogTags.BACKUP_START, transportDirName);
                long startRealtime = SystemClock.elapsedRealtime();

                BackupTransportClient transport = transportConnection.connectOrThrow(
                        callerLogString);
                int status = transport.initializeDevice();
                if (status != BackupTransport.TRANSPORT_OK) {
                    Slog.e(TAG, "Transport error in initializeDevice()");
                } else {
                    status = transport.finishBackup();
                    if (status != BackupTransport.TRANSPORT_OK) {
                        Slog.e(TAG, "Transport error in finishBackup()");
                    }
                }

                // Okay, the wipe really happened.  Clean up our local bookkeeping.
                if (status == BackupTransport.TRANSPORT_OK) {
                    Slog.i(TAG, "Device init successful");
                    int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                    EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                    File stateFileDir = new File(mBaseStateDir, transportDirName);
                    mBackupManagerService.resetBackupState(stateFileDir);
                    EventLog.writeEvent(EventLogTags.BACKUP_SUCCESS, 0, millis);
                    mBackupManagerService.recordInitPending(false, transportName, transportDirName);
                    notifyResult(transportName, BackupTransport.TRANSPORT_OK);
                } else {
                    // If this didn't work, requeue this one and try again
                    // after a suitable interval
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                    mBackupManagerService.recordInitPending(true, transportName, transportDirName);
                    notifyResult(transportName, status);
                    result = status;

                    // do this via another alarm to make sure of the wakelock states
                    long delay = transport.requestBackupTime();
                    Slog.w(TAG, "Init failed on " + transportName + " resched in " + delay);
                    mBackupManagerService.getAlarmManager().set(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + delay,
                            mBackupManagerService.getRunInitIntent());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unexpected error performing init", e);
            result = BackupTransport.TRANSPORT_ERROR;
        } finally {
            for (TransportConnection transportConnection : transportClientsToDisposeOf) {
                mTransportManager.disposeOfTransportClient(transportConnection, callerLogString);
            }
            notifyFinished(result);
            mListener.onFinished(callerLogString);
        }
    }
}
