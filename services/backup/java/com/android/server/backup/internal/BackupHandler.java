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

import android.app.backup.RestoreSet;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.fullbackup.PerformAdbBackupTask;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.keyvalue.BackupRequest;
import com.android.server.backup.keyvalue.KeyValueBackupTask;
import com.android.server.backup.params.AdbBackupParams;
import com.android.server.backup.params.AdbParams;
import com.android.server.backup.params.AdbRestoreParams;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.params.ClearParams;
import com.android.server.backup.params.ClearRetryParams;
import com.android.server.backup.params.RestoreGetSetsParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.restore.PerformAdbRestoreTask;
import com.android.server.backup.restore.PerformUnifiedRestoreTask;
import com.android.server.backup.transport.TransportClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Asynchronous backup/restore handler thread.
 */
public class BackupHandler extends Handler {

    public static final int MSG_RUN_BACKUP = 1;
    public static final int MSG_RUN_ADB_BACKUP = 2;
    public static final int MSG_RUN_RESTORE = 3;
    public static final int MSG_RUN_CLEAR = 4;
    public static final int MSG_RUN_GET_RESTORE_SETS = 6;
    public static final int MSG_RESTORE_SESSION_TIMEOUT = 8;
    public static final int MSG_FULL_CONFIRMATION_TIMEOUT = 9;
    public static final int MSG_RUN_ADB_RESTORE = 10;
    public static final int MSG_RETRY_INIT = 11;
    public static final int MSG_RETRY_CLEAR = 12;
    public static final int MSG_WIDGET_BROADCAST = 13;
    public static final int MSG_RUN_FULL_TRANSPORT_BACKUP = 14;
    public static final int MSG_REQUEST_BACKUP = 15;
    public static final int MSG_SCHEDULE_BACKUP_PACKAGE = 16;
    public static final int MSG_BACKUP_OPERATION_TIMEOUT = 17;
    public static final int MSG_RESTORE_OPERATION_TIMEOUT = 18;
    // backup task state machine tick
    public static final int MSG_BACKUP_RESTORE_STEP = 20;
    public static final int MSG_OP_COMPLETE = 21;
    // Release the wakelock. This is used to ensure we don't hold it after
    // a user is removed. This will also terminate the looper thread.
    public static final int MSG_STOP = 22;

    private final UserBackupManagerService backupManagerService;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    private final HandlerThread mBackupThread;
    private volatile boolean mIsStopping = false;

    public BackupHandler(
            UserBackupManagerService backupManagerService, HandlerThread backupThread) {
        super(backupThread.getLooper());
        mBackupThread = backupThread;
        this.backupManagerService = backupManagerService;
        mAgentTimeoutParameters = Preconditions.checkNotNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
    }

    /**
     * Put the BackupHandler into a stopping state where the remaining messages on the queue will be
     * silently dropped and the {@link WakeLock} held by the {@link UserBackupManagerService} will
     * then be released.
     */
    public void stop() {
        mIsStopping = true;
        sendMessage(obtainMessage(BackupHandler.MSG_STOP));
    }

