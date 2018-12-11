/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.backup;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Useful for spying in {@link BackupAgent} instances since their {@link BackupAgent#onBind()} is
 * final and always points to the original instance, instead of the spy.
 *
 * <p>To use, construct a spy of the desired {@link BackupAgent}, spying on the methods of interest.
 * Then, where you need to pass the agent, use {@link ForwardingBackupAgent#forward(BackupAgent)}
 * with the spy.
 */
public class ForwardingBackupAgent extends BackupAgent {
    /** Returns a {@link BackupAgent} that forwards method calls to {@code backupAgent}. */
    public static BackupAgent forward(BackupAgent backupAgent) {
        return new ForwardingBackupAgent(backupAgent);
    }

    private final BackupAgent mBackupAgent;

    private ForwardingBackupAgent(BackupAgent backupAgent) {
        mBackupAgent = backupAgent;
    }

    @Override
    public void onCreate() {
        mBackupAgent.onCreate();
    }

    @Override
    public void onDestroy() {
        mBackupAgent.onDestroy();
    }

    @Override
    public void onBackup(
            ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState)
            throws IOException {
        mBackupAgent.onBackup(oldState, data, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        mBackupAgent.onRestore(data, appVersionCode, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, long appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        mBackupAgent.onRestore(data, appVersionCode, newState);
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        mBackupAgent.onFullBackup(data);
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        mBackupAgent.onQuotaExceeded(backupDataBytes, quotaBytes);
    }

    @Override
    public void onRestoreFile(
            ParcelFileDescriptor data, long size, File destination, int type, long mode, long mtime)
            throws IOException {
        mBackupAgent.onRestoreFile(data, size, destination, type, mode, mtime);
    }

    @Override
    protected void onRestoreFile(
            ParcelFileDescriptor data,
            long size,
            int type,
            String domain,
            String path,
            long mode,
            long mtime)
            throws IOException {
        mBackupAgent.onRestoreFile(data, size, type, domain, path, mode, mtime);
    }

    @Override
    public void onRestoreFinished() {
        mBackupAgent.onRestoreFinished();
    }

    @Override
    public void attach(Context context) {
        mBackupAgent.attach(context);
    }
}
