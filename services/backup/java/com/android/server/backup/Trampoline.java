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

import android.app.backup.IBackupManager;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
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
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

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
    volatile BackupManagerService mService;

    public Trampoline(Context context) {
        mContext = context;
        File dir = new File(Environment.getSecureDataDirectory(), "backup");
        dir.mkdirs();
        mSuppressFile = new File(dir, BACKUP_SUPPRESS_FILENAME);
        mGlobalDisable = SystemProperties.getBoolean(BACKUP_DISABLE_PROPERTY, false);
    }

    // internal control API
    public void initialize(final int whichUser) {
        // Note that only the owner user is currently involved in backup/restore
        if (whichUser == UserHandle.USER_OWNER) {
            // Does this product support backup/restore at all?
            if (mGlobalDisable) {
                Slog.i(TAG, "Backup/restore not supported");
                return;
            }

            synchronized (this) {
                if (!mSuppressFile.exists()) {
                    mService = new BackupManagerService(mContext, this);
                } else {
                    Slog.i(TAG, "Backup inactive in user " + whichUser);
                }
            }
        }
    }

    public void setBackupServiceActive(final int userHandle, boolean makeActive) {
        // Only the DPM should be changing the active state of backup
        final int caller = Binder.getCallingUid();
        if (caller != Process.SYSTEM_UID
                && caller != Process.ROOT_UID) {
            throw new SecurityException("No permission to configure backup activity");
        }

        if (mGlobalDisable) {
            Slog.i(TAG, "Backup/restore not supported");
            return;
        }

        if (userHandle == UserHandle.USER_OWNER) {
            synchronized (this) {
                if (makeActive != isBackupServiceActive(userHandle)) {
                    Slog.i(TAG, "Making backup "
                            + (makeActive ? "" : "in") + "active in user " + userHandle);
                    if (makeActive) {
                        mService = new BackupManagerService(mContext, this);
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
        if (userHandle == UserHandle.USER_OWNER) {
            synchronized (this) {
                return mService != null;
            }
        }
        return false;
    }

    // IBackupManager binder API
    @Override
    public void dataChanged(String packageName) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.dataChanged(packageName);
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
    public void fullBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
            boolean includeShared, boolean doWidgets, boolean allApps,
            boolean allIncludesSystem, boolean doCompress, String[] packageNames)
                    throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.fullBackup(fd, includeApks, includeObbs, includeShared, doWidgets,
                    allApps, allIncludesSystem, doCompress, packageNames);
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
    public void fullRestore(ParcelFileDescriptor fd) throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.fullRestore(fd);
        }
    }

    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow, String curPassword,
            String encryptionPassword, IFullBackupRestoreObserver observer)
                    throws RemoteException {
        BackupManagerService svc = mService;
        if (svc != null) {
            svc.acknowledgeFullBackupOrRestore(token, allow,
                    curPassword, encryptionPassword, observer);
        }
    }

    @Override
    public String getCurrentTransport() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.getCurrentTransport() : null;
    }

    @Override
    public String[] listAllTransports() throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.listAllTransports() : null;
    }

    @Override
    public String selectBackupTransport(String transport) throws RemoteException {
        BackupManagerService svc = mService;
        return (svc != null) ? svc.selectBackupTransport(transport) : null;
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

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
