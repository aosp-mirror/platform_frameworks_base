/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Set;

/**
 * Definition of the system service that performs backup/restore operations.
 *
 * <p>This class is responsible for handling user-aware operations and acts as a delegator, routing
 * incoming calls to the appropriate per-user {@link UserBackupManagerService} to handle the
 * corresponding backup/restore operation.
 */
public class BackupManagerService {
    public static final String TAG = "BackupManagerService";
    public static final boolean DEBUG = true;
    public static final boolean MORE_DEBUG = false;
    public static final boolean DEBUG_SCHEDULING = true;

    // File containing backup-enabled state. Contains a single byte to denote enabled status.
    // Nonzero is enabled; file missing or a zero byte is disabled.
    private static final String BACKUP_ENABLE_FILE = "backup_enabled";

    // The published binder is a singleton Trampoline object that calls through to the proper code.
    // This indirection lets us turn down the heavy implementation object on the fly without
    // disturbing binders that have been cached elsewhere in the system.
    private static Trampoline sInstance;

    static Trampoline getInstance() {
        // Always constructed during system bring up, so no need to lazy-init.
        return sInstance;
    }

    private final Context mContext;
    private UserBackupManagerService mUserBackupManagerService;

    /** Instantiate a new instance of {@link BackupManagerService}. */
    public BackupManagerService(
            Context context, Trampoline trampoline, HandlerThread backupThread) {
        // Set up our transport options and initialize the default transport
        SystemConfig systemConfig = SystemConfig.getInstance();
        Set<ComponentName> transportWhitelist = systemConfig.getBackupTransportWhitelist();
        if (transportWhitelist == null) {
            transportWhitelist = Collections.emptySet();
        }

        mContext = context;
        mUserBackupManagerService =
                UserBackupManagerService.createAndInitializeService(
                        context, trampoline, backupThread, transportWhitelist);
    }

    /**
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id on which the backup operation is being requested.
     * @param message A message to include in the exception if it is thrown.
     */
    private void enforceCallingPermissionOnUserId(int userId, String message) {
        if (Binder.getCallingUserHandle().getIdentifier() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        }
    }

    // TODO(b/118520567): Remove when tests are modified to use per-user instance.
    @VisibleForTesting
    void setUserBackupManagerService(UserBackupManagerService userBackupManagerService) {
        mUserBackupManagerService = userBackupManagerService;
    }

    /**
     * Called through Trampoline from {@link Lifecycle#onUnlockUser(int)}. We run the heavy work on
     * a background thread to keep the unlock time down.
     */
    public void unlockSystemUser() {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "backup enable");
        try {
            sInstance.setBackupEnabled(readBackupEnableState(UserHandle.USER_SYSTEM));
        } catch (RemoteException e) {
            // can't happen; it's a local object
        }
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Starts the backup service for user {@code userId} by creating a new instance of {@link
     * UserBackupManagerService} and registering it with this service.
     */
    // TODO(b/120212806): Add UserBackupManagerService initialization logic.
    void startServiceForUser(int userId) {
        // Intentionally empty.
    }

    /*
     * The following methods are implementations of IBackupManager methods called from Trampoline.
     * They delegate to the appropriate per-user instance of UserBackupManagerService to perform the
     * action on the passed in user. Currently this is a straight redirection (see TODO).
     */
    // TODO (b/118520567): Take in user id and call per-user instance of UserBackupManagerService.

    // ---------------------------------------------
    // BACKUP AGENT OPERATIONS
    // ---------------------------------------------

    /**
     * An app's backup agent calls this method to let the service know that there's new data to
     * backup for their app {@code packageName}. Only used for apps participating in key-value
     * backup.
     */
    public void dataChanged(String packageName) {
        mUserBackupManagerService.dataChanged(packageName);
    }

    /**
     * Callback: a requested backup agent has been instantiated. This should only be called from the
     * {@link ActivityManager}.
     */
    public void agentConnected(String packageName, IBinder agentBinder) {
        mUserBackupManagerService.agentConnected(packageName, agentBinder);
    }

    /**
     * Callback: a backup agent has failed to come up, or has unexpectedly quit. This should only be
     * called from the {@link ActivityManager}.
     */
    public void agentDisconnected(String packageName) {
        mUserBackupManagerService.agentDisconnected(packageName);
    }

    /**
     * Used by a currently-active backup agent to notify the service that it has completed its given
     * outstanding asynchronous backup/restore operation.
     */
    public void opComplete(int token, long result) {
        mUserBackupManagerService.opComplete(token, result);
    }

