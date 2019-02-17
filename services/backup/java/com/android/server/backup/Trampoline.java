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

import static com.android.server.backup.BackupManagerService.TAG;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.app.backup.BackupManager;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.backup.utils.FileUtils;
import com.android.server.backup.utils.RandomAccessFileUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A proxy to the {@link BackupManagerService} implementation.
 *
 * <p>This is an external interface to the {@link BackupManagerService} which is being accessed via
 * published binder {@link BackupManagerService.Lifecycle}. This lets us turn down the heavy
 * implementation object on the fly without disturbing binders that have been cached somewhere in
 * the system.
 *
 * <p>Trampoline determines whether the backup service is available. It can be disabled in the
 * following two ways:
 *
 * <ul>
 * <li>Temporary - create the file {@link #BACKUP_SUPPRESS_FILENAME}, or
 * <li>Permanent - set the system property {@link #BACKUP_DISABLE_PROPERTY} to true.
 * </ul>
 *
 * Temporary disabling is controlled by {@link #setBackupServiceActive(int, boolean)} through
 * privileged callers (currently {@link DevicePolicyManager}). This is called on {@link
 * UserHandle#USER_SYSTEM} and disables backup for all users.
 *
 * <p>Creation of the backup service is done when {@link UserHandle#USER_SYSTEM} is unlocked. The
 * system user is unlocked before any other users.
 */
public class Trampoline extends IBackupManager.Stub {
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
    private static final String REMEMBER_ACTIVATED_FILENAME_PREFIX = "backup-remember-activated";

    // Product-level suppression of backup/restore.
    private static final String BACKUP_DISABLE_PROPERTY = "ro.backup.disable";

    private static final String BACKUP_THREAD = "backup";

    private final Context mContext;
    private final UserManager mUserManager;

    private final boolean mGlobalDisable;
    // Lock to write backup suppress files.
    // TODD(b/121198006): remove this object and synchronized all methods on "this".
    private final Object mStateLock = new Object();

    private volatile BackupManagerService mService;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public Trampoline(Context context) {
        mContext = context;
        mGlobalDisable = isBackupDisabled();
        mHandlerThread = new HandlerThread(BACKUP_THREAD, Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mUserManager = UserManager.get(context);
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
        return FileUtils.createNewFile(UserBackupManagerFiles.getStateFileInSystemDir(
                REMEMBER_ACTIVATED_FILENAME_PREFIX, userId));
    }

    /** Stored in the system user's directory and the file is indexed by the user it refers to. */
    protected File getActivatedFileForNonSystemUser(int userId) {
        return UserBackupManagerFiles.getStateFileInSystemDir(BACKUP_ACTIVATED_FILENAME, userId);
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

    // A user is ready for a backup if it's unlocked and is not suppressed by a device
    // admin (device owner or profile owner).
    private boolean isUserReadyForBackup(int userId) {
        return mService != null && mService.getServiceUsers().get(userId) != null
                && isBackupActivatedForUser(userId);
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

    protected BackupManagerService createBackupManagerService() {
        return new BackupManagerService(mContext, this, mHandlerThread);
    }

    protected void postToHandler(Runnable runnable) {
        mHandler.post(runnable);
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when the system user is unlocked. Attempts
     * to initialize {@link BackupManagerService}. Offloads work onto the handler thread {@link
     * #mHandlerThread} to keep unlock time low.
     */
    void initializeService() {
        postToHandler(
                () -> {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "backup init");
                    if (mGlobalDisable) {
                        Slog.i(TAG, "Backup service not supported");
                        return;
                    }
                    synchronized (mStateLock) {
                        if (mService == null) {
                            mService = createBackupManagerService();
                        }
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                });
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is unlocked.
     * Starts the backup service for this user if backup is active for this user. Offloads work onto
     * the handler thread {@link #mHandlerThread} to keep unlock time low.
     */
    void unlockUser(int userId) {
        postToHandler(() -> startServiceForUser(userId));
    }

    private void startServiceForUser(int userId) {
        // We know that the user is unlocked here because it is called from setBackupServiceActive
        // and unlockUser which have these guarantees. So we can check if the file exists.
        if (mService != null && isBackupActivatedForUser(userId)) {
            Slog.i(TAG, "Starting service for user: " + userId);
            mService.startServiceForUser(userId);
        }
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is stopped.
     * Offloads work onto the handler thread {@link #mHandlerThread} to keep stopping time low.
     */
    void stopUser(int userId) {
        postToHandler(
                () -> {
                    if (mService != null) {
                        Slog.i(TAG, "Stopping service for user: " + userId);
                        mService.stopServiceForUser(userId);
                    }
                });
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
            RandomAccessFileUtils.writeBoolean(getRememberActivatedFileForNonSystemUser(userId),
                    makeActive);
        }

        if (mGlobalDisable) {
            Slog.i(TAG, "Backup service not supported");
            return;
        }

        synchronized (mStateLock) {
            Slog.i(TAG, "Making backup " + (makeActive ? "" : "in") + "active");
            if (makeActive) {
                if (mService == null) {
                    mService = createBackupManagerService();
                }
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
                stopUser(userId);
            }
        }
    }

    // IBackupManager binder API

    /**
     * Querying activity state of backup service. Calling this method before initialize yields
     * undefined result.
     *
     * @param userId The user in which the activity state of backup service is queried.
     * @return true if the service is active.
     */
    @Override
    public boolean isBackupServiceActive(int userId) {
        synchronized (mStateLock) {
            return isUserReadyForBackup(userId);
        }
    }

    @Override
    public void dataChangedForUser(int userId, String packageName) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.dataChanged(userId, packageName);
        }
    }

    @Override
    public void dataChanged(String packageName) throws RemoteException {
        dataChangedForUser(binderGetCallingUserId(), packageName);
    }

    @Override
    public void initializeTransportsForUser(
            int userId, String[] transportNames, IBackupObserver observer) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.initializeTransports(userId, transportNames, observer);
        }
    }

    @Override
    public void clearBackupDataForUser(int userId, String transportName, String packageName)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.clearBackupData(userId, transportName, packageName);
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
            mService.agentConnected(userId, packageName, agent);
        }
    }

    @Override
    public void agentConnected(String packageName, IBinder agent) throws RemoteException {
        agentConnectedForUser(binderGetCallingUserId(), packageName, agent);
    }

    @Override
    public void agentDisconnectedForUser(int userId, String packageName) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.agentDisconnected(userId, packageName);
        }
    }

    @Override
    public void agentDisconnected(String packageName) throws RemoteException {
        agentDisconnectedForUser(binderGetCallingUserId(), packageName);
    }

    @Override
    public void restoreAtInstallForUser(int userId, String packageName, int token)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.restoreAtInstall(userId, packageName, token);
        }
    }

    @Override
    public void restoreAtInstall(String packageName, int token) throws RemoteException {
        restoreAtInstallForUser(binderGetCallingUserId(), packageName, token);
    }

    @Override
    public void setBackupEnabledForUser(@UserIdInt int userId, boolean isEnabled)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.setBackupEnabled(userId, isEnabled);
        }
    }

    @Override
    public void setBackupEnabled(boolean isEnabled) throws RemoteException {
        setBackupEnabledForUser(binderGetCallingUserId(), isEnabled);
    }

    @Override
    public void setAutoRestoreForUser(int userId, boolean doAutoRestore) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.setAutoRestore(userId, doAutoRestore);
        }
    }

    @Override
    public void setAutoRestore(boolean doAutoRestore) throws RemoteException {
        setAutoRestoreForUser(binderGetCallingUserId(), doAutoRestore);
    }

    @Override
    public boolean isBackupEnabledForUser(@UserIdInt int userId) throws RemoteException {
        return isUserReadyForBackup(userId) && mService.isBackupEnabled(userId);
    }

    @Override
    public boolean isBackupEnabled() throws RemoteException {
        return isBackupEnabledForUser(binderGetCallingUserId());
    }

    @Override
    public boolean setBackupPassword(String currentPw, String newPw) throws RemoteException {
        int userId = binderGetCallingUserId();
        return (isUserReadyForBackup(userId)) && mService.setBackupPassword(currentPw, newPw);
    }

    @Override
    public boolean hasBackupPassword() throws RemoteException {
        int userId = binderGetCallingUserId();
        return (isUserReadyForBackup(userId)) && mService.hasBackupPassword();
    }

    @Override
    public void backupNowForUser(@UserIdInt int userId) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.backupNow(userId);
        }
    }

    @Override
    public void backupNow() throws RemoteException {
        backupNowForUser(binderGetCallingUserId());
    }

    public void adbBackup(@UserIdInt int userId, ParcelFileDescriptor fd,
            boolean includeApks, boolean includeObbs, boolean includeShared, boolean doWidgets,
            boolean allApps, boolean allIncludesSystem, boolean doCompress, boolean doKeyValue,
            String[] packageNames) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.adbBackup(userId, fd, includeApks, includeObbs, includeShared, doWidgets,
                    allApps, allIncludesSystem, doCompress, doKeyValue, packageNames);
        }
    }

    @Override
    public void fullTransportBackupForUser(int userId, String[] packageNames)
            throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.fullTransportBackup(userId, packageNames);
        }
    }

    @Override
    public void adbRestore(@UserIdInt int userId, ParcelFileDescriptor fd) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.adbRestore(userId, fd);
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
            mService.acknowledgeAdbBackupOrRestore(userId, token, allow,
                    curPassword, encryptionPassword, observer);
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
        return (isUserReadyForBackup(userId)) ? mService.getCurrentTransport(userId) : null;
    }

    @Override
    public String getCurrentTransport() throws RemoteException {
        return getCurrentTransportForUser(binderGetCallingUserId());
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or
     * {@code null} if no transport selected or if the transport selected is not registered.
     */
    @Override
    @Nullable
    public ComponentName getCurrentTransportComponentForUser(int userId) {
        return (isUserReadyForBackup(userId)) ? mService.getCurrentTransportComponent(userId)
                : null;
    }

    @Override
    public String[] listAllTransportsForUser(int userId) throws RemoteException {
        return (isUserReadyForBackup(userId)) ? mService.listAllTransports(userId) : null;
    }

    @Override
    public String[] listAllTransports() throws RemoteException {
        return listAllTransportsForUser(binderGetCallingUserId());
    }

    @Override
    public ComponentName[] listAllTransportComponentsForUser(int userId) throws RemoteException {
        return (isUserReadyForBackup(userId)) ? mService.listAllTransportComponents(userId)
                : null;
    }

    @Override
    public String[] getTransportWhitelist() {
        int userId = binderGetCallingUserId();
        return (isUserReadyForBackup(userId)) ? mService.getTransportWhitelist() : null;
    }

    @Override
    public void updateTransportAttributesForUser(
            int userId,
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            String dataManagementLabel) {

        if (isUserReadyForBackup(userId)) {
            mService.updateTransportAttributes(
                    userId,
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
        return (isUserReadyForBackup(userId)) ? mService.selectBackupTransport(userId, transport)
                : null;
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        return selectBackupTransportForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public void selectBackupTransportAsyncForUser(int userId, ComponentName transport,
            ISelectBackupTransportCallback listener) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.selectBackupTransportAsync(userId, transport, listener);
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

    @Override
    public Intent getConfigurationIntentForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId) ? mService.getConfigurationIntent(userId, transport)
                : null;
    }

    @Override
    public Intent getConfigurationIntent(String transport)
            throws RemoteException {
        return getConfigurationIntentForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public String getDestinationStringForUser(int userId, String transport) throws RemoteException {
        return isUserReadyForBackup(userId) ? mService.getDestinationString(userId, transport)
                : null;
    }

    @Override
    public String getDestinationString(String transport) throws RemoteException {
        return getDestinationStringForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public Intent getDataManagementIntentForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId) ? mService.getDataManagementIntent(userId, transport)
                : null;
    }

    @Override
    public Intent getDataManagementIntent(String transport)
            throws RemoteException {
        return getDataManagementIntentForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public String getDataManagementLabelForUser(int userId, String transport)
            throws RemoteException {
        return isUserReadyForBackup(userId) ? mService.getDataManagementLabel(userId, transport)
                : null;
    }

    @Override
    public String getDataManagementLabel(String transport)
            throws RemoteException {
        return getDataManagementLabelForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public IRestoreSession beginRestoreSessionForUser(
            int userId, String packageName, String transportID) throws RemoteException {
        return isUserReadyForBackup(userId) ? mService.beginRestoreSession(userId, packageName,
                transportID) : null;
    }

    @Override
    public void opCompleteForUser(int userId, int token, long result) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.opComplete(userId, token, result);
        }
    }

    @Override
    public void opComplete(int token, long result) throws RemoteException {
        opCompleteForUser(binderGetCallingUserId(), token, result);
    }

    @Override
    public long getAvailableRestoreTokenForUser(int userId, String packageName) {
        return isUserReadyForBackup(userId) ? mService.getAvailableRestoreToken(userId,
                packageName) : 0;
    }

    @Override
    public boolean isAppEligibleForBackupForUser(int userId, String packageName) {
        return isUserReadyForBackup(userId) && mService.isAppEligibleForBackup(userId,
                packageName);
    }

    @Override
    public String[] filterAppsEligibleForBackupForUser(int userId, String[] packages) {
        return isUserReadyForBackup(userId) ? mService.filterAppsEligibleForBackup(userId,
                packages) : null;
    }

    @Override
    public int requestBackupForUser(@UserIdInt int userId, String[] packages, IBackupObserver
            observer, IBackupManagerMonitor monitor, int flags) throws RemoteException {
        if (!isUserReadyForBackup(userId)) {
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }
        return mService.requestBackup(userId, packages, observer, monitor, flags);
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) throws RemoteException {
        return requestBackupForUser(binderGetCallingUserId(), packages,
                observer, monitor, flags);
    }

    @Override
    public void cancelBackupsForUser(@UserIdInt int userId) throws RemoteException {
        if (isUserReadyForBackup(userId)) {
            mService.cancelBackups(userId);
        }
    }

    @Override
    public void cancelBackups() throws RemoteException {
        cancelBackupsForUser(binderGetCallingUserId());
    }

    @Override
    @Nullable public UserHandle getUserForAncestralSerialNumber(long ancestralSerialNumber) {
        if (mService != null) {
            return mService.getUserForAncestralSerialNumber(ancestralSerialNumber);
        }
        return null;
    }

    @Override
    public void setAncestralSerialNumber(long ancestralSerialNumber) {
        if (mService != null) {
            mService.setAncestralSerialNumber(ancestralSerialNumber);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        int userId = binderGetCallingUserId();
        if (isUserReadyForBackup(userId)) {
            mService.dump(fd, pw, args);
        } else {
            pw.println("Inactive");
        }
    }

    // Full backup/restore entry points - non-Binder; called directly
    // by the full-backup scheduled job
    /* package */ boolean beginFullBackup(@UserIdInt int userId, FullBackupJob scheduledJob) {
        return (isUserReadyForBackup(userId)) && mService.beginFullBackup(userId, scheduledJob);
    }

    /* package */ void endFullBackup(@UserIdInt int userId) {
        if (isUserReadyForBackup(userId)) {
            mService.endFullBackup(userId);
        }
    }
}
