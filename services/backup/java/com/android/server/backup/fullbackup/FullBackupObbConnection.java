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

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.OP_TYPE_BACKUP_WAIT;

import android.app.backup.IBackupManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.backup.IObbBackupService;
import com.android.internal.util.Preconditions;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.utils.FullBackupUtils;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Full backup/restore to a file/socket.
 */
public class FullBackupObbConnection implements ServiceConnection {

    private UserBackupManagerService backupManagerService;
    volatile IObbBackupService mService;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    public FullBackupObbConnection(UserBackupManagerService backupManagerService) {
        this.backupManagerService = backupManagerService;
        mService = null;
        mAgentTimeoutParameters = Preconditions.checkNotNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
    }

    public void establish() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Initiating bind of OBB service on " + this);
        }
        Intent obbIntent = new Intent().setComponent(new ComponentName(
                "com.android.sharedstoragebackup",
                "com.android.sharedstoragebackup.ObbBackupService"));
        backupManagerService.getContext().bindServiceAsUser(
                obbIntent, this, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    public void tearDown() {
        backupManagerService.getContext().unbindService(this);
    }

    public boolean backupObbs(PackageInfo pkg, OutputStream out) {
        boolean success = false;
        waitForConnection();

        ParcelFileDescriptor[] pipes = null;
        try {
            pipes = ParcelFileDescriptor.createPipe();
            int token = backupManagerService.generateRandomIntegerToken();
            long fullBackupAgentTimeoutMillis =
                    mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
            backupManagerService.prepareOperationTimeout(
                    token, fullBackupAgentTimeoutMillis, null, OP_TYPE_BACKUP_WAIT);
            mService.backupObbs(pkg.packageName, pipes[1], token,
                    backupManagerService.getBackupManagerBinder());
            FullBackupUtils.routeSocketDataToOutput(pipes[0], out);
            success = backupManagerService.waitUntilOperationComplete(token);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to back up OBBs for " + pkg, e);
        } finally {
            try {
                out.flush();
                if (pipes != null) {
                    if (pipes[0] != null) {
                        pipes[0].close();
                    }
                    if (pipes[1] != null) {
                        pipes[1].close();
                    }
                }
            } catch (IOException e) {
                Slog.w(TAG, "I/O error closing down OBB backup", e);
            }
        }
        return success;
    }

    public void restoreObbFile(String pkgName, ParcelFileDescriptor data,
            long fileSize, int type, String path, long mode, long mtime,
            int token, IBackupManager callbackBinder) {
        waitForConnection();

        try {
            mService.restoreObbFile(pkgName, data, fileSize, type, path, mode, mtime,
                    token, callbackBinder);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to restore OBBs for " + pkgName, e);
        }
    }

    private void waitForConnection() {
        synchronized (this) {
            while (mService == null) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "...waiting for OBB service binding...");
                }
                try {
                    this.wait();
                } catch (InterruptedException e) { /* never interrupted */ }
            }
            if (MORE_DEBUG) {
                Slog.i(TAG, "Connected to OBB service; continuing");
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (this) {
            mService = IObbBackupService.Stub.asInterface(service);
            if (MORE_DEBUG) {
                Slog.i(TAG, "OBB service connection " + mService + " connected on " + this);
            }
            this.notifyAll();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (this) {
            mService = null;
            if (MORE_DEBUG) {
                Slog.i(TAG, "OBB service connection disconnected on " + this);
            }
            this.notifyAll();
        }
    }

}