    public void handleMessage(Message msg) {
        if (msg.what == MSG_STOP) {
            Slog.v(TAG, "Stopping backup handler");
            backupManagerService.getWakelock().quit();
            mBackupThread.quitSafely();
        }

        if (mIsStopping) {
            // If we're finishing all other types of messages should be ignored
            return;
        }

        TransportManager transportManager = backupManagerService.getTransportManager();
        switch (msg.what) {
            case MSG_RUN_BACKUP: {
                backupManagerService.setLastBackupPass(System.currentTimeMillis());

                String callerLogString = "BH/MSG_RUN_BACKUP";
                TransportClient transportClient =
                        transportManager.getCurrentTransportClient(callerLogString);
                IBackupTransport transport =
                        transportClient != null
                                ? transportClient.connect(callerLogString)
                                : null;
                if (transport == null) {
                    if (transportClient != null) {
                        transportManager
                                .disposeOfTransportClient(transportClient, callerLogString);
                    }
                    Slog.v(TAG, "Backup requested but no transport available");
                    synchronized (backupManagerService.getQueueLock()) {
                        backupManagerService.setBackupRunning(false);
                    }
                    backupManagerService.getWakelock().release();
                    break;
                }

                // Snapshot the pending-backup set and work on that.
                List<String> queue = new ArrayList<>();
                DataChangedJournal oldJournal = backupManagerService.getJournal();
                synchronized (backupManagerService.getQueueLock()) {
                    // Do we have any work to do?  Construct the work queue
                    // then release the synchronization lock to actually run
                    // the backup.
                    if (backupManagerService.getPendingBackups().size() > 0) {
                        for (BackupRequest b : backupManagerService.getPendingBackups().values()) {
                            queue.add(b.packageName);
                        }
                        if (DEBUG) {
                            Slog.v(TAG, "clearing pending backups");
                        }
                        backupManagerService.getPendingBackups().clear();

                        // Start a new backup-queue journal file too
                        backupManagerService.setJournal(null);

                    }
                }

                // At this point, we have started a new journal file, and the old
                // file identity is being passed to the backup processing task.
                // When it completes successfully, that old journal file will be
                // deleted.  If we crash prior to that, the old journal is parsed
                // at next boot and the journaled requests fulfilled.
                boolean staged = true;
                if (queue.size() > 0) {
                    // Spin up a backup state sequence and set it running
                    try {
                        OnTaskFinishedListener listener =
                                caller ->
                                        transportManager
                                                .disposeOfTransportClient(transportClient, caller);
                        KeyValueBackupTask.start(
                                backupManagerService,
                                transportClient,
                                transport.transportDirName(),
                                queue,
                                oldJournal,
                                /* observer */ null,
                                /* monitor */ null,
                                listener,
                                Collections.emptyList(),
                                /* userInitiated */ false,
                                /* nonIncremental */ false);
                    } catch (Exception e) {
                        // unable to ask the transport its dir name -- transient failure, since
                        // the above check succeeded.  Try again next time.
                        Slog.e(TAG, "Transport became unavailable attempting backup"
                                + " or error initializing backup task", e);
                        staged = false;
                    }
                } else {
                    Slog.v(TAG, "Backup requested but nothing pending");
                    staged = false;
                }

                if (!staged) {
                    transportManager.disposeOfTransportClient(transportClient, callerLogString);
                    // if we didn't actually hand off the wakelock, rewind until next time
                    synchronized (backupManagerService.getQueueLock()) {
                        backupManagerService.setBackupRunning(false);
                    }
                    backupManagerService.getWakelock().release();
                }
                break;
            }

            case MSG_BACKUP_RESTORE_STEP: {
                try {
                    BackupRestoreTask task = (BackupRestoreTask) msg.obj;
                    if (MORE_DEBUG) {
                        Slog.v(TAG, "Got next step for " + task + ", executing");
                    }
                    task.execute();
                } catch (ClassCastException e) {
                    Slog.e(TAG, "Invalid backup/restore task in flight, obj=" + msg.obj);
                }
                break;
            }

            case MSG_OP_COMPLETE: {
                try {
                    Pair<BackupRestoreTask, Long> taskWithResult =
                            (Pair<BackupRestoreTask, Long>) msg.obj;
                    taskWithResult.first.operationComplete(taskWithResult.second);
                } catch (ClassCastException e) {
                    Slog.e(TAG, "Invalid completion in flight, obj=" + msg.obj);
                }
                break;
            }

            case MSG_RUN_ADB_BACKUP: {
                // TODO: refactor full backup to be a looper-based state machine
                // similar to normal backup/restore.
                AdbBackupParams params = (AdbBackupParams) msg.obj;
                PerformAdbBackupTask task = new PerformAdbBackupTask(backupManagerService,
                        params.fd,
                        params.observer, params.includeApks, params.includeObbs,
                        params.includeShared, params.doWidgets, params.curPassword,
                        params.encryptPassword, params.allApps, params.includeSystem,
                        params.doCompress, params.includeKeyValue, params.packages, params.latch);
                (new Thread(task, "adb-backup")).start();
                break;
            }

            case MSG_RUN_FULL_TRANSPORT_BACKUP: {
                PerformFullTransportBackupTask task = (PerformFullTransportBackupTask) msg.obj;
                (new Thread(task, "transport-backup")).start();
                break;
            }

            case MSG_RUN_RESTORE: {
                RestoreParams params = (RestoreParams) msg.obj;
                Slog.d(TAG, "MSG_RUN_RESTORE observer=" + params.observer);

                PerformUnifiedRestoreTask task =
                        new PerformUnifiedRestoreTask(
                                backupManagerService,
                                params.transportClient,
                                params.observer,
                                params.monitor,
                                params.token,
                                params.packageInfo,
                                params.pmToken,
                                params.isSystemRestore,
                                params.filterSet,
                                params.listener);

                synchronized (backupManagerService.getPendingRestores()) {
                    if (backupManagerService.isRestoreInProgress()) {
                        if (DEBUG) {
                            Slog.d(TAG, "Restore in progress, queueing.");
                        }
                        backupManagerService.getPendingRestores().add(task);
                        // This task will be picked up and executed when the the currently running
                        // restore task finishes.
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "Starting restore.");
                        }
                        backupManagerService.setRestoreInProgress(true);
                        Message restoreMsg = obtainMessage(MSG_BACKUP_RESTORE_STEP, task);
                        sendMessage(restoreMsg);
                    }
                }
                break;
            }

            case MSG_RUN_ADB_RESTORE: {
                // TODO: refactor full restore to be a looper-based state machine
                // similar to normal backup/restore.
                AdbRestoreParams params = (AdbRestoreParams) msg.obj;
                PerformAdbRestoreTask task = new PerformAdbRestoreTask(backupManagerService,
                        params.fd,
                        params.curPassword, params.encryptPassword,
                        params.observer, params.latch);
                (new Thread(task, "adb-restore")).start();
                break;
            }

