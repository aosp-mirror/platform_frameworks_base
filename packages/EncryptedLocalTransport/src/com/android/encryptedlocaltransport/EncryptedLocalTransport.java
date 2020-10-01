/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.encryptedlocaltransport;

import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import com.android.localtransport.LocalTransport;
import com.android.localtransport.LocalTransportParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class EncryptedLocalTransport extends LocalTransport {
    private static final String TAG = "EncryptedLocalTransport";
    private static final int BACKUP_BUFFER_SIZE = 32 * 1024; // 32 KB.

    public EncryptedLocalTransport(Context context,
            LocalTransportParameters parameters) {
        super(context, parameters);
    }

    @Override
    public int performBackup(
            PackageInfo packageInfo, ParcelFileDescriptor data, int flags) {
        File packageFile;
        try {
            StructStat stat = Os.fstat(data.getFileDescriptor());
            if (stat.st_size > KEY_VALUE_BACKUP_SIZE_QUOTA) {
                Log.w(TAG, "New datastore size " + stat.st_size
                        + " exceeds quota " + KEY_VALUE_BACKUP_SIZE_QUOTA);
                return TRANSPORT_QUOTA_EXCEEDED;
            }
        } catch (ErrnoException e) {
            Log.w(TAG, "Failed to stat the backup input file: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        clearBackupData(packageInfo);

        try (InputStream in = new FileInputStream(data.getFileDescriptor())) {
            packageFile = new File(mCurrentSetIncrementalDir, packageInfo.packageName);
            Files.copy(in, packageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.w(TAG, "Failed to save backup data to file: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        return TRANSPORT_OK;
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outFd) {
        if (mRestorePackages == null) {
            throw new IllegalStateException("startRestore not called");
        }
        if (mRestorePackage < 0) {
            throw new IllegalStateException("nextRestorePackage not called");
        }
        if (mRestoreType != RestoreDescription.TYPE_KEY_VALUE) {
            throw new IllegalStateException("getRestoreData(fd) for non-key/value dataset");
        }

        try(OutputStream out = new FileOutputStream(outFd.getFileDescriptor())) {
            File packageFile = new File(mRestoreSetIncrementalDir,
                    mRestorePackages[mRestorePackage].packageName);
            Files.copy(packageFile.toPath(), out);
        } catch (IOException e) {
            Log.d(TAG, "Failed to transfer restore data: " + e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        return BackupTransport.TRANSPORT_OK;
    }

    @Override
    protected boolean hasRestoreDataForPackage(String packageName) {
        File contents = (new File(mRestoreSetIncrementalDir, packageName));
        return contents.exists() && contents.length() != 0;

    }
}
