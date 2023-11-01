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

package com.android.server.backup.restore;

import static android.app.backup.BackupAnnotations.OperationType.RESTORE;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.KEY_WIDGET_STATE;
import static com.android.server.backup.UserBackupManagerService.PACKAGE_MANAGER_SENTINEL;
import static com.android.server.backup.UserBackupManagerService.SETTINGS_PACKAGE;
import static com.android.server.backup.internal.BackupHandler.MSG_BACKUP_RESTORE_STEP;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_SESSION_TIMEOUT;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.Nullable;
import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.RestoreDescription;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupAndRestoreFeatureFlags;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.BackupUtils;
import com.android.server.backup.Flags;
import com.android.server.backup.OperationStorage;
import com.android.server.backup.OperationStorage.OpType;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.PackageManagerBackupAgent.Metadata;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.utils.BackupEligibilityRules;
import com.android.server.backup.utils.BackupManagerMonitorEventSender;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class PerformUnifiedRestoreTask implements BackupRestoreTask {
    private UserBackupManagerService backupManagerService;
    private final OperationStorage mOperationStorage;
    private final int mUserId;
    private final TransportManager mTransportManager;
    // Transport client we're working with to do the restore
    private final TransportConnection mTransportConnection;

    // Where per-transport saved state goes
    private File mStateDir;

    // Restore observer; may be null
    private IRestoreObserver mObserver;

    private BackupManagerMonitorEventSender mBackupManagerMonitorEventSender;

    // Token identifying the dataset to the transport
    private long mToken;

    // When this is a restore-during-install, this is the token identifying the
    // operation to the Package Manager, and we must ensure that we let it know
    // when we're finished.
    private int mPmToken;

    // When this is restore-during-install, we need to tell the package manager
    // whether we actually launched the app, because this affects notifications
    // around externally-visible state transitions.
    private boolean mDidLaunch;

    // Is this a whole-system restore, i.e. are we establishing a new ancestral
    // dataset to base future restore-at-install operations from?
    private boolean mIsSystemRestore;

    // If this is a single-package restore, what package are we interested in?
    private PackageInfo mTargetPackage;

    // In all cases, the calculated list of packages that we are trying to restore
    private List<PackageInfo> mAcceptSet;

    // Our bookkeeping about the ancestral dataset
    private PackageManagerBackupAgent mPmAgent;

    // Currently-bound backup agent for restore + restoreFinished purposes
    private IBackupAgent mAgent;

    // What sort of restore we're doing now
    private RestoreDescription mRestoreDescription;

    // The package we're currently restoring
    private PackageInfo mCurrentPackage;

    // Widget-related data handled as part of this restore operation
    private byte[] mWidgetData;

    // Number of apps restored in this pass
    private int mCount;

    // When did we start?
    private long mStartRealtime;

    // State machine progress
    private UnifiedRestoreState mState;

    // How are things going?
    private int mStatus;

    // Done?
    private boolean mFinished;

    // When finished call listener
    private final OnTaskFinishedListener mListener;

    // Key/value: bookkeeping about staged data and files for agent access
    private File mBackupDataName;
    private File mStageName;
    private File mNewStateName;
    private ParcelFileDescriptor mBackupData;
    private ParcelFileDescriptor mNewState;

    private final int mEphemeralOpToken;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final BackupEligibilityRules mBackupEligibilityRules;

    @VisibleForTesting
    PerformUnifiedRestoreTask(
            UserBackupManagerService backupManagerService,
            TransportConnection transportConnection) {
        mListener = null;
        mAgentTimeoutParameters = null;
        mOperationStorage = null;
        mTransportConnection = transportConnection;
        mTransportManager = null;
        mEphemeralOpToken = 0;
        mUserId = 0;
        mBackupEligibilityRules = null;
        this.backupManagerService = backupManagerService;
        mBackupManagerMonitorEventSender =
                new BackupManagerMonitorEventSender(/*monitor*/null);
    }

    // This task can assume that the wakelock is properly held for it and doesn't have to worry
    // about releasing it.
    public PerformUnifiedRestoreTask(
            UserBackupManagerService backupManagerService,
            OperationStorage operationStorage,
            TransportConnection transportConnection,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long restoreSetToken,
            @Nullable PackageInfo targetPackage,
            int pmToken,
            boolean isFullSystemRestore,
            @Nullable String[] filterSet,
            OnTaskFinishedListener listener,
            BackupEligibilityRules backupEligibilityRules) {
        this.backupManagerService = backupManagerService;
        mOperationStorage = operationStorage;
        mUserId = backupManagerService.getUserId();
        mTransportManager = backupManagerService.getTransportManager();
        mEphemeralOpToken = backupManagerService.generateRandomIntegerToken();
        mState = UnifiedRestoreState.INITIAL;
        mStartRealtime = SystemClock.elapsedRealtime();

        mTransportConnection = transportConnection;
        mObserver = observer;
        mBackupManagerMonitorEventSender =
                new BackupManagerMonitorEventSender(monitor);
        mToken = restoreSetToken;
        mPmToken = pmToken;
        mTargetPackage = targetPackage;
        mIsSystemRestore = isFullSystemRestore;
        mFinished = false;
        mDidLaunch = false;
        mListener = listener;
        mAgentTimeoutParameters = Objects.requireNonNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
        mBackupEligibilityRules = backupEligibilityRules;

        if (targetPackage != null) {
            // Single package restore
            mAcceptSet = new ArrayList<>();
            mAcceptSet.add(targetPackage);
        } else {
            // Everything possible, or a target set
            if (filterSet == null) {
                // We want everything and a pony
                List<PackageInfo> apps =
                        PackageManagerBackupAgent.getStorableApplications(
                                backupManagerService.getPackageManager(), mUserId,
                                backupEligibilityRules);
                filterSet = packagesToNames(apps);
                if (DEBUG) {
                    Slog.i(TAG, "Full restore; asking about " + filterSet.length + " apps");
                }
            }

            mAcceptSet = new ArrayList<>(filterSet.length);

            // Pro tem, we insist on moving the settings provider package to last place.
            // Keep track of whether it's in the list, and bump it down if so.  We also
            // want to do the system package itself first if it's called for.
            boolean hasSystem = false;
            boolean hasSettings = false;
            for (int i = 0; i < filterSet.length; i++) {
                try {
                    PackageManager pm = backupManagerService.getPackageManager();
                    PackageInfo info = pm.getPackageInfoAsUser(filterSet[i], 0, mUserId);
                    if (PLATFORM_PACKAGE_NAME.equals(info.packageName)) {
                        hasSystem = true;
                        continue;
                    }
                    if (SETTINGS_PACKAGE.equals(info.packageName)) {
                        hasSettings = true;
                        continue;
                    }


                    ApplicationInfo applicationInfo = info.applicationInfo;
                    if (backupEligibilityRules.appIsEligibleForBackup(applicationInfo)) {
                        if (Flags.enableSkippingRestoreLaunchedApps()
                            && !backupEligibilityRules.isAppEligibleForRestore(applicationInfo)) {
                            continue;
                        }

                        mAcceptSet.add(info);
                    }
                } catch (NameNotFoundException e) {
                    // requested package name doesn't exist; ignore it
                }
            }
            if (hasSystem) {
                try {
                    mAcceptSet.add(0, backupManagerService.getPackageManager().getPackageInfoAsUser(
                                    PLATFORM_PACKAGE_NAME, 0, mUserId));
                } catch (NameNotFoundException e) {
                    // won't happen; we know a priori that it's valid
                }
            }
            if (hasSettings) {
                try {
                    mAcceptSet.add(backupManagerService.getPackageManager().getPackageInfoAsUser(
                            SETTINGS_PACKAGE, 0, mUserId));
                } catch (NameNotFoundException e) {
                    // this one is always valid too
                }
            }
        }

        mAcceptSet = backupManagerService.filterUserFacingPackages(mAcceptSet);

        if (MORE_DEBUG) {
            Slog.v(TAG, "Restore; accept set size is " + mAcceptSet.size());
            for (PackageInfo info : mAcceptSet) {
                Slog.v(TAG, "   " + info.packageName);
            }
        }
    }

    private String[] packagesToNames(List<PackageInfo> apps) {
        final int N = apps.size();
        String[] names = new String[N];
        for (int i = 0; i < N; i++) {
            names[i] = apps.get(i).packageName;
        }
        return names;
    }

    // Execute one tick of whatever state machine the task implements
    @Override
    public void execute() {
        if (MORE_DEBUG) {
            Slog.v(TAG, "*** Executing restore step " + mState);
        }
        switch (mState) {
            case INITIAL:
                startRestore();
                break;

            case RUNNING_QUEUE:
                dispatchNextRestore();
                break;

            case RESTORE_KEYVALUE:
                restoreKeyValue();
                break;

            case RESTORE_FULL:
                restoreFull();
                break;

            case RESTORE_FINISHED:
                restoreFinished();
                break;

            case FINAL:
                if (!mFinished) {
                    finalizeRestore();
                } else {
                    Slog.e(TAG, "Duplicate finish");
                }
                mFinished = true;
                break;
        }
    }

    /*
     * SKETCH OF OPERATION
     *
     * create one of these PerformUnifiedRestoreTask objects, telling it which
     * dataset & transport to address, and then parameters within the restore
     * operation: single target package vs many, etc.
     *
     * 1. transport.startRestore(token, list-of-packages).  If we need @pm@  it is
     * always placed first and the settings provider always placed last [for now].
     *
     * 1a [if we needed @pm@ then nextRestorePackage() and restore the PMBA inline]
     *
     *   [ state change => RUNNING_QUEUE ]
     *
     * NOW ITERATE:
     *
     * { 3. t.nextRestorePackage()
     *   4. does the metadata for this package allow us to restore it?
     *      does the on-disk app permit us to restore it? [re-check allowBackup etc]
     *   5. is this a key/value dataset?  => key/value agent restore
     *       [ state change => RESTORE_KEYVALUE ]
     *       5a. spin up agent
     *       5b. t.getRestoreData() to stage it properly
     *       5c. call into agent to perform restore
     *       5d. tear down agent
     *       [ state change => RUNNING_QUEUE ]
     *
     *   6. else it's a stream dataset:
     *       [ state change => RESTORE_FULL ]
     *       6a. instantiate the engine for a stream restore: engine handles agent lifecycles
     *       6b. spin off engine runner on separate thread
     *       6c. ITERATE getNextFullRestoreDataChunk() and copy data to engine runner socket
     *       [ state change => RUNNING_QUEUE ]
     * }
     *
     *   [ state change => FINAL ]
     *
     * 7. t.finishRestore(), call listeners, etc.
     *
     *
     */

    // state INITIAL : set up for the restore and read the metadata if necessary
    private void startRestore() {
        sendStartRestore(mAcceptSet.size());

        // If we're starting a full-system restore, set up to begin widget ID remapping
        if (mIsSystemRestore) {
            AppWidgetBackupBridge.systemRestoreStarting(mUserId);
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_START_SYSTEM_RESTORE,
                    null,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
        } else {
            //We are either performing RestoreAtInstall or Bmgr.
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_START_RESTORE_AT_INSTALL,
                    null,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
        }

        try {
            String transportDirName =
                    mTransportManager.getTransportDirName(
                            mTransportConnection.getTransportComponent());
            mStateDir = new File(backupManagerService.getBaseStateDir(), transportDirName);

            // Fetch the current metadata from the dataset first
            PackageInfo pmPackage = new PackageInfo();
            pmPackage.packageName = PACKAGE_MANAGER_SENTINEL;
            mAcceptSet.add(0, pmPackage);

            PackageInfo[] packages = mAcceptSet.toArray(new PackageInfo[0]);

            BackupTransportClient transport =
                    mTransportConnection.connectOrThrow("PerformUnifiedRestoreTask.startRestore()");

            // If the requester of the restore has not passed in a monitor, we ask the transport
            // for one.
            if (mBackupManagerMonitorEventSender.getMonitor() == null) {
                mBackupManagerMonitorEventSender.setMonitor(transport.getBackupManagerMonitor());
            }

            mStatus = transport.startRestore(mToken, packages);
            if (mStatus != BackupTransport.TRANSPORT_OK) {
                Slog.e(TAG, "Transport error " + mStatus + "; no restore possible");
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_DURING_START_RESTORE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                        monitoringExtras);
                mStatus = BackupTransport.TRANSPORT_ERROR;
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }

            RestoreDescription desc = transport.nextRestorePackage();
            if (desc == null) {
                Slog.e(TAG, "No restore metadata available; halting");
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_NO_RESTORE_METADATA_AVAILABLE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                mStatus = BackupTransport.TRANSPORT_ERROR;
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }
            if (!PACKAGE_MANAGER_SENTINEL.equals(
                    desc.getPackageName())) {
                Slog.e(TAG, "Required package metadata but got "
                        + desc.getPackageName());
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_NO_PM_METADATA_RECEIVED,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                mStatus = BackupTransport.TRANSPORT_ERROR;
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }

            // Pull the Package Manager metadata from the restore set first
            mCurrentPackage = new PackageInfo();
            mCurrentPackage.packageName = PACKAGE_MANAGER_SENTINEL;
            mCurrentPackage.applicationInfo = new ApplicationInfo();
            mCurrentPackage.applicationInfo.uid = Process.SYSTEM_UID;
            mPmAgent = backupManagerService.makeMetadataAgent(null);
            mAgent = IBackupAgent.Stub.asInterface(mPmAgent.onBind());
            if (MORE_DEBUG) {
                Slog.v(TAG, "initiating restore for PMBA");
            }
            initiateOneRestore(mCurrentPackage, 0);
            // The PM agent called operationComplete() already, because our invocation
            // of it is process-local and therefore synchronous.  That means that the
            // next-state message (RUNNING_QUEUE) is already enqueued.  Only if we're
            // unable to proceed with running the queue do we remove that pending
            // message and jump straight to the FINAL state.  Because this was
            // synchronous we also know that we should cancel the pending timeout
            // message.
            backupManagerService.getBackupHandler().removeMessages(
                    MSG_RESTORE_OPERATION_TIMEOUT);

            // Verify that the backup set includes metadata.  If not, we can't do
            // signature/version verification etc, so we simply do not proceed with
            // the restore operation.
            if (!mPmAgent.hasMetadata()) {
                Slog.e(TAG, "PM agent has no metadata, so not restoring");
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_PM_AGENT_HAS_NO_METADATA,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                        PACKAGE_MANAGER_SENTINEL,
                        "Package manager restore metadata missing");
                mStatus = BackupTransport.TRANSPORT_ERROR;
                backupManagerService.getBackupHandler().removeMessages(
                        MSG_BACKUP_RESTORE_STEP, this);
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }

            // Success; cache the metadata and continue as expected with the
            // next state already enqueued

        } catch (Exception e) {
            // If we lost the transport at any time, halt
            Slog.e(TAG, "Unable to contact transport for restore: " + e.getMessage());
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_LOST_TRANSPORT,
                    null,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                    monitoringExtras);
            mStatus = BackupTransport.TRANSPORT_ERROR;
            backupManagerService.getBackupHandler().removeMessages(
                    MSG_BACKUP_RESTORE_STEP, this);
            executeNextState(UnifiedRestoreState.FINAL);
            return;
        }
    }

    // state RUNNING_QUEUE : figure out what the next thing to be restored is,
    // and fire the appropriate next step
    private void dispatchNextRestore() {
        UnifiedRestoreState nextState = UnifiedRestoreState.FINAL;
        try {
            BackupTransportClient transport =
                    mTransportConnection.connectOrThrow(
                            "PerformUnifiedRestoreTask.dispatchNextRestore()");
            mRestoreDescription = transport.nextRestorePackage();
            final String pkgName = (mRestoreDescription != null)
                    ? mRestoreDescription.getPackageName() : null;
            if (pkgName == null) {
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_CANNOT_GET_NEXT_PKG_NAME,
                        null,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                        monitoringExtras);
                Slog.e(TAG, "Failure getting next package name");
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                nextState = UnifiedRestoreState.FINAL;
                return;
            } else if (mRestoreDescription == RestoreDescription.NO_MORE_PACKAGES) {
                // Yay we've reached the end cleanly
                if (DEBUG) {
                    Slog.v(TAG, "No more packages; finishing restore");
                }
                int millis = (int) (SystemClock.elapsedRealtime() - mStartRealtime);
                EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, mCount, millis);
                nextState = UnifiedRestoreState.FINAL;
                return;
            }

            if (DEBUG) {
                Slog.i(TAG, "Next restore package: " + mRestoreDescription);
            }
            sendOnRestorePackage(pkgName);

            Metadata metaInfo = mPmAgent.getRestoredMetadata(pkgName);
            if (metaInfo == null) {
                PackageInfo pkgInfo = new PackageInfo();
                pkgInfo.packageName = pkgName;
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_PM_AGENT_HAS_NO_METADATA,
                        pkgInfo,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                Slog.e(TAG, "No metadata for " + pkgName);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, pkgName,
                        "Package metadata missing");
                nextState = UnifiedRestoreState.RUNNING_QUEUE;
                return;
            }

            try {
                mCurrentPackage = backupManagerService.getPackageManager().getPackageInfoAsUser(
                        pkgName, PackageManager.GET_SIGNING_CERTIFICATES, mUserId);
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_START_PACKAGE_RESTORE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);

            } catch (NameNotFoundException e) {
                // Whoops, we thought we could restore this package but it
                // turns out not to be present.  Skip it.
                Slog.e(TAG, "Package not present: " + pkgName);
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_NOT_PRESENT,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, pkgName,
                        "Package missing on device");
                nextState = UnifiedRestoreState.RUNNING_QUEUE;
                return;
            }

            if (metaInfo.versionCode > mCurrentPackage.getLongVersionCode()) {
                // Data is from a "newer" version of the app than we have currently
                // installed.  If the app has not declared that it is prepared to
                // handle this case, we do not attempt the restore.
                if ((mCurrentPackage.applicationInfo.flags
                        & ApplicationInfo.FLAG_RESTORE_ANY_VERSION) == 0) {
                    String message = "Source version " + metaInfo.versionCode
                            + " > installed version " + mCurrentPackage.getLongVersionCode();
                    Slog.w(TAG, "Package " + pkgName + ": " + message);
                    Bundle monitoringExtras = mBackupManagerMonitorEventSender.putMonitoringExtra(
                            null,
                            BackupManagerMonitor.EXTRA_LOG_RESTORE_VERSION,
                            metaInfo.versionCode);
                    monitoringExtras = mBackupManagerMonitorEventSender.putMonitoringExtra(
                            monitoringExtras,
                            BackupManagerMonitor.EXTRA_LOG_RESTORE_ANYWAY, false);
                    monitoringExtras = addRestoreOperationTypeToEvent(monitoringExtras);
                    mBackupManagerMonitorEventSender.monitorEvent(
                            BackupManagerMonitor.LOG_EVENT_ID_RESTORE_VERSION_HIGHER,
                            mCurrentPackage,
                            BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                            monitoringExtras);
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                            pkgName, message);
                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    return;
                } else {
                    if (DEBUG) {
                        Slog.v(TAG, "Source version " + metaInfo.versionCode
                                + " > installed version " + mCurrentPackage.getLongVersionCode()
                                + " but restoreAnyVersion");
                    }
                    Bundle monitoringExtras = mBackupManagerMonitorEventSender.putMonitoringExtra(
                            null,
                            BackupManagerMonitor.EXTRA_LOG_RESTORE_VERSION,
                            metaInfo.versionCode);
                    monitoringExtras = mBackupManagerMonitorEventSender.putMonitoringExtra(
                            monitoringExtras,
                            BackupManagerMonitor.EXTRA_LOG_RESTORE_ANYWAY, true);
                    monitoringExtras = addRestoreOperationTypeToEvent(monitoringExtras);
                    mBackupManagerMonitorEventSender.monitorEvent(
                            BackupManagerMonitor.LOG_EVENT_ID_RESTORE_VERSION_HIGHER,
                            mCurrentPackage,
                            BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                            monitoringExtras);
                }
            }

            if (MORE_DEBUG) {
                Slog.v(TAG, "Package " + pkgName
                        + " restore version [" + metaInfo.versionCode
                        + "] is compatible with installed version ["
                        + mCurrentPackage.getLongVersionCode() + "]");
            }

            // Reset per-package preconditions and fire the appropriate next state
            mWidgetData = null;
            final int type = mRestoreDescription.getDataType();
            if (type == RestoreDescription.TYPE_KEY_VALUE) {
                nextState = UnifiedRestoreState.RESTORE_KEYVALUE;
            } else if (type == RestoreDescription.TYPE_FULL_STREAM) {
                nextState = UnifiedRestoreState.RESTORE_FULL;
            } else {
                // Unknown restore type; ignore this package and move on
                Slog.e(TAG, "Unrecognized restore type " + type);
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);;
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_RESTORE_TYPE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                nextState = UnifiedRestoreState.RUNNING_QUEUE;
                return;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Can't get next restore target from transport; halting: "
                    + e.getMessage());
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);;
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_NO_NEXT_RESTORE_TARGET,
                    mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
            nextState = UnifiedRestoreState.FINAL;
            return;
        } finally {
            executeNextState(nextState);
        }
    }

    // state RESTORE_KEYVALUE : restore one package via key/value API set
    private void restoreKeyValue() {
        // Initiating the restore will pass responsibility for the state machine's
        // progress to the agent callback, so we do not always execute the
        // next state here.
        final String packageName = mCurrentPackage.packageName;
        // Validate some semantic requirements that apply in this way
        // only to the key/value restore API flow
        mBackupManagerMonitorEventSender.monitorEvent(
                BackupManagerMonitor.LOG_EVENT_ID_KV_RESTORE, mCurrentPackage,
                BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                /*monitoringExtras*/ addRestoreOperationTypeToEvent(/*extras*/null));
        if (mCurrentPackage.applicationInfo.backupAgentName == null
                || "".equals(mCurrentPackage.applicationInfo.backupAgentName)) {
            if (MORE_DEBUG) {
                Slog.i(TAG, "Data exists for package " + packageName
                        + " but app has no agent; skipping");
            }
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_APP_HAS_NO_AGENT, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT, monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                    "Package has no agent");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }

        Metadata metaInfo = mPmAgent.getRestoredMetadata(packageName);
        PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        if (!BackupUtils.signaturesMatch(metaInfo.sigHashes, mCurrentPackage, pmi)) {
            Slog.w(TAG, "Signature mismatch restoring " + packageName);
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_SIGNATURE_MISMATCH, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                    "Signature mismatch");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }

        // Good to go!  Set up and bind the agent...
        mAgent = backupManagerService.bindToAgentSynchronous(
                mCurrentPackage.applicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_RESTORE,
                mBackupEligibilityRules.getBackupDestination());
        if (mAgent == null) {
            Slog.w(TAG, "Can't find backup agent for " + packageName);
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_CANT_FIND_AGENT, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                    "Restore agent missing");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }

        // Whatever happens next, we've launched the target app now; remember that.
        mDidLaunch = true;

        // And then finally start the restore on this agent
        try {
            initiateOneRestore(mCurrentPackage, metaInfo.versionCode);
            ++mCount;
        } catch (Exception e) {
            Slog.e(TAG, "Error when attempting restore: " + e.toString());
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_KV_AGENT_ERROR, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                    monitoringExtras);
            keyValueAgentErrorCleanup(false);
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    // Guts of a key/value restore operation
    private void initiateOneRestore(PackageInfo app, long appVersionCode) {
        final String packageName = app.packageName;

        if (DEBUG) {
            Slog.d(TAG, "initiateOneRestore packageName=" + packageName);
        }

        // !!! TODO: get the dirs from the transport
        mBackupDataName = new File(backupManagerService.getDataDir(), packageName + ".restore");
        mStageName = new File(backupManagerService.getDataDir(), packageName + ".stage");
        mNewStateName = new File(mStateDir, packageName + ".new");

        boolean staging = shouldStageBackupData(packageName);
        ParcelFileDescriptor stage;
        File downloadFile = (staging) ? mStageName : mBackupDataName;
        boolean startedAgentRestore = false;

        try {
            BackupTransportClient transport =
                    mTransportConnection.connectOrThrow(
                            "PerformUnifiedRestoreTask.initiateOneRestore()");

            // Run the transport's restore pass
            stage = ParcelFileDescriptor.open(downloadFile,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            if (transport.getRestoreData(stage) != BackupTransport.TRANSPORT_OK) {
                // Transport-level failure. This failure could be specific to package currently in
                // restore.
                Slog.e(TAG, "Error getting restore data for " + packageName);
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_KV_RESTORE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                stage.close();
                downloadFile.delete();
                UnifiedRestoreState nextState =
                        BackupAndRestoreFeatureFlags
                                .getUnifiedRestoreContinueAfterTransportFailureInKvRestore()
                                ? UnifiedRestoreState.RUNNING_QUEUE
                                : UnifiedRestoreState.FINAL;
                executeNextState(nextState);
                return;
            }

            // We have the data from the transport. Now we extract and strip
            // any per-package metadata (typically widget-related information)
            // if appropriate
            if (staging) {
                stage.close();
                stage = ParcelFileDescriptor.open(downloadFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);

                mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                                ParcelFileDescriptor.MODE_CREATE |
                                ParcelFileDescriptor.MODE_TRUNCATE);

                BackupDataInput in = new BackupDataInput(stage.getFileDescriptor());
                BackupDataOutput out = new BackupDataOutput(mBackupData.getFileDescriptor());
                filterExcludedKeys(packageName, in, out);

                mBackupData.close();
            }

            // Okay, we have the data.  Now have the agent do the restore.
            stage.close();

            mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                    ParcelFileDescriptor.MODE_READ_ONLY);

            mNewState = ParcelFileDescriptor.open(mNewStateName,
                    ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

            // Kick off the restore, checking for hung agents.  The timeout or
            // the operationComplete() callback will schedule the next step,
            // so we do not do that here.
            long restoreAgentTimeoutMillis = mAgentTimeoutParameters.getRestoreAgentTimeoutMillis(
                    app.applicationInfo.uid);
            backupManagerService.prepareOperationTimeout(
                    mEphemeralOpToken, restoreAgentTimeoutMillis, this, OpType.RESTORE_WAIT);
            startedAgentRestore = true;
            mAgent.doRestoreWithExcludedKeys(mBackupData, appVersionCode, mNewState,
                    mEphemeralOpToken, backupManagerService.getBackupManagerBinder(),
                    new ArrayList<>(getExcludedKeysForPackage(packageName)));
        } catch (Exception e) {
            Slog.e(TAG, "Unable to call app for restore: " + packageName, e);
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_KV_AGENT_ERROR, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                    monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                    packageName, e.toString());
            // Clears any pending timeout messages as well.
            keyValueAgentErrorCleanup(startedAgentRestore);

            // After a restore failure we go back to running the queue.  If there
            // are no more packages to be restored that will be handled by the
            // next step.
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    @VisibleForTesting
    boolean shouldStageBackupData(String packageName) {
        // Backup data is staged for 2 reasons:
        // 1. We might need to exclude keys from the data before passing it to the agent
        // 2. Widget metadata needs to be separated from the rest to be handled separately
        // But 'android' package doesn't contain widget metadata so we want to skip staging for it
        // when there are no keys to be excluded either.
        return !packageName.equals(PLATFORM_PACKAGE_NAME) ||
                !getExcludedKeysForPackage(PLATFORM_PACKAGE_NAME).isEmpty();
    }

    @VisibleForTesting
    Set<String> getExcludedKeysForPackage(String packageName) {
        return backupManagerService.getExcludedRestoreKeys(packageName);
    }

    @VisibleForTesting
    void filterExcludedKeys(String packageName, BackupDataInput in, BackupDataOutput out)
            throws Exception {
        Set<String> excludedKeysForPackage = getExcludedKeysForPackage(packageName);

        byte[] buffer = new byte[8192]; // will grow when needed
        while (in.readNextHeader()) {
            final String key = in.getKey();
            final int size = in.getDataSize();

            if (excludedKeysForPackage != null && excludedKeysForPackage.contains(key)) {
                Slog.i(TAG, "Skipping blocked key " + key);
                in.skipEntityData();
                continue;
            }

            // is this a special key?
            if (key.equals(KEY_WIDGET_STATE)) {
                if (DEBUG) {
                    Slog.i(TAG, "Restoring widget state for " + packageName);
                }
                mWidgetData = new byte[size];
                in.readEntityData(mWidgetData, 0, size);
            } else {
                if (size > buffer.length) {
                    buffer = new byte[size];
                }
                in.readEntityData(buffer, 0, size);
                out.writeEntityHeader(key, size);
                out.writeEntityData(buffer, size);
            }
        }
    }

    // state RESTORE_FULL : restore one package via streaming engine
    private void restoreFull() {
        // None of this can run on the work looper here, so we spin asynchronous
        // work like this:
        //
        //   StreamFeederThread: read data from transport.getNextFullRestoreDataChunk()
        //                       write it into the pipe to the engine
        //   EngineThread: FullRestoreEngine thread communicating with the target app
        //
        // When finished, StreamFeederThread executes next state as appropriate on the
        // backup looper, and the overall unified restore task resumes
        mBackupManagerMonitorEventSender.monitorEvent(
                BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE, mCurrentPackage,
                BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                /*monitoringExtras*/ addRestoreOperationTypeToEvent(/*extras*/null));
        try {
            StreamFeederThread feeder = new StreamFeederThread();
            if (MORE_DEBUG) {
                Slog.i(TAG, "Spinning threads for stream restore of "
                        + mCurrentPackage.packageName);
            }
            new Thread(feeder, "unified-stream-feeder").start();

            // At this point the feeder is responsible for advancing the restore
            // state, so we're done here.
        } catch (IOException e) {
            // Unable to instantiate the feeder thread -- we need to bail on the
            // current target.  We haven't asked the transport for data yet, though,
            // so we can do that simply by going back to running the restore queue.
            Slog.e(TAG, "Unable to construct pipes for stream restore!");
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_NO_FEEDER_THREAD, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    // state RESTORE_FINISHED : provide the "no more data" signpost callback at the end
    private void restoreFinished() {
        if (DEBUG) {
            Slog.d(TAG, "restoreFinished packageName=" + mCurrentPackage.packageName);
        }
        try {
            long restoreAgentFinishedTimeoutMillis =
                    mAgentTimeoutParameters.getRestoreAgentFinishedTimeoutMillis();
            backupManagerService
                    .prepareOperationTimeout(mEphemeralOpToken,
                            restoreAgentFinishedTimeoutMillis, this,
                            OpType.RESTORE_WAIT);
            mAgent.doRestoreFinished(mEphemeralOpToken,
                    backupManagerService.getBackupManagerBinder());

            // If we get this far, the callback or timeout will schedule the
            // next restore state, so we're done
        } catch (Exception e) {
            final String packageName = mCurrentPackage.packageName;
            Slog.e(TAG, "Unable to finalize restore of " + packageName);
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_AGENT_FAILURE, mCurrentPackage,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT, monitoringExtras);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                    packageName, e.toString());
            keyValueAgentErrorCleanup(true);
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    class StreamFeederThread extends RestoreEngine implements Runnable, BackupRestoreTask {

        final String TAG = "StreamFeederThread";
        FullRestoreEngine mEngine;
        FullRestoreEngineThread mEngineThread;

        // pipe through which we read data from the transport. [0] read, [1] write
        ParcelFileDescriptor[] mTransportPipes;

        // pipe through which the engine will read data.  [0] read, [1] write
        ParcelFileDescriptor[] mEnginePipes;

        private final int mEphemeralOpToken;

        public StreamFeederThread() throws IOException {
            mEphemeralOpToken = backupManagerService.generateRandomIntegerToken();
            mTransportPipes = ParcelFileDescriptor.createPipe();
            mEnginePipes = ParcelFileDescriptor.createPipe();
            setRunning(true);
        }

        @Override
        public void run() {
            UnifiedRestoreState nextState = UnifiedRestoreState.RUNNING_QUEUE;
            int status = BackupTransport.TRANSPORT_OK;

            EventLog.writeEvent(EventLogTags.FULL_RESTORE_PACKAGE,
                    mCurrentPackage.packageName);

            mEngine = new FullRestoreEngine(backupManagerService, mOperationStorage,
                    this, null, mBackupManagerMonitorEventSender.getMonitor(),
                    mCurrentPackage, false, mEphemeralOpToken, false,
                    mBackupEligibilityRules);
            mEngineThread = new FullRestoreEngineThread(mEngine, mEnginePipes[0]);

            ParcelFileDescriptor eWriteEnd = mEnginePipes[1];
            ParcelFileDescriptor tReadEnd = mTransportPipes[0];
            ParcelFileDescriptor tWriteEnd = mTransportPipes[1];

            int bufferSize = 32 * 1024;
            byte[] buffer = new byte[bufferSize];
            FileOutputStream engineOut = new FileOutputStream(eWriteEnd.getFileDescriptor());
            FileInputStream transportIn = new FileInputStream(tReadEnd.getFileDescriptor());

            // spin up the engine and start moving data to it
            new Thread(mEngineThread, "unified-restore-engine").start();

            String callerLogString = "PerformUnifiedRestoreTask$StreamFeederThread.run()";
            try {
                BackupTransportClient transport = mTransportConnection.connectOrThrow(
                        callerLogString);
                while (status == BackupTransport.TRANSPORT_OK) {
                    // have the transport write some of the restoring data to us
                    int result = transport.getNextFullRestoreDataChunk(tWriteEnd);
                    if (result > 0) {
                        // The transport wrote this many bytes of restore data to the
                        // pipe, so pass it along to the engine.
                        if (MORE_DEBUG) {
                            Slog.v(TAG, "  <- transport provided chunk size " + result);
                        }
                        if (result > bufferSize) {
                            bufferSize = result;
                            buffer = new byte[bufferSize];
                        }
                        int toCopy = result;
                        while (toCopy > 0) {
                            int n = transportIn.read(buffer, 0, toCopy);
                            engineOut.write(buffer, 0, n);
                            toCopy -= n;
                            if (MORE_DEBUG) {
                                Slog.v(TAG, "  -> wrote " + n + " to engine, left=" + toCopy);
                            }
                        }
                    } else if (result == BackupTransport.NO_MORE_DATA) {
                        // Clean finish.  Wind up and we're done!
                        if (MORE_DEBUG) {
                            Slog.i(TAG, "Got clean full-restore EOF for "
                                    + mCurrentPackage.packageName);
                        }
                        status = BackupTransport.TRANSPORT_OK;
                        break;
                    } else {
                        // Transport reported some sort of failure; the fall-through
                        // handling will deal properly with that.
                        Slog.e(TAG, "Error " + result + " streaming restore for "
                                + mCurrentPackage.packageName);
                        Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                        mBackupManagerMonitorEventSender.monitorEvent(
                                BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_FULL_RESTORE,
                                mCurrentPackage,
                                BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                                monitoringExtras);
                        EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                        status = result;
                    }
                }
                if (MORE_DEBUG) {
                    Slog.v(TAG, "Done copying to engine, falling through");
                }
            } catch (IOException e) {
                // We lost our ability to communicate via the pipes.  That's worrying
                // but potentially recoverable; abandon this package's restore but
                // carry on with the next restore target.
                Slog.e(TAG, "Unable to route data for restore");
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_FULL_AGENT_ERROR,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                        mCurrentPackage.packageName, "I/O error on pipes");
                status = BackupTransport.AGENT_ERROR;
            } catch (Exception e) {
                // The transport threw; terminate the whole operation.  Closing
                // the sockets will wake up the engine and it will then tidy up the
                // remote end.
                Slog.e(TAG, "Transport failed during restore: " + e.getMessage());
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_FULL_RESTORE,
                        mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                status = BackupTransport.TRANSPORT_ERROR;
            } finally {
                // Close the transport pipes and *our* end of the engine pipe,
                // but leave the engine thread's end open so that it properly
                // hits EOF and winds up its operations.
                IoUtils.closeQuietly(mEnginePipes[1]);
                IoUtils.closeQuietly(mTransportPipes[0]);
                IoUtils.closeQuietly(mTransportPipes[1]);

                // Don't proceed until the engine has wound up operations
                mEngineThread.waitForResult();

                // Now we're really done with this one too
                IoUtils.closeQuietly(mEnginePipes[0]);

                // In all cases we want to remember whether we launched
                // the target app as part of our work so far.
                mDidLaunch = (mEngine.getAgent() != null);

                // If we hit a transport-level error, we are done with everything;
                // if we hit an agent error we just go back to running the queue.
                if (status == BackupTransport.TRANSPORT_OK) {
                    // Clean finish means we issue the restore-finished callback
                    nextState = UnifiedRestoreState.RESTORE_FINISHED;

                    // the engine bound the target's agent, so recover that binding
                    // to use for the callback.
                    mAgent = mEngine.getAgent();

                    // and the restored widget data, if any
                    mWidgetData = mEngine.getWidgetData();
                } else {
                    // Something went wrong somewhere.  Whether it was at the transport
                    // level is immaterial; we need to tell the transport to bail
                    try {
                        BackupTransportClient transport =
                                mTransportConnection.connectOrThrow(callerLogString);
                        transport.abortFullRestore();
                    } catch (Exception e) {
                        // transport itself is dead; make sure we handle this as a
                        // fatal error
                        Slog.e(TAG, "Transport threw from abortFullRestore: " + e.getMessage());
                        status = BackupTransport.TRANSPORT_ERROR;
                    }

                    // We also need to wipe the current target's data, as it's probably
                    // in an incoherent state.
                    backupManagerService.clearApplicationDataAfterRestoreFailure(
                            mCurrentPackage.packageName);

                    // Schedule the next state based on the nature of our failure
                    if (status == BackupTransport.TRANSPORT_ERROR) {
                        nextState = UnifiedRestoreState.FINAL;
                    } else {
                        nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    }
                }
                executeNextState(nextState);
                setRunning(false);
            }
        }

        // BackupRestoreTask interface, specifically for timeout handling

        @Override
        public void execute() { /* intentionally empty */ }

        @Override
        public void operationComplete(long result) { /* intentionally empty */ }

        // The app has timed out handling a restoring file
        @Override
        public void handleCancel(boolean cancelAll) {
            mOperationStorage.removeOperation(mEphemeralOpToken);
            if (DEBUG) {
                Slog.w(TAG, "Full-data restore target timed out; shutting down");
            }
            Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
            mBackupManagerMonitorEventSender.monitorEvent(
                    BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_TIMEOUT,
                    mCurrentPackage, BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                    monitoringExtras);
            mEngineThread.handleTimeout();

            IoUtils.closeQuietly(mEnginePipes[1]);
            mEnginePipes[1] = null;
            IoUtils.closeQuietly(mEnginePipes[0]);
            mEnginePipes[0] = null;
        }
    }

    // state FINAL : tear everything down and we're done.
    private void finalizeRestore() {
        if (MORE_DEBUG) {
            Slog.d(TAG, "finishing restore mObserver=" + mObserver);
        }

        String callerLogString = "PerformUnifiedRestoreTask.finalizeRestore()";
        try {
            BackupTransportClient transport =
                    mTransportConnection.connectOrThrow(callerLogString);
            transport.finishRestore();
        } catch (Exception e) {
            Slog.e(TAG, "Error finishing restore", e);
        }

        // Tell the observer we're done
        if (mObserver != null) {
            try {
                mObserver.restoreFinished(mStatus);
            } catch (RemoteException e) {
                Slog.d(TAG, "Restore observer died at restoreFinished");
            }
        }

        // Clear any ongoing session timeout.
        backupManagerService.getBackupHandler().removeMessages(MSG_RESTORE_SESSION_TIMEOUT);

        // If we have a PM token, we must under all circumstances be sure to
        // handshake when we've finished.
        if (mPmToken > 0) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "finishing PM token " + mPmToken);
            }
            try {
                backupManagerService.getPackageManagerBinder().finishPackageInstall(mPmToken,
                        mDidLaunch);
            } catch (RemoteException e) { /* can't happen */ }
        } else {
            // We were invoked via an active restore session, not by the Package
            // Manager, so start up the session timeout again.
            long restoreAgentTimeoutMillis =
                    mAgentTimeoutParameters.getRestoreSessionTimeoutMillis();
            backupManagerService.getBackupHandler().sendEmptyMessageDelayed(
                    MSG_RESTORE_SESSION_TIMEOUT,
                    restoreAgentTimeoutMillis);
        }

        if (mIsSystemRestore) {
            // Kick off any work that may be needed regarding app widget restores
            AppWidgetBackupBridge.systemRestoreFinished(mUserId);
        }

        // If this was a full-system restore, record the ancestral
        // dataset information
        if (mIsSystemRestore && mPmAgent != null) {
            backupManagerService.setAncestralPackages(mPmAgent.getRestoredPackages());
            backupManagerService.setAncestralToken(mToken);
            backupManagerService.setAncestralBackupDestination(
                    mBackupEligibilityRules.getBackupDestination());
            backupManagerService.writeRestoreTokens();
        }

        synchronized (backupManagerService.getPendingRestores()) {
            if (backupManagerService.getPendingRestores().size() > 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Starting next pending restore.");
                }
                PerformUnifiedRestoreTask task = backupManagerService.getPendingRestores().remove();
                backupManagerService.getBackupHandler().sendMessage(
                        backupManagerService.getBackupHandler().obtainMessage(
                                MSG_BACKUP_RESTORE_STEP, task));

            } else {
                backupManagerService.setRestoreInProgress(false);
                if (MORE_DEBUG) {
                    Slog.d(TAG, "No pending restores.");
                }
            }
        }

        Slog.i(TAG, "Restore complete.");
        Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
        mBackupManagerMonitorEventSender.monitorEvent(
                BackupManagerMonitor.LOG_EVENT_ID_RESTORE_COMPLETE,
                null,
                BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                monitoringExtras);

        mListener.onFinished(callerLogString);
    }

    /**
     * @param clearAppData - set to {@code true} if the backup agent had already been invoked when
     *     restore faied. So the app data may be in corrupted state and has to be cleared.
     */
    void keyValueAgentErrorCleanup(boolean clearAppData) {
        if (clearAppData) {
            // If the agent fails restore, it might have put the app's data
            // into an incoherent state.  For consistency we wipe its data
            // again in this case before continuing with normal teardown
            backupManagerService.clearApplicationDataAfterRestoreFailure(
                    mCurrentPackage.packageName);
        }
        keyValueAgentCleanup();
    }

    // TODO: clean up naming; this is now used at finish by both k/v and stream restores
    void keyValueAgentCleanup() {
        mBackupDataName.delete();
        mStageName.delete();
        try {
            if (mBackupData != null) {
                mBackupData.close();
            }
        } catch (IOException e) {
        }
        try {
            if (mNewState != null) {
                mNewState.close();
            }
        } catch (IOException e) {
        }
        mBackupData = mNewState = null;

        // if everything went okay, remember the recorded state now
        //
        // !!! TODO: the restored data could be migrated on the server
        // side into the current dataset.  In that case the new state file
        // we just created would reflect the data already extant in the
        // backend, so there'd be nothing more to do.  Until that happens,
        // however, we need to make sure that we record the data to the
        // current backend dataset.  (Yes, this means shipping the data over
        // the wire in both directions.  That's bad, but consistency comes
        // first, then efficiency.)  Once we introduce server-side data
        // migration to the newly-restored device's dataset, we will change
        // the following from a discard of the newly-written state to the
        // "correct" operation of renaming into the canonical state blob.
        mNewStateName.delete();                      // TODO: remove; see above comment

        // If this wasn't the PM pseudopackage, tear down the agent side
        if (mCurrentPackage.applicationInfo != null) {
            // unbind and tidy up even on timeout or failure
            try {
                backupManagerService.getActivityManager().unbindBackupAgent(
                        mCurrentPackage.applicationInfo);

                // The agent was probably running with a stub Application object,
                // which isn't a valid run mode for the main app logic.  Shut
                // down the app so that next time it's launched, it gets the
                // usual full initialization.  Note that this is only done for
                // full-system restores: when a single app has requested a restore,
                // it is explicitly not killed following that operation.
                //
                // We execute this kill when these conditions hold:
                //    1. it's not a system-uid process,
                //    2. the app did not request its own restore (mTargetPackage == null), and
                // either
                //    3a. the app is a full-data target (TYPE_FULL_STREAM) or
                //     b. the app does not state android:killAfterRestore="false" in its manifest
                final int appFlags = mCurrentPackage.applicationInfo.flags;
                final boolean killAfterRestore =
                        !UserHandle.isCore(mCurrentPackage.applicationInfo.uid)
                                && ((mRestoreDescription.getDataType()
                                == RestoreDescription.TYPE_FULL_STREAM)
                                || ((appFlags & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0));

                if (mTargetPackage == null && killAfterRestore) {
                    if (DEBUG) {
                        Slog.d(TAG, "Restore complete, killing host process of "
                                + mCurrentPackage.applicationInfo.processName);
                    }
                    backupManagerService.getActivityManager().killApplicationProcess(
                            mCurrentPackage.applicationInfo.processName,
                            mCurrentPackage.applicationInfo.uid);
                }
            } catch (RemoteException e) {
                // can't happen; we run in the same process as the activity manager
            }
        }

        // The caller is responsible for reestablishing the state machine; our
        // responsibility here is to clear the decks for whatever comes next.
        backupManagerService.getBackupHandler().removeMessages(MSG_RESTORE_OPERATION_TIMEOUT, this);
    }

    @Override
    public void operationComplete(long unusedResult) {
        mOperationStorage.removeOperation(mEphemeralOpToken);

        if (MORE_DEBUG) {
            Slog.i(TAG, "operationComplete() during restore: target="
                    + mCurrentPackage.packageName
                    + " state=" + mState);
        }

        final UnifiedRestoreState nextState;
        switch (mState) {
            case INITIAL:
                // We've just (manually) restored the PMBA.  It doesn't need the
                // additional restore-finished callback so we bypass that and go
                // directly to running the queue.
                nextState = UnifiedRestoreState.RUNNING_QUEUE;
                break;

            case RESTORE_KEYVALUE:
            case RESTORE_FULL: {
                // Okay, we've just heard back from the agent that it's done with
                // the restore itself.  We now have to send the same agent its
                // doRestoreFinished() callback, so roll into that state.
                nextState = UnifiedRestoreState.RESTORE_FINISHED;
                break;
            }

            case RESTORE_FINISHED: {
                // Okay, we're done with this package.  Tidy up and go on to the next
                // app in the queue.
                int size = (int) mBackupDataName.length();
                Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
                mBackupManagerMonitorEventSender.monitorEvent(
                        BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_RESTORE_FINISHED, mCurrentPackage,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
                EventLog.writeEvent(EventLogTags.RESTORE_PACKAGE,
                        mCurrentPackage.packageName, size);

                // Ask the agent for logs after doRestoreFinished() has completed executing to allow
                // it to finalize its logs.
                mBackupManagerMonitorEventSender.monitorAgentLoggingResults(mCurrentPackage,
                        mAgent);

                // Just go back to running the restore queue
                keyValueAgentCleanup();

                // If there was widget state associated with this app, get the OS to
                // incorporate it into current bookeeping and then pass that along to
                // the app as part of the restore-time work.
                if (mWidgetData != null) {
                    backupManagerService.restoreWidgetData(mCurrentPackage.packageName,
                            mWidgetData);
                }

                nextState = UnifiedRestoreState.RUNNING_QUEUE;
                break;
            }

            default: {
                // Some kind of horrible semantic error; we're in an unexpected state.
                // Back off hard and wind up.
                Slog.e(TAG, "Unexpected restore callback into state " + mState);
                keyValueAgentErrorCleanup(true);
                nextState = UnifiedRestoreState.FINAL;
                break;
            }
        }

        executeNextState(nextState);
    }

    // A call to agent.doRestore() or agent.doRestoreFinished() has timed out
    @Override
    public void handleCancel(boolean cancelAll) {
        mOperationStorage.removeOperation(mEphemeralOpToken);
        Slog.e(TAG, "Timeout restoring application " + mCurrentPackage.packageName);
        Bundle monitoringExtras = addRestoreOperationTypeToEvent(/*extras*/null);
        mBackupManagerMonitorEventSender.monitorEvent(
                BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_RESTORE_TIMEOUT,
                mCurrentPackage, BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT, monitoringExtras);
        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                mCurrentPackage.packageName, "restore timeout");
        // Handle like an agent that threw on invocation: wipe it and go on to the next
        keyValueAgentErrorCleanup(true);
        executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
    }

    @VisibleForTesting
    void executeNextState(UnifiedRestoreState nextState) {
        if (MORE_DEBUG) {
            Slog.i(TAG, " => executing next step on "
                    + this + " nextState=" + nextState);
        }
        mState = nextState;
        Message msg = backupManagerService.getBackupHandler().obtainMessage(
                MSG_BACKUP_RESTORE_STEP, this);
        backupManagerService.getBackupHandler().sendMessage(msg);
    }

    @VisibleForTesting
    UnifiedRestoreState getCurrentUnifiedRestoreStateForTesting() {
        return mState;
    }

    @VisibleForTesting
    void setCurrentUnifiedRestoreStateForTesting(UnifiedRestoreState state) {
        mState = state;
    }

    @VisibleForTesting
    void setStateDirForTesting(File stateDir) {
        mStateDir = stateDir;
    }

    @VisibleForTesting
    void initiateOneRestoreForTesting(PackageInfo app, long appVersionCode) {
        initiateOneRestore(app, appVersionCode);
    }

    // restore observer support
    void sendStartRestore(int numPackages) {
        if (mObserver != null) {
            try {
                mObserver.restoreStarting(numPackages);
            } catch (RemoteException e) {
                Slog.w(TAG, "Restore observer went away: startRestore");
                mObserver = null;
            }
        }
    }

    void sendOnRestorePackage(String name) {
        if (mObserver != null) {
            try {
                mObserver.onUpdate(mCount, name);
            } catch (RemoteException e) {
                Slog.d(TAG, "Restore observer died in onUpdate");
                mObserver = null;
            }
        }
    }

    void sendEndRestore() {
        if (mObserver != null) {
            try {
                mObserver.restoreFinished(mStatus);
            } catch (RemoteException e) {
                Slog.w(TAG, "Restore observer went away: endRestore");
                mObserver = null;
            }
        }
    }

    private Bundle addRestoreOperationTypeToEvent (@Nullable Bundle extra) {
        return mBackupManagerMonitorEventSender.putMonitoringExtra(
                extra,
                BackupManagerMonitor.EXTRA_LOG_OPERATION_TYPE, RESTORE);
    }
}