    // ---------------------------------------------
    // TRANSPORT OPERATIONS
    // ---------------------------------------------

    /** Run an initialize operation for the given transports {@code transportNames}. */
    public void initializeTransports(String[] transportNames, IBackupObserver observer) {
        mUserBackupManagerService.initializeTransports(transportNames, observer);
    }

    /**
     * Clear the given package {@code packageName}'s backup data from the transport {@code
     * transportName}.
     */
    public void clearBackupData(String transportName, String packageName) {
        mUserBackupManagerService.clearBackupData(transportName, packageName);
    }

    /** Return the name of the currently active transport. */
    public String getCurrentTransport() {
        return mUserBackupManagerService.getCurrentTransport();
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or {@code
     * null} if no transport selected or if the transport selected is not registered.
     */
    public ComponentName getCurrentTransportComponent() {
        return mUserBackupManagerService.getCurrentTransportComponent();
    }

    /** Report all known, available backup transports by name. */
    public String[] listAllTransports() {
        return mUserBackupManagerService.listAllTransports();
    }

    /** Report all known, available backup transports by {@link ComponentName}. */
    public ComponentName[] listAllTransportComponents() {
        return mUserBackupManagerService.listAllTransportComponents();
    }

    /** Report all system whitelisted transports. */
    public String[] getTransportWhitelist() {
        return mUserBackupManagerService.getTransportWhitelist();
    }

    /**
     * Update the attributes of the transport identified by {@code transportComponent}. If the
     * specified transport has not been bound at least once (for registration), this call will be
     * ignored. Only the host process of the transport can change its description, otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param transportComponent The identity of the transport being described.
     * @param name A {@link String} with the new name for the transport. This is NOT for
     *     identification. MUST NOT be {@code null}.
     * @param configurationIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's configuration UI. It may be
     *     {@code null} if the transport does not offer any user-facing configuration UI.
     * @param currentDestinationString A {@link String} describing the destination to which the
     *     transport is currently sending data. MUST NOT be {@code null}.
     * @param dataManagementIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's data-management UI. It may be
     *     {@code null} if the transport does not offer any user-facing data management UI.
     * @param dataManagementLabel A {@link String} to be used as the label for the transport's data
     *     management affordance. This MUST be {@code null} when dataManagementIntent is {@code
     *     null} and MUST NOT be {@code null} when dataManagementIntent is not {@code null}.
     * @throws SecurityException If the UID of the calling process differs from the package UID of
     *     {@code transportComponent} or if the caller does NOT have BACKUP permission.
     */
    public void updateTransportAttributes(
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            String dataManagementLabel) {
        mUserBackupManagerService.updateTransportAttributes(
                transportComponent,
                name,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                dataManagementLabel);
    }

    /**
     * Selects transport {@code transportName} and returns the previously selected transport.
     *
     * @deprecated Use {@link #selectBackupTransportAsync(ComponentName,
     *     ISelectBackupTransportCallback)} instead.
     */
    @Deprecated
    @Nullable
    public String selectBackupTransport(String transportName) {
        return mUserBackupManagerService.selectBackupTransport(transportName);
    }

    /**
     * Selects transport {@code transportComponent} asynchronously and notifies {@code listener}
     * with the result upon completion.
     */
    public void selectBackupTransportAsync(
            ComponentName transportComponent, ISelectBackupTransportCallback listener) {
        mUserBackupManagerService.selectBackupTransportAsync(transportComponent, listener);
    }

    /**
     * Supply the configuration intent for the given transport. If the name is not one of the
     * available transports, or if the transport does not supply any configuration UI, the method
     * returns {@code null}.
     */
    public Intent getConfigurationIntent(String transportName) {
        return mUserBackupManagerService.getConfigurationIntent(transportName);
    }

    /**
     * Supply the current destination string for the given transport. If the name is not one of the
     * registered transports the method will return null.
     *
     * <p>This string is used VERBATIM as the summary text of the relevant Settings item.
     *
     * @param transportName The name of the registered transport.
     * @return The current destination string or null if the transport is not registered.
     */
    public String getDestinationString(String transportName) {
        return mUserBackupManagerService.getDestinationString(transportName);
    }

    /** Supply the manage-data intent for the given transport. */
    public Intent getDataManagementIntent(String transportName) {
        return mUserBackupManagerService.getDataManagementIntent(transportName);
    }

    /**
     * Supply the menu label for affordances that fire the manage-data intent for the given
     * transport.
     */
    public String getDataManagementLabel(String transportName) {
        return mUserBackupManagerService.getDataManagementLabel(transportName);
    }

    // ---------------------------------------------
    // SETTINGS OPERATIONS
    // ---------------------------------------------

    /** Enable/disable the backup service. This is user-configurable via backup settings. */
    public void setBackupEnabled(@UserIdInt int userId, boolean enable) {
        enforceCallingPermissionOnUserId(userId, "setBackupEnabled");
        mUserBackupManagerService.setBackupEnabled(enable);
    }

    /** Enable/disable automatic restore of app data at install time. */
    public void setAutoRestore(boolean autoRestore) {
        mUserBackupManagerService.setAutoRestore(autoRestore);
    }

    /** Mark the backup service as having been provisioned (device has gone through SUW). */
    public void setBackupProvisioned(boolean provisioned) {
        mUserBackupManagerService.setBackupProvisioned(provisioned);
    }

    /**
     * Return {@code true} if the backup mechanism is currently enabled, else returns {@code false}.
     */
    public boolean isBackupEnabled(@UserIdInt int userId) {
        enforceCallingPermissionOnUserId(userId, "isBackupEnabled");
        return mUserBackupManagerService.isBackupEnabled();
    }

    // ---------------------------------------------
    // BACKUP OPERATIONS
    // ---------------------------------------------

    /** Checks if the given package {@code packageName} is eligible for backup. */
    public boolean isAppEligibleForBackup(String packageName) {
        return mUserBackupManagerService.isAppEligibleForBackup(packageName);
    }

    /**
     * Returns from the inputted packages {@code packages}, the ones that are eligible for backup.
     */
    public String[] filterAppsEligibleForBackup(String[] packages) {
        return mUserBackupManagerService.filterAppsEligibleForBackup(packages);
    }

    /**
     * Run a backup pass immediately for any key-value backup applications that have declared that
     * they have pending updates.
     */
    public void backupNow(@UserIdInt int userId) {
        enforceCallingPermissionOnUserId(userId, "backupNow");
        mUserBackupManagerService.backupNow();
    }

    /**
     * Requests a backup for the inputted {@code packages} with a specified callback {@link
     * IBackupManagerMonitor} for receiving events during the operation.
     */
    public int requestBackup(
            @UserIdInt int userId,
            String[] packages,
            IBackupObserver observer,
            IBackupManagerMonitor monitor,
            int flags) {
        enforceCallingPermissionOnUserId(userId, "requestBackup");
        return mUserBackupManagerService.requestBackup(packages, observer, monitor, flags);
    }

    /** Cancel all running backup operations. */
    public void cancelBackups(@UserIdInt int userId) {
        enforceCallingPermissionOnUserId(userId, "cancelBackups");
        mUserBackupManagerService.cancelBackups();
    }

    /**
     * Used by the {@link JobScheduler} to run a full backup when conditions are right. The model we
     * use is to perform one app backup per scheduled job execution, and to reschedule the job with
     * zero latency as long as conditions remain right and we still have work to do.
     *
     * @return Whether ongoing work will continue. The return value here will be passed along as the
     *     return value to the callback {@link JobService#onStartJob(JobParameters)}.
     */
    public boolean beginFullBackup(FullBackupJob scheduledJob) {
        return mUserBackupManagerService.beginFullBackup(scheduledJob);
    }

    /**
     * Used by the {@link JobScheduler} to end the current full backup task when conditions are no
     * longer met for running the full backup job.
     */
    public void endFullBackup() {
        mUserBackupManagerService.endFullBackup();
    }

    /**
     * Run a full backup pass for the given packages {@code packageNames}. Used by 'adb shell bmgr'.
     */
    public void fullTransportBackup(String[] packageNames) {
        mUserBackupManagerService.fullTransportBackup(packageNames);
    }

    // ---------------------------------------------
    // RESTORE OPERATIONS
    // ---------------------------------------------

    /**
     * Used to run a restore pass for an application that is being installed. This should only be
     * called from the {@link PackageManager}.
     */
    public void restoreAtInstall(String packageName, int token) {
        mUserBackupManagerService.restoreAtInstall(packageName, token);
    }

    /**
     * Begin a restore for the specified package {@code packageName} using the specified transport
     * {@code transportName}.
     */
    public IRestoreSession beginRestoreSession(String packageName, String transportName) {
        return mUserBackupManagerService.beginRestoreSession(packageName, transportName);
    }

    /**
     * Get the restore-set token for the best-available restore set for this {@code packageName}:
     * the active set if possible, else the ancestral one. Returns zero if none available.
     */
    public long getAvailableRestoreToken(String packageName) {
        return mUserBackupManagerService.getAvailableRestoreToken(packageName);
    }

    // ---------------------------------------------
    // ADB BACKUP/RESTORE OPERATIONS
    // ---------------------------------------------

    /** Sets the backup password used when running adb backup. */
    public boolean setBackupPassword(String currentPassword, String newPassword) {
        return mUserBackupManagerService.setBackupPassword(currentPassword, newPassword);
    }

    /** Returns {@code true} if adb backup was run with a password, else returns {@code false}. */
    public boolean hasBackupPassword() {
        return mUserBackupManagerService.hasBackupPassword();
    }

    /**
     * Used by 'adb backup' to run a backup pass for packages {@code packageNames} supplied via the
     * command line, writing the resulting data stream to the supplied {@code fd}. This method is
     * synchronous and does not return to the caller until the backup has been completed. It
     * requires on-screen confirmation by the user.
     */
    public void adbBackup(
            @UserIdInt int userId,
            ParcelFileDescriptor fd,
            boolean includeApks,
            boolean includeObbs,
            boolean includeShared,
            boolean doWidgets,
            boolean doAllApps,
            boolean includeSystem,
            boolean doCompress,
            boolean doKeyValue,
            String[] packageNames) {
        enforceCallingPermissionOnUserId(userId, "adbBackup");

        mUserBackupManagerService.adbBackup(
                fd,
                includeApks,
                includeObbs,
                includeShared,
                doWidgets,
                doAllApps,
                includeSystem,
                doCompress,
                doKeyValue,
                packageNames);
    }

    /**
     * Used by 'adb restore' to run a restore pass reading from the supplied {@code fd}. This method
     * is synchronous and does not return to the caller until the restore has been completed. It
     * requires on-screen confirmation by the user.
     */
    public void adbRestore(@UserIdInt int userId, ParcelFileDescriptor fd) {
        enforceCallingPermissionOnUserId(userId, "setBackupEnabled");

        mUserBackupManagerService.adbRestore(fd);
    }

    /**
     * Confirm that the previously requested adb backup/restore operation can proceed. This is used
     * to require a user-facing disclosure about the operation.
     */
    public void acknowledgeAdbBackupOrRestore(
            int token,
            boolean allow,
            String currentPassword,
            String encryptionPassword,
            IFullBackupRestoreObserver observer) {
        mUserBackupManagerService.acknowledgeAdbBackupOrRestore(
                token, allow, currentPassword, encryptionPassword, observer);
    }

    // ---------------------------------------------
    //  SERVICE OPERATIONS
    // ---------------------------------------------

    /** Prints service state for 'dumpsys backup'. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mUserBackupManagerService.dump(fd, pw, args);
    }

    private static boolean readBackupEnableState(int userId) {
        File base = new File(Environment.getDataDirectory(), "backup");
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        if (enableFile.exists()) {
            try (FileInputStream fin = new FileInputStream(enableFile)) {
                int state = fin.read();
                return state != 0;
            } catch (IOException e) {
                // can't read the file; fall through to assume disabled
                Slog.e(TAG, "Cannot read enable state; assuming disabled");
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "isBackupEnabled() => false due to absent settings file");
            }
        }
        return false;
    }

    static void writeBackupEnableState(boolean enable, int userId) {
        File base = new File(Environment.getDataDirectory(), "backup");
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        File stage = new File(base, BACKUP_ENABLE_FILE + "-stage");
        try (FileOutputStream fout = new FileOutputStream(stage)) {
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            // will be synced immediately by the try-with-resources call to close()
        } catch (IOException | RuntimeException e) {
            Slog.e(
                    TAG,
                    "Unable to record backup enable state; reverting to disabled: "
                            + e.getMessage());
            enableFile.delete();
            stage.delete();
        }
    }

    /** Implementation to receive lifecycle event callbacks for system services. */
    public static final class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
            sInstance = new Trampoline(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.BACKUP_SERVICE, sInstance);
        }

        @Override
        public void onUnlockUser(int userId) {
            if (userId == UserHandle.USER_SYSTEM) {
                sInstance.initializeServiceAndUnlockSystemUser();
            } else {
                sInstance.startServiceForUser(userId);
            }
        }
    }
}
