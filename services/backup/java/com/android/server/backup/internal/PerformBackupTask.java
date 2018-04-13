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
import static com.android.server.backup.BackupManagerService.DEBUG_BACKUP_TRACE;
import static com.android.server.backup.BackupManagerService.KEY_WIDGET_STATE;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.OP_PENDING;
import static com.android.server.backup.BackupManagerService.OP_TYPE_BACKUP;
import static com.android.server.backup.BackupManagerService.OP_TYPE_BACKUP_WAIT;
import static com.android.server.backup.BackupManagerService.PACKAGE_MANAGER_SENTINEL;
import static com.android.server.backup.internal.BackupHandler.MSG_BACKUP_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_BACKUP_RESTORE_STEP;

import android.annotation.Nullable;
import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.WorkSource;
import android.system.ErrnoException;
import android.system.Os;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportUtils;
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

/**
 * This class handles the process of backing up a given list of key/value backup packages.
 * Also takes in a list of pending dolly backups and kicks them off when key/value backups
 * are done.
 *
 * Flow:
 * If required, backup @pm@.
 * For each pending key/value backup package:
 *     - Bind to agent.
 *     - Call agent.doBackup()
 *     - Wait either for cancel/timeout or operationComplete() callback from the agent.
 * Start task to perform dolly backups.
 *
 * There are three entry points into this class:
 *     - execute() [Called from the handler thread]
 *     - operationComplete(long result) [Called from the handler thread]
 *     - handleCancel(boolean cancelAll) [Can be called from any thread]
 * These methods synchronize on mCancelLock.
 *
 * Interaction with mCurrentOperations:
 *     - An entry for this task is put into mCurrentOperations for the entire lifetime of the
 *       task. This is useful to cancel the task if required.
 *     - An ephemeral entry is put into mCurrentOperations each time we are waiting on for
 *       response from a backup agent. This is used to plumb timeouts and completion callbacks.
 */
public class PerformBackupTask implements BackupRestoreTask {
    private static final String TAG = "PerformBackupTask";

    private BackupManagerService backupManagerService;
    private final Object mCancelLock = new Object();

    private ArrayList<BackupRequest> mQueue;
    private ArrayList<BackupRequest> mOriginalQueue;
    private File mStateDir;
    @Nullable private DataChangedJournal mJournal;
    private BackupState mCurrentState;
    private List<String> mPendingFullBackups;
    private IBackupObserver mObserver;
    private IBackupManagerMonitor mMonitor;

    private final TransportClient mTransportClient;
    private final OnTaskFinishedListener mListener;
    private final PerformFullTransportBackupTask mFullBackupTask;
    private final int mCurrentOpToken;
    private volatile int mEphemeralOpToken;

    // carried information about the current in-flight operation
    private IBackupAgent mAgentBinder;
    private PackageInfo mCurrentPackage;
    private File mSavedStateName;
    private File mBackupDataName;
    private File mNewStateName;
    private ParcelFileDescriptor mSavedState;
    private ParcelFileDescriptor mBackupData;
    private ParcelFileDescriptor mNewState;
    private int mStatus;
    private boolean mFinished;
    private final boolean mUserInitiated;
    private final boolean mNonIncremental;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    private volatile boolean mCancelAll;

    public PerformBackupTask(BackupManagerService backupManagerService,
            TransportClient transportClient, String dirName,
            ArrayList<BackupRequest> queue, @Nullable DataChangedJournal journal,
            IBackupObserver observer, IBackupManagerMonitor monitor,
            @Nullable OnTaskFinishedListener listener, List<String> pendingFullBackups,
            boolean userInitiated, boolean nonIncremental) {
        this.backupManagerService = backupManagerService;
        mTransportClient = transportClient;
        mOriginalQueue = queue;
        mQueue = new ArrayList<>();
        mJournal = journal;
        mObserver = observer;
        mMonitor = monitor;
        mListener = (listener != null) ? listener : OnTaskFinishedListener.NOP;
        mPendingFullBackups = pendingFullBackups;
        mUserInitiated = userInitiated;
        mNonIncremental = nonIncremental;
        mAgentTimeoutParameters = Preconditions.checkNotNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");

        mStateDir = new File(backupManagerService.getBaseStateDir(), dirName);
        mCurrentOpToken = backupManagerService.generateRandomIntegerToken();

        mFinished = false;

        synchronized (backupManagerService.getCurrentOpLock()) {
            if (backupManagerService.isBackupOperationInProgress()) {
                if (DEBUG) {
                    Slog.d(TAG, "Skipping backup since one is already in progress.");
                }
                mCancelAll = true;
                mFullBackupTask = null;
                mCurrentState = BackupState.FINAL;
                backupManagerService.addBackupTrace("Skipped. Backup already in progress.");
            } else {
                mCurrentState = BackupState.INITIAL;
                CountDownLatch latch = new CountDownLatch(1);
                String[] fullBackups =
                        mPendingFullBackups.toArray(new String[mPendingFullBackups.size()]);
                mFullBackupTask =
                        new PerformFullTransportBackupTask(backupManagerService,
                                transportClient,
                                /*fullBackupRestoreObserver*/ null,
                                fullBackups, /*updateSchedule*/ false, /*runningJob*/ null,
                                latch,
                                mObserver, mMonitor, mListener, mUserInitiated);

                registerTask();
                backupManagerService.addBackupTrace("STATE => INITIAL");
            }
        }
    }

