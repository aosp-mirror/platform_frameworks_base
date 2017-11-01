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

package com.android.server.backup.fullbackup;

import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.app.backup.IFullBackupRestoreObserver;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Generic driver skeleton for full backup operations.
 */
public abstract class FullBackupTask implements Runnable {

    IFullBackupRestoreObserver mObserver;

    FullBackupTask(IFullBackupRestoreObserver observer) {
        mObserver = observer;
    }

    // wrappers for observer use
    final void sendStartBackup() {
        if (mObserver != null) {
            try {
                mObserver.onStartBackup();
            } catch (RemoteException e) {
                Slog.w(TAG, "full backup observer went away: startBackup");
                mObserver = null;
            }
        }
    }

    final void sendOnBackupPackage(String name) {
        if (mObserver != null) {
            try {
                // TODO: use a more user-friendly name string
                mObserver.onBackupPackage(name);
            } catch (RemoteException e) {
                Slog.w(TAG, "full backup observer went away: backupPackage");
                mObserver = null;
            }
        }
    }

    final void sendEndBackup() {
        if (mObserver != null) {
            try {
                mObserver.onEndBackup();
            } catch (RemoteException e) {
                Slog.w(TAG, "full backup observer went away: endBackup");
                mObserver = null;
            }
        }
    }
}
