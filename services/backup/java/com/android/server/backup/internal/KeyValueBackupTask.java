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

import static com.android.server.backup.BackupManagerService.DEBUG_BACKUP_TRACE;
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
import android.app.backup.BackupManagerMonitor;
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
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.remote.RemoteCall;
import com.android.server.backup.remote.RemoteCallable;
import com.android.server.backup.remote.RemoteResult;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;

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
    private static final boolean DEBUG = BackupManagerService.DEBUG || true;
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
            IBackupManagerMonitor monitor,
            @Nullable OnTaskFinishedListener listener,
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
    private final TransportClient mTransportClient;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final IBackupObserver mObserver;
    private final OnTaskFinishedListener mListener;
    private final boolean mUserInitiated;
    private final boolean mNonIncremental;
    private final int mCurrentOpToken;
    private final File mStateDir;
    private final List<BackupRequest> mOriginalQueue;
    private final List<BackupRequest> mQueue;
    private final List<String> mPendingFullBackups;
    @Nullable private final DataChangedJournal mJournal;
    private IBackupManagerMonitor mMonitor;
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
            IBackupManagerMonitor monitor,
            @Nullable OnTaskFinishedListener listener,
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental) {
        mBackupManagerService = backupManagerService;
        mTransportClient = transportClient;
        mOriginalQueue = queue;
        // We need to retain the original queue contents in case of transport failure
        mQueue = new ArrayList<>(mOriginalQueue);
        mJournal = journal;
        mObserver = observer;
        mMonitor = monitor;
        mListener = (listener != null) ? listener : OnTaskFinishedListener.NOP;
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

        BackupState state = beginBackup();
        while (state == BackupState.RUNNING_QUEUE || state == BackupState.BACKUP_PM) {
            if (mCancelled) {
                state = BackupState.CANCELLED;
            }
            switch (state) {
                case BACKUP_PM:
                    state = backupPm();
                    break;
                case RUNNING_QUEUE:
                    Pair<BackupState, RemoteResult> stateAndResult = invokeNextAgent();
                    state = stateAndResult.first;
                    if (state == null) {
                        state = processAgentInvocation(stateAndResult.second);
                    }
                    break;
            }
        }
        if (state == BackupState.CANCELLED) {
            finalizeCancelledBackup();
        } else {
            finalizeBackup();
        }
    }

    private BackupState processAgentInvocation(RemoteResult result) {
        if (result == RemoteResult.FAILED_THREAD_INTERRUPTED) {
            // Not an explicit cancel, we need to flag it
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
        return handleAgentResult(result.get());
    }

    @Override
    public void execute() {}

    @Override
    public void operationComplete(long unusedResult) {}

    private BackupState beginBackup() {
        if (DEBUG_BACKUP_TRACE) {
            mBackupManagerService.clearBackupTrace();
            StringBuilder b = new StringBuilder(256);
            b.append("beginBackup: [");
            for (BackupRequest req : mOriginalQueue) {
                b.append(' ');
                b.append(req.packageName);
            }
            b.append(" ]");
            mBackupManagerService.addBackupTrace(b.toString());
        }
        synchronized (mBackupManagerService.getCurrentOpLock()) {
            if (mBackupManagerService.isBackupOperationInProgress()) {
                if (DEBUG) {
                    Slog.d(TAG, "Skipping backup since one is already in progress.");
                }
                mBackupManagerService.addBackupTrace("Skipped. Backup already in progress.");
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
                        mMonitor,
                        mListener,
                        mUserInitiated);
        registerTask();
        mBackupManagerService.addBackupTrace("STATE => INITIAL");

        mAgentBinder = null;
        mStatus = BackupTransport.TRANSPORT_OK;

        // Sanity check: if the queue is empty we have no work to do.
        if (mOriginalQueue.isEmpty() && mPendingFullBackups.isEmpty()) {
            Slog.w(TAG, "Backup begun with an empty queue - nothing to do.");
            mBackupManagerService.addBackupTrace("queue empty at begin");
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
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Metadata in queue; eliding");
                }
                mQueue.remove(i);
                skipPm = false;
                break;
            }
        }

        if (DEBUG) {
            Slog.v(TAG, "Beginning backup of " + mQueue.size() + " targets");
        }
        File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
        try {
            IBackupTransport transport = mTransportClient.connectOrThrow("KVBT.beginBackup()");
            String transportName = transport.name();
            EventLog.writeEvent(EventLogTags.BACKUP_START, transportName);

            // If we haven't stored package manager metadata yet, we must init the transport.
            if (pmState.length() <= 0) {
                Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                mBackupManagerService.addBackupTrace("initializing transport " + transportName);
                mBackupManagerService.resetBackupState(mStateDir);  // Just to make sure.
                mStatus = transport.initializeDevice();

                mBackupManagerService.addBackupTrace("transport.initializeDevice() == " + mStatus);
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                } else {
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                    Slog.e(TAG, "Transport error in initializeDevice()");
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error in backup thread during init", e);
            mBackupManagerService.addBackupTrace("Exception in backup thread during init: " + e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }
        mBackupManagerService.addBackupTrace("exiting prelim: " + mStatus);

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            // if things went wrong at this point, we need to
            // restage everything and try again later.
            mBackupManagerService.resetBackupState(mStateDir);  // Just to make sure.
            return BackupState.FINAL;
        }

        if (skipPm) {
            Slog.d(TAG, "Skipping backup of package metadata.");
            return BackupState.RUNNING_QUEUE;
        }

        return BackupState.BACKUP_PM;
    }

    private BackupState backupPm() {
        RemoteResult agentResult = null;
        BackupState nextState;
        try {
            // The package manager doesn't have a proper <application> etc, but since it's running
            // here in the system process we can just set up its agent directly and use a synthetic
            // BackupRequest.
            BackupAgent pmAgent = mBackupManagerService.makeMetadataAgent();
            Pair<Integer, RemoteResult> statusAndResult =
                    invokeAgentForBackup(
                            PACKAGE_MANAGER_SENTINEL,
                            IBackupAgent.Stub.asInterface(pmAgent.onBind()));
            mStatus = statusAndResult.first;
            agentResult = statusAndResult.second;

            mBackupManagerService.addBackupTrace("PMBA invoke: " + mStatus);
        } catch (Exception e) {
            Slog.e(TAG, "Error in backup thread during pm", e);
            mBackupManagerService.addBackupTrace("Exception in backup thread during pm: " + e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        }
        mBackupManagerService.addBackupTrace("exiting backupPm: " + mStatus);

        if (mStatus == BackupTransport.TRANSPORT_OK) {
            Preconditions.checkNotNull(agentResult);
            nextState = processAgentInvocation(agentResult);
        } else {
            // if things went wrong at this point, we need to
            // restage everything and try again later.
            mBackupManagerService.resetBackupState(mStateDir);  // Just to make sure.
            nextState = BackupState.FINAL;
        }

        return nextState;
    }

    /**
     * Returns either:
     *
     * <ul>
     *   <li>(next state, {@code null}): In case we failed to call the agent.
     *   <li>({@code null}, agent result): In case we successfully called the agent.
     * </ul>
     */
    private Pair<BackupState, RemoteResult> invokeNextAgent() {
        mStatus = BackupTransport.TRANSPORT_OK;
        mBackupManagerService.addBackupTrace("invoke q=" + mQueue.size());

        // Sanity check that we have work to do.  If not, skip to the end where
        // we reestablish the wakelock invariants etc.
        if (mQueue.isEmpty()) {
            if (MORE_DEBUG) Slog.i(TAG, "queue now empty");
            return Pair.create(BackupState.FINAL, null);
        }

        // pop the entry we're going to process on this step
        BackupRequest request = mQueue.get(0);
        mQueue.remove(0);

        Slog.d(TAG, "starting key/value backup of " + request);
        mBackupManagerService.addBackupTrace("launch agent for " + request.packageName);

        // Verify that the requested app exists; it might be something that
        // requested a backup but was then uninstalled.  The request was
        // journalled and rather than tamper with the journal it's safer
        // to sanity-check here.  This also gives us the classname of the
        // package's backup agent.
        RemoteResult agentResult = null;
        try {
            PackageManager pm = mBackupManagerService.getPackageManager();
            mCurrentPackage = pm.getPackageInfo(request.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (!AppBackupUtils.appIsEligibleForBackup(mCurrentPackage.applicationInfo, pm)) {
                // The manifest has changed but we had a stale backup request pending.
                // This won't happen again because the app won't be requesting further
                // backups.
                Slog.i(TAG, "Package " + request.packageName
                        + " no longer supports backup; skipping");
                mBackupManagerService.addBackupTrace("skipping - not eligible, completion is noop");
                // Shouldn't happen in case of requested backup, as pre-check was done in
                // #requestBackup(), except to app update done concurrently
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            if (AppBackupUtils.appGetsFullBackup(mCurrentPackage)) {
                // It's possible that this app *formerly* was enqueued for key/value backup,
                // but has since been updated and now only supports the full-data path.
                // Don't proceed with a key/value backup for it in this case.
                Slog.i(TAG, "Package " + request.packageName
                        + " requests full-data rather than key/value; skipping");
                mBackupManagerService.addBackupTrace(
                        "skipping - fullBackupOnly, completion is noop");
                // Shouldn't happen in case of requested backup, as pre-check was done in
                // #requestBackup()
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            if (AppBackupUtils.appIsStopped(mCurrentPackage.applicationInfo)) {
                // The app has been force-stopped or cleared or just installed,
                // and not yet launched out of that state, so just as it won't
                // receive broadcasts, we won't run it for backup.
                mBackupManagerService.addBackupTrace("skipping - stopped");
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                return Pair.create(BackupState.RUNNING_QUEUE, null);
            }

            try {
                mBackupManagerService.setWorkSource(
                        new WorkSource(mCurrentPackage.applicationInfo.uid));
                IBackupAgent agent =
                        mBackupManagerService.bindToAgentSynchronous(
                                mCurrentPackage.applicationInfo,
                                ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);
                mBackupManagerService.addBackupTrace("agent bound; a? = " + (agent != null));
                if (agent != null) {
                    mAgentBinder = agent;
                    Pair<Integer, RemoteResult> statusAndResult =
                            invokeAgentForBackup(request.packageName, agent);
                    mStatus = statusAndResult.first;
                    agentResult = statusAndResult.second;
                } else {
                    // Timeout waiting for the agent
                    mStatus = BackupTransport.AGENT_ERROR;
                }
            } catch (SecurityException ex) {
                // Try for the next one.
                Slog.d(TAG, "error in bind/backup", ex);
                mStatus = BackupTransport.AGENT_ERROR;
                mBackupManagerService.addBackupTrace("agent SE");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "Package does not exist; skipping");
            mBackupManagerService.addBackupTrace("no such package");
            mStatus = BackupTransport.AGENT_UNKNOWN;
        } finally {
            mBackupManagerService.setWorkSource(null);
        }

        if (mStatus != BackupTransport.TRANSPORT_OK) {
            BackupState nextState = BackupState.RUNNING_QUEUE;
            mAgentBinder = null;

            // An agent-level failure means we re-enqueue this one agent for
            // a later retry, but otherwise proceed normally.
            if (mStatus == BackupTransport.AGENT_ERROR) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Agent failure for " + request.packageName + " - restaging");
                }
                mBackupManagerService.dataChangedImpl(request.packageName);
                mStatus = BackupTransport.TRANSPORT_OK;
                BackupObserverUtils
                        .sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                                BackupManager.ERROR_AGENT_FAILURE);
            } else if (mStatus == BackupTransport.AGENT_UNKNOWN) {
                // Failed lookup of the app, so we couldn't bring up an agent, but
                // we're otherwise fine.  Just drop it and go on to the next as usual.
                mStatus = BackupTransport.TRANSPORT_OK;
                BackupObserverUtils
                        .sendBackupOnPackageResult(mObserver, request.packageName,
                                BackupManager.ERROR_PACKAGE_NOT_FOUND);
            } else {
                // Transport-level failure means we re-enqueue everything
                revertAndEndBackup();
                nextState = BackupState.FINAL;
            }

            return Pair.create(nextState, null);
        }

        // Success: caller will figure out the state based on call result
        mBackupManagerService.addBackupTrace("call made; result = " + agentResult);
        return Pair.create(null, agentResult);
    }

    private void finalizeBackup() {
        mBackupManagerService.addBackupTrace("finishing");

        // Mark packages that we didn't backup (because backup was cancelled, etc.) as needing
        // backup.
        for (BackupRequest req : mQueue) {
            mBackupManagerService.dataChangedImpl(req.packageName);
        }

        // Either backup was successful, in which case we of course do not need
        // this pass's journal any more; or it failed, in which case we just
        // re-enqueued all of these packages in the current active journal.
        // Either way, we no longer need this pass's journal.
        if (mJournal != null && !mJournal.delete()) {
            Slog.e(TAG, "Unable to remove backup journal file " + mJournal);
        }

        // If everything actually went through and this is the first time we've
        // done a backup, we can now record what the current backup dataset token
        // is.
        String callerLogString = "KVBT.finalizeBackup()";
        if ((mBackupManagerService.getCurrentToken() == 0) && (mStatus
                == BackupTransport.TRANSPORT_OK)) {
            mBackupManagerService.addBackupTrace("success; recording token");
            try {
                IBackupTransport transport = mTransportClient.connectOrThrow(callerLogString);
                mBackupManagerService.setCurrentToken(transport.getCurrentRestoreSet());
                mBackupManagerService.writeRestoreTokens();
            } catch (Exception e) {
                // nothing for it at this point, unfortunately, but this will be
                // recorded the next time we fully succeed.
                Slog.e(TAG, "Transport threw reporting restore set: " + e.getMessage());
                mBackupManagerService.addBackupTrace("transport threw returning token");
            }
        }

        // Set up the next backup pass - at this point we can set mBackupRunning
        // to false to allow another pass to fire
        synchronized (mBackupManagerService.getQueueLock()) {
            mBackupManagerService.setBackupRunning(false);
            if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                // Make sure we back up everything and perform the one-time init
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Server requires init; rerunning");
                }
                mBackupManagerService.addBackupTrace("init required; rerunning");
                try {
                    String name = mBackupManagerService.getTransportManager()
                            .getTransportName(mTransportClient.getTransportComponent());
                    mBackupManagerService.getPendingInits().add(name);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to query transport name for init: " + e.getMessage());
                    // swallow it and proceed; we don't rely on this
                }
                clearMetadata();
                mBackupManagerService.backupNow();
            }
        }

        mBackupManagerService.clearBackupTrace();

        unregisterTask();

        if (!mCancelled && mStatus == BackupTransport.TRANSPORT_OK &&
                mPendingFullBackups != null && !mPendingFullBackups.isEmpty()) {
            Slog.d(TAG, "Starting full backups for: " + mPendingFullBackups);
            // Acquiring wakelock for PerformFullTransportBackupTask before its start.
            mBackupManagerService.getWakelock().acquire();
            // The full-backup task is now responsible for calling onFinish() on mListener, which
            // was the listener we passed it.
            (new Thread(mFullBackupTask, "full-transport-requested")).start();
        } else if (mCancelled) {
            mListener.onFinished(callerLogString);
            if (mFullBackupTask != null) {
                mFullBackupTask.unregisterTask();
            }
            BackupObserverUtils.sendBackupFinished(mObserver, BackupManager.ERROR_BACKUP_CANCELLED);
        } else {
            mListener.onFinished(callerLogString);
            mFullBackupTask.unregisterTask();
            switch (mStatus) {
                case BackupTransport.TRANSPORT_OK:
                case BackupTransport.TRANSPORT_QUOTA_EXCEEDED:
                case BackupTransport.TRANSPORT_PACKAGE_REJECTED:
                    BackupObserverUtils.sendBackupFinished(mObserver, BackupManager.SUCCESS);
                    break;
                case BackupTransport.TRANSPORT_NOT_INITIALIZED:
                    BackupObserverUtils.sendBackupFinished(mObserver,
                            BackupManager.ERROR_TRANSPORT_ABORTED);
                    break;
                case BackupTransport.TRANSPORT_ERROR:
                default:
                    BackupObserverUtils.sendBackupFinished(mObserver,
                            BackupManager.ERROR_TRANSPORT_ABORTED);
                    break;
            }
        }
        Slog.i(TAG, "K/V backup pass finished.");
        // Only once we're entirely finished do we release the wakelock for k/v backup.
        mBackupManagerService.getWakelock().release();
    }

    // Remove the PM metadata state. This will generate an init on the next pass.
    private void clearMetadata() {
        final File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
        if (pmState.exists()) pmState.delete();
    }

    /**
     * Returns a {@link Pair}. The first of the pair contains the status. In case the status is
     * {@link BackupTransport#TRANSPORT_OK}, the second of the pair contains the agent result,
     * otherwise {@code null}.
     */
    private Pair<Integer, RemoteResult> invokeAgentForBackup(
            String packageName, IBackupAgent agent) {
        if (DEBUG) {
            Slog.d(TAG, "invokeAgentForBackup on " + packageName);
        }
        mBackupManagerService.addBackupTrace("invoking " + packageName);

        File blankStateFile = new File(mStateDir, BLANK_STATE_FILE_NAME);
        mSavedStateFile = new File(mStateDir, packageName);
        mBackupDataFile =
                new File(mBackupManagerService.getDataDir(), packageName + STAGING_FILE_SUFFIX);
        mNewStateFile = new File(mStateDir, packageName + NEW_STATE_FILE_SUFFIX);
        if (MORE_DEBUG) {
            Slog.d(TAG, "data file: " + mBackupDataFile);
        }


        mSavedState = null;
        mBackupData = null;
        mNewState = null;

        boolean callingAgent = false;
        final RemoteResult agentResult;
        try {
            // Look up the package info & signatures.  This is first so that if it
            // throws an exception, there's no file setup yet that would need to
            // be unraveled.
            if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                // The metadata 'package' is synthetic; construct one and make
                // sure our global state is pointed at it
                mCurrentPackage = new PackageInfo();
                mCurrentPackage.packageName = packageName;
            }

            mSavedState = ParcelFileDescriptor.open(
                    (mNonIncremental) ? blankStateFile : mSavedStateFile,
                    ParcelFileDescriptor.MODE_READ_ONLY |
                            ParcelFileDescriptor.MODE_CREATE);  // Make an empty file if necessary

            mBackupData = ParcelFileDescriptor.open(mBackupDataFile,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            if (!SELinux.restorecon(mBackupDataFile)) {
                Slog.e(TAG, "SELinux restorecon failed on " + mBackupDataFile);
            }

            mNewState = ParcelFileDescriptor.open(mNewStateFile,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.invokeAgentForBackup()");

            final long quota = transport.getBackupQuota(packageName, false /* isFullBackup */);
            callingAgent = true;

            // Initiate the target's backup pass
            long kvBackupAgentTimeoutMillis =
                    mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();
            mBackupManagerService.addBackupTrace("calling agent doBackup()");

            agentResult =
                    remoteCall(
                            callback ->
                                    agent.doBackup(
                                            mSavedState,
                                            mBackupData,
                                            mNewState,
                                            quota,
                                            callback,
                                            transport.getTransportFlags()),
                            kvBackupAgentTimeoutMillis);
        } catch (Exception e) {
            Slog.e(TAG, "Error invoking for backup on " + packageName + ". " + e);
            mBackupManagerService.addBackupTrace("exception: " + e);
            EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName, e.toString());
            errorCleanup();
            int status =
                    callingAgent ? BackupTransport.AGENT_ERROR : BackupTransport.TRANSPORT_ERROR;
            return Pair.create(status, null);
        } finally {
            if (mNonIncremental) {
                blankStateFile.delete();
            }
        }

        return Pair.create(BackupTransport.TRANSPORT_OK, agentResult);
    }

    private void failAgent(IBackupAgent agent, String message) {
        try {
            agent.fail(message);
        } catch (Exception e) {
            Slog.w(TAG, "Error conveying failure to " + mCurrentPackage.packageName);
        }
    }

    // SHA-1 a byte array and return the result in hex
    private String SHA1Checksum(byte[] input) {
        final byte[] checksum;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            checksum = md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "Unable to use SHA-1!");
            return "00";
        }

        StringBuffer sb = new StringBuffer(checksum.length * 2);
        for (int i = 0; i < checksum.length; i++) {
            sb.append(Integer.toHexString(checksum[i]));
        }
        return sb.toString();
    }

    private void writeWidgetPayloadIfAppropriate(FileDescriptor fd, String pkgName)
            throws IOException {
        // TODO: http://b/22388012
        byte[] widgetState = AppWidgetBackupBridge.getWidgetState(pkgName,
                UserHandle.USER_SYSTEM);
        // has the widget state changed since last time?
        final File widgetFile = new File(mStateDir, pkgName + "_widget");
        final boolean priorStateExists = widgetFile.exists();

        if (MORE_DEBUG) {
            if (priorStateExists || widgetState != null) {
                Slog.i(TAG, "Checking widget update: state=" + (widgetState != null)
                        + " prior=" + priorStateExists);
            }
        }

        if (!priorStateExists && widgetState == null) {
            // no prior state, no new state => nothing to do
            return;
        }

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

    private BackupState handleAgentResult(long unusedResult) {
        Preconditions.checkState(mBackupData != null);

        final String pkgName = mCurrentPackage.packageName;
        final long filepos = mBackupDataFile.length();
        FileDescriptor fd = mBackupData.getFileDescriptor();
        try {
            // If it's a 3rd party app, see whether they wrote any protected keys
            // and complain mightily if they are attempting shenanigans.
            if (mCurrentPackage.applicationInfo != null &&
                    (mCurrentPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                            == 0) {
                ParcelFileDescriptor readFd = ParcelFileDescriptor.open(mBackupDataFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                BackupDataInput in = new BackupDataInput(readFd.getFileDescriptor());
                try {
                    while (in.readNextHeader()) {
                        final String key = in.getKey();
                        if (key != null && key.charAt(0) >= 0xff00) {
                            // Not okay: crash them and bail.
                            failAgent(mAgentBinder, "Illegal backup key: " + key);
                            mBackupManagerService
                                    .addBackupTrace("illegal key " + key + " from " + pkgName);
                            EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, pkgName,
                                    "bad key");
                            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                                    BackupManagerMonitor.LOG_EVENT_ID_ILLEGAL_KEY,
                                    mCurrentPackage,
                                    BackupManagerMonitor
                                            .LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                    BackupManagerMonitorUtils.putMonitoringExtra(null,
                                            BackupManagerMonitor.EXTRA_LOG_ILLEGAL_KEY,
                                            key));
                            BackupObserverUtils
                                    .sendBackupOnPackageResult(mObserver, pkgName,
                                            BackupManager.ERROR_AGENT_FAILURE);
                            errorCleanup();
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Agent failure for " + pkgName
                                        + " with illegal key: " + key + "; dropped");
                            }

                            return BackupState.RUNNING_QUEUE;
                        }
                        in.skipEntityData();
                    }
                } finally {
                    if (readFd != null) {
                        readFd.close();
                    }
                }
            }

            // Piggyback the widget state payload, if any
            writeWidgetPayloadIfAppropriate(fd, pkgName);
        } catch (IOException e) {
            // Hard disk error; recovery/failure policy TBD.  For now roll back,
            // but we may want to consider this a transport-level failure (i.e.
            // we're in such a bad state that we can't contemplate doing backup
            // operations any more during this pass).
            Slog.w(TAG, "Unable read backup data or to save widget state for " + pkgName);
            try {
                Os.ftruncate(fd, filepos);
            } catch (ErrnoException ee) {
                Slog.w(TAG, "Unable to roll back!");
            }
        }

        clearAgentState();
        mBackupManagerService.addBackupTrace("operation complete");

        ParcelFileDescriptor backupData = null;
        mStatus = BackupTransport.TRANSPORT_OK;
        long size = 0;
        try {
            IBackupTransport transport = mTransportClient.connectOrThrow("KVBT.handleAgentResult()");
            size = mBackupDataFile.length();
            if (size > 0) {
                if (MORE_DEBUG) {
                    Slog.v(TAG, "Sending non-empty data to transport for " + pkgName);
                }
                boolean isNonIncremental = mSavedStateFile.length() == 0;
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    backupData = ParcelFileDescriptor.open(mBackupDataFile,
                            ParcelFileDescriptor.MODE_READ_ONLY);
                    mBackupManagerService.addBackupTrace("sending data to transport");

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
                    // TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED is only valid if the backup was
                    // incremental, as if the backup is non-incremental there is no state to
                    // clear. This avoids us ending up in a retry loop if the transport always
                    // returns this code.
                    Slog.w(TAG, "Transport requested non-incremental but already the case, error");
                    mBackupManagerService.addBackupTrace(
                            "Transport requested non-incremental but already the case, error");
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                }

                mBackupManagerService.addBackupTrace("data delivered: " + mStatus);
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    mBackupManagerService.addBackupTrace("finishing op on transport");
                    mStatus = transport.finishBackup();
                    mBackupManagerService.addBackupTrace("finished: " + mStatus);
                } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                    mBackupManagerService.addBackupTrace("transport rejected package");
                }
            } else {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "No backup data written; not calling transport");
                }
                mBackupManagerService.addBackupTrace("no data to send");
                mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_NO_DATA_TO_SEND,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        null);
            }

            if (mStatus == BackupTransport.TRANSPORT_OK) {
                // After successful transport, delete the now-stale data
                // and juggle the files so that next time we supply the agent
                // with the new state file it just created.
                mBackupDataFile.delete();
                mNewStateFile.renameTo(mSavedStateFile);
                BackupObserverUtils.sendBackupOnPackageResult(
                        mObserver, pkgName, BackupManager.SUCCESS);
                EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE, pkgName, size);
                mBackupManagerService.logBackupComplete(pkgName);
            } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                // The transport has rejected backup of this specific package.  Roll it
                // back but proceed with running the rest of the queue.
                mBackupDataFile.delete();
                mNewStateFile.delete();
                BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                        BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
                EventLogTags.writeBackupAgentFailure(pkgName, "Transport rejected");
            } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                        BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
                EventLog.writeEvent(EventLogTags.BACKUP_QUOTA_EXCEEDED, pkgName);

            } else if (mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
                Slog.i(TAG, "Transport lost data, retrying package");
                mBackupManagerService.addBackupTrace(
                        "Transport lost data, retrying package:" + pkgName);
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                        /*extras=*/ null);

                mBackupDataFile.delete();
                mSavedStateFile.delete();
                mNewStateFile.delete();

                // Immediately retry the package by adding it back to the front of the queue.
                // We cannot add @pm@ to the queue because we back it up separately at the start
                // of the backup pass in state BACKUP_PM. Instead we retry this state (see
                // below).
                if (!PACKAGE_MANAGER_SENTINEL.equals(pkgName)) {
                    mQueue.add(0, new BackupRequest(pkgName));
                }

            } else {
                // Actual transport-level failure to communicate the data to the backend
                BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                        BackupManager.ERROR_TRANSPORT_ABORTED);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, pkgName);
            }
        } catch (Exception e) {
            BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                    BackupManager.ERROR_TRANSPORT_ABORTED);
            Slog.e(TAG, "Transport error backing up " + pkgName, e);
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, pkgName);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        } finally {
            try {
                if (backupData != null) {
                    backupData.close();
                }
            } catch (IOException e) {
                Slog.w(TAG, "Error closing backup data fd");
            }
        }

        final BackupState nextState;
        if (mStatus == BackupTransport.TRANSPORT_OK
                || mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
            // Success or single-package rejection.  Proceed with the next app if any,
            // otherwise we're done.
            nextState = BackupState.RUNNING_QUEUE;

        } else if (mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
            // We want to immediately retry the current package.
            if (PACKAGE_MANAGER_SENTINEL.equals(pkgName)) {
                nextState = BackupState.BACKUP_PM;
            } else {
                // This is an ordinary package so we will have added it back into the queue
                // above. Thus, we proceed processing the queue.
                nextState = BackupState.RUNNING_QUEUE;
            }

        } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
            if (MORE_DEBUG) {
                Slog.d(TAG, "Package " + mCurrentPackage.packageName +
                        " hit quota limit on k/v backup");
            }
            if (mAgentBinder != null) {
                try {
                    IBackupTransport transport =
                            mTransportClient.connectOrThrow("KVBT.handleAgentResult()");
                    long quota = transport.getBackupQuota(mCurrentPackage.packageName, false);
                    mAgentBinder.doQuotaExceeded(size, quota);
                } catch (Exception e) {
                    Slog.e(TAG, "Unable to notify about quota exceeded: " + e.getMessage());
                }
            }
            nextState = BackupState.RUNNING_QUEUE;
        } else {
            // Any other error here indicates a transport-level failure.  That means
            // we need to halt everything and reschedule everything for next time.
            revertAndEndBackup();
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
        Preconditions.checkArgument(cancelAll, "Can't partially cancel a key-value backup task");
        if (MORE_DEBUG) {
            Slog.v(TAG, "Cancel received");
        }
        mCancelled = true;
        RemoteCall pendingCall = mPendingCall;
        if (pendingCall != null) {
            pendingCall.cancel();
        }
        mCancelAcknowledged.block();
    }

    private void handleAgentTimeout() {
        String packageName = getPackageNameForLog();
        Slog.i(TAG, "Agent " + packageName + " timed out");
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName);
        mBackupManagerService.addBackupTrace("timeout of " + packageName);
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                        BackupManagerMonitorUtils.putMonitoringExtra(
                                null, BackupManagerMonitor.EXTRA_LOG_CANCEL_ALL, false));
        errorCleanup();
    }

    private void handleAgentCancelled() {
        String packageName = getPackageNameForLog();
        Slog.i(TAG, "Cancel backing up " + packageName);
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName);
        mBackupManagerService.addBackupTrace("cancel of " + packageName);
        errorCleanup();
    }

    private void finalizeCancelledBackup() {
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                        BackupManagerMonitorUtils.putMonitoringExtra(
                                null, BackupManagerMonitor.EXTRA_LOG_CANCEL_ALL, true));
        finalizeBackup();
        // finalizeBackup() may call the transport, so we only acknowledge the cancellation here.
        mCancelAcknowledged.open();
    }

    private String getPackageNameForLog() {
        return (mCurrentPackage != null) ? mCurrentPackage.packageName : "no_package_yet";
    }

    private void revertAndEndBackup() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Reverting backup queue - restaging everything");
        }
        mBackupManagerService.addBackupTrace("transport error; reverting");

        // We want to reset the backup schedule based on whatever the transport suggests
        // by way of retry/backoff time.
        long delay;
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("KVBT.revertAndEndBackup()");
            delay = transport.requestBackupTime();
        } catch (Exception e) {
            Slog.w(TAG, "Unable to contact transport for recommended backoff: " + e.getMessage());
            delay = 0;  // use the scheduler's default
        }
        KeyValueBackupJob.schedule(mBackupManagerService.getContext(), delay,
                mBackupManagerService.getConstants());

        for (BackupRequest request : mOriginalQueue) {
            mBackupManagerService.dataChangedImpl(request.packageName);
        }
    }

    private void errorCleanup() {
        mBackupDataFile.delete();
        mNewStateFile.delete();
        clearAgentState();
    }

    // Cleanup common to both success and failure cases
    private void clearAgentState() {
        try {
            if (mSavedState != null) {
                mSavedState.close();
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error closing old state fd");
        }
        try {
            if (mBackupData != null) {
                mBackupData.close();
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error closing backup data fd");
        }
        try {
            if (mNewState != null) {
                mNewState.close();
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error closing new state fd");
        }
        synchronized (mBackupManagerService.getCurrentOpLock()) {
            // Current-operation callback handling requires the validity of these various
            // bits of internal state as an invariant of the operation still being live.
            // This means we make sure to clear all of the state in unison inside the lock.
            mSavedState = mBackupData = mNewState = null;
        }

        // If this was a pseudo-package there's no associated Activity Manager state
        if (mCurrentPackage.applicationInfo != null) {
            mBackupManagerService.addBackupTrace("unbinding " + mCurrentPackage.packageName);
            mBackupManagerService.unbindAgent(mCurrentPackage.applicationInfo);
        }
    }

    private RemoteResult remoteCall(RemoteCallable<IBackupCallback> remoteCallable, long timeoutMs)
            throws RemoteException {
        mPendingCall = new RemoteCall(mCancelled, remoteCallable, timeoutMs);
        RemoteResult result = mPendingCall.call();
        if (MORE_DEBUG) {
            Slog.v(TAG, "Agent call returned " + result);
        }
        mPendingCall = null;
        return result;
    }
}
