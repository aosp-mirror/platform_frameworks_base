/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.app.backup.IFullBackupRestoreObserver;
import android.os.RemoteException;
import android.util.Slog;

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
                Slog.w(TAG, "full restore observer went away: startRestore");
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
                Slog.w(TAG, "full restore observer went away: restorePackage");
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
                Slog.w(TAG, "full restore observer went away: endRestore");
                observer = null;
            }
        }
        return observer;
    }
}
