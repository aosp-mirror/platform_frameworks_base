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

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.app.backup.BackupProgress;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.util.Slog;

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
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: onUpdate");
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
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: onResult");
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
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: backupFinished");
                }
            }
        }
    }
}
