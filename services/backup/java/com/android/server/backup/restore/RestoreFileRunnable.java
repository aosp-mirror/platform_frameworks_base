package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.RefactoredBackupManagerService;

import java.io.IOException;

/**
 * Runner that can be placed in a separate thread to do in-process invocations of the full restore
 * API asynchronously. Used by adb restore.
 */
class RestoreFileRunnable implements Runnable {

    private final IBackupAgent mAgent;
    private final FileMetadata mInfo;
    private final ParcelFileDescriptor mSocket;
    private final int mToken;
    private final RefactoredBackupManagerService mBackupManagerService;

    RestoreFileRunnable(RefactoredBackupManagerService backupManagerService, IBackupAgent agent,
            FileMetadata info, ParcelFileDescriptor socket, int token) throws IOException {
        mAgent = agent;
        mInfo = info;
        mToken = token;

        // This class is used strictly for process-local binder invocations.  The
        // semantics of ParcelFileDescriptor differ in this case; in particular, we
        // do not automatically get a 'dup'ed descriptor that we can can continue
        // to use asynchronously from the caller.  So, we make sure to dup it ourselves
        // before proceeding to do the restore.
        mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
        this.mBackupManagerService = backupManagerService;
    }

    @Override
    public void run() {
        try {
            mAgent.doRestoreFile(mSocket, mInfo.size, mInfo.type,
                    mInfo.domain, mInfo.path, mInfo.mode, mInfo.mtime,
                    mToken, mBackupManagerService.getBackupManagerBinder());
        } catch (RemoteException e) {
            // never happens; this is used strictly for local binder calls
        }
    }
}
