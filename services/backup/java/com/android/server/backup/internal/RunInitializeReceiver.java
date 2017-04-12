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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.util.Slog;
import com.android.server.backup.RefactoredBackupManagerService;

public class RunInitializeReceiver extends BroadcastReceiver {

    private RefactoredBackupManagerService backupManagerService;

    public RunInitializeReceiver(RefactoredBackupManagerService backupManagerService) {
        this.backupManagerService = backupManagerService;
    }

    public void onReceive(Context context, Intent intent) {
        if (RefactoredBackupManagerService.RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
            synchronized (backupManagerService.mQueueLock) {
                if (RefactoredBackupManagerService.DEBUG) {
                  Slog.v(RefactoredBackupManagerService.TAG, "Running a device init");
                }

                // Acquire the wakelock and pass it to the init thread.  it will
                // be released once init concludes.
                backupManagerService.mWakelock.acquire();

                Message msg = backupManagerService.mBackupHandler.obtainMessage(
                    RefactoredBackupManagerService.MSG_RUN_INITIALIZE);
                backupManagerService.mBackupHandler.sendMessage(msg);
            }
        }
    }
}
