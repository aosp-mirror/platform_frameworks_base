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
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
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
    private static final int THREAD_PRIORITY = Process.THREAD_PRIORITY_BACKGROUND;
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    private static final String BLANK_STATE_FILE_NAME = "blank_state";
    private static final String PM_PACKAGE = BackupManagerService.PACKAGE_MANAGER_SENTINEL;
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
     * @param queue The list of package names that will be backed-up.
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
            List<String> queue,
            @Nullable DataChangedJournal dataChangedJournal,
            IBackupObserver observer,
            @Nullable IBackupManagerMonitor monitor,
            OnTaskFinishedListener listener,
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental) {
        KeyValueBackupReporter reporter =
                new KeyValueBackupReporter(backupManagerService, observer, monitor);
        KeyValueBackupTask task =
                new KeyValueBackupTask(
                        backupManagerService,
                        transportClient,
                        transportDirName,
                        queue,
                        dataChangedJournal,
                        reporter,
                        listener,
                        pendingFullBackups,
                        userInitiated,
                        nonIncremental);
        Thread thread = new Thread(task, "key-value-backup-" + THREAD_COUNT.incrementAndGet());
        thread.start();
        KeyValueBackupReporter.onNewThread(thread.getName());
        return task;
    }

    private final BackupManagerService mBackupManagerService;
    private final PackageManager mPackageManager;
    private final TransportManager mTransportManager;
    private final TransportClient mTransportClient;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final KeyValueBackupReporter mReporter;
    private final OnTaskFinishedListener mTaskFinishedListener;
    private final boolean mUserInitiated;
    private final boolean mNonIncremental;
    private final int mCurrentOpToken;
    private final File mStateDirectory;
    private final File mDataDirectory;
    private final List<String> mOriginalQueue;
    private final List<String> mQueue;
    private final List<String> mPendingFullBackups;
    private final Object mQueueLock;
    @Nullable private final DataChangedJournal mJournal;

    private int mStatus;
    @Nullable private PerformFullTransportBackupTask mFullBackupTask;
    @Nullable private IBackupAgent mAgent;
    @Nullable private PackageInfo mCurrentPackage;
    @Nullable private File mSavedStateFile;
    @Nullable private File mBackupDataFile;
    @Nullable private File mNewStateFile;
    @Nullable private ParcelFileDescriptor mSavedState;
    @Nullable private ParcelFileDescriptor mBackupData;
    @Nullable private ParcelFileDescriptor mNewState;

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
            List<String> queue,
            @Nullable DataChangedJournal journal,
            KeyValueBackupReporter reporter,
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
        mReporter = reporter;
        mTaskFinishedListener = taskFinishedListener;
        mPendingFullBackups = pendingFullBackups;
        mUserInitiated = userInitiated;
        mNonIncremental = nonIncremental;
        mAgentTimeoutParameters =
                Preconditions.checkNotNull(
                        backupManagerService.getAgentTimeoutParameters(),
                        "Timeout parameters cannot be null");
        mStateDirectory = new File(backupManagerService.getBaseStateDir(), transportDirName);
        mDataDirectory = mBackupManagerService.getDataDir();
        mCurrentOpToken = backupManagerService.generateRandomIntegerToken();
        mQueueLock = mBackupManagerService.getQueueLock();
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

        boolean processQueue = startTask();
        while (processQueue && !mQueue.isEmpty() && !mCancelled) {
            String packageName = mQueue.remove(0);
            if (PM_PACKAGE.equals(packageName)) {
                processQueue = backupPm();
            } else {
                processQueue = backupPackage(packageName);
            }
        }
        finishTask();
    }

    /** Returns whether to consume next queue package. */
    private boolean handleAgentResult(@Nullable PackageInfo packageInfo, RemoteResult result) {
        if (result == RemoteResult.FAILED_THREAD_INTERRUPTED) {
            // Not an explicit cancel, we need to flag it.
            mCancelled = true;
            mReporter.onAgentCancelled(packageInfo);
            cleanUpAgentForAgentError();
            return false;
        }
        if (result == RemoteResult.FAILED_CANCELLED) {
            mReporter.onAgentCancelled(packageInfo);
            cleanUpAgentForAgentError();
            return false;
        }
        if (result == RemoteResult.FAILED_TIMED_OUT) {
            mReporter.onAgentTimedOut(packageInfo);
            cleanUpAgentForAgentError();
            return true;
        }
        Preconditions.checkState(result.isPresent());
        long agentResult = result.get();
        if (agentResult == BackupAgent.RESULT_ERROR) {
            mReporter.onAgentResultError(packageInfo);
            cleanUpAgentForAgentError();
            return true;
        }
        return sendDataToTransport();
    }

    @Override
    public void execute() {}

    @Override
    public void operationComplete(long unusedResult) {}

    /** Returns whether to consume next queue package. */
    private boolean startTask() {
        if (mBackupManagerService.isBackupOperationInProgress()) {
            mReporter.onSkipBackup();
            return false;
        }

        // Unfortunately full backup task constructor registers the task with BMS, so we have to
        // create it here instead of in our constructor.
        mFullBackupTask = createFullBackupTask(mPendingFullBackups);
        registerTask();

        mStatus = BackupTransport.TRANSPORT_OK;

        if (mQueue.isEmpty() && mPendingFullBackups.isEmpty()) {
            mReporter.onEmptyQueueAtStart();
            return false;
        }
        // We only backup PM if it was explicitly in the queue or if it's incremental.
        boolean backupPm = mQueue.remove(PM_PACKAGE) || !mNonIncremental;
        if (backupPm) {
            mQueue.add(0, PM_PACKAGE);
        } else {
            mReporter.onSkipPm();
        }

        mReporter.onQueueReady(mQueue);
        File pmState = new File(mStateDirectory, PM_PACKAGE);
        try {
            IBackupTransport transport = mTransportClient.connectOrThrow("KVBT.startTask()");
            String transportName = transport.name();
            mReporter.onTransportReady(transportName);

            // If we haven't stored PM metadata yet, we must initialize the transport.
            if (pmState.length() <= 0) {
                mReporter.onInitializeTransport(transportName);
                mBackupManagerService.resetBackupState(mStateDirectory);
                mStatus = transport.initializeDevice();
                mReporter.onTransportInitialized(mStatus);
            }
        } catch (Exception e) {
            mReporter.onInitializeTransportError(e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            mBackupManagerService.resetBackupState(mStateDirectory);
            return false;
        }

        return true;
    }

    private PerformFullTransportBackupTask createFullBackupTask(List<String> packages) {
        return new PerformFullTransportBackupTask(
                mBackupManagerService,
                mTransportClient,
                /* fullBackupRestoreObserver */ null,
                packages.toArray(new String[packages.size()]),
                /* updateSchedule */ false,
                /* runningJob */ null,
                new CountDownLatch(1),
                mReporter.getObserver(),
                mReporter.getMonitor(),
                mTaskFinishedListener,
                mUserInitiated);
    }

    /** Returns whether to consume next queue package. */
    private boolean backupPm() {
        RemoteResult agentResult = null;
        try {
            mCurrentPackage = new PackageInfo();
            mCurrentPackage.packageName = PM_PACKAGE;

            // Since PM is running in the system process we can set up its agent directly.
            BackupAgent pmAgent = mBackupManagerService.makeMetadataAgent();
            mAgent = IBackupAgent.Stub.asInterface(pmAgent.onBind());

            Pair<Integer, RemoteResult> statusAndResult = extractAgentData(PM_PACKAGE, mAgent);
            mStatus = statusAndResult.first;
            agentResult = statusAndResult.second;
        } catch (Exception e) {
            mReporter.onExtractPmAgentDataError(e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            // In this case either extractAgentData() already made the agent clean-up or we haven't
            // prepared the state for calling the agent, in either case we don't need to clean-up.
            mBackupManagerService.resetBackupState(mStateDirectory);
            return false;
        }

        Preconditions.checkNotNull(agentResult);
        return handleAgentResult(mCurrentPackage, agentResult);
    }

    /** Returns whether to consume next queue package. */
    private boolean backupPackage(String packageName) {
        mReporter.onStartPackageBackup(packageName);
        mStatus = BackupTransport.TRANSPORT_OK;

        // Verify that the requested app is eligible for key-value backup.
        RemoteResult agentResult = null;
        try {
            mCurrentPackage = mPackageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            ApplicationInfo applicationInfo = mCurrentPackage.applicationInfo;
            if (!AppBackupUtils.appIsEligibleForBackup(applicationInfo, mPackageManager)) {
                // The manifest has changed. This won't happen again because the app won't be
                // requesting further backups.
                mReporter.onPackageNotEligibleForBackup(packageName);
                return true;
            }

            if (AppBackupUtils.appGetsFullBackup(mCurrentPackage)) {
                // Initially enqueued for key-value backup, but only supports full-backup now.
                mReporter.onPackageEligibleForFullBackup(packageName);
                return true;
            }

            if (AppBackupUtils.appIsStopped(applicationInfo)) {
                // Just as it won't receive broadcasts, we won't run it for backup.
                mReporter.onPackageStopped(packageName);
                return true;
            }

            try {
                mBackupManagerService.setWorkSource(new WorkSource(applicationInfo.uid));
                IBackupAgent agent =
                        mBackupManagerService.bindToAgentSynchronous(
                                applicationInfo,
                                ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);
                if (agent != null) {
                    mAgent = agent;
                    Pair<Integer, RemoteResult> statusAndResult =
                            extractAgentData(packageName, agent);
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
            // In this case either extractAgentData() already made the agent clean-up or we haven't
            // prepared the state for calling the agent, in either case we don't need to clean-up.
            Preconditions.checkState(mAgent == null);

            if (mStatus == BackupTransport.AGENT_ERROR) {
                mReporter.onAgentError(packageName);
                mBackupManagerService.dataChangedImpl(packageName);
                mStatus = BackupTransport.TRANSPORT_OK;
                return true;
            }

            if (mStatus == BackupTransport.AGENT_UNKNOWN) {
                mReporter.onAgentUnknown(packageName);
                mStatus = BackupTransport.TRANSPORT_OK;
                return true;
            }

            // Transport-level failure, re-enqueue everything.
            revertTask();
            return false;
        }

        Preconditions.checkNotNull(agentResult);
        return handleAgentResult(mCurrentPackage, agentResult);
    }

    private void finishTask() {
        // Mark packages that we couldn't backup as pending backup.
        for (String packageName : mQueue) {
            mBackupManagerService.dataChangedImpl(packageName);
        }

        // If backup succeeded, we just invalidated this journal. If not, we've already re-enqueued
        // the packages and also don't need the journal.
        if (mJournal != null && !mJournal.delete()) {
            mReporter.onJournalDeleteFailed(mJournal);
        }

        String callerLogString = "KVBT.finishTask()";

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

        synchronized (mQueueLock) {
            mBackupManagerService.setBackupRunning(false);
            if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                mReporter.onTransportNotInitialized();
                triggerTransportInitializationLocked();
            }
        }

        unregisterTask();
        mReporter.onTaskFinished();

        if (mCancelled) {
            // We acknowledge the cancel as soon as we unregister the task, allowing other backups
            // to be performed.
            mCancelAcknowledged.open();
        }

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

    @GuardedBy("mQueueLock")
    private void triggerTransportInitializationLocked() {
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.triggerTransportInitializationLocked");
            mBackupManagerService.getPendingInits().add(transport.name());
            deletePmStateFile();
            mBackupManagerService.backupNow();
        } catch (Exception e) {
            mReporter.onPendingInitializeTransportError(e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }
    }

    /** Removes PM state, triggering initialization in the next key-value task. */
    private void deletePmStateFile() {
        new File(mStateDirectory, PM_PACKAGE).delete();
    }

    /**
     * Returns a {@link Pair}. The first of the pair contains the status. In case the status is
     * {@link BackupTransport#TRANSPORT_OK}, the second of the pair contains the agent result,
     * otherwise {@code null}.
     */
    private Pair<Integer, RemoteResult> extractAgentData(String packageName, IBackupAgent agent) {
        mReporter.onExtractAgentData(packageName);

        File blankStateFile = new File(mStateDirectory, BLANK_STATE_FILE_NAME);
        mSavedStateFile = new File(mStateDirectory, packageName);
        mBackupDataFile = new File(mDataDirectory, packageName + STAGING_FILE_SUFFIX);
        mNewStateFile = new File(mStateDirectory, packageName + NEW_STATE_FILE_SUFFIX);
        mReporter.onAgentFilesReady(mBackupDataFile);

        mSavedState = null;
        mBackupData = null;
        mNewState = null;

        boolean callingAgent = false;
        final RemoteResult agentResult;
        try {
            File savedStateFileForAgent = (mNonIncremental) ? blankStateFile : mSavedStateFile;
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
                            mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis(),
                            "doBackup()");
        } catch (Exception e) {
            mReporter.onCallAgentDoBackupError(packageName, callingAgent, e);
            cleanUpAgentForAgentError();
            // TODO: Remove the check on callingAgent when RemoteCall supports local agent calls.
            int status =
                    callingAgent ? BackupTransport.AGENT_ERROR : BackupTransport.TRANSPORT_ERROR;
            return Pair.create(status, null);
        }
        blankStateFile.delete();

        return Pair.create(BackupTransport.TRANSPORT_OK, agentResult);
    }

    private void agentFail(IBackupAgent agent, String message) {
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
        File widgetFile = new File(mStateDirectory, pkgName + "_widget");
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

    /** Returns whether to consume next queue package. */
    private boolean sendDataToTransport() {
        Preconditions.checkState(mBackupData != null);

        String packageName = mCurrentPackage.packageName;
        ApplicationInfo applicationInfo = mCurrentPackage.applicationInfo;

        boolean writingWidgetData = false;
        try {
            if (!validateBackupData(applicationInfo, mBackupDataFile)) {
                cleanUpAgentForAgentError();
                return true;
            }
            writingWidgetData = true;
            writeWidgetPayloadIfAppropriate(mBackupData.getFileDescriptor(), packageName);
        } catch (IOException e) {
            if (writingWidgetData) {
                mReporter.onWriteWidgetDataError(packageName, e);
            } else {
                mReporter.onReadAgentDataError(packageName, e);
            }
            cleanUpAgentForAgentError();
            revertTask();
            return false;
        }

        boolean nonIncremental = mSavedStateFile.length() == 0;
        long size = mBackupDataFile.length();
        if (size > 0) {
            try (ParcelFileDescriptor backupData =
                         ParcelFileDescriptor.open(mBackupDataFile, MODE_READ_ONLY)) {
                IBackupTransport transport =
                        mTransportClient.connectOrThrow("KVBT.sendDataToTransport()");
                mReporter.onSendDataToTransport(packageName);
                int flags = getPerformBackupFlags(mUserInitiated, nonIncremental);

                mStatus = transport.performBackup(mCurrentPackage, backupData, flags);
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    mStatus = transport.finishBackup();
                }
            } catch (Exception e) {
                mReporter.onPackageBackupError(packageName, e);
                mStatus = BackupTransport.TRANSPORT_ERROR;
            }
        } else {
            mReporter.onEmptyData(mCurrentPackage);
            mStatus = BackupTransport.TRANSPORT_OK;
        }

        if (nonIncremental
                && mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
            mReporter.onNonIncrementalAndNonIncrementalRequired();
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }


        boolean processQueue = handleTransportStatus(mStatus, packageName, size);
        // We might report quota exceeded to the agent in handleTransportStatus() above, so we
        // only clean-up after it.
        cleanUpAgentForTransportStatus(mStatus);
        return processQueue;
    }

    /** Returns whether to consume next queue package. */
    private boolean handleTransportStatus(int status, String packageName, long size) {
        if (status == BackupTransport.TRANSPORT_OK) {
            mReporter.onPackageBackupComplete(packageName, size);
            return true;
        }
        if (status == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
            mReporter.onPackageBackupRejected(packageName);
            return true;
        }
        if (status == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
            mReporter.onPackageBackupNonIncrementalRequired(mCurrentPackage);
            // Immediately retry the current package.
            mQueue.add(0, packageName);
            return true;
        }
        if (status == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
            mReporter.onPackageBackupQuotaExceeded(packageName);
            agentDoQuotaExceeded(mAgent, packageName, size);
            return true;
        }
        // Any other error here indicates a transport-level failure.
        mReporter.onPackageBackupTransportFailure(packageName);
        revertTask();
        return false;
    }

    private void agentDoQuotaExceeded(@Nullable IBackupAgent agent, String packageName, long size) {
        if (agent != null) {
            try {
                IBackupTransport transport =
                        mTransportClient.connectOrThrow("KVBT.agentDoQuotaExceeded()");
                long quota = transport.getBackupQuota(packageName, false);
                remoteCall(
                        callback -> agent.doQuotaExceeded(size, quota, callback),
                        mAgentTimeoutParameters.getQuotaExceededTimeoutMillis(),
                        "doQuotaExceeded()");
            } catch (Exception e) {
                mReporter.onAgentDoQuotaExceededError(e);
            }
        }
    }

    /**
     * For system apps and pseudo-apps always return {@code true}. For regular apps returns whether
     * {@code backupDataFile} doesn't have any protected keys.
     *
     * <p>If the app has attempted to write any protected keys we also crash them.
     */
    private boolean validateBackupData(
            @Nullable ApplicationInfo applicationInfo, File backupDataFile) throws IOException {
        if (applicationInfo == null || (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            // System apps and pseudo-apps can write what they want.
            return true;
        }
        try (ParcelFileDescriptor backupData =
                     ParcelFileDescriptor.open(backupDataFile, MODE_READ_ONLY)) {
            BackupDataInput backupDataInput = new BackupDataInput(backupData.getFileDescriptor());
            while (backupDataInput.readNextHeader()) {
                String key = backupDataInput.getKey();
                if (key != null && key.charAt(0) >= 0xff00) {
                    mReporter.onAgentIllegalKey(mCurrentPackage, key);
                    // Crash them if they wrote any protected keys.
                    agentFail(mAgent, "Illegal backup key: " + key);
                    return false;
                }
                backupDataInput.skipEntityData();
            }
        }
        return true;
    }

    private int getPerformBackupFlags(boolean userInitiated, boolean nonIncremental) {
        int userInitiatedFlag = userInitiated ? BackupTransport.FLAG_USER_INITIATED : 0;
        int incrementalFlag =
                nonIncremental
                        ? BackupTransport.FLAG_NON_INCREMENTAL
                        : BackupTransport.FLAG_INCREMENTAL;
        return userInitiatedFlag | incrementalFlag;
    }

    /**
     * Cancels this task.
     *
     * <p>After this method returns this task won't be registered in {@link BackupManagerService}
     * anymore, which means there will be no backups running unless there is a racy request
     * coming from another thread in between. As a consequence there will be no more calls to the
     * transport originated from this task.
     *
     * <p>If this method is executed while an agent is performing a backup, we will stop waiting for
     * it, disregard its backup data and finalize the task. However, if this method is executed in
     * between agent calls, the backup data of the last called agent will be sent to
     * the transport and we will not consider the next agent (nor the rest of the queue), proceeding
     * to finalize the backup.
     *
     * <p>Note: This method is inherently racy since there are no guarantees about how much of the
     * task will be executed after you made the call.
     *
     * @param cancelAll MUST be {@code true}. Will be removed.
     */
    @Override
    public void handleCancel(boolean cancelAll) {
        // This is called in a thread different from the one that executes method run().
        Preconditions.checkArgument(cancelAll, "Can't partially cancel a key-value backup task");
        markCancel();
        waitCancel();
    }

    /** Marks this task as cancelled and tries to stop any ongoing agent call. */
    @VisibleForTesting
    public void markCancel() {
        mReporter.onCancel();
        mCancelled = true;
        RemoteCall pendingCall = mPendingCall;
        if (pendingCall != null) {
            pendingCall.cancel();
        }
    }

    /** Waits for this task to be cancelled after call to {@link #markCancel()}. */
    @VisibleForTesting
    public void waitCancel() {
        mCancelAcknowledged.block();
    }

    private void revertTask() {
        mReporter.onRevertTask();
        long delay;
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.revertTask()");
            delay = transport.requestBackupTime();
        } catch (Exception e) {
            mReporter.onTransportRequestBackupTimeError(e);
            // Use the scheduler's default.
            delay = 0;
        }
        KeyValueBackupJob.schedule(
                mBackupManagerService.getContext(), delay, mBackupManagerService.getConstants());

        for (String packageName : mOriginalQueue) {
            mBackupManagerService.dataChangedImpl(packageName);
        }
    }

    /** Cleans-up after having called the agent. */
    private void cleanUpAgentForTransportStatus(int status) {
        updateFiles(status);
        cleanUpAgent();
    }

    /** Cleans-up if we failed to call the agent. */
    private void cleanUpAgentForAgentError() {
        mBackupDataFile.delete();
        mNewStateFile.delete();
        cleanUpAgent();
    }

    private void updateFiles(int status) {
        switch (status) {
            case BackupTransport.TRANSPORT_OK:
                mBackupDataFile.delete();
                mNewStateFile.renameTo(mSavedStateFile);
                break;
            case BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED:
                mSavedStateFile.delete();
                mBackupDataFile.delete();
                mNewStateFile.delete();
                break;
            default:
                // Includes:
                // * BackupTransport.TRANSPORT_PACKAGE_REJECTED
                // * BackupTransport.TRANSPORT_QUOTA_EXCEEDED
                // * BackupTransport.TRANSPORT_ERROR
                mBackupDataFile.delete();
                mNewStateFile.delete();
                break;
        }
    }

    /** Cleans-up file-descriptors and unbinds agent. */
    private void cleanUpAgent() {
        mAgent = null;
        tryCloseFileDescriptor(mSavedState, "old state");
        tryCloseFileDescriptor(mBackupData, "backup data");
        tryCloseFileDescriptor(mNewState, "new state");
        mSavedState = null;
        mBackupData = null;
        mNewState = null;

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

    private RemoteResult remoteCall(
            RemoteCallable<IBackupCallback> remoteCallable, long timeoutMs, String logIdentifier)
            throws RemoteException {
        mPendingCall = new RemoteCall(mCancelled, remoteCallable, timeoutMs);
        RemoteResult result = mPendingCall.call();
        mReporter.onRemoteCallReturned(result, logIdentifier);
        mPendingCall = null;
        return result;
    }
}
