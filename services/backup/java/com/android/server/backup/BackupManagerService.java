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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.backup.BackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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

    @VisibleForTesting
    static final String DUMP_RUNNING_USERS_MESSAGE = "Backup Manager is running for users:";

    private final Context mContext;
    private final Trampoline mTrampoline;
    private final SparseArray<UserBackupManagerService> mServiceUsers;

    /** Instantiate a new instance of {@link BackupManagerService}. */
    public BackupManagerService(
            Context context,
            Trampoline trampoline,
            SparseArray<UserBackupManagerService> userServices) {
        mContext = checkNotNull(context);
        mTrampoline = checkNotNull(trampoline);
        // TODO(b/135661048): Remove
        mServiceUsers = userServices;
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
        return mTrampoline.getServiceForUserIfCallerHasPermission(userId, caller);
    }

    /*
     * The following methods are implementations of IBackupManager methods called from Trampoline.
     * They delegate to the appropriate per-user instance of UserBackupManagerService to perform the
     * action on the passed in user. Currently this is a straight redirection (see TODO).
     */
    // TODO (b/118520567): Stop hardcoding system user when we pass in user id as a parameter

    /**
     * Sets the ancestral work profile for the calling user.
     *
     * <p> The ancestral work profile corresponds to the profile that was used to restore to the
     * callers profile.
     */
    public void setAncestralSerialNumber(long ancestralSerialNumber) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        Binder.getCallingUserHandle().getIdentifier(),
                        "setAncestralSerialNumber()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setAncestralSerialNumber(ancestralSerialNumber);
        }
    }

    /**
     * Returns a {@link UserHandle} for the user that has {@code ancestralSerialNumber} as the
     * serial number of the its ancestral work profile or null if there is no {@link
     * UserBackupManagerService} associated with that user.
     *
     * <p> The ancestral work profile is set by {@link #setAncestralSerialNumber(long)}
     * and it corresponds to the profile that was used to restore to the callers profile.
     */
    @Nullable
    public UserHandle getUserForAncestralSerialNumber(long ancestralSerialNumber) {
        int callingUserId = Binder.getCallingUserHandle().getIdentifier();
        long oldId = Binder.clearCallingIdentity();
        final int[] userIds;
        try {
            userIds =
                    mContext
                            .getSystemService(UserManager.class)
                            .getProfileIds(callingUserId, false);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }

        for (int userId : userIds) {
            UserBackupManagerService userBackupManagerService = mServiceUsers.get(userId);
            if (userBackupManagerService != null) {
                if (userBackupManagerService.getAncestralSerialNumber() == ancestralSerialNumber) {
                    return UserHandle.of(userId);
                }
            }
        }

        return null;
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
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) {
            return;
        }

        if (args != null) {
            for (String arg : args) {
                if ("users".equals(arg.toLowerCase())) {
                    pw.print(DUMP_RUNNING_USERS_MESSAGE);
                    for (int i = 0; i < mServiceUsers.size(); i++) {
                        pw.print(" " + mServiceUsers.keyAt(i));
                    }
                    pw.println();
                    return;
                }
            }
        }

        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(UserHandle.USER_SYSTEM, "dump()");

        if (userBackupManagerService != null) {
            userBackupManagerService.dump(fd, pw, args);
        }
    }

    /** Implementation to receive lifecycle event callbacks for system services. */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            this(context, new Trampoline(context));
        }

        @VisibleForTesting
        Lifecycle(Context context, Trampoline trampoline) {
            super(context);
            Trampoline.sInstance = trampoline;
        }

        @Override
        public void onStart() {
            publishService(Context.BACKUP_SERVICE, Trampoline.sInstance);
        }

        @Override
        public void onUnlockUser(int userId) {
            Trampoline.sInstance.onUnlockUser(userId);
        }

        @Override
        public void onStopUser(int userId) {
            Trampoline.sInstance.onStopUser(userId);
        }

        @VisibleForTesting
        void publishService(String name, IBinder service) {
            publishBinderService(name, service);
        }
    }
}
