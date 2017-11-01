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

package com.android.server.backup.restore;

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.MORE_DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.TIMEOUT_FULL_BACKUP_INTERVAL;

import android.util.Slog;

import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.RefactoredBackupManagerService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Used for synchronizing doRestoreFinished during adb restore.
 */
public class AdbRestoreFinishedLatch implements BackupRestoreTask {

    private static final String TAG = "AdbRestoreFinishedLatch";
    private RefactoredBackupManagerService backupManagerService;
    final CountDownLatch mLatch;
    private final int mCurrentOpToken;

    public AdbRestoreFinishedLatch(RefactoredBackupManagerService backupManagerService,
            int currentOpToken) {
        this.backupManagerService = backupManagerService;
        mLatch = new CountDownLatch(1);
        mCurrentOpToken = currentOpToken;
    }

    void await() {
        boolean latched = false;
        try {
            latched = mLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Slog.w(TAG, "Interrupted!");
        }
    }

    @Override
    public void execute() {
        // Unused
    }

    @Override
    public void operationComplete(long result) {
        if (MORE_DEBUG) {
            Slog.w(TAG, "adb onRestoreFinished() complete");
        }
        mLatch.countDown();
        backupManagerService.removeOperation(mCurrentOpToken);
    }

    @Override
    public void handleCancel(boolean cancelAll) {
        if (DEBUG) {
            Slog.w(TAG, "adb onRestoreFinished() timed out");
        }
        mLatch.countDown();
        backupManagerService.removeOperation(mCurrentOpToken);
    }
}
