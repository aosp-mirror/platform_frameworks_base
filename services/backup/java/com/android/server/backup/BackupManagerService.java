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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.backup.BackupManager;
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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;
import com.android.server.SystemService;

import java.io.FileDescriptor;
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

    // The published binder is a singleton Trampoline object that calls through to the proper code.
    // This indirection lets us turn down the heavy implementation object on the fly without
    // disturbing binders that have been cached elsewhere in the system.
    private static Trampoline sInstance;

    static Trampoline getInstance() {
        // Always constructed during system bring up, so no need to lazy-init.
        return sInstance;
    }

    private final Context mContext;
    private final Trampoline mTrampoline;
    private final HandlerThread mBackupThread;

    // Keeps track of all unlocked users registered with this service. Indexed by user id.
    private final SparseArray<UserBackupManagerService> mServiceUsers = new SparseArray<>();

    private Set<ComponentName> mTransportWhitelist;

    /** Instantiate a new instance of {@link BackupManagerService}. */
    public BackupManagerService(
            Context context, Trampoline trampoline, HandlerThread backupThread) {
        mContext = checkNotNull(context);
        mTrampoline = checkNotNull(trampoline);
        mBackupThread = checkNotNull(backupThread);

        // Set up our transport options.
        SystemConfig systemConfig = SystemConfig.getInstance();
        mTransportWhitelist = systemConfig.getBackupTransportWhitelist();
        if (mTransportWhitelist == null) {
            mTransportWhitelist = Collections.emptySet();
        }
    }

    /**
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id on which the backup operation is being requested.
     * @param message A message to include in the exception if it is thrown.
     */
    private void enforceCallingPermissionOnUserId(@UserIdInt int userId, String message) {
        if (Binder.getCallingUserHandle().getIdentifier() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        }
    }

    // ---------------------------------------------
    // USER LIFECYCLE CALLBACKS
    // ---------------------------------------------

    /**
     * Starts the backup service for user {@code userId} by creating a new instance of {@link
     * UserBackupManagerService} and registering it with this service.
     */
    @VisibleForTesting
    protected void startServiceForUser(int userId) {
        if (mServiceUsers.get(userId) != null) {
            Slog.i(TAG, "userId " + userId + " already started, so not starting again");
            return;
        }

        UserBackupManagerService userBackupManagerService =
                UserBackupManagerService.createAndInitializeService(
                        userId, mContext, mTrampoline, mTransportWhitelist);
        startServiceForUser(userId, userBackupManagerService);
    }

    /**
     * Starts the backup service for user {@code userId} by registering its instance of {@link
     * UserBackupManagerService} with this service and setting enabled state.
     */
    void startServiceForUser(int userId, UserBackupManagerService userBackupManagerService) {
        mServiceUsers.put(userId, userBackupManagerService);

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "backup enable");
        userBackupManagerService.initializeBackupEnableState();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /** Stops the backup service for user {@code userId} when the user is stopped. */
    @VisibleForTesting
    protected void stopServiceForUser(int userId) {
        UserBackupManagerService userBackupManagerService = mServiceUsers.removeReturnOld(userId);

        if (userBackupManagerService != null) {
            userBackupManagerService.tearDownService();

            KeyValueBackupJob.cancel(userId, mContext);
            FullBackupJob.cancel(userId, mContext);
        }
    }

    /**
     *  Returns a lst of users currently unlocked that have a
     *  {@link UserBackupManagerService} registered.
     */
    @VisibleForTesting
    public SparseArray<UserBackupManagerService> getServiceUsers() {
        return mServiceUsers;
    }

    /**
     * Returns the {@link UserBackupManagerService} instance for the specified user {@code userId}.
     * If the user is not registered with the service (either the user is locked or not eligible for
     * the backup service) then return {@code null}.
     *
     * @param userId The id of the user to retrieve its instance of {@link
     *     UserBackupManagerService}.
     * @param caller A {@link String} identifying the caller for logging purposes.
     * @throws SecurityException if {@code userId} is different from the calling user id and the
     *     caller does NOT have the android.permission.INTERACT_ACROSS_USERS_FULL permission.
     */
    @Nullable
    @VisibleForTesting
    UserBackupManagerService getServiceForUserIfCallerHasPermission(
            @UserIdInt int userId, String caller) {
        enforceCallingPermissionOnUserId(userId, caller);
        UserBackupManagerService userBackupManagerService = mServiceUsers.get(userId);
        if (userBackupManagerService == null) {
            Slog.w(TAG, "Called " + caller + " for unknown user: " + userId);
        }
        return userBackupManagerService;
    }

    /*
     * The following methods are implementations of IBackupManager methods called from Trampoline.
     * They delegate to the appropriate per-user instance of UserBackupManagerService to perform the
     * action on the passed in user. Currently this is a straight redirection (see TODO).
     */
    // TODO (b/118520567): Stop hardcoding system user when we pass in user id as a parameter

    // ---------------------------------------------
    // BACKUP AGENT OPERATIONS
    // ---------------------------------------------

    /**
     * An app's backup agent calls this method to let the service know that there's new data to
     * backup for their app {@code packageName}. Only used for apps participating in key-value
     * backup.
     */
    public void dataChanged(@UserIdInt int userId, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "dataChanged()");

        if (userBackupManagerService != null) {
            userBackupManagerService.dataChanged(packageName);
        }
    }

    /**
     * Callback: a requested backup agent has been instantiated. This should only be called from the
     * {@link ActivityManager}.
     */
    public void agentConnected(@UserIdInt int userId, String packageName, IBinder agentBinder) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "agentConnected()");

        if (userBackupManagerService != null) {
            userBackupManagerService.agentConnected(packageName, agentBinder);
        }
    }

    /**
     * Callback: a backup agent has failed to come up, or has unexpectedly quit. This should only be
     * called from the {@link ActivityManager}.
     */
    public void agentDisconnected(@UserIdInt int userId, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "agentDisconnected()");

        if (userBackupManagerService != null) {
            userBackupManagerService.agentDisconnected(packageName);
        }
    }

    /**
     * Used by a currently-active backup agent to notify the service that it has completed its given
     * outstanding asynchronous backup/restore operation.
     */
    public void opComplete(@UserIdInt int userId, int token, long result) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "opComplete()");

        if (userBackupManagerService != null) {
            userBackupManagerService.opComplete(token, result);
        }
    }

    // ---------------------------------------------
    // TRANSPORT OPERATIONS
    // ---------------------------------------------

    /** Run an initialize operation for the given transports {@code transportNames}. */
    public void initializeTransports(
            @UserIdInt int userId, String[] transportNames, IBackupObserver observer) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "initializeTransports()");

        if (userBackupManagerService != null) {
            userBackupManagerService.initializeTransports(transportNames, observer);
        }
    }

    /**
     * Clear the given package {@code packageName}'s backup data from the transport {@code
     * transportName}.
     */
    public void clearBackupData(@UserIdInt int userId, String transportName, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "clearBackupData()");

        if (userBackupManagerService != null) {
            userBackupManagerService.clearBackupData(transportName, packageName);
        }
    }

    /** Return the name of the currently active transport. */
    @Nullable
    public String getCurrentTransport(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getCurrentTransport()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getCurrentTransport();
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or {@code
     * null} if no transport selected or if the transport selected is not registered.
     */
    @Nullable
    public ComponentName getCurrentTransportComponent(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getCurrentTransportComponent()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getCurrentTransportComponent();
    }

    /** Report all known, available backup transports by name. */
    @Nullable
    public String[] listAllTransports(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "listAllTransports()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.listAllTransports();
    }

    /** Report all known, available backup transports by {@link ComponentName}. */
    @Nullable
    public ComponentName[] listAllTransportComponents(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "listAllTransportComponents()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.listAllTransportComponents();
    }

    /** Report all system whitelisted transports. */
    @Nullable
    public String[] getTransportWhitelist() {
        // No permission check, intentionally.
        String[] whitelistedTransports = new String[mTransportWhitelist.size()];
        int i = 0;
        for (ComponentName component : mTransportWhitelist) {
            whitelistedTransports[i] = component.flattenToShortString();
            i++;
        }
        return whitelistedTransports;
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
            @UserIdInt int userId,
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            String dataManagementLabel) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "updateTransportAttributes()");

        if (userBackupManagerService != null) {
            userBackupManagerService.updateTransportAttributes(
                    transportComponent,
                    name,
                    configurationIntent,
                    currentDestinationString,
                    dataManagementIntent,
                    dataManagementLabel);
        }
    }

    /**
     * Selects transport {@code transportName} and returns the previously selected transport.
     *
     * @deprecated Use {@link #selectBackupTransportAsync(ComponentName,
     *     ISelectBackupTransportCallback)} instead.
     */
    @Deprecated
    @Nullable
    public String selectBackupTransport(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "selectBackupTransport()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.selectBackupTransport(transportName);
    }

    /**
     * Selects transport {@code transportComponent} asynchronously and notifies {@code listener}
     * with the result upon completion.
     */
    public void selectBackupTransportAsync(
            @UserIdInt int userId,
            ComponentName transportComponent,
            ISelectBackupTransportCallback listener) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "selectBackupTransportAsync()");

        if (userBackupManagerService != null) {
            userBackupManagerService.selectBackupTransportAsync(transportComponent, listener);
        }
    }

    /**
     * Supply the configuration intent for the given transport. If the name is not one of the
     * available transports, or if the transport does not supply any configuration UI, the method
     * returns {@code null}.
     */
    @Nullable
    public Intent getConfigurationIntent(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getConfigurationIntent()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getConfigurationIntent(transportName);
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
    @Nullable
    public String getDestinationString(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getDestinationString()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getDestinationString(transportName);
    }

    /** Supply the manage-data intent for the given transport. */
    @Nullable
    public Intent getDataManagementIntent(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getDataManagementIntent()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getDataManagementIntent(transportName);
    }

    /**
     * Supply the menu label for affordances that fire the manage-data intent for the given
     * transport.
     */
    @Nullable
    public String getDataManagementLabel(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getDataManagementLabel()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getDataManagementLabel(transportName);
    }

    // ---------------------------------------------
    // SETTINGS OPERATIONS
    // ---------------------------------------------

    /** Enable/disable the backup service. This is user-configurable via backup settings. */
    public void setBackupEnabled(@UserIdInt int userId, boolean enable) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "setBackupEnabled()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setBackupEnabled(enable);
        }
    }

    /** Enable/disable automatic restore of app data at install time. */
    public void setAutoRestore(@UserIdInt int userId, boolean autoRestore) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "setAutoRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setAutoRestore(autoRestore);
        }
    }

    /**
     * Return {@code true} if the backup mechanism is currently enabled, else returns {@code false}.
     */
    public boolean isBackupEnabled(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "isBackupEnabled()");

        return userBackupManagerService != null && userBackupManagerService.isBackupEnabled();
    }

    // ---------------------------------------------
    // BACKUP OPERATIONS
    // ---------------------------------------------

    /** Checks if the given package {@code packageName} is eligible for backup. */
    public boolean isAppEligibleForBackup(@UserIdInt int userId, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "isAppEligibleForBackup()");

        return userBackupManagerService != null
                && userBackupManagerService.isAppEligibleForBackup(packageName);
    }

    /**
     * Returns from the inputted packages {@code packages}, the ones that are eligible for backup.
     */
    @Nullable
    public String[] filterAppsEligibleForBackup(@UserIdInt int userId, String[] packages) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "filterAppsEligibleForBackup()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.filterAppsEligibleForBackup(packages);
    }

    /**
     * Run a backup pass immediately for any key-value backup applications that have declared that
     * they have pending updates.
     */
    public void backupNow(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "backupNow()");

        if (userBackupManagerService != null) {
            userBackupManagerService.backupNow();
        }
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
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "requestBackup()");

        return userBackupManagerService == null
                ? BackupManager.ERROR_BACKUP_NOT_ALLOWED
                : userBackupManagerService.requestBackup(packages, observer, monitor, flags);
    }

    /** Cancel all running backup operations. */
    public void cancelBackups(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "cancelBackups()");

        if (userBackupManagerService != null) {
            userBackupManagerService.cancelBackups();
        }
    }

    /**
     * Used by the {@link JobScheduler} to run a full backup when conditions are right. The model we
     * use is to perform one app backup per scheduled job execution, and to reschedule the job with
     * zero latency as long as conditions remain right and we still have work to do.
     *
     * @return Whether ongoing work will continue. The return value here will be passed along as the
     *     return value to the callback {@link JobService#onStartJob(JobParameters)}.
     */
    public boolean beginFullBackup(@UserIdInt int userId, FullBackupJob scheduledJob) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "beginFullBackup()");

        return userBackupManagerService != null
                && userBackupManagerService.beginFullBackup(scheduledJob);
    }

    /**
     * Used by the {@link JobScheduler} to end the current full backup task when conditions are no
     * longer met for running the full backup job.
     */
    public void endFullBackup(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "endFullBackup()");

        if (userBackupManagerService != null) {
            userBackupManagerService.endFullBackup();
        }
    }

    /**
     * Run a full backup pass for the given packages {@code packageNames}. Used by 'adb shell bmgr'.
     */
    public void fullTransportBackup(@UserIdInt int userId, String[] packageNames) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "fullTransportBackup()");

        if (userBackupManagerService != null) {
            userBackupManagerService.fullTransportBackup(packageNames);
        }
    }

    // ---------------------------------------------
    // RESTORE OPERATIONS
    // ---------------------------------------------

    /**
     * Used to run a restore pass for an application that is being installed. This should only be
     * called from the {@link PackageManager}.
     */
    public void restoreAtInstall(@UserIdInt int userId, String packageName, int token) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "restoreAtInstall()");

        if (userBackupManagerService != null) {
            userBackupManagerService.restoreAtInstall(packageName, token);
        }
    }

    /**
     * Begin a restore for the specified package {@code packageName} using the specified transport
     * {@code transportName}.
     */
    @Nullable
    public IRestoreSession beginRestoreSession(
            @UserIdInt int userId, String packageName, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "beginRestoreSession()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.beginRestoreSession(packageName, transportName);
    }

    /**
     * Get the restore-set token for the best-available restore set for this {@code packageName}:
     * the active set if possible, else the ancestral one. Returns zero if none available.
     */
    public long getAvailableRestoreToken(@UserIdInt int userId, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getAvailableRestoreToken()");

        return userBackupManagerService == null
                ? 0
                : userBackupManagerService.getAvailableRestoreToken(packageName);
    }

    // ---------------------------------------------
    // ADB BACKUP/RESTORE OPERATIONS
    // ---------------------------------------------

    /** Sets the backup password used when running adb backup. */
    public boolean setBackupPassword(String currentPassword, String newPassword) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        UserHandle.USER_SYSTEM, "setBackupPassword()");

        return userBackupManagerService != null
                && userBackupManagerService.setBackupPassword(currentPassword, newPassword);
    }

    /** Returns {@code true} if adb backup was run with a password, else returns {@code false}. */
    public boolean hasBackupPassword() {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        UserHandle.USER_SYSTEM, "hasBackupPassword()");

        return userBackupManagerService != null && userBackupManagerService.hasBackupPassword();
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
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "adbBackup()");

        if (userBackupManagerService != null) {
            userBackupManagerService.adbBackup(
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
    }

    /**
     * Used by 'adb restore' to run a restore pass reading from the supplied {@code fd}. This method
     * is synchronous and does not return to the caller until the restore has been completed. It
     * requires on-screen confirmation by the user.
     */
    public void adbRestore(@UserIdInt int userId, ParcelFileDescriptor fd) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "adbRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.adbRestore(fd);
        }
    }

    /**
     * Confirm that the previously requested adb backup/restore operation can proceed. This is used
     * to require a user-facing disclosure about the operation.
     */
    public void acknowledgeAdbBackupOrRestore(
            @UserIdInt int userId,
            int token,
            boolean allow,
            String currentPassword,
            String encryptionPassword,
            IFullBackupRestoreObserver observer) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "acknowledgeAdbBackupOrRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.acknowledgeAdbBackupOrRestore(
                    token, allow, currentPassword, encryptionPassword, observer);
        }
    }

    // ---------------------------------------------
    //  SERVICE OPERATIONS
    // ---------------------------------------------

    /** Prints service state for 'dumpsys backup'. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(UserHandle.USER_SYSTEM, "dump()");

        if (userBackupManagerService != null) {
            userBackupManagerService.dump(fd, pw, args);
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
                sInstance.initializeService();
            }
            sInstance.unlockUser(userId);
        }

        @Override
        public void onStopUser(int userId) {
            sInstance.stopUser(userId);
        }
    }
}
