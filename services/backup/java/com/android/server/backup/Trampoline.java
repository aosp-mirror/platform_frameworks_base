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

import android.app.backup.BackupManager;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupObserver;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.DumpUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;


/**
 * A proxy to BackupManagerService implementation.
 *
 * This is an external interface to the BackupManagerService which is being accessed via published
 * binder (see BackupManagerService$Lifecycle). This lets us turn down the heavy implementation
 * object on the fly without disturbing binders that have been cached somewhere in the system.
 *
 * This is where it is decided whether backup subsystem is available. It can be disabled with the
 * following two methods:
 *
 * <ul>
 * <li> Temporarily - create a file named Trampoline.BACKUP_SUPPRESS_FILENAME, or
 * <li> Product level - set Trampoline.BACKUP_DISABLE_PROPERTY system property to true.
 * </ul>
 */
public class Trampoline extends IBackupManager.Stub {
    static final String TAG = "BackupManagerService";
    static final boolean DEBUG_TRAMPOLINE = false;

    // When this file is present, the backup service is inactive
    static final String BACKUP_SUPPRESS_FILENAME = "backup-suppress";

    // Product-level suppression of backup/restore
    static final String BACKUP_DISABLE_PROPERTY = "ro.backup.disable";

    final Context mContext;
    final File mSuppressFile;   // existence testing & creating synchronized on 'this'
    final boolean mGlobalDisable;
    volatile BackupManagerServiceInterface mService;

    public Trampoline(Context context) {
        mContext = context;
        mGlobalDisable = isBackupDisabled();
        mSuppressFile = getSuppressFile();
        mSuppressFile.getParentFile().mkdirs();
    }

    protected BackupManagerServiceInterface createService() {
        if (isRefactoredServiceEnabled()) {
            Slog.i(TAG, "Instantiating RefactoredBackupManagerService");
            return createRefactoredBackupManagerService();
        }

        Slog.i(TAG, "Instantiating BackupManagerService");
        return createBackupManagerService();
    }

    protected boolean isBackupDisabled() {
        return SystemProperties.getBoolean(BACKUP_DISABLE_PROPERTY, false);
    }

