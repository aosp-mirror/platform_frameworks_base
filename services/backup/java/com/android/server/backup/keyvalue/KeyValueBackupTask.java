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

package com.android.server.backup.keyvalue;

import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;

import static com.android.server.backup.BackupManagerService.KEY_WIDGET_STATE;
import static com.android.server.backup.BackupManagerService.OP_PENDING;
import static com.android.server.backup.BackupManagerService.OP_TYPE_BACKUP;
import static com.android.server.backup.BackupManagerService.PACKAGE_MANAGER_SENTINEL;

import android.annotation.Nullable;
import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupCallback;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ConditionVariable;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.WorkSource;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.TransportManager;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.Operation;
import com.android.server.backup.remote.RemoteCall;
import com.android.server.backup.remote.RemoteCallable;
import com.android.server.backup.remote.RemoteResult;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.AppBackupUtils;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents the task of performing a sequence of key-value backups for a given list of packages.
 * Method {@link #run()} executes the backups to the transport specified via the {@code
 * transportClient} parameter in the constructor.
 *
 * <p>A few definitions:
 *
 * <ul>
 *   <li>State directory: {@link BackupManagerService#getBaseStateDir()}/&lt;transport&gt;
 *   <li>State file: {@link
 *       BackupManagerService#getBaseStateDir()}/&lt;transport&gt;/&lt;package&gt;<br>
 *       Represents the state of the backup data for a specific package in the current dataset.
 *   <li>Stage directory: {@link BackupManagerService#getDataDir()}
 *   <li>Stage file: {@link BackupManagerService#getDataDir()}/&lt;package&gt;.data<br>
 *       Contains staged data that the agents wrote via {@link BackupDataOutput}, to be transmitted
 *       to the transport.
 * </ul>
 *
 * If there is no PackageManager (PM) pseudo-package state file in the state directory, the
 * specified transport will be initialized with {@link IBackupTransport#initializeDevice()}.
 *
 * <p>The PM pseudo-package is the first package to be backed-up and sent to the transport in case
 * of incremental choice. If non-incremental, PM will only be backed-up if specified in the queue,
 * and if it's the case it will be re-positioned at the head of the queue.
 *
 * <p>Before starting, this task will register itself in {@link BackupManagerService} current
 * operations.
 *
 * <p>In summary, this task will for each package:
 *
 * <ul>
 *   <li>Bind to its {@link IBackupAgent}.
 *   <li>Request transport quota and flags.
 *   <li>Call {@link IBackupAgent#doBackup(ParcelFileDescriptor, ParcelFileDescriptor,
 *       ParcelFileDescriptor, long, int, IBackupManager, int)} via {@link RemoteCall} passing the
 *       old state file descriptor (read), the backup data file descriptor (write), the new state
 *       file descriptor (write), the quota and the transport flags. This will call {@link
 *       BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)} with
 *       the old state file to be read, a {@link BackupDataOutput} object to write the backup data
 *       and the new state file to write. By writing to {@link BackupDataOutput}, the agent will
 *       write data to the stage file. The task will block waiting for either:
 *       <ul>
 *         <li>Agent response.
 *         <li>Agent time-out (specified via {@link
 *             BackupManagerService#getAgentTimeoutParameters()}.
 *         <li>External cancellation or thread interrupt.
 *       </ul>
 *   <li>Unbind the agent.
 *   <li>Assuming agent response, send the staged data that the agent wrote to disk to the transport
 *       via {@link IBackupTransport#performBackup(PackageInfo, ParcelFileDescriptor, int)}.
 *   <li>Call {@link IBackupTransport#finishBackup()} if previous call was successful.
 *   <li>Save the new state in the state file. During the agent call it was being written to
 *       &lt;state file&gt;.new, here we rename it and replace the old one.
 *   <li>Delete the stage file.
 * </ul>
 *
 * In the end, this task will:
 *
 * <ul>
 *   <li>Mark data-changed for the remaining packages in the queue (skipped packages).
 *   <li>Delete the {@link DataChangedJournal} provided. Note that this should not be the current
 *       journal.
 *   <li>Set {@link BackupManagerService} current token as {@link
 *       IBackupTransport#getCurrentRestoreSet()}, if applicable.
 *   <li>Add the transport to the list of transports pending initialization ({@link
 *       BackupManagerService#getPendingInits()}) and kick-off initialization if the transport ever
 *       returned {@link BackupTransport#TRANSPORT_NOT_INITIALIZED}.
 *   <li>Unregister the task in current operations.
 *   <li>Release the wakelock.
 *   <li>Kick-off {@link PerformFullTransportBackupTask} if a list of full-backup packages was
 *       provided.
 * </ul>
 *
 * The caller can specify whether this should be an incremental or non-incremental backup. In the
 * case of non-incremental the agents will be passed an empty old state file, which signals that a
 * complete backup should be performed.
 *
 * <p>This task is designed to run on a dedicated thread, with the exception of the {@link
 * #handleCancel(boolean)} method, which can be called from any thread.
 */
// TODO: Stop poking into BMS state and doing things for it (e.g. synchronizing on public locks)
// TODO: Consider having the caller responsible for some clean-up (like resetting state)
// TODO: Distinguish between cancel and time-out where possible for logging/monitoring/observing
public class KeyValueBackupTask implements BackupRestoreTask, Runnable {
    private static final String TAG = "KeyValueBackupTask";
    private static final boolean DEBUG = BackupManagerService.DEBUG;
    private static final boolean MORE_DEBUG = BackupManagerService.MORE_DEBUG || false;
    private static final int THREAD_PRIORITY = Process.THREAD_PRIORITY_BACKGROUND;
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    private static final String BLANK_STATE_FILE_NAME = "blank_state";
    @VisibleForTesting
    public static final String STAGING_FILE_SUFFIX = ".data";
    @VisibleForTesting
    public static final String NEW_STATE_FILE_SUFFIX = ".new";

    /**
     * Creates a new {@link KeyValueBackupTask} for key-value backup operation, spins up a new
     * dedicated thread and kicks off the operation in it.
     *
     * @param backupManagerService The {@link BackupManagerService} system service.
     * @param transportClient The {@link TransportClient} that contains the transport used for the
     *     operation.
     * @param transportDirName The value of {@link IBackupTransport#transportDirName()} for the
     *     transport whose {@link TransportClient} was provided above.
     * @param queue The list of packages that will be backed-up, in the form of {@link
     *     BackupRequest}.
     * @param dataChangedJournal The old data-changed journal file that will be deleted when the
     *     operation finishes (successfully or not) or {@code null}.
     * @param observer A {@link IBackupObserver}.
     * @param monitor A {@link IBackupManagerMonitor}.
     * @param listener A {@link OnTaskFinishedListener} or {@code null}.
     * @param pendingFullBackups The list of packages that will be passed for a new {@link
     *     PerformFullTransportBackupTask} operation, which will be started when this finishes.
     * @param userInitiated Whether this was user-initiated or not.
     * @param nonIncremental If {@code true}, this will be a complete backup for each package,
     *     otherwise it will be just an incremental one over the current dataset.
     * @return The {@link KeyValueBackupTask} that was started.
     */
    public static KeyValueBackupTask start(
            BackupManagerService backupManagerService,
            TransportClient transportClient,
            String transportDirName,
            List<BackupRequest> queue,
            @Nullable DataChangedJournal dataChangedJournal,
            IBackupObserver observer,
            @Nullable IBackupManagerMonitor monitor,
            OnTaskFinishedListener listener,
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental) {
        KeyValueBackupTask task =
                new KeyValueBackupTask(
                        backupManagerService,
                        transportClient,
                        transportDirName,
                        queue,
                        dataChangedJournal,
                        observer,
                        monitor,
                        listener,
                        pendingFullBackups,
                        userInitiated,
                        nonIncremental);
        Thread thread = new Thread(task, "key-value-backup-" + THREAD_COUNT.incrementAndGet());
        if (DEBUG) {
            Slog.d(TAG, "Spinning thread " + thread.getName());
        }
        thread.start();
        return task;
    }

    private final BackupManagerService mBackupManagerService;
    private final PackageManager mPackageManager;
    private final TransportManager mTransportManager;
    private final TransportClient mTransportClient;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final IBackupObserver mObserver;
    private final KeyValueBackupReporter mReporter;
    private final OnTaskFinishedListener mTaskFinishedListener;
    private final boolean mUserInitiated;
    private final boolean mNonIncremental;
    private final int mCurrentOpToken;
    private final File mStateDir;
    private final List<BackupRequest> mOriginalQueue;
    private final List<BackupRequest> mQueue;
    private final List<String> mPendingFullBackups;
    @Nullable private final DataChangedJournal mJournal;
    @Nullable private PerformFullTransportBackupTask mFullBackupTask;

    private IBackupAgent mAgentBinder;
    private PackageInfo mCurrentPackage;
    private File mSavedStateFile;
    private File mBackupDataFile;
    private File mNewStateFile;
    private ParcelFileDescriptor mSavedState;
    private ParcelFileDescriptor mBackupData;
    private ParcelFileDescriptor mNewState;
    private int mStatus;

    /**
     * This {@link ConditionVariable} is used to signal that the cancel operation has been
     * received by the task and that no more transport calls will be made. Anyone can call {@link
     * ConditionVariable#block()} to wait for these conditions to hold true, but there should only
     * be one place where {@link ConditionVariable#open()} is called. Also there should be no calls
     * to {@link ConditionVariable#close()}, which means there is only one cancel per backup -
     * subsequent calls to block will return immediately.
     */
    private final ConditionVariable mCancelAcknowledged = new ConditionVariable(false);

    /**
     * Set it to {@code true} and block on {@code mCancelAcknowledged} to wait for the cancellation.
     * DO NOT set it to {@code false}.
     */
    private volatile boolean mCancelled = false;

    /**
     * If non-{@code null} there is a pending agent call being made. This call can be cancelled (and
     * control returned to this task) with {@link RemoteCall#cancel()}.
     */
    @Nullable private volatile RemoteCall mPendingCall;

    @VisibleForTesting
    public KeyValueBackupTask(
            BackupManagerService backupManagerService,
            TransportClient transportClient,
            String transportDirName,
            List<BackupRequest> queue,
            @Nullable DataChangedJournal journal,
            IBackupObserver observer,
            @Nullable IBackupManagerMonitor monitor,
            OnTaskFinishedListener taskFinishedListener,
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental) {
        mBackupManagerService = backupManagerService;
        mTransportManager = backupManagerService.getTransportManager();
        mPackageManager = backupManagerService.getPackageManager();
        mTransportClient = transportClient;
        mOriginalQueue = queue;
        // We need to retain the original queue contents in case of transport failure
        mQueue = new ArrayList<>(queue);
        mJournal = journal;
        mObserver = observer;
        mReporter = new KeyValueBackupReporter(backupManagerService, observer, monitor);
        mTaskFinishedListener = taskFinishedListener;
        mPendingFullBackups = pendingFullBackups;
        mUserInitiated = userInitiated;
        mNonIncremental = nonIncremental;
        mAgentTimeoutParameters =
                Preconditions.checkNotNull(
                        backupManagerService.getAgentTimeoutParameters(),
                        "Timeout parameters cannot be null");
        mStateDir = new File(backupManagerService.getBaseStateDir(), transportDirName);
        mCurrentOpToken = backupManagerService.generateRandomIntegerToken();
    }

    private void registerTask() {
        mBackupManagerService.putOperation(
                mCurrentOpToken, new Operation(OP_PENDING, this, OP_TYPE_BACKUP));
    }

    private void unregisterTask() {
        mBackupManagerService.removeOperation(mCurrentOpToken);
    }

    @Override
    public void run() {
        Process.setThreadPriority(THREAD_PRIORITY);

        BackupState state = startBackup();
        while (state == BackupState.RUNNING_QUEUE || state == BackupState.BACKUP_PM) {
            if (mCancelled) {
                state = BackupState.CANCELLED;
            }
            switch (state) {
                case BACKUP_PM:
                    state = backupPm();
                    break;
                case RUNNING_QUEUE:
                    Pair<BackupState, RemoteResult> stateAndResult = extractNextAgentData();
                    state = stateAndResult.first;
                    if (state == null) {
                        state = handleAgentResult(stateAndResult.second);
                    }
                    break;
            }
        }
        if (state == BackupState.CANCELLED) {
            finishCancelledBackup();
        } else {
            finishBackup();
        }
    }

    private BackupState handleAgentResult(RemoteResult result) {
        if (result == RemoteResult.FAILED_THREAD_INTERRUPTED) {
            // Not an explicit cancel, we need to flag it.
            mCancelled = true;
            handleAgentCancelled();
            return BackupState.CANCELLED;
        }
        if (result == RemoteResult.FAILED_CANCELLED) {
            handleAgentCancelled();
            return BackupState.CANCELLED;
        }
        if (result == RemoteResult.FAILED_TIMED_OUT) {
            handleAgentTimeout();
            return BackupState.RUNNING_QUEUE;
        }
        Preconditions.checkState(result.succeeded());
        return sendDataToTransport(result.get());
    }

    @Override
    public void execute() {}

    @Override
    public void operationComplete(long unusedResult) {}

    private BackupState startBackup() {
        synchronized (mBackupManagerService.getCurrentOpLock()) {
            if (mBackupManagerService.isBackupOperationInProgress()) {
                mReporter.onSkipBackup();
                return BackupState.FINAL;
            }
        }

        String[] fullBackups = mPendingFullBackups.toArray(new String[mPendingFullBackups.size()]);
        mFullBackupTask =
                new PerformFullTransportBackupTask(
                        mBackupManagerService,
                        mTransportClient,
                        /* fullBackupRestoreObserver */ null,
                        fullBackups,
                        /* updateSchedule */ false,
                        /* runningJob */ null,
                        new CountDownLatch(1),
                        mObserver,
                        mReporter.getMonitor(),
                        mTaskFinishedListener,
                        mUserInitiated);
        registerTask();

        mAgentBinder = null;
        mStatus = BackupTransport.TRANSPORT_OK;

        // Sanity check: if the queue is empty we have no work to do.
        if (mOriginalQueue.isEmpty() && mPendingFullBackups.isEmpty()) {
            mReporter.onEmptyQueueAtStart();
            return BackupState.FINAL;
        }

        // When the transport is forcing non-incremental key/value payloads, we send the
        // metadata only if it explicitly asks for it.
        boolean skipPm = mNonIncremental;

        // The app metadata pseudopackage might also be represented in the
        // backup queue if apps have been added/removed since the last time
        // we performed a backup.  Drop it from the working queue now that
        // we're committed to evaluating it for backup regardless.
        for (int i = 0; i < mQueue.size(); i++) {
            if (PACKAGE_MANAGER_SENTINEL.equals(mQueue.get(i).packageName)) {
                mReporter.onPmFoundInQueue();
                mQueue.remove(i);
                skipPm = false;
                break;
            }
        }
        mReporter.onQueueReady(mQueue);

        File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
        try {
            IBackupTransport transport = mTransportClient.connectOrThrow("KVBT.startBackup()");
            String transportName = transport.name();
            mReporter.onTransportReady(transportName);

            // If we haven't stored PM metadata yet, we must initialize the transport.
            if (pmState.length() <= 0) {
                mReporter.onInitializeTransport(transportName);
                mBackupManagerService.resetBackupState(mStateDir);
                mStatus = transport.initializeDevice();
                mReporter.onTransportInitialized(mStatus);
            }
        } catch (Exception e) {
            mReporter.onInitializeTransportError(e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            mBackupManagerService.resetBackupState(mStateDir);
            return BackupState.FINAL;
        }

        if (skipPm) {
            mReporter.onSkipPm();
            return BackupState.RUNNING_QUEUE;
        }

        return BackupState.BACKUP_PM;
    }

    private BackupState backupPm() {
        RemoteResult agentResult = null;
        try {
            // Since PM is running in the system process we can set up its agent directly.
            BackupAgent pmAgent = mBackupManagerService.makeMetadataAgent();
            Pair<Integer, RemoteResult> statusAndResult =
                    extractAgentData(
                            PACKAGE_MANAGER_SENTINEL,
                            IBackupAgent.Stub.asInterface(pmAgent.onBind()));
            mStatus = statusAndResult.first;
            agentResult = statusAndResult.second;
        } catch (Exception e) {
            mReporter.onInvokePmAgentError(e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            mBackupManagerService.resetBackupState(mStateDir);
            return BackupState.FINAL;
        }

        Preconditions.checkNotNull(agentResult);
        return handleAgentResult(agentResult);
    }

    /**
     * Returns either:
     *
     * <ul>
     *   <li>(next state, {@code null}): In case we failed to call the agent.
     *   <li>({@code null}, agent result): In case we successfully called the agent.
     * </ul>
     */
    private Pair<BackupState, RemoteResult> extractNextAgentData() {
        mStatus = BackupTransport.TRANSPORT_OK;

        if (mQueue.isEmpty()) {
            mReporter.onEmptyQueue();
            return Pair.create(BackupState.FINAL, null);
        }

        BackupRequest request = mQueue.remove(0);
        String packageName = request.packageName;
        mReporter.onStartPackageBackup(packageName);

        // Verify that the requested app is eligible for key-value backup.
        RemoteResult agentResult = null;
        try {
            mCurrentPackage = mPackageManager.getPackageInfo(
                    request.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            ApplicationInfo applicationInfo = mCurrentPackage.applicationInfo;
            if (!AppBackupUtils.appIsEligibleForBackup(applicationInfo, mPackageManager)) {
                // The manifest has changed. This won't happen again because the app won't be
                // requesting further backups.
                mReporter.onPackageNotEligibleForBackup(packageName);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            if (AppBackupUtils.appGetsFullBackup(mCurrentPackage)) {
                // Initially enqueued for key-value backup, but only supports full-backup now.
                mReporter.onPackageEligibleForFullBackup(packageName);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            if (AppBackupUtils.appIsStopped(applicationInfo)) {
                // Just as it won't receive broadcasts, we won't run it for backup.
                mReporter.onPackageStopped(packageName);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            try {
                mBackupManagerService.setWorkSource(new WorkSource(applicationInfo.uid));
                IBackupAgent agent =
                        mBackupManagerService.bindToAgentSynchronous(
                                applicationInfo,
                                ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);
                if (agent != null) {
                    mAgentBinder = agent;
                    Pair<Integer, RemoteResult> statusAndResult =
                            extractAgentData(request.packageName, agent);
                    mStatus = statusAndResult.first;
                    agentResult = statusAndResult.second;
                } else {
                    // Timeout waiting for the agent to bind.
                    mStatus = BackupTransport.AGENT_ERROR;
                }
            } catch (SecurityException e) {
                mReporter.onBindAgentError(e);
                mStatus = BackupTransport.AGENT_ERROR;
            }
        } catch (PackageManager.NameNotFoundException e) {
            mStatus = BackupTransport.AGENT_UNKNOWN;
        } finally {
            mBackupManagerService.setWorkSource(null);
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            mAgentBinder = null;

            if (mStatus == BackupTransport.AGENT_ERROR) {
                mReporter.onAgentError(packageName);
                mBackupManagerService.dataChangedImpl(request.packageName);
                mStatus = BackupTransport.TRANSPORT_OK;
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            if (mStatus == BackupTransport.AGENT_UNKNOWN) {
                mReporter.onAgentUnknown(packageName);
                mStatus = BackupTransport.TRANSPORT_OK;
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            // Transport-level failure, re-enqueue everything.
            revertBackup();
            return Pair.create(BackupState.FINAL, null);
        }

        // Success: caller will figure out the state based on call result
        return Pair.create(null, agentResult);
    }

    private void finishBackup() {
        // Mark packages that we couldn't backup as pending backup.
        for (BackupRequest request : mQueue) {
            mBackupManagerService.dataChangedImpl(request.packageName);
        }

        // If backup succeeded, we just invalidated this journal. If not, we've already re-enqueued
        // the packages and also don't need the journal.
        if (mJournal != null && !mJournal.delete()) {
            mReporter.onJournalDeleteFailed(mJournal);
        }

        String callerLogString = "KVBT.finishBackup()";

        // If we succeeded and this is the first time we've done a backup, we can record the current
        // backup dataset token.
        long currentToken = mBackupManagerService.getCurrentToken();
        if ((mStatus == BackupTransport.TRANSPORT_OK) && (currentToken == 0)) {
            try {
                IBackupTransport transport = mTransportClient.connectOrThrow(callerLogString);
                mBackupManagerService.setCurrentToken(transport.getCurrentRestoreSet());
                mBackupManagerService.writeRestoreTokens();
            } catch (Exception e) {
                // This will be recorded the next time we succeed.
                mReporter.onSetCurrentTokenError(e);
            }
        }

        synchronized (mBackupManagerService.getQueueLock()) {
            mBackupManagerService.setBackupRunning(false);
            if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                mReporter.onTransportNotInitialized();
                try {
                    IBackupTransport transport = mTransportClient.connectOrThrow(callerLogString);
                    mBackupManagerService.getPendingInits().add(transport.name());
                    clearPmMetadata();
                    mBackupManagerService.backupNow();
                } catch (Exception e) {
                    mReporter.onPendingInitializeTransportError(e);
                }
            }
        }

        unregisterTask();
        mReporter.onKeyValueBackupFinished();

        if (!mCancelled
                && mStatus == BackupTransport.TRANSPORT_OK
                && mFullBackupTask != null
                && !mPendingFullBackups.isEmpty()) {
            mReporter.onStartFullBackup(mPendingFullBackups);
            // The key-value backup has finished but not the overall backup. Full-backup task will:
            // * Call mObserver.backupFinished() (which is called by mReporter below).
            // * Call mTaskFinishedListener.onFinished().
            // * Release the wakelock.
            (new Thread(mFullBackupTask, "full-transport-requested")).start();
            return;
        }

        if (mFullBackupTask != null) {
            mFullBackupTask.unregisterTask();
        }
        mTaskFinishedListener.onFinished(callerLogString);
        mReporter.onBackupFinished(getBackupFinishedStatus(mCancelled, mStatus));
        mBackupManagerService.getWakelock().release();
    }

    private int getBackupFinishedStatus(boolean cancelled, int transportStatus) {
        if (cancelled) {
            return BackupManager.ERROR_BACKUP_CANCELLED;
        }
        switch (transportStatus) {
            case BackupTransport.TRANSPORT_OK:
            case BackupTransport.TRANSPORT_QUOTA_EXCEEDED:
            case BackupTransport.TRANSPORT_PACKAGE_REJECTED:
                return BackupManager.SUCCESS;
            case BackupTransport.TRANSPORT_NOT_INITIALIZED:
            case BackupTransport.TRANSPORT_ERROR:
            default:
                return BackupManager.ERROR_TRANSPORT_ABORTED;
        }
    }

    /** Removes PM state, triggering initialization in the next key-value task. */
    private void clearPmMetadata() {
        File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
        if (pmState.exists()) {
            pmState.delete();
        }
    }

    /**
     * Returns a {@link Pair}. The first of the pair contains the status. In case the status is
     * {@link BackupTransport#TRANSPORT_OK}, the second of the pair contains the agent result,
     * otherwise {@code null}.
     */
    private Pair<Integer, RemoteResult> extractAgentData(String packageName, IBackupAgent agent) {
        mReporter.onInvokeAgent(packageName);

        File blankStateFile = new File(mStateDir, BLANK_STATE_FILE_NAME);
        mSavedStateFile = new File(mStateDir, packageName);
        File savedStateFileForAgent = (mNonIncremental) ? blankStateFile : mSavedStateFile;
        mBackupDataFile =
                new File(mBackupManagerService.getDataDir(), packageName + STAGING_FILE_SUFFIX);
        mNewStateFile = new File(mStateDir, packageName + NEW_STATE_FILE_SUFFIX);
        mReporter.onAgentFilesReady(mBackupDataFile);

        mSavedState = null;
        mBackupData = null;
        mNewState = null;

        boolean callingAgent = false;
        final RemoteResult agentResult;
        try {
            // TODO: Move this to backupPm()
            if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                mCurrentPackage = new PackageInfo();
                mCurrentPackage.packageName = packageName;
            }

            // MODE_CREATE to make an empty file if necessary
            mSavedState = ParcelFileDescriptor.open(
                    savedStateFileForAgent, MODE_READ_ONLY | MODE_CREATE);
            mBackupData = ParcelFileDescriptor.open(
                    mBackupDataFile, MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);
            mNewState = ParcelFileDescriptor.open(
                    mNewStateFile, MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);

            if (!SELinux.restorecon(mBackupDataFile)) {
                mReporter.onRestoreconFailed(mBackupDataFile);
            }

            IBackupTransport transport = mTransportClient.connectOrThrow("KVBT.extractAgentData()");
            long quota = transport.getBackupQuota(packageName, /* isFullBackup */ false);
            int transportFlags = transport.getTransportFlags();
            long kvBackupAgentTimeoutMillis =
                    mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();

            callingAgent = true;
            agentResult =
                    remoteCall(
                            callback ->
                                    agent.doBackup(
                                            mSavedState,
                                            mBackupData,
                                            mNewState,
                                            quota,
                                            callback,
                                            transportFlags),
                            kvBackupAgentTimeoutMillis);
        } catch (Exception e) {
            mReporter.onCallAgentDoBackupError(packageName, callingAgent, e);
            errorCleanup();
            // TODO: Remove the check on callingAgent when RemoteCall supports local agent calls.
            int status =
                    callingAgent ? BackupTransport.AGENT_ERROR : BackupTransport.TRANSPORT_ERROR;
            return Pair.create(status, null);
        }
        if (mNonIncremental) {
            blankStateFile.delete();
        }
        return Pair.create(BackupTransport.TRANSPORT_OK, agentResult);
    }

    private void failAgent(IBackupAgent agent, String message) {
        try {
            agent.fail(message);
        } catch (Exception e) {
            mReporter.onFailAgentError(mCurrentPackage.packageName);
        }
    }

    // SHA-1 a byte array and return the result in hex
    private String SHA1Checksum(byte[] input) {
        final byte[] checksum;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            checksum = md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            mReporter.onDigestError(e);
            return "00";
        }

        StringBuilder string = new StringBuilder(checksum.length * 2);
        for (byte item : checksum) {
            string.append(Integer.toHexString(item));
        }
        return string.toString();
    }

    private void writeWidgetPayloadIfAppropriate(FileDescriptor fd, String pkgName)
            throws IOException {
        // TODO: http://b/22388012
        byte[] widgetState = AppWidgetBackupBridge.getWidgetState(pkgName, UserHandle.USER_SYSTEM);
        File widgetFile = new File(mStateDir, pkgName + "_widget");
        boolean priorStateExists = widgetFile.exists();
        if (!priorStateExists && widgetState == null) {
            return;
        }
        mReporter.onWriteWidgetData(priorStateExists, widgetState);

        // if the new state is not null, we might need to compare checksums to
        // determine whether to update the widget blob in the archive.  If the
        // widget state *is* null, we know a priori at this point that we simply
        // need to commit a deletion for it.
        String newChecksum = null;
        if (widgetState != null) {
            newChecksum = SHA1Checksum(widgetState);
            if (priorStateExists) {
                final String priorChecksum;
                try (
                        FileInputStream fin = new FileInputStream(widgetFile);
                        DataInputStream in = new DataInputStream(fin)
                ) {
                    priorChecksum = in.readUTF();
                }
                if (Objects.equals(newChecksum, priorChecksum)) {
                    // Same checksum => no state change => don't rewrite the widget data
                    return;
                }
            }
        } // else widget state *became* empty, so we need to commit a deletion

        BackupDataOutput out = new BackupDataOutput(fd);
        if (widgetState != null) {
            try (
                    FileOutputStream fout = new FileOutputStream(widgetFile);
                    DataOutputStream stateOut = new DataOutputStream(fout)
            ) {
                stateOut.writeUTF(newChecksum);
            }

            out.writeEntityHeader(KEY_WIDGET_STATE, widgetState.length);
            out.writeEntityData(widgetState, widgetState.length);
        } else {
            // Widget state for this app has been removed; commit a deletion
            out.writeEntityHeader(KEY_WIDGET_STATE, -1);
            widgetFile.delete();
        }
    }

    private BackupState sendDataToTransport(long agentResult) {
        Preconditions.checkState(mBackupData != null);

        String packageName = mCurrentPackage.packageName;
        ApplicationInfo applicationInfo = mCurrentPackage.applicationInfo;
        long filePos = mBackupDataFile.length();
        FileDescriptor fd = mBackupData.getFileDescriptor();
        boolean writingWidgetData = false;
        try {
            // If it's a 3rd party app, crash them if they wrote any protected keys.
            if (applicationInfo != null &&
                    (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                ParcelFileDescriptor readFd =
                        ParcelFileDescriptor.open(mBackupDataFile, MODE_READ_ONLY);
                BackupDataInput in = new BackupDataInput(readFd.getFileDescriptor());
                try {
                    while (in.readNextHeader()) {
                        String key = in.getKey();
                        if (key != null && key.charAt(0) >= 0xff00) {
                            mReporter.onAgentIllegalKey(mCurrentPackage, key);
                            failAgent(mAgentBinder, "Illegal backup key: " + key);
                            errorCleanup();
                            return BackupState.RUNNING_QUEUE;
                        }
                        in.skipEntityData();
                    }
                } finally {
                    readFd.close();
                }
            }

            writingWidgetData = true;
            writeWidgetPayloadIfAppropriate(fd, packageName);
        } catch (IOException e) {
            if (writingWidgetData) {
                mReporter.onWriteWidgetDataError(packageName, e);
            } else {
                mReporter.onReadAgentDataError(packageName, e);
            }
            try {
                Os.ftruncate(fd, filePos);
            } catch (ErrnoException ee) {
                mReporter.onTruncateDataError();
            }
        }

        clearAgentState();

        ParcelFileDescriptor backupData = null;
        mStatus = BackupTransport.TRANSPORT_OK;
        long size = 0;
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.sendDataToTransport()");
            size = mBackupDataFile.length();
            if (size > 0) {
                boolean isNonIncremental = mSavedStateFile.length() == 0;

                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    mReporter.onSendDataToTransport(packageName);
                    backupData = ParcelFileDescriptor.open(mBackupDataFile, MODE_READ_ONLY);
                    int userInitiatedFlag =
                            mUserInitiated ? BackupTransport.FLAG_USER_INITIATED : 0;
                    int incrementalFlag =
                            isNonIncremental
                                    ? BackupTransport.FLAG_NON_INCREMENTAL
                                    : BackupTransport.FLAG_INCREMENTAL;
                    int flags = userInitiatedFlag | incrementalFlag;

                    mStatus = transport.performBackup(mCurrentPackage, backupData, flags);
                }

                if (isNonIncremental
                        && mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
                    mReporter.onNonIncrementalAndNonIncrementalRequired();
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                }

                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    mStatus = transport.finishBackup();
                }
            } else {
                mReporter.onEmptyData(mCurrentPackage);
            }

            if (mStatus == BackupTransport.TRANSPORT_OK) {
                mBackupDataFile.delete();
                mNewStateFile.renameTo(mSavedStateFile);
                mReporter.onPackageBackupComplete(packageName, size);
            } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                mBackupDataFile.delete();
                mNewStateFile.delete();
                mReporter.onPackageBackupRejected(packageName);
            } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                // TODO: Should reset files like above?
                mReporter.onPackageBackupQuotaExceeded(packageName);
            } else if (mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
                mReporter.onPackageBackupNonIncrementalRequired(mCurrentPackage);
                mBackupDataFile.delete();
                mSavedStateFile.delete();
                mNewStateFile.delete();

                // Immediately retry the package by adding it back to the front of the queue.
                // We cannot add @pm@ to the queue because we back it up separately at the start
                // of the backup pass in state BACKUP_PM. See below.
                if (!PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
                    mQueue.add(0, new BackupRequest(packageName));
                }
            } else {
                mReporter.onPackageBackupTransportFailure(packageName);
            }
        } catch (Exception e) {
            mReporter.onPackageBackupError(packageName, e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        } finally {
            tryCloseFileDescriptor(backupData, "backup data");
        }

        final BackupState nextState;
        if (mStatus == BackupTransport.TRANSPORT_OK
                || mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
            nextState = BackupState.RUNNING_QUEUE;

        } else if (mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
            // We want to immediately retry the current package.
            if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
                nextState = BackupState.BACKUP_PM;
            } else {
                // This is an ordinary package so we will have added it back into the queue
                // above. Thus, we proceed processing the queue.
                nextState = BackupState.RUNNING_QUEUE;
            }

        } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
            if (mAgentBinder != null) {
                try {
                    IBackupTransport transport =
                            mTransportClient.connectOrThrow("KVBT.sendDataToTransport()");
                    long quota = transport.getBackupQuota(mCurrentPackage.packageName, false);
                    mAgentBinder.doQuotaExceeded(size, quota);
                } catch (Exception e) {
                    mReporter.onAgentDoQuotaExceededError(e);
                }
            }
            nextState = BackupState.RUNNING_QUEUE;
        } else {
            // Any other error here indicates a transport-level failure.  That means
            // we need to halt everything and reschedule everything for next time.
            revertBackup();
            nextState = BackupState.FINAL;
        }

        return nextState;
    }

    /**
     * Cancels this task. After this method returns there will be no more calls to the transport.
     *
     * <p>If this method is executed while an agent is performing a backup, we will stop waiting for
     * it, disregard its backup data and finalize the task. However, if this method is executed in
     * between agent calls, the backup data of the last called agent will be sent to
     * the transport and we will not consider the next agent (nor the rest of the queue), proceeding
     * to finalize the backup.
     *
     * @param cancelAll MUST be {@code true}. Will be removed.
     */
    @Override
    public void handleCancel(boolean cancelAll) {
        // This is called in a thread different from the one that executes method run().
        Preconditions.checkArgument(cancelAll, "Can't partially cancel a key-value backup task");
        mReporter.onCancel();
        mCancelled = true;
        RemoteCall pendingCall = mPendingCall;
        if (pendingCall != null) {
            pendingCall.cancel();
        }
        mCancelAcknowledged.block();
    }

    private void handleAgentTimeout() {
        mReporter.onAgentTimedOut(mCurrentPackage);
        errorCleanup();
    }

    private void handleAgentCancelled() {
        mReporter.onAgentCancelled(mCurrentPackage);
        errorCleanup();
    }

    private void finishCancelledBackup() {
        finishBackup();
        // finalizeBackup() may call the transport, so we only acknowledge the cancellation here.
        mCancelAcknowledged.open();
    }

    private void revertBackup() {
        mReporter.onRevertBackup();
        long delay;
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.revertBackup()");
            delay = transport.requestBackupTime();
        } catch (Exception e) {
            mReporter.onTransportRequestBackupTimeError(e);
            // Use the scheduler's default.
            delay = 0;
        }
        KeyValueBackupJob.schedule(
                mBackupManagerService.getContext(), delay, mBackupManagerService.getConstants());

        for (BackupRequest request : mOriginalQueue) {
            mBackupManagerService.dataChangedImpl(request.packageName);
        }
    }

    private void errorCleanup() {
        mBackupDataFile.delete();
        mNewStateFile.delete();
        clearAgentState();
    }

    private void clearAgentState() {
        // Cleanup common to both success and failure cases.
        tryCloseFileDescriptor(mSavedState, "old state");
        tryCloseFileDescriptor(mBackupData, "backup data");
        tryCloseFileDescriptor(mNewState, "new state");
        synchronized (mBackupManagerService.getCurrentOpLock()) {
            // TODO: Do we still need this?
            mSavedState = mBackupData = mNewState = null;
        }

        // For PM metadata (for which applicationInfo is null) there is no agent-bound state.
        if (mCurrentPackage.applicationInfo != null) {
            mBackupManagerService.unbindAgent(mCurrentPackage.applicationInfo);
        }
    }

    private void tryCloseFileDescriptor(@Nullable Closeable closeable, String logName) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                mReporter.onCloseFileDescriptorError(logName);
            }
        }
    }

    private RemoteResult remoteCall(RemoteCallable<IBackupCallback> remoteCallable, long timeoutMs)
            throws RemoteException {
        mPendingCall = new RemoteCall(mCancelled, remoteCallable, timeoutMs);
        RemoteResult result = mPendingCall.call();
        mReporter.onRemoteCallReturned(result);
        mPendingCall = null;
        return result;
    }

    private enum BackupState {
        INITIAL,
        BACKUP_PM,
        RUNNING_QUEUE,
        CANCELLED,
        FINAL
    }
}
