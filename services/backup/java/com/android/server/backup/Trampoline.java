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
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A proxy to BackupManagerService implementation.
 *
 * <p>This is an external interface to the BackupManagerService which is being accessed via
 * published binder (see BackupManagerService$Lifecycle). This lets us turn down the heavy
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

    private final Context mContext;

    @GuardedBy("mStateLock")
    private final File mSuppressFile;

    private final boolean mGlobalDisable;
    private final Object mStateLock = new Object();

    private volatile BackupManagerService mService;
    private HandlerThread mHandlerThread;

    public Trampoline(Context context) {
        mContext = context;
        mGlobalDisable = isBackupDisabled();
        mSuppressFile = getSuppressFile();
        mSuppressFile.getParentFile().mkdirs();
    }

    protected boolean isBackupDisabled() {
        return SystemProperties.getBoolean(BACKUP_DISABLE_PROPERTY, false);
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
        return BackupManagerService.create(mContext, this, mHandlerThread);
    }

    /**
     * Initialize {@link BackupManagerService} if the backup service is not disabled. Only the
     * system user can initialize the service.
     */
    /* package */ void initializeService(int userId) {
        if (mGlobalDisable) {
            Slog.i(TAG, "Backup service not supported");
            return;
        }

        if (userId != UserHandle.USER_SYSTEM) {
            Slog.i(TAG, "Cannot initialize backup service for non-system user: " + userId);
            return;
        }

        synchronized (mStateLock) {
            if (!mSuppressFile.exists()) {
                mService = createBackupManagerService();
            } else {
                Slog.i(TAG, "Backup service inactive");
            }
        }
    }

    /**
     * Called from {@link BackupManagerService$Lifecycle} when the system user is unlocked. Attempts
     * to initialize {@link BackupManagerService} and set backup state for the system user.
     *
     * @see BackupManagerService#unlockSystemUser()
     */
    void unlockSystemUser() {
        mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();

        Handler h = new Handler(mHandlerThread.getLooper());
        h.post(
                () -> {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "backup init");
                    initializeService(UserHandle.USER_SYSTEM);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                    BackupManagerService service = mService;
                    if (service != null) {
                        Slog.i(TAG, "Unlocking system user");
                        service.unlockSystemUser();
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
    public void dataChanged(String packageName) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.dataChanged(packageName);
        }
    }

    @Override
    public void initializeTransports(String[] transportNames, IBackupObserver observer)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.initializeTransports(transportNames, observer);
        }
    }

    @Override
    public void clearBackupData(String transportName, String packageName)
            throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.clearBackupData(transportName, packageName);
        }
    }

    @Override
    public void agentConnected(String packageName, IBinder agent) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.agentConnected(packageName, agent);
        }
    }

    @Override
    public void agentDisconnected(String packageName) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.agentDisconnected(packageName);
        }
    }

    @Override
    public void restoreAtInstall(String packageName, int token) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.restoreAtInstall(packageName, token);
        }
    }

    @Override
    public void setBackupEnabled(boolean isEnabled) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.setBackupEnabled(isEnabled);
        }
    }

    @Override
    public void setAutoRestore(boolean doAutoRestore) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.setAutoRestore(doAutoRestore);
        }
    }

    @Override
    public void setBackupProvisioned(boolean isProvisioned) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.setBackupProvisioned(isProvisioned);
        }
    }

    @Override
    public boolean isBackupEnabled() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.isBackupEnabled() : false;
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
    public void backupNow() throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.backupNow();
        }
    }

    @Override
    public void adbBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
            boolean includeShared, boolean doWidgets, boolean allApps,
            boolean allIncludesSystem, boolean doCompress, boolean doKeyValue, String[] packageNames)
                    throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.adbBackup(fd, includeApks, includeObbs, includeShared, doWidgets,
                    allApps, allIncludesSystem, doCompress, doKeyValue, packageNames);
        }
    }

    @Override
    public void fullTransportBackup(String[] packageNames) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.fullTransportBackup(packageNames);
        }
    }

    @Override
    public void adbRestore(ParcelFileDescriptor fd) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.adbRestore(fd);
        }
    }

    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow, String curPassword,
            String encryptionPassword, IFullBackupRestoreObserver observer)
                    throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.acknowledgeAdbBackupOrRestore(token, allow,
                    curPassword, encryptionPassword, observer);
        }
    }

    @Override
    public String getCurrentTransport() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getCurrentTransport() : null;
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or
     * {@code null} if no transport selected or if the transport selected is not registered.
     */
    @Override
    @Nullable
    public ComponentName getCurrentTransportComponent() {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getCurrentTransportComponent() : null;
    }

    @Override
    public String[] listAllTransports() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.listAllTransports() : null;
    }

    @Override
    public ComponentName[] listAllTransportComponents() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.listAllTransportComponents() : null;
    }

    @Override
    public String[] getTransportWhitelist() {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getTransportWhitelist() : null;
    }

    @Override
    public void updateTransportAttributes(
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            String dataManagementLabel) {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.updateTransportAttributes(
                    transportComponent,
                    name,
                    configurationIntent,
                    currentDestinationString,
                    dataManagementIntent,
                    dataManagementLabel);
        }
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.selectBackupTransport(transport) : null;
    }

    @Override
    public void selectBackupTransportAsync(ComponentName transport,
            ISelectBackupTransportCallback listener) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.selectBackupTransportAsync(transport, listener);
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
    public Intent getConfigurationIntent(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getConfigurationIntent(transport) : null;
    }

    @Override
    public String getDestinationString(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDestinationString(transport) : null;
    }

    @Override
    public Intent getDataManagementIntent(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDataManagementIntent(transport) : null;
    }

    @Override
    public String getDataManagementLabel(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getDataManagementLabel(transport) : null;
    }

    @Override
    public IRestoreSession beginRestoreSession(String packageName, String transportID)
            throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.beginRestoreSession(packageName, transportID) : null;
    }

    @Override
    public void opComplete(int token, long result) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.opComplete(token, result);
        }
    }

    @Override
    public long getAvailableRestoreToken(String packageName) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getAvailableRestoreToken(packageName) : 0;
    }

    @Override
    public boolean isAppEligibleForBackup(String packageName) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.isAppEligibleForBackup(packageName) : false;
    }

    @Override
    public String[] filterAppsEligibleForBackup(String[] packages) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.filterAppsEligibleForBackup(packages) : null;
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc == null) {
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }
        return svc.requestBackup(packages, observer, monitor, flags);
    }

    @Override
    public void cancelBackups() throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.cancelBackups();
        }
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
    /* package */ boolean beginFullBackup(FullBackupJob scheduledJob) {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.beginFullBackup(scheduledJob) : false;
    }

    /* package */ void endFullBackup() {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.endFullBackup();
        }
    }
}