    protected boolean isRefactoredServiceEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BACKUP_REFACTORED_SERVICE_DISABLED, 1) == 0;
    }

    protected int binderGetCallingUid() {
        return Binder.getCallingUid();
    }

    protected File getSuppressFile() {
        return new File(new File(Environment.getDataDirectory(), "backup"),
                BACKUP_SUPPRESS_FILENAME);
    }

    protected BackupManagerServiceInterface createRefactoredBackupManagerService() {
        return new RefactoredBackupManagerService(mContext, this);
    }

    protected BackupManagerServiceInterface createBackupManagerService() {
        return new BackupManagerService(mContext, this);
    }

    // internal control API
    public void initialize(final int whichUser) {
        // Note that only the owner user is currently involved in backup/restore
        // TODO: http://b/22388012
        if (whichUser == UserHandle.USER_SYSTEM) {
            // Does this product support backup/restore at all?
            if (mGlobalDisable) {
                Slog.i(TAG, "Backup/restore not supported");
                return;
            }

            synchronized (this) {
                if (!mSuppressFile.exists()) {
                    mService = createService();
                } else {
                    Slog.i(TAG, "Backup inactive in user " + whichUser);
                }
            }
        }
    }

    public void setBackupServiceActive(final int userHandle, boolean makeActive) {
        // Only the DPM should be changing the active state of backup
        final int caller = binderGetCallingUid();
        if (caller != Process.SYSTEM_UID
                && caller != Process.ROOT_UID) {
            throw new SecurityException("No permission to configure backup activity");
        }

        if (mGlobalDisable) {
            Slog.i(TAG, "Backup/restore not supported");
            return;
        }
        // TODO: http://b/22388012
        if (userHandle == UserHandle.USER_SYSTEM) {
            synchronized (this) {
                if (makeActive != isBackupServiceActive(userHandle)) {
                    Slog.i(TAG, "Making backup "
                            + (makeActive ? "" : "in") + "active in user " + userHandle);
                    if (makeActive) {
                        mService = createService();
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
        }
    }

    /**
     * Querying activity state of backup service. Calling this method before initialize yields
     * undefined result.
     * @param userHandle The user in which the activity state of backup service is queried.
     * @return true if the service is active.
     */
    public boolean isBackupServiceActive(final int userHandle) {
        // TODO: http://b/22388012
        if (userHandle == UserHandle.USER_SYSTEM) {
            synchronized (this) {
                return mService != null;
            }
        }
        return false;
    }

    // IBackupManager binder API
    @Override
    public void dataChanged(String packageName) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.dataChanged(packageName);
        }
    }

    @Override
    public void initializeTransports(String[] transportNames, IBackupObserver observer)
            throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.initializeTransports(transportNames, observer);
        }
    }

    @Override
    public void clearBackupData(String transportName, String packageName)
            throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.clearBackupData(transportName, packageName);
        }
    }

    @Override
    public void agentConnected(String packageName, IBinder agent) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.agentConnected(packageName, agent);
        }
    }

    @Override
    public void agentDisconnected(String packageName) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.agentDisconnected(packageName);
        }
    }

    @Override
    public void restoreAtInstall(String packageName, int token) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.restoreAtInstall(packageName, token);
        }
    }

    @Override
    public void setBackupEnabled(boolean isEnabled) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.setBackupEnabled(isEnabled);
        }
    }

    @Override
    public void setAutoRestore(boolean doAutoRestore) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.setAutoRestore(doAutoRestore);
        }
    }

    @Override
    public void setBackupProvisioned(boolean isProvisioned) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.setBackupProvisioned(isProvisioned);
        }
    }

    @Override
    public boolean isBackupEnabled() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.isBackupEnabled() : false;
    }

    @Override
    public boolean setBackupPassword(String currentPw, String newPw) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.setBackupPassword(currentPw, newPw) : false;
    }

    @Override
    public boolean hasBackupPassword() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.hasBackupPassword() : false;
    }

    @Override
    public void backupNow() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.backupNow();
        }
    }

    @Override
    public void adbBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
            boolean includeShared, boolean doWidgets, boolean allApps,
            boolean allIncludesSystem, boolean doCompress, boolean doKeyValue, String[] packageNames)
                    throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.adbBackup(fd, includeApks, includeObbs, includeShared, doWidgets,
                    allApps, allIncludesSystem, doCompress, doKeyValue, packageNames);
        }
    }

    @Override
    public void fullTransportBackup(String[] packageNames) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.fullTransportBackup(packageNames);
        }
    }

    @Override
    public void adbRestore(ParcelFileDescriptor fd) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.adbRestore(fd);
        }
    }

    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow, String curPassword,
            String encryptionPassword, IFullBackupRestoreObserver observer)
                    throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.acknowledgeAdbBackupOrRestore(token, allow,
                    curPassword, encryptionPassword, observer);
        }
    }

    @Override
    public String getCurrentTransport() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getCurrentTransport() : null;
    }

    @Override
    public String[] listAllTransports() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.listAllTransports() : null;
    }

    @Override
    public ComponentName[] listAllTransportComponents() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.listAllTransportComponents() : null;
    }

    @Override
    public String[] getTransportWhitelist() {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getTransportWhitelist() : null;
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.selectBackupTransport(transport) : null;
    }

    @Override
    public void selectBackupTransportAsync(ComponentName transport,
            ISelectBackupTransportCallback listener) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
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
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getConfigurationIntent(transport) : null;
    }

    @Override
    public String getDestinationString(String transport) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getDestinationString(transport) : null;
    }

    @Override
    public Intent getDataManagementIntent(String transport) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getDataManagementIntent(transport) : null;
    }

    @Override
    public String getDataManagementLabel(String transport) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getDataManagementLabel(transport) : null;
    }

    @Override
    public IRestoreSession beginRestoreSession(String packageName, String transportID)
            throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.beginRestoreSession(packageName, transportID) : null;
    }

    @Override
    public void opComplete(int token, long result) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.opComplete(token, result);
        }
    }

    @Override
    public long getAvailableRestoreToken(String packageName) {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.getAvailableRestoreToken(packageName) : 0;
    }

    @Override
    public boolean isAppEligibleForBackup(String packageName) {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.isAppEligibleForBackup(packageName) : false;
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc == null) {
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }
        return svc.requestBackup(packages, observer, monitor, flags);
    }

    @Override
    public void cancelBackups() throws RemoteException {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.cancelBackups();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.dump(fd, pw, args);
        } else {
            pw.println("Inactive");
        }
    }

    // Full backup/restore entry points - non-Binder; called directly
    // by the full-backup scheduled job
    /* package */ boolean beginFullBackup(FullBackupJob scheduledJob) {
        BackupManagerServiceInterface svc = mService;
        return (svc != null) ? svc.beginFullBackup(scheduledJob) : false;
    }

    /* package */ void endFullBackup() {
        BackupManagerServiceInterface svc = mService;
        if (svc != null) {
            svc.endFullBackup();
        }
    }
}
