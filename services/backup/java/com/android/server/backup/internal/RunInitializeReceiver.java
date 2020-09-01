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

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.RUN_INITIALIZE_ACTION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Slog;

import com.android.server.backup.UserBackupManagerService;

import java.util.Set;

/**
 * A {@link BroadcastReceiver} for the action {@link UserBackupManagerService#RUN_INITIALIZE_ACTION}
 * that runs an initialization operation on all pending transports.
 */
public class RunInitializeReceiver extends BroadcastReceiver {
    private final UserBackupManagerService mUserBackupManagerService;

    public RunInitializeReceiver(UserBackupManagerService userBackupManagerService) {
        mUserBackupManagerService = userBackupManagerService;
    }

    public void onReceive(Context context, Intent intent) {
        if (!RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
            return;
        }

        synchronized (mUserBackupManagerService.getQueueLock()) {
            Set<String> pendingInits = mUserBackupManagerService.getPendingInits();
            if (DEBUG) {
                Slog.v(TAG, "Running a device init; " + pendingInits.size() + " pending");
            }

            if (pendingInits.size() > 0) {
                String[] transports = pendingInits.toArray(new String[pendingInits.size()]);
                mUserBackupManagerService.clearPendingInits();
                mUserBackupManagerService.initializeTransports(transports, null);
            }
        }
    }
}