            case MSG_RUN_CLEAR: {
                ClearParams params = (ClearParams) msg.obj;
                Runnable task =
                        new PerformClearTask(
                                backupManagerService,
                                params.transportClient,
                                params.packageInfo,
                                params.listener);
                task.run();
                break;
            }

            case MSG_RETRY_CLEAR: {
                // reenqueues if the transport remains unavailable
                ClearRetryParams params = (ClearRetryParams) msg.obj;
                backupManagerService.clearBackupData(params.transportName, params.packageName);
                break;
            }

            case MSG_RUN_GET_RESTORE_SETS: {
                // Like other async operations, this is entered with the wakelock held
                RestoreSet[] sets = null;
                RestoreGetSetsParams params = (RestoreGetSetsParams) msg.obj;
                String callerLogString = "BH/MSG_RUN_GET_RESTORE_SETS";
                try {
                    IBackupTransport transport =
                            params.transportClient.connectOrThrow(callerLogString);
                    sets = transport.getAvailableRestoreSets();
                    // cache the result in the active session
                    synchronized (params.session) {
                        params.session.setRestoreSets(sets);
                    }
                    if (sets == null) {
                        EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error from transport getting set list: " + e.getMessage());
                } finally {
                    if (params.observer != null) {
                        try {
                            params.observer.restoreSetsAvailable(sets);
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Unable to report listing to observer");
                        } catch (Exception e) {
                            Slog.e(TAG, "Restore observer threw: " + e.getMessage());
                        }
                    }

                    // Done: reset the session timeout clock
                    removeMessages(MSG_RESTORE_SESSION_TIMEOUT);
                    sendEmptyMessageDelayed(MSG_RESTORE_SESSION_TIMEOUT,
                            mAgentTimeoutParameters.getRestoreAgentTimeoutMillis());

                    params.listener.onFinished(callerLogString);
                }
                break;
            }

            case MSG_BACKUP_OPERATION_TIMEOUT:
            case MSG_RESTORE_OPERATION_TIMEOUT: {
                Slog.d(TAG, "Timeout message received for token=" + Integer.toHexString(msg.arg1));
                backupManagerService.handleCancel(msg.arg1, false);
                break;
            }

            case MSG_RESTORE_SESSION_TIMEOUT: {
                synchronized (backupManagerService) {
                    if (backupManagerService.getActiveRestoreSession() != null) {
                        // Client app left the restore session dangling.  We know that it
                        // can't be in the middle of an actual restore operation because
                        // the timeout is suspended while a restore is in progress.  Clean
                        // up now.
                        Slog.w(TAG, "Restore session timed out; aborting");
                        backupManagerService.getActiveRestoreSession().markTimedOut();
                        post(backupManagerService.getActiveRestoreSession().new EndRestoreRunnable(
                                backupManagerService,
                                backupManagerService.getActiveRestoreSession()));
                    }
                }
                break;
            }

            case MSG_FULL_CONFIRMATION_TIMEOUT: {
                synchronized (backupManagerService.getAdbBackupRestoreConfirmations()) {
                    AdbParams params = backupManagerService.getAdbBackupRestoreConfirmations().get(
                            msg.arg1);
                    if (params != null) {
                        Slog.i(TAG, "Full backup/restore timed out waiting for user confirmation");

                        // Release the waiter; timeout == completion
                        backupManagerService.signalAdbBackupRestoreCompletion(params);

                        // Remove the token from the set
                        backupManagerService.getAdbBackupRestoreConfirmations().delete(msg.arg1);

                        // Report a timeout to the observer, if any
                        if (params.observer != null) {
                            try {
                                params.observer.onTimeout();
                            } catch (RemoteException e) {
                            /* don't care if the app has gone away */
                            }
                        }
                    } else {
                        Slog.d(TAG, "couldn't find params for token " + msg.arg1);
                    }
                }
                break;
            }

            case MSG_WIDGET_BROADCAST: {
                final Intent intent = (Intent) msg.obj;
                backupManagerService.getContext().sendBroadcastAsUser(intent, UserHandle.SYSTEM);
                break;
            }

            case MSG_REQUEST_BACKUP: {
                BackupParams params = (BackupParams) msg.obj;
                if (MORE_DEBUG) {
                    Slog.d(TAG, "MSG_REQUEST_BACKUP observer=" + params.observer);
                }
                backupManagerService.setBackupRunning(true);
                backupManagerService.getWakelock().acquire();

                KeyValueBackupTask.start(
                        backupManagerService,
                        params.transportClient,
                        params.dirName,
                        params.kvPackages,
                        /* dataChangedJournal */ null,
                        params.observer,
                        params.monitor,
                        params.listener,
                        params.fullPackages,
                        /* userInitiated */ true,
                        params.nonIncrementalBackup);
                break;
            }

            case MSG_SCHEDULE_BACKUP_PACKAGE: {
                String pkgName = (String) msg.obj;
                if (MORE_DEBUG) {
                    Slog.d(TAG, "MSG_SCHEDULE_BACKUP_PACKAGE " + pkgName);
                }
                backupManagerService.dataChangedImpl(pkgName);
                break;
            }
        }
    }
}
