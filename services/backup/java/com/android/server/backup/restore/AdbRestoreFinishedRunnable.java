package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.os.RemoteException;

import com.android.server.backup.UserBackupManagerService;

/**
 * Runner that can be placed on a separate thread to do in-process invocation of the "restore
 * finished" API asynchronously.  Used by adb restore.
 */
public class AdbRestoreFinishedRunnable implements Runnable {

    private final IBackupAgent mAgent;
    private final int mToken;
    private final UserBackupManagerService mBackupManagerService;

    AdbRestoreFinishedRunnable(IBackupAgent agent, int token,
            UserBackupManagerService backupManagerService) {
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
