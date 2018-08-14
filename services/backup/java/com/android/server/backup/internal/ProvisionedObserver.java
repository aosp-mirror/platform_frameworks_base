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

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;

import android.database.ContentObserver;
import android.os.Handler;
import android.util.Slog;

import com.android.server.backup.BackupManagerService;
import com.android.server.backup.KeyValueBackupJob;

public class ProvisionedObserver extends ContentObserver {

    private BackupManagerService backupManagerService;

    public ProvisionedObserver(
            BackupManagerService backupManagerService, Handler handler) {
        super(handler);
        this.backupManagerService = backupManagerService;
    }

    public void onChange(boolean selfChange) {
        final boolean wasProvisioned = backupManagerService.isProvisioned();
        final boolean isProvisioned = backupManagerService.deviceIsProvisioned();
        // latch: never unprovision
        backupManagerService.setProvisioned(wasProvisioned || isProvisioned);
        if (MORE_DEBUG) {
            Slog.d(TAG, "Provisioning change: was=" + wasProvisioned
                    + " is=" + isProvisioned + " now=" + backupManagerService.isProvisioned());
        }

        synchronized (backupManagerService.getQueueLock()) {
            if (backupManagerService.isProvisioned() && !wasProvisioned
                    && backupManagerService.isEnabled()) {
                // we're now good to go, so start the backup alarms
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Now provisioned, so starting backups");
                }
                KeyValueBackupJob.schedule(backupManagerService.getContext(),
                        backupManagerService.getConstants());
                backupManagerService.scheduleNextFullBackupJob(0);
            }
        }
    }
}
