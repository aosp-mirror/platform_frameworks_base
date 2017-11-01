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

package com.android.server.backup.internal;

import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.content.pm.PackageInfo;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.RefactoredBackupManagerService;

import java.io.File;

public class PerformClearTask implements Runnable {

    private RefactoredBackupManagerService backupManagerService;
    IBackupTransport mTransport;
    PackageInfo mPackage;

    PerformClearTask(RefactoredBackupManagerService backupManagerService,
            IBackupTransport transport, PackageInfo packageInfo) {
        this.backupManagerService = backupManagerService;
        mTransport = transport;
        mPackage = packageInfo;
    }

    public void run() {
        try {
            // Clear the on-device backup state to ensure a full backup next time
            File stateDir = new File(backupManagerService.getBaseStateDir(),
                    mTransport.transportDirName());
            File stateFile = new File(stateDir, mPackage.packageName);
            stateFile.delete();

            // Tell the transport to remove all the persistent storage for the app
            // TODO - need to handle failures
            mTransport.clearBackupData(mPackage);
        } catch (Exception e) {
            Slog.e(TAG, "Transport threw clearing data for " + mPackage + ": " + e.getMessage());
        } finally {
            try {
                // TODO - need to handle failures
                mTransport.finishBackup();
            } catch (Exception e) {
                // Nothing we can do here, alas
                Slog.e(TAG, "Unable to mark clear operation finished: " + e.getMessage());
            }

            // Last but not least, release the cpu
            backupManagerService.getWakelock().release();
        }
    }
}
