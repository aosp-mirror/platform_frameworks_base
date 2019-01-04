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
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;

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
 *   <li>Temporary - create the file {@link #BACKUP_SUPPRESS_FILENAME}, or
 *   <li>Permanent - set the system property {@link #BACKUP_DISABLE_PROPERTY} to true.
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
    // When this file is present, the backup service is inactive.
    private static final String BACKUP_SUPPRESS_FILENAME = "backup-suppress";

    // Product-level suppression of backup/restore.
    private static final String BACKUP_DISABLE_PROPERTY = "ro.backup.disable";

    private static final String BACKUP_THREAD = "backup";

    /** Values for setting {@link Settings.Global#BACKUP_MULTI_USER_ENABLED} */
    private static final int MULTI_USER_DISABLED = 0;
    private static final int MULTI_USER_ENABLED = 1;

    private final Context mContext;

    @GuardedBy("mStateLock")
    private final File mSuppressFile;

    private final boolean mGlobalDisable;
    private final Object mStateLock = new Object();

    private volatile BackupManagerService mService;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public Trampoline(Context context) {
        mContext = context;
        mGlobalDisable = isBackupDisabled();
        mSuppressFile = getSuppressFile();
        mSuppressFile.getParentFile().mkdirs();

        mHandlerThread = new HandlerThread(BACKUP_THREAD, Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    protected boolean isBackupDisabled() {
        return SystemProperties.getBoolean(BACKUP_DISABLE_PROPERTY, false);
    }

    private boolean isMultiUserEnabled() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.BACKUP_MULTI_USER_ENABLED,
                MULTI_USER_DISABLED)
                == MULTI_USER_ENABLED;
    }

    protected int binderGetCallingUserId() {
        return Binder.getCallingUserHandle().getIdentifier();
    }

    protected int binderGetCallingUid() {
        return Binder.getCallingUid();
    }

    protected File getSuppressFile() {
        return new File(new File(Environment.getDataDirectory(), "backup"),
                BACKUP_SUPPRESS_FILENAME);
    }

    protected Context getContext() {
        return mContext;
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
                        if (!mSuppressFile.exists()) {
                            mService = createBackupManagerService();
                        } else {
                            Slog.i(TAG, "Backup service inactive");
                        }
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                });
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is unlocked.
     * Starts the backup service for this user if it's the system user or if the service supports
     * multi-user. Offloads work onto the handler thread {@link #mHandlerThread} to keep unlock time
     * low.
     */
    void unlockUser(int userId) {
        if (userId != UserHandle.USER_SYSTEM && !isMultiUserEnabled()) {
            Slog.i(TAG, "Multi-user disabled, cannot start service for user: " + userId);
            return;
        }

        postToHandler(() -> startServiceForUser(userId));
    }

    private void startServiceForUser(int userId) {
        BackupManagerService service = mService;
        if (service != null) {
            Slog.i(TAG, "Starting service for user: " + userId);
            service.startServiceForUser(userId);
        }
    }

    /**
     * Called from {@link BackupManagerService.Lifecycle} when a user {@code userId} is stopped.
     * Offloads work onto the handler thread {@link #mHandlerThread} to keep stopping time low.
     */
    void stopUser(int userId) {
        if (userId != UserHandle.USER_SYSTEM && !isMultiUserEnabled()) {
            Slog.i(TAG, "Multi-user disabled, cannot stop service for user: " + userId);
            return;
        }

        postToHandler(
                () -> {
                    BackupManagerService service = mService;
                    if (service != null) {
                        Slog.i(TAG, "Stopping service for user: " + userId);
                        service.stopServiceForUser(userId);
                    }
                });
    }

    /**
     * Only privileged callers should be changing the backup state. This method only acts on {@link
     * UserHandle#USER_SYSTEM} and is a no-op if passed non-system users. Deactivating backup in the
     * system user also deactivates backup in all users.
     */
    public void setBackupServiceActive(int userId, boolean makeActive) {
        int caller = binderGetCallingUid();
        if (caller != Process.SYSTEM_UID && caller != Process.ROOT_UID) {
            throw new SecurityException("No permission to configure backup activity");
        }

        if (mGlobalDisable) {
            Slog.i(TAG, "Backup service not supported");
            return;
        }

        if (userId != UserHandle.USER_SYSTEM) {
            Slog.i(TAG, "Cannot set backup service activity for non-system user: " + userId);
            return;
        }

        if (makeActive == isBackupServiceActive(userId)) {
            Slog.i(TAG, "No change in backup service activity");
            return;
        }

        synchronized (mStateLock) {
            Slog.i(TAG, "Making backup " + (makeActive ? "" : "in") + "active");
            if (makeActive) {
                mService = createBackupManagerService();
                mSuppressFile.delete();
                startServiceForUser(userId);
            } else {
                mService = null;
                try {
                    mSuppressFile.createNewFile();
                } catch (IOException e) {
                    Slog.e(TAG, "Unable to persist backup service inactivity");
                }
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
        // TODO: http://b/22388012
        if (userId == UserHandle.USER_SYSTEM) {
            synchronized (mStateLock) {
                return mService != null;
            }
        }
        return false;
    }

    @Override
    public void dataChangedForUser(int userId, String packageName) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.dataChanged(userId, packageName);
        }
    }

    @Override
    public void dataChanged(String packageName) throws RemoteException {
        dataChangedForUser(binderGetCallingUserId(), packageName);
    }

    @Override
    public void initializeTransportsForUser(
            int userId, String[] transportNames, IBackupObserver observer) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.initializeTransports(userId, transportNames, observer);
        }
    }

    @Override
    public void clearBackupDataForUser(int userId, String transportName, String packageName)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.clearBackupData(userId, transportName, packageName);
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
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.agentConnected(userId, packageName, agent);
        }
    }

    @Override
    public void agentConnected(String packageName, IBinder agent) throws RemoteException {
        agentConnectedForUser(binderGetCallingUserId(), packageName, agent);
    }

    @Override
    public void agentDisconnectedForUser(int userId, String packageName) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.agentDisconnected(userId, packageName);
        }
    }

    @Override
    public void agentDisconnected(String packageName) throws RemoteException {
        agentDisconnectedForUser(binderGetCallingUserId(), packageName);
    }

    @Override
    public void restoreAtInstallForUser(int userId, String packageName, int token)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.restoreAtInstall(userId, packageName, token);
        }
    }

    @Override
    public void restoreAtInstall(String packageName, int token) throws RemoteException {
        restoreAtInstallForUser(binderGetCallingUserId(), packageName, token);
    }

    @Override
    public void setBackupEnabledForUser(@UserIdInt int userId, boolean isEnabled)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.setBackupEnabled(userId, isEnabled);
        }
    }

    @Override
    public void setBackupEnabled(boolean isEnabled) throws RemoteException {
        setBackupEnabledForUser(binderGetCallingUserId(), isEnabled);
    }

    @Override
    public void setAutoRestoreForUser(int userId, boolean doAutoRestore) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.setAutoRestore(userId, doAutoRestore);
        }
    }

    @Override
    public void setAutoRestore(boolean doAutoRestore) throws RemoteException {
        setAutoRestoreForUser(binderGetCallingUserId(), doAutoRestore);
    }

    @Override
    public boolean isBackupEnabledForUser(@UserIdInt int userId) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.isBackupEnabled(userId) : false;
    }

    @Override
    public boolean isBackupEnabled() throws RemoteException {
        return isBackupEnabledForUser(binderGetCallingUserId());
    }

    @Override
    public boolean setBackupPassword(String currentPw, String newPw) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.setBackupPassword(currentPw, newPw) : false;
    }

    @Override
    public boolean hasBackupPassword() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.hasBackupPassword() : false;
    }

    @Override
    public void backupNowForUser(@UserIdInt int userId) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.backupNow(userId);
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
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.adbBackup(userId, fd, includeApks, includeObbs, includeShared, doWidgets,
                    allApps, allIncludesSystem, doCompress, doKeyValue, packageNames);
        }
    }

    @Override
    public void fullTransportBackupForUser(int userId, String[] packageNames)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.fullTransportBackup(userId, packageNames);
        }
    }

    @Override
    public void adbRestore(@UserIdInt int userId, ParcelFileDescriptor fd) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.adbRestore(userId, fd);
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
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.acknowledgeAdbBackupOrRestore(userId, token, allow,
                    curPassword, encryptionPassword, observer);
        }
    }

    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow, String curPassword,
            String encryptionPassword, IFullBackupRestoreObserver observer)
                    throws RemoteException {
        BackupManagerService svc = mService;
        acknowledgeFullBackupOrRestoreForUser(
                binderGetCallingUserId(), token, allow, curPassword, encryptionPassword, observer);
    }


    @Override
    public String getCurrentTransportForUser(int userId) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getCurrentTransport(userId) : null;
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
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getCurrentTransportComponent(userId) : null;
    }

    @Override
    public String[] listAllTransportsForUser(int userId) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.listAllTransports(userId) : null;
    }

    @Override
    public String[] listAllTransports() throws RemoteException {
        return listAllTransportsForUser(binderGetCallingUserId());
    }

    @Override
    public ComponentName[] listAllTransportComponentsForUser(int userId) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.listAllTransportComponents(userId) : null;
    }

    @Override
    public String[] getTransportWhitelist() {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getTransportWhitelist() : null;
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
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.updateTransportAttributes(
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
        BackupManagerService svc = mService;
        return (svc != null) ? svc.selectBackupTransport(userId, transport) : null;
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        return selectBackupTransportForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public void selectBackupTransportAsyncForUser(int userId, ComponentName transport,
            ISelectBackupTransportCallback listener) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.selectBackupTransportAsync(userId, transport, listener);
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
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getConfigurationIntent(userId, transport) : null;
    }

    @Override
    public Intent getConfigurationIntent(String transport)
            throws RemoteException {
        return getConfigurationIntentForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public String getDestinationStringForUser(int userId, String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDestinationString(userId, transport) : null;
    }

    @Override
    public String getDestinationString(String transport) throws RemoteException {
        return getDestinationStringForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public Intent getDataManagementIntentForUser(int userId, String transport)
            throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDataManagementIntent(userId, transport) : null;
    }

    @Override
    public Intent getDataManagementIntent(String transport)
            throws RemoteException {
        return getDataManagementIntentForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public String getDataManagementLabelForUser(int userId, String transport)
            throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDataManagementLabel(userId, transport) : null;
    }

    @Override
    public String getDataManagementLabel(String transport)
            throws RemoteException {
        return getDataManagementLabelForUser(binderGetCallingUserId(), transport);
    }

    @Override
    public IRestoreSession beginRestoreSessionForUser(
            int userId, String packageName, String transportID) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.beginRestoreSession(userId, packageName, transportID) : null;
    }

    @Override
    public void opComplete(int token, long result) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.opComplete(binderGetCallingUserId(), token, result);
        }
    }

    @Override
    public long getAvailableRestoreTokenForUser(int userId, String packageName) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getAvailableRestoreToken(userId, packageName) : 0;
    }

    @Override
    public boolean isAppEligibleForBackupForUser(int userId, String packageName) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.isAppEligibleForBackup(userId, packageName) : false;
    }

    @Override
    public String[] filterAppsEligibleForBackupForUser(int userId, String[] packages) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.filterAppsEligibleForBackup(userId, packages) : null;
    }

    @Override
    public int requestBackupForUser(@UserIdInt int userId, String[] packages, IBackupObserver
            observer, IBackupManagerMonitor monitor, int flags) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc == null) {
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }
        return svc.requestBackup(userId, packages, observer, monitor, flags);
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) throws RemoteException {
        return requestBackupForUser(binderGetCallingUserId(), packages,
                observer, monitor, flags);
    }

    @Override
    public void cancelBackupsForUser(@UserIdInt int userId) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.cancelBackups(userId);
        }
    }

    @Override
    public void cancelBackups() throws RemoteException {
        cancelBackupsForUser(binderGetCallingUserId());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        BackupManagerService svc = mService;
        if (svc != null) {
            svc.dump(fd, pw, args);
        } else {
            pw.println("Inactive");
        }
    }

    // Full backup/restore entry points - non-Binder; called directly
    // by the full-backup scheduled job
    /* package */ boolean beginFullBackup(@UserIdInt int userId, FullBackupJob scheduledJob) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.beginFullBackup(userId, scheduledJob) : false;
    }

    /* package */ void endFullBackup(@UserIdInt int userId) {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.endFullBackup(userId);
        }
    }
}