    /**
     * Put this task in the repository of running tasks.
     */
    private void registerTask() {
        synchronized (backupManagerService.getCurrentOpLock()) {
            backupManagerService.getCurrentOperations().put(
                    mCurrentOpToken, new Operation(OP_PENDING, this, OP_TYPE_BACKUP));
        }
    }

    /**
     * Remove this task from repository of running tasks.
     */
    private void unregisterTask() {
        backupManagerService.removeOperation(mCurrentOpToken);
    }

    // Main entry point: perform one chunk of work, updating the state as appropriate
    // and reposting the next chunk to the primary backup handler thread.
    @Override
    @GuardedBy("mCancelLock")
    public void execute() {
        synchronized (mCancelLock) {
            switch (mCurrentState) {
                case INITIAL:
                    beginBackup();
                    break;

                case BACKUP_PM:
                    backupPm();
                    break;

                case RUNNING_QUEUE:
                    invokeNextAgent();
                    break;

                case FINAL:
                    if (!mFinished) {
                        finalizeBackup();
                    } else {
                        Slog.e(TAG, "Duplicate finish of K/V pass");
                    }
                    break;
            }
        }
    }

    // We're starting a backup pass.  Initialize the transport if we haven't already.
    private void beginBackup() {
        if (DEBUG_BACKUP_TRACE) {
            backupManagerService.clearBackupTrace();
            StringBuilder b = new StringBuilder(256);
            b.append("beginBackup: [");
            for (BackupRequest req : mOriginalQueue) {
                b.append(' ');
                b.append(req.packageName);
            }
            b.append(" ]");
            backupManagerService.addBackupTrace(b.toString());
        }

        mAgentBinder = null;
        mStatus = BackupTransport.TRANSPORT_OK;

        // Sanity check: if the queue is empty we have no work to do.
        if (mOriginalQueue.isEmpty() && mPendingFullBackups.isEmpty()) {
            Slog.w(TAG, "Backup begun with an empty queue - nothing to do.");
            backupManagerService.addBackupTrace("queue empty at begin");
            BackupObserverUtils.sendBackupFinished(mObserver, BackupManager.SUCCESS);
            executeNextState(BackupState.FINAL);
            return;
        }

        // We need to retain the original queue contents in case of transport
        // failure, but we want a working copy that we can manipulate along
        // the way.
        mQueue = (ArrayList<BackupRequest>) mOriginalQueue.clone();

        // When the transport is forcing non-incremental key/value payloads, we send the
        // metadata only if it explicitly asks for it.
        boolean skipPm = mNonIncremental;

        // The app metadata pseudopackage might also be represented in the
        // backup queue if apps have been added/removed since the last time
        // we performed a backup.  Drop it from the working queue now that
        // we're committed to evaluating it for backup regardless.
        for (int i = 0; i < mQueue.size(); i++) {
            if (PACKAGE_MANAGER_SENTINEL.equals(
                    mQueue.get(i).packageName)) {
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
            IBackupTransport transport = mTransportClient.connectOrThrow("PBT.beginBackup()");
            final String transportName = transport.transportDirName();
            EventLog.writeEvent(EventLogTags.BACKUP_START, transportName);

            // If we haven't stored package manager metadata yet, we must init the transport.
            if (mStatus == BackupTransport.TRANSPORT_OK && pmState.length() <= 0) {
                Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                backupManagerService.addBackupTrace("initializing transport " + transportName);
                backupManagerService.resetBackupState(mStateDir);  // Just to make sure.
                mStatus = transport.initializeDevice();

                backupManagerService.addBackupTrace("transport.initializeDevice() == " + mStatus);
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                } else {
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                    Slog.e(TAG, "Transport error in initializeDevice()");
                }
            }

            if (skipPm) {
                Slog.d(TAG, "Skipping backup of package metadata.");
                executeNextState(BackupState.RUNNING_QUEUE);
            } else {
                // As the package manager is running here in the system process we can just set up
                // its agent directly. Thus we always run this pass because it's cheap and this way
                // we guarantee that we don't get out of step even if we're selecting among various
                // transports at run time.
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    executeNextState(BackupState.BACKUP_PM);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error in backup thread during init", e);
            backupManagerService.addBackupTrace("Exception in backup thread during init: " + e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        } finally {
            // If we've succeeded so far, we will move to the BACKUP_PM state. If something has gone
            // wrong then that won't have happen so cleanup.
            backupManagerService.addBackupTrace("exiting prelim: " + mStatus);
            if (mStatus != BackupTransport.TRANSPORT_OK) {
                // if things went wrong at this point, we need to
                // restage everything and try again later.
                backupManagerService.resetBackupState(mStateDir);  // Just to make sure.
                // In case of any other error, it's backup transport error.
                BackupObserverUtils.sendBackupFinished(mObserver,
                        BackupManager.ERROR_TRANSPORT_ABORTED);
                executeNextState(BackupState.FINAL);
            }
        }
    }

    private void backupPm() {
        try {
            // The package manager doesn't have a proper <application> etc, but since it's running
            // here in the system process we can just set up its agent directly and use a synthetic
            // BackupRequest.
            PackageManagerBackupAgent pmAgent = backupManagerService.makeMetadataAgent();
            mStatus = invokeAgentForBackup(
                    PACKAGE_MANAGER_SENTINEL,
                    IBackupAgent.Stub.asInterface(pmAgent.onBind()));
            backupManagerService.addBackupTrace("PMBA invoke: " + mStatus);

            // Because the PMBA is a local instance, it has already executed its backup callback and
            // returned.  Blow away the lingering (spurious) pending timeout message for it.
            backupManagerService.getBackupHandler().removeMessages(
                    MSG_BACKUP_OPERATION_TIMEOUT);
        } catch (Exception e) {
            Slog.e(TAG, "Error in backup thread during pm", e);
            backupManagerService.addBackupTrace("Exception in backup thread during pm: " + e);
            mStatus = BackupTransport.TRANSPORT_ERROR;
        } finally {
            // If we've succeeded so far, invokeAgentForBackup() will have run the PM
            // metadata and its completion/timeout callback will continue the state
            // machine chain.  If it failed that won't happen; we handle that now.
            backupManagerService.addBackupTrace("exiting backupPm: " + mStatus);
            if (mStatus != BackupTransport.TRANSPORT_OK) {
                // if things went wrong at this point, we need to
                // restage everything and try again later.
                backupManagerService.resetBackupState(mStateDir);  // Just to make sure.
                BackupObserverUtils.sendBackupFinished(mObserver,
                        invokeAgentToObserverError(mStatus));
                executeNextState(BackupState.FINAL);
            }
        }
    }

    private int invokeAgentToObserverError(int error) {
        if (error == BackupTransport.AGENT_ERROR) {
            return BackupManager.ERROR_AGENT_FAILURE;
        } else {
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }
    }

    // Transport has been initialized and the PM metadata submitted successfully
    // if that was warranted.  Now we process the single next thing in the queue.
    private void invokeNextAgent() {
        mStatus = BackupTransport.TRANSPORT_OK;
        backupManagerService.addBackupTrace("invoke q=" + mQueue.size());

        // Sanity check that we have work to do.  If not, skip to the end where
        // we reestablish the wakelock invariants etc.
        if (mQueue.isEmpty()) {
            if (MORE_DEBUG) Slog.i(TAG, "queue now empty");
            executeNextState(BackupState.FINAL);
            return;
        }

        // pop the entry we're going to process on this step
        BackupRequest request = mQueue.get(0);
        mQueue.remove(0);

        Slog.d(TAG, "starting key/value backup of " + request);
        backupManagerService.addBackupTrace("launch agent for " + request.packageName);

        // Verify that the requested app exists; it might be something that
        // requested a backup but was then uninstalled.  The request was
        // journalled and rather than tamper with the journal it's safer
        // to sanity-check here.  This also gives us the classname of the
        // package's backup agent.
        try {
            PackageManager pm = backupManagerService.getPackageManager();
            mCurrentPackage = pm.getPackageInfo(request.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (!AppBackupUtils.appIsEligibleForBackup(mCurrentPackage.applicationInfo, pm)) {
                // The manifest has changed but we had a stale backup request pending.
                // This won't happen again because the app won't be requesting further
                // backups.
                Slog.i(TAG, "Package " + request.packageName
                        + " no longer supports backup; skipping");
                backupManagerService.addBackupTrace("skipping - not eligible, completion is noop");
                // Shouldn't happen in case of requested backup, as pre-check was done in
                // #requestBackup(), except to app update done concurrently
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                executeNextState(BackupState.RUNNING_QUEUE);
                return;
            }

            if (AppBackupUtils.appGetsFullBackup(mCurrentPackage)) {
                // It's possible that this app *formerly* was enqueued for key/value backup,
                // but has since been updated and now only supports the full-data path.
                // Don't proceed with a key/value backup for it in this case.
                Slog.i(TAG, "Package " + request.packageName
                        + " requests full-data rather than key/value; skipping");
                backupManagerService.addBackupTrace(
                        "skipping - fullBackupOnly, completion is noop");
                // Shouldn't happen in case of requested backup, as pre-check was done in
                // #requestBackup()
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                executeNextState(BackupState.RUNNING_QUEUE);
                return;
            }

            if (AppBackupUtils.appIsStopped(mCurrentPackage.applicationInfo)) {
                // The app has been force-stopped or cleared or just installed,
                // and not yet launched out of that state, so just as it won't
                // receive broadcasts, we won't run it for backup.
                backupManagerService.addBackupTrace("skipping - stopped");
                BackupObserverUtils.sendBackupOnPackageResult(mObserver,
                        mCurrentPackage.packageName,
                        BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                executeNextState(BackupState.RUNNING_QUEUE);
                return;
            }

            IBackupAgent agent = null;
            try {
                backupManagerService.getWakelock().setWorkSource(
                        new WorkSource(mCurrentPackage.applicationInfo.uid));
                agent = backupManagerService.bindToAgentSynchronous(mCurrentPackage.applicationInfo,
                        ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);
                backupManagerService.addBackupTrace("agent bound; a? = " + (agent != null));
                if (agent != null) {
                    mAgentBinder = agent;
                    mStatus = invokeAgentForBackup(request.packageName, agent);
                    // at this point we'll either get a completion callback from the
                    // agent, or a timeout message on the main handler.  either way, we're
                    // done here as long as we're successful so far.
                } else {
                    // Timeout waiting for the agent
                    mStatus = BackupTransport.AGENT_ERROR;
                }
            } catch (SecurityException ex) {
                // Try for the next one.
                Slog.d(TAG, "error in bind/backup", ex);
                mStatus = BackupTransport.AGENT_ERROR;
                backupManagerService.addBackupTrace("agent SE");
            }
        } catch (NameNotFoundException e) {
            Slog.d(TAG, "Package does not exist; skipping");
            backupManagerService.addBackupTrace("no such package");
            mStatus = BackupTransport.AGENT_UNKNOWN;
        } finally {
            backupManagerService.getWakelock().setWorkSource(null);

            // If there was an agent error, no timeout/completion handling will occur.
            // That means we need to direct to the next state ourselves.
            if (mStatus != BackupTransport.TRANSPORT_OK) {
                BackupState nextState = BackupState.RUNNING_QUEUE;
                mAgentBinder = null;

                // An agent-level failure means we reenqueue this one agent for
                // a later retry, but otherwise proceed normally.
                if (mStatus == BackupTransport.AGENT_ERROR) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Agent failure for " + request.packageName
                                + " - restaging");
                    }
                    backupManagerService.dataChangedImpl(request.packageName);
                    mStatus = BackupTransport.TRANSPORT_OK;
                    if (mQueue.isEmpty()) nextState = BackupState.FINAL;
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
                    // Transport-level failure means we reenqueue everything
                    revertAndEndBackup();
                    nextState = BackupState.FINAL;
                }

                executeNextState(nextState);
            } else {
                // success case
                backupManagerService.addBackupTrace("expecting completion/timeout callback");
            }
        }
    }

    private void finalizeBackup() {
        backupManagerService.addBackupTrace("finishing");

        // Mark packages that we didn't backup (because backup was cancelled, etc.) as needing
        // backup.
        for (BackupRequest req : mQueue) {
            backupManagerService.dataChangedImpl(req.packageName);
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
        String callerLogString = "PBT.finalizeBackup()";
        if ((backupManagerService.getCurrentToken() == 0) && (mStatus
                == BackupTransport.TRANSPORT_OK)) {
            backupManagerService.addBackupTrace("success; recording token");
            try {
                IBackupTransport transport =
                        mTransportClient.connectOrThrow(callerLogString);
                backupManagerService.setCurrentToken(transport.getCurrentRestoreSet());
                backupManagerService.writeRestoreTokens();
            } catch (Exception e) {
                // nothing for it at this point, unfortunately, but this will be
                // recorded the next time we fully succeed.
                Slog.e(TAG, "Transport threw reporting restore set: " + e.getMessage());
                backupManagerService.addBackupTrace("transport threw returning token");
            }
        }

        // Set up the next backup pass - at this point we can set mBackupRunning
        // to false to allow another pass to fire, because we're done with the
        // state machine sequence and the wakelock is refcounted.
        synchronized (backupManagerService.getQueueLock()) {
            backupManagerService.setBackupRunning(false);
            if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                // Make sure we back up everything and perform the one-time init
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Server requires init; rerunning");
                }
                backupManagerService.addBackupTrace("init required; rerunning");
                try {
                    String name = backupManagerService.getTransportManager()
                            .getTransportName(mTransportClient.getTransportComponent());
                    backupManagerService.getPendingInits().add(name);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to query transport name for init: " + e.getMessage());
                    // swallow it and proceed; we don't rely on this
                }
                clearMetadata();
                backupManagerService.backupNow();
            }
        }

        backupManagerService.clearBackupTrace();

        unregisterTask();

        if (!mCancelAll && mStatus == BackupTransport.TRANSPORT_OK &&
                mPendingFullBackups != null && !mPendingFullBackups.isEmpty()) {
            Slog.d(TAG, "Starting full backups for: " + mPendingFullBackups);
            // Acquiring wakelock for PerformFullTransportBackupTask before its start.
            backupManagerService.getWakelock().acquire();
            // The full-backup task is now responsible for calling onFinish() on mListener, which
            // was the listener we passed it.
            (new Thread(mFullBackupTask, "full-transport-requested")).start();
        } else if (mCancelAll) {
            mListener.onFinished(callerLogString);
            if (mFullBackupTask != null) {
                mFullBackupTask.unregisterTask();
            }
            BackupObserverUtils.sendBackupFinished(mObserver,
                    BackupManager.ERROR_BACKUP_CANCELLED);
        } else {
            mListener.onFinished(callerLogString);
            mFullBackupTask.unregisterTask();
            switch (mStatus) {
                case BackupTransport.TRANSPORT_OK:
                    BackupObserverUtils.sendBackupFinished(mObserver,
                            BackupManager.SUCCESS);
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
        mFinished = true;
        Slog.i(TAG, "K/V backup pass finished.");
        // Only once we're entirely finished do we release the wakelock for k/v backup.
        backupManagerService.getWakelock().release();
    }

    // Remove the PM metadata state. This will generate an init on the next pass.
    private void clearMetadata() {
        final File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
        if (pmState.exists()) pmState.delete();
    }

    // Invoke an agent's doBackup() and start a timeout message spinning on the main
    // handler in case it doesn't get back to us.
    private int invokeAgentForBackup(String packageName, IBackupAgent agent) {
        if (DEBUG) {
            Slog.d(TAG, "invokeAgentForBackup on " + packageName);
        }
        backupManagerService.addBackupTrace("invoking " + packageName);

        File blankStateName = new File(mStateDir, "blank_state");
        mSavedStateName = new File(mStateDir, packageName);
        mBackupDataName = new File(backupManagerService.getDataDir(), packageName + ".data");
        mNewStateName = new File(mStateDir, packageName + ".new");
        if (MORE_DEBUG) Slog.d(TAG, "data file: " + mBackupDataName);

        mSavedState = null;
        mBackupData = null;
        mNewState = null;

        boolean callingAgent = false;
        mEphemeralOpToken = backupManagerService.generateRandomIntegerToken();
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

            // In a full backup, we pass a null ParcelFileDescriptor as
            // the saved-state "file". For key/value backups we pass the old state if
            // an incremental backup is required, and a blank state otherwise.
            mSavedState = ParcelFileDescriptor.open(
                    mNonIncremental ? blankStateName : mSavedStateName,
                    ParcelFileDescriptor.MODE_READ_ONLY |
                            ParcelFileDescriptor.MODE_CREATE);  // Make an empty file if necessary

            mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            if (!SELinux.restorecon(mBackupDataName)) {
                Slog.e(TAG, "SELinux restorecon failed on " + mBackupDataName);
            }

            mNewState = ParcelFileDescriptor.open(mNewStateName,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            IBackupTransport transport =
                    mTransportClient.connectOrThrow("PBT.invokeAgentForBackup()");

            final long quota = transport.getBackupQuota(packageName, false /* isFullBackup */);
            callingAgent = true;

            // Initiate the target's backup pass
            backupManagerService.addBackupTrace("setting timeout");
            long kvBackupAgentTimeoutMillis =
                    mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();
            backupManagerService.prepareOperationTimeout(
                    mEphemeralOpToken, kvBackupAgentTimeoutMillis, this, OP_TYPE_BACKUP_WAIT);
            backupManagerService.addBackupTrace("calling agent doBackup()");

            agent.doBackup(
                    mSavedState, mBackupData, mNewState, quota, mEphemeralOpToken,
                    backupManagerService.getBackupManagerBinder(), transport.getTransportFlags());
        } catch (Exception e) {
            Slog.e(TAG, "Error invoking for backup on " + packageName + ". " + e);
            backupManagerService.addBackupTrace("exception: " + e);
            EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName,
                    e.toString());
            errorCleanup();
            return callingAgent ? BackupTransport.AGENT_ERROR
                    : BackupTransport.TRANSPORT_ERROR;
        } finally {
            if (mNonIncremental) {
                blankStateName.delete();
            }
        }

        // At this point the agent is off and running.  The next thing to happen will
        // either be a callback from the agent, at which point we'll process its data
        // for transport, or a timeout.  Either way the next phase will happen in
        // response to the TimeoutHandler interface callbacks.
        backupManagerService.addBackupTrace("invoke success");
        return BackupTransport.TRANSPORT_OK;
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

    @Override
    @GuardedBy("mCancelLock")
    public void operationComplete(long unusedResult) {
        backupManagerService.removeOperation(mEphemeralOpToken);
        synchronized (mCancelLock) {
            // The agent reported back to us!
            if (mFinished) {
                Slog.d(TAG, "operationComplete received after task finished.");
                return;
            }

            if (mBackupData == null) {
                // This callback was racing with our timeout, so we've cleaned up the
                // agent state already and are on to the next thing.  We have nothing
                // further to do here: agent state having been cleared means that we've
                // initiated the appropriate next operation.
                final String pkg = (mCurrentPackage != null)
                        ? mCurrentPackage.packageName : "[none]";
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Callback after agent teardown: " + pkg);
                }
                backupManagerService.addBackupTrace("late opComplete; curPkg = " + pkg);
                return;
            }

            final String pkgName = mCurrentPackage.packageName;
            final long filepos = mBackupDataName.length();
            FileDescriptor fd = mBackupData.getFileDescriptor();
            try {
                // If it's a 3rd party app, see whether they wrote any protected keys
                // and complain mightily if they are attempting shenanigans.
                if (mCurrentPackage.applicationInfo != null &&
                        (mCurrentPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                                == 0) {
                    ParcelFileDescriptor readFd = ParcelFileDescriptor.open(mBackupDataName,
                            ParcelFileDescriptor.MODE_READ_ONLY);
                    BackupDataInput in = new BackupDataInput(readFd.getFileDescriptor());
                    try {
                        while (in.readNextHeader()) {
                            final String key = in.getKey();
                            if (key != null && key.charAt(0) >= 0xff00) {
                                // Not okay: crash them and bail.
                                failAgent(mAgentBinder, "Illegal backup key: " + key);
                                backupManagerService
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
                                backupManagerService.getBackupHandler().removeMessages(
                                        MSG_BACKUP_OPERATION_TIMEOUT);
                                BackupObserverUtils
                                        .sendBackupOnPackageResult(mObserver, pkgName,
                                                BackupManager.ERROR_AGENT_FAILURE);
                                errorCleanup();
                                // agentErrorCleanup() implicitly executes next state properly
                                return;
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
                Slog.w(TAG, "Unable to save widget state for " + pkgName);
                try {
                    Os.ftruncate(fd, filepos);
                } catch (ErrnoException ee) {
                    Slog.w(TAG, "Unable to roll back!");
                }
            }

            // Spin the data off to the transport and proceed with the next stage.
            if (MORE_DEBUG) {
                Slog.v(TAG, "operationComplete(): sending data to transport for "
                        + pkgName);
            }
            backupManagerService.getBackupHandler().removeMessages(MSG_BACKUP_OPERATION_TIMEOUT);
            clearAgentState();
            backupManagerService.addBackupTrace("operation complete");

            IBackupTransport transport = mTransportClient.connect("PBT.operationComplete()");
            ParcelFileDescriptor backupData = null;
            mStatus = BackupTransport.TRANSPORT_OK;
            long size = 0;
            try {
                TransportUtils.checkTransportNotNull(transport);
                size = mBackupDataName.length();
                if (size > 0) {
                    boolean isNonIncremental = mSavedStateName.length() == 0;
                    if (mStatus == BackupTransport.TRANSPORT_OK) {
                        backupData = ParcelFileDescriptor.open(mBackupDataName,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        backupManagerService.addBackupTrace("sending data to transport");

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
                        Slog.w(TAG,
                                "Transport requested non-incremental but already the case, error");
                        backupManagerService.addBackupTrace(
                                "Transport requested non-incremental but already the case, error");
                        mStatus = BackupTransport.TRANSPORT_ERROR;
                    }

                    // TODO - We call finishBackup() for each application backed up, because
                    // we need to know now whether it succeeded or failed.  Instead, we should
                    // hold off on finishBackup() until the end, which implies holding off on
                    // renaming *all* the output state files (see below) until that happens.

                    backupManagerService.addBackupTrace("data delivered: " + mStatus);
                    if (mStatus == BackupTransport.TRANSPORT_OK) {
                        backupManagerService.addBackupTrace("finishing op on transport");
                        mStatus = transport.finishBackup();
                        backupManagerService.addBackupTrace("finished: " + mStatus);
                    } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                        backupManagerService.addBackupTrace("transport rejected package");
                    }
                } else {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "no backup data written; not calling transport");
                    }
                    backupManagerService.addBackupTrace("no data to send");
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
                    mBackupDataName.delete();
                    mNewStateName.renameTo(mSavedStateName);
                    BackupObserverUtils
                            .sendBackupOnPackageResult(mObserver, pkgName, BackupManager.SUCCESS);
                    EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE, pkgName, size);
                    backupManagerService.logBackupComplete(pkgName);
                } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                    // The transport has rejected backup of this specific package.  Roll it
                    // back but proceed with running the rest of the queue.
                    mBackupDataName.delete();
                    mNewStateName.delete();
                    BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                            BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
                    EventLogTags.writeBackupAgentFailure(pkgName, "Transport rejected");
                } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                    BackupObserverUtils.sendBackupOnPackageResult(mObserver, pkgName,
                            BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
                    EventLog.writeEvent(EventLogTags.BACKUP_QUOTA_EXCEEDED, pkgName);

                } else if (mStatus == BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED) {
                    Slog.i(TAG, "Transport lost data, retrying package");
                    backupManagerService.addBackupTrace(
                            "Transport lost data, retrying package:" + pkgName);
                    BackupManagerMonitorUtils.monitorEvent(
                            mMonitor,
                            BackupManagerMonitor
                                    .LOG_EVENT_ID_TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
                            mCurrentPackage,
                            BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                            /*extras=*/ null);

                    mBackupDataName.delete();
                    mSavedStateName.delete();
                    mNewStateName.delete();

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
                    if (backupData != null) backupData.close();
                } catch (IOException e) {
                }
            }

            final BackupState nextState;
            if (mStatus == BackupTransport.TRANSPORT_OK
                    || mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                // Success or single-package rejection.  Proceed with the next app if any,
                // otherwise we're done.
                nextState = (mQueue.isEmpty()) ? BackupState.FINAL : BackupState.RUNNING_QUEUE;

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
                        TransportUtils.checkTransportNotNull(transport);
                        long quota = transport.getBackupQuota(mCurrentPackage.packageName, false);
                        mAgentBinder.doQuotaExceeded(size, quota);
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to notify about quota exceeded: " + e.getMessage());
                    }
                }
                nextState = (mQueue.isEmpty()) ? BackupState.FINAL : BackupState.RUNNING_QUEUE;
            } else {
                // Any other error here indicates a transport-level failure.  That means
                // we need to halt everything and reschedule everything for next time.
                revertAndEndBackup();
                nextState = BackupState.FINAL;
            }

            executeNextState(nextState);
        }
    }


