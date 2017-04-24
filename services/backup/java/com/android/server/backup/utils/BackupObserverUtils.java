package com.android.server.backup.utils;

import android.app.backup.BackupProgress;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.RefactoredBackupManagerService;

/**
 * Utility methods to communicate with BackupObserver.
 */
public class BackupObserverUtils {
    /**
     * Wraps {@link IBackupObserver#onUpdate(String, BackupProgress)} to handle RemoteException,
     * so that the caller doesn't have to.
     */
    public static void sendBackupOnUpdate(IBackupObserver observer, String packageName,
            BackupProgress progress) {
        if (observer != null) {
            try {
                observer.onUpdate(packageName, progress);
            } catch (RemoteException e) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Backup observer went away: onUpdate");
                }
            }
        }
    }

    /**
     * Wraps {@link IBackupObserver#onResult(String, int)} to handle RemoteException, so that the
     * caller doesn't have to.
     */
    public static void sendBackupOnPackageResult(IBackupObserver observer, String packageName,
            int status) {
        if (observer != null) {
            try {
                observer.onResult(packageName, status);
            } catch (RemoteException e) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Backup observer went away: onResult");
                }
            }
        }
    }

    /**
     * Wraps {@link IBackupObserver#backupFinished(int)} to handle RemoteException, so that the
     * caller doesn't have to.
     */
    public static void sendBackupFinished(IBackupObserver observer, int status) {
        if (observer != null) {
            try {
                observer.backupFinished(status);
            } catch (RemoteException e) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Backup observer went away: backupFinished");
                }
            }
        }
    }
}
