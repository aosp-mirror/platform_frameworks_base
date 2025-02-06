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

import static com.android.server.backup.BackupManagerService.DEBUG;

import android.util.Slog;

import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.OperationStorage;
import com.android.server.backup.UserBackupManagerService;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Used for synchronizing doRestoreFinished during adb restore.
 */
public class AdbRestoreFinishedLatch implements BackupRestoreTask {

    private static final String TAG = "AdbRestoreFinishedLatch";
    private UserBackupManagerService backupManagerService;
    private final OperationStorage mOperationStorage;
    final CountDownLatch mLatch;
    private final int mCurrentOpToken;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    public AdbRestoreFinishedLatch(UserBackupManagerService backupManagerService,
            OperationStorage operationStorage,
            int currentOpToken) {
        this.backupManagerService = backupManagerService;
        mOperationStorage = operationStorage;
        mLatch = new CountDownLatch(1);
        mCurrentOpToken = currentOpToken;
        mAgentTimeoutParameters = Objects.requireNonNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
    }

    void await() {
        boolean latched = false;
        long fullBackupAgentTimeoutMillis =
                mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
        try {
            latched = mLatch.await(fullBackupAgentTimeoutMillis, TimeUnit.MILLISECONDS);
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
        if (DEBUG) {
            Slog.w(TAG, "adb onRestoreFinished() complete");
        }
        mLatch.countDown();
        mOperationStorage.removeOperation(mCurrentOpToken);
    }

    @Override
    public void handleCancel(boolean cancelAll) {
        Slog.w(TAG, "adb onRestoreFinished() timed out");
        mLatch.countDown();
        mOperationStorage.removeOperation(mCurrentOpToken);
    }
}
