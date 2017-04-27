package com.android.server.backup.utils;

import android.app.backup.IFullBackupRestoreObserver;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.RefactoredBackupManagerService;

/**
 * Utility methods to communicate with FullBackupRestoreObserver.
 */
public class FullBackupRestoreObserverUtils {
    /**
     * Wraps {@link IFullBackupRestoreObserver#onStartRestore()} to handle RemoteException, so that
     * the caller doesn't have to.
     *
     * @param observer - IFullBackupRestoreObserver to communicate with.
     * @return observer if the call worked and null if there was a communication problem.
     */
    public static IFullBackupRestoreObserver sendStartRestore(IFullBackupRestoreObserver observer) {
        if (observer != null) {
            try {
                observer.onStartRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: startRestore");
                observer = null;
            }
        }
        return observer;
    }

    /**
     * Wraps {@link IFullBackupRestoreObserver#onRestorePackage(String)} to handle RemoteException,
     * so that the caller doesn't have to.
     *
     * @param observer - IFullBackupRestoreObserver to communicate with.
     * @param name - package name.
     * @return observer if the call worked and null if there was a communication problem.
     */
    public static IFullBackupRestoreObserver sendOnRestorePackage(
            IFullBackupRestoreObserver observer, String name) {
        if (observer != null) {
            try {
                // TODO: use a more user-friendly name string
                observer.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: restorePackage");
                observer = null;
            }
        }
        return observer;
    }

    /**
     * Wraps {@link IFullBackupRestoreObserver#onEndRestore()} ()} to handle RemoteException, so
     * that the caller doesn't have to.
     *
     * @param observer - IFullBackupRestoreObserver to communicate with.
     * @return observer if the call worked and null if there was a communication problem.
     */
    public static IFullBackupRestoreObserver sendEndRestore(IFullBackupRestoreObserver observer) {
        if (observer != null) {
            try {
                observer.onEndRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: endRestore");
                observer = null;
            }
        }
        return observer;
    }
}