    @Override
    @GuardedBy("mCancelLock")
    public void handleCancel(boolean cancelAll) {
        backupManagerService.removeOperation(mEphemeralOpToken);
        synchronized (mCancelLock) {
            if (mFinished) {
                // We have already cancelled this operation.
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Ignoring stale cancel. cancelAll=" + cancelAll);
                }
                return;
            }
            mCancelAll = cancelAll;
            final String logPackageName = (mCurrentPackage != null)
                    ? mCurrentPackage.packageName
                    : "no_package_yet";
            Slog.i(TAG, "Cancel backing up " + logPackageName);
            EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, logPackageName);
            backupManagerService.addBackupTrace(
                    "cancel of " + logPackageName + ", cancelAll=" + cancelAll);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(mMonitor,
                    BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL,
                    mCurrentPackage, BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                    BackupManagerMonitorUtils.putMonitoringExtra(null,
                            BackupManagerMonitor.EXTRA_LOG_CANCEL_ALL,
                            mCancelAll));
            errorCleanup();
            if (!cancelAll) {
                // The current agent either timed out or was cancelled running doBackup().
                // Restage it for the next time we run a backup pass.
                // !!! TODO: keep track of failure counts per agent, and blacklist those which
                // fail repeatedly (i.e. have proved themselves to be buggy).
                executeNextState(
                        mQueue.isEmpty() ? BackupState.FINAL : BackupState.RUNNING_QUEUE);
                backupManagerService.dataChangedImpl(mCurrentPackage.packageName);
            } else {
                finalizeBackup();
            }
        }
    }

    private void revertAndEndBackup() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Reverting backup queue - restaging everything");
        }
        backupManagerService.addBackupTrace("transport error; reverting");

        // We want to reset the backup schedule based on whatever the transport suggests
        // by way of retry/backoff time.
        long delay;
        try {
            IBackupTransport transport =
                    mTransportClient.connectOrThrow("PBT.revertAndEndBackup()");
            delay = transport.requestBackupTime();
        } catch (Exception e) {
            Slog.w(TAG, "Unable to contact transport for recommended backoff: " + e.getMessage());
            delay = 0;  // use the scheduler's default
        }
        KeyValueBackupJob.schedule(backupManagerService.getContext(), delay,
                backupManagerService.getConstants());

        for (BackupRequest request : mOriginalQueue) {
            backupManagerService.dataChangedImpl(request.packageName);
        }

    }

    private void errorCleanup() {
        mBackupDataName.delete();
        mNewStateName.delete();
        clearAgentState();
    }

    // Cleanup common to both success and failure cases
    private void clearAgentState() {
        try {
            if (mSavedState != null) mSavedState.close();
        } catch (IOException e) {
        }
        try {
            if (mBackupData != null) mBackupData.close();
        } catch (IOException e) {
        }
        try {
            if (mNewState != null) mNewState.close();
        } catch (IOException e) {
        }
        synchronized (backupManagerService.getCurrentOpLock()) {
            // Current-operation callback handling requires the validity of these various
            // bits of internal state as an invariant of the operation still being live.
            // This means we make sure to clear all of the state in unison inside the lock.
            backupManagerService.getCurrentOperations().remove(mEphemeralOpToken);
            mSavedState = mBackupData = mNewState = null;
        }

        // If this was a pseudopackage there's no associated Activity Manager state
        if (mCurrentPackage.applicationInfo != null) {
            backupManagerService.addBackupTrace("unbinding " + mCurrentPackage.packageName);
            try {  // unbind even on timeout, just in case
                backupManagerService.getActivityManager().unbindBackupAgent(
                        mCurrentPackage.applicationInfo);
            } catch (RemoteException e) { /* can't happen; activity manager is local */ }
        }
    }

    private void executeNextState(BackupState nextState) {
        if (MORE_DEBUG) {
            Slog.i(TAG, " => executing next step on "
                    + this + " nextState=" + nextState);
        }
        backupManagerService.addBackupTrace("executeNextState => " + nextState);
        mCurrentState = nextState;
        Message msg = backupManagerService.getBackupHandler().obtainMessage(
                MSG_BACKUP_RESTORE_STEP, this);
        backupManagerService.getBackupHandler().sendMessage(msg);
    }
}
