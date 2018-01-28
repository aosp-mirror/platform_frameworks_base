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

import android.app.IBackupAgent;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.BackupManagerService;

import java.io.IOException;

/**
 * Runner that can be placed in a separate thread to do in-process invocations of the full restore
 * API asynchronously. Used by adb restore.
 */
class RestoreFileRunnable implements Runnable {

    private final IBackupAgent mAgent;
    private final FileMetadata mInfo;
    private final ParcelFileDescriptor mSocket;
    private final int mToken;
    private final BackupManagerService mBackupManagerService;

    RestoreFileRunnable(BackupManagerService backupManagerService, IBackupAgent agent,
            FileMetadata info, ParcelFileDescriptor socket, int token) throws IOException {
        mAgent = agent;
        mInfo = info;
        mToken = token;

        // This class is used strictly for process-local binder invocations.  The
        // semantics of ParcelFileDescriptor differ in this case; in particular, we
        // do not automatically get a 'dup'ed descriptor that we can can continue
        // to use asynchronously from the caller.  So, we make sure to dup it ourselves
        // before proceeding to do the restore.
        mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
        this.mBackupManagerService = backupManagerService;
    }

    @Override
    public void run() {
        try {
            mAgent.doRestoreFile(mSocket, mInfo.size, mInfo.type,
                    mInfo.domain, mInfo.path, mInfo.mode, mInfo.mtime,
                    mToken, mBackupManagerService.getBackupManagerBinder());
        } catch (RemoteException e) {
            // never happens; this is used strictly for local binder calls
        }
    }
}
