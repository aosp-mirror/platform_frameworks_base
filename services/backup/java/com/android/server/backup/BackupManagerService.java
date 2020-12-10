/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static java.util.Collections.emptySet;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.backup.BackupManager;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.compat.CompatChanges;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.backup.utils.RandomAccessFileUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Definition of the system service that performs backup/restore operations.
 *
 * <p>This class is responsible for handling user-aware operations and acts as a delegator, routing
 * incoming calls to the appropriate per-user {@link UserBackupManagerService} to handle the
 * corresponding backup/restore operation.
 *
 * <p>It also determines whether the backup service is available. It can be disabled in the
 * following two ways:
 *
 * <ul>
 *  <li>Temporary - call {@link #setBackupServiceActive(int, boolean)}, or
 *  <li>Permanent - set the system property {@link #BACKUP_DISABLE_PROPERTY} to true.
 * </ul>
 *
 * Temporary disabling is controlled by {@link #setBackupServiceActive(int, boolean)} through
 * privileged callers (currently {@link DevicePolicyManager}). If called on {@link
 * UserHandle#USER_SYSTEM}, backup is disabled for all users.
 */
public class BackupManagerService extends IBackupManager.Stub {
    public static final String TAG = "BackupManagerService";
    public static final boolean DEBUG = true;
    public static final boolean MORE_DEBUG = false;
    public static final boolean DEBUG_SCHEDULING = true;

    @VisibleForTesting
    static final String DUMP_RUNNING_USERS_MESSAGE = "Backup Manager is running for users:";

    /**
     * Name of file that disables the backup service. If this file exists, then backup is disabled
     * for all users.
     */
    private static final String BACKUP_SUPPRESS_FILENAME = "backup-suppress";

    /**
     * Name of file for non-system users that enables the backup service for the user. Backup is
     * disabled by default in non-system users.
     */
    private static final String BACKUP_ACTIVATED_FILENAME = "backup-activated";

    /**
     * Name of file for non-system users that remembers whether backup was explicitly activated or
     * deactivated with a call to setBackupServiceActive.
     */
    private static final String REMEMBER_ACTIVATED_FILENAME = "backup-remember-activated";

    // Product-level suppression of backup/restore.
    private static final String BACKUP_DISABLE_PROPERTY = "ro.backup.disable";

    private static final String BACKUP_THREAD = "backup";

    static BackupManagerService sInstance;

    static BackupManagerService getInstance() {
        return Objects.requireNonNull(sInstance);
    }

    private final Context mContext;
    private final UserManager mUserManager;

    private final boolean mGlobalDisable;
    // Lock to write backup suppress files.
    // TODD(b/121198006): remove this object and synchronized all methods on "this".
    private final Object mStateLock = new Object();

    private final Handler mHandler;
    private final Set<ComponentName> mTransportWhitelist;

    /** Keeps track of all unlocked users registered with this service. Indexed by user id. */
    private final SparseArray<UserBackupManagerService> mUserServices;

    private final BroadcastReceiver mUserRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId > 0) { // for only non system users
                    mHandler.post(() -> onRemovedNonSystemUser(userId));
                }
            }
        }
    };

    public BackupManagerService(Context context) {
        this(context, new SparseArray<>());
    }

    @VisibleForTesting
    BackupManagerService(Context context, SparseArray<UserBackupManagerService> userServices) {
        mContext = context;
        mGlobalDisable = isBackupDisabled();
        HandlerThread handlerThread =
                new HandlerThread(BACKUP_THREAD, Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mUserManager = UserManager.get(context);
        mUserServices = userServices;
        Set<ComponentName> transportWhitelist =
                SystemConfig.getInstance().getBackupTransportWhitelist();
        mTransportWhitelist = (transportWhitelist == null) ? emptySet() : transportWhitelist;
        mContext.registerReceiver(
                mUserRemovedReceiver, new IntentFilter(Intent.ACTION_USER_REMOVED));
    }

    // TODO: Remove this when we implement DI by injecting in the construtor.
    @VisibleForTesting
    Handler getBackupHandler() {
        return mHandler;
    }

    protected boolean isBackupDisabled() {
        return SystemProperties.getBoolean(BACKUP_DISABLE_PROPERTY, false);
    }

    protected int binderGetCallingUserId() {
        return Binder.getCallingUserHandle().getIdentifier();
    }

    protected int binderGetCallingUid() {
        return Binder.getCallingUid();
    }

    /** Stored in the system user's directory. */
    protected File getSuppressFileForSystemUser() {
        return new File(UserBackupManagerFiles.getBaseStateDir(UserHandle.USER_SYSTEM),
                BACKUP_SUPPRESS_FILENAME);
    }

    /** Stored in the system user's directory and the file is indexed by the user it refers to. */
    protected File getRememberActivatedFileForNonSystemUser(int userId) {
        return UserBackupManagerFiles.getStateFileInSystemDir(REMEMBER_ACTIVATED_FILENAME, userId);
    }

    /** Stored in the system user's directory and the file is indexed by the user it refers to. */
    protected File getActivatedFileForNonSystemUser(int userId) {
        return UserBackupManagerFiles.getStateFileInSystemDir(BACKUP_ACTIVATED_FILENAME, userId);
    }

    /**
     * Remove backup state for non system {@code userId} when the user is removed from the device.
     * For non system users, backup state is stored in both the user's own dir and the system dir.
     * When the user is removed, the user's own dir gets removed by the OS. This method ensures that
     * the part of the user backup state which is in the system dir also gets removed.
     */
    private void onRemovedNonSystemUser(int userId) {
        Slog.i(TAG, "Removing state for non system user " + userId);
        File dir = UserBackupManagerFiles.getStateDirInSystemDir(userId);
        if (!FileUtils.deleteContentsAndDir(dir)) {
            Slog.w(TAG, "Failed to delete state dir for removed user: " + userId);
        }
    }

    // TODO (b/124359804) move to util method in FileUtils
    private void createFile(File file) throws IOException {
        if (file.exists()) {
            return;
        }

        file.getParentFile().mkdirs();
        if (!file.createNewFile()) {
            Slog.w(TAG, "Failed to create file " + file.getPath());
        }
    }

    // TODO (b/124359804) move to util method in FileUtils
    private void deleteFile(File file) {
        if (!file.exists()) {
            return;
        }

        if (!file.delete()) {
            Slog.w(TAG, "Failed to delete file " + file.getPath());
        }
    }

    /**
     * Deactivates the backup service for user {@code userId}. If this is the system user, it
     * creates a suppress file which disables backup for all users. If this is a non-system user, it
     * only deactivates backup for that user by deleting its activate file.
     */
    @GuardedBy("mStateLock")
    private void deactivateBackupForUserLocked(int userId) throws IOException {
        if (userId == UserHandle.USER_SYSTEM) {
            createFile(getSuppressFileForSystemUser());
        } else {
            deleteFile(getActivatedFileForNonSystemUser(userId));
        }
    }

    /**
     * Enables the backup service for user {@code userId}. If this is the system user, it deletes
     * the suppress file. If this is a non-system user, it creates the user's activate file. Note,
     * deleting the suppress file does not automatically enable backup for non-system users, they
     * need their own activate file in order to participate in the service.
     */
    @GuardedBy("mStateLock")
    private void activateBackupForUserLocked(int userId) throws IOException {
        if (userId == UserHandle.USER_SYSTEM) {
            deleteFile(getSuppressFileForSystemUser());
        } else {
            createFile(getActivatedFileForNonSystemUser(userId));
        }
    }

    /**
     * This method should not perform any I/O (e.g. do not call isBackupActivatedForUser),
     * it's used in multiple places where I/O waits would cause system lock-ups.
     * @param userId User id for which this operation should be performed.
     * @return true if the user is ready for backup and false otherwise.
     */
    @Override
    public boolean isUserReadyForBackup(int userId) {
        return mUserServices.get(UserHandle.USER_SYSTEM) != null
                && mUserServices.get(userId) != null;
    }

    /**
     * Backup is activated for the system user if the suppress file does not exist. Backup is
     * activated for non-system users if the suppress file does not exist AND the user's activated
     * file exists.
     */
    private boolean isBackupActivatedForUser(int userId) {
        if (getSuppressFileForSystemUser().exists()) {
            return false;
        }

        return userId == UserHandle.USER_SYSTEM
                || getActivatedFileForNonSystemUser(userId).exists();
    }

    protected Context getContext() {
        return mContext;
    }

    protected UserManager getUserManager() {
        return mUserManager;
    }

    protected void postToHandler(Runnable runnable) {
        mHandler.post(runnable);
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is unlocked.
     * Starts the backup service for this user if backup is active for this user. Offloads work onto
     * the handler thread {@link #mHandlerThread} to keep unlock time low since backup is not
     * essential for device functioning.
     */
    void onUnlockUser(int userId) {
        postToHandler(() -> startServiceForUser(userId));
    }

    /**
     * Starts the backup service for user {@code userId} by creating a new instance of {@link
     * UserBackupManagerService} and registering it with this service.
     */
    @VisibleForTesting
    void startServiceForUser(int userId) {
        // We know that the user is unlocked here because it is called from setBackupServiceActive
        // and unlockUser which have these guarantees. So we can check if the file exists.
        if (mGlobalDisable) {
            Slog.i(TAG, "Backup service not supported");
            return;
        }
        if (!isBackupActivatedForUser(userId)) {
            Slog.i(TAG, "Backup not activated for user " + userId);
            return;
        }
        if (mUserServices.get(userId) != null) {
            Slog.i(TAG, "userId " + userId + " already started, so not starting again");
            return;
        }
        Slog.i(TAG, "Starting service for user: " + userId);
        UserBackupManagerService userBackupManagerService =
                UserBackupManagerService.createAndInitializeService(
                        userId, mContext, this, mTransportWhitelist);
        startServiceForUser(userId, userBackupManagerService);
    }

    /**
     * Starts the backup service for user {@code userId} by registering its instance of {@link
     * UserBackupManagerService} with this service and setting enabled state.
     */
    void startServiceForUser(int userId, UserBackupManagerService userBackupManagerService) {
        mUserServices.put(userId, userBackupManagerService);

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "backup enable");
        userBackupManagerService.initializeBackupEnableState();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /** Stops the backup service for user {@code userId} when the user is stopped. */
    @VisibleForTesting
    protected void stopServiceForUser(int userId) {
        UserBackupManagerService userBackupManagerService = mUserServices.removeReturnOld(userId);

        if (userBackupManagerService != null) {
            userBackupManagerService.tearDownService();

            KeyValueBackupJob.cancel(userId, mContext);
            FullBackupJob.cancel(userId, mContext);
        }
    }

    /**
     *  Returns a list of users currently unlocked that have a {@link UserBackupManagerService}
     *  registered.
     *
     *  Warning: Do NOT modify returned object as it's used inside.
     *
     *  TODO: Return a copy or only expose read-only information through other means.
     */
    @VisibleForTesting
    SparseArray<UserBackupManagerService> getUserServices() {
        return mUserServices;
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is stopped.
     * Offloads work onto the handler thread {@link #mHandlerThread} to keep stopping time low.
     */
    void onStopUser(int userId) {
        postToHandler(
                () -> {
                    if (!mGlobalDisable) {
                        Slog.i(TAG, "Stopping service for user: " + userId);
                        stopServiceForUser(userId);
                    }
                });
    }

    /** Returns {@link UserBackupManagerService} for user {@code userId}. */
    @Nullable
    public UserBackupManagerService getUserService(int userId) {
        return mUserServices.get(userId);
    }

    /**
     * The system user and managed profiles can only be acted on by callers in the system or root
     * processes. Other users can be acted on by callers who have both android.permission.BACKUP and
     * android.permission.INTERACT_ACROSS_USERS_FULL permissions.
     */
    private void enforcePermissionsOnUser(int userId) throws SecurityException {
        boolean isRestrictedUser =
                userId == UserHandle.USER_SYSTEM
                        || getUserManager().getUserInfo(userId).isManagedProfile();

        if (isRestrictedUser) {
            int caller = binderGetCallingUid();
            if (caller != Process.SYSTEM_UID && caller != Process.ROOT_UID) {
                throw new SecurityException("No permission to configure backup activity");
            }
        } else {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.BACKUP, "No permission to configure backup activity");
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "No permission to configure backup activity");
        }
    }

    /**
     * Only privileged callers should be changing the backup state. Deactivating backup in the
     * system user also deactivates backup in all users. We are not guaranteed that {@code userId}
     * is unlocked at this point yet, so handle both cases.
     */
    public void setBackupServiceActive(int userId, boolean makeActive) {
        enforcePermissionsOnUser(userId);

        // In Q, backup is OFF by default for non-system users. In the future, we will change that
        // to ON unless backup was explicitly deactivated with a (permissioned) call to
        // setBackupServiceActive.
        // Therefore, remember this for use in the future. Basically the default in the future will
        // be: rememberFile.exists() ? rememberFile.value() : ON
        // Note that this has to be done right after the permission checks and before any other
        // action since we need to remember that a permissioned call was made irrespective of
        // whether the call changes the state or not.
        if (userId != UserHandle.USER_SYSTEM) {
            try {
                File rememberFile = getRememberActivatedFileForNonSystemUser(userId);
                createFile(rememberFile);
                RandomAccessFileUtils.writeBoolean(rememberFile, makeActive);
            } catch (IOException e) {
                Slog.e(TAG, "Unable to persist backup service activity", e);
            }
        }

        if (mGlobalDisable) {
            Slog.i(TAG, "Backup service not supported");
            return;
        }

        synchronized (mStateLock) {
            Slog.i(TAG, "Making backup " + (makeActive ? "" : "in") + "active");
            if (makeActive) {
                try {
                    activateBackupForUserLocked(userId);
                } catch (IOException e) {
                    Slog.e(TAG, "Unable to persist backup service activity");
                }

                // If the user is unlocked, we can start the backup service for it. Otherwise we
                // will start the service when the user is unlocked as part of its unlock callback.
                if (getUserManager().isUserUnlocked(userId)) {
                    // Clear calling identity as initialization enforces the system identity but we
                    // can be coming from shell.
                    long oldId = Binder.clearCallingIdentity();
                    try {
                        startServiceForUser(userId);
                    } finally {
                        Binder.restoreCallingIdentity(oldId);
                    }
                }
            } else {
                try {
                    //TODO(b/121198006): what if this throws an exception?
                    deactivateBackupForUserLocked(userId);
                } catch (IOException e) {
                    Slog.e(TAG, "Unable to persist backup service inactivity");
                }
                //TODO(b/121198006): loop through active users that have work profile and
                // stop them as well.
                onStopUser(userId);
            }
        }
    }

    // IBackupManager binder API

    /**
     * Querying activity state of backup service.
     *
     * @param userId The user in which the activity state of backup service is queried.
     * @return true if the service is active.
     */
    @Override
    public boolean isBackupServiceActive(int userId) {
        int callingUid = Binder.getCallingUid();
        if (CompatChanges.isChangeEnabled(
                BackupManager.IS_BACKUP_SERVICE_ACTIVE_ENFORCE_PERMISSION_IN_SERVICE, callingUid)) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "isBackupServiceActive");
        }
        synchronized (mStateLock) {
            return !mGlobalDisable && isBackupActivatedForUser(userId);
        }
    }

    @Override
    public void dataChangedForUser(int userId, String packageName) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            dataChanged(userId, packageName);
        }
    }

    @Override
    public void dataChanged(String packageName) throws RemoteException {
        dataChangedForUser(binderGetCallingUserId(), packageName);
    }

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

    // ---------------------------------------------
    // TRANSPORT OPERATIONS
    // ---------------------------------------------

    @Override
    public void initializeTransportsForUser(
            int userId, String[] transportNames, IBackupObserver observer) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            initializeTransports(userId, transportNames, observer);
        }
    }

    /** Run an initialize operation for the given transports {@code transportNames}. */
    public void initializeTransports(
            @UserIdInt int userId, String[] transportNames, IBackupObserver observer) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "initializeTransports()");

        if (userBackupManagerService != null) {
            userBackupManagerService.initializeTransports(transportNames, observer);
        }
    }

    @Override
    public void clearBackupDataForUser(int userId, String transportName, String packageName)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            clearBackupData(userId, transportName, packageName);
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

    @Override
    public void clearBackupData(String transportName, String packageName)
            throws RemoteException {
        clearBackupDataForUser(binderGetCallingUserId(), transportName, packageName);
    }

    @Override
    public void agentConnectedForUser(int userId, String packageName, IBinder agent)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            agentConnected(userId, packageName, agent);
        }
    }

    @Override
    public void agentConnected(String packageName, IBinder agent) throws RemoteException {
        agentConnectedForUser(binderGetCallingUserId(), packageName, agent);
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

    @Override
    public void agentDisconnectedForUser(int userId, String packageName) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            agentDisconnected(userId, packageName);
        }
    }

    @Override
    public void agentDisconnected(String packageName) throws RemoteException {
        agentDisconnectedForUser(binderGetCallingUserId(), packageName);
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

    @Override
    public void restoreAtInstallForUser(int userId, String packageName, int token)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            restoreAtInstall(userId, packageName, token);
        }
    }

    @Override
    public void restoreAtInstall(String packageName, int token) throws RemoteException {
        restoreAtInstallForUser(binderGetCallingUserId(), packageName, token);
    }

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

    @Override
    public void setBackupEnabledForUser(@UserIdInt int userId, boolean isEnabled)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            setBackupEnabled(userId, isEnabled);
        }
    }

    @Override
    public void setBackupEnabled(boolean isEnabled) throws RemoteException {
        setBackupEnabledForUser(binderGetCallingUserId(), isEnabled);
    }

    /** Enable/disable the backup service. This is user-configurable via backup settings. */
    public void setBackupEnabled(@UserIdInt int userId, boolean enable) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "setBackupEnabled()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setBackupEnabled(enable);
        }
    }

    @Override
    public void setAutoRestoreForUser(int userId, boolean doAutoRestore) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            setAutoRestore(userId, doAutoRestore);
        }
    }

    @Override
    public void setAutoRestore(boolean doAutoRestore) throws RemoteException {
        setAutoRestoreForUser(binderGetCallingUserId(), doAutoRestore);
    }

    /** Enable/disable automatic restore of app data at install time. */
    public void setAutoRestore(@UserIdInt int userId, boolean autoRestore) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "setAutoRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setAutoRestore(autoRestore);
        }
    }

    @Override
    public boolean isBackupEnabledForUser(@UserIdInt int userId) throws RemoteException {
        return isUserReadyForBackup(userId) && isBackupEnabled(userId);
    }

    @Override
    public boolean isBackupEnabled() throws RemoteException {
        return isBackupEnabledForUser(binderGetCallingUserId());
    }

    /**
     * Return {@code true} if the backup mechanism is currently enabled, else returns {@code false}.
     */
    public boolean isBackupEnabled(@UserIdInt int userId) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "isBackupEnabled()");

        return userBackupManagerService != null && userBackupManagerService.isBackupEnabled();
    }

    /** Sets the backup password used when running adb backup. */
    @Override
    public boolean setBackupPassword(String currentPassword, String newPassword) {
        int userId = binderGetCallingUserId();
        if (!isUserReadyForBackup(userId)) {
            return false;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        UserHandle.USER_SYSTEM, "setBackupPassword()");

        return userBackupManagerService != null
                && userBackupManagerService.setBackupPassword(currentPassword, newPassword);
    }

    /** Returns {@code true} if adb backup was run with a password, else returns {@code false}. */
    @Override
    public boolean hasBackupPassword() throws RemoteException {
        int userId = binderGetCallingUserId();
        if (!isUserReadyForBackup(userId)) {
            return false;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        UserHandle.USER_SYSTEM, "hasBackupPassword()");

        return userBackupManagerService != null && userBackupManagerService.hasBackupPassword();
    }

    @Override
    public void backupNowForUser(@UserIdInt int userId) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            backupNow(userId);
        }
    }

    @Override
    public void backupNow() throws RemoteException {
        backupNowForUser(binderGetCallingUserId());
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
     * Used by 'adb backup' to run a backup pass for packages {@code packageNames} supplied via the
     * command line, writing the resulting data stream to the supplied {@code fd}. This method is
     * synchronous and does not return to the caller until the backup has been completed. It
     * requires on-screen confirmation by the user.
     */
    @Override
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
        if (!isUserReadyForBackup(userId)) {
            return;
        }
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

    @Override
    public void fullTransportBackupForUser(int userId, String[] packageNames)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            fullTransportBackup(userId, packageNames);
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

    /**
     * Used by 'adb restore' to run a restore pass reading from the supplied {@code fd}. This method
     * is synchronous and does not return to the caller until the restore has been completed. It
     * requires on-screen confirmation by the user.
     */
    @Override
    public void adbRestore(@UserIdInt int userId, ParcelFileDescriptor fd) {
        if (!isUserReadyForBackup(userId)) {
            return;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "adbRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.adbRestore(fd);
        }
    }

    @Override
    public void acknowledgeFullBackupOrRestoreForUser(
            int userId,
            int token,
            boolean allow,
            String curPassword,
            String encryptionPassword,
            IFullBackupRestoreObserver observer)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            acknowledgeAdbBackupOrRestore(userId, token, allow,
                    curPassword, encryptionPassword, observer);
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

    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow, String curPassword,
            String encryptionPassword, IFullBackupRestoreObserver observer)
            throws RemoteException {
        acknowledgeFullBackupOrRestoreForUser(
                binderGetCallingUserId(), token, allow, curPassword, encryptionPassword, observer);
    }


    @Override
    public String getCurrentTransportForUser(int userId) throws RemoteException {
        return (isUserReadyForBackup(userId)) ? getCurrentTransport(userId) : null;
    }

    @Override
    public String getCurrentTransport() throws RemoteException {
        return getCurrentTransportForUser(binderGetCallingUserId());
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
     * Returns the {@link ComponentName} of the host service of the selected transport or
     * {@code null} if no transport selected or if the transport selected is not registered.
     */
    @Override
    @Nullable
    public ComponentName getCurrentTransportComponentForUser(int userId) {
        return (isUserReadyForBackup(userId)) ? getCurrentTransportComponent(userId) : null;
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

    @Override
    public String[] listAllTransportsForUser(int userId) throws RemoteException {
        return (isUserReadyForBackup(userId)) ? listAllTransports(userId) : null;
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

    @Override
    public String[] listAllTransports() throws RemoteException {
        return listAllTransportsForUser(binderGetCallingUserId());
    }

    @Override
    public ComponentName[] listAllTransportComponentsForUser(int userId) throws RemoteException {
        return (isUserReadyForBackup(userId))
                ? listAllTransportComponents(userId) : null;
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

    @Override
    public String[] getTransportWhitelist() {
        int userId = binderGetCallingUserId();
        if (!isUserReadyForBackup(userId)) {
            return null;
        }
        // No permission check, intentionally.
        String[] whitelistedTransports = new String[mTransportWhitelist.size()];
        int i = 0;
        for (ComponentName component : mTransportWhitelist) {
            whitelistedTransports[i] = component.flattenToShortString();
            i++;
        }
        return whitelistedTransports;
    }

    @Override
    public void updateTransportAttributesForUser(
            int userId,
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            CharSequence dataManagementLabel) {
        if (isUserReadyForBackup(userId)) {
            updateTransportAttributes(
                    userId,
                    transportComponent,
                    name,
                    configurationIntent,
                    currentDestinationString,
                    dataManagementIntent,
                    dataManagementLabel);
        }
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
     * @param dataManagementLabel A {@link CharSequence} to be used as the label for the transport's
     *     data management affordance. This MUST be {@code null} when dataManagementIntent is {@code
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
            CharSequence dataManagementLabel) {
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

    @Override
    public String selectBackupTransportForUser(int userId, String transport)
            throws RemoteException {
        return (isUserReadyForBackup(userId))
                ? selectBackupTransport(userId, transport) : null;
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        return selectBackupTransportForUser(binderGetCallingUserId(), transport);
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

    @Override
    public void selectBackupTransportAsyncForUser(int userId, ComponentName transport,
            ISelectBackupTransportCallback listener) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            selectBackupTransportAsync(userId, transport, listener);
        } else {
            if (listener != null) {
                try {
                    listener.onFailure(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                } catch (RemoteException ex) {
                    // ignore
                }
            }
        }
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

    @Override
    public Intent getConfigurationIntentForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId) ? getConfigurationIntent(userId, transport)
                : null;
    }

    @Override
    public Intent getConfigurationIntent(String transport)
            throws RemoteException {
        return getConfigurationIntentForUser(binderGetCallingUserId(), transport);
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

    @Override
    public String getDestinationStringForUser(int userId, String transport) throws RemoteException {
        return isUserReadyForBackup(userId) ? getDestinationString(userId, transport)
                : null;
    }

    @Override
    public String getDestinationString(String transport) throws RemoteException {
        return getDestinationStringForUser(binderGetCallingUserId(), transport);
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

    @Override
    public Intent getDataManagementIntentForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId)
                ? getDataManagementIntent(userId, transport) : null;
    }

    @Override
    public Intent getDataManagementIntent(String transport)
            throws RemoteException {
        return getDataManagementIntentForUser(binderGetCallingUserId(), transport);
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

    @Override
    public CharSequence getDataManagementLabelForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId) ? getDataManagementLabel(userId, transport)
                : null;
    }

    /**
     * Supply the menu label for affordances that fire the manage-data intent for the given
     * transport.
     */
    @Nullable
    public CharSequence getDataManagementLabel(@UserIdInt int userId, String transportName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "getDataManagementLabel()");

        return userBackupManagerService == null
                ? null
                : userBackupManagerService.getDataManagementLabel(transportName);
    }

    @Override
    public IRestoreSession beginRestoreSessionForUser(
            int userId, String packageName, String transportID) throws RemoteException {
        return isUserReadyForBackup(userId)
                ? beginRestoreSession(userId, packageName, transportID) : null;
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

    @Override
    public void opCompleteForUser(int userId, int token, long result) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            opComplete(userId, token, result);
        }
    }

    @Override
    public void opComplete(int token, long result) throws RemoteException {
        opCompleteForUser(binderGetCallingUserId(), token, result);
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

    @Override
    public long getAvailableRestoreTokenForUser(int userId, String packageName) {
        return isUserReadyForBackup(userId) ? getAvailableRestoreToken(userId, packageName) : 0;
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

    @Override
    public boolean isAppEligibleForBackupForUser(int userId, String packageName) {
        return isUserReadyForBackup(userId) && isAppEligibleForBackup(userId,
                packageName);
    }

    /** Checks if the given package {@code packageName} is eligible for backup. */
    public boolean isAppEligibleForBackup(@UserIdInt int userId, String packageName) {
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "isAppEligibleForBackup()");

        return userBackupManagerService != null
                && userBackupManagerService.isAppEligibleForBackup(packageName);
    }

    @Override
    public String[] filterAppsEligibleForBackupForUser(int userId, String[] packages) {
        return isUserReadyForBackup(userId) ? filterAppsEligibleForBackup(userId, packages) : null;
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

    @Override
    public int requestBackupForUser(@UserIdInt int userId, String[] packages, IBackupObserver
            observer, IBackupManagerMonitor monitor, int flags) throws RemoteException {
        if (!isUserReadyForBackup(userId)) {
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }
        return requestBackup(userId, packages, observer, monitor, flags);
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) throws RemoteException {
        return requestBackupForUser(binderGetCallingUserId(), packages,
                observer, monitor, flags);
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

    @Override
    public void cancelBackupsForUser(@UserIdInt int userId) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            cancelBackups(userId);
        }
    }

    @Override
    public void cancelBackups() throws RemoteException {
        cancelBackupsForUser(binderGetCallingUserId());
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
     * Returns a {@link UserHandle} for the user that has {@code ancestralSerialNumber} as the
     * serial number of its ancestral work profile or null if there is no {@link
     * UserBackupManagerService} associated with that user.
     *
     * <p> The ancestral work profile is set by {@link #setAncestralSerialNumber(long)}
     * and it corresponds to the profile that was used to restore to the callers profile.
     */
    @Override
    @Nullable
    public UserHandle getUserForAncestralSerialNumber(long ancestralSerialNumber) {
        if (mGlobalDisable) {
            return null;
        }
        int callingUserId = Binder.getCallingUserHandle().getIdentifier();
        long oldId = Binder.clearCallingIdentity();
        final int[] userIds;
        try {
            userIds = getUserManager().getProfileIds(callingUserId, false);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }

        for (int userId : userIds) {
            UserBackupManagerService userBackupManagerService = mUserServices.get(userId);
            if (userBackupManagerService != null) {
                if (userBackupManagerService.getAncestralSerialNumber() == ancestralSerialNumber) {
                    return UserHandle.of(userId);
                }
            }
        }

        return null;
    }

    /**
     * Sets the ancestral work profile for the calling user.
     *
     * <p> The ancestral work profile corresponds to the profile that was used to restore to the
     * callers profile.
     */
    @Override
    public void setAncestralSerialNumber(long ancestralSerialNumber) {
        if (mGlobalDisable) {
            return;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(
                        Binder.getCallingUserHandle().getIdentifier(),
                        "setAncestralSerialNumber()");

        if (userBackupManagerService != null) {
            userBackupManagerService.setAncestralSerialNumber(ancestralSerialNumber);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) {
            return;
        }
        dumpWithoutCheckingPermission(fd, pw, args);
    }

    @VisibleForTesting
    void dumpWithoutCheckingPermission(FileDescriptor fd, PrintWriter pw, String[] args) {
        int userId = binderGetCallingUserId();
        if (!isUserReadyForBackup(userId)) {
            pw.println("Inactive");
            return;
        }

        if (args != null) {
            for (String arg : args) {
                if ("-h".equals(arg)) {
                    pw.println("'dumpsys backup' optional arguments:");
                    pw.println("  -h       : this help text");
                    pw.println("  a[gents] : dump information about defined backup agents");
                    pw.println("  transportclients : dump information about transport clients");
                    pw.println("  transportstats : dump transport statts");
                    pw.println("  users    : dump the list of users for which backup service "
                            + "is running");
                    return;
                } else if ("users".equals(arg.toLowerCase())) {
                    pw.print(DUMP_RUNNING_USERS_MESSAGE);
                    for (int i = 0; i < mUserServices.size(); i++) {
                        pw.print(" " + mUserServices.keyAt(i));
                    }
                    pw.println();
                    return;
                }
            }
        }

        for (int i = 0; i < mUserServices.size(); i++) {
            UserBackupManagerService userBackupManagerService =
                    getServiceForUserIfCallerHasPermission(mUserServices.keyAt(i), "dump()");
            if (userBackupManagerService != null) {
                userBackupManagerService.dump(fd, pw, args);
            }
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
        if (!isUserReadyForBackup(userId)) {
            return false;
        }
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
        if (!isUserReadyForBackup(userId)) {
            return;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "endFullBackup()");

        if (userBackupManagerService != null) {
            userBackupManagerService.endFullBackup();
        }
    }

    /**
     * Excludes keys from KV restore for a given package. The corresponding data will be excluded
     * from the data set available the backup agent during restore. However,  final list  of keys
     * that have been excluded will be passed to the agent to make it aware of the exclusions.
     */
    public void excludeKeysFromRestore(String packageName, List<String> keys) {
        int userId = Binder.getCallingUserHandle().getIdentifier();
        if (!isUserReadyForBackup(userId)) {
            Slog.w(TAG, "Returning from excludeKeysFromRestore as backup for user" + userId +
                    " is not initialized yet");
            return;
        }
        UserBackupManagerService userBackupManagerService =
                getServiceForUserIfCallerHasPermission(userId, "excludeKeysFromRestore()");

        if (userBackupManagerService != null) {
            userBackupManagerService.excludeKeysFromRestore(packageName, keys);
        }
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
        UserBackupManagerService userBackupManagerService = mUserServices.get(userId);
        if (userBackupManagerService == null) {
            Slog.w(TAG, "Called " + caller + " for unknown user: " + userId);
        }
        return userBackupManagerService;
    }

    /**
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id on which the backup operation is being requested.
     * @param message A message to include in the exception if it is thrown.
     */
    void enforceCallingPermissionOnUserId(@UserIdInt int userId, String message) {
        if (Binder.getCallingUserHandle().getIdentifier() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        }
    }

    /** Implementation to receive lifecycle event callbacks for system services. */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            this(context, new BackupManagerService(context));
        }

        @VisibleForTesting
        Lifecycle(Context context, BackupManagerService backupManagerService) {
            super(context);
            sInstance = backupManagerService;
        }

        @Override
        public void onStart() {
            publishService(Context.BACKUP_SERVICE, BackupManagerService.sInstance);
        }

        @Override
        public void onUnlockUser(int userId) {
            sInstance.onUnlockUser(userId);
        }

        @Override
        public void onStopUser(int userId) {
            sInstance.onStopUser(userId);
        }

        @VisibleForTesting
        void publishService(String name, IBinder service) {
            publishBinderService(name, service);
        }
    }
}
