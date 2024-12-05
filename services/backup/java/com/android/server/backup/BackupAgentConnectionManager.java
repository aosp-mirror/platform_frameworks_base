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
 * limitations under the License.
 */

package com.android.server.backup;

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ApplicationThreadConstants;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.backup.BackupAnnotations;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.backup.internal.LifecycleOperationStorage;

import java.util.Set;

/**
 * Handles the lifecycle of {@link IBackupAgent}s that the {@link UserBackupManagerService}
 * communicates with.
 *
 * <p>There can only be one agent that's connected to at a time.
 *
 * <p>There should be only one instance of this class per {@link UserBackupManagerService}.
 */
public class BackupAgentConnectionManager {

    /**
     * Enables the OS making a decision on whether backup restricted mode should be used for apps
     * that haven't explicitly opted in or out. See
     * {@link android.content.pm.PackageManager#PROPERTY_USE_RESTRICTED_BACKUP_MODE} for details.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.BAKLAVA)
    public static final long OS_DECIDES_BACKUP_RESTRICTED_MODE = 376661510;

    // The thread performing the sequence of queued backups binds to each app's agent
    // in succession.  Bind notifications are asynchronously delivered through the
    // Activity Manager; use this lock object to signal when a requested binding has
    // completed.
    private final Object mAgentConnectLock = new Object();
    @GuardedBy("mAgentConnectLock")
    @Nullable
    private BackupAgentConnection mCurrentConnection;

    private final ArraySet<String> mRestoreNoRestrictedModePackages = new ArraySet<>();
    private final ArraySet<String> mBackupNoRestrictedModePackages = new ArraySet<>();

    private final IActivityManager mActivityManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final LifecycleOperationStorage mOperationStorage;
    private final PackageManager mPackageManager;
    private final UserBackupManagerService mUserBackupManagerService;
    private final int mUserId;
    private final String mUserIdMsg;

    BackupAgentConnectionManager(LifecycleOperationStorage operationStorage,
            PackageManager packageManager, UserBackupManagerService userBackupManagerService,
            int userId) {
        mActivityManager = ActivityManager.getService();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mOperationStorage = operationStorage;
        mPackageManager = packageManager;
        mUserBackupManagerService = userBackupManagerService;
        mUserId = userId;
        mUserIdMsg = "[UserID:" + userId + "] ";
    }

    private static final class BackupAgentConnection {
        public final ApplicationInfo appInfo;
        public final int backupMode;
        public final boolean inRestrictedMode;
        public IBackupAgent backupAgent;
        public boolean connecting = true; // Assume we are trying to connect on creation.

        private BackupAgentConnection(ApplicationInfo appInfo, int backupMode,
                boolean inRestrictedMode) {
            this.appInfo = appInfo;
            this.backupMode = backupMode;
            this.inRestrictedMode = inRestrictedMode;
        }
    }

    /**
     * Fires off a backup agent, blocking until it attaches (i.e. ActivityManager calls
     * {@link #agentConnected(String, IBinder)}) or until this operation times out.
     *
     * @param backupMode a {@code BACKUP_MODE} from {@link android.app.ApplicationThreadConstants}.
     */
    @Nullable
    public IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int backupMode,
            @BackupAnnotations.BackupDestination int backupDestination) {
        if (app == null) {
            Slog.w(TAG, mUserIdMsg + "bindToAgentSynchronous for null app");
            return null;
        }

        synchronized (mAgentConnectLock) {
            boolean useRestrictedMode = shouldUseRestrictedBackupModeForPackage(backupMode,
                    app.packageName);
            if (mCurrentConnection != null) {
                Slog.e(TAG, mUserIdMsg + "binding to new agent before unbinding from old one: "
                        + mCurrentConnection.appInfo.packageName);
            }
            mCurrentConnection = new BackupAgentConnection(app, backupMode, useRestrictedMode);

            // bindBackupAgent() is an async API. It will kick off the app's process and call
            // agentConnected() when it receives the agent from the app.
            boolean startedBindSuccessfully = false;
            try {
                startedBindSuccessfully = mActivityManager.bindBackupAgent(app.packageName,
                        backupMode, mUserId, backupDestination, useRestrictedMode);
            } catch (RemoteException e) {
                // can't happen - ActivityManager is local
            }

            if (!startedBindSuccessfully) {
                Slog.w(TAG, mUserIdMsg + "bind request failed for " + app.packageName);
                mCurrentConnection = null;
            } else {
                Slog.d(TAG, mUserIdMsg + "awaiting agent for " + app.packageName);

                // Wait 10 seconds for the agent and then time out if we still haven't bound to it.
                long timeoutMark = System.currentTimeMillis() + 10 * 1000;
                while (mCurrentConnection != null && mCurrentConnection.connecting && (
                        System.currentTimeMillis() < timeoutMark)) {
                    try {
                        mAgentConnectLock.wait(5000);
                    } catch (InterruptedException e) {
                        Slog.w(TAG, mUserIdMsg + "Interrupted: " + e);
                        mCurrentConnection = null;
                    }
                }
            }

            if (mCurrentConnection != null) {
                if (!mCurrentConnection.connecting) {
                    return mCurrentConnection.backupAgent;
                }
                // If we are still connecting, we've timed out.
                Slog.w(TAG, mUserIdMsg + "Timeout waiting for agent " + app);
                mCurrentConnection = null;
            }

            mActivityManagerInternal.clearPendingBackup(mUserId);
            return null;
        }
    }

    /**
     * Tell the ActivityManager that we are done with the {@link IBackupAgent} of this {@code app}.
     * It will tell the app to destroy the agent.
     *
     * <p>If {@code allowKill} is set, this will kill the app's process if the app is in restricted
     * mode or if it was started for restore and specified {@code android:killAfterRestore} in its
     * manifest.
     *
     * @see #shouldUseRestrictedBackupModeForPackage(int, String)
     */
    public void unbindAgent(ApplicationInfo app, boolean allowKill) {
        if (app == null) {
            Slog.w(TAG, mUserIdMsg + "unbindAgent for null app");
            return;
        }

        synchronized (mAgentConnectLock) {
            // Even if we weren't expecting to be bound to this agent, we should still call
            // ActivityManager just in case. It will ignore the call if it also wasn't expecting it.
            try {
                mActivityManager.unbindBackupAgent(app);

                // Evaluate this before potentially setting mCurrentConnection = null.
                boolean willKill = allowKill && shouldKillAppOnUnbind(app);

                if (mCurrentConnection == null) {
                    Slog.w(TAG, mUserIdMsg + "unbindAgent but no current connection");
                } else if (!mCurrentConnection.appInfo.packageName.equals(app.packageName)) {
                    Slog.w(TAG,
                            mUserIdMsg + "unbindAgent for unexpected package: " + app.packageName
                                    + " expected: " + mCurrentConnection.appInfo.packageName);
                } else {
                    mCurrentConnection = null;
                }

                if (willKill) {
                    Slog.i(TAG, mUserIdMsg + "Killing agent host process");
                    mActivityManager.killApplicationProcess(app.processName, app.uid);
                }
            } catch (RemoteException e) {
                // Can't happen - activity manager is local
            }
        }
    }

    @GuardedBy("mAgentConnectLock")
    private boolean shouldKillAppOnUnbind(ApplicationInfo app) {
        // We don't ask system UID processes to be killed.
        if (UserHandle.isCore(app.uid)) {
            return false;
        }

        // If the app is in restricted mode or if we're not sure if it is because our internal
        // state is messed up, we need to avoid it being stuck in it.
        if (mCurrentConnection == null || mCurrentConnection.inRestrictedMode) {
            return true;
        }

        // App was doing restore and asked to be killed afterwards.
        return isBackupModeRestore(mCurrentConnection.backupMode)
                && (app.flags & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0;
    }

    /**
     * Callback: a requested backup agent has been instantiated. This should only be called from
     * the {@link ActivityManager} when it's telling us that an agent is ready after a call to
     * {@link #bindToAgentSynchronous(ApplicationInfo, int, int)}.
     */
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized (mAgentConnectLock) {
            if (getCallingUid() != Process.SYSTEM_UID) {
                Slog.w(TAG, mUserIdMsg + "Non-system process uid=" + getCallingUid()
                        + " claiming agent connected");
                return;
            }

            Slog.d(TAG, mUserIdMsg + "agentConnected pkg=" + packageName + " agent=" + agentBinder);
            if (mCurrentConnection == null) {
                Slog.w(TAG, mUserIdMsg + "was not expecting connection");
            } else if (!mCurrentConnection.appInfo.packageName.equals(packageName)) {
                Slog.w(TAG, mUserIdMsg + "got agent for unexpected package=" + packageName);
            } else {
                mCurrentConnection.backupAgent = IBackupAgent.Stub.asInterface(agentBinder);
                mCurrentConnection.connecting = false;
            }

            mAgentConnectLock.notifyAll();
        }
    }

    /**
     * Callback: a backup agent has failed to come up, or has unexpectedly quit. If the agent failed
     * to come up in the first place, the agentBinder argument will be {@code null}. This should
     * only be called from the {@link ActivityManager}.
     */
    public void agentDisconnected(String packageName) {
        synchronized (mAgentConnectLock) {
            if (getCallingUid() != Process.SYSTEM_UID) {
                Slog.w(TAG, mUserIdMsg + "Non-system process uid=" + getCallingUid()
                        + " claiming agent disconnected");
                return;
            }

            Slog.w(TAG, mUserIdMsg + "agentDisconnected: the backup agent for " + packageName
                    + " died: cancel current operations");

            // Only abort the current connection if the agent we were expecting or already
            // connected to has disconnected.
            if (mCurrentConnection != null && mCurrentConnection.appInfo.packageName.equals(
                    packageName)) {
                mCurrentConnection = null;
            }

            // Offload operation cancellation off the main thread as the cancellation callbacks
            // might call out to BackupTransport. Other operations started on the same package
            // before the cancellation callback has executed will also be cancelled by the callback.
            Runnable cancellationRunnable = () -> {
                // handleCancel() causes the PerformFullTransportBackupTask to go on to
                // tearDownAgentAndKill: that will unbindBackupAgent in the Activity Manager, so
                // that the package being backed up doesn't get stuck in restricted mode until the
                // backup time-out elapses.
                for (int token : mOperationStorage.operationTokensForPackage(packageName)) {
                    if (MORE_DEBUG) {
                        Slog.d(TAG,
                                mUserIdMsg + "agentDisconnected: will handleCancel(all) for token:"
                                        + Integer.toHexString(token));
                    }
                    mUserBackupManagerService.handleCancel(token, true /* cancelAll */);
                }
            };
            getThreadForCancellation(cancellationRunnable).start();

            mAgentConnectLock.notifyAll();
        }
    }

    /**
     * Marks the given set of packages as packages that should not be put into restricted mode if
     * they are started for the given {@link BackupAnnotations.OperationType}.
     */
    public void setNoRestrictedModePackages(Set<String> packageNames,
            @BackupAnnotations.OperationType int opType) {
        if (opType == BackupAnnotations.OperationType.BACKUP) {
            mBackupNoRestrictedModePackages.clear();
            mBackupNoRestrictedModePackages.addAll(packageNames);
        } else if (opType == BackupAnnotations.OperationType.RESTORE) {
            mRestoreNoRestrictedModePackages.clear();
            mRestoreNoRestrictedModePackages.addAll(packageNames);
        } else {
            throw new IllegalArgumentException("opType must be BACKUP or RESTORE");
        }
    }

    /**
     * Clears the list of packages that should not be put into restricted mode for either backup or
     * restore.
     */
    public void clearNoRestrictedModePackages() {
        mBackupNoRestrictedModePackages.clear();
        mRestoreNoRestrictedModePackages.clear();
    }

    /**
     * If the app has specified {@link PackageManager#PROPERTY_USE_RESTRICTED_BACKUP_MODE}, then
     * its value is returned. If it hasn't and it targets an SDK below
     * {@link Build.VERSION_CODES#BAKLAVA} then returns true. If it targets a newer SDK, then
     * returns the decision made by the {@link android.app.backup.BackupTransport}.
     *
     * <p>When this method is called, we should have already asked the transport and cached its
     * response in {@link #mBackupNoRestrictedModePackages} or
     * {@link #mRestoreNoRestrictedModePackages} so this method will immediately return without
     * any IPC to the transport.
     */
    private boolean shouldUseRestrictedBackupModeForPackage(
            @BackupAnnotations.OperationType int mode, String packageName) {
        // Key/Value apps are never put in restricted mode.
        if (mode == ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL
                || mode == ApplicationThreadConstants.BACKUP_MODE_RESTORE) {
            return false;
        }

        if (!Flags.enableRestrictedModeChanges()) {
            return true;
        }

        try {
            PackageManager.Property property = mPackageManager.getPropertyAsUser(
                    PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE,
                    packageName, /* className= */ null, mUserId);
            if (property.isBoolean()) {
                // If the package has explicitly specified, we won't ask the transport.
                return property.getBoolean();
            } else {
                Slog.w(TAG,
                        PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE + "must be a boolean.");
            }
        } catch (NameNotFoundException e) {
            // This is expected when the package has not defined the property in its manifest.
        }

        // The package has not specified the property. The behavior depends on the package's
        // targetSdk.
        // <36 gets the old behavior of always using restricted mode.
        if (!CompatChanges.isChangeEnabled(OS_DECIDES_BACKUP_RESTRICTED_MODE, packageName,
                UserHandle.of(mUserId))) {
            return true;
        }

        // Apps targeting >=36 get the behavior decided by the transport.
        // By this point, we should have asked the transport and cached its decision.
        if ((mode == ApplicationThreadConstants.BACKUP_MODE_FULL
                && mBackupNoRestrictedModePackages.contains(packageName)) || (
                mode == ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL
                        && mRestoreNoRestrictedModePackages.contains(packageName))) {
            Slog.d(TAG, "Transport requested no restricted mode for: " + packageName);
            return false;
        }
        return true;
    }

    private static boolean isBackupModeRestore(int backupMode) {
        return backupMode == ApplicationThreadConstants.BACKUP_MODE_RESTORE
                || backupMode == ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL;
    }

    @VisibleForTesting
    Thread getThreadForCancellation(Runnable operation) {
        return new Thread(operation, /* operationName */ "agent-disconnected");
    }

    @VisibleForTesting
    int getCallingUid() {
        return Binder.getCallingUid();
    }
}
