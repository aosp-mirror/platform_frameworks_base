/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License.
 */

package android.app.backup;

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Wrapper around {@link BackupManagerMonitor} that helps with IPC between the caller of backup
 * APIs and the backup service.
 *
 * The caller implements {@link BackupManagerMonitor} and passes it into framework APIs that run on
 * the caller's process. Those framework APIs will then wrap it around this class when doing the
 * actual IPC.
 *
 * @hide
 */
@VisibleForTesting
public class BackupManagerMonitorWrapper extends IBackupManagerMonitor.Stub {
    @Nullable
    private final BackupManagerMonitor mMonitor;

    public BackupManagerMonitorWrapper(@Nullable BackupManagerMonitor monitor) {
        mMonitor = monitor;
    }

    @Override
    public void onEvent(final Bundle event) throws RemoteException {
        if (mMonitor == null) {
            // It's valid for the underlying monitor to be null, so just return.
            return;
        }
        mMonitor.onEvent(event);
    }
}
