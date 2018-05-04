package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.os.RemoteException;

import com.android.server.backup.BackupManagerService;

/**
 * Runner that can be placed on a separate thread to do in-process invocation of the "restore
 * finished" API asynchronously.  Used by adb restore.
 */
public class AdbRestoreFinishedRunnable implements Runnable {

    private final IBackupAgent mAgent;
    private final int mToken;
    private final BackupManagerService mBackupManagerService;

    AdbRestoreFinishedRunnable(IBackupAgent agent, int token,
            BackupManagerService backupManagerService) {
        mAgent = agent;
        mToken = token;
        mBackupManagerService = backupManagerService;
    }

    @Override
    public void run() {
        try {
            mAgent.doRestoreFinished(mToken, mBackupManagerService.getBackupManagerBinder());
        } catch (RemoteException e) {
            // never happens; this is used only for local binder calls
        }
    }
}
