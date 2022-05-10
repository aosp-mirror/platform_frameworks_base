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

import static com.android.server.backup.BackupManagerService.TAG;

import android.content.pm.PackageInfo;
import android.util.Slog;

import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;

import java.io.File;

public class PerformClearTask implements Runnable {
    private final UserBackupManagerService mBackupManagerService;
    private final TransportManager mTransportManager;
    private final TransportConnection mTransportConnection;
    private final PackageInfo mPackage;
    private final OnTaskFinishedListener mListener;

    PerformClearTask(UserBackupManagerService backupManagerService,
            TransportConnection transportConnection, PackageInfo packageInfo,
            OnTaskFinishedListener listener) {
        mBackupManagerService = backupManagerService;
        mTransportManager = backupManagerService.getTransportManager();
        mTransportConnection = transportConnection;
        mPackage = packageInfo;
        mListener = listener;
    }

    public void run() {
        String callerLogString = "PerformClearTask.run()";
        BackupTransportClient transport = null;
        try {
            // Clear the on-device backup state to ensure a full backup next time
            String transportDirName =
                    mTransportManager.getTransportDirName(
                            mTransportConnection.getTransportComponent());
            File stateDir = new File(mBackupManagerService.getBaseStateDir(), transportDirName);
            File stateFile = new File(stateDir, mPackage.packageName);
            stateFile.delete();

            transport = mTransportConnection.connectOrThrow(callerLogString);
            // Tell the transport to remove all the persistent storage for the app
            // TODO - need to handle failures
            transport.clearBackupData(mPackage);
        } catch (Exception e) {
            Slog.e(TAG, "Transport threw clearing data for " + mPackage + ": " + e.getMessage());
        } finally {
            if (transport != null) {
                try {
                    // TODO - need to handle failures
                    transport.finishBackup();
                } catch (Exception e) {
                    // Nothing we can do here, alas
                    Slog.e(TAG, "Unable to mark clear operation finished: " + e.getMessage());
                }
            }
            mListener.onFinished(callerLogString);
            // Last but not least, release the cpu
            mBackupManagerService.getWakelock().release();
        }
    }
}
